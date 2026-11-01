package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.service.OwnedFirstRenewalAdmissionService;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.Step;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.support.StripeBillingClockConfirmationCompositionFixture;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

/** Hosted PostgreSQL only. Every successful predecessor comes through confirmation and FactService. */
class OwnedFirstRenewalAdmissionIntegrationTest extends StripeBillingClockConfirmationCompositionFixture {
    private static final String SOURCE="a".repeat(40);
    @Autowired OwnedFirstRenewalStore renewals;
    @Autowired SubscriptionLifecycleLockPort lifecycleLocks;
    @Autowired dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort accountLocks;

    private OwnedFirstRenewalAdmissionService service() {
        return new OwnedFirstRenewalAdmissionService(renewals,transaction,lifecycleOperations,lifecycleLocks,accountLocks,subscriptions,Clock.systemUTC());
    }
    private void genuinePaid() {
        beginConfirmation();issueAndSelect();confirm("succeeded");
        var captured=fence();apply(paidObservation(),captured);assertPaid();
    }
    private dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.Admission admitGenuinePaid() {
        var result=new java.util.concurrent.atomic.AtomicReference<dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.Admission>();
        assertThatCode(()->result.set(service().admit(receipt,SOURCE)))
            .as("A genuine consumed confirmation followed by fresh CASH and FactService must admit the exact paid predecessor")
            .doesNotThrowAnyException();
        return result.get();
    }
    @ParameterizedTest @ValueSource(strings={"succeeded","requires_action"})
    void rejectsAssociationWithoutPaidInitial(String result) {
        beginConfirmation();issueAndSelect();confirm(result);
        int calls=http.calls.size();var before=financialState();
        assertThatThrownBy(()->service().admit(receipt,SOURCE)).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_first_renewals")).isZero();
        assertThat(count("stripe_billing_first_renewal_steps")).isZero();
        assertThat(http.calls).hasSize(calls);assertThat(financialState()).isEqualTo(before);
    }
    @Test void admitsOnlyExactPaidPredecessor() {
        genuinePaid();var before=financialState();int calls=http.calls.size();
        var admitted=admitGenuinePaid();
        assertThat(admitted.receipt()).isEqualTo(receipt);
        assertThat(admitted.sourceSha()).isEqualTo(SOURCE);
        assertThat(admitted.subscriptionRevision()).isEqualTo(current().revision());
        assertThat(admitted.bill()).isEqualTo(jdbc.queryForObject("select id from subscription_billing_records",java.util.UUID.class));
        assertThat(admitted.payment()).isEqualTo(jdbc.queryForObject("select id from payments",java.util.UUID.class));
        assertThat(admitted.root()).isEqualTo(jdbc.queryForObject("select id from subscription_action_capabilities",java.util.UUID.class));
        assertThat(service().admit(receipt,SOURCE)).isEqualTo(admitted);
        assertThatThrownBy(()->service().admit(receipt,"b".repeat(40))).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_first_renewals")).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_steps")).isZero();
        assertThat(http.calls).hasSize(calls);assertThat(financialState()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"create","revision","policy","credentials","refunded","period","trial","price","account","billVersion","paymentVersion","entitled","cancel","debt","pending"})
    void refusesPaidPredecessorDriftWithoutDispatch(String drift) {
        genuinePaid();
        // Persist genuine admission first: subsequent calls must revalidate, never trust the record.
        var admitted=admitGenuinePaid();
        switch(drift) {
            case "create"->jdbc.update("update subscription_operations set status='UNKNOWN' where id=?",receipt.intent().createOperation());
            case "revision"->jdbc.update("update subscriptions set revision=revision+1 where id=?",current().id());
            case "policy"->jdbc.update("update site_subscription_policies set enabled=false where site_id=?",site.value());
            case "credentials"->jdbc.update("update payment_accounts set credentials_ref='changed' where id=?",billing.id().value());
            case "refunded"->jdbc.update("update payments set status='REFUNDED',refunded_amount_minor=amount_minor where id=?",admitted.payment());
            case "period"->jdbc.update("update subscriptions set current_period_end=current_period_end+interval '1 second' where id=?",current().id());
            case "trial"->jdbc.update("update subscriptions set trial_start=current_period_start,trial_end=current_period_end where id=?",current().id());
            case "price"->jdbc.update("update subscriptions set entitled_amount_minor=entitled_amount_minor+1 where id=?",current().id());
            case "account"->jdbc.update("update payment_accounts set account_status='DISABLED' where id=?",billing.id().value());
            case "billVersion"->jdbc.update("update subscription_billing_records set version=version+1 where id=?",admitted.bill());
            case "paymentVersion"->jdbc.update("update payments set version=version+1 where id=?",admitted.payment());
            case "entitled"->jdbc.update("update subscriptions set entitled_interval='YEAR' where id=?",current().id());
            case "cancel"->jdbc.update("update subscriptions set cancellation_effective_at=current_period_end where id=?",current().id());
            case "debt"->jdbc.update("update subscriptions set debt_billing_record_id=?,debt_period_start=current_period_start,debt_period_end=current_period_end where id=?",admitted.bill(),current().id());
            case "pending"->jdbc.update("update subscriptions set pending_operation_id=?,pending_effective_at=current_period_end,pending_state='SCHEDULED',pending_price_id=billing_price_id,pending_price_key=billing_price_key,pending_price_version=billing_price_version,pending_amount_minor=billing_amount_minor,pending_interval=billing_interval,pending_trial_days=billing_trial_days,pending_trial_policy=billing_trial_policy,pending_tax_mode=billing_tax_mode where id=?",receipt.intent().createOperation(),current().id());
        }
        int calls=http.calls.size();var before=financialState();
        assertThatThrownBy(()->service().admit(receipt,SOURCE)).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_first_renewal_steps")).isZero();
        assertThat(http.calls).hasSize(calls);assertThat(financialState()).isEqualTo(before);
    }
    @Test void rejectsUnconsumedRootEvenWhenFreshCashAlreadyExists() {
        beginConfirmation();issueAndSelect();
        var captured=fence();apply(paidObservation(),captured);assertPaid();
        assertThat(jdbc.queryForObject("select consumed_at from subscription_action_capabilities",java.sql.Timestamp.class)).isNull();
        var before=financialState();int calls=http.calls.size();
        assertThatThrownBy(()->service().admit(receipt,SOURCE)).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_first_renewals")).isZero();
        assertThat(http.calls).hasSize(calls);assertThat(financialState()).isEqualTo(before);
    }
    @Test void databaseRefusesForeignInitialPaymentContext() {
        genuinePaid();
        for(var assignment:java.util.List.of("billing_record_id=gen_random_uuid()","subscription_invoice_reference='in_foreign'",
                "provider_payment_reference='pi_foreign'","subscription_period_end=subscription_period_end+interval '1 second'",
                "amount_minor=amount_minor+1","payment_account_id=gen_random_uuid()","customer_id=gen_random_uuid()"))
            assertThatThrownBy(()->jdbc.update("update payments set "+assignment))
                .as("Original immutable payment context rejects "+assignment)
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(admitGenuinePaid()).isNotNull();
    }
    @Test void concurrentAdmissionProducesOneImmutableIdentity() throws Exception {
        genuinePaid();var before=financialState();int calls=http.calls.size();
        try(var workers=new BoundedWorkers()) {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.Admission> call=()->{
                assertThat(start.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return transaction.required(()->{raceStatementDeadlines();return service().admit(receipt,SOURCE);});
            };
            var first=workers.submit(call);var second=workers.submit(call);start.countDown();
            assertThat(first.get(15,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(15,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(count("stripe_billing_first_renewals")).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_steps")).isZero();
        assertThat(http.calls).hasSize(calls);assertThat(financialState()).isEqualTo(before);
    }
    @Test void admissionDoesNotHoldAccountWhileWaitingForLifecycleSite() throws Exception {
        genuinePaid();
        var siteHeld=new java.util.concurrent.CountDownLatch(1);
        var admissionReachedSite=new java.util.concurrent.CountDownLatch(1);
        var observingLocks=(SubscriptionLifecycleLockPort)(s,c)->{
            admissionReachedSite.countDown();lifecycleLocks.lockActive(s,c);
        };
        var observedService=new OwnedFirstRenewalAdmissionService(renewals,transaction,lifecycleOperations,observingLocks,accountLocks,subscriptions,Clock.systemUTC());
        try(var workers=new BoundedWorkers()) {
            var lifecycle=workers.submit(()->transaction.required(()->{
                raceStatementDeadlines();
                jdbc.queryForList("select id from sites where id=? for update",site.value());siteHeld.countDown();
                try {assertThat(admissionReachedSite.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                accountLocks.findByIdForUpdate(billing.id()).orElseThrow();return null;
            }));
            assertThat(siteHeld.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var admission=workers.submit(()->transaction.required(()->{
                raceStatementDeadlines();return observedService.admit(receipt,SOURCE);
            }));
            lifecycle.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(admission.get(15,java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
        }
        assertThat(count("stripe_billing_first_renewals")).isEqualTo(1);
    }
    /** One hosted measurement against the genuine predecessor; no admission predicate is changed. */
    @Test void paidProofExecutionPlan() {
        genuinePaid();
        var raw=transaction.required(()->jdbc.query(connection->{
            var statement=connection.prepareStatement("explain (analyze,format json,summary on) select * from owned_first_renewal_paid(?)");
            statement.setQueryTimeout(60);statement.setObject(1,receipt.intent().id());return statement;
        },result->{if(!result.next())throw new IllegalStateException("Paid proof EXPLAIN returned no result");return result.getString(1);}));
        var measured=com.google.gson.JsonParser.parseString(raw).getAsJsonArray().get(0).getAsJsonObject();
        var nodes=new java.util.TreeMap<String,Integer>();
        collectPlanNodes(measured.getAsJsonObject("Plan"),nodes);
        System.out.println("OWNED_FIRST_RENEWAL_PAID_PLAN="+com.google.gson.JsonParser.parseString(
            "{\"planningMs\":"+measured.get("Planning Time")+",\"executionMs\":"+measured.get("Execution Time")
            +",\"jit\":"+measured.get("JIT")+",\"nodes\":"+new com.google.gson.Gson().toJson(nodes)
            +",\"rows\":"+measured.getAsJsonObject("Plan").get("Actual Rows")+"}"));
        assertThat(measured.getAsJsonObject("Plan").get("Actual Rows").getAsInt()).isEqualTo(1);
        assertThat(measured.get("Planning Time").getAsDouble()+measured.get("Execution Time").getAsDouble())
            .as("The exact owned proof must leave headroom for repeated checks under the five-second lifecycle lock bound")
            .isLessThan(1000.0);
    }
    private static void collectPlanNodes(com.google.gson.JsonObject plan,java.util.Map<String,Integer> counts) {
        counts.merge(plan.get("Node Type").getAsString(),1,Integer::sum);
        if(plan.has("Plans"))for(var child:plan.getAsJsonArray("Plans"))collectPlanNodes(child.getAsJsonObject(),counts);
    }
    private void raceStatementDeadlines(){
        jdbc.execute("set local lock_timeout='5s'");jdbc.execute("set local statement_timeout='10s'");
    }
    /** Avoid ExecutorService.close's unbounded wait after a failed Future.get. Close failures are
     * suppressed under the original failure by try-with-resources. Daemon workers cannot hold JVM exit. */
    private static final class BoundedWorkers implements AutoCloseable {
        private final java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newFixedThreadPool(2,
            task->Thread.ofPlatform().daemon(true).name("owned-renewal-race").unstarted(task));
        private final java.util.List<java.util.concurrent.Future<?>> submitted=new java.util.ArrayList<>();
        <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task){
            var future=executor.submit(task);submitted.add(future);return future;
        }
        public void close() throws InterruptedException {
            for(var future:submitted)future.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS))
                .as("Race workers must terminate within bounded cleanup").isTrue();
        }
    }
    @Test void rejectsEvidenceTamper() {
        genuinePaid();var admitted=admitGenuinePaid();
        assertThatThrownBy(()->jdbc.update("insert into stripe_billing_first_renewal_steps(id,admission_id,ordinal,target,operation_key,fingerprint,prepared_at) values (?,?,1,?,?,?,now())",
            java.util.UUID.randomUUID(),admitted.id(),receipt.periodEnd()+1,"billing-renewal-advance-v1:"+admitted.id()+":1","b".repeat(64)))
            .as("A foreign target cannot become the immutable first intent")
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var step=renewals.prepare(admitted,Step.BOUNDARY,null,java.time.Instant.now());
        for(var sql:java.util.List.of(
                "update stripe_billing_first_renewals set source_sha='"+"b".repeat(40)+"'",
                "update stripe_billing_first_renewals set original_root=gen_random_uuid()",
                "update stripe_billing_first_renewals set fingerprint='"+"b".repeat(64)+"'",
                "delete from stripe_billing_first_renewals",
                "update stripe_billing_first_renewal_steps set target=target+1",
                "update stripe_billing_first_renewal_steps set ordinal=2",
                "update stripe_billing_first_renewal_steps set fingerprint='"+"b".repeat(64)+"'",
                "delete from stripe_billing_first_renewal_steps",
                "update stripe_billing_owned_subscription_receipts set provider_created=provider_created+1",
                "delete from stripe_billing_owned_subscription_receipts",
                "update stripe_billing_clock_ready set frozen_time=frozen_time+1"))
            assertThatThrownBy(()->jdbc.update(sql)).as(sql).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("insert into stripe_billing_first_renewals select gen_random_uuid(),owned_intent,payment_account_id,site_id,customer_id,subscription_id,source_sha,original_root,original_attempt,attempt_version,initial_bill,bill_version,initial_payment,payment_version,invoice_reference,allocation_reference,payment_intent_reference,subscription_revision,policy_version,fingerprint,admitted_at,stopped_at,stop_reason from stripe_billing_first_renewals"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(renewals.prepare(admitted,Step.BOUNDARY,null,java.time.Instant.now())).isEqualTo(step);
        assertThat(step.target()).isEqualTo(receipt.periodEnd());
        assertThatThrownBy(()->renewals.prepare(admitted,Step.FINALIZATION,null,java.time.Instant.now())).isInstanceOf(RuntimeException.class);
        renewals.stop(admitted,"AUTHORITY_DRIFT",java.time.Instant.now());
        assertThatThrownBy(()->jdbc.update("update stripe_billing_first_renewals set stopped_at=null,stop_reason=null"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->service().admit(receipt,SOURCE)).isInstanceOf(RuntimeException.class);
    }
    @Test void callerRecordCannotSubstituteOriginalReceiptIdentity() {
        genuinePaid();var a=admitGenuinePaid();
        var foreign=new dev.genericpayments.platform.provider.application.model.OwnedClockSubscriptionReceipt(receipt.intent(),receipt.providerOperation(),receipt.reference(),
            receipt.providerCreated(),"si_foreign",receipt.periodStart(),receipt.periodEnd(),receipt.acknowledgedAt());
        var forged=new dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.Admission(a.id(),foreign,a.sourceSha(),a.root(),a.attempt(),a.attemptVersion(),
            a.bill(),a.billVersion(),a.payment(),a.paymentVersion(),a.invoice(),a.allocation(),a.paymentIntent(),a.subscriptionRevision(),a.policyVersion(),a.fingerprint(),a.admittedAt());
        assertThatThrownBy(()->renewals.stop(forged,"AUTHORITY_DRIFT",java.time.Instant.now())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->renewals.prepare(forged,Step.BOUNDARY,null,java.time.Instant.now())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->transaction.required(()->renewals.requirePaidInTransaction(forged))).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_first_renewal_steps")).isZero();
        assertThat(jdbc.queryForObject("select stopped_at from stripe_billing_first_renewals",java.sql.Timestamp.class)).isNull();
        assertThat(service().admit(receipt,SOURCE)).isEqualTo(a);
    }
}
