package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.google.gson.*;
import com.stripe.net.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.service.OwnedRecoveryInertFixture;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Snapshot;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore.Setup;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedMethodMappingTest.assertMismatch;
import static org.assertj.core.api.Assertions.*;

/** The local service authenticates its own real completed lineage; only SDK GET responses are inert. */
class StripeBillingOwnedRecoveryPreparationMappingTest {
    static final List<String> GRAPH_PATHS=List.of("/v1/customers/cus_owned","/v1/subscriptions/sub_owned","/v1/invoices",
        "/v1/invoices/in_initial/lines","/v1/invoice_payments","/v1/payment_intents/pi_initial","/v1/charges/ch_owned","/v1/invoice_payments",
        "/v1/invoices/in_renewal/lines","/v1/invoice_payments","/v1/payment_intents/pi_renewal","/v1/charges/ch_renewal","/v1/invoice_payments",
        "/v1/setup_intents/seti_owned","/v1/payment_methods/pm_owned");
    static final class Graph {
        final StripeBillingInertSdkFixture sdk=new StripeBillingInertSdkFixture();
        final OwnedRecoveryInertFixture local=new OwnedRecoveryInertFixture(sdk.digest);
        RecoveryPreparationContext original=local.inspect();
        StripeBillingSnapshotMapper.OwnedMethodCandidate candidate=candidate(original);
        int executions;
        final StripeBillingSnapshotMapper.ReadExecutor executor=new StripeBillingSnapshotMapper.ReadExecutor(){
            public <T>T execute(StripeBillingSnapshotMapper.Call<T> call){executions++;try{return call.run();}catch(com.stripe.exception.StripeException e){throw new IllegalStateException(e);}}
            public RequestOptions options(){return RequestOptions.builder().setMaxNetworkRetries(0).build();}
        };
        StripeBillingSnapshotMapper.OwnedMethodCandidate candidate(RecoveryPreparationContext c){return new StripeBillingSnapshotMapper.OwnedMethodCandidate(c.setupOperation(),local.proof,local.steps.get(c.completion().setupStepId()),local.steps.get(c.completion().methodStepId()));}
        StripeBillingSnapshotMapper.Ownership owner(){var c=original;var i=c.reservation().admission().firstRenewalAdmission().receipt().intent();return new StripeBillingSnapshotMapper.Ownership(c.account(),c.subscription(),c.binding(),List.of(new StripeBillingSnapshotMapper.PublishedPrice(i.terms(),i.priceReference(),i.productReference())));}
        Snapshot read(){return StripeBillingSnapshotMapper.retrieveOwnedRecoveryPreparation(sdk.clients.create(sdk.credentials.resolve(original.account())),owner(),original,candidate,sdk.digest,local.now(),executor);}
        void queue(){
            // Reuse known complete native graph shapes, explicitly rebasing remote data to the actual
            // service fixture's identities and tiny billing epoch. No authenticated local record is replaced.
            var template=new StripeBillingOwnedMethodMappingTest.Graph();template.queue();
            for(var response:template.f.f.http.replies){var body=((StripeResponse)response).body();
                body=body.replace("inpay_owned","inpay_initial").replace("in_owned","in_initial").replace("pi_owned","pi_initial").replace("seti_saved","seti_owned").replace("pm_saved","pm_owned");
                var json=JsonParser.parseString(body);rebaseTimes(json);sdk.http.reply(json.toString());
            }
            change(0,j->{j.addProperty("created",100);var m=j.getAsJsonObject("metadata");m.addProperty("account_id",original.account().id().value().toString());m.addProperty("site_id",original.subscription().siteId().value().toString());m.addProperty("customer_id",original.subscription().customerId().value().toString());});
            change(1,j->{var i=original.reservation().admission().firstRenewalAdmission().receipt().intent();var m=j.getAsJsonObject("metadata");m.addProperty("account_id",original.account().id().value().toString());m.addProperty("site_id",original.subscription().siteId().value().toString());m.addProperty("customer_id",original.subscription().customerId().value().toString());m.addProperty("subscription_id",original.subscription().id().toString());m.addProperty("operation_id",i.createOperation().toString());m.addProperty("request_fingerprint",i.requestFingerprint());});
            change(13,j->{j.addProperty("created",candidate.setupStep().firstDispatchedAt().getEpochSecond());j.getAsJsonObject("metadata").addProperty("operation_id",original.setupOperation().id().toString());j.getAsJsonObject("metadata").addProperty("subscription_id",original.subscription().id().toString());});
        }
        void queueClocks(){queue();var list=new ArrayList<>(sdk.http.replies);sdk.http.replies.clear();sdk.http.reply(clockBody());sdk.http.replies.addAll(list);sdk.http.reply(clockBody());}
        String clockBody(){var c=original.reservation().admission().ready().observation();return "{\"id\":\""+c.reference()+"\",\"object\":\"test_helpers.test_clock\",\"livemode\":false,\"name\":\""+c.name()+"\",\"frozen_time\":"+c.frozenTime()+",\"created\":"+c.created()+",\"deletes_after\":"+c.deletesAfter()+",\"status\":\"ready\",\"status_details\":{}}";}
        void change(int index,Consumer<JsonObject> action){var list=new ArrayList<>(sdk.http.replies);var old=(StripeResponse)list.get(index);var json=JsonParser.parseString(old.body()).getAsJsonObject();action.accept(json);list.set(index,new StripeResponse(200,HttpHeaders.of(Map.of()),json.toString()));sdk.http.replies.clear();sdk.http.replies.addAll(list);}
        static void rebaseTimes(JsonElement element){
            if(element.isJsonArray()){element.getAsJsonArray().forEach(Graph::rebaseTimes);return;}
            if(!element.isJsonObject())return;var o=element.getAsJsonObject();
            for(var entry:new ArrayList<>(o.entrySet())){var v=entry.getValue();if(v.isJsonPrimitive()&&v.getAsJsonPrimitive().isNumber()){
                Long replacement=Map.of(1699999900L,900L,1702678400L,1100L,1705356800L,10000L,1700000011L,1001L,1702678411L,1111L,1600000007L,950L,1500000000L,951L,1500000001L,952L).get(v.getAsLong());if(replacement!=null)o.addProperty(entry.getKey(),replacement);
            }else rebaseTimes(v);}
        }
    }
    @Test void originalSucceededSetupAndReservedRecoveryReadTheUnchangedDebtThenOriginalCard(){
        var g=new Graph();g.queue();var state=g.local.state();var s=g.read();
        assertThat(g.original.setupOperation().status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);assertThat(g.original.setupOperation().version()).isEqualTo(2);
        assertThat(g.original.recoveryOperation().status()).isEqualTo(SubscriptionOperation.Status.RESERVED);assertThat(g.original.recoveryOperation().version()).isZero();
        assertThat(s.status()).isEqualTo(Subscription.Status.PAST_DUE);assertThat(s.paymentMethodReady()).isTrue();assertThat(s.period()).isEqualTo(g.original.reservation().failure().period());
        assertThat(s.invoices()).hasSize(2);assertThat(s.invoices().getFirst().cashObjects()).singleElement().satisfies(c->assertThat(c.reference()).isEqualTo("pi_initial"));
        assertThat(s.invoices().getLast().settlement().name()).isEqualTo("OPEN");assertThat(s.invoices().getLast().cashObjects()).isEmpty();
        assertThat(s.operationKey()).isNull();assertThat(s.operationObservation()).isNull();assertThat(s.recoveryObservations()).isEmpty();assertThat(s.providerRevision()).isZero();
        assertThat(g.sdk.http.calls.stream().map(r->r.url().getPath()).toList()).isEqualTo(GRAPH_PATHS);assertThat(g.executions).isEqualTo(15);assertThat(g.sdk.http.replies).isEmpty();assertThat(g.local.state()).isEqualTo(state);
    }
    @Test void existingMethodMapperRemainsProcessingOnly(){var g=new Graph();assertMismatch(()->StripeBillingSnapshotMapper.retrieveOwnedMethodReady(g.sdk.clients.create(g.sdk.credentials.resolve(g.original.account())),g.owner(),g.original.reservation().admission(),g.original.reservation().failure(),g.candidate,g.sdk.digest,g.local.now(),g.executor));assertThat(g.sdk.http.calls).isEmpty();}
    @ParameterizedTest @ValueSource(strings={"processing-original","final-version","completion-method","completion-step","step-version","step-account","step-fingerprint","method-reference","method-before-setup","proof-operation","recovery-allocation"})
    void mismatchedOriginalRecordsRefuseBeforeAnySdkCall(String bad){
        var g=new Graph();var c=g.original;var completion=c.completion();var o=g.candidate.operation();var proof=g.candidate.setup();var setup=g.candidate.setupStep();var method=g.candidate.methodStep();
        if(bad.equals("processing-original"))o=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),SubscriptionOperation.Status.PROCESSING,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),o.safeFailureCode(),o.createdAt(),o.updatedAt(),o.version());
        if(bad.startsWith("completion-")||bad.equals("final-version"))completion=new Completion(completion.reservationId(),completion.claimId(),completion.setupOperationId(),bad.equals("final-version")?3:completion.setupFinalVersion(),bad.equals("completion-step")?new UUID(0,999):completion.setupStepId(),completion.setupStepVersion(),completion.methodStepId(),completion.methodStepVersion(),completion.setupIntent(),bad.equals("completion-method")?"pm_other":completion.method(),completion.revision(),completion.observationSequence(),completion.completedAt());
        if(bad.startsWith("step-"))setup=new ProviderOperation(setup.id(),bad.equals("step-account")?new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(new UUID(0,999)):setup.accountId(),setup.externalAccountId(),setup.credentialsRef(),setup.kind(),setup.resourceId(),setup.operationKey(),bad.equals("step-fingerprint")?"wrong":setup.fingerprint(),setup.status(),setup.providerReference(),setup.firstDispatchedAt(),setup.retryBefore(),null,null,bad.equals("step-version")?3:setup.version());
        if(bad.startsWith("method-"))method=new ProviderOperation(method.id(),method.accountId(),method.externalAccountId(),method.credentialsRef(),method.kind(),method.resourceId(),method.operationKey(),method.fingerprint(),method.status(),bad.equals("method-reference")?"sub_other":method.providerReference(),bad.equals("method-before-setup")?setup.firstDispatchedAt().minusSeconds(1):method.firstDispatchedAt(),method.retryBefore(),null,null,method.version());
        if(bad.equals("proof-operation"))proof=new Setup(new UUID(0,999),proof.subscription(),proof.account(),proof.step(),proof.reference(),proof.method());
        var recovery=c.recovery();if(bad.equals("recovery-allocation")){var a=recovery.association();recovery=new Recovery(recovery.reservationId(),new dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.Recovery(a.admissionId(),a.operationId(),a.billId(),"inpay_other",a.paymentIntent(),a.expectedRevision(),a.operationVersion(),a.associatedAt()),recovery.observationSequence());}
        g.original=new RecoveryPreparationContext(c.reservation(),c.enrollment(),c.account(),c.subscription(),o,c.recoveryOperation(),c.binding(),completion,recovery,c.billingSample());g.candidate=new StripeBillingSnapshotMapper.OwnedMethodCandidate(o,proof,setup,method);
        assertMismatch(g::read);assertThat(g.sdk.http.calls).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"default-card","allocation","reverse","new-cash","setup-status","setup-metadata","setup-chronology","card-customer","card-type"})
    void debtOrOriginalCardDriftIsRefused(String bad){
        var g=new Graph();g.queue();int index=switch(bad){case "default-card"->1;case "allocation"->9;case "reverse"->12;case "new-cash"->10;case "card-customer","card-type"->14;default->13;};
        g.change(index,j->{var x=StripeBillingFailedRenewalMappingTest.first(j);switch(bad){case "default-card"->x.addProperty("default_payment_method","pm_other");case "allocation"->x.addProperty("id","inpay_other");case "reverse"->x.addProperty("invoice","in_other");case "new-cash"->x.addProperty("amount_received",1000);case "setup-status"->x.addProperty("status","processing");case "setup-metadata"->x.getAsJsonObject("metadata").addProperty("operation_id",new UUID(0,999).toString());case "setup-chronology"->x.addProperty("created",g.candidate.methodStep().firstDispatchedAt().plusSeconds(1).getEpochSecond());case "card-customer"->x.addProperty("customer","cus_other");case "card-type"->x.addProperty("type","link");default->throw new AssertionError(bad);}});
        assertMismatch(g::read);assertThat(g.sdk.http.calls).allSatisfy(r->assertThat(r.method().name()).isEqualTo("GET"));
    }
}
