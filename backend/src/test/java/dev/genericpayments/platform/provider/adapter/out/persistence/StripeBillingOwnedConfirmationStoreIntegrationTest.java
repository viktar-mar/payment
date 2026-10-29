package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.service.ProviderTestFixture;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Hosted PostgreSQL only. Local permission is compilation, never execution. Fixtures are negative/store tests. */
class StripeBillingOwnedConfirmationStoreIntegrationTest extends ProviderTestFixture {
    @Autowired StripeBillingClockStore clocks;
    @Autowired StripeBillingClockSubscriptionStore owned;
    @Autowired ProviderOperationStore operations;
    @Autowired StripeBillingBindingPort bindings;
    @Autowired PlatformTransactionManager transactions;
@Autowired dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionActionStore actions;
@Autowired dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionStore subscriptions;
@Autowired dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionOperationStore lifecycleOperations;
@Autowired dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionConfirmationStore confirmations;
    PaymentAccount billing;
    CustomerId customer=new CustomerId(UUID.randomUUID());
    ReadyClock ready;
    UUID subscription,operation;
    @BeforeEach void fixture() {
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_clock',credentials_ref='clock-alias' where id=?",account.id().value());
        billing=PaymentAccount.create(account.id(),account.key(),PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_clock","clock-alias");
        jdbc.update("insert into customers(id,site_id,status,email_ciphertext,email_nonce,email_key_version,email_digest,email_digest_key_version,created_at,updated_at) values (?,?,'ACTIVE',decode(repeat('00',16),'hex'),decode(repeat('00',12),'hex'),'test-only',?,'v1',now(),now())",customer.value(),site.value(),"digest-"+customer.value());
        var owner=clocks.admitFresh(billing,site,customer,UUID.randomUUID(),"api-test",1000,Instant.now());
        var step=operations.reserve(billing,ProviderOperation.Kind.TEST_CLOCK_CREATE,owner.intent(),owner.createKey(),owner.createFingerprint());
        var claim=operations.claim(step.id(),"clock",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        var ack=operations.acknowledge(step.id(),"clock",claim.version(),"clock_owned");
        ready=clocks.recordReady(owner,ack,new StripeBillingClockStore.ReadyObservation(StripeBillingClockStore.ReadyState.READY,"clock_owned",1000,2000,3000,Instant.now()));
        step=operations.reserveOwnedBillingCustomer(billing,ready);claim=operations.claim(step.id(),"customer",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        ack=operations.acknowledge(step.id(),"customer",claim.version(),"cus_owned");
        bindings.bindCustomer(billing.id(),site,customer,"cus_owned",ack.id());
        lifecycle();
    }
    void lifecycle() {
        var product=UUID.randomUUID();var price=UUID.randomUUID();subscription=UUID.randomUUID();operation=UUID.randomUUID();
        String key="owned-"+product.toString().substring(0,8);
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,?,'Owned fixture','ACTIVE',now(),now())",product,site.value(),key);
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?, ?,1,'SUBSCRIPTION',1000,'EUR','MONTH',0,'OPTIONAL_DURING_TRIAL','NONE','PUBLISHED',now(),now(),now())",price,site.value(),product,key);
        jdbc.update("insert into provider_product_mappings(payment_account_id,product_id,provider_product_id,created_at) values (?,?,?,now())",billing.id().value(),product,"prod_"+product.toString().replace("-",""));
        jdbc.update("insert into provider_price_mappings(payment_account_id,price_id,provider_price_id,created_at) values (?,?,?,now())",billing.id().value(),price,"price_"+price.toString().replace("-",""));
        jdbc.update("insert into subscriptions(id,site_id,customer_id,product_id,payment_account_id,currency,billing_price_id,billing_price_key,billing_price_version,billing_amount_minor,billing_interval,billing_trial_days,billing_trial_policy,billing_tax_mode,billing_status,created_at,updated_at) values (?,?,?,?,?,'EUR',?,?,1,1000,'MONTH',0,'OPTIONAL_DURING_TRIAL','NONE','INCOMPLETE',now(),now())",subscription,site.value(),customer.value(),product,billing.id().value(),price,key);
        jdbc.update("insert into subscription_operations(id,site_id,customer_id,subscription_id,kind,status,authority_scope,key_digest,fingerprint,provider_operation_key,expected_revision,policy_version,created_at,updated_at) values (?,?,?,?,'CREATE','RESERVED','customer:clock',?,?,?,0,1,now(),now())",operation,site.value(),customer.value(),subscription,"key-"+operation,"v1.paid."+"a".repeat(64),"subscription:"+billing.id().value()+":"+operation+":CREATE");
    }
    OwnedClockSubscription admit(){return owned.admitFresh(billing,ready.owner(),subscription,operation,Instant.now());}
    ProviderOperation reserve(OwnedClockSubscription i){return operations.reserveOwnedBillingSubscription(billing,i);}
    ProviderOperation claim(OwnedClockSubscription i){return operations.claim(reserve(i).id(),"winner",Instant.now(),Duration.ofSeconds(60)).orElseThrow();}
    OwnedClockSubscriptionReceipt ack(OwnedClockSubscription i,ProviderOperation c){return operations.acknowledgeOwnedBillingSubscription(i,c,"sub_owned",4000,"si_owned",900,1100,Instant.now());}
    OwnedClockSubscriptionReceipt published(){
        var i=admit();var receipt=ack(i,claim(i));
        jdbc.update("insert into site_subscription_policies(site_id,enabled,version,grace_days) values (?,true,1,0) on conflict(site_id) do update set enabled=true,version=1",site.value());
        jdbc.update("update subscription_operations set status='PROCESSING',provider_reference='sub_owned',version=version+1 where id=?",operation);
        jdbc.update("update subscriptions set provider_reference='sub_owned',revision=revision+1 where id=?",subscription);
        return receipt;
    }
    dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant root(OwnedClockSubscriptionReceipt r){
        var i=r.intent();var at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return new dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant(UUID.randomUUID(),site,customer,billing.id(),subscription,operation,"v1",
            new dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.Binding(r.providerOperation(),i.operationKey(),OwnedInitialConfirmationProof.hash(r,"in_owned","inpay_owned","pi_owned"),r.reference(),i.customerReference(),"in_owned","inpay_owned","pi_owned","owned-initial-v1",null,i.id()),
            UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""),at,at.plusSeconds(600),null,null,null,null,null);
    }
    <T>T tx(java.util.function.Supplier<T> work){return new TransactionTemplate(transactions).execute(status->work.get());}
    void reserve(dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant g,OwnedClockSubscriptionReceipt r){tx(()->{actions.reserveOwnedInitialBilling(g,r);return null;});}
    OwnedClockSubscriptionReceipt require(OwnedClockSubscriptionReceipt r){return tx(()->owned.requireConfirmationReceipt(r,billing,subscriptions.find(site,subscription).orElseThrow(),lifecycleOperations.find(site,subscription,operation).orElseThrow()));}
    @Test void currentPersistedReceiptGuardsAndDedicatedRootRoundTrip(){var r=published();assertThat(require(r)).isEqualTo(r);var g=root(r);reserve(g,r);assertThat(tx(()->actions.billing(g.tokenDigest()))).contains(g);}
    @Test void originalPeriodMayBeAbsentOrExactlyTheReceipt(){var r=published();assertThat(require(r)).isEqualTo(r);jdbc.update("update subscriptions set current_period_start=to_timestamp(?),current_period_end=to_timestamp(?) where id=?",r.periodStart(),r.periodEnd(),subscription);assertThat(require(r)).isEqualTo(r);jdbc.update("update subscriptions set current_period_end=to_timestamp(?) where id=?",r.periodEnd()+1,subscription);assertThatThrownBy(()->require(r)).isInstanceOf(PaymentApplicationException.class);}
    @Test void mandatoryGuardsRejectOutsideTransactionIncludingSpringProxy(){var r=published();assertThatThrownBy(()->owned.requireOrdinaryConfirmation(billing.id(),site,customer)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);assertThatThrownBy(()->owned.requireConfirmationReceipt(r,billing,subscriptions.find(site,subscription).orElseThrow(),lifecycleOperations.find(site,subscription,operation).orElseThrow())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);assertThatThrownBy(()->actions.reserveOwnedInitialBilling(root(r),r)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);}
    @Test void ordinaryGuardRejectsActiveAndStoppedAdmittedOwner(){published();assertThatThrownBy(()->tx(()->{owned.requireOrdinaryConfirmation(billing.id(),site,customer);return null;})).isInstanceOf(PaymentApplicationException.class);clocks.stop(ready.owner(),StripeBillingClockStore.StopReason.AUTHORITY_REVOKED,Instant.now());assertThatThrownBy(()->tx(()->{owned.requireOrdinaryConfirmation(billing.id(),site,customer);return null;})).isInstanceOf(PaymentApplicationException.class);}
    @Test void stopDeniesOriginalReceiptAndNewRoot(){var r=published();clocks.stop(ready.owner(),StripeBillingClockStore.StopReason.AUTHORITY_REVOKED,Instant.now());assertThatThrownBy(()->require(r)).isInstanceOf(PaymentApplicationException.class);assertThatThrownBy(()->reserve(root(r),r)).isInstanceOf(PaymentApplicationException.class);assertThat(count("subscription_action_capabilities")).isZero();}
    @Test void changedAccountAndPolicyDenyCurrentAuthority(){var r=published();jdbc.update("update payment_accounts set credentials_ref='rotated-alias' where id=?",billing.id().value());assertThatThrownBy(()->require(r)).isInstanceOf(PaymentApplicationException.class);jdbc.update("update payment_accounts set credentials_ref='clock-alias' where id=?",billing.id().value());jdbc.update("update site_subscription_policies set version=version+1 where site_id=?",site.value());assertThatThrownBy(()->require(r)).isInstanceOf(PaymentApplicationException.class);}
    @Test void callerForgedReceiptNeverBecomesAuthority(){var r=published();var wrong=new OwnedClockSubscriptionReceipt(r.intent(),r.providerOperation(),r.reference(),r.providerCreated()+1,r.itemReference(),r.periodStart(),r.periodEnd(),r.acknowledgedAt());assertThatThrownBy(()->require(wrong)).isInstanceOf(PaymentApplicationException.class);assertThatThrownBy(()->reserve(root(r),wrong)).isInstanceOf(PaymentApplicationException.class);}
    @Test void oneOwnedIntentCannotIssueSecondRoot(){var r=published();reserve(root(r),r);assertThatThrownBy(()->reserve(root(r),r)).isInstanceOf(DataIntegrityViolationException.class);assertThat(count("subscription_action_capabilities")).isEqualTo(1);}
    @Test void ordinaryReserveAndV1BindingClaimRefuseOwnedLane(){var r=published();var g=root(r);assertThatThrownBy(()->tx(()->{actions.reserveBilling(g);return null;})).isInstanceOf(PaymentApplicationException.class);reserve(g,r);assertThatThrownBy(()->tx(()->{actions.bindSession(g.tokenDigest(),UUID.randomUUID(),"https://shop.test",g.expiresAt(),g.issuedAt());return null;})).isInstanceOf(PaymentApplicationException.class);assertThatThrownBy(()->tx(()->actions.claimBilling(site,customer,billing.id(),subscription,operation,g.tokenDigest(),UUID.randomUUID(),"https://shop.test","authority","a".repeat(64),"b".repeat(64),g.issuedAt()))).isInstanceOf(PaymentApplicationException.class);}
    @Test void ownedRootLinkageAndOriginalReceiptJoinsAreImmutable(){var r=published();var g=root(r);reserve(g,r);for(String change:List.of("billing_owned_intent=null,billing_schema='payment-v1'","billing_owned_intent='00000000-0000-0000-0000-000000000001'::uuid","billing_step_id='00000000-0000-0000-0000-000000000001'::uuid","billing_subscription_reference='sub_foreign',billing_step_reference='sub_foreign'"))assertThatThrownBy(()->jdbc.update("update subscription_action_capabilities set "+change+" where id=?",g.id())).isInstanceOf(DataIntegrityViolationException.class);}
    void insertRaw(dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant g,String schema,UUID ownedIntent,UUID providerStep){
        var b=g.binding();jdbc.update("""
            insert into subscription_action_capabilities(id,site_id,customer_id,payment_account_id,subscription_id,operation_id,purpose,token_digest,issued_at,expires_at,
             consent_purpose,recurring_consent,consent_policy_version,consent_at,billing_key_version,billing_step_id,billing_step_kind,billing_operation_key,billing_proof,
             billing_subscription_reference,billing_customer_reference,billing_invoice_reference,billing_allocation_reference,billing_intent_reference,billing_schema,
             billing_step_reference,billing_step_resource_id,billing_owned_intent)
            select ?,?,?,?, ?,o.id,'PROVIDER_CLIENT',?,?,?,'CREATE',true,o.policy_version,o.created_at,?,?,'SUBSCRIPTION_CREATE',?,?,?,?,?,?,?,?,?,o.id,?
             from subscription_operations o where o.id=?
            """,g.id(),g.site().value(),g.customer().value(),g.account().value(),g.subscription(),g.tokenDigest(),java.sql.Timestamp.from(g.issuedAt()),java.sql.Timestamp.from(g.expiresAt()),g.keyVersion(),providerStep,b.operationKey(),b.proof(),b.subscriptionReference(),b.customerReference(),b.invoiceReference(),b.allocationReference(),b.intentReference(),schema,b.subscriptionReference(),ownedIntent,g.operation());
    }
    @Test void sqlOwnedCreateCannotMasqueradeAsOrdinaryRoot(){var r=published();var g=root(r);assertThatThrownBy(()->insertRaw(g,"payment-v1",null,g.binding().stepId())).isInstanceOf(DataIntegrityViolationException.class);assertThat(count("subscription_action_capabilities")).isZero();}
    @Test void sqlForeignReceiptCannotClaimOwnedSchema(){var r=published();var g=root(r);assertThatThrownBy(()->insertRaw(g,"owned-initial-v1",UUID.randomUUID(),g.binding().stepId())).isInstanceOf(DataIntegrityViolationException.class);assertThatThrownBy(()->insertRaw(g,"owned-initial-v1",r.intent().id(),r.intent().customerCreationOperation())).isInstanceOf(DataIntegrityViolationException.class);assertThat(count("subscription_action_capabilities")).isZero();}
    @Test void sqlNonownedCreateCannotClaimOriginalOwnedSchema(){var r=published();lifecycle();var g=root(r);assertThatThrownBy(()->insertRaw(g,"owned-initial-v1",r.intent().id(),r.providerOperation())).isInstanceOf(DataIntegrityViolationException.class);assertThat(count("subscription_action_capabilities")).isZero();}
    @Test void ownedRootCannotBeDeletedSupersededOrReissuedAfterItsExpiry(){var r=published();var g=root(r);reserve(g,r);assertThatThrownBy(()->jdbc.update("delete from subscription_action_capabilities where id=?",g.id())).isInstanceOf(DataIntegrityViolationException.class);assertThatThrownBy(()->jdbc.update("update subscription_action_capabilities set superseded_at=expires_at where id=?",g.id())).isInstanceOf(DataIntegrityViolationException.class);var later=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant(UUID.randomUUID(),g.site(),g.customer(),g.account(),g.subscription(),g.operation(),g.keyVersion(),g.binding(),"e".repeat(64),g.expiresAt().plusSeconds(1),g.expiresAt().plusSeconds(601),null,null,null,null,null);assertThatThrownBy(()->insertRaw(later,"owned-initial-v1",r.intent().id(),r.providerOperation())).isInstanceOf(DataIntegrityViolationException.class);assertThat(count("subscription_action_capabilities")).isEqualTo(1);}
    @Test void ownedAttemptCannotInsertPredecessorOrDeclineRetry(){var r=published();var g=root(r);reserve(g,r);var c=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionConfirmationChain(g.id(),g.tokenDigest(),UUID.randomUUID(),"https://shop.test",g.expiresAt(),1,1);var a=dev.genericpayments.platform.subscriptions.application.model.SubscriptionConfirmationAttempt.ready(UUID.randomUUID(),g.id(),null,"v1",g.issuedAt(),g.expiresAt());tx(()->{confirmations.reserve(g,c,a);return null;});assertThatThrownBy(()->tx(()->{confirmations.insert(dev.genericpayments.platform.subscriptions.application.model.SubscriptionConfirmationAttempt.ready(UUID.randomUUID(),g.id(),a.id(),"v1",g.issuedAt(),g.expiresAt()));return null;})).isInstanceOf(DataIntegrityViolationException.class);}
    @Test void ownedChainAndAttemptCannotEraseConsumedHistoryOrInsertAlreadyClaimedAttempt(){
        var r=published();var g=root(r);reserve(g,r);var c=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionConfirmationChain(g.id(),g.tokenDigest(),UUID.randomUUID(),"https://shop.test",g.expiresAt(),1,1);
        tx(()->{jdbc.update("insert into subscription_confirmation_chains(id,operation_id,root_digest,session_id,origin,expires_at,policy_version,subscription_revision) values (?,?,?,?,?,?,?,?)",g.id(),g.operation(),g.tokenDigest(),c.sessionId(),c.origin(),java.sql.Timestamp.from(c.expiresAt()),c.policyVersion(),c.subscriptionRevision());return null;});
        assertThatThrownBy(()->jdbc.update("delete from subscription_confirmation_chains where id=?",g.id())).isInstanceOf(DataIntegrityViolationException.class);
        UUID attempt=UUID.randomUUID();assertThatThrownBy(()->jdbc.update("insert into subscription_confirmation_attempts(id,chain_id,key_version,issued_at,expires_at,state,claimed_at,confirm_key,token_digest,fingerprint,provider_key) values (?,?,'v1',?,?,'DISPATCHING',?,repeat('a',64),repeat('b',64),repeat('c',64),?)",attempt,g.id(),java.sql.Timestamp.from(g.issuedAt()),java.sql.Timestamp.from(g.expiresAt()),java.sql.Timestamp.from(g.issuedAt()),"billing-confirm-v2:"+attempt)).isInstanceOf(DataIntegrityViolationException.class);
        var a=dev.genericpayments.platform.subscriptions.application.model.SubscriptionConfirmationAttempt.ready(attempt,g.id(),null,"v1",g.issuedAt(),g.expiresAt());tx(()->{confirmations.insert(a);return null;});
        assertThatThrownBy(()->jdbc.update("delete from subscription_confirmation_attempts where id=?",attempt)).isInstanceOf(DataIntegrityViolationException.class);
        tx(()->{confirmations.save(a.claim("a".repeat(64),"b".repeat(64),"c".repeat(64)),0,g.issuedAt());return null;});
        assertThatThrownBy(()->jdbc.update("delete from subscription_confirmation_attempts where id=?",attempt)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("subscription_confirmation_attempts")).isEqualTo(1);
    }
    @Test void stoppedOwnerCannotPublishChainFromExistingRoot(){var r=published();var g=root(r);reserve(g,r);clocks.stop(ready.owner(),StripeBillingClockStore.StopReason.AUTHORITY_REVOKED,Instant.now());var c=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionConfirmationChain(g.id(),g.tokenDigest(),UUID.randomUUID(),"https://shop.test",g.expiresAt(),1,1);var a=dev.genericpayments.platform.subscriptions.application.model.SubscriptionConfirmationAttempt.ready(UUID.randomUUID(),g.id(),null,"v1",g.issuedAt(),g.expiresAt());assertThatThrownBy(()->tx(()->{confirmations.reserve(g,c,a);return null;})).isInstanceOf(DataIntegrityViolationException.class);assertThat(count("subscription_confirmation_chains")).isZero();}
    @Test void stopHoldingAccountWinsAgainstWaitingConfirmationGuard()throws Exception{
        var r=published();var stopped=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var holder=pool.submit(()->tx(()->{jdbc.queryForList("select id from payment_accounts where id=? for update",UUID.class,billing.id().value());jdbc.update("update stripe_billing_clock_intents set stopped_at=now(),stop_reason='AUTHORITY_REVOKED' where id=?",ready.owner().intent());stopped.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("gate timeout");}catch(InterruptedException e){throw new AssertionError(e);}return null;}));
            assertThat(stopped.await(10,TimeUnit.SECONDS)).isTrue();var waiter=pool.submit(()->require(r));
            try{assertThatThrownBy(()->waiter.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}
            holder.get(10,TimeUnit.SECONDS);assertThatThrownBy(()->waiter.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(PaymentApplicationException.class);
        }
    }
}
