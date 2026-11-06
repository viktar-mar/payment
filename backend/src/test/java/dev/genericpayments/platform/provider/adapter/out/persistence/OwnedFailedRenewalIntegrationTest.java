package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalStore;
import dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalService;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedRenewalObserver;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionFactService;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.support.StripeBillingFirstRenewalCompositionFixture;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

/** Hosted composition only. Never selected by the offline pure-test launcher. */
class OwnedFailedRenewalIntegrationTest extends StripeBillingFirstRenewalCompositionFixture {
    @Autowired TrialEligibilityStore trials;
    @Autowired BillingRecordStore bills;
    @Autowired SubscriptionCashPort cash;
    @Autowired SubscriptionEventStore events;
    @Autowired SubscriptionCatalogPort catalog;
    @Autowired SubscriptionCallbackPort callback;
    @Autowired BillingMetadataPort metadata;
    @Autowired SubscriptionQuoteStore quotes;
    @Autowired SubscriptionSweepPort sweep;
    @Autowired SubscriptionAuthorityPort authority;
    @Autowired LockSitePort siteLock;
    @Autowired CustomerStore customerStore;
    private OwnedFailedRenewalStore store() { return new JdbcOwnedFailedRenewalStore(jdbc); }
    private final ExactTime time=new ExactTime();
    private class ExactTime implements SubscriptionBillingTimePort {
        Instant selected; boolean driftSecondSample; int samples;
        public Sample sample(PaymentAccountId account,SiteId suppliedSite,CustomerId suppliedCustomer,Instant utc) {
            if(!transaction.active() || selected==null || !billing.id().equals(account) || !site.equals(suppliedSite) || !customer.equals(suppliedCustomer)) throw new IllegalStateException("foreign or unarmed isolated owner");
            samples++;
            return new Sample(driftSecondSample&&samples==2?selected.plusSeconds(1):selected,Mode.ISOLATED);
        }
        public void requireUnscopedAllowed(SiteId s,CustomerId c) {
            if((s==null||s.equals(site))&&(c==null||c.equals(customer))) throw new IllegalStateException("isolated owner included");
        }
    }
    private OwnedFailedRenewalService service(OwnedFailedRenewalStore store, ApplySubscriptionSnapshotUseCase wrapper) {
        return service(store,wrapper,()->{});
    }
    private OwnedFailedRenewalService service(OwnedFailedRenewalStore store,ApplySubscriptionSnapshotUseCase wrapper,Runnable beforeFacts) {
        var real=new SubscriptionFactService(subscriptions,lifecycleOperations,trials,siteLock,customerStore,accountLocks,bills,cash,events,catalog,
            Clock.systemUTC(),callback,objects,metadata,policies,observations,digest,quotes,sweep,readPreparation,authority,time,
            new OwnedFailedRenewalFactProof(store,transaction,time));
        return new OwnedFailedRenewalService(store,transaction,lifecycleOperations,lifecycleLocks,accountLocks,subscriptions,bills,
            readPreparation,observations,command->{beforeFacts.run();real.apply(command);if(wrapper!=null)wrapper.apply(command);},trials,time,Clock.systemUTC(),bindings);
    }
    private record Setup(OwnedFirstRenewal.Admission upstream,OwnedFirstRenewal.LaterReady ready,OwnedFirstRenewal.Boundary boundary,Admission admission) {}
    private Setup setup() {
        var upstream=paidAdmission();var first=advance(upstream,OwnedFirstRenewal.Step.BOUNDARY);var pending=boundary(upstream,first);
        var ready=advance(upstream,OwnedFirstRenewal.Step.FINALIZATION);time.selected=Instant.ofEpochSecond(receipt.periodEnd()+3600);
        var admission=service(store(),null).admit(upstream,ready,pending.boundary());
        return new Setup(upstream,ready,pending.boundary(),admission);
    }
    private Snapshot snapshot(Setup s,boolean recovered,String key,Instant observed) {
        var original=s.upstream();var terms=original.receipt().intent().terms();
        var initial=new Invoice(original.invoice(),BillingRecord.Kind.INITIAL,terms,null,
            new Subscription.Period(Instant.ofEpochSecond(receipt.periodStart()),Instant.ofEpochSecond(receipt.periodEnd())),1000,"EUR",SettlementKind.CASH,
            List.of(new CashObject(original.paymentIntent(),1000,"EUR",PaymentStatus.SUCCEEDED,Instant.ofEpochSecond(1700000011))));
        var renewal=new Invoice(s.boundary().renewalInvoice(),BillingRecord.Kind.RENEWAL,terms,null,s.boundary().period(),1000,"EUR",
            recovered?SettlementKind.CASH:SettlementKind.OPEN,recovered?List.of(new CashObject("pi_renewal",1000,"EUR",PaymentStatus.SUCCEEDED,observed)):List.of());
        return new Snapshot(receipt.reference(),recovered?Subscription.Status.ACTIVE:Subscription.Status.PAST_DUE,terms,s.boundary().period(),null,null,
            List.of(initial,renewal),key,true,0,observed,recovered?new OperationObservation(key,OperationState.APPLIED):null);
    }
    private Failure fail(Setup s,OwnedFailedRenewalService service) {
        var read=service.beginFailedRead(s.admission(),UUID.randomUUID());var now=Instant.now();
        return service.publishFailure(s.admission(),read,new FailedEvidence(snapshot(s,false,null,now),"inpay_renewal","pi_renewal",now));
    }
    @Test void genuineInspectionAndFactResamplingMismatchRollsBack() {
        var s=setup();var svc=service(store(),null);var read=svc.beginFailedRead(s.admission(),UUID.randomUUID());
        var before=financialState();var context=svc.inspectFailedRead(s.admission(),read);
        assertThat(context.binding()).isEqualTo(bindings.findCustomer(billing.id(),site,customer).orElseThrow());
        assertThat(context.billingAt()).isEqualTo(Instant.ofEpochSecond(s.ready().ack().advance().target()));
        assertThat(financialState()).isEqualTo(before);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
        time.samples=0;time.driftSecondSample=true;var now=Instant.now();
        assertThatThrownBy(()->svc.publishFailure(s.admission(),read,new FailedEvidence(snapshot(s,false,null,now),"inpay_renewal","pi_renewal",now),context)).hasMessage("BILLING_TIME_CHANGED");
        assertThat(financialState()).isEqualTo(before);assertThat(count("stripe_billing_failed_renewal_failures")).isZero();
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);assertThat(count("payments")).isEqualTo(1);
    }
    @Test void observerPublishesFreshDeclineThroughJdbcAndRealFactService() {
        var s=setup();var original=jdbc.queryForMap("select * from payments where id=?",s.upstream().payment());
        long beforeOutbox=count("outbox_events"),beforeEvents=count("subscription_events");int beforeReads=http.calls.size();
        failedGraph(s);
        var result=new StripeBillingOwnedFailedRenewalObserver(service(store(),null),credentials,clients,Clock.systemUTC()).observe(s.admission());
        assertThat(current().billingStatus()).isEqualTo(Subscription.Status.PAST_DUE);
        assertThat(current().debt().billingRecordId()).isEqualTo(result.billId());
        assertThat(result.invoice()).isEqualTo(s.boundary().renewalInvoice());assertThat(result.paymentIntent()).isEqualTo("pi_renewal");
        assertThat(jdbc.queryForObject("select claim_id from stripe_billing_failed_renewal_failures where admission_id=?",UUID.class,s.admission().id())).isEqualTo(result.claimId());
        assertThat(jdbc.queryForObject("select settlement from subscription_billing_records where id=?",String.class,result.billId())).isEqualTo("OPEN");
        assertThat(jdbc.queryForMap("select * from payments where id=?",s.upstream().payment())).isEqualTo(original);
        assertThat(count("payments")).isEqualTo(1);assertThat(count("subscription_events")).isEqualTo(beforeEvents+1);
        assertThat(count("outbox_events")).isEqualTo(beforeOutbox+1);assertThat(count("stripe_billing_failed_renewal_failures")).isEqualTo(1);
        assertThat(count("stripe_billing_failed_renewal_reads")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from subscription_operations where kind='RECOVER'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='INVOICE_PAY'",Long.class)).isZero();
        assertThat(http.calls.subList(beforeReads,http.calls.size())).hasSize(15).allSatisfy(r->assertThat(r.method().name()).isEqualTo("GET"));
        assertThat(http.replies).isEmpty();
    }
    @Test void observerPostFactFailureRollsBackDebtJournalAndOutboxBeforeOneStop() {
        var s=setup();var before=financialState();int beforeReads=http.calls.size();failedGraph(s);
        var broken=service(store(),command->{throw new IllegalStateException("synthetic after fact");});
        assertThatThrownBy(()->new StripeBillingOwnedFailedRenewalObserver(broken,credentials,clients,Clock.systemUTC()).observe(s.admission()))
            .isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,e->{
                assertThat(e.code()).isEqualTo("FAILED_RENEWAL_UNAVAILABLE");assertThat(e.status()).isEqualTo(409);
                assertThat(e).hasMessage("Failed renewal observation unavailable").hasNoCause();});
        assertThat(financialState()).isEqualTo(before);assertThat(current().debt()).isNull();assertThat(count("payments")).isEqualTo(1);
        assertThat(count("stripe_billing_failed_renewal_failures")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);
        assertThat(http.calls.subList(beforeReads,http.calls.size())).hasSize(15);assertThat(http.replies).isEmpty();
    }
    @Test void observerFactResamplingDriftAfterMatchedPublicationContextCannotWrite() {
        var s=setup();var before=financialState();int beforeReads=http.calls.size();failedGraph(s);
        var drifting=service(store(),null,()->time.selected=time.selected.plusSeconds(1));
        assertThatThrownBy(()->new StripeBillingOwnedFailedRenewalObserver(drifting,credentials,clients,Clock.systemUTC()).observe(s.admission()))
            .isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,e->{
                assertThat(e.code()).isEqualTo("FAILED_RENEWAL_UNAVAILABLE");assertThat(e).hasMessage("Failed renewal observation unavailable").hasNoCause();});
        assertThat(financialState()).isEqualTo(before);assertThat(current().debt()).isNull();assertThat(count("payments")).isEqualTo(1);
        assertThat(count("stripe_billing_failed_renewal_failures")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);
        assertThat(http.calls.subList(beforeReads,http.calls.size())).hasSize(15);assertThat(http.replies).isEmpty();
    }
    /** Fresh synthetic failed SDK graph, queued after genuine initial payment and both owned advances. */
    private void failedGraph(Setup s) {
        assertThat(http.replies).isEmpty();graph(s.ready(),true);
        replaceFailedReply(2,j->j.addProperty("status","past_due"));
        replaceFailedReply(3,j->{var renewal=j.getAsJsonArray("data").get(1).getAsJsonObject();renewal.addProperty("status","open");renewal.addProperty("amount_paid",0);renewal.addProperty("amount_remaining",1000);});
        for(int i:new int[]{10,13})replaceFailedReply(i,j->{var allocation=j.getAsJsonArray("data").get(0).getAsJsonObject();
            allocation.addProperty("status","open");allocation.addProperty("is_default",true);allocation.addProperty("amount_paid",0);
            allocation.getAsJsonObject("status_transitions").add("paid_at",com.google.gson.JsonNull.INSTANCE);
            allocation.getAsJsonObject("status_transitions").add("canceled_at",com.google.gson.JsonNull.INSTANCE);});
        replaceFailedReply(11,j->{j.addProperty("status","requires_payment_method");j.addProperty("amount_received",0);
            j.add("last_payment_error",com.google.gson.JsonParser.parseString("{\"type\":\"card_error\",\"code\":\"card_declined\",\"decline_code\":\"generic_decline\",\"charge\":\"ch_renewal\"}"));});
        replaceFailedReply(12,j->{j.addProperty("status","failed");j.addProperty("paid",false);j.addProperty("captured",false);
            j.addProperty("amount_captured",0);j.addProperty("amount_refunded",0);j.addProperty("refunded",false);j.addProperty("failure_code","card_declined");
            j.getAsJsonObject("payment_method_details").add("card",com.google.gson.JsonParser.parseString("{}"));
            j.add("outcome",com.google.gson.JsonParser.parseString("{\"type\":\"issuer_declined\",\"reason\":\"generic_decline\"}"));});
    }
    private void replaceFailedReply(int index,java.util.function.Consumer<com.google.gson.JsonObject> mutation) {
        var replies=new ArrayList<>(http.replies);var old=(com.stripe.net.StripeResponse)replies.get(index);
        var value=com.google.gson.JsonParser.parseString(old.body()).getAsJsonObject();mutation.accept(value);
        replies.set(index,new com.stripe.net.StripeResponse(200,com.stripe.net.HttpHeaders.of(Map.of()),value.toString()));
        http.replies.clear();http.replies.addAll(replies);
    }
    @Test void genuineDebtGraceRecoveryAndReadOnlyReplay() {
        var s=setup();var service=service(store(),null);var original=jdbc.queryForMap("select * from payments where id=?",s.upstream().payment());
        var failure=fail(s,service);assertThat(current().debt().billingRecordId()).isEqualTo(failure.billId());
        assertThat(current().billingStatus()).isEqualTo(Subscription.Status.PAST_DUE);assertThat(count("payments")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select settlement from subscription_billing_records where id=?",String.class,failure.billId())).isEqualTo("OPEN");
        var policy=new SubscriptionAccessPolicy();var g=failure.graceDeadline();
        time.selected=g.minusSeconds(1);assertThat(policy.evaluate(current(),sampleSelected()).reason()).isEqualTo(AccessDecision.Reason.GRACE);
        time.selected=g;assertThat(policy.evaluate(current(),sampleSelected()).allowed()).isFalse();
        var debtBefore=current().debt();var cashBefore=rows("payments");var financialBeforeReservation=financialState();
        var recovery=service.reserveExistingRecovery(s.admission());assertThat(recovery.paymentIntent()).isEqualTo("pi_renewal");
        assertThat(current().debt()).isEqualTo(debtBefore);assertThat(rows("payments")).isEqualTo(cashBefore);
        assertThat(financialState()).isEqualTo(financialBeforeReservation);
        assertThat(policy.evaluate(current(),sampleSelected()).allowed()).isFalse();
        time.selected=g.plusSeconds(1);assertThat(policy.evaluate(current(),sampleSelected()).allowed()).isFalse();
        assertThat(recovery.allocation()).isEqualTo("inpay_renewal");assertThat(recovery.billId()).isEqualTo(failure.billId());
        assertThat(count("stripe_subscription_recovery_proofs")).isEqualTo(1);
        var operation=lifecycleOperations.find(site,current().id(),recovery.operationId()).orElseThrow();
        var read=service.beginRecoveredRead(s.admission(),UUID.randomUUID());var now=Instant.now();
        var publication=service.publishRecovery(s.admission(),read,new RecoveredEvidence(snapshot(s,true,operation.providerOperationKey(),now),now));
        assertThat(publication.billId()).isEqualTo(failure.billId());assertThat(current().debt()).isNull();
        assertThat(current().currentPeriod()).isEqualTo(s.boundary().period());assertThat(current().paidThrough()).isEqualTo(s.boundary().period().end());
        assertThat(policy.evaluate(current(),sampleSelected()).reason()).isEqualTo(AccessDecision.Reason.PAID);
        assertThat(lifecycleOperations.find(site,current().id(),recovery.operationId()).orElseThrow().status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(jdbc.queryForObject("select settlement from subscription_billing_records where id=?",String.class,failure.billId())).isEqualTo("CONFIRMED_CASH");
        assertThat(jdbc.queryForObject("select provider_payment_reference from payments where id=?",String.class,publication.paymentId())).isEqualTo("pi_renewal");
        assertThat(jdbc.queryForMap("select * from payments where id=?",s.upstream().payment())).isEqualTo(original);
        assertThat(count("payments")).isEqualTo(2);assertThat(count("outbox_events")).isEqualTo(5);
        assertThat(jdbc.queryForList("select event_type from subscription_events",String.class)).contains("RENEWAL_FAILED","PERIOD_PAID");
        assertThat(jdbc.queryForList("select event_type from payment_events",String.class)).containsOnly("PAYMENT_SUCCEEDED");
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='INVOICE_PAY'",Long.class)).isZero();
        var state=financialState();assertThat(service.replayPublication(s.admission())).isEqualTo(publication);
        assertThat(service.replayPublication(s.admission())).isEqualTo(publication);assertThat(financialState()).isEqualTo(state);
    }
    @Test void postFactFailureRollsBackAndStopsInSeparateTransaction() {
        var s=setup();var base=service(store(),null);fail(s,base);var recovery=base.reserveExistingRecovery(s.admission());
        var operation=lifecycleOperations.find(site,current().id(),recovery.operationId()).orElseThrow();var claim=base.beginRecoveredRead(s.admission(),UUID.randomUUID());
        var before=financialState();var beforeRecover=jdbc.queryForMap("select * from subscription_operations where id=?",recovery.operationId());
        assertThat(beforeRecover).containsEntry("status","RESERVED").containsEntry("version",recovery.operationVersion());
        var beforeReceipts=rows("stripe_billing_failed_renewal_recoveries");var beforeProofs=rows("stripe_subscription_recovery_proofs");
        assertThat(beforeReceipts).hasSize(1);assertThat(beforeProofs).hasSize(1);
        var now=Instant.now();var broken=service(store(),command->{throw new IllegalStateException("after facts");});
        assertThatThrownBy(()->broken.publishRecovery(s.admission(),claim,new RecoveredEvidence(snapshot(s,true,operation.providerOperationKey(),now),now))).hasMessage("after facts");
        assertThat(financialState()).isEqualTo(before);
        assertThat(jdbc.queryForMap("select * from subscription_operations where id=?",recovery.operationId())).isEqualTo(beforeRecover);
        assertThat(rows("stripe_billing_failed_renewal_recoveries")).isEqualTo(beforeReceipts);
        assertThat(rows("stripe_subscription_recovery_proofs")).isEqualTo(beforeProofs);
        assertThat(count("stripe_billing_failed_renewal_publications")).isZero();
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);
        assertThatThrownBy(()->base.beginRecoveredRead(s.admission(),UUID.randomUUID())).isInstanceOf(RuntimeException.class);
    }
    @ParameterizedTest @ValueSource(strings={"foreign","stale","expired"}) void invalidReadCannotApplyFacts(String kind) throws InterruptedException {
        var s=setup();var svc=service(store(),null);var read=svc.beginFailedRead(s.admission(),UUID.randomUUID());
        var supplied=kind.equals("foreign")?new ReadClaim(read.id(),UUID.randomUUID(),read.phase(),read.invocation(),read.startedAt(),read.deadlineAt(),read.fence()):read;
        if(kind.equals("stale"))jdbc.update("update subscriptions set observation_sequence=observation_sequence+1 where id=?",current().id());
        var before=financialState();var beforeAuthority=authorityRows();
        if(kind.equals("expired"))Thread.sleep(Duration.ofSeconds(31));
        var now=kind.equals("expired")?read.startedAt():Instant.now();
        assertThatThrownBy(()->svc.publishFailure(s.admission(),supplied,new FailedEvidence(snapshot(s,false,null,now),"inpay_renewal","pi_renewal",now))).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(before);assertThat(authorityRows()).isEqualTo(beforeAuthority);
        assertThat(count("payments")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_failures")).isZero();
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);
    }
    @Test void associationFailureRollsBackFreshRecoverAndProofBeforeStop() {
        var s=setup();var real=store();var normal=service(real,null);fail(s,normal);var before=financialState();
        var throwing=(OwnedFailedRenewalStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{OwnedFailedRenewalStore.class},(proxy,method,args)->{
            try {var result=method.invoke(real,args);if(method.getName().equals("associateInTransaction"))throw new IllegalStateException("after association");return result;}
            catch(InvocationTargetException e){throw e.getCause();}
        });
        assertThatThrownBy(()->service(throwing,null).reserveExistingRecovery(s.admission())).hasMessage("after association");
        assertThat(financialState()).isEqualTo(before);assertThat(count("stripe_billing_failed_renewal_recoveries")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from subscription_operations where kind='RECOVER'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from stripe_subscription_recovery_proofs",Long.class)).isZero();
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);
    }
    @Test void disabledAccountDeniesAuthorityButExactIdentityCanStop() {
        var s=setup();var before=financialState();
        transaction.requiresNew(()->{assertThat(jdbc.update("update payment_accounts set account_status='DISABLED' where id=? and account_status='ACTIVE'",billing.id().value())).isEqualTo(1);return null;});
        var real=store();assertThatThrownBy(()->transaction.requiresNew(()->real.requireCurrentInTransaction(s.admission()))).isInstanceOf(RuntimeException.class);
        var a=s.admission();var forged=new Admission(a.id(),a.firstRenewalAdmission(),a.ready(),a.boundary(),a.sourceSha(),a.admittedAt().plusSeconds(1));
        assertThatThrownBy(()->transaction.requiresNew(()->{real.stopInTransaction(forged,Reason.AUTHORITY_DRIFT,Instant.now());return null;})).isInstanceOf(IllegalStateException.class);
        assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
        var svc=service(real,null);svc.stop(a,Reason.AUTHORITY_DRIFT);svc.stop(a,Reason.AUTHORITY_DRIFT);
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select reason from stripe_billing_failed_renewal_stops where admission_id=?",String.class,a.id())).isEqualTo("AUTHORITY_DRIFT");
        assertThat(financialState()).isEqualTo(before);
        assertThatThrownBy(()->transaction.requiresNew(()->real.requireCurrentInTransaction(a))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->underLifecycle(s.upstream(),()->renewals.beginReadInTransaction(s.upstream(),s.ready(),fence(),UUID.randomUUID(),Instant.now()))).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_first_renewal_publications")).isZero();
    }
    @Test void stoppedAdmissionCannotResumeOrReopenSuccessfulBranch() {
        var s=setup();var svc=service(store(),null);var before=financialState();svc.stop(s.admission(),Reason.AUTHORITY_DRIFT);
        assertThatThrownBy(()->svc.beginFailedRead(s.admission(),UUID.randomUUID())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->svc.replayPublication(s.admission())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->underLifecycle(s.upstream(),()->renewals.beginReadInTransaction(s.upstream(),s.ready(),fence(),UUID.randomUUID(),Instant.now()))).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(1);
        assertThat(financialState()).isEqualTo(before);
    }
    private record Predecessor(OwnedFirstRenewal.Admission upstream,OwnedFirstRenewal.LaterReady ready,OwnedFirstRenewal.Boundary boundary) {}
    private Predecessor predecessor() {
        var u=paidAdmission();var first=advance(u,OwnedFirstRenewal.Step.BOUNDARY);var pending=boundary(u,first);
        var ready=advance(u,OwnedFirstRenewal.Step.FINALIZATION);time.selected=Instant.ofEpochSecond(receipt.periodEnd()+3600);
        return new Predecessor(u,ready,pending.boundary());
    }
    @ParameterizedTest @ValueSource(booleans={true,false}) void opposingBranchesSerializeAndExcludeEachOther(boolean failedWins) throws Exception {
        var p=predecessor();var chosen=new CountDownLatch(1);var release=new CountDownLatch(1);var competing=new CountDownLatch(1);
        try(var workers=new BoundedWorkers()) {
            var winner=workers.submit(()->transaction.requiresNew(()->{
                // Hold the shared predecessor branch row until the opposing transaction has started.
                jdbc.queryForObject("select id from stripe_billing_first_renewals where id=? for update",UUID.class,p.upstream().id());
                if(failedWins)store().admitInTransaction(p.upstream(),p.ready(),p.boundary(),Instant.now());
                else renewals.beginReadInTransaction(p.upstream(),p.ready(),fence(),UUID.randomUUID(),Instant.now());
                chosen.countDown();awaitRelease(release);return true;
            }));
            try {
                assertThat(chosen.await(5,TimeUnit.SECONDS)).isTrue();
                var loser=workers.submit(()->{competing.countDown();assertThatThrownBy(()->transaction.requiresNew(()->{
                    if(failedWins)renewals.beginReadInTransaction(p.upstream(),p.ready(),fence(),UUID.randomUUID(),Instant.now());
                    else store().admitInTransaction(p.upstream(),p.ready(),p.boundary(),Instant.now());return null;
                })).isInstanceOf(RuntimeException.class);return true;});
                assertThat(competing.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->loser.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();assertThat(winner.get(10,TimeUnit.SECONDS)).isTrue();assertThat(loser.get(10,TimeUnit.SECONDS)).isTrue();
            } finally {release.countDown();}
        }
        assertThat(count("stripe_billing_failed_renewals")).isEqualTo(failedWins?1:0);
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(failedWins?1:2);
        assertThat(count("payments")).isEqualTo(1);assertThat(count("stripe_billing_first_renewal_publications")).isZero();
    }
    private Instant sampleSelected() {
        return transaction.requiresNew(()->time.sample(billing.id(),site,customer,Instant.now()).billingAt());
    }
    private List<String> rows(String table) {
        return jdbc.queryForList("select row_to_json(t)::text from "+table+" t order by row_to_json(t)::text",String.class);
    }
    private Map<String,List<String>> authorityRows() {
        var result=new TreeMap<String,List<String>>();
        for(var table:List.of("stripe_billing_failed_renewals","stripe_billing_failed_renewal_reads","stripe_billing_failed_renewal_failures",
                "stripe_billing_failed_renewal_recoveries","stripe_billing_failed_renewal_publications","stripe_subscription_recovery_proofs","subscription_operations"))
            result.put(table,rows(table));
        return result;
    }
    private static void awaitRelease(CountDownLatch latch) {
        try {if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("branch race timed out");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }

}
