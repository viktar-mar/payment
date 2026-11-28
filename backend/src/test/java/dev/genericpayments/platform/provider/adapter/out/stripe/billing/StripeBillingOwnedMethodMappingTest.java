package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.net.RequestOptions;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeRequestFingerprint;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingFirstRenewalMappingTest.*;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingFailedRenewalMappingTest.*;
import static org.assertj.core.api.Assertions.*;

/** Only the SDK's queued, socket-free transport is substituted; normalization and fingerprints are real. */
class StripeBillingOwnedMethodMappingTest {
    static final class Graph {
        final StripeBillingOwnedSubscriptionFixture f=paidLocal();
        final OwnedFailedRenewal.Admission admission=failed(admission(f),boundary(admission(f),f));
        final Instant failureAt=f.utc.plusSeconds(10),at=f.utc.plusSeconds(20);
        OwnedFailedRenewal.Failure failure=new OwnedFailedRenewal.Failure(admission.id(),new UUID(0,31),new UUID(0,32),0,
            "in_renewal","inpay_renewal","pi_renewal",admission.boundary().period(),
            admission.boundary().period().start().plusSeconds(259200),3,2,failureAt);
        StripeBillingSnapshotMapper.OwnedMethodCandidate candidate;
        final RequestOptions options=RequestOptions.builder().setMaxNetworkRetries(0).setReadTimeout(1234).setConnectTimeout(1234).build();
        int executions;
        final StripeBillingSnapshotMapper.ReadExecutor executor=new StripeBillingSnapshotMapper.ReadExecutor(){
            public <T>T execute(StripeBillingSnapshotMapper.Call<T> call){executions++;try{return call.run();}catch(com.stripe.exception.StripeException e){throw new IllegalStateException(e);}}
            public RequestOptions options(){return options;}
        };
        Graph(){
            var s=f.local;
            f.local=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),
                Subscription.Status.PAST_DUE,s.providerReference(),failure.period(),s.paidThrough(),null,null,null,
                new Subscription.Debt(failure.billId(),failure.period(),failure.graceDeadline()),null,3,s.createdAt(),failureAt,failureAt);
            UUID id=new UUID(0,40);String key="subscription:"+f.f.account.id().value()+":"+id+":SETUP";
            var o=new SubscriptionOperation(id,s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Status.PROCESSING,
                "authority","digest","setup-request",key,3,1,"sub_owned",null,f.utc.plusSeconds(11),f.utc.plusSeconds(15),1);
            var setup=new SubscriptionBillingProofStore.Setup(id,s.id(),f.f.account.id(),new UUID(0,41),"seti_saved","pm_saved");
            // Literal existing contract encoding; expectations below never call mapper helpers.
            String sf=StripeRequestFingerprint.of(f.f.digest,f.f.account,"SETUP_INTENT_CREATE_V1",id.toString(),key+":SETUP",o.fingerprint(),"1",
                s.id().toString(),s.siteId().value().toString(),s.customerId().value().toString(),"cus_owned","off_session","card","consent:true");
            String mf=StripeRequestFingerprint.of(f.f.digest,f.f.account,"SUBSCRIPTION_METHOD_V1",id.toString(),key+":METHOD",o.fingerprint(),
                "sub_owned","cus_owned",setup.step().toString(),"seti_saved","pm_saved","consent:true");
            var step=new ProviderOperation(setup.step(),f.f.account.id(),f.f.account.externalAccountId(),f.f.account.credentialsRef(),
                ProviderOperation.Kind.SETUP_INTENT_CREATE,id,key+":SETUP",sf,ProviderOperation.Status.ACKNOWLEDGED,"seti_saved",
                f.utc.plusSeconds(12),f.utc.plusSeconds(100),null,null,2);
            var method=new ProviderOperation(new UUID(0,42),step.accountId(),step.externalAccountId(),step.credentialsRef(),ProviderOperation.Kind.SUBSCRIPTION_UPDATE,
                id,key+":METHOD",mf,ProviderOperation.Status.ACKNOWLEDGED,"sub_owned",f.utc.plusSeconds(14),f.utc.plusSeconds(100),null,null,2);
            candidate=new StripeBillingSnapshotMapper.OwnedMethodCandidate(o,setup,step,method);
        }
        void queue(){
            graph(f);replace(f,1,j->j.addProperty("default_payment_method","pm_saved"));
            var setup=StripeBillingOwnedSubscriptionFixture.json("{\"id\":\"seti_saved\",\"object\":\"setup_intent\",\"livemode\":false,\"customer\":\"cus_owned\",\"usage\":\"off_session\",\"payment_method_types\":[\"card\"],\"status\":\"succeeded\",\"payment_method\":\"pm_saved\",\"client_secret\":\"do_not_return\"}");
            setup.addProperty("created",f.utc.plusSeconds(13).getEpochSecond());
            setup.add("metadata",StripeBillingOwnedSubscriptionFixture.json("{\"operation_id\":\""+candidate.operation().id()+"\",\"subscription_id\":\""+f.local.id()+"\"}"));
            f.f.http.reply(setup.toString());
            f.f.http.reply("{\"id\":\"pm_saved\",\"object\":\"payment_method\",\"livemode\":false,\"customer\":\"cus_owned\",\"type\":\"card\",\"card\":{\"last4\":\"4242\"}}");
        }
        StripeBillingSnapshotMapper.OwnedMethodRead read(){return read(at,executor);}
        StripeBillingSnapshotMapper.OwnedMethodRead read(Instant at,StripeBillingSnapshotMapper.ReadExecutor executor){
            return StripeBillingSnapshotMapper.retrieveOwnedMethodReady(f.f.clients.create(f.f.credentials.resolve(f.f.account)),f.ordinary(),admission,failure,candidate,f.f.digest,at,executor);
        }
    }
    @Test void savedCardDescribesUnchangedDebtWithoutCompletingThePendingSetup(){
        var g=new Graph();g.queue();var local=g.f.local;var result=g.read();var s=result.snapshot();
        assertThat(result.setupIntent()).isEqualTo("seti_saved");assertThat(result.method()).isEqualTo("pm_saved");
        assertThat(s.status()).isEqualTo(Subscription.Status.PAST_DUE);assertThat(s.period()).isEqualTo(g.failure.period());
        assertThat(s.paymentMethodReady()).isTrue();assertThat(s.invoices()).hasSize(2);
        assertThat(s.invoices().getLast().reference()).isEqualTo("in_renewal");assertThat(s.invoices().getLast().settlement().name()).isEqualTo("OPEN");
        assertThat(s.invoices().getLast().cashObjects()).isEmpty();assertThat(s.invoices().getFirst().cashObjects()).hasSize(1);
        assertThat(s.operationKey()).isNull();assertThat(s.operationObservation()).isNull();assertThat(s.recoveryObservations()).isEmpty();assertThat(s.providerRevision()).isZero();
        assertThat(g.f.local).isSameAs(local);assertThat(g.candidate.operation().status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        assertThat(result.toString()).doesNotContain("do_not_return","4242","setup-request");
        assertThat(g.f.f.http.replies).isEmpty();assertThat(g.executions).isEqualTo(15);
        assertThat(g.f.f.http.calls).allSatisfy(r->{assertThat(r.method().name()).isEqualTo("GET");
            assertThat(r.options().getMaxNetworkRetries()).isZero();assertThat(r.options().getReadTimeout()).isEqualTo(1234);assertThat(r.options().getConnectTimeout()).isEqualTo(1234);
            if(r.params()!=null&&r.params().containsKey("limit"))assertThat(r.params().get("limit").toString()).isEqualTo("10");});
        assertThat(g.f.f.http.calls.stream().map(r->r.url().getPath()).toList()).containsExactly(
            "/v1/customers/cus_owned","/v1/subscriptions/sub_owned","/v1/invoices","/v1/invoices/in_owned/lines","/v1/invoice_payments",
            "/v1/payment_intents/pi_owned","/v1/charges/ch_owned","/v1/invoice_payments","/v1/invoices/in_renewal/lines","/v1/invoice_payments",
            "/v1/payment_intents/pi_renewal","/v1/charges/ch_renewal","/v1/invoice_payments","/v1/setup_intents/seti_saved","/v1/payment_methods/pm_saved");
    }
    @ParameterizedTest @ValueSource(strings={"admission","invoice","period","revision","bill","grace","paid-through","status","executor","observation","created","operation-status","operation-kind","operation-key","operation-reference","operation-policy","operation-revision","operation-customer","operation-site","operation-subscription","setup-operation","setup-subscription","setup-account","setup-step","setup-reference","setup-method","setup-unknown","method-dispatching","step-account","step-credentials","step-external","step-resource","step-kind","step-key","step-reference","step-fingerprint","method-fingerprint","step-dispatch","step-window","method-order","method-future","operation-updated"})
    void inconsistentSuppliedRecordsRefuseBeforeSdkIo(String mutation){
        var g=new Graph();var c=g.candidate;var o=c.operation();var p=c.setup();var step=c.setupStep();var method=c.methodStep();var f=g.failure;
        if(Set.of("admission","invoice","period","revision","bill","grace").contains(mutation))g.failure=new OwnedFailedRenewal.Failure(
            mutation.equals("admission")?new UUID(0,99):f.admissionId(),f.claimId(),mutation.equals("bill")?new UUID(0,99):f.billId(),f.billVersion(),
            mutation.equals("invoice")?"in_other":f.invoice(),f.allocation(),f.paymentIntent(),mutation.equals("period")?new Subscription.Period(f.period().start().plusSeconds(1),f.period().end()):f.period(),
            mutation.equals("grace")?f.graceDeadline().plusSeconds(1):f.graceDeadline(),mutation.equals("revision")?4:f.revision(),f.observationSequence(),f.recordedAt());
        if(mutation.startsWith("operation-")||mutation.equals("created"))o=new SubscriptionOperation(o.id(),mutation.equals("operation-site")?new dev.genericpayments.platform.sites.domain.SiteId(new UUID(0,99)):o.siteId(),
            mutation.equals("operation-customer")?new dev.genericpayments.platform.customers.domain.CustomerId(new UUID(0,99)):o.customerId(),mutation.equals("operation-subscription")?new UUID(0,99):o.subscriptionId(),
            mutation.equals("operation-kind")?SubscriptionOperation.Kind.RECOVER:o.kind(),mutation.equals("operation-status")?SubscriptionOperation.Status.SUCCEEDED:o.status(),o.authorityScope(),o.keyDigest(),o.fingerprint(),
            mutation.equals("operation-key")?"foreign":o.providerOperationKey(),mutation.equals("operation-revision")?4:o.expectedRevision(),mutation.equals("operation-policy")?2:o.policyVersion(),
            mutation.equals("operation-reference")?"sub_other":o.providerReference(),null,mutation.equals("created")?f.recordedAt().minusSeconds(1):o.createdAt(),mutation.equals("operation-updated")?g.at.plusSeconds(1):o.updatedAt(),o.version());
        if(mutation.startsWith("setup-")&&!mutation.equals("setup-unknown"))p=new SubscriptionBillingProofStore.Setup(
            mutation.equals("setup-operation")?new UUID(0,99):p.operation(),mutation.equals("setup-subscription")?new UUID(0,99):p.subscription(),
            mutation.equals("setup-account")?new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(new UUID(0,99)):p.account(),mutation.equals("setup-step")?new UUID(0,99):p.step(),
            mutation.equals("setup-reference")?"seti_other":p.reference(),mutation.equals("setup-method")?"pm_other":p.method());
        if(mutation.startsWith("step-")||mutation.equals("setup-unknown"))step=new ProviderOperation(step.id(),mutation.equals("step-account")?new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(new UUID(0,99)):step.accountId(),
            mutation.equals("step-external")?"acct_other":step.externalAccountId(),mutation.equals("step-credentials")?"other":step.credentialsRef(),mutation.equals("step-kind")?ProviderOperation.Kind.INVOICE_PAY:step.kind(),
            mutation.equals("step-resource")?new UUID(0,99):step.resourceId(),mutation.equals("step-key")?"foreign":step.operationKey(),mutation.equals("step-fingerprint")?"wrong":step.fingerprint(),
            mutation.equals("setup-unknown")?ProviderOperation.Status.UNKNOWN:step.status(),mutation.equals("step-reference")?"seti_other":step.providerReference(),
            mutation.equals("step-dispatch")?f.recordedAt():step.firstDispatchedAt(),mutation.equals("step-window")?step.firstDispatchedAt():step.retryBefore(),null,null,step.version());
        if(mutation.startsWith("method-"))method=new ProviderOperation(method.id(),method.accountId(),method.externalAccountId(),method.credentialsRef(),method.kind(),method.resourceId(),method.operationKey(),
            mutation.equals("method-fingerprint")?"wrong":method.fingerprint(),mutation.equals("method-dispatching")?ProviderOperation.Status.DISPATCHING:method.status(),method.providerReference(),
            mutation.equals("method-order")?step.firstDispatchedAt().minusSeconds(1):mutation.equals("method-future")?g.at.plusSeconds(1):method.firstDispatchedAt(),method.retryBefore(),null,null,method.version());
        g.candidate=new StripeBillingSnapshotMapper.OwnedMethodCandidate(o,p,step,method);
        if(Set.of("paid-through","status").contains(mutation)){var s=g.f.local;g.f.local=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),
            mutation.equals("status")?Subscription.Status.ACTIVE:s.billingStatus(),s.providerReference(),s.currentPeriod(),mutation.equals("paid-through")?s.paidThrough().plusSeconds(1):s.paidThrough(),null,null,null,s.debt(),null,s.revision(),s.createdAt(),s.updatedAt(),s.lastReconciledAt());}
        assertMismatch(()->g.read(mutation.equals("observation")?f.recordedAt().minusSeconds(1):g.at,mutation.equals("executor")?null:g.executor));assertThat(g.f.f.http.calls).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"default","setup-status","setup-customer","setup-method","setup-live","setup-object","setup-usage","setup-types","setup-metadata","setup-window","setup-future","method-customer","method-type","method-live","method-card","method-id","allocation","intent","new-cash","tax"})
    void remoteDriftNeverDescribesMethodReadiness(String mutation){
        var g=new Graph();g.queue();int index=switch(mutation){case "default"->1;case "allocation"->9;case "intent","new-cash"->10;case "tax"->2;default->mutation.startsWith("setup-")?13:14;};
        replace(g.f,index,j->{var x=first(j);switch(mutation){
            case "default"->x.addProperty("default_payment_method","pm_other");case "allocation"->x.addProperty("id","inpay_other");case "intent"->x.addProperty("id","pi_other");case "new-cash"->x.addProperty("amount_received",1000);
            case "tax"->j.getAsJsonArray("data").get(1).getAsJsonObject().add("total_taxes",com.google.gson.JsonParser.parseString("[{\"amount\":5}]"));
            case "setup-status"->x.addProperty("status","processing");case "setup-customer","method-customer"->x.addProperty("customer","cus_other");case "setup-method"->x.addProperty("payment_method","pm_other");
            case "setup-live","method-live"->x.addProperty("livemode",true);case "setup-object"->x.addProperty("object","other");case "setup-usage"->x.addProperty("usage","on_session");
            case "setup-types"->x.add("payment_method_types",com.google.gson.JsonParser.parseString("[\"card\",\"link\"]"));case "setup-metadata"->x.getAsJsonObject("metadata").addProperty("operation_id",new UUID(0,99).toString());
            case "setup-window"->x.addProperty("created",g.f.utc.getEpochSecond());case "setup-future"->x.addProperty("created",g.at.plusSeconds(1).getEpochSecond());
            case "method-type"->x.addProperty("type","link");case "method-card"->x.remove("card");case "method-id"->x.addProperty("id","pm_other");default->throw new AssertionError(mutation);
        }});assertMismatch(g::read);assertThat(g.f.f.http.calls).allSatisfy(r->assertThat(r.method().name()).isEqualTo("GET"));
    }
    @Test void sharedReadRejectsAnEleventhInvoicePageBeforeReadingSetup(){
        var g=new Graph();g.queue();var replies=new ArrayList<>(g.f.f.http.replies);g.f.f.http.replies.clear();g.f.f.http.replies.add(replies.get(0));g.f.f.http.replies.add(replies.get(1));
        for(int i=0;i<11;i++){var page=StripeBillingOwnedSubscriptionFixture.json("{\"object\":\"list\",\"has_more\":true,\"data\":[{\"id\":\"in_page"+i+"\",\"object\":\"invoice\"}]}");g.f.f.http.reply(page.toString());}
        assertMismatch(g::read);assertThat(g.executions).isEqualTo(12);assertThat(g.f.f.http.calls).noneSatisfy(r->assertThat(r.url().getPath()).contains("setup_intents"));
    }
    static void assertMismatch(org.assertj.core.api.ThrowableAssert.ThrowingCallable call){assertThatThrownBy(call).isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,e->{
        assertThat(e.code()).isEqualTo("PROVIDER_EVIDENCE_MISMATCH");assertThat(e.status()).isEqualTo(409);assertThat(e.getMessage()).isEqualTo("Provider billing evidence does not match");});}
}
