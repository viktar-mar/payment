package dev.genericpayments.platform.provider.adapter.out.stripe;

import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.support.*;
import java.time.*;
import java.util.*;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Audited standalone tests: only value objects and an inert official SDK HttpClient. */
class StripeBillingOwnedConfirmationSdkTest {
    @Test void explicitOwnedActionAcceptsOpaqueCreatedAndSeparatedClockWhileOrdinaryRefuses() throws Exception {
        var f=new StripeBillingOwnedSubscriptionFixture();
        f.graph();
        assertThatThrownBy(()->StripeBillingSnapshotMapper.retrieve(f.f.clients.create(null),f.ordinary(),f.utc)).isInstanceOf(RuntimeException.class);
        f.f.http.replies.clear();openGraph(f);
        var entry=java.util.Arrays.stream(StripeBillingSnapshotMapper.class.getMethods()).filter(m->m.getName().equals("retrieveOwnedPaymentAction")).findFirst();
        assertThat(entry).as("explicit typed owned payment action entry").isPresent();
        var action=(StripeBillingSnapshotMapper.PaymentAction)entry.orElseThrow().invoke(null,f.f.clients.create(null),f.ordinary(),new StripeBillingSnapshotMapper.OwnedInitial(f.receipt()),f.utc);
        assertThat(action.binding().schema()).isEqualTo("owned-initial-v1");
        assertThat(action.binding().ownedIntent()).isEqualTo(f.intent.id());
        assertThat(action.binding().proof()).isEqualTo(OwnedInitialConfirmationProof.hash(f.receipt(),"in_owned","inpay_owned","pi_owned"));
        assertThat(action.clientSecret()).isEqualTo("pi_owned_secret_INERT");
        assertThat(action.toString()).doesNotContain(action.clientSecret());
    }
    @Test void ownedActionIssuesOneUtcRootAndRefusesReservedReissue() throws Exception {
        var h=new Harness();h.graph();
        var type=type("StripeBillingClockActionAdapter");
        var adapter=type.getConstructors()[0].newInstance(h.receipt,h.phases,h.roots,h.tokens,h.f.f.credentials,h.f.f.clients,h.f.f.verification,h.clock);
        var next=type.getMethod("nextAction",SubscriptionConfirmationUseCase.Scope.class);
        var action=(SubscriptionProviderModels.NextAction)next.invoke(adapter,h.scope);
        assertThat(action.secret()).startsWith("bga1_");assertThat(action.expiresAt()).isEqualTo(h.now.plusSeconds(600));assertThat(h.inserts).isEqualTo(1);
        h.reserve(false);int reads=h.f.f.http.calls.size();
        assertThat(next.invoke(adapter,h.scope)).isNull();assertThat(h.inserts).isEqualTo(1);assertThat(h.f.f.http.calls).hasSize(reads);
    }
    @Test void persistedOriginalConsumedAttemptConfirmsOnceWithStrictSdkRequest()throws Exception{
        var h=new Harness();h.root();h.reserve(true);h.graph();h.f.f.http.reply(h.success());h.f.f.http.reply(h.f.f.clockJson());
        var type=type("StripeBillingClockConfirmationAdapter");
        var provider=(SubscriptionConfirmationProvider)type.getConstructors()[0].newInstance(h.receipt,h.scope,h.phases,h.roots,h.confirmations,h.tokens,h.f.f.credentials,h.f.f.clients,h.f.f.verification,h.clock);
        assertThat(provider.confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT").state()).isEqualTo(SubscriptionConfirmationAttempt.State.OBSERVE);
        var posts=h.f.f.http.calls.stream().filter(c->c.method().name().equals("POST")).toList();assertThat(posts).hasSize(1);
        assertThat(posts.getFirst().url().getPath()).isEqualTo("/v1/payment_intents/pi_owned/confirm");
        assertThat(posts.getFirst().params()).containsOnlyKeys("confirmation_token","use_stripe_sdk").containsEntry("confirmation_token","ctoken_INERT").containsEntry("use_stripe_sdk",true);
        assertThat(posts.getFirst().headers().map().get("Idempotency-Key")).containsExactly(h.attempt.providerKey());
        assertThat(posts.getFirst().headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");assertThat(posts.getFirst().options().getMaxNetworkRetries()).isZero();
    }
    @Test void actualServiceClaimDispatchesOnceAndSameRequestReplayNeverPostsAgain(){
        var h=new Harness();h.root();var provider=h.provider();
        var ports=StripeBillingInertSdkFixture.port(org.springframework.beans.factory.ObjectProvider.class,c->provider);
        var service=new SubscriptionConfirmationService(h.phases,h.roots,h.confirmations,ports,null,h.clock,h.receipt);
        h.graph();var selected=service.select(h.scope,h.tokens.token("v1",h.root.descriptor()));
        h.graph();h.graph();h.f.f.http.reply(h.success());h.f.f.http.reply(h.f.f.clockJson());
        var confirmed=service.confirm(h.scope,selected.attemptId(),selected.capability(),"ctoken_INERT");
        assertThat(confirmed.status().state()).isEqualTo(SubscriptionConfirmationAttempt.State.OBSERVE);
        assertThat(service.confirm(h.scope,selected.attemptId(),selected.capability(),"ctoken_INERT").status().state()).isEqualTo(SubscriptionConfirmationAttempt.State.OBSERVE);
        assertThat(h.f.f.http.calls.stream().filter(c->c.method().name().equals("POST"))).hasSize(1);
        assertThatThrownBy(()->service.retry(h.scope,selected.attemptId())).isInstanceOf(RuntimeException.class);
    }
    @ParameterizedTest @ValueSource(strings={"object","id","customer","livemode","amount","currency","received","capture","confirmation","cards","next","missingAction","missingAmount","missingConfirmation","missingStatus","nullReceived","stringAmount","decimalAmount","stringMode","processing","canceled","requires_confirmation","requires_capture","requires_payment_method","unknown","sdkMissing","sdkEmpty","sdkRedirect","sdkReceived","sdkMissingType","disconnect","402","500","malformed"})
    void uncertainForeignOrMalformedResponseNeverGrantsObservationOrRetry(String bad){
        var h=new Harness();h.root();h.reserve(true);h.graph();var r=StripeBillingOwnedSubscriptionFixture.json(h.success());
        switch(bad){
            case "object"->r.addProperty("object","charge");case "id"->r.addProperty("id","pi_foreign");case "customer"->r.addProperty("customer","cus_foreign");case "livemode"->r.addProperty("livemode",true);
            case "amount"->r.addProperty("amount",999);case "currency"->r.addProperty("currency","usd");case "received"->r.addProperty("amount_received",999);
            case "capture"->r.addProperty("capture_method","manual");case "confirmation"->r.addProperty("confirmation_method","manual");case "cards"->r.add("payment_method_types",com.google.gson.JsonParser.parseString("[\"card\",\"link\"]"));
            case "next"->r.add("next_action",StripeBillingOwnedSubscriptionFixture.json("{\"type\":\"use_stripe_sdk\",\"use_stripe_sdk\":{\"opaque\":true}}"));
            case "missingAction"->r.remove("next_action");case "missingAmount"->r.remove("amount");case "missingConfirmation"->r.remove("confirmation_method");case "missingStatus"->r.remove("status");case "nullReceived"->r.add("amount_received",com.google.gson.JsonNull.INSTANCE);
            case "stringAmount"->r.addProperty("amount","1000");case "decimalAmount"->r.addProperty("amount",1000.5);case "stringMode"->r.addProperty("livemode","false");
            case "sdkMissing","sdkEmpty","sdkRedirect","sdkReceived","sdkMissingType"->{r.addProperty("status","requires_action");r.addProperty("amount_received",bad.equals("sdkReceived")?1000:0);var a=StripeBillingOwnedSubscriptionFixture.json("{\"type\":\"use_stripe_sdk\",\"use_stripe_sdk\":{\"opaque\":true}}");if(bad.equals("sdkMissing"))a.remove("use_stripe_sdk");if(bad.equals("sdkEmpty"))a.add("use_stripe_sdk",StripeBillingOwnedSubscriptionFixture.json("{}"));if(bad.equals("sdkRedirect"))a.addProperty("type","redirect_to_url");if(bad.equals("sdkMissingType"))a.remove("type");r.add("next_action",a);}
            case "disconnect","402","500","malformed"->{}default->r.addProperty("status",bad);
        }
        switch(bad){case "disconnect"->h.f.f.http.disconnect();case "402"->h.f.f.http.error(402);case "500"->h.f.f.http.error(500);case "malformed"->h.f.f.http.reply("{");default->h.f.f.http.reply(r.toString());}
        h.f.f.http.reply(h.f.f.clockJson());var result=h.provider().confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT");
        assertThat(result.state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(result.receipt()).isNull();
        assertThat(h.f.f.http.calls.stream().filter(c->c.method().name().equals("POST"))).hasSize(1);
        assertThat(result.toString()).doesNotContain("ctoken_INERT","secret","synthetic");
    }
    @Test void requiresActionUsesOpaqueNonemptySdkMapWithoutInspectingItsInternals(){
        var h=new Harness();h.root();h.reserve(true);h.graph();var r=StripeBillingOwnedSubscriptionFixture.json(h.success());r.addProperty("status","requires_action");r.addProperty("amount_received",0);
        r.add("next_action",StripeBillingOwnedSubscriptionFixture.json("{\"type\":\"use_stripe_sdk\",\"use_stripe_sdk\":{\"future_sdk_field\":{\"anything\":[null,42,true]}}}"));h.f.f.http.reply(r.toString());h.f.f.http.reply(h.f.f.clockJson());
        var result=h.provider().confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT");assertThat(result.state()).isEqualTo(SubscriptionConfirmationAttempt.State.REQUIRES_ACTION);assertThat(result.receipt()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"customerClock","subscriptionClock","invoiceClock","created","item","periodStart","periodEnd","customer","allocationInvoice","allocationDefault","allocationPaid","allocationRequested","allocationMissingPaid","allocationCanceled","allocationSecond","reverse","piId","piCustomer","piAmount","piCurrency","piLive","piObject","piReceived","piConfirmation","piCapture","piStatus","piCards","piLatest","secretMissing","secretForeign","secretOversize","invoiceSecret","invoicePaid","invoiceSecond"})
    void originalGraphSubstitutionNeverReleasesOwnedSecret(String bad){
        var f=new StripeBillingOwnedSubscriptionFixture();prepareOpen(f);
        switch(bad){
            case "customerClock"->f.customer.addProperty("test_clock","clock_other");case "subscriptionClock"->f.remote.addProperty("test_clock","clock_other");case "invoiceClock"->f.invoice.addProperty("test_clock","clock_other");
            case "created"->f.remote.addProperty("created",43);case "item"->f.item().addProperty("id","si_other");case "periodStart"->f.item().addProperty("current_period_start",1699999901);case "periodEnd"->f.item().addProperty("current_period_end",1702678401);case "customer"->f.remote.addProperty("customer","cus_other");
            case "allocationInvoice"->f.allocation.addProperty("invoice","in_other");case "allocationDefault"->f.allocation.addProperty("is_default",false);case "allocationPaid"->f.allocation.addProperty("amount_paid",1);case "allocationRequested"->f.allocation.addProperty("amount_requested",999);case "allocationMissingPaid"->f.allocation.remove("amount_paid");case "allocationCanceled"->f.allocation.addProperty("status","canceled");
            case "piId"->f.pi.addProperty("id","pi_other");case "piCustomer"->f.pi.addProperty("customer","cus_other");case "piAmount"->f.pi.addProperty("amount",999);case "piCurrency"->f.pi.addProperty("currency","usd");case "piLive"->f.pi.addProperty("livemode",true);case "piObject"->f.pi.addProperty("object","charge");case "piReceived"->f.pi.addProperty("amount_received",1);case "piConfirmation"->f.pi.addProperty("confirmation_method","manual");case "piCapture"->f.pi.addProperty("capture_method","manual");case "piStatus"->f.pi.addProperty("status","requires_action");case "piCards"->f.pi.add("payment_method_types",com.google.gson.JsonParser.parseString("[\"card\",\"link\"]"));
            case "piLatest"->f.pi.addProperty("latest_charge","ch_owned");case "secretMissing"->f.pi.remove("client_secret");case "secretForeign"->f.pi.addProperty("client_secret","pi_other_secret_CANARY");case "secretOversize"->f.pi.addProperty("client_secret","pi_owned_secret_"+"x".repeat(497));case "invoiceSecret"->f.invoice.add("confirmation_secret",StripeBillingOwnedSubscriptionFixture.json("{\"type\":\"payment_intent\",\"client_secret\":\"pi_owned_secret_OTHER\"}"));case "invoicePaid"->f.invoice.addProperty("status","paid");default->{}
        }
        var second=f.allocation.deepCopy();second.addProperty("id","inpay_second");var invoice2=f.invoice.deepCopy();invoice2.addProperty("id","in_second");
        f.f.http.reply(f.customer.toString());f.f.http.reply(f.remote.toString());f.f.http.reply(bad.equals("invoiceSecond")?StripeBillingOwnedSubscriptionFixture.page(f.invoice,invoice2):StripeBillingOwnedSubscriptionFixture.page(f.invoice));
        f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(f.line));f.f.http.reply(bad.equals("allocationSecond")?StripeBillingOwnedSubscriptionFixture.page(f.allocation,second):StripeBillingOwnedSubscriptionFixture.page(f.allocation));f.f.http.reply(f.pi.toString());f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(bad.equals("reverse")?second:f.allocation));
        assertThatThrownBy(()->StripeBillingSnapshotMapper.retrieveOwnedPaymentAction(f.f.clients.create(null),f.ordinary(),new StripeBillingSnapshotMapper.OwnedInitial(f.receipt()),f.utc)).isInstanceOf(RuntimeException.class).hasMessageNotContaining("secret_CANARY");
        if(bad.equals("piLatest"))assertThat(f.f.http.calls.stream().map(c->c.url().getPath())).noneMatch(path->path.startsWith("/v1/charges"));
    }
    @ParameterizedTest @ValueSource(strings={"before","afterClock","afterGraph","afterPost","afterPostClock"})
    void revokedSessionOrPostIoPolicyDriftNeverPublishesOutcome(String when){
        var h=new Harness();h.root();h.reserve(true);h.graph();h.f.f.http.reply(h.success());h.f.f.http.reply(h.f.f.clockJson());
        if(when.equals("before"))h.revoked=true;else after(h,when.equals("afterClock")?1:when.equals("afterGraph")?9:when.equals("afterPost")?10:11,()->h.revoked=true);
        var result=h.provider().confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT");assertThat(result.state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(result.receipt()).isNull();
        assertThat(h.f.f.http.calls.stream().filter(c->c.method().name().equals("POST"))).hasSize(when.startsWith("afterPost")?1:0);
    }
    @ParameterizedTest @ValueSource(strings={"unconsumed","wrongToken","wrongDigest","wrongChain","wrongSession","wrongOrigin","wrongPolicy","wrongRevision","predecessor","successor","receipt","ready","finished","forgedVersion","forgedAttempt","wrongFingerprint","expired","wrongLookup","foreignScope"})
    void missingOrSubstitutedDurableAuthorityCannotPost(String bad){
        var h=new Harness();h.root();h.reserve(!bad.equals("unconsumed"));String token="ctoken_INERT";var lookup=h.f.lookup();var a=h.attempt;var c=h.chain;
        switch(bad){
            case "wrongToken"->token="ctoken_OTHER";case "wrongDigest"->h.chain=new SubscriptionConfirmationChain(c.id(),"f".repeat(64),c.sessionId(),c.origin(),c.expiresAt(),c.policyVersion(),c.subscriptionRevision());
            case "wrongChain"->h.chain=new SubscriptionConfirmationChain(UUID.randomUUID(),c.rootDigest(),c.sessionId(),c.origin(),c.expiresAt(),c.policyVersion(),c.subscriptionRevision());
            case "wrongSession","wrongOrigin","wrongPolicy","wrongRevision","expired"->h.chain=new SubscriptionConfirmationChain(c.id(),c.rootDigest(),bad.equals("wrongSession")?UUID.randomUUID():c.sessionId(),bad.equals("wrongOrigin")?"https://other.test":c.origin(),bad.equals("expired")?h.now:c.expiresAt(),bad.equals("wrongPolicy")?2:c.policyVersion(),bad.equals("wrongRevision")?99:c.subscriptionRevision());
            case "predecessor","successor","receipt","ready","finished","forgedVersion","wrongFingerprint"->h.attempt=new SubscriptionConfirmationAttempt(a.id(),a.chain(),bad.equals("predecessor")?UUID.randomUUID():null,a.keyVersion(),a.issuedAt(),a.expiresAt(),bad.equals("ready")?SubscriptionConfirmationAttempt.State.READY:bad.equals("finished")?SubscriptionConfirmationAttempt.State.UNKNOWN:a.state(),a.confirmKey(),a.tokenDigest(),bad.equals("wrongFingerprint")?"e".repeat(64):a.fingerprint(),bad.equals("receipt")?new SubscriptionConfirmationAttempt.Receipt("req_fake","ch_fake","generic_decline"):null,bad.equals("successor")?UUID.randomUUID():null,null,bad.equals("forgedVersion")?2:a.version());
            case "wrongLookup"->lookup=new SubscriptionProviderModels.Lookup(lookup.account(),lookup.subscriptionId(),"sub_other",lookup.providerOperationKey());case "foreignScope"->h.revoked=true;default->{}
        }
        var supplied=bad.equals("forgedAttempt")?new SubscriptionConfirmationAttempt(UUID.randomUUID(),a.chain(),null,a.keyVersion(),a.issuedAt(),a.expiresAt(),a.state(),a.confirmKey(),a.tokenDigest(),a.fingerprint(),null,null,null,a.version()):h.attempt;
        assertThat(h.provider().confirm(lookup,h.root,supplied,token).state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(h.f.f.http.calls).isEmpty();
    }
    @Test void directAmbientTransactionRefusesBothIoEntriesAndActionBeforeCollaborators(){
        var h=new Harness();h.root();h.reserve(true);var provider=h.provider();int guards=h.guards;
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try{
            assertThatThrownBy(()->provider.read(h.f.lookup(),h.root,null)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->provider.confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT")).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->h.action().nextAction(h.scope)).isInstanceOf(RuntimeException.class);
            assertThat(h.guards).isEqualTo(guards);assertThat(h.f.f.verifications).isZero();assertThat(h.f.f.http.calls).isEmpty();
        }finally{org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void capabilityKeepsExistingV2BytesAndAdaptersHaveNoRegistrationOrExtraMethods(){
        var h=new Harness();h.root();h.reserve(false);var ordinary=new StripeBillingConfirmationAdapter(null,h.tokens,null,null,null,h.clock);
        assertThat(h.provider().capability(h.root,h.chain,h.attempt)).isEqualTo(ordinary.capability(h.root,h.chain,h.attempt));
        assertThat(Arrays.stream(StripeBillingClockConfirmationAdapter.class.getDeclaredMethods()).filter(m->java.lang.reflect.Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName)).containsExactlyInAnyOrder("read","confirm","capability");
        assertThat(StripeBillingClockConfirmationAdapter.class.isAnnotationPresent(org.springframework.stereotype.Component.class)).isFalse();
        assertThat(StripeBillingClockActionAdapter.class.getInterfaces()).isEmpty();
    }
    static void after(Harness h,int count,Runnable effect){h.f.f.http.afterResponse=()->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();if(count==1)effect.run();else after(h,count-1,effect);};}
    @ParameterizedTest @ValueSource(strings={"id","object","live","deleted","name","frozen","created","deletes","advancing","details"})
    void originalReadyClockCannotDriftBeforeAnyGraphOrConfirm(String bad){
        var h=new Harness();h.root();h.reserve(true);var r=StripeBillingOwnedSubscriptionFixture.json(h.f.f.clockJson());
        switch(bad){case "id"->r.addProperty("id","clock_other");case "object"->r.addProperty("object","customer");case "live"->r.addProperty("livemode",true);case "deleted"->r.addProperty("deleted",true);case "name"->r.addProperty("name","other");case "frozen"->r.addProperty("frozen_time",1700000001);case "created"->r.addProperty("created",1800000001);case "deletes"->r.addProperty("deletes_after",1801000001);case "advancing"->r.addProperty("status","advancing");case "details"->r.add("status_details",StripeBillingOwnedSubscriptionFixture.json("{\"advancing\":{\"target_frozen_time\":1700000001}}"));}
        h.f.f.http.reply(r.toString());assertThat(h.provider().confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT").state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);
        assertThat(h.f.f.http.calls).hasSize(1);assertThat(h.f.f.http.calls.getFirst().method().name()).isEqualTo("GET");
    }
    @ParameterizedTest @ValueSource(strings={"account","site","customer","subscription","operation","ownedIntent","step","operationKey","proof","subscriptionReference","customerReference","invoice","allocation","intent","ordinary","expired","issuedBeforeAck","over600","superseded","digest","session"})
    void foreignOrExpiredRootCannotReachProvider(String bad){
        var h=new Harness();h.root();h.reserve(true);var g=h.root;var b=g.binding();var other=UUID.randomUUID();
        var altered=new SubscriptionClientGrant.Binding(bad.equals("step")?other:b.stepId(),bad.equals("operationKey")?"foreign":b.operationKey(),bad.equals("proof")?"f".repeat(64):b.proof(),bad.equals("subscriptionReference")?"sub_other":b.subscriptionReference(),bad.equals("customerReference")?"cus_other":b.customerReference(),bad.equals("invoice")?"in_other":b.invoiceReference(),bad.equals("allocation")?"inpay_other":b.allocationReference(),bad.equals("intent")?"pi_other":b.intentReference(),bad.equals("ordinary")?"payment-v1":b.schema(),null,bad.equals("ordinary")?null:bad.equals("ownedIntent")?other:b.ownedIntent());
        h.root=new SubscriptionClientGrant(g.id(),bad.equals("site")?new dev.genericpayments.platform.sites.domain.SiteId(other):g.site(),bad.equals("customer")?new CustomerId(other):g.customer(),bad.equals("account")?new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(other):g.account(),bad.equals("subscription")?other:g.subscription(),bad.equals("operation")?other:g.operation(),g.keyVersion(),altered,bad.equals("digest")?"f".repeat(64):g.tokenDigest(),bad.equals("issuedBeforeAck")?h.receipt.acknowledgedAt().minusSeconds(1):g.issuedAt(),bad.equals("expired")?h.now:bad.equals("over600")?g.issuedAt().plusSeconds(601):g.expiresAt(),g.consumedAt(),bad.equals("superseded")?h.now:null,bad.equals("session")?other:null,null,null);
        assertThat(h.provider().confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT").state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(h.f.f.http.calls).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"receipt","created","item","period","owner","clock"})
    void differentExpectedReceiptNeverGainsAuthority(String bad){
        var h=new Harness();h.root();h.reserve(true);var r=h.receipt;var i=r.intent();
        if(bad.equals("owner")||bad.equals("clock")){var c=i.clock();var o=c.owner();o=new ClockOwner(bad.equals("owner")?UUID.randomUUID():o.intent(),o.run(),o.account(),o.site(),o.customer(),o.externalAccount(),o.credentialsRef(),o.apiVersion(),bad.equals("clock")?o.initialFrozenTime()+1:o.initialFrozenTime(),o.name(),o.createKey(),o.createFingerprint(),o.admittedAt());c=new ReadyClock(o,c.creationOperation(),c.reference(),bad.equals("clock")?c.frozenTime()+1:c.frozenTime(),c.providerCreated(),c.providerDeletesAfter(),c.observedAt(),c.customerFingerprint());i=new OwnedClockSubscription(i.id(),c,i.customerBinding(),i.customerCreationOperation(),i.customerReference(),i.subscription(),i.createOperation(),i.operationKey(),i.requestFingerprint(),i.expectedRevision(),i.policyVersion(),i.terms(),i.priceReference(),i.productReference(),i.fingerprint(),i.admittedAt());}
        var changed=new OwnedClockSubscriptionReceipt(i,bad.equals("receipt")?UUID.randomUUID():r.providerOperation(),r.reference(),bad.equals("created")?r.providerCreated()+1:r.providerCreated(),bad.equals("item")?"si_other":r.itemReference(),r.periodStart(),bad.equals("period")?r.periodEnd()+1:r.periodEnd(),r.acknowledgedAt());
        var provider=new StripeBillingClockConfirmationAdapter(changed,h.scope,h.phases,h.roots,h.confirmations,h.tokens,h.f.f.credentials,h.f.f.clients,h.f.f.verification,h.clock);
        assertThat(provider.confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT").state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(h.f.f.http.calls).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"reserved","consumed","expired","unknown"})
    void anyLifetimeOwnedRootPreventsNewPermission(String kind){
        var h=new Harness();h.root();if(!kind.equals("expired"))h.reserve(!kind.equals("reserved"));if(kind.equals("unknown"))h.attempt=h.attempt.finish(SubscriptionConfirmationAttempt.State.UNKNOWN,null);
        assertThat(h.action().nextAction(h.scope)).isNull();assertThat(h.inserts).isZero();assertThat(h.f.f.http.calls).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"revoke","policy","operation","subscription"})
    void issuanceRechecksActualLockedStateAfterGraphBeforeInsert(String bad){
        var h=new Harness();h.graph();after(h,9,()->{switch(bad){case "revoke"->h.revoked=true;case "policy"->h.drift=true;case "operation"->{var o=h.f.operation;h.f.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),o.status(),o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),null,o.createdAt(),o.updatedAt(),o.version()+1);}case "subscription"->{var s=h.f.local;h.f.local=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),s.debt(),s.terminalReason(),s.revision()+1,s.createdAt(),s.updatedAt(),s.lastReconciledAt());}}});
        assertThatThrownBy(()->h.action().nextAction(h.scope)).isInstanceOf(RuntimeException.class);assertThat(h.inserts).isZero();assertThat(h.root).isNull();
    }
    @Test void ordinaryMapperRetainsFixedProofAndLargerSecretWithOpaqueConfirmationMethod(){
        var f=new StripeBillingOwnedSubscriptionFixture();prepareOpen(f);f.customer.remove("test_clock");f.remote.remove("test_clock");f.invoice.remove("test_clock");f.remote.addProperty("created",f.utc.getEpochSecond());f.pi.remove("confirmation_method");f.pi.addProperty("client_secret","pi_owned_secret_"+"x".repeat(600));queueGraph(f);
        var step=new ProviderOperation(f.receipt().providerOperation(),f.f.account.id(),f.f.account.externalAccountId(),f.f.account.credentialsRef(),ProviderOperation.Kind.SUBSCRIPTION_CREATE,f.operationId,f.intent.operationKey(),f.intent.fingerprint(),ProviderOperation.Status.ACKNOWLEDGED,"sub_owned",f.utc,f.utc.plusSeconds(82800),null,null,2);
        var proof=new StripeBillingOwnership.OriginalCreate(f.f.account,StripeBillingOwnership.bound(f.local,"sub_owned"),f.operation,f.binding(),step,new StripeBillingSnapshotMapper.PublishedPrice(f.terms,"price_owned","prod_owned"));
        var action=StripeBillingSnapshotMapper.retrievePaymentAction(f.f.clients.create(null),proof,f.utc);
        assertThat(action.binding().schema()).isEqualTo("payment-v1");assertThat(action.binding().ownedIntent()).isNull();assertThat(action.binding().proof()).isEqualTo("e0cc069edcdff6e9162561ab45fe5e31370d6e7e94b540b3948efa72c249a434");assertThat(action.clientSecret()).hasSize(616);
    }
    @Test void ownedSecretAtExactSdkCeilingIsAccepted(){
        var f=new StripeBillingOwnedSubscriptionFixture();prepareOpen(f);f.pi.addProperty("client_secret","pi_owned_secret_"+"x".repeat(496));queueGraph(f);
        assertThat(StripeBillingSnapshotMapper.retrieveOwnedPaymentAction(f.f.clients.create(null),f.ordinary(),new StripeBillingSnapshotMapper.OwnedInitial(f.receipt()),f.utc).clientSecret()).hasSize(512);
    }
    @Test void concurrentServiceRequestSeesDurableClaimWithoutAnotherPost()throws Exception{
        var h=new Harness();h.root();var provider=h.provider();var ports=StripeBillingInertSdkFixture.port(org.springframework.beans.factory.ObjectProvider.class,c->provider);
        var service=new SubscriptionConfirmationService(h.phases,h.roots,h.confirmations,ports,null,h.clock,h.receipt);
        h.graph();var selected=service.select(h.scope,h.tokens.token("v1",h.root.descriptor()));h.graph();h.graph();h.f.f.http.reply(h.success());h.f.f.http.reply(h.f.f.clockJson());
        var claimed=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        after(h,10,()->{claimed.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("replay did not complete");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}});
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var first=executor.submit(()->service.confirm(h.scope,selected.attemptId(),selected.capability(),"ctoken_INERT"));
            assertThat(claimed.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try{assertThat(service.confirm(h.scope,selected.attemptId(),selected.capability(),"ctoken_INERT").status().state()).isEqualTo(SubscriptionConfirmationAttempt.State.DISPATCHING);}finally{release.countDown();}
            assertThat(first.get(5,java.util.concurrent.TimeUnit.SECONDS).status().state()).isEqualTo(SubscriptionConfirmationAttempt.State.OBSERVE);
        }
        assertThat(h.f.f.http.calls.stream().filter(c->c.method().name().equals("POST"))).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"requires_action","402","disconnect"})
    void ownedNonSuccessServiceOutcomesHaveNoNewPermissionOrRedispatch(String mode){
        var h=new Harness();h.root();var provider=h.provider();var ports=StripeBillingInertSdkFixture.port(org.springframework.beans.factory.ObjectProvider.class,c->provider);
        var service=new SubscriptionConfirmationService(h.phases,h.roots,h.confirmations,ports,null,h.clock,h.receipt);h.graph();var selected=service.select(h.scope,h.tokens.token("v1",h.root.descriptor()));h.graph();h.graph();
        if(mode.equals("402"))h.f.f.http.error(402);else if(mode.equals("disconnect"))h.f.f.http.disconnect();else{var r=StripeBillingOwnedSubscriptionFixture.json(h.success());r.addProperty("status","requires_action");r.addProperty("amount_received",0);r.add("next_action",StripeBillingOwnedSubscriptionFixture.json("{\"type\":\"use_stripe_sdk\",\"use_stripe_sdk\":{\"opaque\":true}}"));h.f.f.http.reply(r.toString());h.f.f.http.reply(h.f.f.clockJson());}
        var outcome=service.confirm(h.scope,selected.attemptId(),selected.capability(),"ctoken_INERT");assertThat(outcome.status().state()).isEqualTo(mode.equals("requires_action")?SubscriptionConfirmationAttempt.State.REQUIRES_ACTION:SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(outcome.nextActionAllowed()).isEqualTo(mode.equals("requires_action"));
        assertThat(service.confirm(h.scope,selected.attemptId(),selected.capability(),"ctoken_INERT").nextActionAllowed()).isFalse();assertThat(outcome.status().retryAllowed()).isFalse();
        assertThatThrownBy(()->service.retry(h.scope,selected.attemptId())).isInstanceOf(RuntimeException.class);assertThat(h.action().nextAction(h.scope)).isNull();
        assertThat(h.f.f.http.calls.stream().filter(c->c.method().name().equals("POST"))).hasSize(1);
    }
    static Class<?> type(String name){Class<?> type=null;try{type=Class.forName("dev.genericpayments.platform.provider.adapter.out.stripe.billing."+name);}catch(ClassNotFoundException absent){}
        assertThat(type).as("explicit original receipt bound adapter "+name).isNotNull();return type;}
    static class Harness {
        final StripeBillingOwnedSubscriptionFixture f=new StripeBillingOwnedSubscriptionFixture();
        final OwnedClockSubscriptionReceipt receipt=f.receipt();final Instant now=f.utc.plusSeconds(2);final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
        final StripeBillingGrantTokens tokens=new StripeBillingGrantTokens(true,"v1",Map.of("v1",Base64.getEncoder().encodeToString(new byte[32])));
        final CustomerSessionContext session=new CustomerSessionContext(new CustomerSessionId(new UUID(0,90)),f.f.site,f.f.customer,"https://shop.test",Set.of(CustomerSessionScope.SUBSCRIPTION_MANAGE),now.plusSeconds(1200));
        final SubscriptionConfirmationUseCase.Scope scope=new SubscriptionConfirmationUseCase.Scope(new SubscriptionCommandContext(new SubscriptionActor.Customer(session),f.f.site,f.f.customer,session.origin(),"key","test"),f.subscriptionId,f.operationId);
        SubscriptionClientGrant root;SubscriptionConfirmationChain chain;SubscriptionConfirmationAttempt attempt;boolean revoked,drift,active;int inserts,guards;
        final SubscriptionActionStore roots=StripeBillingInertSdkFixture.port(SubscriptionActionStore.class,c->switch(c.name()){
            case "current","billing"->Optional.ofNullable(root);case "serverConfirmationReserved"->chain!=null;
            case "reserveOwnedInitialBilling"->{if(!active||!receipt.equals(c.args()[1])||root!=null)throw new AssertionError("invalid owned insert");root=(SubscriptionClientGrant)c.args()[0];inserts++;yield null;}
            default->throw new AssertionError(c.name());});
        final SubscriptionConfirmationStore confirmations=StripeBillingInertSdkFixture.port(SubscriptionConfirmationStore.class,c->switch(c.name()){
            case "chain","current"->Optional.ofNullable(chain);case "attempt","latest"->attempt;
            case "reserve"->{chain=(SubscriptionConfirmationChain)c.args()[1];attempt=(SubscriptionConfirmationAttempt)c.args()[2];yield null;}
            case "save"->{if(attempt.version()!=(Long)c.args()[1])throw new AssertionError("stale attempt");attempt=(SubscriptionConfirmationAttempt)c.args()[0];if(attempt.state()==SubscriptionConfirmationAttempt.State.DISPATCHING)root=new SubscriptionClientGrant(root.id(),root.site(),root.customer(),root.account(),root.subscription(),root.operation(),root.keyVersion(),root.binding(),root.tokenDigest(),root.issuedAt(),root.expiresAt(),now,null,null,null,null);yield null;}
            default->throw new AssertionError(c.name());
});
        final SubscriptionTransactionPort tx=new SubscriptionTransactionPort(){public <T>T required(Supplier<T> work){return requiresNew(work);}public synchronized <T>T requiresNew(Supplier<T> work){if(active)throw new AssertionError("nested transaction");active=true;org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);try{return work.get();}finally{active=false;org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}}public boolean active(){return org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();}};
        final SubscriptionInteractionTransaction phases;
        Harness(){
            f.local=StripeBillingOwnership.bound(f.local,"sub_owned");var o=f.operation;f.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),o.status(),o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),"sub_owned",null,o.createdAt(),o.updatedAt(),o.version());
            var ops=StripeBillingInertSdkFixture.port(SubscriptionOperationStore.class,c->f.operation);
            var subs=StripeBillingInertSdkFixture.port(SubscriptionStore.class,c->c.name().equals("lock")?f.local:Optional.of(f.local));
            var auth=StripeBillingInertSdkFixture.port(SubscriptionAuthorityPort.class,c->{if(revoked||!scope.context().equals(c.args()[0]))throw new IllegalStateException("revoked session");return null;});
            var policy=StripeBillingInertSdkFixture.port(SubscriptionPolicyPort.class,c->new SubscriptionPolicy(f.f.site,drift?2:1,true,0,false,false,false,Set.of()));
            var accounts=StripeBillingInertSdkFixture.port(LockPaymentAccountPort.class,c->Optional.of(f.f.account));
            var owned=StripeBillingInertSdkFixture.port(StripeBillingClockSubscriptionStore.class,c->{guards++;if(!active||!c.name().equals("requireConfirmationReceipt")||!receipt.equals(c.args()[0]))throw new IllegalStateException("foreign receipt");return receipt;});
            phases=new SubscriptionInteractionTransaction(tx,ops,subs,auth,policy,accounts,roots,null,null,clock,owned);
            try{var field=SubscriptionInteractionTransaction.class.getDeclaredField("securityClock");field.setAccessible(true);field.set(phases,clock);}catch(Exception e){throw new AssertionError(e);}
        }
        StripeBillingClockActionAdapter action(){return new StripeBillingClockActionAdapter(receipt,phases,roots,tokens,f.f.credentials,f.f.clients,f.f.verification,clock);}
        StripeBillingClockConfirmationAdapter provider(){return new StripeBillingClockConfirmationAdapter(receipt,scope,phases,roots,confirmations,tokens,f.f.credentials,f.f.clients,f.f.verification,clock);}
        void graph(){f.f.http.reply(f.f.clockJson());openGraph(f);f.f.http.reply(f.f.clockJson());}
        void root(){var b=new SubscriptionClientGrant.Binding(receipt.providerOperation(),f.intent.operationKey(),OwnedInitialConfirmationProof.hash(receipt,"in_owned","inpay_owned","pi_owned"),"sub_owned","cus_owned","in_owned","inpay_owned","pi_owned","owned-initial-v1",null,f.intent.id());root=new SubscriptionClientGrant(new UUID(0,80),f.f.site,f.f.customer,f.f.account.id(),f.subscriptionId,f.operationId,"v1",b,null,now,now.plusSeconds(600),null,null,null,null,null);root=root.digest(StripeBillingGrantTokens.hash(tokens.token("v1",root.descriptor())));}
        void reserve(boolean consume){chain=new SubscriptionConfirmationChain(root.id(),root.tokenDigest(),session.sessionId().value(),session.origin(),root.expiresAt(),1,f.local.revision());attempt=SubscriptionConfirmationAttempt.ready(new UUID(0,81),root.id(),null,"v1",now,chain.expiresAt());if(consume){attempt=attempt.claim(StripeBillingGrantTokens.hash("key"),StripeBillingGrantTokens.hash("ctoken_INERT"),StripeBillingGrantTokens.hash(SubscriptionClientGrant.canonical("SERVER_CONFIRM_V2",attempt.id(),root.descriptor(),StripeBillingGrantTokens.hash("ctoken_INERT"),"use_stripe_sdk=true")));root=new SubscriptionClientGrant(root.id(),root.site(),root.customer(),root.account(),root.subscription(),root.operation(),root.keyVersion(),root.binding(),root.tokenDigest(),root.issuedAt(),root.expiresAt(),now,null,null,null,null);}}
        String success(){return "{\"id\":\"pi_owned\",\"object\":\"payment_intent\",\"livemode\":false,\"customer\":\"cus_owned\",\"amount\":1000,\"currency\":\"eur\",\"amount_received\":1000,\"status\":\"succeeded\",\"capture_method\":\"automatic\",\"confirmation_method\":\"automatic\",\"payment_method_types\":[\"card\"],\"next_action\":null}";}
    }
    static void openGraph(StripeBillingOwnedSubscriptionFixture f){prepareOpen(f);queueGraph(f);}
    static void prepareOpen(StripeBillingOwnedSubscriptionFixture f){
        f.allocation.addProperty("status","open");f.allocation.addProperty("is_default",true);f.allocation.addProperty("amount_paid",0);f.allocation.remove("status_transitions");
        f.pi.addProperty("status","requires_payment_method");f.pi.addProperty("amount_received",0);f.pi.remove("latest_charge");f.pi.addProperty("confirmation_method","automatic");f.pi.addProperty("client_secret","pi_owned_secret_INERT");
    }
    static void queueGraph(StripeBillingOwnedSubscriptionFixture f){
        f.f.http.reply(f.customer.toString());f.f.http.reply(f.remote.toString());f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(f.invoice));f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(f.line));f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(f.allocation));f.f.http.reply(f.pi.toString());f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(f.allocation));
    }
}
