package dev.genericpayments.platform.provider.adapter.out.stripe;

import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Standalone pure official SDK tests. All ports handwritten; transport is the audited inert HttpClient. */
class StripeBillingClockSubscriptionSdkTest {
    @Test void ownedCreateAndReadUseOwnedAuthorityWithoutLegacyAccountFirstReentry(){
        var h=new Harness();
        var clocks=StripeBillingInertSdkFixture.port(StripeBillingClockStore.class,c->{throw new AssertionError("Legacy clock entry: "+c.name());});
        var adapter=new StripeBillingClockSubscriptionAdapter(h.f.f.credentials,h.f.f.clients,h.f.f.verification,h.steps,clocks,h.owned,h.bindings,h.subscriptions,h.operations,Clock.fixed(h.f.utc.plusSeconds(2),ZoneOffset.UTC));
        h.createReplies();
        assertThat(adapter.createOwned(h.f.command(),h.f.f.owner).outcome()).isEqualTo(Outcome.ACCEPTED);
        h.f.local=StripeBillingOwnership.bound(h.f.local,"sub_owned");
        h.f.f.http.reply(h.f.f.clockJson());h.f.graph();
        assertThat(adapter.retrieveOwned(h.f.lookup(),h.f.f.owner).status()).isEqualTo(Subscription.Status.INCOMPLETE);
        assertThat(h.currentChecks).isGreaterThan(2);
    }
    // Explicit construction bypasses Spring interceptors; neither entry may touch a port under an outer transaction.
    @ParameterizedTest @ValueSource(strings={"create","retrieve"})
    void explicitlyConstructedAdapterRefusesActualTransactionBeforeAnyDependency(String entry){
        var f=new StripeBillingOwnedSubscriptionFixture();var calls=new ArrayList<String>();
        var clients=new StripeClientFactory(){@Override public com.stripe.StripeClient create(StripeCredentials credentials){calls.add("client");throw new AssertionError("Client reached inside transaction");}};
        var steps=untouched(ProviderOperationStore.class,calls);
        var clocks=untouched(StripeBillingClockStore.class,calls);
        var owned=untouched(StripeBillingClockSubscriptionStore.class,calls);
        var bindings=untouched(StripeBillingBindingPort.class,calls);
        var subscriptions=untouched(SubscriptionStore.class,calls);
        var operations=untouched(SubscriptionOperationStore.class,calls);
        var adapter=new StripeBillingClockSubscriptionAdapter(f.f.credentials,clients,a->{calls.add("verify");throw new AssertionError("Verification reached inside transaction");},steps,clocks,owned,bindings,subscriptions,operations,Clock.fixed(f.utc,ZoneOffset.UTC));
        boolean previous=org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try{
            assertThatThrownBy(()->{if(entry.equals("create"))adapter.createOwned(f.command(),f.f.owner);else adapter.retrieveOwned(f.lookup(),f.f.owner);})
                .isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
            assertThat(calls).isEmpty();assertThat(f.f.http.calls).isEmpty();assertThat(f.f.verifications).isZero();
        }finally{
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(previous);
        }
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isEqualTo(previous);
    }
    private static <T>T untouched(Class<T> type,List<String> calls){return StripeBillingInertSdkFixture.port(type,c->{calls.add(type.getSimpleName()+"."+c.name());throw new AssertionError("Port reached inside transaction");});}
    // Missing owned dispatch/atomic receipt must fail to produce this original accepted result.
    @Test void originalClaimPostsOnceAndCapturesOpaqueReceiptThenReplaysLocally(){
        var h=new Harness();h.createReplies();
        assertThatCode(()->assertThat(h.adapter.createOwned(h.f.command(),h.f.f.owner).outcome()).isEqualTo(Outcome.ACCEPTED)).doesNotThrowAnyException();
        assertThat(h.receipt).isNotNull();assertThat(h.receipt.providerCreated()).isEqualTo(42);
        assertThat(h.receipt.periodStart()).isEqualTo(1699999900);assertThat(h.receipt.itemReference()).isEqualTo("si_owned");
        int verified=h.f.f.verifications;
        assertThat(h.adapter.createOwned(h.f.command(),h.f.f.owner).reference()).isEqualTo("sub_owned");
        assertThat(h.f.f.verifications).isEqualTo(verified);assertThat(h.f.f.http.calls).hasSize(2);
        var get=h.f.f.http.calls.getFirst();var post=h.f.f.http.calls.getLast();
        assertThat(get.method().name()).isEqualTo("GET");assertThat(get.url().getPath()).isEqualTo("/v1/test_helpers/test_clocks/clock_owned");
        assertThat(post.method().name()).isEqualTo("POST");assertThat(post.url().getPath()).isEqualTo("/v1/subscriptions");
        assertThat(post.params()).containsOnlyKeys("customer","billing_mode","collection_method","payment_behavior","payment_settings","items","metadata");
        assertThat(post.params()).containsEntry("customer","cus_owned").containsEntry("collection_method","charge_automatically").containsEntry("payment_behavior","default_incomplete");
        assertThat(post.params().get("billing_mode")).isEqualTo(Map.of("type","classic"));
        assertThat(post.params().get("payment_settings")).isEqualTo(Map.of("payment_method_types",List.of("card"),"save_default_payment_method","on_subscription"));
        assertThat(post.params().get("items")).isEqualTo(List.of(Map.of("price","price_owned","quantity",1L)));
        assertThat(post.params().get("metadata")).isEqualTo(Map.of("account_id","10000000-0000-0000-0000-000000000001","site_id","20000000-0000-0000-0000-000000000002","customer_id","30000000-0000-0000-0000-000000000003","subscription_id","00000000-0000-0000-0000-00000000000c","operation_id","00000000-0000-0000-0000-00000000000d"));
        assertThat(post.content().stringContent()).contains("payment_behavior=default_incomplete","items[0][quantity]=1");
        assertThat(post.headers().map().get("Idempotency-Key")).isEqualTo(List.of(h.f.intent.operationKey()));
        assertThat(h.f.f.http.calls).allSatisfy(r->{assertThat(r.options().getMaxNetworkRetries()).isZero();assertThat(r.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");});
        assertThat(h.f.f.stopped).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"disconnect","500","malformed","missingClock","foreignClock","missingCreated","zeroCreated","customer","itemSubscription","deletedItem","metadata","price","product","quantity","secondItem","truncated","missingPeriod","nonpositive","reversed","beforeFrozen","atPeriodEnd","active","trial","schedule","pending","cancel","pause","live","unknown"})
    void uncertainOrExcludedOriginalResponseStopsWinnerAndNeverRedispatches(String bad){
        var h=new Harness();h.f.f.http.reply(h.f.f.clockJson());
        switch(bad){
            case "disconnect"->h.f.f.http.disconnect();case "500"->h.f.f.http.error(500);case "malformed"->h.f.f.http.reply("{");
            default->{var f=h.f;switch(bad){
                case "missingClock"->f.remote.remove("test_clock");case "foreignClock"->f.remote.addProperty("test_clock","clock_other");
                case "missingCreated"->f.remote.remove("created");case "zeroCreated"->f.remote.addProperty("created",0);
                case "customer"->f.remote.addProperty("customer","cus_other");case "itemSubscription"->f.item().addProperty("subscription","sub_other");
                case "deletedItem"->f.item().addProperty("deleted",true);case "metadata"->f.remote.getAsJsonObject("metadata").addProperty("operation_id","00000000-0000-0000-0000-000000000099");
                case "price"->f.price().addProperty("id","price_other");case "product"->f.price().addProperty("product","prod_other");case "quantity"->f.item().addProperty("quantity",2);
                case "secondItem"->f.remote.getAsJsonObject("items").getAsJsonArray("data").add(f.item().deepCopy());case "truncated"->f.remote.getAsJsonObject("items").addProperty("has_more",true);
                case "missingPeriod"->f.item().remove("current_period_end");case "nonpositive"->f.item().addProperty("current_period_start",0);
                case "reversed"->{f.item().addProperty("current_period_start",1700000001);f.item().addProperty("current_period_end",1699999900);}
                case "beforeFrozen"->f.item().addProperty("current_period_start",1700000001);case "atPeriodEnd"->f.item().addProperty("current_period_end",1700000000);
                case "active","unknown"->f.remote.addProperty("status",bad);case "trial"->f.remote.addProperty("trial_start",1700000000);
                case "schedule"->f.remote.addProperty("schedule","sub_sched_other");case "pending"->f.remote.add("pending_update",StripeBillingOwnedSubscriptionFixture.json("{}"));
                case "cancel"->f.remote.addProperty("cancel_at",1702678400);case "pause"->f.remote.add("pause_collection",StripeBillingOwnedSubscriptionFixture.json("{}"));case "live"->f.remote.addProperty("livemode",true);
            }h.f.f.http.reply(f.remote.toString());}
        }
        assertThat(h.adapter.createOwned(h.f.command(),h.f.f.owner).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(h.receipt).isNull();assertThat(h.f.f.stopped).isEqualTo(StripeBillingClockStore.StopReason.SUBSCRIPTION_CREATE_FAILED);
        assertThatThrownBy(()->h.adapter.createOwned(h.f.command(),h.f.f.owner)).isInstanceOf(RuntimeException.class);assertThat(h.f.f.http.calls).hasSize(2);
    }
    @Test void frozenAtInclusiveStartIsValidWithoutCreatedEquality(){
        var h=new Harness();h.f.item().addProperty("current_period_start",1700000000);h.createReplies();
        assertThat(h.adapter.createOwned(h.f.command(),h.f.f.owner).outcome()).isEqualTo(Outcome.ACCEPTED);assertThat(h.receipt.periodStart()).isEqualTo(1700000000);
    }
    @ParameterizedTest @ValueSource(strings={"disconnect","500","malformed","foreign","created","expiry","name","frozen","advancing","target","live","deleted","unknown"})
    void nonReadyOrDriftedClockNeverPosts(String bad){
        var h=new Harness();var r=StripeBillingOwnedSubscriptionFixture.json(h.f.f.clockJson());
        switch(bad){case "disconnect"->h.f.f.http.disconnect();case "500"->h.f.f.http.error(500);case "malformed"->h.f.f.http.reply("{");default->{
            switch(bad){case "foreign"->r.addProperty("id","clock_other");case "created"->r.addProperty("created",1800000001);case "expiry"->r.addProperty("deletes_after",1801000001);
                case "name"->r.addProperty("name","other");case "frozen"->r.addProperty("frozen_time",1700000001);case "advancing","unknown"->r.addProperty("status",bad);
                case "target"->r.add("status_details",StripeBillingOwnedSubscriptionFixture.json("{\"advancing\":{\"target_frozen_time\":1700000001}}"));case "live"->r.addProperty("livemode",true);case "deleted"->r.addProperty("deleted",true);}
            h.f.f.http.reply(r.toString());}}
        assertThat(h.adapter.createOwned(h.f.command(),h.f.f.owner).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(h.f.f.http.calls).hasSize(1);assertThat(h.f.f.stopped).isNotNull();assertThat(h.receipt).isNull();
    }
    @Test void lostClaimCannotStopWinnerOrCallHttp(){
        var h=new Harness();h.claimLost=true;
        assertThat(h.adapter.createOwned(h.f.command(),h.f.f.owner).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(h.f.f.http.calls).isEmpty();assertThat(h.f.f.stopped).isNull();
    }
    @Test void duplicateDuringLiveOriginalDispatchCannotStopOrRedispatch(){
        var h=new Harness();h.createReplies();
        h.f.f.http.afterResponse=()->assertThat(h.adapter.createOwned(h.f.command(),h.f.f.owner).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(h.adapter.createOwned(h.f.command(),h.f.f.owner).outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(h.f.f.http.calls).hasSize(2);assertThat(h.f.f.stopped).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"unknown","dispatching","expired","ackWithoutReceipt"})
    void missingOriginalReceiptNeverAuthorizesRecovery(String status){
        var h=new Harness();var op=h.f.f.operations.reserve(h.f.f.account,ProviderOperation.Kind.SUBSCRIPTION_CREATE,h.f.operationId,h.f.intent.operationKey(),h.f.intent.fingerprint());
        var claim=h.f.f.operations.claim(op.id(),"original",h.f.utc.minusSeconds(100),Duration.ofSeconds(status.equals("expired")?1:600)).orElseThrow();
        if(status.equals("unknown"))h.f.f.operations.unknown(claim.id(),claim.leaseOwner(),claim.version());
        if(status.equals("ackWithoutReceipt"))h.f.f.operations.acknowledge(claim.id(),claim.leaseOwner(),claim.version(),"sub_owned");
        int claims=h.f.f.claims;
        assertThat(h.adapter.createOwned(h.f.command(),h.f.f.owner).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(h.f.f.claims).isEqualTo(claims);assertThat(h.f.f.http.calls).isEmpty();assertThat(h.f.f.stopped).isNull();
    }
    @Test void failedAtomicReceiptStopsWithoutInventingAckOrSecondPost(){
        var h=new Harness();h.ackFailure=true;h.createReplies();
        assertThat(h.adapter.createOwned(h.f.command(),h.f.f.owner).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(h.f.f.stopped).isEqualTo(StripeBillingClockStore.StopReason.SUBSCRIPTION_RECEIPT_FAILED);assertThat(h.receipt).isNull();
        assertThat(h.f.f.rows.values()).allSatisfy(r->assertThat(r.status()).isEqualTo(ProviderOperation.Status.UNKNOWN));
        assertThatThrownBy(()->h.adapter.createOwned(h.f.command(),h.f.f.owner)).isInstanceOf(RuntimeException.class);assertThat(h.f.f.http.calls).hasSize(2);
    }
    @ParameterizedTest @ValueSource(strings={"afterPreflight","afterPost","accountAfterPost"})
    void stoppedOrChangedAuthorityCannotPostOrAcknowledge(String when){
        var h=new Harness();h.createReplies();Runnable revoke=()->{if(when.equals("accountAfterPost"))h.accountDrift=true;else h.f.f.active=false;};
        h.f.f.http.afterResponse=when.equals("afterPreflight")?revoke:()->h.f.f.http.afterResponse=revoke;
        assertThat(h.adapter.createOwned(h.f.command(),h.f.f.owner).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(h.f.f.http.calls).hasSize(when.equals("afterPreflight")?1:2);assertThat(h.receipt).isNull();assertThat(h.f.f.stopped).isNotNull();
    }
    @ParameterizedTest @ValueSource(strings={"exact","omitted","null"})
    void exactInitialObservationRevalidatesReceiptAndReturnsPendingWithoutAuthority(String representation){
        var h=new Harness();h.seedReceipt();h.f.flatSubscription(representation);h.f.f.http.reply(h.f.f.clockJson());h.f.graph();int checks=h.currentChecks;
        var s=h.adapter.retrieveOwned(h.f.lookup(),h.f.f.owner);
        assertThat(s.status()).isEqualTo(Subscription.Status.INCOMPLETE);assertThat(s.operationObservation().state()).isEqualTo(OperationState.PENDING);
        assertThat(s.observedAt()).isEqualTo(h.f.utc.plusSeconds(2));assertThat(s.trialEvidence()).isNull();assertThat(s.externalWithdrawalEvidence()).isNull();assertThat(s.recoveryObservations()).isEmpty();
        assertThat(h.currentChecks).isGreaterThan(checks+2);
        assertThat(h.f.f.http.calls.stream().map(r->r.url().getPath())).containsExactly("/v1/test_helpers/test_clocks/clock_owned","/v1/customers/cus_owned","/v1/subscriptions/sub_owned","/v1/invoices","/v1/invoices/in_owned/lines","/v1/invoice_payments");
        assertThat(h.f.f.http.calls).allSatisfy(r->{assertThat(r.options().getMaxNetworkRetries()).isZero();assertThat(r.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");});
    }
    @ParameterizedTest @ValueSource(strings={"exact","omitted","null"})
    void paidOwnedObservationRetainsCapturedCashAndExactReceipt(String representation){
        var h=new Harness();h.seedReceipt();h.f.flatSubscription(representation);h.f.f.http.reply(h.f.f.clockJson());h.f.paidGraph();
        var snapshot=h.adapter.retrieveOwned(h.f.lookup(),h.f.f.owner);
        assertThat(snapshot.invoices()).singleElement().satisfies(invoice->{
            assertThat(invoice.settlement()).isEqualTo(SettlementKind.CASH);
            assertThat(invoice.cashObjects()).singleElement().satisfies(cash->{assertThat(cash.reference()).isEqualTo("pi_owned");assertThat(cash.amountMinor()).isEqualTo(1000);});
        });
        assertThat(snapshot.status()).isEqualTo(Subscription.Status.ACTIVE);assertThat(snapshot.observedAt()).isEqualTo(h.f.utc.plusSeconds(2));
        assertThat(h.f.f.http.calls).hasSize(9).allMatch(c->c.method().name().equals("GET"));
    }
    @ParameterizedTest @ValueSource(strings={"stopped","account","wrongKey","wrongReference","missingReceipt"})
    void invalidObservationAuthorityRejectsBeforeHttp(String bad){
        for(var representation:List.of("exact","omitted","null")){
            var h=new Harness();h.f.flatSubscription(representation);h.seedReceipt();var lookup=h.f.lookup();
            switch(bad){case "stopped"->h.f.f.active=false;case "account"->h.accountDrift=true;
                case "wrongKey"->lookup=new Lookup(h.f.f.account,h.f.subscriptionId,"sub_owned","subscription:other:CANCEL");
                case "wrongReference"->lookup=new Lookup(h.f.f.account,h.f.subscriptionId,"sub_other",h.f.intent.operationKey());case "missingReceipt"->h.receipt=null;}
            var selected=lookup;assertThatThrownBy(()->h.adapter.retrieveOwned(selected,h.f.f.owner)).isInstanceOf(RuntimeException.class);assertThat(h.f.f.http.calls).isEmpty();
        }
    }
    @Test void ownerRevokedAfterGraphCannotPublishObservation(){
        for(var representation:List.of("exact","omitted","null")){
            var h=new Harness();h.f.flatSubscription(representation);h.seedReceipt();h.f.f.http.reply(h.f.f.clockJson());h.f.graph();after(h,6,()->h.f.f.active=false);
            assertThatThrownBy(()->h.adapter.retrieveOwned(h.f.lookup(),h.f.f.owner)).isInstanceOf(RuntimeException.class);assertThat(h.f.f.http.calls).hasSize(6);
        }
    }
    @Test void changedReceiptAfterGraphCannotPublishObservation(){
        for(var representation:List.of("exact","omitted","null")){
            var h=new Harness();h.f.flatSubscription(representation);h.seedReceipt();h.f.f.http.reply(h.f.f.clockJson());h.f.graph();after(h,6,()->h.receipt=new OwnedClockSubscriptionReceipt(h.f.intent,h.receipt.providerOperation(),"sub_owned",43,"si_owned",1699999900,1702678400,h.receipt.acknowledgedAt()));
            assertThatThrownBy(()->h.adapter.retrieveOwned(h.f.lookup(),h.f.f.owner)).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void changedOperationVersionAfterGraphCannotPublishObservation(){
        for(var representation:List.of("exact","omitted","null")){
            var h=new Harness();h.f.flatSubscription(representation);h.seedReceipt();h.f.f.http.reply(h.f.f.clockJson());h.f.graph();after(h,6,()->{var o=h.f.operation;h.f.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),o.status(),o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),o.safeFailureCode(),o.createdAt(),o.updatedAt(),o.version()+1);});
            assertThatThrownBy(()->h.adapter.retrieveOwned(h.f.lookup(),h.f.f.owner)).isInstanceOf(RuntimeException.class);
        }
    }
    @ParameterizedTest @ValueSource(strings={"trial","consent","cancel","setup","target","invoice","quote"})
    void unsupportedCommandsHaveNoProviderCapability(String bad){
        var h=new Harness();var c=h.f.command();var o=c.operation();
        if(bad.equals("cancel")||bad.equals("setup"))o=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),bad.equals("cancel")?SubscriptionOperation.Kind.CANCEL:SubscriptionOperation.Kind.SETUP,o.status(),o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),null,null,o.createdAt(),o.updatedAt(),o.version());
        var selected=new Command(c.account(),o,c.current(),bad.equals("target")?c.current():null,null,bad.equals("invoice")?"in_owned":null,bad.equals("quote")?"quote_owned":null,!bad.equals("consent"),bad.equals("trial"));
        assertThatThrownBy(()->h.adapter.createOwned(selected,h.f.f.owner)).isInstanceOf(RuntimeException.class);assertThat(h.f.f.http.calls).isEmpty();assertThat(h.f.f.rows).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"validUtc","opaqueCreated","unexpectedClock"})
    void ordinaryCreateRetainsExactRequestOriginalKeyAndUtcWindow(String shape){
        var h=new Harness();h.f.f.owner=null;
        var mappings=StripeBillingInertSdkFixture.port(dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort.class,c->switch(c.name()){
            case "findPrice"->Optional.of(new dev.genericpayments.platform.catalog.application.model.ProviderPriceRef("price_owned"));
            case "findProduct"->Optional.of(new dev.genericpayments.platform.catalog.application.model.ProviderProductRef("prod_owned"));default->throw new AssertionError(c.name());});
        var catalog=StripeBillingInertSdkFixture.port(SubscriptionCatalogPort.class,c->h.f.terms);
        var proofs=StripeBillingInertSdkFixture.port(SubscriptionBillingProofStore.class,c->{throw new AssertionError("Ordinary paid create reached trial/setup proof");});
        var adapter=new StripeSubscriptionProviderAdapter(h.f.f.credentials,h.f.f.clients,h.f.f.verification,h.f.f.operations,h.bindings,h.f.f.customerAdapter(),h.f.f.digest,h.f.f.accounts,h.f.f.sites,h.f.f.customers,h.subscriptions,h.operations,catalog,mappings,Clock.fixed(h.f.utc,ZoneOffset.UTC),null,proofs,Clock.fixed(h.f.utc,ZoneOffset.UTC));
        if(!shape.equals("unexpectedClock"))h.f.remote.remove("test_clock");
        h.f.remote.addProperty("created",shape.equals("opaqueCreated")?42:h.f.utc.getEpochSecond());h.f.f.http.reply(h.f.remote.toString());
        var reply=adapter.create(h.f.command());
        assertThat(reply.outcome()).isEqualTo(shape.equals("validUtc")?Outcome.ACCEPTED:Outcome.UNKNOWN);
        assertThat(h.f.f.http.calls).hasSize(1);var post=h.f.f.http.calls.getFirst();
        assertThat(post.params()).containsOnlyKeys("customer","billing_mode","collection_method","payment_behavior","payment_settings","items","metadata");
        assertThat(post.params()).containsEntry("customer","cus_owned").containsEntry("collection_method","charge_automatically").containsEntry("payment_behavior","default_incomplete");
        assertThat(post.params().get("billing_mode")).isEqualTo(Map.of("type","classic"));
        assertThat(post.params().get("payment_settings")).isEqualTo(Map.of("payment_method_types",List.of("card"),"save_default_payment_method","on_subscription"));
        assertThat(post.params().get("items")).isEqualTo(List.of(Map.of("price","price_owned","quantity",1L)));
        assertThat(post.headers().map().get("Idempotency-Key")).containsExactly(h.f.intent.operationKey());
        if(shape.equals("validUtc")){assertThat(adapter.create(h.f.command()).outcome()).isEqualTo(Outcome.ACCEPTED);assertThat(h.f.f.http.calls).hasSize(1);}
    }
    @Test void ownedAdapterHasOnlyExplicitCreateAndReadAndNoRuntimeRegistration(){
        assertThat(StripeBillingClockSubscriptionAdapter.class.getInterfaces()).isEmpty();
        assertThat(StripeBillingClockSubscriptionAdapter.class.isAnnotationPresent(org.springframework.stereotype.Component.class)).isFalse();
        assertThat(Arrays.stream(StripeBillingClockSubscriptionAdapter.class.getDeclaredMethods()).filter(m->java.lang.reflect.Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName)).containsExactlyInAnyOrder("createOwned","retrieveOwned");
    }
    static void after(Harness h,int remaining,Runnable effect){h.f.f.http.afterResponse=remaining==1?effect:()->after(h,remaining-1,effect);}
    static final class Harness {
        final StripeBillingOwnedSubscriptionFixture f=new StripeBillingOwnedSubscriptionFixture();
        OwnedClockSubscriptionReceipt receipt;boolean ackFailure,claimLost,accountDrift;int currentChecks;
        final StripeBillingClockSubscriptionStore owned=StripeBillingInertSdkFixture.port(StripeBillingClockSubscriptionStore.class,c->{
            current();return switch(c.name()){
                case "admitFresh"->{if(!c.args()[0].equals(f.f.account)||!c.args()[1].equals(f.f.owner)||!c.args()[2].equals(f.subscriptionId)||!c.args()[3].equals(f.operationId))throw new AssertionError("foreign admission");yield f.intent;}
                case "find"->Optional.of(f.intent);
                case "requireCurrent"->{if(!c.args()[0].equals(f.intent))throw new IllegalStateException("foreign intent");yield f.intent;}
                case "receipt"->Optional.ofNullable(receipt);
                case "requireReceipt"->{if(receipt==null)throw new IllegalStateException("missing receipt");yield receipt;}
                default->throw new AssertionError(c.name());};
        });
        final ProviderOperationStore steps=StripeBillingInertSdkFixture.port(ProviderOperationStore.class,c->{var a=c.args();return switch(c.name()){
            case "reserveOwnedBillingSubscription"->{current();if(!a[0].equals(f.f.account)||!a[1].equals(f.intent))throw new AssertionError("foreign reserve");yield f.f.operations.reserve(f.f.account,ProviderOperation.Kind.SUBSCRIPTION_CREATE,f.operationId,f.intent.operationKey(),f.intent.fingerprint());}
            case "claim"->{if(claimLost)yield Optional.empty();yield f.f.operations.claim((UUID)a[0],(String)a[1],(Instant)a[2],(Duration)a[3]);}
            case "findById"->f.f.operations.findById((UUID)a[0]);
            case "acknowledgeOwnedBillingSubscription"->{current();var claim=(ProviderOperation)a[1];if(ackFailure)throw new IllegalStateException("atomic ACK failed");if(!a[0].equals(f.intent)||!claim.equals(f.f.rows.get(claim.id()))||claim.status()!=ProviderOperation.Status.DISPATCHING)throw new AssertionError("nonoriginal ACK");
                var r=new OwnedClockSubscriptionReceipt(f.intent,claim.id(),(String)a[2],(Long)a[3],(String)a[4],(Long)a[5],(Long)a[6],(Instant)a[7]);
                f.f.operations.acknowledge(claim.id(),claim.leaseOwner(),claim.version(),r.reference());receipt=r;yield r;}
            case "unknown"->f.f.operations.unknown((UUID)a[0],(String)a[1],(Long)a[2]);
            default->throw new AssertionError(c.name());};
        });
        final SubscriptionStore subscriptions=StripeBillingInertSdkFixture.port(SubscriptionStore.class,c->{if(!c.name().equals("findOwned"))throw new AssertionError(c.name());return Optional.of(f.local);});
        final SubscriptionOperationStore operations=StripeBillingInertSdkFixture.port(SubscriptionOperationStore.class,c->{if(!c.name().equals("find"))throw new AssertionError(c.name());return Optional.of(f.operation);});
        final StripeBillingBindingPort bindings=StripeBillingInertSdkFixture.port(StripeBillingBindingPort.class,c->{if(!c.name().equals("findCustomer"))throw new AssertionError(c.name());return Optional.of(f.binding());});
        final StripeBillingClockSubscriptionAdapter adapter=new StripeBillingClockSubscriptionAdapter(f.f.credentials,f.f.clients,f.f.verification,steps,f.f.clocks,owned,bindings,subscriptions,operations,Clock.fixed(f.utc.plusSeconds(2),ZoneOffset.UTC));
        void current(){currentChecks++;f.f.clocks.requireActive(f.f.owner);if(accountDrift)throw new IllegalStateException("account changed");}
        void createReplies(){f.f.http.reply(f.f.clockJson());f.f.http.reply(f.remote.toString());}
        void seedReceipt(){var reserved=f.f.operations.reserve(f.f.account,ProviderOperation.Kind.SUBSCRIPTION_CREATE,f.operationId,f.intent.operationKey(),f.intent.fingerprint());var claimed=f.f.operations.claim(reserved.id(),"original",f.utc,Duration.ofSeconds(60)).orElseThrow();f.f.operations.acknowledge(claimed.id(),claimed.leaseOwner(),claimed.version(),"sub_owned");receipt=new OwnedClockSubscriptionReceipt(f.intent,claimed.id(),"sub_owned",42,"si_owned",1699999900,1702678400,f.utc.plusSeconds(1));f.local=StripeBillingOwnership.bound(f.local,"sub_owned");}
    }
}
