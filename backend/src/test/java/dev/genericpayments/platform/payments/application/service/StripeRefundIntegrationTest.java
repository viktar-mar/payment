package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.in.CreateRefundCommand;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.payments.application.port.out.LoadRefundPort;
import java.time.*;
import java.util.concurrent.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class StripeRefundIntegrationTest extends StripeInboxFixture {
    @Autowired RefundCoordinator coordinator;
    @Autowired ReconcilePaymentService reconciliation;
    final UUID actor=UUID.fromString("30000000-0000-0000-0000-000000000001");
    Payment paid(){mapped();original=save.save(original.succeeded(INITIAL_TIME));return original;}
    CreateRefundCommand command(long amount,String key){return new CreateRefundCommand(site,original.id(),actor,amount,"EUR","REQUESTED_BY_CUSTOMER",null,key,"refund-request");}
    void creation(long amount,String reference){snapshot("succeeded","ch_paid");http.enqueue("POST","/v1/refunds",200,call->generatedRefund(reference,amount,call));}
    Refund reserve(long amount){var id=new RefundId(UUID.randomUUID());return saveRefunds.insert(Refund.request(id,current(),Money.of(amount,"EUR"),ProviderOperationKeys.refund(id),"REQUESTED_BY_CUSTOMER",null,INITIAL_TIME)).value();}
    Refund createReserved(long amount,String reference){var r=reserve(amount);snapshot("succeeded","ch_paid");http.enqueue("POST","/v1/refunds",200,refundJson(reference,amount,"succeeded",r));var result=provider.requestRefund(new ProviderRefundRequest(currentAccount(),current(),r,r.providerOperationKey()));assertThat(result.reference()).isEqualTo(reference);return saveRefunds.save(new LoadRefundPort.StoredRefund(r.withProviderRefundReference(reference,INITIAL_TIME),0)).value();}
    ProviderReferenceReceipt refundReceipt(Refund r,String event){return new ProviderReferenceReceipt(dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.STRIPE,account.id(),event,r.providerRefundReference(),ProviderReferenceReceipt.ObjectType.REFUND,"refund.updated","2026-09-30.endive",INITIAL_TIME,Instant.now());}
    void refundSnapshot(Refund r,String status){identity();http.enqueue("GET","/v1/refunds/"+r.providerRefundReference(),200,refundJson(r.providerRefundReference(),r.money().minorUnits(),status,r));http.enqueue("GET","/v1/payment_intents/pi_owned",200,intent("succeeded","ch_paid",2000));charge("ch_paid",true);}
    void settle(Refund r,String status,String event){refundSnapshot(r,status);inbox.insertReference(refundReceipt(r,event));worker.runOnce();}
    String refundJson(String reference,long amount,String status,Refund r){return "{\"id\":\""+reference+"\",\"object\":\"refund\",\"amount\":"+amount+",\"currency\":\"eur\",\"payment_intent\":\"pi_owned\",\"charge\":\"ch_paid\",\"status\":\""+status+"\",\"metadata\":{"+(r==null?"":"\"refund_id\":\""+r.id().value()+"\",\"payment_id\":\""+original.id().value()+"\"")+"}}";}
    @Test void synchronousRefundAcknowledgementDoesNotSettleAndSameKeyCannotChangeAmount(){
        paid();creation(700,"re_partial");var r=coordinator.create(command(700,"partial"));
        assertThat(r.providerRefundReference()).isEqualTo("re_partial");assertThat(r.status()).isIn(RefundStatus.PENDING,RefundStatus.PROCESSING);
        assertThat(current().refundedAmountMinor()).isZero();
        assertThat(events.findByPayment(site,original.id())).extracting(PaymentEvent::type).doesNotContain(PaymentEventType.REFUND_SUCCEEDED);
        identity();assertThat(coordinator.create(command(700,"partial")).id()).isEqualTo(r.id());
        assertThatThrownBy(()->coordinator.create(command(701,"partial"))).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(http.calls().stream().filter(c->c.path().equals("/v1/refunds")&&c.method().equals("POST"))).hasSize(1);
    }
    @Test void disconnectedRefundResponseKeepsAmountReservedAndReusesOriginalOperation(){
        paid();factory.readTimeout=100;snapshot("succeeded","ch_paid");http.disconnectNext("POST","/v1/refunds");
        var r=coordinator.create(command(1400,"unknown"));assertThat(r.status()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(jdbc.queryForList("select status from provider_operations where kind='REFUND_CREATE'",String.class)).containsExactly("UNKNOWN");
        assertThatThrownBy(()->coordinator.create(command(601,"excess"))).extracting("code").isEqualTo("REFUND_EXCEEDS_AVAILABLE");
        factory.readTimeout=5000;creation(1400,"re_recovered");var replay=coordinator.create(command(1400,"unknown"));
        assertThat(replay.id()).isEqualTo(r.id());assertThat(replay.providerRefundReference()).isEqualTo("re_recovered");
        assertThat(http.calls().stream().filter(c->c.path().equals("/v1/refunds")&&c.method().equals("POST"))).hasSize(2).allSatisfy(c->assertThat(c.header("Idempotency-Key")).isEqualTo(ProviderOperationKeys.refund(r.id())));
        assertThat(current().refundedAmountMinor()).isZero();
    }
    @Test void historicalStripeRefundUsesOriginalAccountAfterSiteReassignment(){
        paid();var other=UUID.randomUUID();jdbc.update("insert into payment_accounts values (?,'other-refund-account','FAKE','SANDBOX','ACTIVE','other','other-ref',?,0)",other,java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update("update sites set payment_account_id=? where id=?",other,site.value());
        creation(700,"re_historical");var r=coordinator.create(command(700,"historical"));
        assertThat(r.providerRefundReference()).isEqualTo("re_historical");assertThat(current().paymentAccountId()).isEqualTo(account.id());
    }
    @Test void partialAndFullIndividualSuccessProduceExactSumAndOneCallbackEach(){
        paid();callback();var one=createReserved(700,"re_one");settle(one,"succeeded","evt_refundone");
        assertThat(current().status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);assertThat(current().refundedAmountMinor()).isEqualTo(700);
        settle(one,"succeeded","evt_refundduplicate");var two=createReserved(1300,"re_two");settle(two,"succeeded","evt_refundtwo");
        assertThat(current().status()).isEqualTo(PaymentStatus.REFUNDED);assertThat(current().refundedAmountMinor()).isEqualTo(2000);
        assertThat(count("outbox_events")).isEqualTo(2);assertThat(events.findByPayment(site,original.id())).extracting(PaymentEvent::type).containsExactly(PaymentEventType.REFUND_SUCCEEDED,PaymentEventType.REFUND_SUCCEEDED);
    }
    @Test void individualPendingAndFailureNeverCountAsSuccessfulCash(){
        paid();var r=createReserved(700,"re_pending");settle(r,"pending","evt_pendingrefund");
        assertThat(refunds.findById(site,r.id()).orElseThrow().value().status()).isEqualTo(RefundStatus.PROCESSING);assertThat(current().refundedAmountMinor()).isZero();
        settle(r,"failed","evt_failedrefund");assertThat(refunds.findById(site,r.id()).orElseThrow().value().status()).isEqualTo(RefundStatus.FAILED);assertThat(current().refundedAmountMinor()).isZero();
        creation(2000,"re_afterfailure");assertThat(coordinator.create(command(2000,"after-failure")).providerRefundReference()).isEqualTo("re_afterfailure");
    }
    @Test void refundJournalFailureRollsBackRefundParentOutboxAndInboxCompletion(){
        paid();callback();var r=createReserved(700,"re_rollback");rejectJournal(PaymentEventType.REFUND_SUCCEEDED);settle(r,"succeeded","evt_rollbackrefund");
        assertThat(refunds.findById(site,r.id()).orElseThrow().value().status()).isEqualTo(RefundStatus.PENDING);assertThat(current().refundedAmountMinor()).isZero();assertThat(count("outbox_events")).isZero();assertThat(inboxStatus()).isNotEqualTo("PROCESSED");
    }
    @Test void verifiedSingleRefundReconciliationSettlesAndReleasesParentLease(){
        paid();var r=createReserved(700,"re_reconcile");var lease=transitions.acquire(site,original.id(),"single-refund",Duration.ofSeconds(30));
        refundSnapshot(r,"succeeded");var proof=provider.retrieveRefund(ProviderRefundLookup.forRefund(currentAccount(),original.id(),r.id(),r.providerRefundReference()));
        refundTransitions.reconcile(lease,r.id(),proof,"single-refund");
        assertThat(current().refundedAmountMinor()).isEqualTo(700);assertThat(jdbc.queryForObject("select reconciliation_lease_owner from payments where id=?",String.class,original.id().value())).isNull();
    }
    @Test void originalStripeAcquisitionUsesRealBoundedLeaseAfterSiteReassignment(){
        paid();var other=UUID.randomUUID();jdbc.update("insert into payment_accounts values (?,'other-reconcile-account','FAKE','SANDBOX','ACTIVE','other','other-ref',?,0)",other,java.sql.Timestamp.from(INITIAL_TIME));jdbc.update("update sites set payment_account_id=? where id=?",other,site.value());
        var referenceClock=context.getBean("providerReferenceClock",Clock.class);
        var before=referenceClock.instant();var lease=transitions.acquire(site,original.id(),"historical-refund",Duration.ofSeconds(1));var after=referenceClock.instant();assertThat(lease.account().id()).isEqualTo(account.id());
        var expiry=jdbc.queryForObject("select reconciliation_lease_expires_at from payments where id=?",java.sql.Timestamp.class,original.id().value()).toInstant();
        assertThat(expiry).isBetween(before.plusSeconds(120),after.plusSeconds(120));
    }
    @Test void paymentReconciliationExpiryAfterJournalRollsBackFinancialWrites(){
        mapped();callback();var lease=transitions.acquire(site,original.id(),"late-payment",Duration.ofSeconds(30));snapshot("succeeded","ch_paid");var proof=provider.retrievePayment(ProviderPaymentLookup.forPayment(currentAccount(),original.id(),"pi_owned"));
        jdbc.execute("create function task10_delay_journal() returns trigger language plpgsql as $$ begin perform pg_sleep(0.6); return new; end; $$");jdbc.execute("create trigger task10_delay_journal after insert on payment_events for each row execute function task10_delay_journal()");
        try{jdbc.update("update payments set reconciliation_lease_expires_at=clock_timestamp()+interval '0.3 seconds' where id=?",original.id().value());assertThatThrownBy(()->transitions.reconcile(lease,proof,"late-payment")).isInstanceOf(RuntimeException.class);}
        finally{jdbc.execute("drop trigger task10_delay_journal on payment_events");jdbc.execute("drop function task10_delay_journal()");}
        assertThat(current().status()).isEqualTo(PaymentStatus.CREATED);assertThat(count("payment_events")).isZero();assertThat(count("outbox_events")).isZero();
    }
    @Test void singleRefundEntryMakesExactlyFourReadsAndPaymentEntryDoesNotScanRefunds(){
        paid();var one=createReserved(700,"re_single");createReserved(500,"re_other");int before=http.calls().size();refundSnapshot(one,"succeeded");
        assertThat(reconciliation.reconcileRefund(site,original.id(),one.id(),"single-entry").status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(http.calls().subList(before,http.calls().size())).extracting(c->c.path()).containsExactly("/v1/account","/v1/refunds/re_single","/v1/payment_intents/pi_owned","/v1/charges/ch_paid");
        before=http.calls().size();snapshot("succeeded","ch_paid");reconciliation.reconcile(site,original.id(),"payment-only");
        assertThat(http.calls().subList(before,http.calls().size())).extracting(c->c.path()).containsExactly("/v1/account","/v1/payment_intents/pi_owned","/v1/charges/ch_paid");assertThat(current().refundedAmountMinor()).isEqualTo(700);
    }
    @ParameterizedTest @ValueSource(strings={"refund-amount","refund-reason","parent-amount","account","operation-fingerprint"})
    void lockedFactsMustStillMatchIndividualRefundProof(String change){
        paid();var r=createReserved(700,"re_mutated");inbox.insertReference(refundReceipt(r,"evt_mutatedrefund"));var claim=inbox.claimReference(Duration.ofSeconds(120)).orElseThrow();refundSnapshot(r,"succeeded");var proof=resolution.resolve(inbox.reference(claim).orElseThrow());
        switch(change){case "refund-amount"->jdbc.update("update refunds set amount_minor=701 where id=?",r.id().value());case "refund-reason"->jdbc.update("update refunds set reason_code='DUPLICATE' where id=?",r.id().value());case "parent-amount"->jdbc.update("update payments set amount_minor=2001 where id=?",original.id().value());case "account"->jdbc.update("update payment_accounts set external_account_id='acct_changed' where id=?",account.id().value());default->jdbc.update("update provider_operations set fingerprint=repeat('a',64) where kind='REFUND_CREATE'");}
        assertThatThrownBy(()->transitions.processVerified(claim,proof)).isInstanceOf(RuntimeException.class);assertThat(current().refundedAmountMinor()).isZero();assertThat(count("payment_events")).isZero();assertThat(inboxStatus()).isEqualTo("PROCESSING");
    }
    @Test void refundExpiryAfterJournalRollsBackSettlementAndOwnedLeaseRelease(){
        paid();callback();var r=createReserved(700,"re_late");var lease=transitions.acquire(site,original.id(),"late-refund",Duration.ofSeconds(30));refundSnapshot(r,"succeeded");var proof=provider.retrieveRefund(ProviderRefundLookup.forRefund(currentAccount(),original.id(),r.id(),r.providerRefundReference()));
        jdbc.execute("create function task10_delay_refund() returns trigger language plpgsql as $$ begin perform pg_sleep(0.6); return new; end; $$");jdbc.execute("create trigger task10_delay_refund after insert on payment_events for each row execute function task10_delay_refund()");
        try{jdbc.update("update payments set reconciliation_lease_expires_at=clock_timestamp()+interval '0.3 seconds' where id=?",original.id().value());assertThatThrownBy(()->refundTransitions.reconcile(lease,r.id(),proof,"late-refund")).isInstanceOf(RuntimeException.class);}
        finally{jdbc.execute("drop trigger task10_delay_refund on payment_events");jdbc.execute("drop function task10_delay_refund()");}
        assertThat(current().refundedAmountMinor()).isZero();assertThat(refunds.findById(site,r.id()).orElseThrow().value().status()).isEqualTo(RefundStatus.PENDING);assertThat(count("payment_events")).isZero();assertThat(count("outbox_events")).isZero();assertThat(jdbc.queryForObject("select reconciliation_lease_owner from payments where id=?",String.class,original.id().value())).isEqualTo(lease.owner());
    }
    @Test void completionFailureRollsBackIndividualRefundAndParent(){
        paid();callback();var r=createReserved(700,"re_completion");jdbc.execute("create function task10_fail_complete() returns trigger language plpgsql as $$ begin if new.status='PROCESSED' then raise exception 'completion failed' using errcode='23514'; end if; return new; end; $$");jdbc.execute("create trigger task10_fail_complete before update on provider_inbox for each row execute function task10_fail_complete()");
        try{settle(r,"succeeded","evt_refundcompletion");}finally{jdbc.execute("drop trigger task10_fail_complete on provider_inbox");jdbc.execute("drop function task10_fail_complete()");}
        assertThat(current().refundedAmountMinor()).isZero();assertThat(refunds.findById(site,r.id()).orElseThrow().value().status()).isEqualTo(RefundStatus.PENDING);assertThat(count("payment_events")).isZero();assertThat(count("outbox_events")).isZero();
        jdbc.update("update provider_inbox set next_attempt_at=clock_timestamp()");refundSnapshot(r,"succeeded");worker.runOnce();assertThat(current().refundedAmountMinor()).isEqualTo(700);
    }
    @Test void unknownRefundCannotUseMetadataAloneToBindOrSettle(){
        paid();var r=reserve(700).withProviderRefundReference("re_unproven",INITIAL_TIME);settle(r,"succeeded","evt_unprovenrefund");
        assertThat(refunds.findById(site,r.id()).orElseThrow().value().providerRefundReference()).isNull();assertThat(current().refundedAmountMinor()).isZero();assertThat(count("payment_events")).isZero();assertThat(inboxStatus()).isEqualTo("FAILED");
    }
    String generatedRefund(String reference,long amount,dev.genericpayments.platform.support.StripeHttpFixture.Call call){return refundJson(reference,amount,"succeeded",null).replace("\"metadata\":{}","\"metadata\":{\"refund_id\":\""+call.form().get("metadata[refund_id]")+"\",\"payment_id\":\""+call.form().get("metadata[payment_id]")+"\"}");}
    @Test void twoConcurrentPartialReservationsStayUnavailableUntilVerifiedSettlement() throws Exception {
        paid();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);snapshot("succeeded","ch_paid");
        http.enqueue("POST","/v1/refunds",200,call->{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("refund response not released");}catch(InterruptedException e){throw new AssertionError(e);}return generatedRefund("re_parallelone",1000,call);});
        try(var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(()->coordinator.create(command(1000,"parallel-one")));
            try{assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();creation(1000,"re_paralleltwo");var second=coordinator.create(command(1000,"parallel-two"));assertThat(second.providerRefundReference()).isEqualTo("re_paralleltwo");assertThatThrownBy(()->coordinator.create(command(1,"over-reserved"))).extracting("code").isEqualTo("REFUND_EXCEEDS_AVAILABLE");}
            finally{release.countDown();}
            assertThat(first.get(5,TimeUnit.SECONDS).providerRefundReference()).isEqualTo("re_parallelone");
        }
        assertThat(count("refunds")).isEqualTo(2);assertThat(current().refundedAmountMinor()).isZero();assertThat(jdbc.queryForObject("select count(*) from audit_log where action='PAYMENT_REFUND_REQUESTED'",Integer.class)).isEqualTo(2);
    }
    @Test void webhookBeforeResponseWaitsForOriginalAckThenRepairsOnlyThatRefund() throws Exception {
        paid();var r=reserve(700);snapshot("succeeded","ch_paid");http.enqueue("POST","/v1/refunds",200,refundJson("re_race",700,"succeeded",r),Map.of(),1500);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var response=pool.submit(()->provider.requestRefund(new ProviderRefundRequest(currentAccount(),current(),r,r.providerOperationKey())));
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();while(http.calls().stream().noneMatch(c->c.path().equals("/v1/refunds")&&c.method().equals("POST"))&&System.nanoTime()<deadline)Thread.sleep(10);
            assertThat(http.calls().stream().filter(c->c.path().equals("/v1/refunds")&&c.method().equals("POST"))).hasSize(1);
            var candidate=r.withProviderRefundReference("re_race",INITIAL_TIME);identity();http.enqueue("GET","/v1/refunds/re_race",200,refundJson("re_race",700,"succeeded",r));inbox.insertReference(refundReceipt(candidate,"evt_beforeresponse"));worker.runOnce();
            assertThat(current().refundedAmountMinor()).isZero();assertThat(inboxStatus()).isEqualTo("FAILED");assertThat(response.get(5,TimeUnit.SECONDS).reference()).isEqualTo("re_race");
            jdbc.update("update provider_inbox set next_attempt_at=clock_timestamp()");refundSnapshot(candidate,"succeeded");worker.runOnce();
            assertThat(current().refundedAmountMinor()).isEqualTo(700);assertThat(refunds.findById(site,r.id()).orElseThrow().value().providerRefundReference()).isEqualTo("re_race");assertThat(count("payment_events")).isEqualTo(1);
            identity();var late=coordinator.resume(original,r,currentAccount(),new dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.Context("test-site",dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.Provider.STRIPE),"late-ack");
            assertThat(late.status()).isEqualTo(RefundStatus.SUCCEEDED);assertThat(count("payment_events")).isEqualTo(1);
        }
    }
    @ParameterizedTest @ValueSource(strings={"refund","account","payment"})
    void changedCommittedFactsDuringProviderResponseCannotBindAcknowledgement(String change){
        paid();snapshot("succeeded","ch_paid");http.enqueue("POST","/v1/refunds",200,call->{switch(change){case "refund"->jdbc.update("update refunds set amount_minor=701 where id=?",UUID.fromString(call.form().get("metadata[refund_id]")));case "account"->jdbc.update("update payment_accounts set external_account_id='acct_changed' where id=?",account.id().value());default->jdbc.update("update payments set amount_minor=2001 where id=?",original.id().value());}return generatedRefund("re_changed",700,call);});
        assertThatThrownBy(()->coordinator.create(command(700,"changed-ack"))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select provider_refund_reference from refunds",String.class)).isNull();assertThat(current().refundedAmountMinor()).isZero();
    }
    @Test void longValidProviderEventIdUsesBoundedLocalJournalCorrelation(){
        paid();var r=createReserved(700,"re_longevent");settle(r,"succeeded","evt_"+"a".repeat(200));
        assertThat(current().refundedAmountMinor()).isEqualTo(700);assertThat(inboxStatus()).isEqualTo("PROCESSED");assertThat(events.findByPayment(site,original.id())).singleElement().satisfies(e->assertThat(e.requestId().length()).isLessThanOrEqualTo(128));
    }
    @Test void originalAckCannotRepairRefundReferenceUnderAnotherLiveLocalLease(){
        paid();var r=reserve(700);snapshot("succeeded","ch_paid");http.enqueue("POST","/v1/refunds",200,refundJson("re_lease",700,"succeeded",r));provider.requestRefund(new ProviderRefundRequest(currentAccount(),current(),r,r.providerOperationKey()));
        transitions.acquire(site,original.id(),"live-local",Duration.ofSeconds(30));var candidate=r.withProviderRefundReference("re_lease",INITIAL_TIME);settle(candidate,"succeeded","evt_livelease");
        assertThat(refunds.findById(site,r.id()).orElseThrow().value().providerRefundReference()).isNull();assertThat(current().refundedAmountMinor()).isZero();
        jdbc.update("update payments set reconciliation_lease_expires_at=clock_timestamp()-interval '1 second' where id=?",original.id().value());jdbc.update("update provider_inbox set next_attempt_at=clock_timestamp()");refundSnapshot(candidate,"succeeded");worker.runOnce();assertThat(current().refundedAmountMinor()).isEqualTo(700);
    }
}
