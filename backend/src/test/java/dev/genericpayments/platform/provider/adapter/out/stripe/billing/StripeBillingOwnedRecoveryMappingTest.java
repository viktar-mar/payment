package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.stripe.net.HttpHeaders;
import com.stripe.net.StripeResponse;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingFirstRenewalMappingTest.*;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingFailedRenewalMappingTest.*;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedMethodMappingTest.assertMismatch;
import static org.assertj.core.api.Assertions.*;

/** Real official SDK graph normalization over queued memory responses; no local server or provider IO. */
class StripeBillingOwnedRecoveryMappingTest {
    private static final List<String> PATHS=List.of("/v1/customers/cus_owned","/v1/subscriptions/sub_owned","/v1/invoices",
        "/v1/invoices/in_owned/lines","/v1/invoice_payments","/v1/payment_intents/pi_owned","/v1/charges/ch_owned","/v1/invoice_payments",
        "/v1/invoices/in_renewal/lines","/v1/invoice_payments","/v1/payment_intents/pi_renewal","/v1/charges/ch_renewal","/v1/invoice_payments");
    static final class Graph {
        final StripeBillingOwnedMethodMappingTest.Graph debt=new StripeBillingOwnedMethodMappingTest.Graph();
        final StripeBillingOwnedSubscriptionFixture f=debt.f;
        OwnedFailedRenewal.Recovery recovery;
        SubscriptionOperation operation;
        Graph(){
            var s=f.local;var id=new UUID(0,50);var at=f.utc.plusSeconds(12);
            operation=new SubscriptionOperation(id,s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.RECOVER,SubscriptionOperation.Status.RESERVED,
                "owned-failed-renewal:00000000-0000-0000-0000-00000000001e",
                "e9a83a3191f3e7e3abbebe1f19da5fa34b59f7827aa852d02c287c0ca375dc3b",
                "v1.recover.00000000-0000-0000-0000-000000000020.4a9131ca5227ca8d1f8352b1ef4be89808e15ca6a286c04704db4bb335d7c9f3",
                "subscription:10000000-0000-0000-0000-000000000001:"+id+":RECOVER",3,1,"sub_owned",null,at,at,0);
            recovery=new OwnedFailedRenewal.Recovery(debt.admission.id(),id,debt.failure.billId(),"inpay_renewal","pi_renewal",3,0,f.utc.plusSeconds(13));
        }
        void queue(){
            queueGraph(f,true);
            for(int i:new int[]{9,12})replace(f,i,j->first(j).addProperty("is_default",true));
        }
        StripeBillingSnapshotMapper.OwnedRecoveredRead read(){return read(debt.at,debt.executor);}
        StripeBillingSnapshotMapper.OwnedRecoveredRead read(Instant at,StripeBillingSnapshotMapper.ReadExecutor executor){
            return StripeBillingSnapshotMapper.retrieveOwnedRecoveredRenewal(f.f.clients.create(f.f.credentials.resolve(f.f.account)),f.ordinary(),debt.admission,debt.failure,recovery,operation,at,executor);
        }
    }
    // Catches falsely accepting OPEN/replacement cash or granting operation-completion authority.
    @Test void exactRecoveredDebtDescribesTwoCashInvoicesWithoutApplyingTheReservedOperation(){
        var g=new Graph();g.queue();var local=g.f.local;var result=g.read();var s=result.snapshot();
        assertThat(result.allocation()).isEqualTo("inpay_renewal");assertThat(result.paymentIntent()).isEqualTo("pi_renewal");
        assertThat(s.status()).isEqualTo(Subscription.Status.ACTIVE);assertThat(s.period()).isEqualTo(g.debt.failure.period());
        assertThat(s.invoices()).hasSize(2).allSatisfy(i->assertThat(i.settlement()).isEqualTo(SettlementKind.CASH));
        var initial=s.invoices().getFirst();var renewal=s.invoices().getLast();
        assertThat(initial.reference()).isEqualTo("in_owned");assertThat(initial.cashObjects()).hasSize(1);
        assertThat(initial.cashObjects().getFirst().reference()).isEqualTo("pi_owned");assertThat(initial.cashObjects().getFirst().amountMinor()).isEqualTo(1000);
        assertThat(initial.cashObjects().getFirst().occurredAt()).isEqualTo(Instant.ofEpochSecond(1700000011));
        assertThat(renewal.reference()).isEqualTo("in_renewal");assertThat(renewal.cashObjects()).hasSize(1);
        assertThat(renewal.cashObjects().getFirst().reference()).isEqualTo("pi_renewal");assertThat(renewal.cashObjects().getFirst().amountMinor()).isEqualTo(1000);
        assertThat(s.operationKey()).isNull();assertThat(s.operationObservation()).isNull();assertThat(s.recoveryObservations()).isEmpty();assertThat(s.providerRevision()).isZero();
        assertThat(g.f.local).isSameAs(local);assertThat(local.billingStatus()).isEqualTo(Subscription.Status.PAST_DUE);
        assertThat(g.operation.status()).isEqualTo(SubscriptionOperation.Status.RESERVED);assertThat(s.paymentMethodReady()).isFalse();
        assertThat(result.toString()).doesNotContain("APPLIED",g.operation.providerOperationKey(),g.operation.authorityScope(),g.operation.fingerprint());
        assertThat(g.f.f.http.replies).isEmpty();assertThat(g.debt.executions).isEqualTo(13);assertPrefix(g);
    }
    private static void assertPrefix(Graph g){
        var calls=g.f.f.http.calls;assertThat(calls.stream().map(r->r.url().getPath()).toList()).isEqualTo(PATHS.subList(0,calls.size()));
        assertThat(calls).allSatisfy(r->{assertThat(r.method().name()).isEqualTo("GET");assertThat(r.options().getMaxNetworkRetries()).isZero();
            assertThat(r.options().getReadTimeout()).isEqualTo(1234);assertThat(r.options().getConnectTimeout()).isEqualTo(1234);
            if(r.params()!=null&&r.params().containsKey("limit"))assertThat(r.params().get("limit").toString()).isEqualTo("10");});
    }
    @Test void aLaterSuccessfulChargeMayDifferFromTheHistoricalDeclinedCharge(){
        var g=new Graph();g.queue();replace(g.f,10,j->j.addProperty("latest_charge","ch_recovered"));replace(g.f,11,j->j.addProperty("id","ch_recovered"));
        var s=g.read().snapshot();assertThat(s.invoices().getLast().cashObjects().getFirst().reference()).isEqualTo("pi_renewal");
        assertThat(g.f.f.http.calls.get(11).url().getPath()).isEqualTo("/v1/charges/ch_recovered");
        assertThat(g.f.f.http.calls).allSatisfy(r->assertThat(r.method().name()).isEqualTo("GET"));
    }
    @ParameterizedTest @ValueSource(strings={"renewal-allocation","renewal-intent","initial-allocation","initial-intent"})
    void coherentReplacementGraphsCannotReplaceTheSavedCashIdentity(String mutation){
        var g=new Graph();g.queue();boolean initial=mutation.startsWith("initial-");int allocation=initial?4:9,pi=initial?5:10,charge=initial?6:11,reverse=initial?7:12;
        if(mutation.endsWith("allocation")){for(int index:new int[]{allocation,reverse})replace(g.f,index,j->first(j).addProperty("id","inpay_replaced"));}
        else{
            for(int index:new int[]{allocation,reverse})replace(g.f,index,j->first(j).getAsJsonObject("payment").addProperty("payment_intent","pi_replaced"));
            replace(g.f,pi,j->j.addProperty("id","pi_replaced"));replace(g.f,charge,j->j.addProperty("payment_intent","pi_replaced"));
        }
        assertMismatch(g::read);assertThat(g.f.f.http.calls).allSatisfy(r->assertThat(r.method().name()).isEqualTo("GET"));
    }
    @Test void recoveryAssociationAndObservationAllowExactChronologicalBoundaries(){
        var g=new Graph();var r=g.recovery;var o=g.operation;var at=g.debt.failure.recordedAt();
        g.recovery=new OwnedFailedRenewal.Recovery(r.admissionId(),r.operationId(),r.billId(),r.allocation(),r.paymentIntent(),r.expectedRevision(),r.operationVersion(),at);
        g.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),o.status(),o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),null,at,at,o.version());
        g.queue();assertThat(g.read(at,g.debt.executor).snapshot().observedAt()).isEqualTo(at);
    }
    @Test void legacyMethodReaderRetainsGrossInitialCashWhenARefundIsASeparateLaterFact(){
        var g=new StripeBillingOwnedMethodMappingTest.Graph();g.queue();replace(g.f,6,j->{j.addProperty("amount_refunded",1);j.addProperty("refunded",true);});
        var s=g.read().snapshot();assertThat(s.invoices().getFirst().cashObjects().getFirst().amountMinor()).isEqualTo(1000);assertThat(s.status()).isEqualTo(Subscription.Status.PAST_DUE);
    }
    // Each mutation removes a required local join or confuses method completion with recovery evidence.
    @ParameterizedTest @ValueSource(strings={"null-recovery","null-operation","executor","observation","association-before-failure","association-future","recovery-admission","recovery-operation","recovery-bill","recovery-allocation","recovery-intent","recovery-revision","recovery-version",
        "failure-admission","failure-invoice","failure-period","failure-bill-version","failure-allocation","failure-intent","failure-recorded","failure-grace","failure-revision","failure-bill",
        "operation-kind","operation-succeeded","operation-processing","operation-site","operation-customer","operation-subscription","operation-key","operation-authority","operation-digest","operation-fingerprint","operation-reference","operation-policy","operation-revision","operation-version","operation-created","operation-updated","operation-associated","operation-failure-code","local-active","local-paid-through"})
    void suppliedRecordInconsistencyRefusesBeforeSdkIo(String mutation){
        var g=new Graph();var r=g.recovery;var o=g.operation;var f=g.debt.failure;var other=new UUID(0,99);
        if(mutation.startsWith("recovery-")||mutation.startsWith("association-"))g.recovery=new OwnedFailedRenewal.Recovery(
            mutation.equals("recovery-admission")?other:r.admissionId(),mutation.equals("recovery-operation")?other:r.operationId(),mutation.equals("recovery-bill")?other:r.billId(),
            mutation.equals("recovery-allocation")?"inpay_other":r.allocation(),mutation.equals("recovery-intent")?"pi_other":r.paymentIntent(),mutation.equals("recovery-revision")?4:r.expectedRevision(),mutation.equals("recovery-version")?1:r.operationVersion(),
            mutation.equals("association-before-failure")?f.recordedAt().minusSeconds(1):mutation.equals("association-future")?g.debt.at.plusSeconds(1):r.associatedAt());
        if(mutation.startsWith("failure-"))g.debt.failure=new OwnedFailedRenewal.Failure(mutation.equals("failure-admission")?other:f.admissionId(),f.claimId(),mutation.equals("failure-bill")?other:f.billId(),mutation.equals("failure-bill-version")?1:f.billVersion(),
            mutation.equals("failure-invoice")?"in_other":f.invoice(),mutation.equals("failure-allocation")?"inpay_other":f.allocation(),mutation.equals("failure-intent")?"pi_other":f.paymentIntent(),
            mutation.equals("failure-period")?new Subscription.Period(f.period().start().plusSeconds(1),f.period().end()):f.period(),mutation.equals("failure-grace")?f.graceDeadline().plusSeconds(1):f.graceDeadline(),
            mutation.equals("failure-revision")?4:f.revision(),f.observationSequence(),mutation.equals("failure-recorded")?g.debt.admission.admittedAt().minusSeconds(1):f.recordedAt());
        if(mutation.startsWith("operation-"))g.operation=new SubscriptionOperation(o.id(),mutation.equals("operation-site")?new dev.genericpayments.platform.sites.domain.SiteId(other):o.siteId(),
            mutation.equals("operation-customer")?new dev.genericpayments.platform.customers.domain.CustomerId(other):o.customerId(),mutation.equals("operation-subscription")?other:o.subscriptionId(),
            mutation.equals("operation-kind")?SubscriptionOperation.Kind.SETUP:o.kind(),mutation.equals("operation-succeeded")?SubscriptionOperation.Status.SUCCEEDED:mutation.equals("operation-processing")?SubscriptionOperation.Status.PROCESSING:o.status(),
            mutation.equals("operation-authority")?"foreign":o.authorityScope(),mutation.equals("operation-digest")?"wrong":o.keyDigest(),mutation.equals("operation-fingerprint")?"wrong":o.fingerprint(),mutation.equals("operation-key")?"foreign":o.providerOperationKey(),
            mutation.equals("operation-revision")?4:o.expectedRevision(),mutation.equals("operation-policy")?2:o.policyVersion(),mutation.equals("operation-reference")?"sub_other":o.providerReference(),mutation.equals("operation-failure-code")?"FAILED":null,
            mutation.equals("operation-created")?f.recordedAt().minusSeconds(1):o.createdAt(),mutation.equals("operation-updated")?g.debt.at.plusSeconds(1):mutation.equals("operation-associated")?r.associatedAt().plusSeconds(1):o.updatedAt(),mutation.equals("operation-version")?1:o.version());
        if(mutation.equals("null-recovery"))g.recovery=null;if(mutation.equals("null-operation"))g.operation=null;
        if(mutation.startsWith("local-")){var s=g.f.local;g.f.local=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),
            mutation.equals("local-active")?Subscription.Status.ACTIVE:s.billingStatus(),s.providerReference(),s.currentPeriod(),mutation.equals("local-paid-through")?s.paidThrough().plusSeconds(1):s.paidThrough(),null,null,null,s.debt(),null,s.revision(),s.createdAt(),s.updatedAt(),s.lastReconciledAt());}
        assertMismatch(()->g.read(mutation.equals("observation")?f.recordedAt().minusSeconds(1):g.debt.at,mutation.equals("executor")?null:g.debt.executor));
        assertThat(g.f.f.http.calls).isEmpty();
    }
    // Catches weakened full-cash/default/identity/reverse/refund validation in the new lane only.
    @ParameterizedTest @ValueSource(strings={"customer","clock","remote-past-due","terms","third","invoice","period","tax","adjustment","off-stripe","zero","noncash","open","unknown-invoice-paid","initial-intent","initial-allocation","initial-refund","initial-refund-unknown","initial-refunded",
        "initial-tax","initial-off-stripe-unknown","initial-refunded-unknown","allocation","foreign-allocation","split","default-false","default-missing","allocation-status","requested","requested-unknown","paid","paid-unknown","non-pi","intent","intent-customer","intent-currency","intent-status","intent-amount","received","received-unknown","capture","types",
        "charge","charge-customer","charge-currency","charge-status","charge-paid","charge-captured","charge-amount","charge-captured-amount","charge-refund","charge-refund-unknown","charge-refunded","reverse","reverse-split","paid-at"})
    void remoteDriftDoesNotDescribeRecoveredCash(String mutation){
        var g=new Graph();g.queue();
        int index=switch(mutation){case "customer","clock"->0;case "remote-past-due","terms"->1;case "third","invoice","tax","adjustment","off-stripe","zero","noncash","open","unknown-invoice-paid"->2;
            case "initial-tax","initial-off-stripe-unknown"->2;case "initial-intent"->5;case "initial-allocation"->4;case "initial-refund","initial-refund-unknown","initial-refunded","initial-refunded-unknown"->6;case "period"->8;
            case "allocation","foreign-allocation","split","default-false","default-missing","allocation-status","requested","requested-unknown","paid","paid-unknown","non-pi","paid-at"->9;
            case "intent","intent-customer","intent-currency","intent-status","intent-amount","received","received-unknown","capture","types"->10;case "reverse","reverse-split"->12;default->11;};
        replace(g.f,index,j->{var x=first(j);var renewal=j.has("data")&&index==2?j.getAsJsonArray("data").get(1).getAsJsonObject():x;
            switch(mutation){
                case "customer"->x.addProperty("id","cus_other");case "clock"->x.addProperty("test_clock","clock_other");case "remote-past-due"->x.addProperty("status","past_due");
                case "terms"->x.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject().getAsJsonObject("price").addProperty("unit_amount",999);
                case "third"->{var z=renewal.deepCopy();z.addProperty("id","in_third");j.getAsJsonArray("data").add(z);}
                case "invoice"->renewal.addProperty("id","in_other");case "tax"->renewal.add("total_taxes",JsonParser.parseString("[{\"amount\":5}]"));
                case "adjustment"->renewal.addProperty("post_payment_credit_notes_amount",1);case "off-stripe"->renewal.addProperty("amount_paid_off_stripe",1000);
                case "zero"->{renewal.addProperty("amount_due",0);renewal.addProperty("total",0);renewal.addProperty("subtotal",0);renewal.addProperty("amount_paid",0);}
                case "noncash"->renewal.addProperty("collection_method","send_invoice");case "open"->renewal.addProperty("status","open");case "unknown-invoice-paid"->renewal.remove("amount_paid");
                case "period"->x.getAsJsonObject("period").addProperty("start",1702678401);case "initial-intent","intent"->x.addProperty("id","pi_other");case "initial-allocation","allocation"->x.addProperty("id","inpay_other");
                case "initial-refund","charge-refund"->x.addProperty("amount_refunded",1);case "initial-refund-unknown","charge-refund-unknown"->x.remove("amount_refunded");case "initial-refunded","charge-refunded"->x.addProperty("refunded",true);
                case "initial-tax"->x.add("total_taxes",JsonParser.parseString("[{\"amount\":5}]"));case "initial-off-stripe-unknown"->x.remove("amount_paid_off_stripe");case "initial-refunded-unknown"->x.remove("refunded");
                case "foreign-allocation","reverse"->x.addProperty("invoice","in_other");case "split","reverse-split"->{var z=x.deepCopy();z.addProperty("id","inpay_split");j.getAsJsonArray("data").add(z);}
                case "default-false"->x.addProperty("is_default",false);case "default-missing"->x.remove("is_default");case "allocation-status"->x.addProperty("status","canceled");
                case "requested"->x.addProperty("amount_requested",999);case "requested-unknown"->x.remove("amount_requested");case "paid"->x.addProperty("amount_paid",999);case "paid-unknown"->x.remove("amount_paid");
                case "non-pi"->x.getAsJsonObject("payment").addProperty("type","payment_record");case "paid-at"->x.getAsJsonObject("status_transitions").add("paid_at",JsonNull.INSTANCE);
                case "intent-customer","charge-customer"->x.addProperty("customer","cus_other");case "intent-currency","charge-currency"->x.addProperty("currency","usd");case "intent-status"->x.addProperty("status","processing");
                case "intent-amount","charge-amount"->x.addProperty("amount",999);case "received"->x.addProperty("amount_received",999);case "received-unknown"->x.remove("amount_received");case "capture"->x.addProperty("capture_method","manual");case "types"->x.add("payment_method_types",JsonParser.parseString("[\"link\"]"));
                case "charge"->x.addProperty("payment_intent","pi_other");case "charge-status"->x.addProperty("status","failed");case "charge-paid"->x.addProperty("paid",false);case "charge-captured"->x.addProperty("captured",false);case "charge-captured-amount"->x.addProperty("amount_captured",999);
                default->throw new AssertionError(mutation);
            }});
        assertMismatch(g::read);assertPrefix(g);
    }
    @Test void methodReadyStillLeavesTheSavedDebtOpen(){
        var g=new Graph();g.debt.queue();assertMismatch(g::read);assertPrefix(g);
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6,7,8,9,10,11,12})
    void sdkFailuresStaySafeAndReadOnly(int index){
        var g=new Graph();g.queue();var replies=new ArrayList<>(g.f.f.http.replies);
        replies.set(index,new StripeResponse(503,HttpHeaders.of(Map.of()),"{\"error\":{\"type\":\"api_error\",\"message\":\"do_not_return\"}}"));
        g.f.f.http.replies.clear();g.f.f.http.replies.addAll(replies);assertMismatch(g::read);assertPrefix(g);
    }
    @Test void sharedReaderRefusesAnEleventhPage(){
        var g=new Graph();g.queue();var replies=new ArrayList<>(g.f.f.http.replies);g.f.f.http.replies.clear();g.f.f.http.replies.add(replies.get(0));g.f.f.http.replies.add(replies.get(1));
        for(int i=0;i<11;i++)g.f.f.http.reply("{\"object\":\"list\",\"has_more\":true,\"data\":[{\"id\":\"in_page"+i+"\",\"object\":\"invoice\"}]}");
        assertMismatch(g::read);assertThat(g.debt.executions).isEqualTo(12);
    }
    @Test void sharedReaderRefusesOversizedPages(){
        var g=new Graph();g.queue();replace(g.f,2,j->{for(int i=0;i<9;i++){var invoice=first(j).deepCopy();invoice.addProperty("id","in_extra"+i);j.getAsJsonArray("data").add(invoice);}});
        assertMismatch(g::read);assertThat(g.debt.executions).isEqualTo(3);
    }
}
