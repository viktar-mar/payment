package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.stripe.net.RequestOptions;
import com.stripe.net.StripeResponse;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingFirstRenewalMappingTest.*;
import static org.assertj.core.api.Assertions.*;

/** The SDK transport consumes only locally queued responses and cannot open sockets. */
class StripeBillingFailedRenewalMappingTest {
    static final StripeBillingSnapshotMapper.ReadExecutor EXECUTOR=new StripeBillingSnapshotMapper.ReadExecutor(){
        public <T>T execute(StripeBillingSnapshotMapper.Call<T> call){try{return call.run();}catch(com.stripe.exception.StripeException e){throw new IllegalStateException(e);}}
        public RequestOptions options(){return null;}
    };
    static Boundary boundary(Admission a,StripeBillingOwnedSubscriptionFixture f){
        return new Boundary(new UUID(0,24),a.id(),boundaryReady(a,f.utc.plusSeconds(4)),"si_owned","in_renewal",
            new Subscription.Period(Instant.ofEpochSecond(1702678400),Instant.ofEpochSecond(1705356800)),"b".repeat(64),f.utc.plusSeconds(5));
    }
    static OwnedFailedRenewal.Admission failed(Admission a,Boundary b){
        var ready=finalReady(a,b);return new OwnedFailedRenewal.Admission(new UUID(0,30),a,ready,b,a.sourceSha(),ready.observedAt());
    }
    static void replace(StripeBillingOwnedSubscriptionFixture f,int i,java.util.function.Consumer<com.google.gson.JsonObject> change){
        var replies=new ArrayList<>(f.f.http.replies);var old=(StripeResponse)replies.get(i);var json=StripeBillingOwnedSubscriptionFixture.json(old.body());change.accept(json);
        replies.set(i,new StripeResponse(200,com.stripe.net.HttpHeaders.of(Map.of()),json.toString()));f.f.http.replies.clear();f.f.http.replies.addAll(replies);
    }
    static com.google.gson.JsonObject first(com.google.gson.JsonObject j){return j.has("data")?j.getAsJsonArray("data").get(0).getAsJsonObject():j;}
    static void graph(StripeBillingOwnedSubscriptionFixture f){
        queueGraph(f,true);
        replace(f,1,j->j.addProperty("status","past_due"));
        replace(f,2,j->{var x=j.getAsJsonArray("data").get(1).getAsJsonObject();x.addProperty("status","open");x.addProperty("amount_paid",0);x.addProperty("amount_remaining",1000);});
        for(int i:new int[]{9,12})replace(f,i,j->{var x=first(j);x.addProperty("status","open");x.addProperty("is_default",true);x.addProperty("amount_paid",0);});
        replace(f,10,j->{j.addProperty("status","requires_payment_method");j.addProperty("amount_received",0);j.add("last_payment_error",JsonParser.parseString("{\"type\":\"card_error\",\"code\":\"card_declined\",\"decline_code\":\"generic_decline\",\"charge\":\"ch_renewal\"}"));});
        replace(f,11,j->{j.addProperty("status","failed");j.addProperty("paid",false);j.addProperty("captured",false);j.addProperty("amount_captured",0);j.addProperty("amount_refunded",0);j.addProperty("refunded",false);j.addProperty("failure_code","card_declined");j.getAsJsonObject("payment_method_details").add("card",JsonParser.parseString("{}"));j.add("outcome",JsonParser.parseString("{\"type\":\"issuer_declined\",\"reason\":\"generic_decline\"}"));});
    }
    static StripeBillingSnapshotMapper.FailedRenewalRead map(StripeBillingOwnedSubscriptionFixture f,Admission a,Boundary b){
        return StripeBillingSnapshotMapper.retrieveOwnedFailedRenewal(f.f.clients.create(f.f.credentials.resolve(f.f.account)),f.ordinary(),failed(a,b),f.utc.plusSeconds(10),EXECUTOR);
    }
    private static final List<String> READ_PATHS=List.of(
        "/v1/customers/cus_owned","/v1/subscriptions/sub_owned","/v1/invoices",
        "/v1/invoices/in_owned/lines","/v1/invoice_payments","/v1/payment_intents/pi_owned",
        "/v1/charges/ch_owned","/v1/invoice_payments","/v1/invoices/in_renewal/lines",
        "/v1/invoice_payments","/v1/payment_intents/pi_renewal","/v1/charges/ch_renewal","/v1/invoice_payments");
    private static void assertReadPrefix(StripeBillingOwnedSubscriptionFixture f){
        var calls=f.f.http.calls;
        assertThat(calls).hasSizeLessThanOrEqualTo(READ_PATHS.size()).allSatisfy(request->assertThat(request.method().name()).isEqualTo("GET"));
        assertThat(calls.stream().map(request->request.url().getPath()).toList()).isEqualTo(READ_PATHS.subList(0,calls.size()));
    }
    @Test void declineProducesDescriptiveOpenSnapshotAndOnlyGets(){
        var f=paidLocal();var a=admission(f);var b=boundary(a,f);graph(f);var result=map(f,a,b);var snapshot=result.snapshot();
        assertThat(result.allocation()).isEqualTo("inpay_renewal");assertThat(result.paymentIntent()).isEqualTo("pi_renewal");
        assertThat(snapshot.status()).isEqualTo(Subscription.Status.PAST_DUE);assertThat(snapshot.period()).isEqualTo(b.period());
        assertThat(snapshot.invoices()).hasSize(2);assertThat(snapshot.invoices().getFirst().cashObjects().getFirst().reference()).isEqualTo("pi_owned");
        assertThat(snapshot.invoices().getLast().settlement()).isEqualTo(SettlementKind.OPEN);assertThat(snapshot.invoices().getLast().cashObjects()).isEmpty();
        assertThat(snapshot.paymentMethodReady()).isFalse();assertThat(snapshot.recoveryObservations()).isEmpty();assertThat(snapshot.operationKey()).isNull();assertThat(snapshot.operationObservation()).isNull();assertThat(snapshot.providerRevision()).isZero();
        assertThat(f.f.http.replies).isEmpty();assertThat(f.f.http.calls).hasSize(13);assertReadPrefix(f);
    }
    @Test void successEntryRefusesTheFailedGraph(){
        var f=paidLocal();var a=admission(f);var b=boundary(a,f);graph(f);
        assertThatThrownBy(()->StripeBillingSnapshotMapper.retrieveOwnedFirstRenewal(f.f.clients.create(f.f.credentials.resolve(f.f.account)),f.ordinary(),a,finalReady(a,b),f.utc.plusSeconds(10),b)).isInstanceOf(RuntimeException.class);
    }
    @Test void wrongSavedInvoiceAndMissingExecutorAreRefused(){
        var f=paidLocal();var a=admission(f);var b=boundary(a,f);
        var wrong=new Boundary(b.id(),b.admissionId(),b.ready(),b.item(),"in_other",b.period(),b.fingerprint(),b.observedAt());
        graph(f);assertMismatch(()->map(f,a,wrong));assertReadPrefix(f);
        var other=paidLocal();var second=admission(other);var saved=boundary(second,other);
        assertMismatch(()->StripeBillingSnapshotMapper.retrieveOwnedFailedRenewal(other.f.clients.create(other.f.credentials.resolve(other.f.account)),other.ordinary(),failed(second,saved),other.utc.plusSeconds(10),null));
        assertThat(other.f.http.calls).isEmpty();
    }
    private static void assertMismatch(org.assertj.core.api.ThrowableAssert.ThrowingCallable call){
        assertThatThrownBy(call).isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,e->{
            assertThat(e.code()).isEqualTo("PROVIDER_EVIDENCE_MISMATCH");
            assertThat(e.status()).isEqualTo(409);
            assertThat(e.getMessage()).isEqualTo("Provider billing evidence does not match");
        });
    }
    @ParameterizedTest @ValueSource(strings={"draft","paid","unknown-requested","unknown-intent-amount","unknown-captured"})
    void statusAndUnknownAmountsAreRejectedSafely(String mutation){
        var f=paidLocal();var a=admission(f);var b=boundary(a,f);
        if(mutation.equals("paid")){queueGraph(f,true);replace(f,1,j->j.addProperty("status","past_due"));}
        else graph(f);
        if(mutation.equals("paid")){assertMismatch(()->map(f,a,b));assertThat(f.f.http.calls).hasSize(13);assertReadPrefix(f);return;}
        int index=switch(mutation){case "draft"->2;case "unknown-requested"->9;case "unknown-intent-amount"->10;default->11;};
        replace(f,index,j->{var x=first(j);switch(mutation){
            case "draft"->j.getAsJsonArray("data").get(1).getAsJsonObject().addProperty("status","draft");
            case "unknown-requested"->x.add("amount_requested",JsonNull.INSTANCE);
            case "unknown-intent-amount"->x.add("amount",JsonNull.INSTANCE);
            case "unknown-captured"->x.add("amount_captured",JsonNull.INSTANCE);
            default->throw new AssertionError(mutation);
        }});
        assertMismatch(()->map(f,a,b));
        assertThat(f.f.http.calls).hasSize(switch(mutation){case "draft","unknown-requested"->10;case "unknown-intent-amount"->11;default->12;});assertReadPrefix(f);
    }
    @ParameterizedTest @ValueSource(strings={"revision","account"})
    void localProjectionDriftIsRejectedBeforeAnyRead(String mutation){
        var f=paidLocal();var a=admission(f);var b=boundary(a,f);var s=f.local;
        var account=mutation.equals("account")?new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(new UUID(0,999)):s.paymentAccountId();
        var revision=mutation.equals("revision")?s.revision()+1:s.revision();
        f.local=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),account,s.billingPrice(),s.entitledPrice(),s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),s.debt(),s.terminalReason(),revision,s.createdAt(),s.updatedAt(),s.lastReconciledAt());
        graph(f);assertMismatch(()->map(f,a,b));assertThat(f.f.http.calls).isEmpty();assertReadPrefix(f);
    }
    @ParameterizedTest @ValueSource(strings={"clock","customer","terms","third","credit","tax","period","initial-cash","split","canceled","not-default","unknown-paid","foreign-allocation","processing","action","succeeded","missing-error","error-code","error-charge","charge-paid","charge-captured","charge-amount","refund","refund-unknown","refunded","outcome","reverse","missing-default","requested","same-intent","charge-owner","charge-currency","charge-failure","unknown-received","error-type","error-missing-code","unknown-off-stripe","missing-card"})
    void inconsistentGraphIsRefused(String mutation){
        var f=paidLocal();var a=admission(f);var b=boundary(a,f);graph(f);
        int index=switch(mutation){case "clock","customer"->0;case "terms"->1;case "third","credit","tax","unknown-off-stripe"->2;case "period"->8;case "initial-cash"->5;case "split","canceled","not-default","unknown-paid","foreign-allocation","missing-default","requested","same-intent"->9;case "processing","action","succeeded","missing-error","error-code","error-charge","unknown-received","error-type","error-missing-code"->10;case "reverse"->12;default->11;};
        replace(f,index,j->{var x=first(j);switch(mutation){
            case "clock"->x.addProperty("test_clock","clock_other");case "customer"->x.addProperty("id","cus_other");
            case "terms"->x.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject().getAsJsonObject("price").addProperty("unit_amount",999);
            case "third"->{var z=j.getAsJsonArray("data").get(1).getAsJsonObject().deepCopy();z.addProperty("id","in_third");j.getAsJsonArray("data").add(z);}
            case "unknown-off-stripe"->j.getAsJsonArray("data").get(1).getAsJsonObject().remove("amount_paid_off_stripe");case "credit"->j.getAsJsonArray("data").get(1).getAsJsonObject().addProperty("pre_payment_credit_notes_amount",1);
            case "tax"->j.getAsJsonArray("data").get(1).getAsJsonObject().add("total_taxes",JsonParser.parseString("[{\"amount\":5}]"));
            case "period"->x.getAsJsonObject("period").addProperty("start",1702678401);case "initial-cash"->x.addProperty("amount_received",0);
            case "split"->{var z=x.deepCopy();z.addProperty("id","inpay_split");j.getAsJsonArray("data").add(z);}
            case "missing-default"->x.remove("is_default");case "requested"->x.addProperty("amount_requested",999);case "same-intent"->x.getAsJsonObject("payment").addProperty("payment_intent","pi_owned");case "canceled"->x.addProperty("status","canceled");case "not-default"->x.addProperty("is_default",false);case "unknown-paid"->x.add("amount_paid",JsonNull.INSTANCE);case "foreign-allocation"->x.addProperty("invoice","in_other");
            case "processing"->x.addProperty("status","processing");case "action"->x.addProperty("status","requires_action");case "succeeded"->x.addProperty("status","succeeded");
            case "unknown-received"->x.remove("amount_received");case "error-type"->x.getAsJsonObject("last_payment_error").addProperty("type","api_error");case "error-missing-code"->x.getAsJsonObject("last_payment_error").remove("decline_code");case "missing-error"->x.remove("last_payment_error");case "error-code"->x.getAsJsonObject("last_payment_error").addProperty("decline_code","other");case "error-charge"->x.getAsJsonObject("last_payment_error").addProperty("charge","ch_other");
            case "missing-card"->x.getAsJsonObject("payment_method_details").remove("card");case "charge-owner"->x.addProperty("customer","cus_other");case "charge-currency"->x.addProperty("currency","usd");case "charge-failure"->x.addProperty("failure_code","other");case "refunded"->x.addProperty("refunded",true);case "charge-paid"->x.addProperty("paid",true);case "charge-captured"->x.addProperty("captured",true);case "charge-amount"->x.addProperty("amount",999);case "refund"->x.addProperty("amount_refunded",1);case "refund-unknown"->x.remove("amount_refunded");case "outcome"->x.getAsJsonObject("outcome").addProperty("reason","other");case "reverse"->x.addProperty("invoice","in_other");default->throw new AssertionError(mutation);
        }});
        assertMismatch(()->map(f,a,b));assertReadPrefix(f);
    }
}
