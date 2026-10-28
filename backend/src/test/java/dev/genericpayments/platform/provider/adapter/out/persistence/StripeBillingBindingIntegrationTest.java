package dev.genericpayments.platform.provider.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.service.ProviderTestFixture;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** Requires hosted PostgreSQL; never part of the Docker-free local command. */
class StripeBillingBindingIntegrationTest extends ProviderTestFixture {
    @Autowired StripeBillingBindingPort bindings;
    @Autowired ProviderOperationStore operations;
    final CustomerId customer = new CustomerId(UUID.randomUUID());
    PaymentAccount billingAccount;
    static final Duration LEASE = Duration.ofSeconds(60);
    @BeforeEach void billingFixture() {
        jdbc.update("update payment_accounts set provider='STRIPE', external_account_id='acct_billing', credentials_ref='stripe-sandbox-default' where id=?", account.id().value());
        billingAccount = new PaymentAccount(account.id(), account.key(), PaymentProviderType.STRIPE,
            PaymentAccountMode.SANDBOX, PaymentAccountStatus.ACTIVE, "acct_billing", "stripe-sandbox-default");
        addCustomer(site, customer);
    }
    // Catches a random per-subscription resource UUID/key defeating uniqueness under racing callers.
    @Test void concurrentReservationsShareTheOriginalCustomerOperation() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<ProviderOperation> call = () -> { barrier.await(10, TimeUnit.SECONDS); return reserve(customer); };
            var one = pool.submit(call); var two = pool.submit(call);
            assertThat(one.get(20, TimeUnit.SECONDS)).isEqualTo(two.get(20, TimeUnit.SECONDS));
        }
        assertThat(count("provider_operations")).isEqualTo(1);
    }
    // Catches email lookup/coalescing and resource scope validation being removed.
    @Test void sameEmailCustomersAcrossSitesAndAccountsCannotShareBindings() {
        var secondSite = new SiteId(UUID.randomUUID()); var secondCustomer = new CustomerId(UUID.randomUUID());
        var otherAccount = PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()), "other-billing",
            PaymentProviderType.STRIPE, PaymentAccountMode.SANDBOX, "acct_other", "stripe-sandbox-default");
        jdbc.update("insert into payment_accounts values (?, ?, 'STRIPE','SANDBOX','ACTIVE', ?, ?, now(),0)", otherAccount.id().value(), otherAccount.key(), otherAccount.externalAccountId(), otherAccount.credentialsRef());
        jdbc.update("insert into sites(id,site_key,display_name,status,payment_account_id,payment_method_policy,created_at,updated_at,version) values (?,'other-site','Other','ACTIVE',?,'AUTOMATIC',now(),now(),0)", secondSite.value(), otherAccount.id().value());
        addCustomer(secondSite, secondCustomer);
        var first = ack(reserve(customer), "cus_first");
        var other = operations.reserve(otherAccount, ProviderOperation.Kind.valueOf("CUSTOMER_CREATE"), secondCustomer.value(), "customer-other", "other-facts");
        var otherClaim = operations.claim(other.id(), "other-owner", Instant.now(), LEASE).orElseThrow();
        other = operations.acknowledge(other.id(), "other-owner", otherClaim.version(), "cus_second");
        var firstBinding = bindings.bindCustomer(billingAccount.id(), site, customer, "cus_first", first.id());
        bindings.bindCustomer(otherAccount.id(), secondSite, secondCustomer, "cus_second", other.id());
        assertThat(bindings.findCustomer(billingAccount.id(), site, customer)).contains(firstBinding);
        assertThat(bindings.findCustomer(billingAccount.id(), secondSite, secondCustomer)).isEmpty();
        conflict(() -> operations.reserve(billingAccount, ProviderOperation.Kind.valueOf("CUSTOMER_CREATE"), secondCustomer.value(), "foreign", "facts"));
        var otherId = other.id();
        conflict(() -> bindings.bindCustomer(billingAccount.id(), site, customer, "cus_second", otherId));
        conflict(() -> bindings.bindCustomer(billingAccount.id(), secondSite, customer, "cus_first", first.id()));
        assertThat(count("stripe_billing_customers")).isEqualTo(2);
    }
    // Catches caller metadata/reference being allowed to establish a mapping without dispatch ACK.
    @Test void bindingRequiresAcknowledgedOriginalOperationAndExactReference() {
        var op = reserve(customer);
        conflict(() -> bindings.bindCustomer(billingAccount.id(), site, customer, "cus_unproved", op.id()));
        var acknowledged = ack(op, "cus_owned");
        conflict(() -> bindings.bindCustomer(billingAccount.id(), site, customer, "cus_other", op.id()));
        var binding = bindings.bindCustomer(billingAccount.id(), site, customer, "cus_owned", acknowledged.id());
        assertThat(bindings.bindCustomer(billingAccount.id(), site, customer, "cus_owned", acknowledged.id())).isEqualTo(binding);
    }
    // Catches losing the durable ACK when local binding rolls back; retry must use the same operation.
    @Test void acknowledgedReceiptSurvivesBindingRollback() {
        var acknowledged = ack(reserve(customer), "cus_owned");
        jdbc.execute("create function task2_reject_binding() returns trigger language plpgsql as $$ begin raise exception 'injected binding rollback' using errcode='23514'; end; $$");
        jdbc.execute("create trigger task2_reject_binding before insert on stripe_billing_customers for each row execute function task2_reject_binding()");
        try { conflict(() -> bindings.bindCustomer(billingAccount.id(), site, customer, "cus_owned", acknowledged.id())); }
        finally { jdbc.execute("drop trigger task2_reject_binding on stripe_billing_customers"); jdbc.execute("drop function task2_reject_binding()"); }
        assertThat(operations.findById(acknowledged.id())).contains(acknowledged);
        assertThat(bindings.bindCustomer(billingAccount.id(), site, customer, "cus_owned", acknowledged.id()).creationOperationId()).isEqualTo(acknowledged.id());
        assertThat(count("provider_operations")).isEqualTo(1);
    }
    // Catches stale owner/version completing a Customer write or bypassing the 23h cutoff.
    @Test void staleLeaseAndExpiredWindowCannotBindOrRekey() {
        var op = reserve(customer); var now = Instant.now();
        var stale = operations.claim(op.id(), "stale", now.minusSeconds(120), LEASE).orElseThrow();
        var live = operations.claim(op.id(), "live", now, LEASE).orElseThrow();
        conflict(() -> operations.acknowledge(op.id(), "stale", stale.version(), "cus_stale"));
        conflict(() -> operations.bindRecovered(live, "cus_unproved", live.fingerprint()));
        operations.unknown(op.id(), "live", live.version());
        assertThat(operations.claim(op.id(), "too-late", stale.retryBefore(), LEASE)).isEmpty();
        conflict(() -> operations.reserve(billingAccount, ProviderOperation.Kind.valueOf("CUSTOMER_CREATE"), customer.value(), "replacement", "facts"));
        assertThat(bindings.findCustomer(billingAccount.id(), site, customer)).isEmpty();
    }
    // Catches ACK/recovery failing to revalidate Account/Site/Customer after dispatch.
    @Test void revokedCustomerCannotAcknowledgeOrRecoverOrBind() {
        var op = reserve(customer); var claimed = operations.claim(op.id(), "owner", Instant.now(), LEASE).orElseThrow();
        jdbc.update("update customers set status='ANONYMIZED',email_ciphertext=null,email_nonce=null,email_key_version=null,email_digest=null,email_digest_key_version=null,anonymized_at=now(),updated_at=now() where id=?", customer.value());
        conflict(() -> operations.acknowledge(op.id(), "owner", claimed.version(), "cus_revoked"));
        var unknown = operations.unknown(op.id(), "owner", claimed.version());
        conflict(() -> operations.bindRecovered(unknown, "cus_revoked", unknown.fingerprint()));
        assertThat(count("stripe_billing_customers")).isZero();
    }
    // Catches a persisted Account identity change being accepted between dispatch and receipt commit.
    @Test void changedAccountAfterDispatchCannotAcknowledgeOrRecover() {
        var op = reserve(customer); var claimed = operations.claim(op.id(), "owner", Instant.now(), LEASE).orElseThrow();
        jdbc.update("update payment_accounts set external_account_id='acct_foreign' where id=?", billingAccount.id().value());
        conflict(() -> operations.acknowledge(op.id(), "owner", claimed.version(), "cus_foreign"));
        var unknown = operations.unknown(op.id(), "owner", claimed.version());
        conflict(() -> operations.bindRecovered(unknown, "cus_foreign", unknown.fingerprint()));
        assertThat(count("stripe_billing_customers")).isZero();
    }
    // Catches permissive generic resource handling for Billing kinds owned by later tasks.
    @Test void futureBillingKindsRefuseUnprovedResources() {
        for (var kind : ProviderOperation.Kind.values()) {
            if (Set.of(ProviderOperation.Kind.PRODUCT_CREATE, ProviderOperation.Kind.PRICE_CREATE,
                ProviderOperation.Kind.PAYMENT_PREPARE, ProviderOperation.Kind.REFUND_CREATE, ProviderOperation.Kind.valueOf("CUSTOMER_CREATE")).contains(kind)) continue;
            conflict(() -> operations.reserve(billingAccount, kind, customer.value(), "unsupported-" + kind, "facts"));
        }
        assertThat(count("provider_operations")).isZero();
    }
    // Catches manual/untrusted rows bypassing the future-kind resource gate at ACK/recovery.
    @Test void futureBillingKindCannotAcknowledgeOrRecoverAnUnprovedReceipt() {
        var id = UUID.randomUUID();
        jdbc.update("""
            insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,
                resource_id,operation_key,fingerprint,status,first_dispatched_at,retry_before,lease_owner,lease_expires_at)
            values (?,?,'acct_billing','stripe-sandbox-default','SUBSCRIPTION_UPDATE',?,
                'unproved-update','unproved-facts','DISPATCHING',now(),now()+interval '23 hours','owner',now()+interval '60 seconds')
            """, id, billingAccount.id().value(), customer.value());
        var receipt = operations.findById(id).orElseThrow();
        conflict(() -> operations.acknowledge(id, "owner", receipt.version(), "sub_unproved"));
        var unknown = operations.unknown(id, "owner", receipt.version());
        conflict(() -> operations.bindRecovered(unknown, "sub_unproved", unknown.fingerprint()));
        assertThat(operations.findById(id).orElseThrow().status()).isEqualTo(ProviderOperation.Status.UNKNOWN);
    }
    // Catches missing account/reference uniqueness and composite original-operation FK proof.
    @Test void databaseRejectsForeignOriginalReferenceAndCustomerScope() {
        var op = ack(reserve(customer), "cus_owned");
        assertThatThrownBy(() -> jdbc.update("insert into stripe_billing_customers(id,payment_account_id,site_id,customer_id,provider_reference,creation_operation_id) values (?,?,?,?,?,?)",
            UUID.randomUUID(), billingAccount.id().value(), site.value(), customer.value(), "cus_unproved", op.id())).isInstanceOf(DataIntegrityViolationException.class);
        var binding = bindings.bindCustomer(billingAccount.id(), site, customer, "cus_owned", op.id());
        assertThatThrownBy(() -> jdbc.update("update stripe_billing_customers set provider_reference='cus_changed' where id=?", binding.id())).isInstanceOf(DataIntegrityViolationException.class);
    }
    ProviderOperation reserve(CustomerId id) { return operations.reserve(billingAccount, ProviderOperation.Kind.valueOf("CUSTOMER_CREATE"), id.value(), "customer-" + id.value(), "facts-" + id.value()); }
    ProviderOperation ack(ProviderOperation op, String ref) { var c = operations.claim(op.id(), "owner", Instant.now(), LEASE).orElseThrow(); return operations.acknowledge(op.id(), "owner", c.version(), ref); }
    void addCustomer(SiteId s, CustomerId c) {
        jdbc.update("insert into customers(id,site_id,status,email_ciphertext,email_nonce,email_key_version,email_digest,email_digest_key_version,created_at,updated_at) values (?,?,'ACTIVE',decode(repeat('00',16),'hex'),decode(repeat('00',12),'hex'),'test-only','same_email_digest','v1',now(),now())", c.value(), s.value());
    }
    static void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) { assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class, e -> assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT")); }
}
