package dev.genericpayments.platform.provider.adapter.out.stripe;

import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Standalone official SDK/inert transport; no parent, extension, context, server or DB. */
class StripeBillingClockSnapshotTest {
    // Removing an ordinary resource's clock fence would admit a clock graph through a direct mapper call.
    @ParameterizedTest @ValueSource(strings={"customer","subscription","invoice"})
    void ordinaryDirectMapperRejectsEachUnexpectedClock(String resource){
        var f=new StripeBillingOwnedSubscriptionFixture();
        for(var r:java.util.List.of(f.customer,f.remote,f.invoice))r.remove("test_clock");
        switch(resource){case "customer"->f.customer.addProperty("test_clock","clock_foreign");case "subscription"->f.remote.addProperty("test_clock","clock_foreign");case "invoice"->f.invoice.addProperty("test_clock","clock_foreign");}
        f.graph();assertThatThrownBy(()->StripeBillingSnapshotMapper.retrieve(f.f.clients.create(null),f.ordinary(),f.utc)).isInstanceOf(PaymentApplicationException.class);
    }
    @Test void originalOpaqueCreatedOutsideFrozenAndUtcIsAcceptedWithExactTypedReceipt(){
        var f=new StripeBillingOwnedSubscriptionFixture();f.graph();
        assertThatCode(()->{var s=owned(f);assertThat(s.observedAt()).isEqualTo(f.utc);assertThat(s.invoices()).singleElement().satisfies(i->assertThat(i.settlement()).isEqualTo(dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind.OPEN));}).doesNotThrowAnyException();
    }
    @ParameterizedTest @ValueSource(strings={"customerMissing","customerForeign","subscriptionMissing","subscriptionForeign","invoiceMissing","invoiceForeign","createdMissing","createdChanged","item","price","periodStart","periodEnd","laterPeriod","trial","schedule","pending","cancel","pause","renewal","linePeriod","lineItem","lineProduct","proration","invoiceCustomer","invoiceParent","live","deletedCustomer","unknownStatus"})
    void rejectsBrokenOriginalClockAndInitialGraph(String mutation){
        var f=new StripeBillingOwnedSubscriptionFixture();
        switch(mutation){
            case "customerMissing"->f.customer.remove("test_clock");case "customerForeign"->f.customer.addProperty("test_clock","clock_foreign");
            case "subscriptionMissing"->f.remote.remove("test_clock");case "subscriptionForeign"->f.remote.addProperty("test_clock","clock_foreign");
            case "invoiceMissing"->f.invoice.remove("test_clock");case "invoiceForeign"->f.invoice.addProperty("test_clock","clock_foreign");
            case "createdMissing"->f.remote.remove("created");case "createdChanged"->f.remote.addProperty("created",43);
            case "item"->f.item().addProperty("id","si_other");case "price"->f.price().addProperty("id","price_other");
            case "periodStart"->f.item().addProperty("current_period_start",1699999901);case "periodEnd"->f.item().addProperty("current_period_end",1702678401);
            case "laterPeriod"->{f.item().addProperty("current_period_start",1702678400);f.item().addProperty("current_period_end",1705356800);}
            case "trial"->f.remote.addProperty("trial_end",1702678400);case "schedule"->f.remote.addProperty("schedule","sub_sched_other");
            case "pending"->f.remote.add("pending_update",StripeBillingOwnedSubscriptionFixture.json("{}"));case "cancel"->f.remote.addProperty("cancel_at_period_end",true);
            case "pause"->f.remote.add("pause_collection",StripeBillingOwnedSubscriptionFixture.json("{}"));case "renewal"->f.invoice.addProperty("billing_reason","subscription_cycle");
            case "linePeriod"->f.line.getAsJsonObject("period").addProperty("start",1699999901);
            case "lineItem"->f.line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details").addProperty("subscription_item","si_other");
            case "lineProduct"->f.line.getAsJsonObject("pricing").getAsJsonObject("price_details").addProperty("product","prod_other");
            case "proration"->f.line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details").addProperty("proration",true);
            case "invoiceCustomer"->f.invoice.addProperty("customer","cus_other");case "invoiceParent"->f.invoice.getAsJsonObject("parent").getAsJsonObject("subscription_details").addProperty("subscription","sub_other");
            case "live"->f.remote.addProperty("livemode",true);case "deletedCustomer"->f.customer.addProperty("deleted",true);case "unknownStatus"->f.remote.addProperty("status","future_status");
        }
        f.graph();assertThatThrownBy(()->owned(f)).isInstanceOf(PaymentApplicationException.class);
    }
    @Test void ordinaryNullClockSnapshotKeepsUtcAndDoesNotInventCreationWindow(){
        var f=new StripeBillingOwnedSubscriptionFixture();for(var r:java.util.List.of(f.customer,f.remote,f.invoice))r.remove("test_clock");
        f.remote.addProperty("created",42);f.graph();var s=StripeBillingSnapshotMapper.retrieve(f.f.clients.create(null),f.ordinary(),f.utc);
        assertThat(s.observedAt()).isEqualTo(f.utc);assertThat(s.providerRevision()).isZero();
    }
    @Test void ownedCashStillRequiresFullReverseAllocationAndCapturedGross(){
        var f=new StripeBillingOwnedSubscriptionFixture();f.paidGraph();var s=owned(f);
        assertThat(s.invoices().getFirst().settlement()).isEqualTo(dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind.CASH);
        assertThat(s.invoices().getFirst().cashObjects().getFirst().occurredAt()).isEqualTo(java.time.Instant.ofEpochSecond(1700000011));
        assertThat(s.externalWithdrawalEvidence()).isNull();assertThat(s.recoveryObservations()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"uncaptured","chargeAmount","chargeCustomer","chargeIntent","intentState","intentCustomer","allocationAmount","allocationType","offStripe","reverseForeign","reverseDuplicate"})
    void ownedPaidStatusCannotReplaceFullCashGraph(String broken){
        var f=new StripeBillingOwnedSubscriptionFixture();
        switch(broken){case "uncaptured"->f.charge.addProperty("captured",false);case "chargeAmount"->f.charge.addProperty("amount_captured",999);
            case "chargeCustomer"->f.charge.addProperty("customer","cus_other");case "chargeIntent"->f.charge.addProperty("payment_intent","pi_other");
            case "intentState"->f.pi.addProperty("status","requires_capture");case "intentCustomer"->f.pi.addProperty("customer","cus_other");
            case "allocationAmount"->f.allocation.addProperty("amount_paid",999);case "allocationType"->f.allocation.getAsJsonObject("payment").addProperty("type","future_payment");
            case "offStripe"->f.invoice.addProperty("amount_paid_off_stripe",1000);}
        f.paidGraph();
        if(broken.startsWith("reverse")){
            f.f.http.replies.removeLast();var other=f.allocation.deepCopy();other.addProperty("id","inpay_other");other.addProperty("invoice","in_other");
            f.f.http.reply(broken.equals("reverseForeign")?StripeBillingOwnedSubscriptionFixture.page(other):StripeBillingOwnedSubscriptionFixture.page(f.allocation,other));
        }
        assertThatThrownBy(()->owned(f)).isInstanceOf(PaymentApplicationException.class);
    }
    @Test void foreignTypedBindingRejectsBeforeProviderRead(){
        var f=new StripeBillingOwnedSubscriptionFixture();var ordinary=f.ordinary();var b=f.binding();
        var foreign=new StripeBillingSnapshotMapper.Ownership(ordinary.account(),ordinary.subscription(),new dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding(java.util.UUID.randomUUID(),b.accountId(),b.siteId(),b.customerId(),b.providerReference(),b.creationOperationId(),b.version()),ordinary.prices());
        assertThatThrownBy(()->StripeBillingSnapshotMapper.retrieveOwned(f.f.clients.create(null),foreign,new StripeBillingSnapshotMapper.OwnedInitial(f.receipt()),f.utc)).isInstanceOf(PaymentApplicationException.class);
        assertThat(f.f.http.calls).isEmpty();
    }
    static dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Snapshot owned(StripeBillingOwnedSubscriptionFixture f){return StripeBillingSnapshotMapper.retrieveOwned(f.f.clients.create(null),f.ordinary(),new StripeBillingSnapshotMapper.OwnedInitial(f.receipt()),f.utc);}
}
