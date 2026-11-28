package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.application.model.*;
import dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

/** Hosted PostgreSQL only: original operation ownership, lock order and bounded catalog SQL. */
class StripeSubscriptionOperationIntegrationTest extends SubscriptionFactFixture {
    @Autowired ProviderOperationStore steps;
    @Autowired ProviderCatalogMappingPort mappings;
    Subscription original;SubscriptionOperation operation;
    @BeforeEach void stripeCreateFixture(){
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_billing',credentials_ref='stripe-sandbox-default' where id=?",account.id().value());
        account=accounts.findById(account.id()).orElseThrow();
        var reserved=commands.reserveCreate(command("original-create"));original=reserved.subscription();operation=reserved.operation();
    }
    ProviderOperation reserve(){return steps.reserve(account,ProviderOperation.Kind.SUBSCRIPTION_CREATE,operation.id(),operation.providerOperationKey(),"immutable-create-facts");}
    ProviderOperation claim(ProviderOperation p){return steps.claim(p.id(),"owner",Instant.now(),Duration.ofSeconds(60)).orElseThrow();}
    @Test void onlyOriginalCreateOperationOwnsTheDurableStep(){
        var reserved=reserve();assertThat(reserve()).isEqualTo(reserved);
        assertThat(reserved.resourceId()).isEqualTo(operation.id());
        assertThatThrownBy(()->steps.reserve(account,ProviderOperation.Kind.SUBSCRIPTION_CREATE,original.id(),operation.providerOperationKey(),"immutable-create-facts")).isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->steps.reserve(account,ProviderOperation.Kind.SUBSCRIPTION_CREATE,operation.id(),"replacement-key","immutable-create-facts")).isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->steps.reserve(account,ProviderOperation.Kind.SUBSCRIPTION_CREATE,operation.id(),operation.providerOperationKey(),"different-price")).isInstanceOf(PaymentApplicationException.class);
        var claimed=claim(reserved);var ack=steps.acknowledge(claimed.id(),"owner",claimed.version(),"sub_owned");
        assertThat(ack.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(sub(original.id()).billingStatus()).isEqualTo(Subscription.Status.INCOMPLETE);assertThat(count("payments")).isZero();
    }
    @Test void wrongKindUnattachedAndForeignCustomerProofsAreRefused(){
        jdbc.update("update subscription_operations set kind='SETUP' where id=?",operation.id());
        assertThatThrownBy(this::reserve).isInstanceOf(PaymentApplicationException.class);
        jdbc.update("update subscription_operations set kind='CREATE',subscription_id=null where id=?",operation.id());
        assertThatThrownBy(this::reserve).isInstanceOf(PaymentApplicationException.class);
        jdbc.update("update subscription_operations set subscription_id=? where id=?",original.id(),operation.id());
        customers.save(customer.anonymize(INITIAL_TIME));
        assertThatThrownBy(this::reserve).isInstanceOf(PaymentApplicationException.class);
    }
    @Test void acceptedIncompleteReceiptPinsIdentityWithoutPaidEntitlement(){
        var claimed=claim(reserve());steps.acknowledge(claimed.id(),"owner",claimed.version(),"sub_owned");
        var result=commands.recordReply(operation.id(),new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Reply(
            dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Outcome.ACCEPTED,"sub_owned",null));
        var stored=sub(original.id());assertThat(result.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);assertThat(stored.providerReference()).isEqualTo("sub_owned");assertThat(stored.billingStatus()).isEqualTo(Subscription.Status.INCOMPLETE);
        assertThat(stored.entitledPrice()).isNull();assertThat(stored.paidThrough()).isNull();assertThat(count("payments")).isZero();assertThat(count("subscription_billing_records")).isZero();
    }
    @Test void acknowledgementAndRecoveryRevalidateOwnerAndExistingReference(){
        var step=claim(reserve());jdbc.update("update subscriptions set provider_reference='sub_foreign' where id=?",original.id());
        assertThatThrownBy(()->steps.acknowledge(step.id(),"owner",step.version(),"sub_owned")).isInstanceOf(PaymentApplicationException.class);
        var unknown=steps.unknown(step.id(),"owner",step.version());
        assertThatThrownBy(()->steps.bindRecovered(unknown,"sub_owned",unknown.fingerprint())).isInstanceOf(PaymentApplicationException.class);
        jdbc.update("update subscriptions set provider_reference=null where id=?",original.id());
        jdbc.update("update payment_accounts set external_account_id='acct_foreign' where id=?",account.id().value());
        assertThatThrownBy(()->steps.bindRecovered(unknown,"sub_owned",unknown.fingerprint())).isInstanceOf(PaymentApplicationException.class);
    }
    @Test void recoveredOriginalCannotRekeyAndFutureKindsStayClosed(){
        var claimed=claim(reserve());var unknown=steps.unknown(claimed.id(),"owner",claimed.version());
        var recovered=steps.bindRecovered(unknown,"sub_owned",unknown.fingerprint());assertThat(recovered.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThatThrownBy(()->steps.bindRecovered(recovered,"sub_other",recovered.fingerprint())).isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->steps.reserve(account,ProviderOperation.Kind.INVOICE_PAY,operation.id(),"future-kind","facts")).isInstanceOf(PaymentApplicationException.class);
    }
    @Test void lifecycleOperationLockDoesNotInvertProviderAccountOwnershipLocks() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            tx.required(() -> {
                assertThat(operations.lock(operation.id())).isEqualTo(operation);
                assertLifecycleRowIsLocked(executor);
                var future = executor.submit(() -> {
                    var claimed = claim(reserve());
                    assertThat(claimed.status()).isEqualTo(ProviderOperation.Status.DISPATCHING);
                    return steps.acknowledge(claimed.id(), "owner", claimed.version(), "sub_owned");
                });
                try {
                    var acknowledged = future.get(3, TimeUnit.SECONDS);
                    assertThat(acknowledged.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
                    assertThat(acknowledged.resourceId()).isEqualTo(operation.id());
                    assertThat(acknowledged.accountId()).isEqualTo(account.id());
                    assertThat(acknowledged.kind()).isEqualTo(ProviderOperation.Kind.SUBSCRIPTION_CREATE);
                    assertThat(acknowledged.operationKey()).isEqualTo(operation.providerOperationKey());
                    assertThat(acknowledged.providerReference()).isEqualTo("sub_owned");
                    assertThat(steps.findById(acknowledged.id())).contains(acknowledged);
                    assertLifecycleRowIsLocked(executor);
                    assertThat(operations.lock(operation.id())).isEqualTo(operation);
                } catch (Exception failure) {
                    throw new AssertionError("Provider claim/ACK blocked behind lifecycle lock", failure);
                }
                return null;
            });
        }
        assertThat(sub(original.id()).billingStatus()).isEqualTo(Subscription.Status.INCOMPLETE);
        assertThat(sub(original.id()).providerReference()).isNull();
        assertThat(sub(original.id()).entitledPrice()).isNull();
        assertThat(sub(original.id()).paidThrough()).isNull();
        assertThat(count("payments")).isZero();
        assertThat(count("subscription_billing_records")).isZero();
    }
    void assertLifecycleRowIsLocked(ExecutorService executor) {
        // A separate SQL transaction must observe the original row lock before and after ACK.
        // NOWAIT avoids sleeps and proves a real held lock rather than a merely scheduled worker.
        var probe = executor.submit(() -> catchThrowable(() -> tx.required(() ->
            jdbc.queryForObject("select id from subscription_operations where id=? for update nowait", UUID.class, operation.id()))));
        try {
            assertThat(probe.get(3, TimeUnit.SECONDS)).isNotNull().hasRootCauseInstanceOf(SQLException.class)
                .satisfies(failure -> {
                    while (failure.getCause() != null) failure = failure.getCause();
                    assertThat(((SQLException) failure).getSQLState()).isEqualTo("55P03");
                });
        } catch (Exception failure) {
            throw new AssertionError("Could not prove the lifecycle row lock is held", failure);
        }
    }
    @Test void siteAccountSwitchDoesNotRelocateTheOriginalSubscriptionStep(){
        var other=UUID.randomUUID();jdbc.update("insert into payment_accounts values (?,'other-billing','STRIPE','SANDBOX','ACTIVE','acct_other','stripe-sandbox-default',now(),0)",other);
        jdbc.update("update sites set payment_account_id=? where id=?",other,site.value());
        assertThat(reserve().accountId()).isEqualTo(account.id());
    }
    @Test void boundedMappingIncludesArchivedOriginalAccountHistory(){
        map(price.value(),"price_initial");var archived=addPrice(2,"ARCHIVED");map(archived,"price_archived");
        var rows=mappings.findPricesForProduct(account.id(),site,original.productId());
        assertThat(rows).extracting(m->m.priceRef().value()).containsExactlyInAnyOrder("price_initial","price_archived");
        assertThat(mappings.findPricesForProduct(new PaymentAccountId(UUID.randomUUID()),site,original.productId())).isEmpty();
        assertThat(mappings.findPricesForProduct(account.id(),new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),original.productId())).isEmpty();
    }
    @Test void oneHundredAndOneMappingsRefuseInsteadOfTruncating(){
        map(price.value(),"price_initial");for(int version=2;version<=101;version++)map(addPrice(version,"ARCHIVED"),"price_version"+version);
        assertThatThrownBy(()->mappings.findPricesForProduct(account.id(),site,original.productId())).isInstanceOf(PaymentApplicationException.class);
    }
    void map(UUID id,String remote){mappings.save(new ProviderCatalogMapping(account.id(),original.productId(),new ProviderProductRef("prod_owned"),new dev.genericpayments.platform.catalog.domain.PriceId(id),new ProviderPriceRef(remote),INITIAL_TIME));}
    UUID addPrice(int version,String status){var id=UUID.randomUUID();jdbc.update("""
        insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at)
        select ?,site_id,product_id,price_key,?,'SUBSCRIPTION',amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,?,published_at,created_at,updated_at from prices where id=?
        """,id,version,status,price.value());return id;}
}
