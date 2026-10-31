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
    @ParameterizedTest @ValueSource(strings={"omitted","null","zero","positive","negative"})
    void indicatorPolicyIsLimitedToOwnedPositiveInitialOpenAndCash(String indicator){
        for(boolean paid:java.util.List.of(false,true))for(boolean owned:java.util.List.of(false,true)){
            var f=new StripeBillingOwnedSubscriptionFixture();indicator(f,indicator);
            if(!owned)for(var resource:java.util.List.of(f.customer,f.remote,f.invoice))resource.remove("test_clock");
            if(paid)f.paidGraph();else f.graph();
            if(owned&&(indicator.equals("omitted")||indicator.equals("null")||indicator.equals("zero"))){
                var invoice=owned(f).invoices().getFirst();
                assertThat(invoice.settlement()).isEqualTo(paid?dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind.CASH:dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind.OPEN);
                assertThat(invoice.amountDueMinor()).isEqualTo(1000);assertThat(invoice.currency()).isEqualTo("EUR");
            }else if(!owned&&indicator.equals("zero")){
                assertThat(StripeBillingSnapshotMapper.retrieve(f.f.clients.create(null),f.ordinary(),f.utc).invoices().getFirst().settlement()).isEqualTo(paid?dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind.CASH:dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind.OPEN);
            }else if(owned)assertThatThrownBy(()->owned(f)).isInstanceOf(PaymentApplicationException.class);
            else assertThat(StripeBillingSnapshotMapper.retrieve(f.f.clients.create(null),f.ordinary(),f.utc).invoices().getFirst().settlement()).isEqualTo(dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind.UNSUPPORTED);
            assertThat(f.f.http.calls).hasSize(paid&&(owned||indicator.equals("zero"))&&!java.util.List.of("positive","negative").contains(indicator)?8:5).allMatch(c->c.method().name().equals("GET"));
        }
    }
    static void indicator(StripeBillingOwnedSubscriptionFixture f,String state){
        switch(state){case "omitted"->f.invoice.remove("amount_paid_off_stripe");case "null"->f.invoice.add("amount_paid_off_stripe",com.google.gson.JsonNull.INSTANCE);case "zero"->f.invoice.addProperty("amount_paid_off_stripe",0);case "positive"->f.invoice.addProperty("amount_paid_off_stripe",1);case "negative"->f.invoice.addProperty("amount_paid_off_stripe",-1);default->throw new AssertionError(state);}
    }
    // Removing an ordinary resource's clock fence would admit a clock graph through a direct mapper call.
    @ParameterizedTest @ValueSource(strings={"customer","subscription","invoice"})
    void ordinaryDirectMapperRejectsEachUnexpectedClock(String resource){
        var f=new StripeBillingOwnedSubscriptionFixture();
        for(var r:java.util.List.of(f.customer,f.remote,f.invoice))r.remove("test_clock");
        switch(resource){case "customer"->f.customer.addProperty("test_clock","clock_foreign");case "subscription"->f.remote.addProperty("test_clock","clock_foreign");case "invoice"->f.invoice.addProperty("test_clock","clock_foreign");}
        f.graph();assertThatThrownBy(()->StripeBillingSnapshotMapper.retrieve(f.f.clients.create(null),f.ordinary(),f.utc)).isInstanceOf(PaymentApplicationException.class);
    }
    @ParameterizedTest @ValueSource(strings={"exact","omitted","null"})
    void originalOpaqueCreatedOutsideFrozenAndUtcIsAcceptedWithExactTypedReceipt(String representation){
        var f=new StripeBillingOwnedSubscriptionFixture();f.flatSubscription(representation);f.graph();
        assertThatCode(()->{var s=owned(f);assertThat(s.observedAt()).isEqualTo(f.utc);assertThat(s.invoices()).singleElement().satisfies(i->assertThat(i.settlement()).isEqualTo(dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind.OPEN));}).doesNotThrowAnyException();
    }
    @ParameterizedTest @ValueSource(strings={"customerMissing","customerForeign","subscriptionMissing","subscriptionForeign","invoiceMissing","invoiceForeign","createdMissing","createdChanged","item","price","periodStart","periodEnd","laterPeriod","trial","schedule","pending","cancel","pause","renewal","linePeriod","lineItem","lineProduct","proration","invoiceCustomer","invoiceParent","live","deletedCustomer","unknownStatus"})
    void rejectsBrokenOriginalClockAndInitialGraph(String mutation){
        for(var indicator:java.util.List.of("omitted","null","zero"))for(var representation:java.util.List.of("exact","omitted","null")){
            var f=new StripeBillingOwnedSubscriptionFixture();f.flatSubscription(representation);indicator(f,indicator);
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
    }
    @Test void ordinaryNullClockSnapshotKeepsUtcAndDoesNotInventCreationWindow(){
        var f=new StripeBillingOwnedSubscriptionFixture();for(var r:java.util.List.of(f.customer,f.remote,f.invoice))r.remove("test_clock");
        f.remote.addProperty("created",42);f.graph();var s=StripeBillingSnapshotMapper.retrieve(f.f.clients.create(null),f.ordinary(),f.utc);
        assertThat(s.observedAt()).isEqualTo(f.utc);assertThat(s.providerRevision()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"exact","omitted","null"})
    void ownedCashStillRequiresFullReverseAllocationAndCapturedGross(String representation){
        var f=new StripeBillingOwnedSubscriptionFixture();f.flatSubscription(representation);f.paidGraph();var s=owned(f);
        assertThat(s.invoices().getFirst().settlement()).isEqualTo(dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind.CASH);
        assertThat(s.invoices().getFirst().cashObjects().getFirst().occurredAt()).isEqualTo(java.time.Instant.ofEpochSecond(1700000011));
        assertThat(s.invoices().getFirst().cashObjects().getFirst().reference()).isEqualTo("pi_owned");
        assertThat(s.invoices().getFirst().cashObjects().getFirst().amountMinor()).isEqualTo(1000);
        assertThat(f.f.http.calls).hasSize(8).allMatch(c->c.method().name().equals("GET"));
        assertThat(s.externalWithdrawalEvidence()).isNull();assertThat(s.recoveryObservations()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"missingCharge","missingAllocation","splitAllocation","foreignAllocation","allocationCurrency","allocationLive","missingPaidAt","intentLive","intentCards","invoiceAmount","invoicePaid","invoiceRemaining","invoiceCurrency","invoiceCustomer","uncaptured","chargeAmount","chargeCustomer","chargeIntent","intentState","intentCustomer","allocationAmount","allocationType","offStripe","reverseMissing","reverseForeign","reverseDuplicate","nullCaptured","nullChargeAmount","nullIntentReceived","nullPaidAt","chargeCurrency","chargeLive","chargeObject","chargeId","chargeStatus","chargePaid","chargeMethod","chargeGross","intentAmount","intentCurrency","intentCapture","allocationRequested"})
    void ownedPaidStatusCannotReplaceFullCashGraph(String broken){
        for(var indicator:java.util.List.of("omitted","null","zero"))for(var representation:java.util.List.of("exact","omitted","null")){
            var f=new StripeBillingOwnedSubscriptionFixture();f.flatSubscription(representation);indicator(f,indicator);
            switch(broken){
                case "missingCharge"->f.pi.remove("latest_charge");case "missingPaidAt"->f.allocation.getAsJsonObject("status_transitions").remove("paid_at");case "foreignAllocation"->f.allocation.addProperty("invoice","in_foreign");
                case "allocationCurrency"->f.allocation.addProperty("currency","usd");case "allocationLive"->f.allocation.addProperty("livemode",true);
                case "intentLive"->f.pi.addProperty("livemode",true);case "intentCards"->f.pi.add("payment_method_types",com.google.gson.JsonParser.parseString("[\"link\"]"));case "invoiceCurrency"->f.invoice.addProperty("currency","usd");case "invoiceCustomer"->f.invoice.addProperty("customer","cus_foreign");
                case "invoiceAmount"->f.invoice.addProperty("amount_due",999);
                case "nullCaptured"->f.charge.add("captured",com.google.gson.JsonNull.INSTANCE);case "nullChargeAmount"->f.charge.add("amount_captured",com.google.gson.JsonNull.INSTANCE);case "nullIntentReceived"->f.pi.add("amount_received",com.google.gson.JsonNull.INSTANCE);case "nullPaidAt"->f.allocation.getAsJsonObject("status_transitions").add("paid_at",com.google.gson.JsonNull.INSTANCE);
                case "chargeCurrency"->f.charge.addProperty("currency","usd");case "chargeLive"->f.charge.addProperty("livemode",true);case "chargeObject"->f.charge.addProperty("object","payment_intent");case "chargeId"->f.charge.addProperty("id","ch_foreign");case "chargeStatus"->f.charge.addProperty("status","failed");case "chargePaid"->f.charge.addProperty("paid",false);case "chargeMethod"->f.charge.getAsJsonObject("payment_method_details").addProperty("type","link");case "chargeGross"->f.charge.addProperty("amount",999);
                case "intentAmount"->f.pi.addProperty("amount",999);case "intentCurrency"->f.pi.addProperty("currency","usd");case "intentCapture"->f.pi.addProperty("capture_method","manual");case "allocationRequested"->f.allocation.addProperty("amount_requested",999);
                case "uncaptured"->f.charge.addProperty("captured",false);case "chargeAmount"->f.charge.addProperty("amount_captured",999);
                case "chargeCustomer"->f.charge.addProperty("customer","cus_other");case "chargeIntent"->f.charge.addProperty("payment_intent","pi_other");
                case "intentState"->f.pi.addProperty("status","requires_capture");case "intentCustomer"->f.pi.addProperty("customer","cus_other");
                case "allocationAmount"->f.allocation.addProperty("amount_paid",999);case "allocationType"->f.allocation.getAsJsonObject("payment").addProperty("type","future_payment");
                case "offStripe"->f.invoice.addProperty("amount_paid_off_stripe",1000);}
            f.paidGraph();
            if(broken.equals("invoicePaid")||broken.equals("invoiceRemaining")){
                // paidGraph supplies the baseline; replace only the queued invoice response.
                var replies=new java.util.ArrayList<>(f.f.http.replies);f.f.http.replies.clear();f.invoice.addProperty(broken.equals("invoicePaid")?"amount_paid":"amount_remaining",1);
                f.f.http.reply(f.customer.toString());f.f.http.reply(f.remote.toString());f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(f.invoice));for(int n=3;n<replies.size();n++)f.f.http.replies.add(replies.get(n));
            }
            if(broken.equals("missingAllocation")||broken.equals("splitAllocation")){
                var replies=new java.util.ArrayList<>(f.f.http.replies);f.f.http.replies.clear();
                var first=f.allocation.deepCopy();first.addProperty("amount_paid",500);first.addProperty("amount_requested",500);var second=first.deepCopy();second.addProperty("id","inpay_second");
                for(int n=0;n<replies.size();n++)if(n==4)f.f.http.reply(broken.equals("missingAllocation")?StripeBillingOwnedSubscriptionFixture.page():StripeBillingOwnedSubscriptionFixture.page(first,second));else f.f.http.replies.add(replies.get(n));
            }
            if(broken.startsWith("reverse")){
                f.f.http.replies.removeLast();var other=f.allocation.deepCopy();other.addProperty("id","inpay_other");other.addProperty("invoice","in_other");
                f.f.http.reply(broken.equals("reverseMissing")?StripeBillingOwnedSubscriptionFixture.page():broken.equals("reverseForeign")?StripeBillingOwnedSubscriptionFixture.page(other):StripeBillingOwnedSubscriptionFixture.page(f.allocation,other));
            }
            assertThatThrownBy(()->owned(f)).isInstanceOf(PaymentApplicationException.class);
        }
    }
    @ParameterizedTest @ValueSource(strings={"omitted","null","foreign"})
    void ordinaryNullClockStillRequiresExactFlatSubscription(String representation){
        var f=new StripeBillingOwnedSubscriptionFixture();
        for(var resource:java.util.List.of(f.customer,f.remote,f.invoice))resource.remove("test_clock");
        if(representation.equals("foreign"))f.line.addProperty("subscription","sub_foreign");else f.flatSubscription(representation);
        f.graph();assertThatThrownBy(()->StripeBillingSnapshotMapper.retrieve(f.f.clients.create(null),f.ordinary(),f.utc)).isInstanceOf(PaymentApplicationException.class);
        assertThat(f.f.http.calls).hasSize(4).allMatch(c->c.method().name().equals("GET"));
    }
    @Test void foreignTypedBindingRejectsBeforeProviderRead(){
        var f=new StripeBillingOwnedSubscriptionFixture();var ordinary=f.ordinary();var b=f.binding();
        var foreign=new StripeBillingSnapshotMapper.Ownership(ordinary.account(),ordinary.subscription(),new dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding(java.util.UUID.randomUUID(),b.accountId(),b.siteId(),b.customerId(),b.providerReference(),b.creationOperationId(),b.version()),ordinary.prices());
        assertThatThrownBy(()->StripeBillingSnapshotMapper.retrieveOwned(f.f.clients.create(null),foreign,new StripeBillingSnapshotMapper.OwnedInitial(f.receipt()),f.utc)).isInstanceOf(PaymentApplicationException.class);
        assertThat(f.f.http.calls).isEmpty();
    }
    @Test void ordinaryCashKeepsFullCapturedProof(){
        var f=new StripeBillingOwnedSubscriptionFixture();for(var resource:java.util.List.of(f.customer,f.remote,f.invoice))resource.remove("test_clock");
        f.paidGraph();var s=StripeBillingSnapshotMapper.retrieve(f.f.clients.create(null),f.ordinary(),f.utc);
        assertThat(s.invoices().getFirst().settlement()).isEqualTo(dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind.CASH);
        assertThat(s.invoices().getFirst().cashObjects().getFirst().reference()).isEqualTo("pi_owned");assertThat(s.invoices().getFirst().cashObjects().getFirst().amountMinor()).isEqualTo(1000);
        assertThat(f.f.http.calls).hasSize(8).allMatch(c->c.method().name().equals("GET"));
    }
    static dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Snapshot owned(StripeBillingOwnedSubscriptionFixture f){return StripeBillingSnapshotMapper.retrieveOwned(f.f.clients.create(null),f.ordinary(),new StripeBillingSnapshotMapper.OwnedInitial(f.receipt()),f.utc);}
}
