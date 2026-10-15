package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import java.util.UUID;
import dev.genericpayments.platform.payments.application.port.out.PendingProviderRecoveryPort;
import static dev.genericpayments.platform.payments.application.port.out.PendingProviderRecoveryPort.Outcome.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.concurrent.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.catalog.domain.Money;

class StripeRecoveryIntegrationTest extends StripeInboxFixture {
    @Autowired AdminReconciliationService admin;
    @Autowired PendingProviderRecoveryPort pending;
    @Autowired StripeRecoveryService recovery;
    @Autowired ProviderOperationStore operations;
    @Autowired ProviderRecoveryProofPort proofs;

    Refund refundAck(String reference){
        mapped();original=save.save(original.succeeded(INITIAL_TIME));var id=new RefundId(UUID.randomUUID());
        var refund=saveRefunds.insert(Refund.request(id,current(),Money.of(700,"EUR"),ProviderOperationKeys.refund(id),"REQUESTED_BY_CUSTOMER",null,INITIAL_TIME)).value();
        snapshot("succeeded","ch_paid");http.enqueue("POST","/v1/refunds",200,refundJson(refund,reference));
        provider.requestRefund(new ProviderRefundRequest(currentAccount(),current(),refund,refund.providerOperationKey()));return refund;
    }
    String refundJson(Refund refund,String reference){return "{\"id\":\""+reference+"\",\"object\":\"refund\",\"amount\":700,\"currency\":\"eur\",\"payment_intent\":\"pi_owned\",\"charge\":\"ch_paid\",\"status\":\"succeeded\",\"reason\":\"requested_by_customer\",\"metadata\":{\"refund_id\":\""+refund.id().value()+"\",\"payment_id\":\""+original.id().value()+"\"}}";}
    void refundTruth(Refund refund,String reference){identity();http.enqueue("GET","/v1/refunds/"+reference,200,refundJson(refund,reference));http.enqueue("GET","/v1/payment_intents/pi_owned",200,intent("succeeded","ch_paid",2000));charge("ch_paid",true);}
    @Test void automaticRecoveryRepairsAdvancedRefundMappingAndSettlesOnlyIndividualAmount() {
        var refund=refundAck("re_advanced");saveRefunds.save(new LoadRefundPort.StoredRefund(refund.processing(INITIAL_TIME),0));callback();refundTruth(refund,"re_advanced");
        recovery.runOnce();
        assertThat(refunds.findById(site,refund.id()).orElseThrow().value().providerRefundReference()).isEqualTo("re_advanced");
        assertThat(current().refundedAmountMinor()).isEqualTo(700);assertThat(count("payment_events")).isEqualTo(1);assertThat(count("outbox_events")).isEqualTo(1);
    }
    @Test void unknownRefundReplayHasOneAdapterClaimAndSeparateBoundedReadPhase() {
        var refund=refundAck("re_replay");jdbc.update("update provider_operations set status='UNKNOWN',provider_reference=null where kind='REFUND_CREATE'");
        snapshot("succeeded","ch_paid");http.enqueue("POST","/v1/refunds",200,refundJson(refund,"re_replay"));int before=http.calls().size();
        recovery.runOnce();assertThat(current().refundedAmountMinor()).isZero();assertThat(refunds.findById(site,refund.id()).orElseThrow().value().providerRefundReference()).isNull();
        assertThat(http.calls().subList(before,http.calls().size())).extracting(c->c.path()).containsExactly("/v1/account","/v1/payment_intents/pi_owned","/v1/charges/ch_paid","/v1/refunds");
        jdbc.update("update provider_operations set next_attempt_at=clock_timestamp() where kind='REFUND_CREATE'");refundTruth(refund,"re_replay");recovery.runOnce();
        assertThat(current().refundedAmountMinor()).isEqualTo(700);
        assertThat(http.calls().stream().filter(c->c.method().equals("POST")&&c.path().equals("/v1/refunds"))).hasSize(2).allSatisfy(c->assertThat(c.header("Idempotency-Key")).isEqualTo(refund.providerOperationKey()));
    }
    @ParameterizedTest @ValueSource(strings={"bound","missing"})
    void unsupportedRefundObservationRetainsTwelfthFailureAndRequiresManual(String mapping) {
        var refund=refundAck("re_future");callback();if(mapping.equals("bound"))saveRefunds.save(new LoadRefundPort.StoredRefund(refund.withProviderRefundReference("re_future",INITIAL_TIME),0));
        var op=operations.find(account.id(),refund.providerOperationKey()).orElseThrow();jdbc.update("update provider_operations set recovery_attempts=11 where id=?",op.id());
        identity();http.enqueue("GET","/v1/refunds/re_future",200,refundJson(refund,"re_future").replace("\"status\":\"succeeded\"","\"status\":\"future_status\""));http.enqueue("GET","/v1/payment_intents/pi_owned",200,intent("succeeded","ch_paid",2000));charge("ch_paid",true);
        recovery.recover(op.id());
        assertThat(jdbc.queryForObject("select recovery_attempts from provider_operations where id=?",Integer.class,op.id())).isEqualTo(12);
        assertThat(jdbc.queryForObject("select manual_review_required from provider_operations where id=?",Boolean.class,op.id())).isTrue();
        assertThat(current().refundedAmountMinor()).isZero();assertThat(count("payment_events")).isZero();
        assertThat(count("outbox_events")).isZero();assertThat(count("provider_charge_receipts")).isZero();
        if(mapping.equals("missing"))assertThat(refunds.findById(site,refund.id()).orElseThrow().value().providerRefundReference()).isNull();
        int calls=http.calls().size();jdbc.update("update provider_operations set next_attempt_at=clock_timestamp() where id=?",op.id());recovery.recover(op.id());
        assertThat(http.calls()).hasSize(calls);assertThat(jdbc.queryForObject("select recovery_attempts from provider_operations where id=?",Integer.class,op.id())).isEqualTo(12);
    }
    @Test void knownPendingRefundObservationPreservesEarlierFailuresWithoutExhaustion() {
        var refund=refundAck("re_pending");var op=operations.find(account.id(),refund.providerOperationKey()).orElseThrow();jdbc.update("update provider_operations set recovery_attempts=11 where id=?",op.id());
        identity();http.enqueue("GET","/v1/refunds/re_pending",200,refundJson(refund,"re_pending").replace("\"status\":\"succeeded\"","\"status\":\"pending\""));http.enqueue("GET","/v1/payment_intents/pi_owned",200,intent("succeeded","ch_paid",2000));charge("ch_paid",true);
        recovery.recover(op.id());
        assertThat(jdbc.queryForObject("select recovery_attempts from provider_operations where id=?",Integer.class,op.id())).isEqualTo(11);
        assertThat(jdbc.queryForObject("select manual_review_required from provider_operations where id=?",Boolean.class,op.id())).isFalse();
        assertThat(refunds.findById(site,refund.id()).orElseThrow().value().status()).isEqualTo(RefundStatus.PROCESSING);assertThat(current().refundedAmountMinor()).isZero();
    }
    @Test void manualRefundProofUsesCreationEventAndFiveReadsBeforeSeparateFinancialPhase() {
        var refund=refundAck("re_manual");jdbc.update("update provider_operations set status='UNKNOWN',provider_reference=null,first_dispatched_at=now()-interval '24 hours',retry_before=now()-interval '1 hour' where kind='REFUND_CREATE'");
        var op=operations.find(account.id(),refund.providerOperationKey()).orElseThrow();int before=http.calls().size();
        identity();http.enqueue("GET","/v1/events/evt_refundcreated",200,"{\"id\":\"evt_refundcreated\",\"object\":\"event\",\"created\":"+(op.firstDispatchedAt().getEpochSecond()+1)+",\"type\":\"refund.created\",\"livemode\":false,\"api_version\":\"2026-09-30.endive\",\"request\":{\"idempotency_key\":\""+op.operationKey()+"\"},\"data\":{\"object\":{\"id\":\"re_manual\",\"object\":\"refund\"}}}");
        http.enqueue("GET","/v1/refunds/re_manual",200,refundJson(refund,"re_manual"));http.enqueue("GET","/v1/payment_intents/pi_owned",200,intent("succeeded","ch_paid",2000));charge("ch_paid",true);refundTruth(refund,"re_manual");
        recovery.recover(new RecoverProviderOperationCommand(op.id(),manual("unused").actorId(),"re_manual","evt_refundcreated","manual-refund","manual-refund"));
        assertThat(current().refundedAmountMinor()).isEqualTo(700);assertThat(http.calls().subList(before,http.calls().size())).hasSize(9).allSatisfy(c->assertThat(c.method()).isEqualTo("GET"));
    }

    @Test void failedRetrieveReleasesOnlyOwnedLocalLeaseAndKeepsMappingAbsent() {
        identity();http.enqueue("GET","/v1/payment_intents/pi_owned",503,"{\"error\":{\"type\":\"api_error\"}}");
        recovery.runOnce();
        assertThat(current().providerPaymentReference()).isNull();assertThat(current().status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(jdbc.queryForObject("select reconciliation_lease_owner from payments",String.class)).isNull();
        assertThat(jdbc.queryForObject("select recovery_attempts from provider_operations",Integer.class)).isEqualTo(1);
    }
    @Test void advancedMappingAndMoneyRollBackWhenLeaseExpiresDuringJournal() {
        original=save.save(original.processing(INITIAL_TIME));callback();
        var lease=transitions.acquireRecovery(site,original.id(),"stale-recovery");snapshot("succeeded","ch_paid");var proof=proofs.retrieve(operations.findById(operationId()).orElseThrow());
        jdbc.execute("create function task11_delay() returns trigger language plpgsql as $$ begin perform pg_sleep(0.6); return new; end; $$");jdbc.execute("create trigger task11_delay after insert on payment_events for each row execute function task11_delay()");
        try{jdbc.update("update payments set reconciliation_lease_expires_at=clock_timestamp()+interval '0.3 seconds'");assertThatThrownBy(()->transitions.reconcileRecovered(lease,proof,"stale-recovery")).isInstanceOf(RuntimeException.class);}
        finally{jdbc.execute("drop trigger task11_delay on payment_events");jdbc.execute("drop function task11_delay()");}
        assertThat(current().providerPaymentReference()).isNull();assertThat(current().status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(count("payment_events")).isZero();assertThat(count("outbox_events")).isZero();assertThat(count("provider_charge_receipts")).isZero();
    }
    @Test void staleRecoveryCannotApplyAfterNewOwnerAcquiresPaymentLease() {
        var old=transitions.acquireRecovery(site,original.id(),"old-recovery");snapshot("succeeded","ch_paid");var proof=proofs.retrieve(operations.findById(operationId()).orElseThrow());
        jdbc.update("update payments set reconciliation_lease_expires_at=clock_timestamp()-interval '1 second'");
        var next=transitions.acquireRecovery(site,original.id(),"next-recovery");
        assertThatThrownBy(()->transitions.reconcileRecovered(old,proof,"old-recovery")).isInstanceOf(RuntimeException.class);
        transitions.releaseRecovery(old);
        assertThat(jdbc.queryForObject("select reconciliation_lease_owner from payments",String.class)).isEqualTo(next.owner());
        assertThat(current().providerPaymentReference()).isNull();
        transitions.reconcileRecovered(next,proof,"next-recovery");assertThat(current().status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
    @ParameterizedTest @ValueSource(strings={"account","amount","fingerprint"})
    void finalFreshFactsMustMatchRetrievedRecoveryProof(String changed) {
        var lease=transitions.acquireRecovery(site,original.id(),"mutated-recovery");snapshot("succeeded","ch_paid");var proof=proofs.retrieve(operations.findById(operationId()).orElseThrow());
        switch(changed){case "account"->jdbc.update("update payment_accounts set external_account_id='acct_changed'");case "amount"->jdbc.update("update payments set amount_minor=2001");default->jdbc.update("update provider_operations set fingerprint=repeat('a',64)");}
        assertThatThrownBy(()->transitions.reconcileRecovered(lease,proof,"mutated-recovery")).isInstanceOf(RuntimeException.class);
        assertThat(current().providerPaymentReference()).isNull();assertThat(count("payment_events")).isZero();
    }
    @Test void concurrentDueWorkersAcquireExactlyOneToken() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();return pending.claimNext();});var b=pool.submit(()->{start.await();return pending.claimNext();});start.countDown();
            assertThat(java.util.stream.Stream.of(a.get(3,TimeUnit.SECONDS),b.get(3,TimeUnit.SECONDS)).filter(java.util.Optional::isPresent)).hasSize(1);
        }
        assertThat(jdbc.queryForObject("select recovery_attempts from provider_operations",Integer.class)).isEqualTo(1);
    }
    @Test void successfulTwelfthObservationPreservesElevenFailuresAndAllowsLaterAttempt() {
        jdbc.update("update provider_operations set recovery_attempts=11");var claim=pending.claimNext().orElseThrow();pending.finish(claim,RETRY_PENDING);
        assertThat(jdbc.queryForObject("select recovery_attempts from provider_operations",Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("select manual_review_required from provider_operations",Boolean.class)).isFalse();
        jdbc.update("update provider_operations set next_attempt_at=clock_timestamp()");assertThat(pending.claimNext()).isPresent();
    }
    @Test void exhaustionMarksOnlyOneSelectedActionableStripeRow() {
        var extra=save.save(payment());
        jdbc.update("insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint,status,recovery_attempts,first_dispatched_at,retry_before) values (?,?,'acct_expected','stripe-sandbox-default','PAYMENT_PREPARE',?,?,repeat('a',64),'UNKNOWN',12,now()-interval '24 hours',now()-interval '1 hour')",UUID.randomUUID(),account.id().value(),extra.id().value(),ProviderOperationKeys.prepare(extra.id()));
        jdbc.update("update provider_operations set recovery_attempts=12");
        assertThat(pending.claimNext()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where manual_review_required",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select max(recovery_attempts) from provider_operations",Integer.class)).isEqualTo(12);
    }
    @ParameterizedTest @ValueSource(strings={"fake","live-dispatch","terminal"})
    void exhaustionDoesNotTouchIneligibleRows(String kind) {
        jdbc.update("update provider_operations set recovery_attempts=12");
        switch(kind){case "fake"->jdbc.update("update payment_accounts set provider='FAKE'");case "live-dispatch"->jdbc.update("update provider_operations set status='DISPATCHING',provider_reference=null,lease_owner='live',lease_expires_at=clock_timestamp()+interval '1 minute'");default->{mapped();save.save(current().succeeded(INITIAL_TIME));}}
        assertThat(pending.claimNext()).isEmpty();
        assertThat(jdbc.queryForObject("select manual_review_required from provider_operations",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select recovery_attempts from provider_operations",Integer.class)).isEqualTo(12);
    }
    @ParameterizedTest @ValueSource(strings={"pending","missing","wrong-reference","stale-snapshot","fake"})
    void resolvedMetadataRequiresExactOriginalAckAndOwnedTerminalMapping(String kind) {
        jdbc.update("update provider_operations set recovery_attempts=12,manual_review_required=true,safe_error_code='RETRY_EXHAUSTED'");
        var expected=operations.findById(operationId()).orElseThrow();
        if(!kind.equals("missing")){mapped();if(!kind.equals("pending"))save.save(current().succeeded(INITIAL_TIME));}
        switch(kind){case "wrong-reference"->jdbc.update("update payments set provider_payment_reference='pi_other'");case "stale-snapshot"->jdbc.update("update provider_operations set version=version+1");case "fake"->jdbc.update("update payment_accounts set provider='FAKE'");default->{}}
        pending.clearResolved(expected);
        assertThat(jdbc.queryForObject("select manual_review_required from provider_operations",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select safe_error_code from provider_operations",String.class)).isEqualTo("RETRY_EXHAUSTED");
        assertThat(jdbc.queryForObject("select recovery_attempts from provider_operations",Integer.class)).isEqualTo(12);
    }
    @Test void manualFinancialExpiryRollsBackMoneyButKeepsAcceptedReceiptForResume() {
        unknownExpired();creationProof("prepare_"+original.id().value(),"pi_owned");snapshot("succeeded","ch_paid");callback();
        jdbc.execute("create function task11_expire() returns trigger language plpgsql as $$ begin update payments set reconciliation_lease_expires_at=clock_timestamp()-interval '1 second' where id=new.payment_id; return new; end; $$");
        jdbc.execute("create trigger task11_expire after insert on payment_events for each row execute function task11_expire()");
        try{assertThatThrownBy(()->recovery.recover(manual("expired-manual"))).isInstanceOf(RuntimeException.class);}
        finally{jdbc.execute("drop trigger task11_expire on payment_events");jdbc.execute("drop function task11_expire()");}
        assertThat(operations.findById(operationId()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(count("idempotency_records")).isEqualTo(1);assertThat(count("audit_log")).isEqualTo(1);
        assertThat(current().providerPaymentReference()).isNull();assertThat(count("payment_events")).isZero();assertThat(count("outbox_events")).isZero();assertThat(count("provider_charge_receipts")).isZero();
        snapshot("succeeded","ch_paid");recovery.recover(manual("expired-manual"));
        assertThat(current().status()).isEqualTo(PaymentStatus.SUCCEEDED);assertThat(count("payment_events")).isEqualTo(1);assertThat(count("audit_log")).isEqualTo(1);
    }
    @Test void runOnceAcquiresAtMostTwentyItemsJustInTime() {
        for(int i=0;i<20;i++){var p=save.save(payment());jdbc.update("insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint,status,first_dispatched_at,retry_before) values (?,?,'acct_expected','stripe-sandbox-default','PAYMENT_PREPARE',?,?,repeat('a',64),'UNKNOWN',now()-interval '24 hours',now()-interval '1 hour')",UUID.randomUUID(),account.id().value(),p.id().value(),ProviderOperationKeys.prepare(p.id()));}
        unknownExpired();recovery.runOnce();assertThat(jdbc.queryForObject("select count(*) from provider_operations where manual_review_required",Integer.class)).isEqualTo(20);
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where not manual_review_required",Integer.class)).isEqualTo(1);
    }
    @Test void exactWriteWindowBoundaryAndAcknowledgedOperationNeverReplay() {
        var first=Instant.parse("2026-10-14T10:00:00Z");
        assertThat(StripeRecoveryService.mayReplayMissingReference(ProviderOperation.Status.UNKNOWN,null,first,first.plusSeconds(82799))).isTrue();
        assertThat(StripeRecoveryService.mayReplayMissingReference(ProviderOperation.Status.UNKNOWN,null,first,first.plusSeconds(82800))).isFalse();
        assertThat(StripeRecoveryService.mayReplayMissingReference(ProviderOperation.Status.DISPATCHING,null,first,first.minusSeconds(1))).isFalse();
        assertThat(StripeRecoveryService.mayReplayMissingReference(ProviderOperation.Status.ACKNOWLEDGED,"pi_owned",first,first.plusSeconds(1))).isFalse();
    }
    @Test void manualAuditFailureRollsBackBindingAndIdempotencyReceipt() {
        unknownExpired();creationProof("prepare_"+original.id().value(),"pi_owned");
        jdbc.execute("create function task11_audit() returns trigger language plpgsql as $$ begin if new.action='PROVIDER_OPERATION_RECOVERED' then raise exception 'audit failed' using errcode='23514'; end if; return new; end; $$");jdbc.execute("create trigger task11_audit before insert on audit_log for each row execute function task11_audit()");
        try{assertThatThrownBy(()->recovery.recover(manual("audit-rollback"))).isInstanceOf(RuntimeException.class);}
        finally{jdbc.execute("drop trigger task11_audit on audit_log");jdbc.execute("drop function task11_audit()");}
        assertThat(operations.findById(operationId()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.UNKNOWN);
        assertThat(count("idempotency_records")).isZero();assertThat(count("audit_log")).isZero();assertThat(current().providerPaymentReference()).isNull();
    }
    @Test void exhaustedManualRecoveryKeepsFailedHistoryAndDuplicateResumesWithoutCreationEvent() {
        unknownExpired();jdbc.update("update provider_operations set recovery_attempts=12,manual_review_required=true");
        creationProof("prepare_"+original.id().value(),"pi_owned");identity();http.enqueue("GET","/v1/payment_intents/pi_owned",503,"{\"error\":{\"type\":\"api_error\"}}");
        assertThatThrownBy(()->recovery.recover(manual("manual-resume"))).isInstanceOf(RuntimeException.class);
        assertThat(operations.findById(operationId()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(count("idempotency_records")).isEqualTo(1);assertThat(count("audit_log")).isEqualTo(1);assertThat(current().providerPaymentReference()).isNull();
        snapshot("succeeded","ch_paid");recovery.recover(manual("manual-resume"));
        assertThat(current().status()).isEqualTo(PaymentStatus.SUCCEEDED);assertThat(count("audit_log")).isEqualTo(1);assertThat(count("payment_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select recovery_attempts from provider_operations",Integer.class)).isEqualTo(12);
        assertThat(http.calls().stream().filter(c->c.path().startsWith("/v1/events/"))).hasSize(1);
        assertThat(http.calls().stream().filter(c->c.method().equals("POST"))).hasSize(1);
        assertThat(jdbc.queryForObject("select manual_review_required from provider_operations",Boolean.class)).isFalse();
        jdbc.update("update provider_operations set next_attempt_at=clock_timestamp()");recovery.runOnce();
        assertThat(jdbc.queryForObject("select manual_review_required from provider_operations",Boolean.class)).isFalse();
    }
    @Test void conflictingManualReplayCannotChangeBoundReference() {
        unknownExpired();creationProof("prepare_"+original.id().value(),"pi_owned");snapshot("succeeded","ch_paid");recovery.recover(manual("manual-conflict"));
        var other=new RecoverProviderOperationCommand(operationId(),manual("manual-conflict").actorId(),"pi_other","evt_other","manual-conflict","manual-conflict");
        assertThatThrownBy(()->recovery.recover(other)).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(operations.findById(operationId()).orElseThrow().providerReference()).isEqualTo("pi_owned");assertThat(count("audit_log")).isEqualTo(1);
    }
    @Test void wrongCreationEventObjectCannotBindEvenWithMatchingRequestKey() {
        unknownExpired();creationProof("prepare_"+original.id().value(),"pi_other");
        assertThatThrownBy(()->recovery.recover(manual("wrong-object"))).isInstanceOf(RuntimeException.class);
        assertThat(current().providerPaymentReference()).isNull();assertThat(count("idempotency_records")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"expired-event","wrong-managed-account","live-dispatch","changed-local-amount"})
    void manualProofFailureLeavesNoBindingReceiptOrMoney(String invalid) {
        unknownExpired();int before=http.calls().size();
        if(invalid.equals("live-dispatch"))jdbc.update("update provider_operations set status='DISPATCHING',lease_owner='other',lease_expires_at=clock_timestamp()+interval '1 minute'");
        if(invalid.equals("wrong-managed-account"))http.enqueue("GET","/v1/account",200,"{\"id\":\"acct_foreign\",\"object\":\"account\"}");else identity();
        http.enqueue("GET","/v1/events/evt_created",invalid.equals("expired-event")?404:200,invalid.equals("expired-event")?"{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\"}}":creationEvent("prepare_"+original.id().value(),"pi_owned"));
        http.enqueue("GET","/v1/payment_intents/pi_owned",200,call->{if(invalid.equals("changed-local-amount"))jdbc.update("update payments set amount_minor=2001");return intent("requires_payment_method",null,2000);});
        assertThatThrownBy(()->recovery.recover(manual("invalid-proof"))).isInstanceOf(RuntimeException.class);
        assertThat(operations.findById(operationId()).orElseThrow().providerReference()).isNull();assertThat(current().providerPaymentReference()).isNull();
        assertThat(count("idempotency_records")).isZero();assertThat(count("audit_log")).isZero();assertThat(count("payment_events")).isZero();assertThat(count("provider_charge_receipts")).isZero();
        assertThat(http.calls().subList(before,http.calls().size())).allSatisfy(c->assertThat(c.method()).isEqualTo("GET"));
    }
    dev.genericpayments.platform.provider.application.model.RecoverProviderOperationCommand manual(String key){return new dev.genericpayments.platform.provider.application.model.RecoverProviderOperationCommand(operationId(),UUID.fromString("30000000-0000-0000-0000-000000000001"),"pi_owned","evt_created",key,"manual-recovery");}
    void unknownExpired(){jdbc.update("update provider_operations set status='UNKNOWN',provider_reference=null,first_dispatched_at=now()-interval '24 hours',retry_before=now()-interval '1 hour'");}
    void creationProof(String key,String object){
        identity();http.enqueue("GET","/v1/events/evt_created",200,creationEvent(key,object));
        http.enqueue("GET","/v1/payment_intents/pi_owned",200,intent("requires_payment_method",null,2000));
    }
    String creationEvent(String key,String object){return "{\"id\":\"evt_created\",\"object\":\"event\",\"created\":"+(operations.findById(operationId()).orElseThrow().firstDispatchedAt().getEpochSecond()+1)+",\"type\":\"payment_intent.created\",\"livemode\":false,\"api_version\":\"2026-09-30.endive\",\"request\":{\"idempotency_key\":\""+key+"\"},\"data\":{\"object\":{\"id\":\""+object+"\",\"object\":\"payment_intent\"}}}";}
    @ParameterizedTest @ValueSource(strings={"late","missing-time","connected-account","blank-context","before-first","at-upper","live","wrong-type","wrong-account"})
    void creationEventMustProveOriginalDispatchWindowAndUnsupportedContextMustFail(String invalid){
        unknownExpired();String event=creationEvent("prepare_"+original.id().value(),"pi_owned");
        switch(invalid){case "late"->event=event.replaceFirst("\\\"created\\\":\\d+","\"created\":"+Instant.now().getEpochSecond());case "missing-time"->event=event.replaceFirst("\\\"created\\\":\\d+,","");case "connected-account"->event=event.replace("\"object\":\"event\"","\"object\":\"event\",\"account\":\"acct_expected\"");case "blank-context"->event=event.replace("\"object\":\"event\"","\"object\":\"event\",\"context\":\"\"");case "before-first"->event=event.replaceFirst("\\\"created\\\":\\d+","\"created\":"+(operations.findById(operationId()).orElseThrow().firstDispatchedAt().getEpochSecond()-1));case "at-upper"->event=event.replaceFirst("\\\"created\\\":\\d+","\"created\":"+operations.findById(operationId()).orElseThrow().retryBefore().getEpochSecond());case "live"->event=event.replace("\"livemode\":false","\"livemode\":true");case "wrong-type"->event=event.replace("payment_intent.created","payment_intent.succeeded");default->event=event.replace("\"object\":\"event\"","\"object\":\"event\",\"account\":\"acct_other\"");}
        identity();http.enqueue("GET","/v1/events/evt_created",200,event);http.enqueue("GET","/v1/payment_intents/pi_owned",200,intent("requires_payment_method",null,2000));snapshot("succeeded","ch_paid");
        assertThatThrownBy(()->recovery.recover(manual("event-boundary"))).isInstanceOf(RuntimeException.class);
        assertThat(operations.findById(operationId()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.UNKNOWN);
        assertThat(current().providerPaymentReference()).isNull();assertThat(count("idempotency_records")).isZero();assertThat(count("audit_log")).isZero();assertThat(count("payment_events")).isZero();
    }
    @Test void creationEventSecondFloorCanIdentifyOriginalSubsecondDispatch() {
        unknownExpired();var op=operations.findById(operationId()).orElseThrow();
        var event=creationEvent(op.operationKey(),"pi_owned").replaceFirst("\\\"created\\\":\\d+","\"created\":"+op.firstDispatchedAt().getEpochSecond());
        identity();http.enqueue("GET","/v1/events/evt_created",200,event);http.enqueue("GET","/v1/payment_intents/pi_owned",200,intent("requires_payment_method",null,2000));snapshot("succeeded","ch_paid");
        recovery.recover(manual("floor-precision"));assertThat(current().status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
    @Test void manualCreationProofRecoversOriginalOperationAtomicallyWithAuditAndIdempotency() {
        unknownExpired();creationProof("prepare_"+original.id().value(),"pi_owned");snapshot("succeeded","ch_paid");
        recovery.recover(manual("manual-one"));
        assertThat(jdbc.queryForObject("select status from provider_operations",String.class)).isEqualTo("ACKNOWLEDGED");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='PROVIDER_OPERATION_RECOVERED'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records where resource_type='PROVIDER_OPERATION'",Integer.class)).isEqualTo(1);
        assertThat(http.calls().stream().filter(c->c.method().equals("POST"))).hasSize(1);
    }
    @Test void manualMetadataWithoutOriginalCreationKeyCannotAuthorizeBinding() {
        unknownExpired();creationProof("different-key","pi_owned");
        assertThatThrownBy(()->recovery.recover(manual("manual-wrong-key"))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select status from provider_operations",String.class)).isEqualTo("UNKNOWN");
        assertThat(count("idempotency_records")).isZero();assertThat(count("audit_log")).isZero();
    }

    @Test void restartRepairsAdvancedMissingMappingFromVerifiedOriginalAckAndSettlesOnce() {
        original=save.save(original.processing(INITIAL_TIME));callback();
        snapshot("succeeded","ch_paid");recovery.runOnce();
        assertThat(current().providerPaymentReference()).isEqualTo("pi_owned");
        assertThat(current().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(count("payment_events")).isEqualTo(1);assertThat(count("outbox_events")).isEqualTo(1);
        recovery.runOnce();assertThat(count("payment_events")).isEqualTo(1);
        assertThat(http.calls().stream().filter(c->c.method().equals("POST"))).hasSize(1);
    }
    @Test void missingReferenceReplayUsesOriginalKeyThenSeparateReadOnlyRecoveryTick() {
        jdbc.update("update provider_operations set status='UNKNOWN',provider_reference=null");
        identity();http.enqueue("POST","/v1/payment_intents",200,intent("requires_payment_method",null,2000));
        recovery.recover(operationId());
        assertThat(jdbc.queryForObject("select status from provider_operations",String.class)).isEqualTo("ACKNOWLEDGED");
        assertThat(current().providerPaymentReference()).isNull();assertThat(count("payment_events")).isZero();
        assertThat(http.calls().stream().filter(c->c.method().equals("POST"))).hasSize(2).allSatisfy(c->assertThat(c.header("Idempotency-Key")).isEqualTo("prepare_"+original.id().value()));
        jdbc.update("update provider_operations set next_attempt_at=clock_timestamp()");
        snapshot("succeeded","ch_paid");recovery.runOnce();
        assertThat(current().status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }
    @Test void outsideOriginalWindowNeverPostsAndRequiresManualProofDespiteFrozenBusinessClock() {
        jdbc.update("update provider_operations set status='UNKNOWN',provider_reference=null,first_dispatched_at=now()-interval '24 hours',retry_before=now()-interval '1 hour'");
        int before=http.calls().size();recovery.runOnce();
        assertThat(jdbc.queryForObject("select manual_review_required from provider_operations",Boolean.class)).isTrue();
        assertThat(http.calls()).hasSize(before);assertThat(current().providerPaymentReference()).isNull();
    }

    UUID operationId(){return jdbc.queryForObject("select id from provider_operations where resource_id=?",UUID.class,original.id().value());}
    @Test void dueClaimNeverReopensAcknowledgedDispatchAndBlocksCompetingWorker() {
        var claim=pending.claimNext();assertThat(claim).isPresent();
        assertThat(pending.claimNext()).isEmpty();
        assertThat(jdbc.queryForObject("select status from provider_operations",String.class)).isEqualTo("ACKNOWLEDGED");
        assertThat(jdbc.queryForObject("select lease_owner from provider_operations",String.class)).isNull();
        assertThat(claim.orElseThrow().expiresAt()).isAfter(java.time.Instant.now().plusSeconds(110));
    }
    @Test void successfulPendingObservationPreservesEarlierFailuresAndAdvancesToken() {
        jdbc.update("update provider_operations set recovery_attempts=3");
        var claim=pending.claimNext();assertThat(claim).isPresent();
        pending.finish(claim.orElseThrow(),RETRY_PENDING);
        assertThat(jdbc.queryForObject("select recovery_attempts from provider_operations",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select next_attempt_at from provider_operations",java.sql.Timestamp.class).toInstant()).isAfter(claim.orElseThrow().expiresAt());
        assertThatThrownBy(()->pending.finish(claim.orElseThrow(),FAILED)).isInstanceOf(RuntimeException.class);
    }
    @Test void twelfthCrashedAttemptBecomesManualAndNeverGetsThirteenthClaim() {
        jdbc.update("update provider_operations set recovery_attempts=11");
        var claim=pending.claimNext();assertThat(claim).isPresent();
        assertThat(pending.claimNext()).isEmpty();
        assertThat(jdbc.queryForObject("select manual_review_required from provider_operations",Boolean.class)).isFalse();
        jdbc.update("update provider_operations set next_attempt_at=clock_timestamp()-interval '1 second'");
        assertThat(pending.claimNext()).isEmpty();
        assertThat(jdbc.queryForObject("select manual_review_required from provider_operations",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select recovery_attempts from provider_operations",Integer.class)).isEqualTo(12);
    }

    @Test void historicalAdminReconciliationUsesOriginalStripeAccountAndOneAuditReceipt() {
        mapped(); callback();
        var other=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'recovery-routing-account','FAKE','SANDBOX','ACTIVE','other','other-ref',?,0)",other,java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update("update sites set payment_account_id=? where id=?",other,site.value());
        var actor=UUID.fromString("30000000-0000-0000-0000-000000000001");
        snapshot("succeeded","ch_paid");
        assertThatCode(()->admin.reconcile(original.id(),actor,"historical-recovery","recover-admin")).doesNotThrowAnyException();
        assertThat(current().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(current().paymentAccountId()).isEqualTo(account.id());
        snapshot("succeeded","ch_paid");
        admin.reconcile(original.id(),actor,"historical-recovery","recover-admin");
        assertThat(count("payment_events")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='PAYMENT_RECONCILIATION_REQUESTED'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records where status='COMPLETED'",Integer.class)).isEqualTo(1);
    }
}
