package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod;
import dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser;
import dev.genericpayments.platform.provider.application.service.OwnedMethodBrowserTestAccess;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalMethodService;
import dev.genericpayments.platform.provider.application.service.ProviderOperationGuard;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeRequestFingerprint;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedRenewalMethodObserver;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedMethodRemoteChecks;
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
    @Autowired SubscriptionBillingProofStore methodBillingProofs;
    private OwnedFailedRenewalStore store() { return new JdbcOwnedFailedRenewalStore(jdbc); }
    private dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext managementContext(String key){
        TestClock.set(Instant.now());var session=sessions.issue(site,customer,"https://shop.test");
        var actor=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer(sessions.resolveRequired(session.rawToken(),"https://shop.test"));
        return new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(actor,site,customer,"https://shop.test",key,"owned-method-request");
    }
    private SubscriptionFactService methodFacts(dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore methods){
        return new SubscriptionFactService(subscriptions,lifecycleOperations,trials,siteLock,customerStore,accountLocks,bills,cash,events,catalog,
            Clock.systemUTC(),callback,objects,metadata,policies,observations,digest,quotes,sweep,readPreparation,authority,time,
            new OwnedFailedRenewalMethodRecoveryFactProof(methods,transaction,time),new OwnedFailedRenewalMethodFactProof(methods,transaction,time),methodBillingProofs);
    }
    private dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalMethodService methodService(dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore methods){
        return new dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalMethodService(methods,store(),transaction,lifecycleOperations,lifecycleLocks,
            accountLocks,subscriptions,bills,readPreparation,observations,methodFacts(methods),trials,time,Clock.systemUTC(),bindings,authority,digest);
    }
    private final ExactTime time=new ExactTime();
    private class ExactTime implements SubscriptionBillingTimePort {
        Instant selected; boolean driftSecondSample,ordinary; int samples;
        public Sample sample(PaymentAccountId account,SiteId suppliedSite,CustomerId suppliedCustomer,Instant utc) {
            if(!transaction.active() || selected==null || !billing.id().equals(account) || !site.equals(suppliedSite) || !customer.equals(suppliedCustomer)) throw new IllegalStateException("foreign or unarmed isolated owner");
            samples++;
            return new Sample(driftSecondSample&&samples==2?selected.plusSeconds(1):selected,ordinary?Mode.ORDINARY:Mode.ISOLATED);
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

    /** V35 persistence negatives only; no successful lifecycle or ACK row is fabricated. */
    @Test void authenticatedMethodServiceReservesTrueConsentButCannotRecordPendingWithoutBothAcks(){
        var s=setup();var failure=fail(s,service(store(),null));var before=financialState();var methods=new JdbcOwnedFailedRenewalMethodStore(jdbc);var svc=methodService(methods);var context=managementContext("method-consent");int calls=http.calls.size();
        var r=svc.reserveMethod(s.admission(),context,true);var op=lifecycleOperations.find(site,current().id(),r.setupOperationId()).orElseThrow();
        assertThat(op.status()).isEqualTo(SubscriptionOperation.Status.RESERVED);assertThat(op.version()).isZero();assertThat(op.providerReference()).isNull();
        assertThat(op.authorityScope()).isEqualTo("owned-failed-renewal-method:"+s.admission().id());
        assertThat(op.keyDigest()).isEqualTo(digest.sha256Hex(context.idempotencyKey()));
        assertThat(op.fingerprint()).isEqualTo(dev.genericpayments.platform.subscriptions.application.service.SubscriptionInteractionTransaction.request(context,"owned-failed-renewal-method-v1",s.admission().id(),failure.billId(),failure.billVersion(),failure.invoice(),failure.allocation(),failure.paymentIntent(),failure.revision(),failure.observationSequence(),"SETUP",true));
        assertThatThrownBy(()->svc.recordMethodUpdateReply(r,context)).isInstanceOf(RuntimeException.class);
        assertThat(lifecycleOperations.find(site,current().id(),op.id())).contains(op);assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);
        assertThat(count("stripe_billing_failed_method_reservations")).isEqualTo(1);assertThat(count("stripe_billing_failed_method_reads")).isZero();assertThat(count("stripe_billing_failed_method_completions")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);
    }
    @Test void methodServiceFalseConsentCannotMintAnOperationOrReceipt(){
        var s=setup();fail(s,service(store(),null));var before=authorityRows();var methods=new JdbcOwnedFailedRenewalMethodStore(jdbc);var context=managementContext("false-method-consent");int calls=http.calls.size();
        assertThatThrownBy(()->methodService(methods).reserveMethod(s.admission(),context,false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(authorityRows()).isEqualTo(before);assertThat(count("stripe_billing_failed_method_reservations")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(http.calls).hasSize(calls);
    }
    @Test void methodReservationReceiptFailureRollsBackSpeculativeClaimBeforeSeparateStop(){
        var s=setup();fail(s,service(store(),null));var before=financialState();long operationCount=count("subscription_operations");var methods=new JdbcOwnedFailedRenewalMethodStore(jdbc);
        var broken=(dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore.class},(p,m,a)->{
            try{var result=m.invoke(methods,a);if(m.getName().equals("reserveInTransaction"))throw new IllegalStateException("synthetic reservation receipt failure");return result;}
            catch(InvocationTargetException failure){throw failure.getCause();}
        });
        var context=managementContext("rollback-method-consent");assertThatThrownBy(()->methodService(broken).reserveMethod(s.admission(),context,true)).hasMessage("synthetic reservation receipt failure");
        assertThat(count("subscription_operations")).isEqualTo(operationCount);assertThat(count("stripe_billing_failed_method_reservations")).isZero();assertThat(financialState()).isEqualTo(before);assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"unchanged","begin-time","begin-mode","publish-time","publish-mode"})
    void preparedMethodSampleControlAndTimeOrModeOnlyDrift(String mutation){
        var p=preparedMethod();var r=p.reservation();var op=lifecycleOperations.find(site,current().id(),r.setupOperationId()).orElseThrow();
        assertThat(op.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);assertThat(op.version()).isEqualTo(1);
        assertThat(p.setupStep().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(p.methodStep().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        var before=financialState();
        if(mutation.equals("unchanged")) {
            var q=p.service().beginMethodRead(r,UUID.randomUUID());assertThat(p.service().inspectMethodRead(r,q).billingSample()).isEqualTo(r.billingSample());
            assertThat(count("stripe_billing_failed_method_reads")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
        } else if(mutation.startsWith("begin")) {
            driftSample(mutation);assertThatThrownBy(()->p.service().beginMethodRead(r,UUID.randomUUID())).isInstanceOf(RuntimeException.class);
            assertThat(count("stripe_billing_failed_method_reads")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);
        } else {
            var q=p.service().beginMethodRead(r,UUID.randomUUID());var c=p.service().inspectMethodRead(r,q);driftSample(mutation);var now=Instant.now();
            var raw=new OwnedFailedRenewalMethod.MethodEvidence(snapshot(p.setup(),false,null,now),"seti_saved","pm_saved",now);
            assertThatThrownBy(()->p.service().publishMethod(r,q,raw,c)).isInstanceOf(RuntimeException.class);
            assertThat(count("stripe_billing_failed_method_reads")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);
        }
        assertThat(financialState()).isEqualTo(before);assertThat(lifecycleOperations.find(site,current().id(),op.id())).contains(op);
        assertThat(count("stripe_billing_failed_method_completions")).isZero();assertThat(http.replies).isEmpty();
    }
    private void driftSample(String mutation){if(mutation.endsWith("mode"))time.ordinary=true;else time.selected=time.selected.plusSeconds(1);}

    private record PreparedMethod(Setup setup,Failure failure,OwnedFailedRenewalMethod.Reservation reservation,
            OwnedFailedRenewalMethodService service,ProviderOperation setupStep,ProviderOperation methodStep) {}
    private record PendingMethod(Setup setup,Failure failure,OwnedFailedRenewalMethod.Reservation reservation,
            OwnedFailedRenewalMethodService service,dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext context,
            ProviderOperation setupStep,int callStart) {}

    /** Genuine failed observer, service reservation, typed first dispatches, SDK replies and ordinary proof stores.
     * Provider-side confirmation is synthetic observation only; no browser capability is issued or claimed. */
    private PreparedMethod preparedMethod() {
        var pending=pendingMethod(false);var s=pending.setup();var failure=pending.failure();var r=pending.reservation();var svc=pending.service();
        var context=pending.context();var setupStep=pending.setupStep();var callStart=pending.callStart();var before=financialState();
        var subscription=current();var o=lifecycleOperations.find(site,subscription.id(),r.setupOperationId()).orElseThrow();
        var binding=bindings.findCustomer(billing.id(),site,customer).orElseThrow();var client=clients.create(credentials.resolve(billing));
        var proof=methodBillingProofs.setup(billing.id(),o.id()).orElseThrow();
        http.reply(setupBody(o,setupStep,"succeeded"));http.reply(methodBody());
        try {
            var succeeded=client.v1().setupIntents().retrieve(proof.reference());OwnedMethodRemoteChecks.requireSetup(succeeded,subscription,o,binding,setupStep);
            assertThat(succeeded.getStatus()).isEqualTo("succeeded");assertThat(succeeded.getPaymentMethod()).isEqualTo("pm_saved");
            var method=client.v1().paymentMethods().retrieve(succeeded.getPaymentMethod());
            assertThat(method.getId()).isEqualTo("pm_saved");assertThat(method.getObject()).isEqualTo("payment_method");assertThat(method.getLivemode()).isFalse();
            assertThat(method.getCustomer()).isEqualTo(binding.providerReference());assertThat(method.getType()).isEqualTo("card");assertThat(method.getCard()).isNotNull();
            proof=methodBillingProofs.bindMethod(proof,method.getId());
        }catch(com.stripe.exception.StripeException failureSdk){throw new AssertionError(failureSdk);}
        assertThat(lifecycleOperations.find(site,subscription.id(),o.id())).contains(o);
        var update=operations.reserveOwnedFailedRenewalMethodStep(billing,r,ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
        String methodOwner=UUID.randomUUID().toString();var updateClaim=operations.claim(update.id(),methodOwner,Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        http.reply("{\"id\":\""+subscription.providerReference()+"\",\"object\":\"subscription\",\"livemode\":false,\"customer\":\""+binding.providerReference()+"\",\"default_payment_method\":\"pm_saved\"}");
        var updateDispatch=operations.requireOwnedFailedRenewalMethodDispatch(billing,r,updateClaim,Instant.now());
        String updateKey=new ProviderOperationGuard().requireDispatch(updateDispatch,billing,update.fingerprint(),Instant.now());
        try {var reply=client.v1().subscriptions().update(subscription.providerReference(),com.stripe.param.SubscriptionUpdateParams.builder().setDefaultPaymentMethod(proof.method()).build(),writeOptions(updateKey));
            assertThat(reply.getId()).isEqualTo(subscription.providerReference());assertThat(reply.getObject()).isEqualTo("subscription");assertThat(reply.getLivemode()).isFalse();
            assertThat(reply.getCustomer()).isEqualTo(binding.providerReference());assertThat(reply.getDefaultPaymentMethod()).isEqualTo(proof.method());
        }catch(com.stripe.exception.StripeException failureSdk){throw new AssertionError(failureSdk);}
        var methodStep=operations.acknowledge(updateDispatch.id(),methodOwner,updateDispatch.version(),subscription.providerReference());
        assertThat(setupStep.version()).isEqualTo(2);assertThat(methodStep.version()).isEqualTo(2);
        assertThat(financialState()).isEqualTo(before);assertThat(lifecycleOperations.find(site,subscription.id(),o.id())).contains(o);
        assertThat(http.calls.subList(callStart,http.calls.size()).stream().map(request->request.method().name()+" "+request.url().getPath()).toList())
            .containsExactly("POST /v1/setup_intents","GET /v1/setup_intents/seti_saved","GET /v1/payment_methods/pm_saved","POST /v1/subscriptions/"+subscription.providerReference());
        assertThat(http.calls.get(callStart).options().getIdempotencyKey()).isEqualTo(setupStep.operationKey());
        assertThat(http.calls.get(callStart+3).options().getIdempotencyKey()).isEqualTo(methodStep.operationKey());assertThat(http.replies).isEmpty();
        var processing=svc.recordMethodUpdateReply(r,context);assertThat(processing.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);assertThat(processing.version()).isEqualTo(1);
        assertThat(processing.providerReference()).isEqualTo(subscription.providerReference());assertThat(financialState()).isEqualTo(before);
        return new PreparedMethod(s,failure,r,svc,setupStep,methodStep);
    }
    /** Reuses the genuine original first dispatch and SDK ACK producer, stopping before any method binding. */
    private PendingMethod pendingMethod(boolean browser) {
        return pendingMethod(browser,0);
    }
    private PendingMethod pendingMethod(boolean browser,int sessionSeconds) {
        var s=setup();failedGraph(s);
        var failure=new StripeBillingOwnedFailedRenewalObserver(service(store(),null),credentials,clients,Clock.systemUTC()).observe(s.admission());
        var before=financialState();var methods=new JdbcOwnedFailedRenewalMethodStore(jdbc);var svc=methodService(methods);
        var context=managementContext("genuine-owned-method");
        if(sessionSeconds>0){
            var original=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)context.actor()).session();
            var expiry=Instant.now().plusSeconds(sessionSeconds).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            jdbc.update("update customer_sessions set expires_at=? where id=?",java.sql.Timestamp.from(expiry),original.sessionId().value());
            var changed=new dev.genericpayments.platform.customers.application.model.CustomerSessionContext(original.sessionId(),original.siteId(),original.customerId(),original.origin(),original.scopes(),expiry);
            context=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer(changed),site,customer,changed.origin(),context.idempotencyKey(),context.requestId());
        }
        var r=browser?svc.reserveBrowserMethod(s.admission(),context,true):svc.reserveMethod(s.admission(),context,true);
        var subscription=current();var o=lifecycleOperations.find(site,subscription.id(),r.setupOperationId()).orElseThrow();
        assertThat(o.status()).isEqualTo(SubscriptionOperation.Status.RESERVED);assertThat(o.version()).isZero();
        var binding=bindings.findCustomer(billing.id(),site,customer).orElseThrow();var client=clients.create(credentials.resolve(billing));
        int callStart=http.calls.size();var reserved=operations.reserveOwnedFailedRenewalMethodStep(billing,r,ProviderOperation.Kind.SETUP_INTENT_CREATE);
        String setupOwner=UUID.randomUUID().toString();var claimed=operations.claim(reserved.id(),setupOwner,Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        methodBillingProofs.authorizeSetup(subscription,o);
        http.reply(setupBody(o,claimed,"requires_payment_method"));
        var dispatch=operations.requireOwnedFailedRenewalMethodDispatch(billing,r,claimed,Instant.now());
        String key=new ProviderOperationGuard().requireDispatch(dispatch,billing,reserved.fingerprint(),Instant.now());
        com.stripe.model.SetupIntent remote;
        try {remote=client.v1().setupIntents().create(com.stripe.param.SetupIntentCreateParams.builder().setCustomer(binding.providerReference())
            .setUsage(com.stripe.param.SetupIntentCreateParams.Usage.OFF_SESSION).addAllowedPaymentMethodType(com.stripe.param.SetupIntentCreateParams.AllowedPaymentMethodType.CARD)
            .putMetadata("operation_id",o.id().toString()).putMetadata("subscription_id",subscription.id().toString()).build(),writeOptions(key));}
        catch(com.stripe.exception.StripeException failureSdk){throw new AssertionError(failureSdk);}
        OwnedMethodRemoteChecks.requireSetup(remote,subscription,o,binding,dispatch);
        var setupStep=operations.acknowledge(dispatch.id(),setupOwner,dispatch.version(),remote.getId());
        var proof=methodBillingProofs.recordSetup(subscription,o,setupStep.id(),setupStep.providerReference());
        assertThat(proof.method()).isNull();assertThat(financialState()).isEqualTo(before);
        return new PendingMethod(s,failure,r,svc,context,setupStep,callStart);
    }
    private dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore browserStore(){return new JdbcOwnedFailedRenewalMethodStore(jdbc);}
    private <T>T browserPhase(PendingMethod p,java.util.function.Function<OwnedMethodBrowser.Pending,T> work){
        return OwnedMethodBrowserTestAccess.phase(p.service(),p.reservation(),p.context(),work);
    }
    private static Instant browserNow(){return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private OwnedMethodBrowser.Root browserRoot(PendingMethod p){
        return browserPhase(p,pending->{var at=browserNow();var expiry=at.plusSeconds(600);
            if(pending.enrollment().sessionExpiresAt().isBefore(expiry))expiry=pending.enrollment().sessionExpiresAt();
            return browserStore().insertBrowserRootInTransaction(pending,new OwnedMethodBrowser.Root(UUID.randomUUID(),p.reservation().id(),pending.operation().id(),pending.setupStep().id(),
                pending.setupStep().version(),pending.setup().reference(),"v1","a".repeat(64),at,expiry));});
    }
    private OwnedMethodBrowser.Selection selectBrowser(PendingMethod p,OwnedMethodBrowser.Root root){
        return browserPhase(p,pending->browserStore().startBrowserSelectionInTransaction(pending,root,"b".repeat(64),browserNow()));
    }
    private void releaseBrowser(PendingMethod p,OwnedMethodBrowser.Root root,OwnedMethodBrowser.Selection selected){
        browserPhase(p,pending->{browserStore().finishBrowserSelectionInTransaction(pending,root,selected,browserNow());return null;});
    }
    private boolean consumeBrowser(PendingMethod p,OwnedMethodBrowser.Root root){
        return browserPhase(p,pending->browserStore().consumeBrowserFirstInTransaction(pending,root,"c".repeat(64),"d".repeat(64),browserNow()));
    }

    @Test void browserEnrollmentPinsExactOriginalSessionAndReservedSetupWithoutFinancialEffects(){
        var p=pendingMethod(true);var r=p.reservation();var original=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)p.context().actor()).session();
        var before=financialState();var operation=lifecycleOperations.find(site,current().id(),r.setupOperationId()).orElseThrow();int calls=http.calls.size();
        var pending=browserPhase(p,value->value);
        assertThat(pending.enrollment()).isEqualTo(new OwnedMethodBrowser.Enrollment(original.sessionId().value(),original.origin(),original.expiresAt()));
        assertThat(pending.setupStep()).isEqualTo(p.setupStep());assertThat(pending.setup().method()).isNull();assertThat(pending.operation()).isEqualTo(operation);
        assertThat(operation.status()).isEqualTo(SubscriptionOperation.Status.RESERVED);assertThat(operation.version()).isZero();
        assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @ParameterizedTest @ValueSource(booleans={true,false}) void browserEnrollmentCannotBeChangedRemovedOrAddedLater(boolean enrolled){
        var p=pendingMethod(enrolled);var before=rows("stripe_billing_failed_method_reservations");
        for(var mutation:List.of("browser_session_id=null,browser_origin=null,browser_session_expires_at=null","browser_origin='https://foreign.test'",
            "browser_session_id='11111111-1111-1111-1111-111111111111',browser_origin='https://shop.test',browser_session_expires_at=clock_timestamp()+interval '10 minutes'")){
            assertThatThrownBy(()->transaction.requiresNew(()->jdbc.update("update stripe_billing_failed_method_reservations set "+mutation+" where id=?",p.reservation().id()))).isInstanceOf(RuntimeException.class);
        }
        assertThat(rows("stripe_billing_failed_method_reservations")).isEqualTo(before);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void failedBrowserEnrollmentRollsBackClaimAndSnapshotWithoutStoppingAdmission(){
        var s=setup();fail(s,service(store(),null));var before=financialState();long operationsBefore=count("subscription_operations");var real=browserStore();
        var broken=(dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore.class},(proxy,method,args)->{
            try{var result=method.invoke(real,args);if(method.getName().equals("reserveBrowserInTransaction"))throw new IllegalStateException("after browser enrollment");return result;}
            catch(InvocationTargetException e){throw e.getCause();}
        });
        var context=managementContext("browser-rollback");assertThatThrownBy(()->methodService(broken).reserveBrowserMethod(s.admission(),context,true)).hasMessage("after browser enrollment");
        assertThat(count("stripe_billing_failed_method_reservations")).isZero();assertThat(count("subscription_operations")).isEqualTo(operationsBefore);
        assertThat(financialState()).isEqualTo(before);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"session","origin","missing-scope","non-browser"}) void browserPendingRefusesWrongOriginalAuthorityWithoutStop(String mutation){
        var p=pendingMethod(!mutation.equals("non-browser"));var s=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)p.context().actor()).session();
        dev.genericpayments.platform.customers.application.model.CustomerSessionContext changed;
        if(mutation.equals("session")){
            // Issue a real second session at the original issuance time so UUID is the only changed identity.
            TestClock.set(jdbc.queryForObject("select issued_at from customer_sessions where id=?",java.sql.Timestamp.class,s.sessionId().value()).toInstant());
            var second=sessions.issue(site,customer,s.origin());changed=sessions.resolveRequired(second.rawToken(),s.origin());TestClock.set(Instant.now());
            assertThat(changed.sessionId()).isNotEqualTo(s.sessionId());assertThat(changed.expiresAt()).isEqualTo(s.expiresAt());
            assertThat(changed.siteId()).isEqualTo(s.siteId());assertThat(changed.customerId()).isEqualTo(s.customerId());
            assertThat(changed.origin()).isEqualTo(s.origin());assertThat(changed.scopes()).isEqualTo(s.scopes());
            assertThat(transaction.requiresNew(()->sessions.revalidate(changed,site,changed.origin(),dev.genericpayments.platform.customers.domain.CustomerSessionScope.SUBSCRIPTION_MANAGE))).isEqualTo(changed);
        }else changed=new dev.genericpayments.platform.customers.application.model.CustomerSessionContext(s.sessionId(),s.siteId(),s.customerId(),
            mutation.equals("origin")?"https://foreign.test":s.origin(),mutation.equals("missing-scope")?Set.of(dev.genericpayments.platform.customers.domain.CustomerSessionScope.SUBSCRIPTION_READ):s.scopes(),s.expiresAt());
        var context=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer(changed),site,customer,changed.origin(),null,"browser-get");
        var before=financialState();assertThatThrownBy(()->OwnedMethodBrowserTestAccess.phase(p.service(),p.reservation(),context,value->value)).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(before);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(count("stripe_billing_method_browser_roots")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"operation","step","version","reference","future","interval"}) void browserRootRejectsNonOriginalProofOrTime(String mutation){
        var p=pendingMethod(true);var at=browserNow();var expires=at.plusSeconds(600);var session=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)p.context().actor()).session();
        if(session.expiresAt().isBefore(expires))expires=session.expiresAt();var expiry=expires;
        assertThatThrownBy(()->browserPhase(p,pending->jdbc.update("""
            insert into stripe_billing_method_browser_roots(id,reservation_id,setup_operation,setup_step,step_version,payment_account_id,
             setup_reference,key_version,digest,issued_at,expires_at) values(?,?,?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),p.reservation().id(),mutation.equals("operation")?UUID.randomUUID():pending.operation().id(),
            mutation.equals("step")?UUID.randomUUID():pending.setupStep().id(),mutation.equals("version")?1:2,billing.id().value(),
            mutation.equals("reference")?"seti_foreign":pending.setup().reference(),"v1","a".repeat(64),java.sql.Timestamp.from(mutation.equals("future")?at.plusSeconds(60):at),
            java.sql.Timestamp.from(mutation.equals("interval")?expiry.plusSeconds(1):expiry)))).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_method_browser_roots")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void browserOneShotSelectionReleaseConsumptionHaveDistinctPrestatesAndExactReplay(){
        var p=pendingMethod(true);var before=financialState();var root=browserRoot(p);int calls=http.calls.size();
        browserPhase(p,pending->{browserStore().requireBrowserActionInTransaction(pending,root,browserNow());return null;});
        assertThatThrownBy(()->consumeBrowser(p,root)).isInstanceOf(RuntimeException.class);
        var selected=selectBrowser(p,root);
        assertThatThrownBy(()->browserPhase(p,pending->{browserStore().requireBrowserActionInTransaction(pending,root,browserNow());return null;})).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->selectBrowser(p,root)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->consumeBrowser(p,root)).isInstanceOf(RuntimeException.class);
        releaseBrowser(p,root,selected);assertThatThrownBy(()->releaseBrowser(p,root,selected)).isInstanceOf(RuntimeException.class);
        assertThat(consumeBrowser(p,root)).isTrue();assertThat(consumeBrowser(p,root)).isFalse();
        for(var bad:List.of("key","body"))assertThatThrownBy(()->browserPhase(p,pending->browserStore().consumeBrowserFirstInTransaction(pending,root,
            bad.equals("key")?"e".repeat(64):"c".repeat(64),bad.equals("body")?"e".repeat(64):"d".repeat(64),browserNow()))).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_method_browser_selections")).isEqualTo(1);assertThat(count("stripe_billing_method_browser_releases")).isEqualTo(1);assertThat(count("stripe_billing_method_browser_consumptions")).isEqualTo(1);
        assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void browserReceiptsAreAppendOnlyUniqueAndConsumptionRollbackKeepsTheReleasedRoot(){
        var p=pendingMethod(true);var root=browserRoot(p);
        assertThatThrownBy(()->browserPhase(p,pending->browserStore().insertBrowserRootInTransaction(pending,root))).isInstanceOf(RuntimeException.class);
        var selected=selectBrowser(p,root);releaseBrowser(p,root,selected);
        assertThatThrownBy(()->browserPhase(p,pending->{assertThat(browserStore().consumeBrowserFirstInTransaction(pending,root,"c".repeat(64),"d".repeat(64),browserNow())).isTrue();throw new IllegalStateException("after consume");})).hasMessage("after consume");
        assertThat(count("stripe_billing_method_browser_consumptions")).isZero();assertThat(consumeBrowser(p,root)).isTrue();
        for(var table:List.of("stripe_billing_method_browser_roots","stripe_billing_method_browser_selections","stripe_billing_method_browser_releases","stripe_billing_method_browser_consumptions")){
            var before=rows(table);assertThatThrownBy(()->transaction.requiresNew(()->jdbc.update("delete from "+table))).isInstanceOf(RuntimeException.class);
            String field=table.endsWith("roots")?"issued_at=issued_at":table.endsWith("selections")?"started_at=started_at":table.endsWith("releases")?"released_at=released_at":"consumed_at=consumed_at";
            assertThatThrownBy(()->transaction.requiresNew(()->jdbc.update("update "+table+" set "+field))).isInstanceOf(RuntimeException.class);assertThat(rows(table)).isEqualTo(before);
        }
    }
    @Test void browserEnrollmentDeniesDirectBindingPreboundInsertAndMethodAdmissionUntilHistoricalConsumption(){
        var p=pendingMethod(true,5);var proof=methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId()).orElseThrow();var before=financialState();
        assertThatThrownBy(()->methodBillingProofs.bindMethod(proof,"pm_saved")).isInstanceOf(RuntimeException.class).hasStackTraceContaining("Owned method browser consumption required");
        assertThatThrownBy(()->operations.reserveOwnedFailedRenewalMethodStep(billing,p.reservation(),ProviderOperation.Kind.SUBSCRIPTION_UPDATE)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->transaction.requiresNew(()->{
            jdbc.update("delete from stripe_subscription_setup_proofs where operation_id=?",proof.operation());
            return jdbc.update("""
                insert into stripe_subscription_setup_proofs(operation_id,site_id,customer_id,subscription_id,payment_account_id,operation_kind,step_id,setup_reference,method_reference)
                values(?,?,?,?,?,'SETUP',?,?,'pm_saved')
                """,proof.operation(),site.value(),customer.value(),proof.subscription(),billing.id().value(),proof.step(),proof.reference());
        })).isInstanceOf(RuntimeException.class).hasStackTraceContaining("Owned method browser consumption required");
        assertThatThrownBy(()->transaction.requiresNew(()->jdbc.update("""
            insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint,status,version)
            select ?,payment_account_id,external_account_id,credentials_ref,'SUBSCRIPTION_UPDATE',resource_id,operation_key||':BYPASS',fingerprint,'RESERVED',0 from provider_operations where id=?
            """,UUID.randomUUID(),p.setupStep().id()))).isInstanceOf(RuntimeException.class).hasStackTraceContaining("Owned method browser consumption required");
        var root=browserRoot(p);var selected=selectBrowser(p,root);releaseBrowser(p,root,selected);assertThat(consumeBrowser(p,root)).isTrue();
        var untilExpiry=Duration.between(Instant.now(),root.expiresAt()).plusMillis(50);
        try{if(!untilExpiry.isNegative())Thread.sleep(untilExpiry);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        var session=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)p.context().actor()).session();
        jdbc.update("update customer_sessions set revoked_at=clock_timestamp(),revoked_reason='LOGOUT' where id=?",session.sessionId().value());
        jdbc.update("delete from customer_sessions where id=?",session.sessionId().value());
        assertThat(jdbc.queryForObject("select browser_session_id from stripe_billing_failed_method_reservations where id=?",UUID.class,p.reservation().id())).isEqualTo(session.sessionId().value());
        assertThat(methodBillingProofs.bindMethod(proof,"pm_saved").method()).isEqualTo("pm_saved");
        assertThat(operations.reserveOwnedFailedRenewalMethodStep(billing,p.reservation(),ProviderOperation.Kind.SUBSCRIPTION_UPDATE).status()).isEqualTo(ProviderOperation.Status.RESERVED);
        assertThat(financialState()).isEqualTo(before);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"selection","release","consume"}) void browserReceiptsRefuseFutureCallerTime(String phase){
        var p=pendingMethod(true);var root=browserRoot(p);var future=browserNow().plusSeconds(60);
        if(phase.equals("selection")){
            assertThatThrownBy(()->browserPhase(p,pending->browserStore().startBrowserSelectionInTransaction(pending,root,"b".repeat(64),future))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->transaction.requiresNew(()->jdbc.update("insert into stripe_billing_method_browser_selections(root_id,key_digest,started_at,deadline_at) values(?,?,?,?)",
                root.id(),"b".repeat(64),java.sql.Timestamp.from(future),java.sql.Timestamp.from(future.plusSeconds(30))))).isInstanceOf(RuntimeException.class);
        }
        else {var selected=selectBrowser(p,root);
            if(phase.equals("release")){
                assertThatThrownBy(()->browserPhase(p,pending->{browserStore().finishBrowserSelectionInTransaction(pending,root,selected,future);return null;})).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(()->transaction.requiresNew(()->jdbc.update("insert into stripe_billing_method_browser_releases(root_id,released_at) values(?,?)",root.id(),java.sql.Timestamp.from(future)))).isInstanceOf(RuntimeException.class);
            }
            else {releaseBrowser(p,root,selected);
                assertThatThrownBy(()->browserPhase(p,pending->browserStore().consumeBrowserFirstInTransaction(pending,root,"c".repeat(64),"d".repeat(64),future))).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(()->transaction.requiresNew(()->jdbc.update("insert into stripe_billing_method_browser_consumptions(root_id,key_digest,request_fingerprint,consumed_at) values(?,?,?,?)",root.id(),"c".repeat(64),"d".repeat(64),java.sql.Timestamp.from(future)))).isInstanceOf(RuntimeException.class);
            }
        }
        assertThat(count("stripe_billing_method_browser_consumptions")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @ParameterizedTest @ValueSource(booleans={true,false}) void browserSelectionDeadlineControlsReleaseAndNotReleasedCustomerInput(boolean released) throws Exception {
        var p=pendingMethod(true);var root=browserRoot(p);var selected=selectBrowser(p,root);if(released)releaseBrowser(p,root,selected);
        var wait=Duration.between(Instant.now(),selected.deadlineAt()).plusMillis(50);if(!wait.isNegative())Thread.sleep(wait);
        if(released)assertThat(consumeBrowser(p,root)).isTrue();
        else {assertThatThrownBy(()->releaseBrowser(p,root,selected)).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->consumeBrowser(p,root)).isInstanceOf(RuntimeException.class);}
        assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void browserSelectionRefusesExpiredOpportunityWhileRootLiveWithoutBurningFreshSelection() throws Exception {
        var p=pendingMethod(true);var root=browserRoot(p);var stale=browserNow();
        var wait=Duration.between(Instant.now(),stale.plusSeconds(30)).plusMillis(50);if(!wait.isNegative())Thread.sleep(wait);
        assertThat(Instant.now()).isBefore(root.expiresAt());
        assertThatThrownBy(()->browserPhase(p,pending->browserStore().startBrowserSelectionInTransaction(pending,root,"b".repeat(64),stale)))
            .isInstanceOf(RuntimeException.class).hasStackTraceContaining("Invalid owned method browser selection");
        assertThat(count("stripe_billing_method_browser_selections")).isZero();
        var fresh=selectBrowser(p,root);assertThat(fresh.startedAt()).isAfterOrEqualTo(stale.plusSeconds(30));
        assertThat(fresh.deadlineAt()).isEqualTo(fresh.startedAt().plusSeconds(30));assertThat(count("stripe_billing_method_browser_selections")).isEqualTo(1);
        assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void browserReleaseMustCommitBeforeConsumptionEvenAcrossSavepoints(){
        var p=pendingMethod(true);var root=browserRoot(p);var selected=selectBrowser(p,root);
        assertThatThrownBy(()->browserPhase(p,pending->{
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{var savepoint=connection.setSavepoint();
                browserStore().finishBrowserSelectionInTransaction(pending,root,selected,browserNow());connection.releaseSavepoint(savepoint);return null;});
            return browserStore().consumeBrowserFirstInTransaction(pending,root,"c".repeat(64),"d".repeat(64),browserNow());
        })).isInstanceOf(RuntimeException.class).hasStackTraceContaining("Missing owned method browser release");
        assertThat(count("stripe_billing_method_browser_releases")).isZero();assertThat(count("stripe_billing_method_browser_consumptions")).isZero();
        assertThatThrownBy(()->transaction.requiresNew(()->jdbc.update("insert into stripe_billing_method_browser_releases(root_id,released_at,recorded_transaction) values(?,?, '1'::xid8)",root.id(),java.sql.Timestamp.from(browserNow())))).isInstanceOf(RuntimeException.class);
        releaseBrowser(p,root,selected);assertThat(consumeBrowser(p,root)).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"binding","method"}) void browserConsumptionMustCommitBeforeDownstreamEvenAcrossSavepoints(String next){
        var p=pendingMethod(true);var root=browserRoot(p);releaseBrowser(p,root,selectBrowser(p,root));
        var proof=methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId()).orElseThrow();
        assertThatThrownBy(()->browserPhase(p,pending->{
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{var savepoint=connection.setSavepoint();
                assertThat(browserStore().consumeBrowserFirstInTransaction(pending,root,"c".repeat(64),"d".repeat(64),browserNow())).isTrue();connection.releaseSavepoint(savepoint);return null;});
            if(next.equals("binding"))return jdbc.update("update stripe_subscription_setup_proofs set method_reference='pm_saved' where operation_id=?",proof.operation());
            return jdbc.update("""
                insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint,status,version)
                select ?,payment_account_id,external_account_id,credentials_ref,'SUBSCRIPTION_UPDATE',resource_id,operation_key||':BYPASS',fingerprint,'RESERVED',0 from provider_operations where id=?
                """,UUID.randomUUID(),p.setupStep().id());
        })).isInstanceOf(RuntimeException.class).hasStackTraceContaining("Owned method browser consumption required");
        assertThat(count("stripe_billing_method_browser_consumptions")).isZero();assertThat(methodBillingProofs.setup(billing.id(),proof.operation()).orElseThrow().method()).isNull();
        assertThat(consumeBrowser(p,root)).isTrue();
    }
    @Test void browserConsumptionSerializesConcurrentIdenticalRequestsAndChangedReplayRefuses() throws Exception {
        var p=pendingMethod(true);var root=browserRoot(p);releaseBrowser(p,root,selectBrowser(p,root));var started=new CountDownLatch(2);var begin=new CountDownLatch(1);
        try(var workers=new BoundedWorkers()){
            var first=workers.submit(()->{started.countDown();awaitRelease(begin);return consumeBrowser(p,root);});
            var second=workers.submit(()->{started.countDown();awaitRelease(begin);return consumeBrowser(p,root);});
            try{assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();begin.countDown();assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}
            finally{begin.countDown();}
        }
        assertThat(count("stripe_billing_method_browser_consumptions")).isEqualTo(1);
        assertThatThrownBy(()->browserPhase(p,pending->browserStore().consumeBrowserFirstInTransaction(pending,root,"e".repeat(64),"d".repeat(64),browserNow()))).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void browserConsumptionRechecksDatabaseExpiryAfterWaitingForTheOriginalOperationLock() throws Exception {
        var p=pendingMethod(true,5);var root=browserRoot(p);releaseBrowser(p,root,selectBrowser(p,root));var stale=browserNow();
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var attempting=new CountDownLatch(1);
        try(var workers=new BoundedWorkers()){
            var blocker=workers.submit(()->transaction.requiresNew(()->{lifecycleOperations.lock(p.reservation().setupOperationId());locked.countDown();awaitRelease(release);return true;}));
            try{
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                var loser=workers.submit(()->{attempting.countDown();assertThatThrownBy(()->transaction.requiresNew(()->{
                    // A stale pre-wait trusted timestamp must not bypass the post-lock database clock.
                    jdbc.update("insert into stripe_billing_method_browser_consumptions(root_id,key_digest,request_fingerprint,consumed_at) values(?,?,?,?)",root.id(),"c".repeat(64),"d".repeat(64),java.sql.Timestamp.from(stale));return true;
                })).isInstanceOf(RuntimeException.class);return true;});
                assertThat(attempting.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->loser.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                var wait=Duration.between(Instant.now(),root.expiresAt()).plusMillis(100);if(!wait.isNegative())Thread.sleep(wait);
                release.countDown();assertThat(blocker.get(10,TimeUnit.SECONDS)).isTrue();assertThat(loser.get(10,TimeUnit.SECONDS)).isTrue();
            }finally{release.countDown();}
        }
        assertThat(count("stripe_billing_method_browser_consumptions")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void browserRootAndTypedProviderReplayShareOperationBeforeProviderReceiptLockOrder() throws Exception {
        var p=pendingMethod(true);var locked=new CountDownLatch(1);var insert=new CountDownLatch(1);var attempting=new CountDownLatch(1);
        try(var workers=new BoundedWorkers()){
            var root=workers.submit(()->browserPhase(p,pending->{locked.countDown();awaitRelease(insert);
                var now=browserNow();var expiry=now.plusSeconds(600);if(pending.enrollment().sessionExpiresAt().isBefore(expiry))expiry=pending.enrollment().sessionExpiresAt();
                return browserStore().insertBrowserRootInTransaction(pending,new OwnedMethodBrowser.Root(UUID.randomUUID(),p.reservation().id(),pending.operation().id(),pending.setupStep().id(),2,pending.setup().reference(),"v1","a".repeat(64),now,expiry));}));
            try{
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                var replay=workers.submit(()->{attempting.countDown();assertThatThrownBy(()->operations.acknowledge(p.setupStep().id(),"terminal-replay",1,p.setupStep().providerReference()))
                    .isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,e->{
                        assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT");assertThat(e.status()).isEqualTo(409);
                        assertThat(e).hasMessage("Provider operation conflicts with the request").hasNoCause();
                    });return true;});
                assertThat(attempting.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->replay.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                insert.countDown();assertThat(root.get(10,TimeUnit.SECONDS).setupStep()).isEqualTo(p.setupStep().id());assertThat(replay.get(10,TimeUnit.SECONDS)).isTrue();
            }finally{insert.countDown();}
        }
        assertThat(count("stripe_billing_method_browser_roots")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    private com.stripe.net.RequestOptions writeOptions(String key) {
        var builder=com.stripe.net.RequestOptions.builder().setIdempotencyKey(key).setConnectTimeout(2000).setReadTimeout(2000).setMaxNetworkRetries(0);
        com.stripe.net.RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(builder,"2026-09-30.endive");return builder.build();
    }
    private String setupBody(SubscriptionOperation o,ProviderOperation step,String status) {
        var body=new com.google.gson.JsonObject();body.addProperty("id","seti_saved");body.addProperty("object","setup_intent");body.addProperty("livemode",false);
        body.addProperty("customer",receipt.intent().customerReference());body.addProperty("usage","off_session");body.addProperty("status",status);
        body.add("payment_method_types",com.google.gson.JsonParser.parseString("[\"card\"]"));body.addProperty("created",step.firstDispatchedAt().getEpochSecond());
        if(status.equals("succeeded"))body.addProperty("payment_method","pm_saved");
        var metadata=new com.google.gson.JsonObject();metadata.addProperty("operation_id",o.id().toString());metadata.addProperty("subscription_id",o.subscriptionId().toString());body.add("metadata",metadata);return body.toString();
    }
    private String methodBody(){return "{\"id\":\"pm_saved\",\"object\":\"payment_method\",\"livemode\":false,\"customer\":\""+receipt.intent().customerReference()+"\",\"type\":\"card\",\"card\":{\"last4\":\"4242\"}}";}
    private void methodGraph(PreparedMethod p) {
        failedGraph(p.setup());replaceFailedReply(2,j->j.addProperty("default_payment_method","pm_saved"));
        var queue=new ArrayList<>(http.replies);var end=queue.removeLast();
        var o=lifecycleOperations.find(site,current().id(),p.reservation().setupOperationId()).orElseThrow();
        queue.add(new com.stripe.net.StripeResponse(200,com.stripe.net.HttpHeaders.of(Map.of()),setupBody(o,p.setupStep(),"succeeded")));
        queue.add(new com.stripe.net.StripeResponse(200,com.stripe.net.HttpHeaders.of(Map.of()),methodBody()));queue.add(end);
        http.replies.clear();http.replies.addAll(queue);assertThat(http.replies).hasSize(17);
    }
    private void recoveredGraph(PreparedMethod p) {
        assertThat(http.replies).isEmpty();graph.remote.addProperty("status","active");graph(p.setup().ready(),true);
        for(int i:new int[]{10,13})replaceFailedReply(i,j->j.getAsJsonArray("data").get(0).getAsJsonObject().addProperty("is_default",true));assertThat(http.replies).hasSize(15);
    }
    private StripeBillingOwnedFailedRenewalMethodObserver methodObserver(OwnedFailedRenewalMethodService svc) {
        return new StripeBillingOwnedFailedRenewalMethodObserver(svc,credentials,clients,digest,Clock.systemUTC());
    }
    private void assertObservationSlice(PreparedMethod p,int start,int count) {
        var calls=http.calls.subList(start,http.calls.size());assertThat(calls).hasSize(count).allSatisfy(r->{
            assertThat(r.method().name()).isEqualTo("GET");assertThat(r.options().getMaxNetworkRetries()).isZero();
            assertThat(r.options().getReadTimeout()).isBetween(1,2000);assertThat(r.options().getConnectTimeout()).isBetween(1,2000);
            assertThat(r.headers().firstValue("Stripe-Version")).contains("2026-09-30.endive");});
        var paths=new ArrayList<>(List.of("/v1/test_helpers/test_clocks/clock_owned","/v1/customers/"+receipt.intent().customerReference(),"/v1/subscriptions/"+receipt.reference(),"/v1/invoices",
            "/v1/invoices/"+p.setup().upstream().invoice()+"/lines","/v1/invoice_payments","/v1/payment_intents/"+p.setup().upstream().paymentIntent(),"/v1/charges/"+graph.charge.get("id").getAsString(),
            "/v1/invoice_payments","/v1/invoices/in_renewal/lines","/v1/invoice_payments","/v1/payment_intents/pi_renewal","/v1/charges/ch_renewal","/v1/invoice_payments"));
        if(count==17){paths.add("/v1/setup_intents/seti_saved");paths.add("/v1/payment_methods/pm_saved");}paths.add("/v1/test_helpers/test_clocks/clock_owned");
        assertThat(calls.stream().map(r->r.url().getPath()).toList()).isEqualTo(paths);assertThat(http.replies).isEmpty();
    }
    @Test void genuinePreparedMethodObserverCompletesThenSuccessorObserverPaysOnlyOriginalDebt() {
        var p=preparedMethod();var r=p.reservation();var original=jdbc.queryForMap("select * from payments where id=?",p.setup().upstream().payment());
        var before=financialState();var subscription=current();long sequence=observations.captureFence(subscription);int start=http.calls.size();methodGraph(p);
        var completion=methodObserver(p.service()).observeMethod(r);assertObservationSlice(p,start,17);
        assertThat(current()).isEqualTo(subscription);assertThat(observations.captureFence(current())).isEqualTo(sequence+1);
        var afterMethod=new TreeMap<>(financialState());afterMethod.put("sequence",before.get("sequence"));assertThat(afterMethod).isEqualTo(before);
        assertThat(completion.reservationId()).isEqualTo(r.id());assertThat(completion.observationSequence()).isEqualTo(p.failure().observationSequence()+1);
        assertThat(lifecycleOperations.find(site,current().id(),r.setupOperationId()).orElseThrow().status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(transaction.required(()->new JdbcOwnedFailedRenewalMethodStore(jdbc).requireCompletionInTransaction(r,completion))).isEqualTo(completion);
        assertThat(count("payments")).isEqualTo(1);var beforeRecovery=financialState();var recovery=p.service().reserveRecovery(r,completion);assertThat(financialState()).isEqualTo(beforeRecovery);
        start=http.calls.size();recoveredGraph(p);var publication=methodObserver(p.service()).observeRecovery(r,recovery);assertObservationSlice(p,start,15);
        assertThat(publication.publication().billId()).isEqualTo(p.failure().billId());assertThat(publication.observationSequence()).isEqualTo(p.failure().observationSequence()+2);
        assertThat(current().revision()).isEqualTo(p.failure().revision()+1);assertThat(current().debt()).isNull();assertThat(current().paidThrough()).isEqualTo(p.failure().period().end());
        assertThat(count("payments")).isEqualTo(2);assertThat(jdbc.queryForMap("select * from payments where id=?",p.setup().upstream().payment())).isEqualTo(original);
        assertThat(jdbc.queryForObject("select provider_payment_reference from payments where id=?",String.class,publication.publication().paymentId())).isEqualTo(p.failure().paymentIntent());
        assertThat(jdbc.queryForObject("select settlement from subscription_billing_records where id=?",String.class,p.failure().billId())).isEqualTo("CONFIRMED_CASH");
        assertThat(count("stripe_billing_failed_method_completions")).isEqualTo(1);assertThat(count("stripe_billing_failed_method_publications")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='INVOICE_PAY'",Long.class)).isZero();
        var finalState=financialState();int calls=http.calls.size();assertThatThrownBy(()->methodObserver(p.service()).observeRecovery(r,recovery)).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(finalState);assertThat(http.calls).hasSize(calls);assertThat(count("payments")).isEqualTo(2);
    }

    @Test void typedStepFingerprintsMatchJavaAndSqlEncodingIncludingUnicode() {
        var p=preparedMethod();var r=p.reservation();var o=lifecycleOperations.find(site,current().id(),r.setupOperationId()).orElseThrow();var s=current();var binding=r.binding();
        String setup=StripeRequestFingerprint.of(digest,billing,"SETUP_INTENT_CREATE_V1",o.id().toString(),o.providerOperationKey()+":SETUP",o.fingerprint(),Long.toString(o.policyVersion()),
            s.id().toString(),s.siteId().value().toString(),s.customerId().value().toString(),binding.providerReference(),"off_session","card","consent:true");
        String method=StripeRequestFingerprint.of(digest,billing,"SUBSCRIPTION_METHOD_V1",o.id().toString(),o.providerOperationKey()+":METHOD",o.fingerprint(),
            s.providerReference(),binding.providerReference(),p.setupStep().id().toString(),"seti_saved","pm_saved","consent:true");
        assertThat(p.setupStep().fingerprint()).isEqualTo(setup);assertThat(p.methodStep().fingerprint()).isEqualTo(method);
        assertThat(jdbc.queryForObject("select owned_method_step_fingerprint(?,false)",String.class,r.id())).isEqualTo(setup);
        assertThat(jdbc.queryForObject("select owned_method_step_fingerprint(?,true)",String.class,r.id())).isEqualTo(method);
        String[] facts={null,"","é漢","emoji😀𐐷"};
        String expected=StripeRequestFingerprint.of(digest,billing,facts);
        assertThat(jdbc.queryForObject("select owned_method_stripe_hash(?,?,?,?,?,?,?,?,?)",String.class,billing.id().value().toString(),billing.externalAccountId(),billing.credentialsRef(),
            billing.provider().name(),billing.mode().name(),facts[0],facts[1],facts[2],facts[3])).isEqualTo(expected);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void authenticObserverReceiptFailureRollsBackFactsLifecycleSequenceAndCashBeforeOneStop(boolean recovered) {
        var p=preparedMethod();var r=p.reservation();OwnedFailedRenewalMethod.Recovery recovery=null;
        if(recovered){methodGraph(p);var completion=methodObserver(p.service()).observeMethod(r);recovery=p.service().reserveRecovery(r,completion);}
        var real=new JdbcOwnedFailedRenewalMethodStore(jdbc);
        var broken=(dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore.class},(proxy,method,args)->{
            try {var result=method.invoke(real,args);if(method.getName().equals(recovered?"recordPublicationInTransaction":"recordCompletionInTransaction"))throw new IllegalStateException("synthetic post-receipt failure");return result;}
            catch(InvocationTargetException failure){throw failure.getCause();}
        });
        var before=financialState();var setup=lifecycleOperations.find(site,current().id(),r.setupOperationId()).orElseThrow();
        var selected=recovered?lifecycleOperations.find(site,current().id(),recovery.association().operationId()).orElseThrow():setup;
        var successor=recovery;int start=http.calls.size();if(recovered)recoveredGraph(p);else methodGraph(p);
        var observer=methodObserver(methodService(broken));
        assertThatThrownBy(()->{if(recovered)observer.observeRecovery(r,successor);else observer.observeMethod(r);})
            .isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,e->{assertThat(e.code()).isEqualTo("OWNED_FAILED_RENEWAL_METHOD_UNAVAILABLE");assertThat(e).hasMessage("Owned method observation unavailable").hasNoCause();});
        assertObservationSlice(p,start,recovered?15:17);assertThat(financialState()).isEqualTo(before);
        assertThat(lifecycleOperations.find(site,current().id(),selected.id())).contains(selected);assertThat(lifecycleOperations.find(site,current().id(),setup.id())).contains(setup);
        assertThat(count("stripe_billing_failed_method_completions")).isEqualTo(recovered?1:0);assertThat(count("stripe_billing_failed_method_publications")).isZero();
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);assertThat(count("payments")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"version","sequence","expired","forged-fence"})
    void preparedMethodPublicationRejectsActualVersionSequenceDeadlineAndCapturedFenceDrift(String mutation) throws InterruptedException {
        var p=preparedMethod();var r=p.reservation();var read=p.service().beginMethodRead(r,UUID.randomUUID());var context=p.service().inspectMethodRead(r,read);
        if(mutation.equals("version"))jdbc.update("update subscription_operations set version=version+1,updated_at=clock_timestamp() where id=?",r.setupOperationId());
        if(mutation.equals("sequence"))jdbc.update("update subscriptions set observation_sequence=observation_sequence+1 where id=?",current().id());
        var q=read;
        if(mutation.equals("forged-fence"))q=new OwnedFailedRenewalMethod.ReadClaim(read.id(),read.reservationId(),read.phase(),read.invocation(),read.startedAt(),read.deadlineAt(),
            new ApplySubscriptionSnapshotUseCase.Fence(read.fence().revision(),read.fence().operationId(),read.fence().operationVersion()+1,null,null,read.fence().observationSequence()),read.billingSample());
        var expected=q;
        var before=financialState();var operation=lifecycleOperations.find(site,current().id(),r.setupOperationId()).orElseThrow();var now=Instant.now();
        var evidence=new OwnedFailedRenewalMethod.MethodEvidence(snapshot(p.setup(),false,null,now),"seti_saved","pm_saved",now);
        read.requireAccepted(evidence.snapshot().observedAt(),evidence.acceptedAt());
        if(mutation.equals("expired")) {
            assertThat(read.deadlineAt()).isEqualTo(read.startedAt().plusSeconds(30));
            long remaining=Math.max(0,Duration.between(Instant.now(),read.deadlineAt()).toNanos());
            assertThat(remaining).isBetween(0L,TimeUnit.SECONDS.toNanos(30));
            // Wait only for this genuine claim. A future Java clock would also send an invalid future stop to PostgreSQL.
            // Interrupting this bounded hosted-only wait fails the test; no deadline or persisted authority is changed.
            TimeUnit.NANOSECONDS.sleep(remaining+TimeUnit.MILLISECONDS.toNanos(50));
            assertThat(Instant.now()).isAfterOrEqualTo(read.deadlineAt());
            assertThat(jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant()).isAfterOrEqualTo(read.deadlineAt());
        }
        assertThatThrownBy(()->p.service().publishMethod(r,expected,evidence,context)).isInstanceOf(RuntimeException.class).hasNoSuppressedExceptions();
        assertThat(financialState()).isEqualTo(before);assertThat(lifecycleOperations.find(site,current().id(),operation.id())).contains(operation);
        assertThat(count("stripe_billing_failed_method_completions")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);
    }
    @Test void preparedReadDetectsCorruptedSavedProviderSnapshotAndRollsBackTheCorruption() {
        var p=preparedMethod();var methods=new JdbcOwnedFailedRenewalMethodStore(jdbc);var r=p.reservation();var q=p.service().beginMethodRead(r,UUID.randomUUID());
        var before=financialState();var reads=rows("stripe_billing_failed_method_reads");
        assertThatThrownBy(()->transaction.required(()->{
            jdbc.execute("alter table stripe_billing_failed_method_reads disable trigger failed_method_read_immutable");
            jdbc.update("update stripe_billing_failed_method_reads set method_step_snapshot=jsonb_set(method_step_snapshot,'{fingerprint}','\"corrupted\"'::jsonb) where id=?",q.id());
            return methods.requireReadInTransaction(r,q);
        })).hasMessage("Owned method transition authority unavailable");
        assertThat(rows("stripe_billing_failed_method_reads")).isEqualTo(reads);assertThat(financialState()).isEqualTo(before);
        assertThat(p.service().inspectMethodRead(r,q).methodStep()).isEqualTo(p.methodStep());assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void callerAppliedMarkerCannotUseRealMethodFactCompositionWhileSetupIsStillReserved(){
        var s=setup();fail(s,service(store(),null));var methods=new JdbcOwnedFailedRenewalMethodStore(jdbc);var r=methodService(methods).reserveMethod(s.admission(),managementContext("marker-method-consent"),true);
        var before=financialState();var op=lifecycleOperations.find(site,current().id(),r.setupOperationId()).orElseThrow();var raw=snapshot(s,false,null,Instant.now());
        var marked=new Snapshot(raw.subscriptionReference(),raw.status(),raw.billingPrice(),raw.period(),null,null,raw.invoices(),op.providerOperationKey(),true,0,raw.observedAt(),new OperationObservation(op.providerOperationKey(),OperationState.APPLIED));
        var fence=new ApplySubscriptionSnapshotUseCase.Fence(r.failure().revision(),op.id(),op.version(),null,null,r.failure().observationSequence());
        assertThatThrownBy(()->transaction.requiresNew(()->{methodFacts(methods).apply(new ApplySubscriptionSnapshotUseCase.Command(billing,current().id(),marked,SubscriptionEvent.Source.RECONCILIATION,"forged-method-claim","negative",fence,r.billingSample()));return null;})).hasMessage("ISOLATED_BILLING_UNSUPPORTED");
        assertThat(financialState()).isEqualTo(before);assertThat(lifecycleOperations.find(site,current().id(),op.id())).contains(op);assertThat(count("stripe_billing_failed_method_completions")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    private dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Reservation reserveMethod(Setup s,Failure failure) {
        return underLifecycle(s.upstream(),()->{
            var id=UUID.randomUUID();var at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var candidate=new SubscriptionOperation(id,site,customer,current().id(),SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Status.RESERVED,
                "synthetic-management", "a".repeat(64),"v1.setup."+"b".repeat(64),"subscription:"+billing.id().value()+":"+id+":SETUP",
                failure.revision(),s.upstream().policyVersion(),null,null,at,at,0);
            assertThat(lifecycleOperations.claim(candidate).owner()).isTrue();
            return new JdbcOwnedFailedRenewalMethodStore(jdbc).reserveInTransaction(s.admission(),failure,candidate,
                bindings.findCustomer(billing.id(),site,customer).orElseThrow(),new SubscriptionBillingTimePort.Sample(time.selected,SubscriptionBillingTimePort.Mode.ISOLATED),at);
        });
    }
    @Test void methodReservationPinsExactIdentityAndRejectsAppendOnlyMutationAndReadBeforeProviderProofs() {
        var s=setup();var failure=fail(s,service(store(),null));var r=reserveMethod(s,failure);
        var method=new JdbcOwnedFailedRenewalMethodStore(jdbc);
        assertThat(transaction.required(()->method.requireReservationInTransaction(r))).isEqualTo(r);
        var financial=financialState();
        assertThatThrownBy(()->jdbc.update("update stripe_billing_failed_method_reservations set request_fingerprint='changed' where id=?",r.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("delete from stripe_billing_failed_method_reservations where id=?",r.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->transaction.required(()->method.requirePreparedMethodInTransaction(r))).isInstanceOf(RuntimeException.class);
        var fence=new ApplySubscriptionSnapshotUseCase.Fence(failure.revision(),r.setupOperationId(),0L,null,null,failure.observationSequence());
        assertThatThrownBy(()->transaction.required(()->method.claimReadInTransaction(r,
            dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Phase.METHOD,UUID.randomUUID(),fence,r.billingSample(),Instant.now()))).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(financial);assertThat(count("stripe_billing_failed_method_reads")).isZero();
        assertThat(count("stripe_billing_failed_method_completions")).isZero();
        var forged=new dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Reservation(UUID.randomUUID(),r.admission(),r.failure(),r.setupOperationId(),
            r.requestFingerprint(),r.initialOperationVersion(),r.binding(),r.billingSample(),r.reservedAt());
        assertThatThrownBy(()->transaction.required(()->method.requireReservationInTransaction(forged))).isInstanceOf(RuntimeException.class);
    }
    @Test void methodSqlArgumentsAuthenticateTheRequestedReservationAndExactDebtPhase() {
        var s=setup();fail(s,service(store(),null));
        var r=methodService(new JdbcOwnedFailedRenewalMethodStore(jdbc)).reserveMethod(s.admission(),managementContext("sql-method-binding"),true);
        var before=financialState();var receipts=authorityRows();
        // The genuine reservation joins READY2's bigint advance.target; its UUID argument must remain independent.
        assertThat(jdbc.queryForObject("select owned_method_identity(?)",Boolean.class,r.id())).isTrue();
        assertThat(jdbc.queryForObject("select owned_method_lineage(?)",Boolean.class,r.id())).isTrue();
        assertThat(jdbc.queryForObject("select owned_method_initial_operation(?)",Boolean.class,r.id())).isTrue();
        assertThat(jdbc.queryForObject("select owned_method_debt(?,0,?)",Boolean.class,r.id(),r.setupOperationId())).isTrue();
        for(var wrongId:List.of(UUID.randomUUID(),r.admission().id(),r.setupOperationId())) {
            assertThat(jdbc.queryForObject("select owned_method_identity(?)",Boolean.class,wrongId)).isFalse();
            assertThat(jdbc.queryForObject("select owned_method_lineage(?)",Boolean.class,wrongId)).isFalse();
            assertThat(jdbc.queryForObject("select owned_method_initial_operation(?)",Boolean.class,wrongId)).isFalse();
            assertThat(jdbc.queryForObject("select owned_method_debt(?,0,?)",Boolean.class,wrongId,r.setupOperationId())).isFalse();
            assertThat(jdbc.queryForObject("select owned_method_step_fingerprint(?,false)",String.class,wrongId)).isNull();
        }
        assertThat(jdbc.queryForObject("select owned_method_debt(?,0,null)",Boolean.class,r.id())).isFalse();
        for(int successor:new int[]{-1,1,2})assertThat(jdbc.queryForObject("select owned_method_debt(?,?,?)",Boolean.class,r.id(),successor,r.setupOperationId())).isFalse();
        assertThat(jdbc.queryForObject("select owned_method_step_fingerprint(?,false)",String.class,r.id())).matches("[a-f0-9]{64}");
        assertThat(transaction.required(()->new JdbcOwnedFailedRenewalMethodStore(jdbc).requireReservationInTransaction(r))).isEqualTo(r);
        assertThat(financialState()).isEqualTo(before);assertThat(authorityRows()).isEqualTo(receipts);
    }
    @Test void ordinarySetupAndMethodReservationsRemainClosedForOwnedResources() {
        var s=setup();var failure=fail(s,service(store(),null));var r=reserveMethod(s,failure);
        for(var kind:List.of(dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SETUP_INTENT_CREATE,
                dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SUBSCRIPTION_UPDATE)) {
            String key=jdbc.queryForObject("select provider_operation_key from subscription_operations where id=?",String.class,r.setupOperationId())
                +(kind==dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SETUP_INTENT_CREATE?":SETUP":":METHOD");
            String fingerprint=jdbc.queryForObject("select owned_method_step_fingerprint(?,?)",String.class,r.id(),kind==dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
            assertThatThrownBy(()->operations.reserve(billing,kind,r.setupOperationId(),key,fingerprint)).isInstanceOf(RuntimeException.class);
        }
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where resource_id=?",Long.class,r.setupOperationId())).isZero();
        // METHOD cannot be admitted before its already acknowledged/bound setup proof exists.
        assertThatThrownBy(()->operations.reserveOwnedFailedRenewalMethodStep(billing,r,
            dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SUBSCRIPTION_UPDATE)).isInstanceOf(RuntimeException.class);
    }
    @Test void terminalFailedSetupNeverReopensTheLegacyRecoveryBranch() {
        var s=setup();var svc=service(store(),null);var failure=fail(s,svc);var r=reserveMethod(s,failure);
        // Negative branch test only. This terminal failure is not positive method completion evidence.
        jdbc.update("update subscription_operations set status='FAILED',safe_failure_code='SYNTHETIC_FAILURE',version=version+1,updated_at=clock_timestamp() where id=?",r.setupOperationId());
        assertThat(transaction.required(()->new JdbcOwnedFailedRenewalMethodStore(jdbc).requireReservationInTransaction(r))).isEqualTo(r);
        assertThatThrownBy(()->svc.reserveExistingRecovery(s.admission())).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_failed_method_reservations")).isEqualTo(1);
        assertThat(count("stripe_billing_failed_renewal_recoveries")).isZero();
        assertThat(count("stripe_subscription_recovery_proofs")).isZero();
    }
    @Test void ownedMethodDispatchDeniesLeaseThatExpiresWhileWaitingForItsReceiptLock() throws Exception {
        var s=setup();var failure=fail(s,service(store(),null));var r=reserveMethod(s,failure);
        int sdkCalls=http.calls.size();
        var reservedStep=operations.reserveOwnedFailedRenewalMethodStep(billing,r,
            dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SETUP_INTENT_CREATE);
        var claimed=operations.claim(reservedStep.id(),"negative-lock-delay",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        // Negative fixture only: keep the genuine RESERVED -> DISPATCHING version-one receipt but shorten its lease.
        // No successful provider call, ACK, lifecycle completion or old operation is manufactured.
        jdbc.update("update provider_operations set lease_expires_at=first_dispatched_at+interval '1 second' where id=? and version=1",claimed.id());
        var shortened=operations.findById(claimed.id()).orElseThrow();
        var suppliedAt=shortened.firstDispatchedAt().plusMillis(1);
        assertThat(suppliedAt).isBefore(shortened.leaseExpiresAt());
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var checking=new CountDownLatch(1);
        try(var workers=new BoundedWorkers()) {
            var holder=workers.submit(()->transaction.requiresNew(()->{
                assertThat(jdbc.queryForList("select id from provider_operations where id=? for update",UUID.class,shortened.id())).containsExactly(shortened.id());
                locked.countDown();awaitRelease(release);return true;
            }));
            try {
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                var dispatch=workers.submit(()->{
                    checking.countDown();
                    assertThatThrownBy(()->operations.requireOwnedFailedRenewalMethodDispatch(billing,r,shortened,suppliedAt))
                        .isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,
                            e->assertThat(e.code()).isEqualTo("PROVIDER_RESULT_UNKNOWN"));
                    return true;
                });
                assertThat(checking.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->dispatch.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                Thread.sleep(1100);
                assertThat(jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant()).isAfterOrEqualTo(shortened.leaseExpiresAt());
                release.countDown();assertThat(holder.get(5,TimeUnit.SECONDS)).isTrue();assertThat(dispatch.get(10,TimeUnit.SECONDS)).isTrue();
            } finally {release.countDown();}
        }
        assertThat(operations.findById(shortened.id())).contains(shortened);
        assertThat(shortened.status()).isEqualTo(dev.genericpayments.platform.provider.application.model.ProviderOperation.Status.DISPATCHING);
        assertThat(shortened.providerReference()).isNull();assertThat(shortened.version()).isEqualTo(1);
        assertThat(http.calls).hasSize(sdkCalls);
        assertThat(jdbc.queryForObject("select count(*) from stripe_subscription_setup_proofs where operation_id=?",Long.class,r.setupOperationId())).isZero();
        assertThat(count("stripe_billing_failed_method_completions")).isZero();
    }
    @Test void legacyRecoveryPermanentlyRefusesALaterMethodReservation() {
        var s=setup();var svc=service(store(),null);var failure=fail(s,svc);var recovery=svc.reserveExistingRecovery(s.admission());
        assertThatThrownBy(()->reserveMethod(s,failure)).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_failed_method_reservations")).isZero();
        assertThat(count("stripe_billing_failed_renewal_recoveries")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select operation_id from stripe_billing_failed_renewal_recoveries where admission_id=?",UUID.class,s.admission().id())).isEqualTo(recovery.operationId());
    }
    @Test void methodReservationReloadDetectsCorruptedInitialOperationSnapshot() {
        var s=setup();var failure=fail(s,service(store(),null));var r=reserveMethod(s,failure);
        assertThatThrownBy(()->transaction.requiresNew(()->{
            // Deliberate negative corruption, rolled back with the failed authentication below.
            jdbc.execute("alter table stripe_billing_failed_method_reservations disable trigger failed_method_reservation_immutable");
            jdbc.update("update stripe_billing_failed_method_reservations set operation_snapshot=jsonb_set(operation_snapshot,'{fingerprint}','\"corrupted\"'::jsonb) where id=?",r.id());
            return new JdbcOwnedFailedRenewalMethodStore(jdbc).requireReservationInTransaction(r);
        })).hasMessage("Owned method transition authority unavailable");
        assertThat(transaction.required(()->new JdbcOwnedFailedRenewalMethodStore(jdbc).requireReservationInTransaction(r))).isEqualTo(r);
    }
    @Test void methodReadForeignKeySurvivesDisabledPhaseGuard() {
        var s=setup();var failure=fail(s,service(store(),null));var r=reserveMethod(s,failure);
        assertThatThrownBy(()->transaction.requiresNew(()->{
            // Isolate the FK negative from the stronger prestate trigger. Transaction rollback restores it.
            jdbc.execute("alter table stripe_billing_failed_method_reads disable trigger failed_method_read_proof");
            jdbc.update("""
                insert into stripe_billing_failed_method_reads(id,reservation_id,phase,invocation,started_at,deadline_at,captured_revision,
                 observation_sequence,selected_operation,selected_version,operation_snapshot,setup_step_snapshot,method_step_snapshot,billing_at,billing_mode)
                values(?,?,'METHOD',?,transaction_timestamp(),transaction_timestamp()+interval '30 seconds',?,?,?,0,'{}','{}','{}',?,'ISOLATED')
                """,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),failure.revision(),failure.observationSequence(),r.setupOperationId(),java.sql.Timestamp.from(time.selected));
            return true;
        })).hasStackTraceContaining("stripe_billing_failed_method_reads_reservation_id_fkey");
        assertThat(count("stripe_billing_failed_method_reads")).isZero();
    }
    @Test void methodExclusionRechecksCommittedReservationAfterWaitingForCommonAdmissionLock() throws Exception {
        var s=setup();var failure=fail(s,service(store(),null));
        var chosen=new CountDownLatch(1);var release=new CountDownLatch(1);var competing=new CountDownLatch(1);
        try(var workers=new BoundedWorkers()) {
            var winner=workers.submit(()->transaction.requiresNew(()->{
                reserveMethod(s,failure);chosen.countDown();awaitRelease(release);return true;
            }));
            try {
                assertThat(chosen.await(5,TimeUnit.SECONDS)).isTrue();
                var loser=workers.submit(()->{
                    competing.countDown();
                    // Deliberately invalid downstream identity: the branch trigger must reject BEFORE V34/FK checks.
                    assertThatThrownBy(()->transaction.requiresNew(()->{
                        jdbc.update("""
                            insert into stripe_billing_failed_renewal_recoveries(admission_id,operation_id,renewal_bill,payment_account_id,site_id,customer_id,subscription_id,
                             allocation_reference,payment_intent_reference,expected_revision,operation_version,associated_at)
                            values(?,?,?,?,?,?,?,?,?,?,0,clock_timestamp())
                            """,s.admission().id(),UUID.randomUUID(),failure.billId(),billing.id().value(),site.value(),customer.value(),current().id(),
                            failure.allocation(),failure.paymentIntent(),failure.revision());return true;
                    })).hasStackTraceContaining("Method reservation permanently owns this branch");return true;
                });
                assertThat(competing.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->loser.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();assertThat(winner.get(10,TimeUnit.SECONDS)).isTrue();assertThat(loser.get(10,TimeUnit.SECONDS)).isTrue();
            } finally {release.countDown();}
        }
        assertThat(count("stripe_billing_failed_method_reservations")).isEqualTo(1);
        assertThat(count("stripe_billing_failed_renewal_recoveries")).isZero();assertThat(count("payments")).isEqualTo(1);
    }

}
