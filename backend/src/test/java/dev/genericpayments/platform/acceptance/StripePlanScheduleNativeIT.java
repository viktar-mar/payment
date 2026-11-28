package dev.genericpayments.platform.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.NativePaidPlanFixture;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named="PAYMENT_STRIPE_BILLING_CASE",matches="plan_schedule")
class StripePlanScheduleNativeIT extends NativePaidPlanFixture {
    @Autowired private PreviewSubscriptionChangeUseCase previews;
    @Autowired private ChangeSubscriptionPlanUseCase changes;
    @Autowired private ClearSubscriptionChangeUseCase clears;
    @Autowired private SubscriptionPlanProofStore proofs;
    @Autowired private JdbcTemplate jdbc;

    @Test void originalScheduleCanBeClearedBeforeThePaidBoundary() throws Exception {
        var plan=provisionPaidPlan(1500);
        var original=local(plan);
        var cash=paidEvidence(plan);
        assertThat(cash.size()).isOne();
        var quote=previews.preview(new PreviewSubscriptionChangeUseCase.Query(context(plan,null),
            plan.subscriptionId(),plan.target(),ChangeTiming.NEXT_PERIOD));
        assertThat(quote.dueNowMinor()).isZero();
        assertThat(quote.nextAmountMinor()).isEqualTo(1500);
        assertThat(quote.effectiveAt()).isEqualTo(original.paidThrough());
        var change=changes.change(new ChangeSubscriptionPlanUseCase.Command(context(plan,key()),plan.subscriptionId(),quote.id()));
        assertThat(change.status()).as("schedule mutation acknowledgement").isEqualTo(SubscriptionOperation.Status.PROCESSING);
        var schedule=proofs.schedule(plan.account().id(),change.operationId()).orElseThrow();
        assertThat(schedule.subscription()).isEqualTo(plan.subscriptionId());
        assertThat(schedule.updateStep()).isNotNull();
        assertThat(schedule.clearOperation()).isNull();
        var saved=proofs.quote(plan.account().id(),schedule.quote()).orElseThrow();
        assertThat(saved.reference()).isEqualTo(quote.providerQuoteReference());
        var client=sandboxClient();
        var nativeSchedule=client.v1().subscriptionSchedules().retrieve(schedule.reference());
        assertThat(nativeSchedule.getLivemode()).isFalse();
        assertThat(nativeSchedule.getCustomer()).isEqualTo(plan.remoteCustomer());
        assertThat(nativeSchedule.getSubscription()).isEqualTo(plan.remoteSubscription());
        assertThat(nativeSchedule.getStatus()).isEqualTo("active");
        assertThat(nativeSchedule.getEndBehavior()).isEqualTo("release");
        var boundary=original.paidThrough().getEpochSecond();
        assertThat(nativeSchedule.getCurrentPhase().getEndDate()).isEqualTo(boundary);
        assertThat(nativeSchedule.getPhases().size()).isEqualTo(2);
        var source=nativeSchedule.getPhases().getFirst();
        var target=nativeSchedule.getPhases().getLast();
        assertThat(source.getEndDate()).isEqualTo(boundary);
        assertThat(target.getStartDate()).isEqualTo(boundary);
        assertThat(target.getEndDate()).isEqualTo(schedule.targetEnd()).isGreaterThan(boundary);
        assertThat(target.getProrationBehavior()).isEqualTo("none");
        assertThat(source.getItems()).extracting(com.stripe.model.SubscriptionSchedule.Phase.Item::getPrice)
            .containsExactly(plan.remotePrice());
        assertThat(target.getItems()).extracting(com.stripe.model.SubscriptionSchedule.Phase.Item::getPrice)
            .containsExactly(plan.remoteTarget());
        reconcile(plan);
        var projected=local(plan);
        assertThat(projected.pendingChange().state()).isEqualTo(Subscription.ChangeState.SCHEDULED);
        assertThat(projected.pendingChange().target().priceId()).isEqualTo(plan.target());
        assertThat(projected.pendingChange().effectiveAt()).isEqualTo(quote.effectiveAt());
        assertOriginal(plan,original,cash,schedule.reference());

        var cleared=clears.clear(new ClearSubscriptionChangeUseCase.Command(context(plan,key()),plan.subscriptionId()));
        assertThat(cleared.status()).as("schedule release acknowledgement").isEqualTo(SubscriptionOperation.Status.PROCESSING);
        var released=client.v1().subscriptionSchedules().retrieve(schedule.reference());
        assertThat(released.getId()).isEqualTo(nativeSchedule.getId());
        assertThat(released.getCustomer()).isEqualTo(plan.remoteCustomer());
        assertThat(released.getStatus()).isEqualTo("released");
        assertThat(released.getSubscription()).isNull();
        assertThat(released.getReleasedSubscription()).isEqualTo(plan.remoteSubscription());
        assertThat(released.getReleasedAt()).isLessThan(boundary);
        reconcile(plan);
        assertThat(local(plan).pendingChange()).isNull();
        var finalProof=proofs.schedule(plan.account().id(),change.operationId()).orElseThrow();
        assertThat(finalProof.clearOperation()).isEqualTo(cleared.operationId());
        assertThat(finalProof.reference()).isEqualTo(schedule.reference());
        assertThat(jdbc.queryForList("""
            select kind from provider_operations where payment_account_id=? and provider_reference=?
              and status='ACKNOWLEDGED' and id in (?,?,?) order by kind
            """,String.class,plan.account().id().value(),schedule.reference(),schedule.creationStep(),
            schedule.updateStep(),finalProof.releaseStep())).containsExactly("SCHEDULE_CREATE","SCHEDULE_RELEASE","SCHEDULE_UPDATE");
        assertOriginal(plan,original,cash,null);
        assertThat(jdbc.queryForList("""
            select e.event_type from subscription_events e join outbox_events o on e.id=o.subscription_event_id
             and o.id=e.id and o.subscription_id=e.subscription_id and o.site_id=e.site_id
             where e.subscription_id=? and e.event_type like 'CHANGE_%' order by e.sequence_number
            """,String.class,plan.subscriptionId())).containsExactly("CHANGE_REQUESTED","CHANGE_SCHEDULED","CHANGE_CLEARED");
        deliverCallbacks(plan.site(),Set.of(plan.subscriptionId()));
    }

    private void assertOriginal(PaidPlan plan,Subscription original,List<Map<String,Object>> cash,String schedule)
            throws Exception {
        var remote=sandboxClient().v1().subscriptions().retrieve(plan.remoteSubscription());
        assertThat(remote.getStatus()).isEqualTo("active");
        assertThat(remote.getCustomer()).isEqualTo(plan.remoteCustomer());
        assertThat(remote.getSchedule()).isEqualTo(schedule);
        assertThat(remote.getItems().getData().size()).isOne();
        var item=remote.getItems().getData().getFirst();
        assertThat(item.getPrice().getId()).isEqualTo(plan.remotePrice());
        assertThat(item.getCurrentPeriodEnd()).isEqualTo(original.paidThrough().getEpochSecond());
        var current=local(plan);
        assertThat(current.providerReference()).isEqualTo(plan.remoteSubscription());
        assertThat(current.billingPrice()).isEqualTo(original.billingPrice());
        assertThat(current.entitledPrice()).isEqualTo(original.entitledPrice());
        assertThat(current.paidThrough()).isEqualTo(original.paidThrough());
        assertThat(new SubscriptionAccessPolicy().evaluate(current,Instant.now()).reason()).isEqualTo(AccessDecision.Reason.PAID);
        assertThat(paidEvidence(plan)).isEqualTo(cash);
        assertThat(jdbc.queryForObject("select count(*) from subscription_billing_records where subscription_id=?",
            Integer.class,plan.subscriptionId())).isOne();
    }

    private List<Map<String,Object>> paidEvidence(PaidPlan plan) {
        return jdbc.queryForList("""
            select p.id payment,b.id bill,p.provider_payment_reference intent,b.provider_invoice_reference invoice,
                   pe.id cash_event,se.id period_event
              from payments p join subscription_billing_records b on b.subscription_id=p.subscription_id
               and b.id=p.billing_record_id and b.site_id=p.site_id and b.payment_account_id=p.payment_account_id
              join payment_events pe on pe.payment_id=p.id and pe.event_type='PAYMENT_SUCCEEDED'
              join subscription_events se on se.subscription_id=p.subscription_id and se.billing_record_id=b.id and se.event_type='PERIOD_PAID'
              join outbox_events po on po.id=pe.id and po.payment_id=p.id and po.callback_type='payment.succeeded'
              join outbox_events so on so.id=se.id and so.subscription_id=p.subscription_id and so.callback_type='subscription.updated'
             where p.subscription_id=? and p.site_id=? and p.customer_id=? and p.payment_account_id=?
               and p.status='SUCCEEDED' and p.amount_minor=2000 and b.amount_due_minor=p.amount_minor
               and b.currency=p.currency and p.currency='EUR' and b.settlement='CONFIRMED_CASH'
            """,plan.subscriptionId(),plan.site().value(),plan.customer().id().value(),plan.account().id().value());
    }
    private static String key() { return UUID.randomUUID().toString(); }
}
