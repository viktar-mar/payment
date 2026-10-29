package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCancellationProofStore.*;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionCancellationTransaction;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** Hosted PostgreSQL only. Authored offline; source setup is not provider normalization evidence. */
class StripeObservedCancellationProofIntegrationTest extends Task10StripeProofFixture {
    @Autowired SubscriptionCancellationProofStore proofs;
    @Autowired SubscriptionCancellationTransaction cancellations;
    @Autowired SubscriptionEventStore events;

    @Test void genuineLocalScheduleRemainsAvailableBeforeAndImmediatelyAfterProjection() {
        var local=localSchedule();
        assertThat(proofs.activeSchedule(account.id(),local.subscription().id(),boundary)).contains(local.receipt());
        var event=proofs.observedSchedule(account.id(),local.subscription().id(),boundary).orElseThrow();
        assertThat(events.list(site,local.subscription().id(),100,0).stream().filter(e->e.id().equals(event.event())).findFirst()).hasValueSatisfying(e->assertThat(e.operationId()).isEqualTo(local.receipt().operation()));
        var pending=withdraw(local.subscription());
        var receipt=proofs.record(pending.subscription(),pending.operation(),pending.step(),OWNER,Mode.WITHDRAW,boundary,local.receipt().operation(),received(pending.step()));
        assertThat(receipt.priorOperation()).isEqualTo(local.receipt().operation());assertThat(receipt.observed()).isNull();
    }

    @Test void externalWithdrawalAndRescheduleCannotReuseHistoricalLocalReceiptAtSameBoundary() {
        var old=localSchedule();var cleared=externalClear(old.subscription());
        assertThat(proofs.activeSchedule(account.id(),cleared.id(),boundary)).isEmpty();
        var current=subscriptions.save(cleared.scheduleCancellation(boundary,INITIAL_TIME),cleared.revision());var event=append(current,SubscriptionEvent.Type.CANCEL_SCHEDULED);
        assertThat(proofs.activeSchedule(account.id(),current.id(),boundary)).isEmpty();
        var observed=proofs.observedSchedule(account.id(),current.id(),boundary).orElseThrow();assertThat(observed.event()).isEqualTo(event.id());
        var pending=withdraw(current);var own=record(pending,observed,received(pending.step()));
        assertThat(own.observed()).isEqualTo(observed);assertThat(own.priorOperation()).isNull();assertThat(proofs.receipt(account.id(),old.receipt().operation())).contains(old.receipt());
    }

    @Test void newLocalScheduleAmongHistoricalUnconsumedReceiptsSelectsOnlyCurrentGeneration() {
        var old=localSchedule();externalClear(old.subscription());var current=localSchedule();
        assertThat(proofs.activeSchedule(account.id(),current.subscription().id(),boundary)).contains(current.receipt());
        assertThat(proofs.receipt(account.id(),old.receipt().operation())).contains(old.receipt());
        assertThat(jdbc.queryForObject("select count(*) from stripe_subscription_cancellation_proofs where subscription_id=? and mode='PERIOD_END'",Integer.class,current.subscription().id())).isEqualTo(2);
        var pending=withdraw(current.subscription());var saved=proofs.record(pending.subscription(),pending.operation(),pending.step(),OWNER,Mode.WITHDRAW,boundary,current.receipt().operation(),received(pending.step()));
        assertThat(saved.priorOperation()).isEqualTo(current.receipt().operation());assertThat(saved.priorOperation()).isNotEqualTo(old.receipt().operation());
    }

    @Test void localReceiptCannotBridgeUnprovedRevisionOrLaterCancellationGeneration() {
        var local=localSchedule();jdbc.update("update subscriptions set revision=revision+1 where id=?",local.subscription().id());
        assertThat(proofs.activeSchedule(account.id(),local.subscription().id(),boundary)).isEmpty();
        assertThat(proofs.observedSchedule(account.id(),local.subscription().id(),boundary)).isEmpty();
        assertThat(proofs.receipt(account.id(),local.receipt().operation())).contains(local.receipt());
    }

    @Test void laterWithdrawalEventInvalidatesLocalReceiptEvenWithoutTrustingAnUnchangedRow() {
        var local=localSchedule();append(local.subscription(),SubscriptionEvent.Type.CANCEL_WITHDRAWN);
        assertThat(proofs.activeSchedule(account.id(),local.subscription().id(),boundary)).isEmpty();
        assertThat(proofs.observedSchedule(account.id(),local.subscription().id(),boundary)).isEmpty();
    }

    @Test void currentTypedScheduleCanPrecedeOwnWithdrawalWithoutInventingLocalCancellation() {
        var scheduled = scheduled(SubscriptionEvent.Type.CANCEL_SCHEDULED);
        var observed = proofs.observedSchedule(account.id(), scheduled.subscription().id(), boundary).orElseThrow();
        assertThat(observed).isEqualTo(new ObservedSchedule(scheduled.event().id(), scheduled.subscription().revision(), boundary));
        var pending = withdraw(scheduled.subscription());
        var saved = record(pending, observed, received(pending.step()));
        assertThat(saved.observed()).isEqualTo(observed);
        assertThat(saved.priorOperation()).isNull();
        assertThat(saved.operation()).isEqualTo(pending.operation().id());
        assertThat(saved.step()).isEqualTo(pending.step().id());
        assertThat(saved.account()).isEqualTo(account.id());
        assertThat(saved.mode()).isEqualTo(Mode.WITHDRAW);
        assertThat(saved.reference()).isEqualTo(scheduled.subscription().providerReference());
        assertThat(proofs.receipt(account.id(), saved.operation())).contains(saved);
        assertThat(proofs.observedSchedule(account.id(), saved.subscription(), boundary)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from subscription_operations where kind='CANCEL'", Integer.class)).isZero();
        assertThat(steps.findById(saved.step())).hasValueSatisfying(s -> {
            assertThat(s.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
            assertThat(s.providerReference()).isEqualTo(saved.reference());
            assertThat(s.operationKey()).isEqualTo(pending.operation().providerOperationKey());
        });
        assertThat(subscriptions.find(site, saved.subscription())).contains(scheduled.subscription());
        assertThat(op(saved.operation()).status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        assertThat(count("payments")).isZero();
        assertThat(count("refunds")).isZero();
    }

    @Test void nullOperationOnAnUnrelatedJournalTypeIsNotScheduleAuthority() {
        var scheduled = scheduled(SubscriptionEvent.Type.METHOD_UPDATED);
        assertThat(scheduled.event().operationId()).isNull();
        assertThat(proofs.observedSchedule(account.id(), scheduled.subscription().id(), boundary)).isEmpty();
    }

    @Test void exactCurrentRevisionBoundaryAccountAndSubscriptionAreRequired() {
        var scheduled = scheduled(SubscriptionEvent.Type.CANCEL_SCHEDULED);
        assertThat(proofs.observedSchedule(foreign.account().id(), scheduled.subscription().id(), boundary)).isEmpty();
        assertThat(proofs.observedSchedule(account.id(), other.subscription().id(), boundary)).isEmpty();
        assertThat(proofs.observedSchedule(account.id(), scheduled.subscription().id(), boundary.plusSeconds(1))).isEmpty();
        var current = scheduled.subscription();
        // A later unrelated revision has no continuity proof under the conservative current-event contract.
        jdbc.update("update subscriptions set revision=revision+1 where id=?", current.id());
        assertThat(proofs.observedSchedule(account.id(), current.id(), boundary)).isEmpty();
        evidenceConflict(() -> proofs.requireObserved(sub(current.id()), new ObservedSchedule(scheduled.event().id(), current.revision(), boundary)));
    }

    @Test void laterCancellationGenerationInvalidatesTheEarlierTypedEvent() {
        var scheduled = scheduled(SubscriptionEvent.Type.CANCEL_SCHEDULED);
        append(scheduled.subscription(), SubscriptionEvent.Type.CANCEL_WITHDRAWN);
        append(scheduled.subscription(), SubscriptionEvent.Type.CANCEL_SCHEDULED);
        assertThat(proofs.observedSchedule(account.id(), scheduled.subscription().id(), boundary)).hasValueSatisfying(
            observed -> assertThat(observed.event()).isNotEqualTo(scheduled.event().id()));
        evidenceConflict(() -> proofs.requireObserved(scheduled.subscription(), new ObservedSchedule(scheduled.event().id(), scheduled.subscription().revision(), boundary)));
    }

    @Test void typedEventWithDifferentNativeReferenceCannotAuthorizeWithdrawal() {
        var s = scheduleOnly();
        var event = new SubscriptionEvent(UUID.randomUUID(), site, s.id(), account.id(), 0, s.revision(),
            SubscriptionEvent.Type.CANCEL_SCHEDULED, SubscriptionEvent.Source.RECONCILIATION, INITIAL_TIME, INITIAL_TIME,
            null, null, null, other.subscription().providerReference(), "task10:foreign-reference:"+UUID.randomUUID(), "task10-sql");
        assertThat(events.append(event)).isTrue();
        assertThat(proofs.observedSchedule(account.id(), s.id(), boundary)).isEmpty();
    }

    @Test void receiptRefusesOriginalEventDriftWithoutLosingOwnDispatchOrWritingReceipt() {
        var scheduled = scheduled(SubscriptionEvent.Type.CANCEL_SCHEDULED);
        var observed = proofs.observedSchedule(account.id(), scheduled.subscription().id(), boundary).orElseThrow();
        var pending = withdraw(scheduled.subscription());
        append(scheduled.subscription(), SubscriptionEvent.Type.CANCELLED);
        evidenceConflict(() -> record(pending, observed, received(pending.step())));
        assertUnacknowledged(pending);
    }

    @Test void withdrawalReceiptRejectsWrongOwnerAndExactBoundaryWithoutAcknowledging() {
        var scheduled = scheduled(SubscriptionEvent.Type.CANCEL_SCHEDULED);
        var observed = proofs.observedSchedule(account.id(), scheduled.subscription().id(), boundary).orElseThrow();
        var pending = withdraw(scheduled.subscription());
        assertThatThrownBy(() -> proofs.recordObserved(pending.subscription(), pending.operation(), pending.step(), "stale-owner", observed, received(pending.step())))
            .isInstanceOf(PaymentApplicationException.class);
        evidenceConflict(() -> record(pending, observed, boundary));
        evidenceConflict(() -> record(pending, observed, pending.step().firstDispatchedAt().minusNanos(1000)));
        assertUnacknowledged(pending);
        assertThat(record(pending, observed, received(pending.step())).observed()).isEqualTo(observed);
    }

    @Test void sqlGroupsTypedEventWithOwnerReferenceRevisionAndOwnAcknowledgedStep() {
        var scheduled = scheduled(SubscriptionEvent.Type.CANCEL_SCHEDULED);
        var pending = withdraw(scheduled.subscription());
        var observed = new ObservedSchedule(scheduled.event().id(), scheduled.subscription().revision(), boundary);
        var row = row(pending, observed, received(pending.step()));
        sqlState("23503", () -> insert(row)); // A DISPATCHING step has no acknowledged native-reference tuple.
        acknowledge(pending.step(), pending.subscription().providerReference());
        reject(row, "site_id", foreign.site().value(), "23503");
        reject(row, "customer_id", foreign.customer().id().value(), "23503");
        reject(row, "subscription_id", other.subscription().id(), "23503");
        reject(row, "payment_account_id", foreign.account().id().value(), "23503");
        reject(row, "prior_event_revision", scheduled.subscription().revision()+1, "23503");
        reject(row, "prior_event", append(scheduled.subscription(), SubscriptionEvent.Type.METHOD_UPDATED).id(), "23503");
        reject(row, "prior_event", append(other.subscription(), SubscriptionEvent.Type.CANCEL_SCHEDULED).id(), "23503");
        reject(row, "step_id", original.creationStep().id(), "23503");
        insert(row);
        assertThat(proofs.receipt(account.id(), pending.operation().id())).hasValueSatisfying(p -> assertThat(p.observed()).isEqualTo(observed));
    }

    @Test void sqlRequiresExactlyOneCompletePredecessorAndWithdrawMode() {
        var scheduled = scheduled(SubscriptionEvent.Type.CANCEL_SCHEDULED);
        var pending = withdraw(scheduled.subscription());
        acknowledge(pending.step(), pending.subscription().providerReference());
        var row = row(pending, new ObservedSchedule(scheduled.event().id(), scheduled.subscription().revision(), boundary), received(pending.step()));
        reject(row, "prior_event", null, "23514");
        reject(row, "prior_event_revision", null, "23514");
        reject(row, "prior_event_revision", -1L, "23514");
        reject(row, "prior_event_type", "METHOD_UPDATED", "23514");
        reject(row, "prior_operation", original.operation().id(), "23514");
        reject(row, "mode", "PERIOD_END", "23514");
        reject(row, "boundary", null, "23514");
        insert(row);
        sqlState("23514", () -> jdbc.update("update stripe_subscription_cancellation_proofs set prior_event_revision=prior_event_revision+1 where operation_id=?", pending.operation().id()));
    }

    @Test void failedObservedReceiptInsertRollsBackItsOwnAcknowledgementAtomically() {
        var scheduled=scheduled(SubscriptionEvent.Type.CANCEL_SCHEDULED);
        var observed=proofs.observedSchedule(account.id(),scheduled.subscription().id(),boundary).orElseThrow();
        var pending=withdraw(scheduled.subscription());
        jdbc.execute("create function reject_task10_observed_receipt() returns trigger language plpgsql as $task10$ begin raise exception 'task10 forced failure' using errcode='23514'; end; $task10$");
        jdbc.execute("create trigger reject_task10_observed_receipt before insert on stripe_subscription_cancellation_proofs for each row execute function reject_task10_observed_receipt()");
        try {
            sqlState("23514",()->record(pending,observed,received(pending.step())));
            assertUnacknowledged(pending);
            assertThat(proofs.observedSchedule(account.id(),pending.subscription().id(),boundary)).contains(observed);
        } finally {
            jdbc.execute("drop trigger reject_task10_observed_receipt on stripe_subscription_cancellation_proofs");
            jdbc.execute("drop function reject_task10_observed_receipt()");
        }
        assertThat(record(pending,observed,received(pending.step())).observed()).isEqualTo(observed);
    }

    private record Scheduled(Subscription subscription, SubscriptionEvent event) {}
    private record LocalSchedule(Subscription subscription,Receipt receipt) {}
    private record Pending(Subscription subscription, SubscriptionOperation operation, ProviderOperation step) {}
    private LocalSchedule localSchedule() {
        var s=sub(original.subscription().id());var c=context(local,"cancel-"+UUID.randomUUID());
        var r=cancellations.reserve(c,s.id(),dev.genericpayments.platform.subscriptions.application.port.in.CancelSubscriptionUseCase.Mode.PERIOD_END,null,cancellations.candidate(c,s.id()),admissionSnapshot(s),true,false);
        assertThat(r.owner()).isTrue();var step=claim(steps.reserve(account,ProviderOperation.Kind.SUBSCRIPTION_UPDATE,r.operation().id(),r.operation().providerOperationKey(),"task10-local-cancel-"+r.operation().id()));
        var receipt=proofs.record(r.subscription(),r.operation(),step,OWNER,Mode.PERIOD_END,boundary,null,received(step));
        assertThat(proofs.activeSchedule(account.id(),s.id(),boundary)).contains(receipt);finish(r.operation());
        var scheduled=subscriptions.save(s.scheduleCancellation(boundary,INITIAL_TIME),s.revision());
        var event=new SubscriptionEvent(UUID.randomUUID(),site,scheduled.id(),account.id(),0,scheduled.revision(),SubscriptionEvent.Type.CANCEL_SCHEDULED,SubscriptionEvent.Source.API,INITIAL_TIME,INITIAL_TIME,r.operation().id(),null,null,scheduled.providerReference(),"task10:local-journal:"+UUID.randomUUID(),"task10-sql");
        assertThat(events.append(event)).isTrue();return new LocalSchedule(scheduled,receipt);
    }
    private Subscription externalClear(Subscription s) {
        var cleared=subscriptions.save(s.withdrawCancellation(INITIAL_TIME),s.revision());append(cleared,SubscriptionEvent.Type.CANCEL_WITHDRAWN);return cleared;
    }
    private Subscription scheduleOnly() {
        var s = sub(original.subscription().id());
        subscriptions.save(s.scheduleCancellation(boundary, INITIAL_TIME), s.revision());
        return sub(s.id());
    }
    private Scheduled scheduled(SubscriptionEvent.Type type) { var s=scheduleOnly(); return new Scheduled(s,append(s,type)); }
    private SubscriptionEvent append(Subscription s, SubscriptionEvent.Type type) {
        var e = new SubscriptionEvent(UUID.randomUUID(), s.siteId(), s.id(), s.paymentAccountId(), 0, s.revision(), type,
            SubscriptionEvent.Source.RECONCILIATION, INITIAL_TIME, INITIAL_TIME, null, null, null, s.providerReference(),
            "task10:journal:"+UUID.randomUUID(), "task10-sql");
        assertThat(events.append(e)).isTrue(); return e;
    }
    private Pending withdraw(Subscription s) {
        var c=context(local,"withdraw-"+UUID.randomUUID());
        var r=cancellations.reserve(c,s.id(),null,null,cancellations.candidate(c,s.id()),admissionSnapshot(s),true,true);
        assertThat(r.owner()).isTrue();
        var step=claim(steps.reserve(account,ProviderOperation.Kind.SUBSCRIPTION_UPDATE,r.operation().id(),r.operation().providerOperationKey(),"task10-withdraw-"+r.operation().id()));
        return new Pending(r.subscription(),r.operation(),step);
    }
    private Receipt record(Pending p,ObservedSchedule o,Instant at) { return proofs.recordObserved(p.subscription(),p.operation(),p.step(),OWNER,o,at); }
    private void assertUnacknowledged(Pending p) {
        assertThat(steps.findById(p.step().id())).contains(p.step());
        assertThat(proofs.receipt(account.id(),p.operation().id())).isEmpty();
    }
    private Map<String,Object> row(Pending p,ObservedSchedule o,Instant at) {
        var r=new LinkedHashMap<String,Object>();
        r.put("operation_id",p.operation().id()); r.put("site_id",site.value()); r.put("customer_id",customer.id().value());
        r.put("subscription_id",p.subscription().id()); r.put("payment_account_id",account.id().value());
        r.put("operation_kind","WITHDRAW_CANCEL"); r.put("step_id",p.step().id()); r.put("step_kind","SUBSCRIPTION_UPDATE");
        r.put("subscription_reference",p.subscription().providerReference()); r.put("mode","WITHDRAW");
        r.put("boundary",Timestamp.from(boundary)); r.put("prior_event",o.event()); r.put("prior_event_revision",o.revision());
        r.put("acknowledged_at",Timestamp.from(at)); return r;
    }
    private void insert(Map<String,Object> row) { jdbc.update("insert into stripe_subscription_cancellation_proofs("+String.join(",",row.keySet())+") values ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray()); }
    private void reject(Map<String,Object> row,String column,Object value,String state) { var r=new LinkedHashMap<>(row);r.put(column,value);sqlState(state,()->insert(r)); }
}
