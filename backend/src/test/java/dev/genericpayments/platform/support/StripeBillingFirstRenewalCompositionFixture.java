package dev.genericpayments.platform.support;

import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingFirstRenewalAdapter;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingFirstRenewalAdvanceAdapter;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import dev.genericpayments.platform.provider.application.service.OwnedFirstRenewalAdmissionService;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionLifecycleLockPort;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import static dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.*;
import static org.assertj.core.api.Assertions.*;

/** Genuine hosted producers shared by observation and publication tests. No sampler opt-in here. */
public abstract class StripeBillingFirstRenewalCompositionFixture extends StripeBillingClockConfirmationCompositionFixture {
    @Autowired protected OwnedFirstRenewalStore renewals;
    @Autowired protected LockPaymentAccountPort accountLocks;
    @Autowired protected SubscriptionLifecycleLockPort lifecycleLocks;
    protected void genuineInitialCash() {
        beginConfirmation();issueAndSelect();confirm("succeeded");
        var captured=fence();apply(paidObservation(),captured);assertPaid();
    }
    protected Admission admitRenewal() {
        return new OwnedFirstRenewalAdmissionService(renewals,transaction,lifecycleOperations,lifecycleLocks,accountLocks,subscriptions,Clock.systemUTC())
            .admit(receipt,"a".repeat(40));
    }
    protected Admission paidAdmission() { genuineInitialCash();return admitRenewal(); }
    protected StripeBillingFirstRenewalAdapter observer() {
        return new StripeBillingFirstRenewalAdapter(credentials,clients,renewals,transaction,lifecycleOperations,lifecycleLocks,accountLocks,
            subscriptions,readPreparation,observations,bindings,Clock.systemUTC());
    }
    protected String clock(long frozen,String status) {
        var value=json(clockJson(owner));value.addProperty("frozen_time",frozen);value.addProperty("status",status);return value.toString();
    }
    protected LaterReady advance(Admission admission,Step step) {
        long before=step==Step.BOUNDARY?FROZEN:receipt.periodEnd(),target=step==Step.BOUNDARY?receipt.periodEnd():receipt.periodEnd()+3600;
        http.reply(clock(before,"ready"));http.reply(clock(before,"advancing"));http.reply(clock(target,"ready"));
        return new StripeBillingFirstRenewalAdvanceAdapter(credentials,clients,accounts,operations,renewals,Clock.systemUTC()).advance(admission,step);
    }
    protected void graph(LaterReady ready,boolean paid) {
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
        if(paid) {
            var allocation=graph.allocation.deepCopy();allocation.addProperty("id","inpay_renewal");allocation.addProperty("invoice","in_renewal");
            allocation.getAsJsonObject("payment").addProperty("payment_intent","pi_renewal");allocation.getAsJsonObject("status_transitions").addProperty("paid_at",b+11);
            var pi=graph.pi.deepCopy();pi.addProperty("id","pi_renewal");pi.addProperty("latest_charge","ch_renewal");
            var charge=graph.charge.deepCopy();charge.addProperty("id","ch_renewal");charge.addProperty("payment_intent","pi_renewal");
            http.reply(page(allocation));http.reply(pi.toString());http.reply(charge.toString());http.reply(page(allocation));
        } else http.reply(page());
        http.reply(clock(ready.observation().frozenTime(),"ready"));
    }
    protected Pending boundary(Admission admission,LaterReady ready) {
        graph(ready,false);return (Pending)observer().observe(admission,ready);
    }
    protected Paid paid(Admission admission) {
        var first=advance(admission,Step.BOUNDARY);var pending=boundary(admission,first);
        var second=advance(admission,Step.FINALIZATION);graph(second,true);
        var paid=(Paid)observer().observe(admission,second);
        assertThat(paid.boundary()).isEqualTo(pending.boundary());assertThat(paid.claim().ready()).isEqualTo(second);
        assertThat(underLifecycle(admission,()->renewals.readClaim(admission,second).orElseThrow())).isEqualTo(paid.claim());
        assertThat(underLifecycle(admission,()->renewals.boundaryInTransaction(admission))).isEqualTo(pending.boundary());
        assertThat(paid.fence()).isEqualTo(paid.claim().fence());assertThat(paid.graphAcceptedAt()).isBefore(paid.claim().deadlineAt());
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(2);
        assertThat(count("stripe_billing_first_renewal_publications")).isZero();assertThat(http.replies).isEmpty();return paid;
    }
    protected <T>T underLifecycle(Admission admission,Supplier<T> work) {
        return transaction.required(()->{
            var ids=new TreeSet<UUID>();ids.add(admission.receipt().intent().createOperation());
            lifecycleOperations.unresolved(site,current().id()).forEach(operation->ids.add(operation.id()));for(var id:ids)lifecycleOperations.lock(id);
            lifecycleLocks.lockActive(site,customer);accountLocks.findByIdForUpdate(billing.id()).orElseThrow();subscriptions.lock(site,current().id());return work.get();
        });
    }
}
