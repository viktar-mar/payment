package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.stripe.net.RequestOptions;
import com.stripe.param.testhelpers.TestClockAdvanceParams;
import dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.domain.Customer;
import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.NativePaidPlanFixture;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test Clock is attached before the customer exists. Operational UTC remains independent throughout. */
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="PAYMENT_STRIPE_BILLING_CASE",matches="plan_schedule_boundary")
class StripePlanScheduleBoundaryNativeIT extends NativePaidPlanFixture {
    private ClockOwner owner;
    private ReadyClock ready;
    private StripeBillingClockSubscriptionAdapter createAdapter;
    private OwnedClockSubscriptionReceipt receipt;

    @Override protected SubscriptionOperationResult createFreshSubscription(PaymentAccount account, SiteId site,
            Customer customer, CreateSubscriptionUseCase.Command command) {
        var utc=Clock.systemUTC();
        // The platform can publish target access against ordinary UTC once this real boundary arrives.
        // The returned Subscription period supplies the actual deadline used by the proof.
        var due=utc.instant().plusSeconds(120).atZone(ZoneOffset.UTC);
        var start=due.minusMonths(1);
        var clocks=planPort(StripeBillingClockStore.class);
        var steps=planPort(ProviderOperationStore.class);
        var credentials=planPort(StripeCredentialResolver.class);
        var clients=planPort(StripeClientFactory.class);
        var verify=planPort(PaymentAccountVerificationPort.class);
        owner=clocks.admitFresh(account,site,customer.id(),runId(),StripeBillingTestClockAdapter.API_VERSION,
            start.toEpochSecond(),utc.instant());
        ready=new StripeBillingTestClockAdapter(credentials,clients,verify,steps,clocks,planPort(DigestPort.class))
            .createFresh(account,owner);
        planPort(StripeBillingCustomerAdapter.class).ensureOwnedCustomer(account,owner);
        var commands=planPort(SubscriptionCommandTransaction.class);
        var reservation=commands.reserveCreate(command);
        assertThat(reservation.owner()).isTrue();
        var store=planPort(StripeBillingClockSubscriptionStore.class);
        createAdapter=new StripeBillingClockSubscriptionAdapter(credentials,clients,verify,steps,clocks,store,
            planPort(StripeBillingBindingPort.class),planPort(SubscriptionStore.class),planPort(SubscriptionOperationStore.class),utc);
        var reply=createAdapter.createOwned(new Command(account,reservation.operation(),reservation.subscription().billingPrice(),
            null,null,null,null,true,false),owner);
        assertThat(reply.outcome()).isEqualTo(Outcome.ACCEPTED);
        var result=commands.recordReply(reservation.operation().id(),reply);
        receipt=store.requireReceipt(account.id(),result.subscriptionId());
        return result;
    }

    @Override protected void publishInitial(PaidPlan plan) {
        var source=operation(plan,plan.createOperationId());
        var before=local(plan);
        long seq=planPort(SubscriptionObservationPort.class).captureFence(before);
        var snapshot=createAdapter.retrieveOwned(new Lookup(plan.account(),plan.subscriptionId(),
            plan.remoteSubscription(),source.providerOperationKey()),owner);
        assertThat(snapshot.invoices()).extracting(Invoice::settlement).containsExactly(SettlementKind.CASH);
        apply(plan,before,source,seq,snapshot);
    }

    @Test void targetCashOnOriginalClockedSubscriptionAppliesAtTheFirstRenewal() throws Exception {
        var plan=provisionPaidPlan(1500);
        var original=local(plan);
        var paidSource=sandboxClient().v1().subscriptions().retrieve(plan.remoteSubscription());
        assertThat(paidSource.getTestClock()).isEqualTo(ready.reference());
        assertThat(paidSource.getCustomer()).isEqualTo(plan.remoteCustomer());
        var defaultMethod=paidSource.getDefaultPaymentMethod();
        assertThat(defaultMethod).matches("pm_[A-Za-z0-9]+");
        var originalCard=sandboxClient().v1().paymentMethods().retrieve(defaultMethod);
        assertThat(originalCard.getCustomer()).isEqualTo(plan.remoteCustomer());
        assertThat(originalCard.getLivemode()).isFalse();
        assertThat(originalCard.getType()).isEqualTo("card");
        var db=planPort(JdbcTemplate.class);
        var baselineCash=db.queryForMap("""
            select p.id,p.provider_payment_reference,p.subscription_invoice_reference,b.id billing_id,pe.id cash_event,se.id period_event
              from payments p join subscription_billing_records b on b.id=p.billing_record_id and b.subscription_id=p.subscription_id
              join payment_events pe on pe.payment_id=p.id and pe.event_type='PAYMENT_SUCCEEDED'
              join subscription_events se on se.billing_record_id=b.id and se.event_type='PERIOD_PAID'
             where p.subscription_id=? and p.status='SUCCEEDED' and p.amount_minor=2000 and b.kind='INITIAL'
            """,plan.subscriptionId());
        var scope=new StripeBillingScheduleClock(receipt,planPort(StripeBillingClockSubscriptionStore.class));
        var ownership=new StripeBillingOwnership(planPort(StripeCredentialResolver.class),planPort(ProviderOperationStore.class),
            planPort(StripeBillingBindingPort.class),planPort(DigestPort.class),planPort(LoadPaymentAccountPort.class),
            planPort(LoadSitePort.class),planPort(CustomerStore.class),planPort(SubscriptionStore.class),
            planPort(SubscriptionOperationStore.class),planPort(SubscriptionCatalogPort.class),planPort(ProviderCatalogMappingPort.class));
        var proofs=planPort(SubscriptionPlanProofStore.class);
        var mutation=new StripeBillingMutationAdapter(ownership,planPort(StripeBillingBindingPort.class),proofs,
            planPort(StripeCredentialResolver.class),planPort(StripeClientFactory.class),planPort(PaymentAccountVerificationPort.class),
            Clock.systemUTC(),scope);
        var provider=new ScheduledPlansOnly(mutation,planPort(ProviderOperationStore.class),plan.account());
        var service=new SubscriptionPlanService(planPort(SubscriptionPlanTransaction.class),account -> {
            assertThat(account).isEqualTo(plan.account());return provider;
        });
        var quote=service.preview(new PreviewSubscriptionChangeUseCase.Query(context(plan,null),plan.subscriptionId(),
            plan.target(),ChangeTiming.NEXT_PERIOD));
        assertThat(quote.effectiveAt()).isEqualTo(original.paidThrough());
        assertThat(quote.dueNowMinor()).isZero();
        assertThat(quote.nextAmountMinor()).isEqualTo(1500);
        var change=service.change(new ChangeSubscriptionPlanUseCase.Command(context(plan,UUID.randomUUID().toString()),
            plan.subscriptionId(),quote.id()));
        assertThat(change.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        var schedule=proofs.schedule(plan.account().id(),change.operationId()).orElseThrow();
        var q=proofs.quote(plan.account().id(),schedule.quote()).orElseThrow();
        var remoteSchedule=sandboxClient().v1().subscriptionSchedules().retrieve(schedule.reference());
        StripeBillingMutationAdapter.requireSchedule(remoteSchedule,q,schedule,true);
        assertThat(remoteSchedule.getTestClock()).isEqualTo(ready.reference());
        var changeOp=operation(plan,change.operationId());
        var binding=planPort(StripeBillingBindingPort.class).findCustomer(plan.account().id(),plan.site(),plan.customer().id()).orElseThrow();
        var prepared=local(plan);
        long seq=planPort(SubscriptionObservationPort.class).captureFence(prepared);
        var originalSource=read(plan,prepared,binding,ownership,scope,proofs);
        assertThat(originalSource.invoices()).extracting(Invoice::settlement).containsExactly(SettlementKind.CASH);
        var request=new Lookup(plan.account(),plan.subscriptionId(),plan.remoteSubscription(),changeOp.providerOperationKey());
        var accepted=planPort(StripeSubscriptionProviderAdapter.class).operationResult(request,prepared,binding,originalSource);
        assertThat(accepted.operationObservation()).isEqualTo(new OperationObservation(changeOp.providerOperationKey(),OperationState.APPLIED));
        apply(plan,prepared,changeOp,seq,accepted);
        var scheduled=local(plan);
        assertThat(scheduled.pendingChange().state()).isEqualTo(Subscription.ChangeState.SCHEDULED);
        long boundary=receipt.periodEnd();
        advance(ready.frozenTime(),boundary-1);
        var sourceSub=sandboxClient().v1().subscriptions().retrieve(plan.remoteSubscription());
        scope.requireRemote(sourceSub,ready.reference());
        assertThat(sourceSub.getDefaultPaymentMethod()).isEqualTo(defaultMethod);
        assertThat(sourceSub.getSchedule()).isEqualTo(schedule.reference());
        assertThat(sourceSub.getItems().getData().getFirst().getPrice().getId()).isEqualTo(plan.remotePrice());
        assertThat(new SubscriptionAccessPolicy().evaluate(scheduled,Instant.ofEpochSecond(boundary-1)).entitledPriceId()).isEqualTo(plan.price());
        advance(boundary-1,boundary);
        var at=sandboxClient().v1().subscriptions().retrieve(plan.remoteSubscription());
        scope.requireRemote(at,ready.reference());
        assertThat(at.getDefaultPaymentMethod()).isEqualTo(defaultMethod);
        assertThat(at.getSchedule()).isEqualTo(schedule.reference());
        assertThat(at.getItems().getData().getFirst().getPrice().getId()).isEqualTo(plan.remoteTarget());
        assertThat(at.getItems().getData().getFirst().getCurrentPeriodStart()).isEqualTo(boundary);
        var active=sandboxClient().v1().subscriptionSchedules().retrieve(schedule.reference());
        assertThat(active.getCustomer()).isEqualTo(plan.remoteCustomer());
        assertThat(active.getSubscription()).isEqualTo(plan.remoteSubscription());
        assertThat(active.getTestClock()).isEqualTo(ready.reference());
        assertThat(active.getCurrentPhase().getStartDate()).isEqualTo(boundary);
        assertThat(active.getCurrentPhase().getEndDate()).isEqualTo(schedule.targetEnd());
        var due=sandboxClient().v1().invoices().retrieve(at.getLatestInvoice());
        assertThat(due.getBillingReason()).isEqualTo("subscription_cycle");
        assertThat(due.getTestClock()).isEqualTo(ready.reference());
        assertThat(due.getAmountDue()).isEqualTo(1500);
        assertThat(due.getParent().getSubscriptionDetails().getSubscription()).isEqualTo(plan.remoteSubscription());
        assertThat(due.getStatus()).isIn("draft","open");
        assertThat(due.getAmountPaid()).isZero();
        assertThat(due.getAmountRemaining()).isEqualTo(1500);
        var pending=local(plan);
        var pendingSequence=planPort(SubscriptionObservationPort.class).captureFence(pending);
        Snapshot unpaid=null;
        try {
            unpaid=read(plan,pending,binding,ownership,scope,proofs);
        } catch(dev.genericpayments.platform.shared.application.PaymentApplicationException refused) {
            // An unfinalized invoice can have incomplete monetary fields. Such a graph cannot authorize cash.
            assertThat(due.getStatus()).isEqualTo("draft");
            assertThat(refused.code()).isEqualTo("PROVIDER_EVIDENCE_MISMATCH");
        }
        if(unpaid!=null){
            var observed=unpaid.invoices().stream().filter(i->i.reference().equals(due.getId())).findFirst().orElseThrow();
            assertThat(observed.settlement()).isIn(SettlementKind.OPEN,SettlementKind.UNSUPPORTED);
            assertThat(observed.cashObjects()).isEmpty();
            apply(plan,pending,null,pendingSequence,unpaid);
        }
        var unentitled=local(plan);
        assertThat(unentitled.entitledPrice().priceId()).isEqualTo(plan.price());
        assertThat(unentitled.pendingChange().target().priceId()).isEqualTo(plan.target());
        assertThat(unentitled.paidThrough()).isEqualTo(original.paidThrough());
        assertThat(new SubscriptionAccessPolicy().evaluate(unentitled,Instant.ofEpochSecond(boundary)).allowed()).isFalse();
        assertThat(db.queryForObject("select count(*) from subscription_events where subscription_id=? and event_type='CHANGE_APPLIED'",
            Integer.class,plan.subscriptionId())).isZero();
        assertThat(db.queryForObject("select count(*) from payments where subscription_id=?",Integer.class,plan.subscriptionId())).isOne();
        advance(boundary,boundary+7200);
        var paid=sandboxClient().v1().invoices().retrieve(due.getId());
        assertThat(paid.getStatus()).isEqualTo("paid");
        assertThat(paid.getAmountPaid()).isEqualTo(1500);
        assertThat(paid.getAmountRemaining()).isZero();
        var allocationPage=sandboxClient().v1().invoicePayments().list(com.stripe.param.InvoicePaymentListParams.builder()
            .setInvoice(paid.getId()).setLimit(2L).build());
        assertThat(allocationPage.getHasMore()).isFalse();
        assertThat(allocationPage.getData().size()).isOne();
        var allocation=allocationPage.getData().getFirst();
        assertThat(allocation.getStatus()).isEqualTo("paid");
        assertThat(allocation.getInvoice()).isEqualTo(paid.getId());
        assertThat(allocation.getPayment().getType()).isEqualTo("payment_intent");
        assertThat(allocation.getAmountPaid()).isEqualTo(1500);
        var intent=sandboxClient().v1().paymentIntents().retrieve(allocation.getPayment().getPaymentIntent());
        assertThat(intent.getCustomer()).isEqualTo(plan.remoteCustomer());
        assertThat(intent.getStatus()).isEqualTo("succeeded");
        assertThat(intent.getId()).isNotEqualTo(baselineCash.get("provider_payment_reference"));
        var charge=sandboxClient().v1().charges().retrieve(intent.getLatestCharge());
        assertThat(charge.getLivemode()).isFalse();
        assertThat(charge.getCustomer()).isEqualTo(plan.remoteCustomer());
        assertThat(charge.getPaymentIntent()).isEqualTo(intent.getId());
        assertThat(charge.getPaymentMethod()).isEqualTo(defaultMethod);
        assertThat(charge.getCaptured()).isTrue();
        assertThat(charge.getCurrency()).isEqualTo("eur");
        assertThat(charge.getAmountCaptured()).isEqualTo(1500);
        long remaining=boundary-Instant.now().getEpochSecond();
        assertThat(remaining).isLessThanOrEqualTo(120);
        while(Instant.now().getEpochSecond()<boundary) Thread.sleep(250);

        var before=local(plan);
        seq=planPort(SubscriptionObservationPort.class).captureFence(before);
        var target=read(plan,before,binding,ownership,scope,proofs);
        assertThat(target.invoices().size()).isEqualTo(2);
        var bill=target.invoices().stream().filter(i->i.reference().equals(paid.getId())).findFirst().orElseThrow();
        assertThat(bill.period().start()).isEqualTo(Instant.ofEpochSecond(boundary));
        assertThat(bill.price().priceId()).isEqualTo(plan.target());
        assertThat(bill.settlement()).isEqualTo(SettlementKind.CASH);
        assertThat(bill.cashObjects().size()).isOne();
        apply(plan,before,null,seq,target);
        var result=local(plan);
        assertThat(result.pendingChange()).isNull();
        assertThat(result.billingPrice().priceId()).isEqualTo(plan.target());
        assertThat(result.entitledPrice().priceId()).isEqualTo(plan.target());
        assertThat(result.currentPeriod()).isEqualTo(bill.period());
        assertThat(result.paidThrough()).isEqualTo(bill.period().end());
        assertThat(new SubscriptionAccessPolicy().evaluate(result,Instant.now()).reason()).isEqualTo(AccessDecision.Reason.PAID);
        assertThat(bill.cashObjects().getFirst().reference()).isEqualTo(intent.getId());
        assertThat(db.queryForObject("""
            select count(*) from payments p join subscription_billing_records b on b.id=p.billing_record_id
              join payment_events pe on pe.payment_id=p.id join subscription_events se on se.billing_record_id=b.id
             where p.id=? and p.subscription_id=? and b.id=? and pe.id=? and se.id=?
               and p.subscription_invoice_reference=? and p.provider_payment_reference=? and p.amount_minor=2000
            """,Long.class,baselineCash.get("id"),plan.subscriptionId(),baselineCash.get("billing_id"),baselineCash.get("cash_event"),
            baselineCash.get("period_event"),baselineCash.get("subscription_invoice_reference"),baselineCash.get("provider_payment_reference"))).isOne();
        assertThat(db.queryForList("select amount_minor from payments where subscription_id=? order by amount_minor",Long.class,
            plan.subscriptionId())).containsExactly(1500L,2000L);
        assertThat(db.queryForList("""
            select se.event_type from subscription_billing_records b join subscription_events se on se.billing_record_id=b.id
              join outbox_events o on o.id=se.id and o.subscription_id=b.subscription_id
              join payments p on p.billing_record_id=b.id and p.status='SUCCEEDED' and p.amount_minor=b.amount_due_minor
              join payment_events pe on pe.payment_id=p.id and pe.event_type='PAYMENT_SUCCEEDED'
              join outbox_events po on po.id=pe.id and po.callback_type='payment.succeeded'
             where b.subscription_id=? and b.billed_price_id=? and b.provider_invoice_reference=?
               and b.settlement='CONFIRMED_CASH' and p.provider_payment_reference=? order by se.sequence_number
            """,String.class,plan.subscriptionId(),plan.target().value(),paid.getId(),bill.cashObjects().getFirst().reference()))
            .containsExactly("CHANGE_APPLIED","PERIOD_PAID");
        deliverCallbacks(plan.site(),Set.of(plan.subscriptionId()));
    }

    private Snapshot read(PaidPlan plan,Subscription local,StripeBillingBindingPort.CustomerBinding binding,
            StripeBillingOwnership ownership,StripeBillingScheduleClock scope,SubscriptionPlanProofStore proofs) {
        var graph=new StripeBillingSnapshotMapper.Ownership(plan.account(),local,binding,ownership.prices(plan.account(),local),
            null,null,List.of(),ownership.scheduleHistory(plan.account(),local,proofs));
        return StripeBillingSnapshotMapper.retrieveScheduledClock(sandboxClient(),graph,scope,Instant.now());
    }
    private SubscriptionOperation operation(PaidPlan plan,UUID id) {
        return planPort(SubscriptionOperationStore.class).find(plan.site(),plan.subscriptionId(),id).orElseThrow();
    }
    private void apply(PaidPlan plan,Subscription before,SubscriptionOperation op,long seq,Snapshot snapshot) {
        var fence=new ApplySubscriptionSnapshotUseCase.Fence(before.revision(),op==null?null:op.id(),op==null?null:op.version(),
            null,context(plan,null),seq);
        var command=new ApplySubscriptionSnapshotUseCase.Command(plan.account(),plan.subscriptionId(),snapshot,
            SubscriptionEvent.Source.RECONCILIATION,"schedule:"+UUID.randomUUID(),"native-schedule",fence);
        planPort(SubscriptionTransactionPort.class).required(()->{
            planPort(ApplySubscriptionSnapshotUseCase.class).apply(command);
            return null;
        });
    }

    @org.junit.jupiter.api.AfterAll void removeFreshClock() throws Exception {
        if(ready==null)return;
        var clocks=sandboxClient().v1().testHelpers().testClocks();
        var own=clocks.retrieve(ready.reference());
        assertThat(own.getId()).isEqualTo(ready.reference());
        assertThat(own.getLivemode()).isFalse();
        assertThat(own.getName()).isEqualTo(owner.name());
        assertThat(own.getCreated()).isEqualTo(ready.providerCreated());
        var deleted=clocks.delete(own.getId());
        assertThat(deleted.getId()).isEqualTo(ready.reference());
        assertThat(deleted.getDeleted()).isTrue();
    }

    private void advance(long previous,long next) throws Exception {
        var clocks=sandboxClient().v1().testHelpers().testClocks();
        var current=clocks.retrieve(ready.reference());
        assertThat(current.getLivemode()).isFalse();
        assertThat(current.getName()).isEqualTo(owner.name());
        assertThat(current.getCreated()).isEqualTo(ready.providerCreated());
        assertThat(current.getFrozenTime()).isEqualTo(previous);
        assertThat(current.getStatus()).isEqualTo("ready");
        var advancing=clocks.advance(ready.reference(),TestClockAdvanceParams.builder().setFrozenTime(next).build(),
            RequestOptions.builder().setIdempotencyKey("schedule-"+runId()+"-"+next).build());
        assertThat(advancing.getId()).isEqualTo(ready.reference());
        long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
        do {
            var observed=clocks.retrieve(ready.reference());
            assertThat(observed.getId()).isEqualTo(ready.reference());
            assertThat(observed.getName()).isEqualTo(owner.name());
            if ("ready".equals(observed.getStatus())) {
                assertThat(observed.getFrozenTime()).isEqualTo(next);
                return;
            }
            assertThat(observed.getStatus()).isEqualTo("advancing");
            Thread.sleep(500);
        } while(System.nanoTime()<deadline);
        throw new AssertionError("OWNED_SCHEDULE_CLOCK_ADVANCE_TIMEOUT");
    }
}
