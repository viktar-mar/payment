package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import dev.genericpayments.platform.provider.application.service.OwnedFirstRenewalAdmissionService;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionLifecycleLockPort;
import dev.genericpayments.platform.support.StripeBillingClockConfirmationCompositionFixture;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.*;
import static org.assertj.core.api.Assertions.*;

/** Hosted only: real initial confirmation/CASH, actual advances and lifecycle-locked read/boundary writers. */
class StripeBillingFirstRenewalObservationIntegrationTest extends StripeBillingClockConfirmationCompositionFixture {
    @Autowired OwnedFirstRenewalStore renewals;
    @Autowired LoadPaymentAccountPort accounts;
    @Autowired LockPaymentAccountPort accountLocks;
    @Autowired SubscriptionLifecycleLockPort lifecycleLocks;

    private Admission paidAdmission(){
        beginConfirmation();issueAndSelect();confirm("succeeded");var captured=fence();apply(paidObservation(),captured);assertPaid();
        return new OwnedFirstRenewalAdmissionService(renewals,transaction,lifecycleOperations,lifecycleLocks,accountLocks,subscriptions,Clock.systemUTC())
            .admit(receipt,"a".repeat(40));
    }
    private StripeBillingFirstRenewalAdapter observer(){
        return new StripeBillingFirstRenewalAdapter(credentials,clients,renewals,transaction,lifecycleOperations,lifecycleLocks,accountLocks,
            subscriptions,readPreparation,observations,bindings,Clock.systemUTC());
    }
    private String clock(long frozen,String status){var value=json(clockJson(owner));value.addProperty("frozen_time",frozen);value.addProperty("status",status);return value.toString();}
    private LaterReady advance(Admission admission,Step step){
        long before=step==Step.BOUNDARY?FROZEN:receipt.periodEnd(),target=step==Step.BOUNDARY?receipt.periodEnd():receipt.periodEnd()+3600;
        http.reply(clock(before,"ready"));http.reply(clock(before,"advancing"));http.reply(clock(target,"ready"));
        return new StripeBillingFirstRenewalAdvanceAdapter(credentials,clients,accounts,operations,renewals,Clock.systemUTC()).advance(admission,step);
    }
    private void graph(LaterReady ready,boolean paid){
        long b=receipt.periodEnd(),n=b+31*86400;
        var renewal=graph.invoice.deepCopy();renewal.addProperty("id","in_renewal");renewal.addProperty("billing_reason","subscription_cycle");
        renewal.addProperty("status",paid?"paid":"draft");renewal.addProperty("amount_paid",paid?1000:0);renewal.addProperty("amount_remaining",paid?0:1000);
        renewal.addProperty("amount_paid_off_stripe",0);
        var line=graph.line.deepCopy();line.addProperty("id","il_renewal");line.addProperty("invoice","in_renewal");
        line.addProperty("subscription","sub_owned");line.addProperty("subscription_item","si_owned");
        line.getAsJsonObject("period").addProperty("start",b);line.getAsJsonObject("period").addProperty("end",n);
        graph.item().addProperty("current_period_start",b);graph.item().addProperty("current_period_end",n);
        http.reply(clock(ready.observation().frozenTime(),"ready"));http.reply(customerJson("clock_owned"));http.reply(graph.remote.toString());
        http.reply(page(graph.invoice,renewal));http.reply(page(graph.line));http.reply(page(graph.allocation));http.reply(graph.pi.toString());
        http.reply(graph.charge.toString());http.reply(page(graph.allocation));http.reply(page(line));
        if(paid){
            var allocation=graph.allocation.deepCopy();allocation.addProperty("id","inpay_renewal");allocation.addProperty("invoice","in_renewal");
            allocation.getAsJsonObject("payment").addProperty("payment_intent","pi_renewal");allocation.getAsJsonObject("status_transitions").addProperty("paid_at",b+11);
            var pi=graph.pi.deepCopy();pi.addProperty("id","pi_renewal");pi.addProperty("latest_charge","ch_renewal");
            var charge=graph.charge.deepCopy();charge.addProperty("id","ch_renewal");charge.addProperty("payment_intent","pi_renewal");
            http.reply(page(allocation));http.reply(pi.toString());http.reply(charge.toString());http.reply(page(allocation));
        }else http.reply(page());
        http.reply(clock(ready.observation().frozenTime(),"ready"));
    }
    private <T>T underLifecycle(Admission admission,Supplier<T> work){
        return transaction.required(()->{
            var ids=new TreeSet<UUID>();ids.add(admission.receipt().intent().createOperation());
            lifecycleOperations.unresolved(site,current().id()).forEach(operation->ids.add(operation.id()));for(var id:ids)lifecycleOperations.lock(id);
            lifecycleLocks.lockActive(site,customer);accountLocks.findByIdForUpdate(billing.id()).orElseThrow();subscriptions.lock(site,current().id());return work.get();
        });
    }

    @Test void genuineInitialCashBoundaryAndFinalCashRemainPrivate(){
        var admission=paidAdmission();var before=financialState();var first=advance(admission,Step.BOUNDARY);var originalFence=fence();
        int reads=http.calls.size();graph(first,false);
        inspectSdk(request->{assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(1);
            assertThat(request.method().name()).isEqualTo("GET");assertThat(request.options().getReadTimeout()).isBetween(1,2000);
            assertThat(request.options().getMaxNetworkRetries()).isZero();});
        var pending=(Pending)observer().observe(admission,first);assertThat(pending.claim().fence()).isEqualTo(originalFence);
        assertThat(http.calls).hasSize(reads+12);assertThat(financialState()).isEqualTo(before);
        assertThat(underLifecycle(admission,()->renewals.recordBoundary(admission,first,pending.boundary()))).isEqualTo(pending.boundary());
        assertThat(count("stripe_billing_first_renewal_boundary")).isEqualTo(1);
        int completed=http.calls.size();assertThatThrownBy(()->observer().observe(admission,first)).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(completed);assertThat(jdbc.queryForObject("select stopped_at from stripe_billing_first_renewals",java.sql.Timestamp.class)).isNull();
        // Remove only this test's count=1 SDK inspection before the second legitimate claim/advance.
        http.afterResponse=null;
        var second=advance(admission,Step.FINALIZATION);var finalFence=fence();graph(second,true);
        var paid=(Paid)observer().observe(admission,second);
        assertThat(paid.claim().ready()).isEqualTo(second);assertThat(paid.fence()).isEqualTo(finalFence).isEqualTo(paid.claim().fence());
        assertThat(paid.boundary()).isEqualTo(pending.boundary());assertThat(paid.graphAcceptedAt()).isBefore(paid.claim().deadlineAt());
        assertThat(paid.snapshot().invoices()).hasSize(2);assertThat(paid.snapshot().invoices().getLast().cashObjects().getFirst().reference()).isEqualTo("pi_renewal");
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(2);assertThat(count("stripe_billing_first_renewal_publications")).isZero();
        assertThat(financialState()).isEqualTo(before);assertThat(http.replies).isEmpty();
    }

    @Test void crashAfterCommittedReadStartCannotResumeOrStopOwner(){
        var admission=paidAdmission();var first=advance(admission,Step.BOUNDARY);var before=financialState();
        var claim=underLifecycle(admission,()->renewals.beginReadInTransaction(admission,first,fence(),UUID.randomUUID(),Instant.now()));
        int calls=http.calls.size();assertThatThrownBy(()->observer().observe(admission,first)).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(calls);assertThat(underLifecycle(admission,()->renewals.readClaim(admission,first).orElseThrow())).isEqualTo(claim);
        assertThat(jdbc.queryForObject("select stopped_at from stripe_billing_first_renewals",java.sql.Timestamp.class)).isNull();
        assertThat(financialState()).isEqualTo(before);
    }

    @Test void concurrentClaimLoserCannotReadOrStopWinner() throws Exception {
        var admission=paidAdmission();var first=advance(admission,Step.BOUNDARY);var before=financialState();graph(first,false);
        var reading=new CountDownLatch(1);var release=new CountDownLatch(1);int start=http.calls.size();
        http.afterResponse=()->{reading.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("winner release timed out");}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}};
        try(var workers=new BoundedWorkers()){
            var winner=workers.submit(()->observer().observe(admission,first));assertThat(reading.await(5,TimeUnit.SECONDS)).isTrue();
            try{
                var loser=workers.submit(()->{assertThatThrownBy(()->observer().observe(admission,first)).isInstanceOf(RuntimeException.class);return true;});
                assertThat(loser.get(5,TimeUnit.SECONDS)).isTrue();assertThat(http.calls).hasSize(start+1);
                assertThat(jdbc.queryForObject("select stopped_at from stripe_billing_first_renewals",java.sql.Timestamp.class)).isNull();
            }finally{release.countDown();}
            assertThat(winner.get(10,TimeUnit.SECONDS)).isInstanceOf(Pending.class);
        }
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(1);assertThat(financialState()).isEqualTo(before);
    }

    @Test void staleFenceAndAmbientTransactionFailBeforeAnyIo(){
        var admission=paidAdmission();var first=advance(admission,Step.BOUNDARY);int calls=http.calls.size();var captured=fence();
        var stale=new dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence(captured.revision(),null,null,null,null,captured.observationSequence()+1);
        assertThatThrownBy(()->underLifecycle(admission,()->renewals.beginReadInTransaction(admission,first,stale,UUID.randomUUID(),Instant.now()))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->transaction.required(()->observer().observe(admission,first))).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_first_renewal_read_claims")).isZero();
    }

    @Test void readClaimAndBoundaryAreImmutableExactLineage(){
        var admission=paidAdmission();var first=advance(admission,Step.BOUNDARY);graph(first,false);var pending=(Pending)observer().observe(admission,first);
        for(var table:List.of("stripe_billing_first_renewal_read_claims","stripe_billing_first_renewal_boundary")){
            assertThatThrownBy(()->jdbc.update("delete from "+table)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThatThrownBy(()->jdbc.update("update stripe_billing_first_renewal_read_claims set invocation=gen_random_uuid()"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update stripe_billing_first_renewal_boundary set invoice_reference='in_foreign'"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(renewals.boundary(admission)).isEqualTo(pending.boundary());
    }
    @Test void sqlRejectsForeignSelectionAndStaleSequenceBeforeValidGraph(){
        var admission=paidAdmission();var first=advance(admission,Step.BOUNDARY);var captured=fence();
        var at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        for(boolean foreignSelection:List.of(false,true)){
            assertThatThrownBy(()->jdbc.update("""
                insert into stripe_billing_first_renewal_read_claims(ready_id,step_id,admission_id,invocation,started_at,deadline_at,
                 captured_revision,selected_operation,selected_version,observation_sequence) values(?,?,?,?,?,?,?,?,?,?)
                """,first.id(),first.ack().advance().id(),admission.id(),UUID.randomUUID(),java.sql.Timestamp.from(at),java.sql.Timestamp.from(at.plusSeconds(30)),
                captured.revision(),foreignSelection?currentOperation().id():null,foreignSelection?currentOperation().version():null,
                foreignSelection?captured.observationSequence():captured.observationSequence()+1)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThat(count("stripe_billing_first_renewal_read_claims")).isZero();graph(first,false);
        assertThat(observer().observe(admission,first)).isInstanceOf(Pending.class);assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(1);
    }
    @Test void actualReadDeadlineRejectsLateBoundaryAndNeverRestarts() throws InterruptedException {
        var admission=paidAdmission();var first=advance(admission,Step.BOUNDARY);var before=financialState();
        var claim=underLifecycle(admission,()->renewals.beginReadInTransaction(admission,first,fence(),UUID.randomUUID(),Instant.now()));
        var id=UUID.randomUUID();var period=new dev.genericpayments.platform.subscriptions.domain.Subscription.Period(
            Instant.ofEpochSecond(receipt.periodEnd()),Instant.ofEpochSecond(receipt.periodEnd()+31*86400));
        var accepted=claim.startedAt().plusMillis(1);
        var candidate=new Boundary(id,admission.id(),first,"si_owned","in_renewal",period,
            dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.fingerprint("gp:owned-first-renewal-boundary:v1",id,admission.id(),first.id(),
                "si_owned","in_renewal",period.start(),period.end(),accepted),accepted);
        // Genuine UTC expiry; no persisted timestamp, DB clock or original claim is changed.
        long began=System.nanoTime();
        while(Instant.now().isBefore(claim.deadlineAt().plusMillis(50))){
            if(System.nanoTime()-began>Duration.ofSeconds(35).toNanos())throw new AssertionError("Read deadline wait exceeded35 seconds");
            Thread.sleep(100);
        }
        assertThatThrownBy(()->underLifecycle(admission,()->renewals.recordBoundary(admission,first,candidate)))
            .as("A pre-deadline observedAt cannot authorize a boundary INSERT after the real original deadline")
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        int calls=http.calls.size();assertThatThrownBy(()->observer().observe(admission,first)).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_first_renewal_boundary")).isZero();
        assertThat(underLifecycle(admission,()->renewals.readClaim(admission,first).orElseThrow())).isEqualTo(claim);
        assertThat(financialState()).isEqualTo(before);assertThat(jdbc.queryForObject("select stopped_at from stripe_billing_first_renewals",java.sql.Timestamp.class)).isNull();
    }
}
