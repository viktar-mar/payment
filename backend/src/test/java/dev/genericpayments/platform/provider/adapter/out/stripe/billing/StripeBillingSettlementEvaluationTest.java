package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.model.Invoice;
import com.stripe.model.SubscriptionItem;
import com.stripe.net.ApiResource;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Exact mapper expression with official SDK values and two inert GET responses only. */
class StripeBillingSettlementEvaluationTest {
    @Test void allocationReasonsRetainLiteralShortCircuitGetterOrder() throws Exception {
        String[][] rows={
            {"status","null","STATUS_UNAVAILABLE","1"},{"status","canceled","STATUS_NOT_OPEN","1"},
            {"is_default","null","DEFAULT_UNAVAILABLE","2"},{"is_default","false","DEFAULT_NOT_TRUE","2"},
            {"amount_requested","null","AMOUNT_REQUESTED_UNAVAILABLE","3"},{"amount_requested","999","AMOUNT_REQUESTED_MISMATCH","3"},
            {"amount_paid","null","AMOUNT_PAID_UNAVAILABLE","4"},{"amount_paid","-1","AMOUNT_PAID_NONZERO","4"},
            {"payment","null","PAYMENT_UNAVAILABLE","5"},{"type","null","TYPE_UNAVAILABLE","7"},{"type","payment_record","TYPE_UNEXPECTED","7"}
        };
        var order=List.of("status","default","requested","paid","payment1","payment2","type");
        for(var row:rows)for(boolean invalidateLater:List.of(false,true))for(boolean recorded:List.of(false,true)){
            var probe=new AllocationProbe(recorded);var json=probe.f.allocation;
            var target=row[0].equals("type")?json.getAsJsonObject("payment"):json;
            target.add(row[0],row[1].equals("null")?com.google.gson.JsonNull.INSTANCE:row[0].equals("status")||row[0].equals("type")?new com.google.gson.JsonPrimitive(row[1]):com.google.gson.JsonParser.parseString(row[1]));
            if(invalidateLater){
                int boundary=Integer.parseInt(row[3]);
                if(boundary<2)json.add("is_default",com.google.gson.JsonNull.INSTANCE);
                if(boundary<3)json.add("amount_requested",com.google.gson.JsonNull.INSTANCE);
                if(boundary<4)json.add("amount_paid",com.google.gson.JsonNull.INSTANCE);
                if(boundary<5)json.add("payment",com.google.gson.JsonNull.INSTANCE);
            }
            probe.load();
            assertThat(probe.evaluate()).as(row[2]).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
            assertThat(probe.allocation.reads).containsExactlyElementsOf(order.subList(0,Integer.parseInt(row[3])));
            if(recorded){
                assertThat(probe.failure.guard()).isEqualTo(StripeBillingSnapshotMapper.OwnedActionGuard.PAYMENT_ALLOCATION);assertThat(probe.reason()).isEqualTo(row[2]);
                var stage=StripeBillingClockActionAdapter.class.getDeclaredMethod("allocationStage",probe.failure.allocation().getClass());stage.setAccessible(true);
                assertThat(stage.invoke(null,probe.failure.allocation())).isEqualTo("ACTION_GRAPH_PAYMENT_ALLOCATION_"+row[2]);
                assertThat(stage.invoke(null,new Object[]{null})).isEqualTo("ACTION_GRAPH_PAYMENT_ALLOCATION");
            }
            else assertThat(probe.failure.guard()).isNull();
            assertThat(probe.f.f.http.calls).isEmpty();
        }
        var baseline=new AllocationProbe(true);baseline.load();
        baseline.f.f.http.reply(baseline.f.pi.toString());baseline.f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(baseline.f.allocation));
        assertThat(baseline.evaluate()).isNull();assertThat(baseline.failure.guard()).isNull();assertThat(baseline.reason()).isNull();
        assertThat(baseline.allocation.boundaryReads).containsExactlyElementsOf(order);
        assertThat(baseline.f.f.http.calls).hasSize(2).allMatch(c->c.method().name().equals("GET"));
    }
    @Test void allocationThrowingGettersKeepGenericProvenance() throws Exception {
        var order=List.of("status","default","requested","paid","payment1","payment2","type");
        for(String getter:List.of("status","default","requested","paid","payment1","payment2","type","nullSecondPayment")){
            var probe=new AllocationProbe(true);probe.load();probe.allocation.throwAt=getter;
            var thrown=probe.evaluate();
            assertThat(thrown).isInstanceOf(getter.equals("nullSecondPayment")?NullPointerException.class:UnreadableGetterFailure.class);
            assertThat(probe.failure.guard()).isNull();assertThat(probe.reason()).isNull();
            int last=getter.equals("nullSecondPayment")?6:order.indexOf(getter)+1;
            assertThat(probe.allocation.reads).containsExactlyElementsOf(order.subList(0,last));assertThat(probe.f.f.http.calls).isEmpty();
        }
    }
    /** Real private paymentGraph, preloaded with typed SDK objects; no production test hook. */
    static final class AllocationProbe {
        final StripeBillingOwnedSubscriptionFixture f=new StripeBillingOwnedSubscriptionFixture();
        final StripeBillingSnapshotMapper.OwnedActionFailure failure=new StripeBillingSnapshotMapper.OwnedActionFailure();
        final CountingAllocation allocation=new CountingAllocation();
        final Object read;final Class<?> readType;final java.lang.reflect.Method graph;
        AllocationProbe(boolean recorded) throws Exception {
            f.allocation.addProperty("status","open");f.allocation.addProperty("is_default",true);f.allocation.addProperty("amount_paid",0);f.allocation.remove("status_transitions");
            f.pi.addProperty("status","requires_payment_method");f.pi.addProperty("amount_received",0);f.pi.remove("latest_charge");f.pi.addProperty("confirmation_method","automatic");f.pi.addProperty("client_secret","pi_owned_secret_INERT");
            readType=Class.forName(StripeBillingSnapshotMapper.class.getName()+"$Read");
            var constructor=readType.getDeclaredConstructor(com.stripe.StripeClient.class,StripeBillingSnapshotMapper.Ownership.class,java.time.Instant.class,StripeBillingSnapshotMapper.OwnedInitial.class,StripeBillingSnapshotMapper.OwnedActionFailure.class);constructor.setAccessible(true);
            read=constructor.newInstance(f.f.clients.create(null),f.ordinary(),f.utc,null,recorded?failure:null);
            var invoices=readType.getDeclaredField("sourceInvoices");invoices.setAccessible(true);invoices.set(read,List.of(ApiResource.GSON.fromJson(f.invoice.toString(),Invoice.class)));
            var allocations=readType.getDeclaredField("sourceAllocations");allocations.setAccessible(true);
            @SuppressWarnings("unchecked") var values=(java.util.Map<String,List<com.stripe.model.InvoicePayment>>)allocations.get(read);values.put("in_owned",List.of(allocation));
            graph=StripeBillingSnapshotMapper.class.getDeclaredMethod("paymentGraph",readType,dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Invoice.class,String.class);graph.setAccessible(true);
        }
        void load() throws Exception {
            var parsed=ApiResource.GSON.fromJson(f.allocation.toString(),com.stripe.model.InvoicePayment.class);
            for(var field:com.stripe.model.InvoicePayment.class.getDeclaredFields())if(!java.lang.reflect.Modifier.isStatic(field.getModifiers())){field.setAccessible(true);field.set(allocation,field.get(parsed));}
            if(parsed.getPayment()!=null){var payment=new CountingPayment(allocation);payment.setType(parsed.getPayment().getType());payment.setPaymentIntent(parsed.getPayment().getPaymentIntent());allocation.setPayment(payment);}
        }
        Throwable evaluate() throws Exception {
            var invoice=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Invoice("in_owned",dev.genericpayments.platform.subscriptions.domain.BillingRecord.Kind.INITIAL,f.terms,null,new dev.genericpayments.platform.subscriptions.domain.Subscription.Period(java.time.Instant.ofEpochSecond(1699999900),java.time.Instant.ofEpochSecond(1702678400)),1000,"EUR",dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind.OPEN,List.of());
            try{graph.invoke(null,read,invoice,"cus_owned");return null;}catch(java.lang.reflect.InvocationTargetException e){return e.getCause();}
        }
        String reason() throws Exception {var method=failure.getClass().getDeclaredMethod("allocation");method.setAccessible(true);var value=method.invoke(failure);return value==null?null:((Enum<?>)value).name();}
    }
    static final class UnreadableGetterFailure extends RuntimeException {
        @Override public String getMessage(){throw new AssertionError("Do not inspect getter failures");}
        @Override public String toString(){throw new AssertionError("Do not stringify getter failures");}
    }
    static final class CountingAllocation extends com.stripe.model.InvoicePayment {
        final List<String> reads=new ArrayList<>();List<String> boundaryReads;String throwAt;int payments;
        void reached(String name){if(boundaryReads==null)reads.add(name);if(name.equals(throwAt))throw new UnreadableGetterFailure();}
        @Override public String getStatus(){reached("status");return super.getStatus();}
        @Override public Boolean getIsDefault(){reached("default");return super.getIsDefault();}
        @Override public Long getAmountRequested(){reached("requested");return super.getAmountRequested();}
        @Override public Long getAmountPaid(){reached("paid");return super.getAmountPaid();}
        @Override public Payment getPayment(){payments++;if(payments==3)boundaryReads=List.copyOf(reads);if(payments<=2)reached("payment"+payments);if(payments==2&&"nullSecondPayment".equals(throwAt))return null;return super.getPayment();}
    }
    static final class CountingPayment extends com.stripe.model.InvoicePayment.Payment {
        final CountingAllocation allocation;CountingPayment(CountingAllocation allocation){this.allocation=allocation;}
        @Override public String getType(){allocation.reached("type");return super.getType();}
    }
    // Splitting zeros into short-circuited getter calls would skip five original argument reads.
    @ParameterizedTest @ValueSource(strings={"nonzero","omitted","null"})
    void allSixZeroArgumentsAreReadBeforeFirstRefusal(String indicator) throws Exception {
        var f=new StripeBillingOwnedSubscriptionFixture();
        switch(indicator){case "nonzero"->f.invoice.addProperty("amount_paid_off_stripe",1);case "omitted"->f.invoice.remove("amount_paid_off_stripe");case "null"->f.invoice.add("amount_paid_off_stripe",com.google.gson.JsonNull.INSTANCE);}
        f.invoice.addProperty("ending_balance",1);
        var parsed=ApiResource.GSON.fromJson(f.invoice.toString(),Invoice.class);
        var invoice=new CountingInvoice();
        for(var field:Invoice.class.getDeclaredFields())if(!java.lang.reflect.Modifier.isStatic(field.getModifiers())){field.setAccessible(true);field.set(invoice,field.get(parsed));}
        var remote=ApiResource.GSON.fromJson(f.remote.toString(),com.stripe.model.Subscription.class);
        f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(f.line));f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page());
        var type=Class.forName(StripeBillingSnapshotMapper.class.getName()+"$Read");
        var constructor=type.getDeclaredConstructor(com.stripe.StripeClient.class,StripeBillingSnapshotMapper.Ownership.class,java.time.Instant.class,StripeBillingSnapshotMapper.OwnedInitial.class,StripeBillingSnapshotMapper.OwnedActionFailure.class);
        constructor.setAccessible(true);
        var failure=new StripeBillingSnapshotMapper.OwnedActionFailure();
        var read=constructor.newInstance(f.f.clients.create(null),f.ordinary(),f.utc,new StripeBillingSnapshotMapper.OwnedInitial(f.receipt()),failure);
        var prices=type.getDeclaredField("prices");prices.setAccessible(true);
        @SuppressWarnings("unchecked") var mapped=(java.util.Map<String,StripeBillingSnapshotMapper.PublishedPrice>)prices.get(read);
        mapped.put("price_owned",new StripeBillingSnapshotMapper.PublishedPrice(f.terms,"price_owned","prod_owned"));
        var normalize=type.getDeclaredMethod("invoice",Invoice.class,com.stripe.model.Subscription.class,SubscriptionItem.class,dev.genericpayments.platform.subscriptions.domain.Subscription.Period.class);
        normalize.setAccessible(true);
        var result=(dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Invoice)normalize.invoke(read,invoice,remote,remote.getItems().getData().getFirst(),null);
        assertThat(result.settlement()).isEqualTo(dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind.UNSUPPORTED);
        assertThat(invoice.reads).containsExactly("paidOffStripe","overpaid","starting","ending","preCredit","postCredit");
        assertThat(invoice.statusReads).as("the typed status is cached once before the eager amount getters").isEqualTo(1);
        assertThat(failure.guard()).as("tentative normalization cannot commit a boundary failure").isNull();
        assertThat(f.f.http.calls).hasSize(2).allMatch(c->c.method().name().equals("GET"));
    }
    @ParameterizedTest @ValueSource(strings={"zero","omitted","null","positive","negative"})
    void actualNoAdjustmentsRetainsExplicitZeroRequirement(String indicator){
        var f=new StripeBillingOwnedSubscriptionFixture();
        assertThat(StripeBillingMutationAdapter.noAdjustments(ApiResource.GSON.fromJson(f.invoice.toString(),Invoice.class))).isTrue();
        switch(indicator){case "omitted"->f.invoice.remove("amount_paid_off_stripe");case "null"->f.invoice.add("amount_paid_off_stripe",com.google.gson.JsonNull.INSTANCE);case "positive"->f.invoice.addProperty("amount_paid_off_stripe",1);case "negative"->f.invoice.addProperty("amount_paid_off_stripe",-1);}
        assertThat(StripeBillingMutationAdapter.noAdjustments(ApiResource.GSON.fromJson(f.invoice.toString(),Invoice.class))).isEqualTo(indicator.equals("zero"));
        assertThat(f.f.http.calls).isEmpty();
    }
    static final class CountingInvoice extends Invoice {
        final List<String> reads=new ArrayList<>();
        int statusReads;
        @Override public String getStatus(){statusReads++;return super.getStatus();}
        @Override public Long getAmountPaidOffStripe(){reads.add("paidOffStripe");return super.getAmountPaidOffStripe();}
        @Override public Long getAmountOverpaid(){reads.add("overpaid");return super.getAmountOverpaid();}
        @Override public Long getStartingBalance(){reads.add("starting");return super.getStartingBalance();}
        @Override public Long getEndingBalance(){reads.add("ending");return super.getEndingBalance();}
        @Override public Long getPrePaymentCreditNotesAmount(){reads.add("preCredit");return super.getPrePaymentCreditNotesAmount();}
        @Override public Long getPostPaymentCreditNotesAmount(){reads.add("postCredit");return super.getPostPaymentCreditNotesAmount();}
    }
}
