package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance;
import dev.genericpayments.platform.provider.application.service.OwnedFailedGraceAdvanceService;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedGraceAdvanceStore;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedGraceAdvanceAdapter;
import dev.genericpayments.acceptance.runtime.OwnedBillingFailedRenewalRecovery;
import dev.genericpayments.acceptance.runtime.OwnedBillingFailedRenewalRecovery.State;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod;
import dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser;
import dev.genericpayments.platform.provider.application.service.OwnedMethodBrowserTestAccess;
import dev.genericpayments.platform.provider.application.service.OwnedMethodBrowserService;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeOwnedMethodBrowserReader;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeOwnedMethodBrowserTokens;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingGrantTokens;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedRenewalSetupAdapter;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedRenewalMethodAdapter;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedRenewalRecoveryReader;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedRenewalBindingAdapter;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore;
import dev.genericpayments.platform.subscriptions.application.port.in.GetSubscriptionActionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionClientSessionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.ConfirmSubscriptionActionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.SubscriptionConfirmationUseCase;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalMethodService;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedRecoveryConfirmStore;
import dev.genericpayments.platform.provider.application.service.OwnedRecoveryConfirmService;
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
import dev.genericpayments.platform.customers.domain.CustomerSessionScope;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.support.StripeBillingFirstRenewalCompositionFixture;
import dev.genericpayments.platform.support.OwnedFailedRenewalBillingTime;
import dev.genericpayments.platform.support.OwnedFirstRenewalBillingTime;
import dev.genericpayments.platform.support.OwnedSubscriptionBillingTime;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
    @Autowired dev.genericpayments.platform.subscriptions.adapter.in.web.SubscriptionHttpUseCases ordinaryHttp;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    private OwnedFailedRenewalStore store() { return new JdbcOwnedFailedRenewalStore(jdbc,entityManagerFactory); }
    private record ManagementAuthentication(dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext context,String rawToken){
        @Override public String toString(){return "ManagementAuthentication[redacted]";}
    }
    private dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext managementContext(String key){
        return managementAuthentication(key).context();
    }
    private ManagementAuthentication managementAuthentication(String key){
        TestClock.set(Instant.now());var session=sessions.issue(site,customer,"https://shop.test");
        var actor=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer(sessions.resolveRequired(session.rawToken(),"https://shop.test"));
        return new ManagementAuthentication(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(actor,site,customer,"https://shop.test",key,"owned-method-request"),session.rawToken());
    }
    private SubscriptionFactService methodFacts(dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore methods){
        return new SubscriptionFactService(subscriptions,lifecycleOperations,trials,siteLock,customerStore,accountLocks,bills,cash,events,catalog,
            Clock.systemUTC(),callback,objects,metadata,policies,observations,digest,quotes,sweep,readPreparation,authority,time,
            new OwnedFailedRenewalMethodRecoveryFactProof(methods,transaction,time),new OwnedFailedRenewalMethodFactProof(methods,transaction,time),methodBillingProofs);
    }
    private dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalMethodService methodService(dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore methods){
        return methodService(methods,lifecycleOperations);
    }
    private OwnedFailedRenewalMethodService methodService(OwnedFailedRenewalMethodStore methods,SubscriptionOperationStore operationStore){
        return methodService(methods,operationStore,transaction);
    }
    private OwnedFailedRenewalMethodService methodService(OwnedFailedRenewalMethodStore methods,SubscriptionOperationStore operationStore,SubscriptionTransactionPort serviceTransaction){
        return methodService(methods,operationStore,serviceTransaction,authority);
    }
    private OwnedFailedRenewalMethodService methodService(OwnedFailedRenewalMethodStore methods,SubscriptionOperationStore operationStore,SubscriptionTransactionPort serviceTransaction,SubscriptionAuthorityPort serviceAuthority){
        return new OwnedFailedRenewalMethodService(methods,store(),serviceTransaction,operationStore,lifecycleLocks,
            accountLocks,subscriptions,bills,readPreparation,observations,methodFacts(methods),trials,time,Clock.systemUTC(),bindings,serviceAuthority,digest);
    }
    private final ExactTime time=new ExactTime();
    private class ExactTime implements SubscriptionBillingTimePort {
        Instant selected; boolean driftSecondSample,ordinary; int samples;
        OwnedFailedRenewalBillingTime persisted;
        final List<Sample> persistedSamples=new ArrayList<>();
        final List<Instant> persistedOperationalTimes=new ArrayList<>();
        int beforeFailedAdmissionSamples;
        void usePersisted() {
            persisted=new OwnedFailedRenewalBillingTime(jdbc,new OwnedFirstRenewalBillingTime(jdbc,new OwnedSubscriptionBillingTime(jdbc)));
        }
        public Sample sample(PaymentAccountId account,SiteId suppliedSite,CustomerId suppliedCustomer,Instant utc) {
            if(persisted!=null) {
                var holder=org.springframework.transaction.support.TransactionSynchronizationManager.getResource(jdbc.getDataSource());
                var sample=persisted.sample(account,suppliedSite,suppliedCustomer,utc);
                assertThat(persisted.sampledConnectionHolder).isSameAs(holder);
                assertThat(persisted.sampledTransaction).isEqualTo(jdbc.queryForObject("select pg_current_xact_id()::text",String.class));
                // The fixed initial period ends at 1702678400; original READY2 is E + 3600.
                assertThat(sample).isEqualTo(persistedExpected());
                persistedSamples.add(sample);persistedOperationalTimes.add(utc);
                if(count("stripe_billing_failed_renewals")==0)beforeFailedAdmissionSamples++;
                return sample;
            }
            if(!transaction.active() || selected==null || !billing.id().equals(account) || !site.equals(suppliedSite) || !customer.equals(suppliedCustomer)) throw new IllegalStateException("foreign or unarmed isolated owner");
            samples++;
            return new Sample(driftSecondSample&&samples==2?selected.plusSeconds(1):selected,ordinary?Mode.ORDINARY:Mode.ISOLATED);
        }
        public void requireUnscopedAllowed(SiteId s,CustomerId c) {
            if(persisted!=null){persisted.requireUnscopedAllowed(s,c);return;}
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
    private record GracePrepared(Setup setup,OwnedFailedRenewalService failures,OwnedFailedGraceAdvanceService service,
            OwnedFailedGraceAdvance.PreparationContext context,InvocationBudget budget) {}
    /** Positive origin is always the genuine setup and actual canned observer/FactService producer. */
    private GracePrepared gracePrepared() {
        time.usePersisted();var s=setup();var failures=service(store(),null);failedGraph(s);
        var failure=new StripeBillingOwnedFailedRenewalObserver(failures,credentials,clients,Clock.systemUTC()).observe(s.admission());
        assertThat(failure.billId()).isEqualTo(current().debt().billingRecordId());assertThat(http.replies).isEmpty();
        var preparation=failures.prepareGraceLastSecond(s.admission());
        var service=new OwnedFailedGraceAdvanceService(failures,new JdbcOwnedFailedGraceAdvanceStore(jdbc,entityManagerFactory),transaction,Clock.systemUTC());
        var budget=new InvocationBudget(Clock.systemUTC(),System::nanoTime);var context=service.inspect(s.admission(),preparation,budget);
        return new GracePrepared(s,failures,service,context,budget);
    }
    private ProviderOperation graceClaim(GracePrepared p){return p.service().claim(p.context(),"hosted-original-grace",p.budget());}
    /** Captures only the actual adapter's original values; all positive writes remain genuine JDBC. */
    private final class GraceSdk {
        final GracePrepared prepared;final Clock adapterUtc;final java.util.concurrent.atomic.AtomicBoolean liveUtc;
        InvocationBudget original;ProviderOperation originalClaim;OwnedFailedGraceAdvance.AdvanceAck returnedAck;
        String writingPhase;java.util.function.Consumer<String> beforeCommit=phase->{},afterCommit=phase->{};
        Throwable transactionFailure;final OwnedFailedGraceAdvanceService service;
        GraceSdk(GracePrepared p){this(p,Clock.systemUTC(),null);}
        GraceSdk(GracePrepared p,Clock utc,java.util.concurrent.atomic.AtomicBoolean live){
            prepared=p;adapterUtc=utc;liveUtc=live;var delegate=new JdbcOwnedFailedGraceAdvanceStore(jdbc,entityManagerFactory);
            var store=(OwnedFailedGraceAdvanceStore)Proxy.newProxyInstance(OwnedFailedGraceAdvanceStore.class.getClassLoader(),new Class<?>[]{OwnedFailedGraceAdvanceStore.class},(proxy,method,args)->{
                if(method.getName().equals("beforeAuthorityInTransaction")){if(original==null)original=(InvocationBudget)args[0];if(liveUtc!=null)liveUtc.set(true);}
                Object result;try{result=method.invoke(delegate,args);}catch(InvocationTargetException failure){throw failure.getCause();}
                switch(method.getName()){
                    case "firstClaimInTransaction"->{originalClaim=(ProviderOperation)result;writingPhase="claim";}
                    case "acknowledgeInTransaction"->{returnedAck=(OwnedFailedGraceAdvance.AdvanceAck)result;writingPhase="ack";}
                    case "recordReadyInTransaction"->writingPhase="ready";
                    case "uncertainInTransaction"->writingPhase="cleanup";
                    default->{}
                }return result;
            });
            var wrapped=new SubscriptionTransactionPort(){
                public boolean active(){return transaction.active();}
                public <T>T required(java.util.function.Supplier<T> effect){return requiresNew(effect);}
                public <T>T requiresNew(java.util.function.Supplier<T> effect){
                    writingPhase=null;final String[] committed={null};T result;
                    try{result=transaction.requiresNew(()->{T value=effect.get();committed[0]=writingPhase;if(writingPhase!=null)beforeCommit.accept(writingPhase);return value;});}
                    catch(RuntimeException|Error failure){transactionFailure=failure;throw failure;}
                    if(committed[0]!=null)afterCommit.accept(committed[0]);return result;
                }
            };
            service=new OwnedFailedGraceAdvanceService(p.failures(),store,wrapped,Clock.systemUTC());
        }
        OwnedFailedGraceAdvance.GraceAdvanceReady advance(){return new StripeBillingOwnedFailedGraceAdvanceAdapter(service,credentials,clients,adapterUtc).advance(prepared.setup().admission(),prepared.context().preparation());}
        void queue(){http.reply(clock(prepared.setup().ready().observation().frozenTime(),"ready"));http.reply(clock(prepared.context().preparation().target(),"advancing"));http.reply(clock(prepared.context().preparation().target(),"ready"));}
    }
    private List<String> graceSdkHistory(){return List.of("stripe_billing_failed_grace_claims","stripe_billing_failed_grace_acks","stripe_billing_failed_grace_ready","stripe_billing_failed_grace_cleanup").stream().map(table->table+rows(table)).toList();}
    private List<String> oldGraceAuthorityRows(){return List.of("stripe_billing_failed_renewals","stripe_billing_failed_renewal_reads","stripe_billing_failed_renewal_failures","stripe_billing_failed_grace_preparations","stripe_billing_first_renewals","stripe_billing_first_renewal_steps","stripe_billing_first_renewal_acks","stripe_billing_first_renewal_ready").stream().map(table->table+rows(table)).toList();}
    @Test void graceSdkContradictoryReady2AdvancingDetailsRefuseBeforePostAndCleanOnlyOriginal(){
        var p=gracePrepared();var f=new GraceSdk(p);var financial=financialState();var selector=selectorRows();var old=oldGraceAuthorityRows();var providers=rows("provider_operations");int calls=http.calls.size();
        var contradictory=com.google.gson.JsonParser.parseString(clock(p.setup().ready().observation().frozenTime(),"ready")).getAsJsonObject();
        contradictory.add("status_details",com.google.gson.JsonParser.parseString("{\"advancing\":{\"target_frozen_time\":"+p.context().preparation().target()+"}}"));
        http.reply(contradictory.toString());http.reply(clock(p.context().preparation().target(),"advancing"));http.reply(clock(p.context().preparation().target(),"ready"));
        assertThatThrownBy(f::advance).isInstanceOf(PaymentApplicationException.class);assertThat(http.calls.subList(calls,http.calls.size())).hasSize(1).allSatisfy(r->assertThat(r.method().name()).isEqualTo("GET"));
        assertThat(count("stripe_billing_failed_grace_claims")).isEqualTo(1);assertThat(count("stripe_billing_failed_grace_acks")).isZero();assertThat(count("stripe_billing_failed_grace_ready")).isZero();assertThat(count("stripe_billing_failed_grace_cleanup")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);
        assertThat(operations.findById(f.originalClaim.id()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(jdbc.queryForObject("select invocation from stripe_billing_failed_grace_cleanup where preparation_id=?",UUID.class,p.context().preparation().id())).isEqualTo(f.original.invocation());
        assertThat(jdbc.queryForList("select row_to_json(t)::text from provider_operations t where id<>? order by row_to_json(t)::text",String.class,f.originalClaim.id())).isEqualTo(providers);assertThat(financialState()).isEqualTo(financial);assertThat(selectorRows()).isEqualTo(selector);assertThat(oldGraceAuthorityRows()).isEqualTo(old);
    }
    @Test void graceSdkGenuineCompositionCommitsClaimAckReadyIndependentlyAndLeavesOriginalSelectedTime(){
        var p=gracePrepared();var f=new GraceSdk(p);var financial=financialState();var selector=selectorRows();var old=oldGraceAuthorityRows();var providers=rows("provider_operations");int calls=http.calls.size();f.queue();var ready=f.advance();
        assertThat(ready.admissionId()).isEqualTo(p.setup().admission().id());assertThat(ready.observation().frozenTime()).isEqualTo(p.context().preparation().target());assertThat(ready.ack().operation().id()).isEqualTo(f.originalClaim.id());
        var claim=jdbc.queryForMap("select * from stripe_billing_failed_grace_claims where preparation_id=?",p.context().preparation().id());
        var ack=jdbc.queryForMap("select * from stripe_billing_failed_grace_acks where preparation_id=?",p.context().preparation().id());
        var observed=jdbc.queryForMap("select * from stripe_billing_failed_grace_ready where preparation_id=?",p.context().preparation().id());
        assertThat(Set.of(claim.get("recorded_transaction").toString(),ack.get("recorded_transaction").toString(),observed.get("recorded_transaction").toString())).hasSize(3);
        for(var receipt:List.of(claim,ack,observed)){assertThat(receipt.get("operation_id")).isEqualTo(f.originalClaim.id());assertThat(receipt.get("invocation")).isEqualTo(f.original.invocation());}
        assertThat(claim.get("claimant")).isEqualTo(f.originalClaim.leaseOwner());assertThat(ack.get("claimant")).isEqualTo(f.originalClaim.leaseOwner());assertThat(ack.get("deadline")).isEqualTo(claim.get("deadline"));
        var requests=http.calls.subList(calls,http.calls.size());assertThat(requests.stream().map(r->r.method().name()+" "+r.url().getPath()).toList()).containsExactly("GET /v1/test_helpers/test_clocks/clock_owned","POST /v1/test_helpers/test_clocks/clock_owned/advance","GET /v1/test_helpers/test_clocks/clock_owned");
        var post=requests.get(1);assertThat(post.params()).containsExactlyInAnyOrderEntriesOf(Map.of("frozen_time",p.context().preparation().target()));assertThat(post.options().getIdempotencyKey()).isEqualTo(p.context().preparation().operationKey());
        requests.forEach(r->{assertThat(r.options().getMaxNetworkRetries()).isZero();assertThat(r.options().getReadTimeout()).isBetween(1,2000);assertThat(r.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");});
        assertThat(jdbc.queryForList("select row_to_json(t)::text from provider_operations t where id<>? order by row_to_json(t)::text",String.class,f.originalClaim.id())).isEqualTo(providers);
        assertThat(financialState()).isEqualTo(financial);assertThat(selectorRows()).isEqualTo(selector);assertThat(oldGraceAuthorityRows()).isEqualTo(old);assertThat(count("stripe_billing_failed_grace_cleanup")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertPersistedSampleUnchanged();
        var history=graceSdkHistory();assertThatThrownBy(f::advance).isInstanceOf(PaymentApplicationException.class);assertThat(graceSdkHistory()).isEqualTo(history);assertThat(http.calls).hasSize(calls+3);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"ack","ready"})
    void graceSdkAmbiguousCommittedReturnAuthenticatesOnlyOriginalCleanupAndPreservesTerminalReady(String phase){
        var p=gracePrepared();var f=new GraceSdk(p);var financial=financialState();var selector=selectorRows();f.queue();
        f.afterCommit=committed->{if(committed.equals(phase)){
            var borrowed=new InvocationBudget(Clock.systemUTC(),System::nanoTime);f.service.uncertain(p.context(),f.originalClaim,null,borrowed);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
            throw new IllegalStateException("synthetic ambiguous original grace return");
        }};
        assertThatThrownBy(f::advance).isInstanceOf(PaymentApplicationException.class);assertThat(operations.findById(f.originalClaim.id()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(count("stripe_billing_failed_grace_acks")).isEqualTo(1);
        assertThat(count("stripe_billing_failed_grace_ready")).isEqualTo(phase.equals("ready")?1:0);assertThat(count("stripe_billing_failed_grace_cleanup")).isEqualTo(phase.equals("ack")?1:0);assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(phase.equals("ack")?1:0);
        if(phase.equals("ack")){var cleanup=jdbc.queryForMap("select c.*,q.claimant from stripe_billing_failed_grace_cleanup c join stripe_billing_failed_grace_claims q using(preparation_id) where c.preparation_id=?",p.context().preparation().id());assertThat(cleanup.get("invocation")).isEqualTo(f.original.invocation());assertThat(cleanup.get("claimant")).isEqualTo(f.originalClaim.leaseOwner());assertThat(cleanup.get("operation_id")).isEqualTo(f.originalClaim.id());}
        assertThat(financialState()).isEqualTo(financial);assertThat(selectorRows()).isEqualTo(selector);
    }
    @Test void graceSdkOriginalAckCleanupUsesOneDatabaseTimestampAfterApplicationUtcRollback(){
        var p=gracePrepared();var now=new java.util.concurrent.atomic.AtomicReference<Instant>();var rolled=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now.get()==null?Instant.now():now.get();}};
        var f=new GraceSdk(p,rolled,null);var financial=financialState();var selector=selectorRows();f.queue();final Instant[] before={null};
        f.afterCommit=phase->{if(phase.equals("ack")){before[0]=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();now.set(f.returnedAck.acknowledgedAt().minusSeconds(1));}};
        assertThatThrownBy(f::advance).isInstanceOf(PaymentApplicationException.class);var after=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();
        var cleanup=jdbc.queryForMap("select * from stripe_billing_failed_grace_cleanup where preparation_id=?",p.context().preparation().id());var stop=jdbc.queryForMap("select * from stripe_billing_failed_renewal_stops where admission_id=?",p.setup().admission().id());
        assertThat(cleanup.get("stopped_at")).isEqualTo(stop.get("stopped_at"));assertThat(((java.sql.Timestamp)stop.get("stopped_at")).toInstant()).isBetween(before[0],after).isAfterOrEqualTo(f.returnedAck.acknowledgedAt());assertThat(cleanup.get("invocation")).isEqualTo(f.original.invocation());assertThat(stop.get("reason")).isEqualTo("UNCERTAIN");assertThat(operations.findById(f.originalClaim.id()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(count("stripe_billing_failed_grace_ready")).isZero();assertThat(financialState()).isEqualTo(financial);assertThat(selectorRows()).isEqualTo(selector);
    }
    @ParameterizedTest @ValueSource(strings={"ack","ready"})
    void graceSdkPositiveWritingTransactionDeferredProofRejectsAuthorityDriftAfterFinalCallback(String phase){
        var p=gracePrepared();var f=new GraceSdk(p);var financial=financialState();var selector=selectorRows();var alias=billing.credentialsRef();f.queue();
        f.beforeCommit=writing->{if(writing.equals(phase)){assertThat(jdbc.update("update payment_accounts set credentials_ref='negative-grace-sdk-deferred-drift' where id=?",billing.id().value())).isEqualTo(1);assertThat(jdbc.queryForObject("select owned_failed_grace_current(?)",Boolean.class,p.setup().admission().id())).isFalse();}};
        assertThatThrownBy(f::advance).isInstanceOf(PaymentApplicationException.class);assertGraceAdvanceSqlState(f.transactionFailure,"23514");assertThat(jdbc.queryForObject("select credentials_ref from payment_accounts where id=?",String.class,billing.id().value())).isEqualTo(alias);
        assertThat(count("stripe_billing_failed_grace_acks")).isEqualTo(phase.equals("ready")?1:0);assertThat(count("stripe_billing_failed_grace_ready")).isZero();assertThat(operations.findById(f.originalClaim.id()).orElseThrow().status()).isEqualTo(phase.equals("ready")?ProviderOperation.Status.ACKNOWLEDGED:ProviderOperation.Status.UNKNOWN);assertThat(count("stripe_billing_failed_grace_cleanup")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);assertThat(financialState()).isEqualTo(financial);assertThat(selectorRows()).isEqualTo(selector);
    }
    @ParameterizedTest @ValueSource(strings={"ack","ready"})
    void graceSdkPositiveWritingTransactionHeldPastOriginalDeadlineRollsBackAtDeferredCommit(String phase){
        var p=gracePrepared();var databaseStart=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();var live=new java.util.concurrent.atomic.AtomicBoolean();
        var originalUtc=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return live.get()?Instant.now():databaseStart.minusSeconds(26);}};
        var f=new GraceSdk(p,originalUtc,live);var financial=financialState();var selector=selectorRows();f.queue();
        f.beforeCommit=writing->{if(writing.equals(phase)){assertThat(f.original.deadlineAt()).isEqualTo(databaseStart.plusSeconds(4));assertThat(jdbc.queryForObject("select clock_timestamp()<deadline_at from stripe_billing_failed_grace_claims where preparation_id=?",Boolean.class,p.context().preparation().id())).isTrue();try{awaitGraceDatabaseDeadline(f.original);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}}};
        assertThatThrownBy(f::advance).isInstanceOf(PaymentApplicationException.class);assertGraceAdvanceSqlState(f.transactionFailure,"23514");assertThat(count("stripe_billing_failed_grace_acks")).isEqualTo(phase.equals("ready")?1:0);assertThat(count("stripe_billing_failed_grace_ready")).isZero();assertThat(operations.findById(f.originalClaim.id()).orElseThrow().status()).isEqualTo(phase.equals("ready")?ProviderOperation.Status.ACKNOWLEDGED:ProviderOperation.Status.UNKNOWN);assertThat(count("stripe_billing_failed_grace_cleanup")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);assertThat(financialState()).isEqualTo(financial);assertThat(selectorRows()).isEqualTo(selector);
    }
    @ParameterizedTest @ValueSource(strings={"get","post","poll"})
    void graceSdkMalformedWireConsumesOnlyActualOriginalAndPreservesAckWhenItExists(String phase){
        var p=gracePrepared();var f=new GraceSdk(p);var financial=financialState();var selector=selectorRows();String originalClock=clock(p.setup().ready().observation().frozenTime(),"ready");String post=clock(p.context().preparation().target(),"advancing");String ready=clock(p.context().preparation().target(),"ready");
        for(String selected:List.of("get","post","poll")){String body=selected.equals("get")?originalClock:selected.equals("post")?post:ready;http.reply(selected.equals(phase)?body.substring(0,body.length()-1)+",\"livemode\":false}":body);if(selected.equals(phase))break;}
        assertThatThrownBy(f::advance).isInstanceOf(PaymentApplicationException.class);assertThat(count("stripe_billing_failed_grace_acks")).isEqualTo(phase.equals("poll")?1:0);assertThat(count("stripe_billing_failed_grace_ready")).isZero();assertThat(count("stripe_billing_failed_grace_cleanup")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);assertThat(financialState()).isEqualTo(financial);assertThat(selectorRows()).isEqualTo(selector);
    }
    @ParameterizedTest @ValueSource(strings={"preflight","get","post","poll"})
    void graceSdkCurrentAccountDriftRefusesAtEveryRemoteBoundaryWithoutStoppingAnUnclaimedLane(String phase){
        var p=gracePrepared();var f=new GraceSdk(p);var financial=financialState();var selector=selectorRows();var alias=billing.credentialsRef();int start=http.calls.size();f.queue();
        Runnable drift=()->jdbc.update("update payment_accounts set credentials_ref='negative-grace-sdk-account-drift' where id=?",billing.id().value());
        if(phase.equals("preflight"))drift.run();else inspectSdk(request->{int index=http.calls.size()-start;if(index==(phase.equals("get")?1:phase.equals("post")?2:3))drift.run();});
        try{assertThatThrownBy(f::advance).isInstanceOf(PaymentApplicationException.class);assertThat(count("stripe_billing_failed_grace_claims")).isEqualTo(phase.equals("preflight")?0:1);assertThat(count("stripe_billing_failed_grace_acks")).isEqualTo(phase.equals("poll")?1:0);assertThat(count("stripe_billing_failed_grace_ready")).isZero();assertThat(count("stripe_billing_failed_grace_cleanup")).isEqualTo(phase.equals("preflight")?0:1);assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(phase.equals("preflight")?0:1);}
        finally{jdbc.update("update payment_accounts set credentials_ref=? where id=?",alias,billing.id().value());}
        assertThat(financialState()).isEqualTo(financial);assertThat(selectorRows()).isEqualTo(selector);
    }
    @Test void graceSdkRacingOriginalInvocationsHaveOneReadyWinnerAndNoLoserStop()throws Exception{
        var p=gracePrepared();var first=new GraceSdk(p);var second=new GraceSdk(p);var financial=financialState();var selector=selectorRows();var start=new CountDownLatch(1);first.queue();
        try(var workers=new BoundedWorkers()){
            Callable<Boolean> a=()->{awaitRelease(start);try{first.advance();return true;}catch(PaymentApplicationException refused){return false;}};
            Callable<Boolean> b=()->{awaitRelease(start);try{second.advance();return true;}catch(PaymentApplicationException refused){return false;}};
            var one=workers.submit(a);var two=workers.submit(b);start.countDown();assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(count("stripe_billing_failed_grace_claims")).isEqualTo(1);assertThat(count("stripe_billing_failed_grace_acks")).isEqualTo(1);assertThat(count("stripe_billing_failed_grace_ready")).isEqualTo(1);assertThat(count("stripe_billing_failed_grace_cleanup")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(financialState()).isEqualTo(financial);assertThat(selectorRows()).isEqualTo(selector);
    }
    @ParameterizedTest @ValueSource(strings={"method","recovery"})
    void graceSdkCannotAdoptBranchWinnerAfterProvisionalPreparation(String branch){
        var p=gracePrepared();var f=new GraceSdk(p);if(branch.equals("method"))methodService(new JdbcOwnedFailedRenewalMethodStore(jdbc)).reserveMethod(p.setup().admission(),managementContext("sdk-losing-grace-branch"),true);else p.failures().reserveExistingRecovery(p.setup().admission());
        var financial=financialState();var history=graceSdkHistory();int calls=http.calls.size();assertThatThrownBy(f::advance).isInstanceOf(PaymentApplicationException.class);assertThat(graceSdkHistory()).isEqualTo(history);assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(financialState()).isEqualTo(financial);
    }
    private InvocationBudget graceBudgetWithFourDatabaseSecondsRemaining(){
        Instant databaseStart=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();
        var liveUtc=new java.util.concurrent.atomic.AtomicBoolean();
        var originalUtc=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return liveUtc.get()?Instant.now():databaseStart.minusSeconds(26);}};
        var original=new InvocationBudget(originalUtc,System::nanoTime);liveUtc.set(true);
        assertThat(original.deadlineAt()).isEqualTo(databaseStart.plusSeconds(4));return original;
    }
    private void awaitGraceDatabaseDeadline(InvocationBudget original)throws InterruptedException{
        boolean expired=false;var waitLimit=Instant.now().plusSeconds(5);
        while(Instant.now().isBefore(waitLimit)){
            expired=Boolean.TRUE.equals(jdbc.queryForObject("select clock_timestamp()>=?",Boolean.class,java.sql.Timestamp.from(original.deadlineAt())));if(expired)break;Thread.sleep(10);
        }
        assertThat(expired).as("Original database deadline must pass without renewing its invocation").isTrue();
    }
    @Test void graceFirstClaimCommitsOriginalInvocationOnActualJpaConnectionWithoutChangingSelectorOrFinancialRows(){
        var p=gracePrepared();var financial=financialState();var selector=selectorRows();var oldProviders=rows("provider_operations");int calls=http.calls.size();
        var claim=graceClaim(p);var receipt=jdbc.queryForMap("select * from stripe_billing_failed_grace_claims where preparation_id=?",p.context().preparation().id());
        assertThat(claim.kind()).isEqualTo(ProviderOperation.Kind.OWNED_GRACE_CLOCK_ADVANCE);assertThat(claim.resourceId()).isEqualTo(p.context().preparation().id());assertThat(claim.operationKey()).isEqualTo(p.context().preparation().operationKey());assertThat(claim.fingerprint()).isEqualTo(p.context().preparation().fingerprint());
        assertThat(claim.status()).isEqualTo(ProviderOperation.Status.DISPATCHING);assertThat(claim.version()).isEqualTo(1);assertThat(claim.providerReference()).isNull();
        assertThat(receipt.get("operation_id")).isEqualTo(claim.id());assertThat(receipt.get("invocation")).isEqualTo(p.budget().invocation());assertThat(receipt.get("claimant")).isEqualTo(claim.leaseOwner());
        assertThat(((java.sql.Timestamp)receipt.get("entered_at")).toInstant()).isEqualTo(p.budget().enteredAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(((java.sql.Timestamp)receipt.get("deadline_at")).toInstant()).isEqualTo(p.budget().deadlineAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(receipt.get("recorded_transaction").toString()).isEqualTo(jdbc.queryForObject("select xmin::text from stripe_billing_failed_grace_claims where preparation_id=?",String.class,claim.resourceId()));
        assertThat(p.service().inspectClaim(p.context(),claim,p.budget())).isEqualTo(claim);
        assertThat(financialState()).isEqualTo(financial);assertThat(selectorRows()).isEqualTo(selector);assertThat(http.calls).hasSize(calls);
        assertThat(rows("provider_operations")).hasSize(oldProviders.size()+1);assertThat(count("stripe_billing_failed_grace_acks")).isZero();assertThat(count("stripe_billing_failed_grace_ready")).isZero();assertThat(count("stripe_billing_failed_grace_cleanup")).isZero();
        assertThat(jdbc.queryForList("select row_to_json(t)::text from provider_operations t where id<>? order by row_to_json(t)::text",String.class,claim.id())).isEqualTo(oldProviders);
        assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertPersistedSampleUnchanged();
    }
    @Test void graceFirstClaimHasNoSameTransactionContinuationAndRealRollbackRemovesBothWrites(){
        var p=gracePrepared();var financial=financialState();var selector=selectorRows();var budget=p.budget();var store=new JdbcOwnedFailedGraceAdvanceStore(jdbc,entityManagerFactory);
        assertThatThrownBy(()->transaction.requiresNew(()->p.failures().withGraceLastSecondInTransaction(p.setup().admission(),p.context().preparation(),context->{
            var claim=store.firstClaimInTransaction(context,"rollback-original",Instant.now(),budget);
            String receiptTransaction=jdbc.queryForObject("select xmin::text from stripe_billing_failed_grace_claims where preparation_id=?",String.class,claim.resourceId());
            String callerTransaction=jdbc.queryForObject("select pg_current_xact_id()::text",String.class);
            assertThat(receiptTransaction).isEqualTo(callerTransaction);
            assertThatThrownBy(()->store.requireClaimInTransaction(context,claim,Instant.now(),budget)).isInstanceOf(RuntimeException.class);
            throw new IllegalStateException("rollback firstclaim control");
        }))).hasMessage("rollback firstclaim control");
        assertThat(count("stripe_billing_failed_grace_claims")).isZero();assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='OWNED_GRACE_CLOCK_ADVANCE'",Long.class)).isZero();assertThat(financialState()).isEqualTo(financial);assertThat(selectorRows()).isEqualTo(selector);
    }
    @Test void graceDuplicateAndBothRealLegacyGuardedLosersCannotStopCommittedWinner(){
        var p=gracePrepared();var claim=graceClaim(p);var financial=financialState();var receipts=rows("stripe_billing_failed_grace_claims");int calls=http.calls.size();
        assertThatThrownBy(()->graceClaim(p)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->p.failures().reserveExistingRecovery(p.setup().admission())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->methodService(new JdbcOwnedFailedRenewalMethodStore(jdbc)).reserveMethod(p.setup().admission(),managementContext("grace-claimed-method-loser"),true)).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(count("stripe_billing_failed_grace_cleanup")).isZero();assertThat(count("stripe_billing_failed_method_reservations")).isZero();assertThat(count("stripe_billing_failed_renewal_recoveries")).isZero();
        assertThat(operations.findById(claim.id())).contains(claim);assertThat(rows("stripe_billing_failed_grace_claims")).isEqualTo(receipts);assertThat(financialState()).isEqualTo(financial);assertThat(http.calls).hasSize(calls);
    }
    @ParameterizedTest @ValueSource(strings={"method","recovery"})
    void graceFirstClaimRefusesBranchThatCommittedAfterProvisionalPreparation(String branch){
        var p=gracePrepared();if(branch.equals("method"))methodService(new JdbcOwnedFailedRenewalMethodStore(jdbc)).reserveMethod(p.setup().admission(),managementContext("method-beats-grace"),true);else p.failures().reserveExistingRecovery(p.setup().admission());
        var financial=financialState();assertThatThrownBy(()->graceClaim(p)).isInstanceOf(RuntimeException.class);assertThat(count("stripe_billing_failed_grace_claims")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(financialState()).isEqualTo(financial);
    }
    @Test void graceOriginalCleanupAtomicallyConsumesClaimAndCreatesOnlyOriginalUncertainStop(){
        var p=gracePrepared();var claim=graceClaim(p);var before=financialState();
        p.service().uncertain(p.context(),claim,null,new InvocationBudget(Clock.systemUTC(),System::nanoTime));assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
        p.service().uncertain(p.context(),claim,null,p.budget());var unknown=operations.findById(claim.id()).orElseThrow();
        assertThat(unknown.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(unknown.version()).isEqualTo(2);assertThat(count("stripe_billing_failed_grace_cleanup")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select reason from stripe_billing_failed_renewal_stops where admission_id=?",String.class,p.setup().admission().id())).isEqualTo("UNCERTAIN");
        assertThat(jdbc.queryForObject("select recorded_transaction::text from stripe_billing_failed_grace_cleanup where preparation_id=?",String.class,claim.resourceId())).isEqualTo(jdbc.queryForObject("select xmin::text from stripe_billing_failed_renewal_stops where admission_id=?",String.class,p.setup().admission().id()));
        assertThat(financialState()).isEqualTo(before);assertThatThrownBy(()->graceClaim(p)).isInstanceOf(RuntimeException.class);
    }
    @Test void graceOriginalDispatchCleanupUsesOneDatabaseTimestampDespiteApplicationUtcRollback(){
        var p=gracePrepared();var claim=graceClaim(p);var before=financialState();var selector=selectorRows();
        Instant beforeDatabase=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();
        var rolledClock=Clock.fixed(claim.firstDispatchedAt().minusSeconds(1),ZoneOffset.UTC);
        assertThat(rolledClock.instant()).isBefore(claim.firstDispatchedAt());
        var rolledService=new OwnedFailedGraceAdvanceService(p.failures(),new JdbcOwnedFailedGraceAdvanceStore(jdbc,entityManagerFactory),transaction,rolledClock);
        rolledService.uncertain(p.context(),claim,null,p.budget());
        Instant afterDatabase=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();
        assertThat(operations.findById(claim.id()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.UNKNOWN);
        var cleanup=jdbc.queryForMap("select * from stripe_billing_failed_grace_cleanup where preparation_id=?",claim.resourceId());
        var stop=jdbc.queryForMap("select * from stripe_billing_failed_renewal_stops where admission_id=?",p.setup().admission().id());
        Instant stoppedAt=((java.sql.Timestamp)stop.get("stopped_at")).toInstant();
        assertThat(cleanup.get("stopped_at")).isEqualTo(stop.get("stopped_at"));assertThat(stoppedAt).isBetween(beforeDatabase,afterDatabase);assertThat(stoppedAt).isAfterOrEqualTo(claim.firstDispatchedAt());
        assertThat(stop.get("reason")).isEqualTo("UNCERTAIN");assertThat(cleanup.get("invocation")).isEqualTo(p.budget().invocation());
        assertThat(financialState()).isEqualTo(before);assertThat(selectorRows()).isEqualTo(selector);assertThat(count("stripe_billing_failed_grace_acks")).isZero();
    }
    @Test void graceFirstClaimDeferredCommitRejectsIdleHoldPastOriginalPersistedDeadline()throws Exception{
        var p=gracePrepared();var before=financialState();var oldProviders=rows("provider_operations");var written=new CountDownLatch(1);var release=new CountDownLatch(1);
        // One unchanged 30s invocation under a lagging application UTC has only 4s left on the database.
        // This is a rollback-only negative control, not a renewed or shortened InvocationBudget type.
        var original=graceBudgetWithFourDatabaseSecondsRemaining();
        try(var workers=new BoundedWorkers()){
            var contender=workers.submit(()->{
                assertThatThrownBy(()->transaction.requiresNew(()->{
                    var store=new JdbcOwnedFailedGraceAdvanceStore(jdbc,entityManagerFactory);store.beforeAuthorityInTransaction(original);
                    var claim=p.failures().withGraceLastSecondInTransaction(p.setup().admission(),p.context().preparation(),c->store.firstClaimInTransaction(c,"negative-idle-expiry",Instant.now(),original));
                    assertThat(jdbc.queryForObject("select clock_timestamp()<deadline_at from stripe_billing_failed_grace_claims where preparation_id=?",Boolean.class,claim.resourceId())).isTrue();
                    written.countDown();awaitRelease(release);return claim;
                })).satisfies(error->assertGraceAdvanceSqlState(error,"23514"));return true;
            });
            try{
                assertThat(written.await(5,TimeUnit.SECONDS)).isTrue();assertThat(count("stripe_billing_failed_grace_claims")).isZero();
                awaitGraceDatabaseDeadline(original);assertThat(contender.isDone()).isFalse();
            }finally{release.countDown();}
            assertThat(contender.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("stripe_billing_failed_grace_claims")).isZero();assertThat(rows("provider_operations")).isEqualTo(oldProviders);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(financialState()).isEqualTo(before);
    }
    @Test void originalDispatchCleanupStillCommitsAfterOriginalDatabaseInvocationDeadline()throws Exception{
        var p=gracePrepared();var before=financialState();var selector=selectorRows();var original=graceBudgetWithFourDatabaseSecondsRemaining();
        var store=new JdbcOwnedFailedGraceAdvanceStore(jdbc,entityManagerFactory);
        var claim=transaction.requiresNew(()->{store.beforeAuthorityInTransaction(original);return p.failures().withGraceLastSecondInTransaction(p.setup().admission(),p.context().preparation(),c->store.firstClaimInTransaction(c,"original-expired-cleanup",Instant.now(),original));});
        awaitGraceDatabaseDeadline(original);assertThatThrownBy(()->original.remainingNanos(claim)).isInstanceOf(IllegalStateException.class);
        p.service().uncertain(p.context(),claim,null,original);
        assertThat(operations.findById(claim.id()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(count("stripe_billing_failed_grace_cleanup")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);assertThat(financialState()).isEqualTo(before);assertThat(selectorRows()).isEqualTo(selector);
    }
    @Test void graceDeferredPositiveProofRejectsAuthorityDriftIntroducedAfterItsWritingCallback(){
        var p=gracePrepared();var before=financialState();var oldProviders=rows("provider_operations");var alias=billing.credentialsRef();
        assertThatThrownBy(()->transaction.requiresNew(()->{
            var store=new JdbcOwnedFailedGraceAdvanceStore(jdbc,entityManagerFactory);store.beforeAuthorityInTransaction(p.budget());
            p.failures().withGraceLastSecondInTransaction(p.setup().admission(),p.context().preparation(),c->store.firstClaimInTransaction(c,"negative-deferred-drift",Instant.now(),p.budget()));
            assertThat(jdbc.update("update payment_accounts set credentials_ref='negative-deferred-drift' where id=?",billing.id().value())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select credentials_ref from payment_accounts where id=?",String.class,billing.id().value())).isEqualTo("negative-deferred-drift");
            assertThat(jdbc.queryForObject("select owned_failed_grace_current(?)",Boolean.class,p.setup().admission().id())).isFalse();
            // Exercise the actual deferred constraints, then force rollback even if a broken guard accepts them.
            assertThatThrownBy(()->jdbc.execute("set constraints failed_grace_provider_committed,failed_grace_claim_committed immediate")).satisfies(error->assertGraceAdvanceSqlState(error,"23514"));
            throw new IllegalStateException("rollback deferred authority drift control");
        })).hasMessage("rollback deferred authority drift control");
        assertThat(jdbc.queryForObject("select credentials_ref from payment_accounts where id=?",String.class,billing.id().value())).isEqualTo(alias);assertThat(count("stripe_billing_failed_grace_claims")).isZero();assertThat(rows("provider_operations")).isEqualTo(oldProviders);assertThat(financialState()).isEqualTo(before);
    }
    @Test void originalDispatchCleanupDeferredProofRemainsAllowedAfterSameTransactionAuthorityDrift(){
        var p=gracePrepared();var claim=graceClaim(p);var before=financialState();var oldProviders=rows("provider_operations");var alias=billing.credentialsRef();
        assertThatThrownBy(()->transaction.requiresNew(()->{
            assertThat(jdbc.update("update payment_accounts set credentials_ref='negative-cleanup-drift' where id=?",billing.id().value())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select owned_failed_grace_current(?)",Boolean.class,p.setup().admission().id())).isFalse();
            new JdbcOwnedFailedGraceAdvanceStore(jdbc,entityManagerFactory).uncertainInTransaction(p.context(),claim,null,p.budget(),claim.firstDispatchedAt().minusSeconds(1));
            assertThat(jdbc.queryForObject("select status from provider_operations where id=?",String.class,claim.id())).isEqualTo("UNKNOWN");
            assertThat(count("stripe_billing_failed_grace_cleanup")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);
            assertThatCode(()->jdbc.execute("set constraints all immediate")).doesNotThrowAnyException();
            throw new IllegalStateException("rollback original cleanup authority drift control");
        })).hasMessage("rollback original cleanup authority drift control");
        assertThat(jdbc.queryForObject("select credentials_ref from payment_accounts where id=?",String.class,billing.id().value())).isEqualTo(alias);assertThat(rows("provider_operations")).isEqualTo(oldProviders);assertThat(count("stripe_billing_failed_grace_cleanup")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(financialState()).isEqualTo(before);
    }
    @Test void claimedLaneDirectStopAndHistoryConversionAreRejectedWithExactSqlState(){
        var p=gracePrepared();var claim=graceClaim(p);var before=rows("provider_operations");var receipts=rows("stripe_billing_failed_grace_claims");
        assertThatThrownBy(()->p.failures().stop(p.setup().admission(),Reason.AUTHORITY_DRIFT)).satisfies(error->assertGraceAdvanceSqlState(error,"23514"));
        for(var mutation:List.of("kind='TEST_CLOCK_ADVANCE'","resource_id='00000000-0000-0000-0000-000000000001'","lease_owner='borrowed'","version=version+1","fingerprint='forged'","status='ACKNOWLEDGED',provider_reference='clock_forged',lease_owner=null,lease_expires_at=null,version=2"))
            assertThatThrownBy(()->transaction.requiresNew(()->jdbc.update("update provider_operations set "+mutation+" where id=?",claim.id()))).satisfies(error->assertGraceAdvanceSqlState(error,"23514"));
        assertThatThrownBy(()->transaction.requiresNew(()->jdbc.update("delete from stripe_billing_failed_grace_claims where preparation_id=?",claim.resourceId()))).satisfies(error->assertGraceAdvanceSqlState(error,"23514"));
        assertThat(rows("provider_operations")).isEqualTo(before);assertThat(rows("stripe_billing_failed_grace_claims")).isEqualTo(receipts);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void rawReceiptProvenanceCannotBeForgedAndNoOpUpdatesDoNotRenewClaim(){
        var p=gracePrepared();var claim=graceClaim(p);var receipts=rows("stripe_billing_failed_grace_claims");
        for(var mutation:List.of("recorded_transaction=pg_current_xact_id()","invocation='00000000-0000-0000-0000-000000000001'","deadline_at=deadline_at+interval '1 second'","claimant=claimant"))
            assertThatThrownBy(()->transaction.requiresNew(()->jdbc.update("update stripe_billing_failed_grace_claims set "+mutation+" where preparation_id=?",claim.resourceId()))).satisfies(error->assertGraceAdvanceSqlState(error,"23514"));
        assertThat(rows("stripe_billing_failed_grace_claims")).isEqualTo(receipts);assertThat(operations.findById(claim.id())).contains(claim);
    }
    @ParameterizedTest @ValueSource(strings={"reserved-only","claim-only","fake-xid","initial-ack","initial-unknown","same-transaction-ack"})
    void rawGraceHalfTransitionsAndFabricatedPriorTransactionNeverCommit(String malformed){
        var p=gracePrepared();var preparation=p.context().preparation();var financial=financialState();var oldProviders=rows("provider_operations");
        assertThatThrownBy(()->transaction.requiresNew(()->{
            var id=UUID.randomUUID();var at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if(malformed.startsWith("initial-")){
                jdbc.update("""
                    insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint,status,provider_reference,first_dispatched_at,retry_before,version)
                    values(?,?,?,?,'OWNED_GRACE_CLOCK_ADVANCE',?,?,?,?,?,?,?,2)
                    """,id,billing.id().value(),billing.externalAccountId(),billing.credentialsRef(),preparation.id(),preparation.operationKey(),preparation.fingerprint(),malformed.equals("initial-ack")?"ACKNOWLEDGED":"UNKNOWN",malformed.equals("initial-ack")?p.setup().ready().observation().reference():null,java.sql.Timestamp.from(at),java.sql.Timestamp.from(at.plusSeconds(23*3600)));
            }else if(malformed.equals("same-transaction-ack")){
                var st=new JdbcOwnedFailedGraceAdvanceStore(jdbc,entityManagerFactory);st.beforeAuthorityInTransaction(p.budget());
                var claim=p.failures().withGraceLastSecondInTransaction(p.setup().admission(),preparation,c->st.firstClaimInTransaction(c,"negative-same-transaction",Instant.now(),p.budget()));
                jdbc.update("update provider_operations set status='ACKNOWLEDGED',provider_reference=?,lease_owner=null,lease_expires_at=null,version=2 where id=?",p.setup().ready().observation().reference(),claim.id());
            }else{
                // Negative-only malformed raw rows; this transaction must fail, including at its deferred boundary.
                jdbc.update("insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint) values(?,?,?,?,'OWNED_GRACE_CLOCK_ADVANCE',?,?,?)",id,billing.id().value(),billing.externalAccountId(),billing.credentialsRef(),preparation.id(),preparation.operationKey(),preparation.fingerprint());
                if(!malformed.equals("reserved-only"))jdbc.update("""
                    insert into stripe_billing_failed_grace_claims(preparation_id,operation_id,invocation,claimant,entered_at,deadline_at,claimed_at,deadline,recorded_transaction)
                    values(?,?,?,'negative-half-transition',?,?,?,?,%s)
                    """.formatted(malformed.equals("fake-xid")?"'1'::xid8":"pg_current_xact_id()"),preparation.id(),id,p.budget().invocation(),java.sql.Timestamp.from(p.budget().enteredAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS)),java.sql.Timestamp.from(p.budget().deadlineAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS)),java.sql.Timestamp.from(at),java.sql.Timestamp.from(at.plusSeconds(60)));
            }return true;
        })).satisfies(error->assertGraceAdvanceSqlState(error,"23514"));
        assertThat(rows("provider_operations")).isEqualTo(oldProviders);assertThat(count("stripe_billing_failed_grace_claims")).isZero();assertThat(count("stripe_billing_failed_grace_acks")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(financialState()).isEqualTo(financial);
    }
    @Test void twoIndependentGraceInvocationsHaveOneDurableFirstClaimAndNoLoserStop()throws Exception{
        var p=gracePrepared();var start=new CountDownLatch(1);var financial=financialState();
        try(var workers=new BoundedWorkers()){
            Callable<Boolean> attempt=()->{var budget=new InvocationBudget(Clock.systemUTC(),System::nanoTime);awaitRelease(start);try{p.service().claim(p.context(),"independent-"+budget.invocation(),budget);return true;}catch(RuntimeException refusal){return false;}};
            var first=workers.submit(attempt);var second=workers.submit(attempt);start.countDown();assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(count("stripe_billing_failed_grace_claims")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(count("stripe_billing_failed_grace_cleanup")).isZero();assertThat(financialState()).isEqualTo(financial);
    }
    @ParameterizedTest @ValueSource(strings={"method","recovery"})
    void graceAndActualGuardedLegacyBranchRaceLeavesOneWinnerAndNoLoserStop(String branch)throws Exception{
        var p=gracePrepared();var start=new CountDownLatch(1);var context=managementContext("concurrent-grace-legacy");
        try(var workers=new BoundedWorkers()){
            var grace=workers.submit(()->{awaitRelease(start);try{graceClaim(p);return true;}catch(RuntimeException refused){return false;}});
            var legacy=workers.submit(()->{awaitRelease(start);try{if(branch.equals("method"))methodService(new JdbcOwnedFailedRenewalMethodStore(jdbc)).reserveMethod(p.setup().admission(),context,true);else p.failures().reserveExistingRecovery(p.setup().admission());return true;}catch(RuntimeException refused){return false;}});
            start.countDown();assertThat(List.of(grace.get(10,TimeUnit.SECONDS),legacy.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(count("stripe_billing_failed_grace_claims")+count(branch.equals("method")?"stripe_billing_failed_method_reservations":"stripe_billing_failed_renewal_recoveries")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(count("stripe_billing_failed_grace_cleanup")).isZero();
    }
    @Test void stopAlwaysAttemptsUpstreamNowaitBeforeFreshClaimQueryEvenWhileClaimIsInvisible()throws Exception{
        var p=gracePrepared();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var holdingTx=new SubscriptionTransactionPort(){public boolean active(){return transaction.active();}public <T>T required(java.util.function.Supplier<T> work){return requiresNew(work);}public <T>T requiresNew(java.util.function.Supplier<T> work){return transaction.requiresNew(()->{var value=work.get();entered.countDown();awaitRelease(release);return value;});}};
        var service=new OwnedFailedGraceAdvanceService(p.failures(),new JdbcOwnedFailedGraceAdvanceStore(jdbc,entityManagerFactory),holdingTx,Clock.systemUTC());
        try(var workers=new BoundedWorkers()){
            var winner=workers.submit(()->service.claim(p.context(),"invisible-original-grace",p.budget()));
            try{
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(count("stripe_billing_failed_grace_claims")).isZero();
                var loser=workers.submit(()->{assertThatThrownBy(()->p.failures().stop(p.setup().admission(),Reason.AUTHORITY_DRIFT)).satisfies(OwnedFailedRenewalIntegrationTest::assertGraceLockUnavailable);return true;});
                assertThat(loser.get(5,TimeUnit.SECONDS)).isTrue();assertThat(winner.isDone()).isFalse();assertThat(release.getCount()).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
            }finally{release.countDown();}assertThat(winner.get(10,TimeUnit.SECONDS).status()).isEqualTo(ProviderOperation.Status.DISPATCHING);
        }
        assertThat(count("stripe_billing_failed_grace_claims")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void repeatableReadStaleNoClaimSnapshotCannotStopIndependentlyCommittedGraceWinner()throws Exception{
        var p=gracePrepared();var snapshot=new CountDownLatch(1);var winnerCommitted=new CountDownLatch(1);
        try(var workers=new BoundedWorkers()){
            var staleStop=workers.submit(()->{
                assertThatThrownBy(()->transaction.requiresNew(()->{
                    jdbc.execute("set transaction isolation level repeatable read");
                    String isolation=jdbc.queryForObject("show transaction_isolation",String.class);assertThat(isolation).isEqualTo("repeatable read");
                    assertThat(jdbc.queryForObject("select count(*) from stripe_billing_failed_grace_claims",Long.class)).isZero();snapshot.countDown();awaitRelease(winnerCommitted);
                    // This deliberately remains the original stale snapshot after the real claim commits.
                    assertThat(jdbc.queryForObject("select count(*) from stripe_billing_failed_grace_claims",Long.class)).isZero();
                    store().stopInTransaction(p.setup().admission(),Reason.AUTHORITY_DRIFT,Instant.now());return true;
                })).satisfies(error->assertGraceAdvanceSqlState(error,"23514"));return true;
            });
            ProviderOperation winner;
            try{assertThat(snapshot.await(5,TimeUnit.SECONDS)).isTrue();winner=graceClaim(p);assertThat(count("stripe_billing_failed_grace_claims")).isEqualTo(1);}
            finally{winnerCommitted.countDown();}
            assertThat(staleStop.get(10,TimeUnit.SECONDS)).isTrue();assertThat(operations.findById(winner.id())).contains(winner);
        }
        assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(count("stripe_billing_failed_grace_cleanup")).isZero();
    }
    @Test void unclaimedReadCommittedStopStillUsesOriginalV34Semantics(){
        var p=gracePrepared();var financial=financialState();
        transaction.requiresNew(()->{String isolation=jdbc.queryForObject("show transaction_isolation",String.class);assertThat(isolation).isEqualTo("read committed");store().stopInTransaction(p.setup().admission(),Reason.AUTHORITY_DRIFT,Instant.now());return true;});
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);assertThat(jdbc.queryForObject("select reason from stripe_billing_failed_renewal_stops where admission_id=?",String.class,p.setup().admission().id())).isEqualTo("AUTHORITY_DRIFT");assertThat(count("stripe_billing_failed_grace_claims")).isZero();assertThat(financialState()).isEqualTo(financial);
    }
    @ParameterizedTest @ValueSource(strings={"stripe_billing_failed_grace_preparations","stripe_billing_first_renewals","stripe_billing_failed_renewal_reads","stripe_billing_first_renewal_ready"})
    void graceFirstClaimRefusesHeldExactParentsWhileHolderStillOwnsThem(String table)throws Exception{
        var p=gracePrepared();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);UUID id=switch(table){case "stripe_billing_failed_grace_preparations"->p.context().preparation().id();case "stripe_billing_first_renewals"->p.setup().upstream().id();case "stripe_billing_failed_renewal_reads"->p.context().failure().claimId();default->p.setup().ready().id();};
        try(var workers=new BoundedWorkers()){
            var holder=workers.submit(()->transaction.requiresNew(()->{assertThat(jdbc.queryForList("select id from "+table+" where id=? for update",id)).hasSize(1);locked.countDown();awaitRelease(release);return true;}));
            try{assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();var refused=workers.submit(()->{assertThatThrownBy(()->graceClaim(p)).satisfies(OwnedFailedRenewalIntegrationTest::assertGraceLockUnavailable);return true;});assertThat(refused.get(5,TimeUnit.SECONDS)).isTrue();assertThat(holder.isDone()).isFalse();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();}
            finally{release.countDown();}assertThat(holder.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("stripe_billing_failed_grace_claims")).isZero();
    }
    private static void assertGraceAdvanceSqlState(Throwable error,String state){Throwable cause=error;while(cause!=null&&!(cause instanceof java.sql.SQLException))cause=cause.getCause();assertThat(cause).isInstanceOf(java.sql.SQLException.class);assertThat(((java.sql.SQLException)cause).getSQLState()).isEqualTo(state);}
    @Test void gracePreparationPersistsExactCapturedTargetWithoutChangingReadyTimeOrFinancialAuthority() {
        time.usePersisted();var s=setup();var svc=service(store(),null);var f=fail(s,svc);
        var financial=financialState();var receipts=selectorRows();int calls=http.calls.size();
        var p=svc.prepareGraceLastSecond(s.admission());
        assertThat(p.target()).isEqualTo(f.graceDeadline().getEpochSecond()-1);assertThat(p.predecessorReadyId()).isEqualTo(s.ready().id());
        assertThat(p.failureClaimId()).isEqualTo(f.claimId());assertThat(p.billId()).isEqualTo(f.billId());assertThat(p.graceDeadline()).isEqualTo(f.graceDeadline());
        assertThat(p.fingerprint()).isEqualTo(OwnedFailedRenewal.graceLastSecondFingerprint(p.id(),s.admission(),f,p.target(),p.operationKey(),p.preparedAt()));
        assertThat(jdbc.queryForObject("select owned_failed_grace_fingerprint(id,admission_id,target,operation_key,prepared_at) from stripe_billing_failed_grace_preparations where id=?",String.class,p.id())).isEqualTo(p.fingerprint());
        assertThat(jdbc.queryForObject("select xmin::text from stripe_billing_failed_grace_preparations where id=?",String.class,p.id())).isEqualTo(time.persisted.sampledTransaction);
        assertThat(svc.requireGraceLastSecond(s.admission(),p)).isEqualTo(p);assertThat(count("stripe_billing_failed_grace_preparations")).isEqualTo(1);
        assertThatThrownBy(()->svc.prepareGraceLastSecond(s.admission())).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(financial);assertThat(selectorRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);assertPersistedSampleUnchanged();
    }
    @Test void gracePreparationRollbackUsesBoundCallerConnectionAndDoesNotPublishReceipt() {
        time.usePersisted();var s=setup();var svc=service(store(),null);var f=fail(s,svc);var financial=financialState();var receipts=selectorRows();
        assertThatThrownBy(()->transaction.requiresNew(()->{
            var emHolder=org.springframework.transaction.support.TransactionSynchronizationManager.getResource(entityManagerFactory);
            assertThat(emHolder).isInstanceOf(org.springframework.orm.jpa.EntityManagerHolder.class);
            var em=((org.springframework.orm.jpa.EntityManagerHolder)emHolder).getEntityManager();assertThat(em.getTransaction().isActive()).isTrue();
            var connectionHolder=(org.springframework.jdbc.datasource.ConnectionHolder)org.springframework.transaction.support.TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            java.sql.Connection managedConnection=em.unwrap(org.hibernate.Session.class).doReturningWork(c->c);
            assertThat(managedConnection).isSameAs(connectionHolder.getConnection());
            var st=store();st.lockGraceLastSecondInTransaction(s.admission());
            var sample=time.sample(billing.id(),site,customer,Instant.now());assertThat(sample).isEqualTo(persistedExpected());
            var p=st.prepareGraceLastSecondInTransaction(s.admission(),f,Instant.now());
            assertThat(jdbc.queryForObject("select xmin::text from stripe_billing_failed_grace_preparations where id=?",String.class,p.id())).isEqualTo(time.persisted.sampledTransaction);
            throw new IllegalStateException("rollback preparation control");
        })).hasMessage("rollback preparation control");
        assertThat(count("stripe_billing_failed_grace_preparations")).isZero();assertThat(selectorRows()).isEqualTo(receipts);assertThat(financialState()).isEqualTo(financial);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void graceRejectsPretouchedSecondaryPoolRegardlessOfAutoCommitWithoutProofSqlOrLocks(boolean autoCommit)throws Exception {
        time.usePersisted();var s=setup();var f=fail(s,service(store(),null));var receipts=selectorRows();var financial=financialState();
        var originalPool=jdbc.getDataSource().unwrap(com.zaxxer.hikari.HikariDataSource.class);var configuration=new com.zaxxer.hikari.HikariConfig();originalPool.copyStateTo(configuration);
        configuration.setPoolName("grace-provenance-negative-"+UUID.randomUUID());configuration.setMaximumPoolSize(1);configuration.setMinimumIdle(0);configuration.setAutoCommit(autoCommit);
        try(var secondaryPool=new com.zaxxer.hikari.HikariDataSource(configuration)) {
            var proofQueries=new java.util.concurrent.atomic.AtomicInteger();
            var secondary=new org.springframework.jdbc.core.JdbcTemplate(secondaryPool){@Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper,Object... args){proofQueries.incrementAndGet();throw new AssertionError("SECONDARY_POOL_REACHED_PROOF_SQL");}};
            transaction.requiresNew(()->{
                assertThat(new org.springframework.jdbc.core.JdbcTemplate(secondaryPool).queryForObject("select 1",Integer.class)).isEqualTo(1);
                var holder=(org.springframework.jdbc.datasource.ConnectionHolder)org.springframework.transaction.support.TransactionSynchronizationManager.getResource(secondaryPool);
                assertThat(holder).isNotNull();assertThat(holder.isSynchronizedWithTransaction()).isTrue();
                try{assertThat(holder.getConnection().getAutoCommit()).isEqualTo(autoCommit);}catch(java.sql.SQLException e){throw new IllegalStateException(e);}
                var wrong=new JdbcOwnedFailedRenewalStore(secondary,entityManagerFactory);
                for(Runnable entry:List.<Runnable>of(()->wrong.lockGraceLastSecondInTransaction(s.admission()),()->wrong.prepareGraceLastSecondInTransaction(s.admission(),f,Instant.now()),()->wrong.requireGraceLastSecondInTransaction(s.admission(),null)))
                    assertThatThrownBy(entry::run).hasMessage("Owned failed renewal requires caller DataSource transaction");
                var graceWrong=new JdbcOwnedFailedGraceAdvanceStore(secondary,entityManagerFactory);var budget=new InvocationBudget(Clock.systemUTC(),System::nanoTime);var at=Instant.now();
                for(Runnable entry:List.<Runnable>of(()->graceWrong.beforeAuthorityInTransaction(budget),()->graceWrong.firstClaimInTransaction(null,"foreign-pool",at,budget),()->graceWrong.requireClaimInTransaction(null,null,at,budget),()->graceWrong.acknowledgeInTransaction(null,null,null,at,at,budget),()->graceWrong.requireAcknowledgedInTransaction(null,null,at,budget),()->graceWrong.recordReadyInTransaction(null,null,null,at,at,budget),()->graceWrong.uncertainInTransaction(null,null,null,budget,at)))
                    assertThatThrownBy(entry::run).isInstanceOf(IllegalStateException.class);
                assertThat(proofQueries).hasValue(0);
                // A separate physical connection can lock canonical rows immediately: refusal held none.
                try(var probe=originalPool.getConnection()) {
                    probe.setAutoCommit(false);
                    try(var customerProbe=probe.prepareStatement("select id from customers where id=? for update nowait");var operationProbe=probe.prepareStatement("select id from subscription_operations where id=? for update nowait")) {
                        customerProbe.setObject(1,customer.value());try(var rows=customerProbe.executeQuery()){assertThat(rows.next()).isTrue();}
                        operationProbe.setObject(1,s.upstream().receipt().intent().createOperation());try(var rows=operationProbe.executeQuery()){assertThat(rows.next()).isTrue();}
                    }finally{probe.rollback();}
                }catch(java.sql.SQLException e){throw new IllegalStateException(e);}
                return true;
            });
        }
        assertThat(count("stripe_billing_failed_grace_preparations")).isZero();assertThat(selectorRows()).isEqualTo(receipts);assertThat(financialState()).isEqualTo(financial);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void gracePostParentMembershipGuardRefusesNewHeldReconcileAndStableReconcileRemainsEligible()throws Exception {
        time.usePersisted();var s=setup();var f=fail(s,service(store(),null));
        var beforeIds=jdbc.queryForList("select unnest(owned_failed_grace_operation_ids(?))",UUID.class,s.admission().id());
        assertThat(beforeIds).containsExactly(s.upstream().receipt().intent().createOperation());
        var id=UUID.randomUUID();var at=Instant.now();var candidate=new SubscriptionOperation(id,site,customer,current().id(),SubscriptionOperation.Kind.RECONCILE,SubscriptionOperation.Status.RESERVED,"grace-membership-negative","a".repeat(64),"b".repeat(64),"reconcile:"+id,f.revision(),0,null,null,at,at,0);
        assertThat(transaction.requiresNew(()->lifecycleOperations.claim(candidate)).owner()).isTrue();
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var workers=new BoundedWorkers()) {
            var holder=workers.submit(()->transaction.requiresNew(()->{lifecycleOperations.lock(id);held.countDown();awaitRelease(release);return true;}));
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                var check=workers.submit(()->{
                    assertThatThrownBy(()->transaction.requiresNew(()->{
                        lifecycleLocks.lockActive(site,customer);accountLocks.findByIdForUpdate(billing.id()).orElseThrow();subscriptions.lock(site,current().id());
                        jdbc.queryForList("select require_owned_failed_grace_operation_membership(?,?::uuid[])",s.admission().id(),"{"+beforeIds.getFirst()+"}");return true;
                    })).satisfies(error->assertGraceSqlProof(error,"Owned grace operation membership changed"));return true;
                });
                assertThat(check.get(5,TimeUnit.SECONDS)).isTrue();
            } finally {release.countDown();}assertThat(holder.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("stripe_billing_failed_grace_preparations")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
        // Stable, uncontended RECONCILE remains part of the canonical lock set and allowed semantics.
        assertThat(jdbc.queryForList("select unnest(owned_failed_grace_operation_ids(?))",UUID.class,s.admission().id())).containsExactlyInAnyOrder(beforeIds.getFirst(),id);
        var p=service(store(),null).prepareGraceLastSecond(s.admission());assertThat(p.target()).isEqualTo(f.graceDeadline().getEpochSecond()-1);
    }
    @ParameterizedTest @ValueSource(strings={"target","hash","claim","ready","bill","future","before","deadline"})
    void graceDirectInsertAuthenticatesPayloadBeforeAnyUniqueWinnerCanMaskForgery(String bad) {
        time.usePersisted();var s=setup();var f=fail(s,service(store(),null));var financial=financialState();var receipts=selectorRows();
        var id=UUID.randomUUID();var target=f.graceDeadline().getEpochSecond()-(bad.equals("target")?0:1);var key="billing-failed-grace-v1:"+s.admission().id()+":1";
        var at=bad.equals("future")?Instant.now().plusSeconds(60):bad.equals("before")?f.recordedAt().minusSeconds(1):Instant.now();
        at=at.truncatedTo(java.time.temporal.ChronoUnit.MICROS);var prepared=at;
        // Coherent alternate target/time hashes still require the independent INSERT proof.
        var hash=bad.equals("hash")?"0".repeat(64):OwnedFirstRenewal.fingerprint(graceFingerprintFields(id,s.admission(),f,target,key,prepared));
        assertThatThrownBy(()->jdbc.update("""
            insert into stripe_billing_failed_grace_preparations(id,admission_id,failure_claim_id,renewal_bill,predecessor_ready,grace_deadline,target,operation_key,fingerprint,prepared_at)
            values(?,?,?,?,?,?,?,?,?,?)
            """,id,s.admission().id(),bad.equals("claim")?UUID.randomUUID():f.claimId(),bad.equals("bill")?s.upstream().bill():f.billId(),bad.equals("ready")?s.boundary().ready().id():s.ready().id(),java.sql.Timestamp.from(bad.equals("deadline")?f.graceDeadline().plusSeconds(1):f.graceDeadline()),target,key,hash,java.sql.Timestamp.from(prepared)))
            .satisfies(error->assertGraceSqlProof(error,"Owned grace preparation requires exact original failure and G-1"));
        assertThat(count("stripe_billing_failed_grace_preparations")).isZero();assertThat(financialState()).isEqualTo(financial);assertThat(selectorRows()).isEqualTo(receipts);
    }
    @Test void gracePreparationIsImmutableAndExactReloadRefusesTamperingAndLaterMethodReservation() {
        time.usePersisted();var s=setup();var svc=service(store(),null);var f=fail(s,svc);var p=svc.prepareGraceLastSecond(s.admission());
        var row=rows("stripe_billing_failed_grace_preparations");
        assertThatThrownBy(()->jdbc.update("update stripe_billing_failed_grace_preparations set target=target+1 where id=?",p.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("delete from stripe_billing_failed_grace_preparations where id=?",p.id())).isInstanceOf(RuntimeException.class);
        var forged=new GraceLastSecondPreparation(UUID.randomUUID(),p.admissionId(),p.failureClaimId(),p.billId(),p.predecessorReadyId(),p.graceDeadline(),p.target(),p.operationKey(),p.fingerprint(),p.preparedAt());
        assertThatThrownBy(()->svc.requireGraceLastSecond(s.admission(),forged)).isInstanceOf(RuntimeException.class);
        var r=methodService(new JdbcOwnedFailedRenewalMethodStore(jdbc)).reserveMethod(s.admission(),managementContext("grace-then-method"),true);
        assertThat(r.failure()).isEqualTo(f);assertThatThrownBy(()->svc.requireGraceLastSecond(s.admission(),p)).isInstanceOf(RuntimeException.class);
        assertThat(rows("stripe_billing_failed_grace_preparations")).isEqualTo(row);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"beforeFailure","method","recovery","stop"})
    void gracePreparationRefusesUnavailablePhasesWithoutAdditionalStop(String phase) {
        time.usePersisted();var s=setup();var svc=service(store(),null);
        if(!phase.equals("beforeFailure"))fail(s,svc);
        if(phase.equals("method"))methodService(new JdbcOwnedFailedRenewalMethodStore(jdbc)).reserveMethod(s.admission(),managementContext("grace-refuse-method"),true);
        if(phase.equals("recovery"))svc.reserveExistingRecovery(s.admission());if(phase.equals("stop"))svc.stop(s.admission(),Reason.AUTHORITY_DRIFT);
        var receipts=selectorRows();var financial=financialState();
        assertThatThrownBy(()->svc.prepareGraceLastSecond(s.admission())).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_failed_grace_preparations")).isZero();assertThat(selectorRows()).isEqualTo(receipts);assertThat(financialState()).isEqualTo(financial);
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(phase.equals("stop")?1:0);
    }
    @ParameterizedTest @ValueSource(strings={"credentials","disabled","policy"})
    void gracePreparationRejectsAuthorityDriftWithRollbackOnlyNegativeCorruption(String bad) {
        time.usePersisted();var s=setup();fail(s,service(store(),null));var receipts=selectorRows();var financial=financialState();
        assertThatThrownBy(()->transaction.requiresNew(()->{
            switch(bad) {
                case "credentials"->jdbc.update("update payment_accounts set credentials_ref='grace-inert-drift' where id=?",billing.id().value());
                case "disabled"->jdbc.update("update payment_accounts set account_status='DISABLED' where id=?",billing.id().value());
                case "policy"->jdbc.update("update site_subscription_policies set version=version+1 where site_id=?",site.value());
            }
            var st=store();st.lockGraceLastSecondInTransaction(s.admission());return st.prepareGraceLastSecondInTransaction(s.admission(),store().requireFailureInTransaction(s.admission()),Instant.now());
        })).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_failed_grace_preparations")).isZero();assertThat(selectorRows()).isEqualTo(receipts);assertThat(financialState()).isEqualTo(financial);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void graceRawSourceMismatchUsesCoherentDatabaseHashAndDistinctiveCurrentProof() {
        time.usePersisted();var s=setup();var f=fail(s,service(store(),null));var original=s.admission().sourceSha();var receipts=selectorRows();
        assertThatThrownBy(()->transaction.requiresNew(()->{
            jdbc.execute("alter table stripe_billing_failed_renewals disable trigger failed_renewal_immutable");
            assertThat(jdbc.update("update stripe_billing_failed_renewals set source_sha=? where id=?","f".repeat(40),s.admission().id())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select source_sha from stripe_billing_failed_renewals where id=?",String.class,s.admission().id())).isEqualTo("f".repeat(40)).isNotEqualTo(original);
            assertThatThrownBy(()->insertGraceRaw(s,f,true)).satisfies(error->assertGraceSqlProof(error,"Owned grace preparation authority unavailable"));
            throw new IllegalStateException("rollback coherent source mismatch control");
        })).hasMessage("rollback coherent source mismatch control");
        assertThat(jdbc.queryForObject("select source_sha from stripe_billing_failed_renewals where id=?",String.class,s.admission().id())).isEqualTo(original);
        assertThat(selectorRows()).isEqualTo(receipts);assertThat(count("stripe_billing_failed_grace_preparations")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    private static void assertGraceSqlProof(Throwable error,String message) {
        Throwable cause=error;while(cause!=null&&!(cause instanceof java.sql.SQLException))cause=cause.getCause();
        assertThat(cause).as("Expected the V38 proof exception, not an FK/unique/fixture failure").isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException)cause).getSQLState()).isEqualTo("23514");assertThat(cause.getMessage()).contains(message);
    }
    @Test void graceConcurrentRequestsHaveExactlyOneDurableWinner() throws Exception {
        time.usePersisted();var s=setup();fail(s,service(store(),null));var start=new CountDownLatch(1);var financial=financialState();
        try(var workers=new BoundedWorkers()) {
            Callable<Boolean> work=()->{awaitRelease(start);try{service(store(),null).prepareGraceLastSecond(s.admission());return true;}catch(RuntimeException refused){return false;}};
            var a=workers.submit(work);var b=workers.submit(work);start.countDown();assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(count("stripe_billing_failed_grace_preparations")).isEqualTo(1);assertThat(financialState()).isEqualTo(financial);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void graceRefusesHeldSpeculativeMethodForeignKeysBeforeBlockingCreateAndCannotStopMethodWinner() throws Exception {
        time.usePersisted();var s=setup();fail(s,service(store(),null));var claimed=new CountDownLatch(1);var release=new CountDownLatch(1);
        var delegate=lifecycleOperations;
        var wrapped=(SubscriptionOperationStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SubscriptionOperationStore.class},(proxy,method,args)->{
            try {var result=method.invoke(delegate,args);
                if(method.getName().equals("claim")){claimed.countDown();awaitRelease(release);}return result;
            } catch(InvocationTargetException failure){throw failure.getCause();}
        });
        try(var workers=new BoundedWorkers()) {
            var method=workers.submit(()->methodService(new JdbcOwnedFailedRenewalMethodStore(jdbc),wrapped).reserveMethod(s.admission(),managementContext("grace-method-race"),true));
            try {
                assertThat(claimed.await(5,TimeUnit.SECONDS)).isTrue();
                var grace=workers.submit(()->{assertThatThrownBy(()->service(store(),null).prepareGraceLastSecond(s.admission())).isInstanceOf(RuntimeException.class);return true;});
                assertThat(grace.get(5,TimeUnit.SECONDS)).isTrue();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
                release.countDown();assertThat(method.get(10,TimeUnit.SECONDS).admission()).isEqualTo(s.admission());
            } finally {release.countDown();}
        }
        assertThat(count("stripe_billing_failed_grace_preparations")).isZero();assertThat(count("stripe_billing_failed_method_reservations")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    private Object[] graceFingerprintFields(UUID id,Admission a,Failure f,long target,String key,Instant at) {
        var u=a.firstRenewalAdmission();var i=u.receipt().intent();var c=i.clock();var o=c.owner();
        return new Object[]{"gp:owned-failed-grace-preparation:v1",id,a.id(),u.id(),u.fingerprint(),a.sourceSha(),a.admittedAt(),u.root(),u.attempt(),u.attemptVersion(),u.bill(),u.billVersion(),u.payment(),u.paymentVersion(),u.invoice(),u.allocation(),u.paymentIntent(),u.subscriptionRevision(),u.policyVersion(),o.intent(),o.run(),o.account().value(),o.site().value(),o.customer().value(),o.externalAccount(),o.credentialsRef(),o.apiVersion(),c.reference(),c.creationOperation(),c.frozenTime(),i.id(),i.subscription(),i.createOperation(),i.customerBinding(),i.customerReference(),u.receipt().reference(),u.receipt().itemReference(),a.boundary().id(),a.ready().id(),a.ready().ack().advance().id(),a.ready().ack().advance().target(),f.claimId(),f.billId(),f.billVersion(),f.invoice(),f.allocation(),f.paymentIntent(),f.period().start(),f.period().end(),f.graceDeadline(),f.revision(),f.observationSequence(),f.recordedAt(),target,key,at};
    }
    @Test void gracePreparationRefusesRecoveredPublication() {
        time.usePersisted();var s=setup();var svc=service(store(),null);fail(s,svc);
        var recovery=svc.reserveExistingRecovery(s.admission());var read=svc.beginRecoveredRead(s.admission(),UUID.randomUUID());var now=Instant.now();
        var key=lifecycleOperations.find(site,current().id(),recovery.operationId()).orElseThrow().providerOperationKey();
        svc.publishRecovery(s.admission(),read,new RecoveredEvidence(snapshot(s,true,key,now),now));
        var before=selectorRows();assertThatThrownBy(()->svc.prepareGraceLastSecond(s.admission())).isInstanceOf(RuntimeException.class);
        assertThat(selectorRows()).isEqualTo(before);assertThat(count("stripe_billing_failed_grace_preparations")).isZero();
    }
    @Test void graceRawInsertAndServiceRefuseUnresolvedPortal() {
        time.usePersisted();var s=setup();var svc=service(store(),null);var f=fail(s,svc);var id=UUID.randomUUID();var now=Instant.now();
        var portal=new SubscriptionOperation(id,site,customer,current().id(),SubscriptionOperation.Kind.PORTAL,SubscriptionOperation.Status.RESERVED,"grace-negative-portal","a".repeat(64),"v1.portal."+"b".repeat(64),"subscription:"+billing.id().value()+":"+id+":PORTAL",f.revision(),s.upstream().policyVersion(),null,null,now,now,0);
        transaction.requiresNew(()->lifecycleOperations.claim(portal));var before=selectorRows();var financial=financialState();
        assertThatThrownBy(()->svc.prepareGraceLastSecond(s.admission())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->insertGraceRaw(s,f)).isInstanceOf(RuntimeException.class);
        assertThat(selectorRows()).isEqualTo(before);assertThat(financialState()).isEqualTo(financial);assertThat(count("stripe_billing_failed_grace_preparations")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void graceRequiresReadCommittedForBothPreflightAndRawInsert() {
        time.usePersisted();var s=setup();var f=fail(s,service(store(),null));
        for(boolean raw:List.of(false,true))assertThatThrownBy(()->transaction.requiresNew(()->{
            jdbc.execute("set transaction isolation level repeatable read");
            if(raw)insertGraceRaw(s,f);else store().lockGraceLastSecondInTransaction(s.admission());return true;
        })).isInstanceOf(RuntimeException.class).hasStackTraceContaining("READ COMMITTED");
        assertThat(count("stripe_billing_failed_grace_preparations")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"stripe_billing_first_renewals","stripe_billing_failed_renewals","stripe_billing_failed_renewal_failures","customers","subscriptions","stripe_billing_failed_renewal_reads","stripe_billing_first_renewal_ready"})
    void graceRawInsertAndPublicPrepareRefuseExactReferenceContentionBeforeHolderRelease(String table) throws Exception {
        time.usePersisted();var s=setup();var f=fail(s,service(store(),null));var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        UUID row=switch(table){case "stripe_billing_first_renewals"->s.upstream().id();case "stripe_billing_failed_renewals","stripe_billing_failed_renewal_failures"->s.admission().id();case "customers"->customer.value();case "stripe_billing_failed_renewal_reads"->f.claimId();case "stripe_billing_first_renewal_ready"->s.admission().ready().id();default->current().id();};
        if(table.equals("stripe_billing_failed_renewal_reads"))assertThat(jdbc.queryForObject("select phase from stripe_billing_failed_renewal_reads where id=?",String.class,row)).isEqualTo("FAILED");
        if(table.equals("stripe_billing_first_renewal_ready"))assertThat(row).isEqualTo(s.ready().id());
        var financial=financialState();var receipts=selectorRows();
        try(var workers=new BoundedWorkers()) {
            var holder=workers.submit(()->transaction.requiresNew(()->{assertThat(jdbc.queryForList("select * from "+table+" where "+(table.equals("stripe_billing_failed_renewal_failures")?"admission_id":"id")+"=? for update",row)).hasSize(1);locked.countDown();awaitRelease(release);return true;}));
            try {
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                for(boolean raw:List.of(true,false)) {
                    var contender=workers.submit(()->{
                        assertThatThrownBy(()->{if(raw)insertGraceRaw(s,f);else service(store(),null).prepareGraceLastSecond(s.admission());})
                            .satisfies(OwnedFailedRenewalIntegrationTest::assertGraceLockUnavailable);
                        return true;
                    });
                    assertThat(contender.get(5,TimeUnit.SECONDS)).isTrue();
                    assertThat(holder.isDone()).isFalse();assertThat(release.getCount()).isEqualTo(1);
                    assertThat(count("stripe_billing_failed_grace_preparations")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
                    assertThat(financialState()).isEqualTo(financial);assertThat(selectorRows()).isEqualTo(receipts);
                }
            }
            finally{release.countDown();}assertThat(holder.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("stripe_billing_failed_grace_preparations")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
        assertThat(financialState()).isEqualTo(financial);assertThat(selectorRows()).isEqualTo(receipts);
    }
    private static void assertGraceLockUnavailable(Throwable error) {
        Throwable cause=error;while(cause!=null&&!(cause instanceof java.sql.SQLException))cause=cause.getCause();
        assertThat(cause).as("Expected immediate PostgreSQL NOWAIT refusal while the exact referenced row remains locked").isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException)cause).getSQLState()).isEqualTo("55P03");
    }
    private void insertGraceRaw(Setup s,Failure f) {
        insertGraceRaw(s,f,false);
    }
    private void insertGraceRaw(Setup s,Failure f,boolean databaseHash) {
        var id=UUID.randomUUID();long target=f.graceDeadline().getEpochSecond()-1;var key="billing-failed-grace-v1:"+s.admission().id()+":1";var at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var hash=databaseHash?jdbc.queryForObject("select owned_failed_grace_fingerprint(?,?,?,?,?)",String.class,id,s.admission().id(),target,key,java.sql.Timestamp.from(at)):OwnedFirstRenewal.fingerprint(graceFingerprintFields(id,s.admission(),f,target,key,at));
        assertThat(hash).matches("[a-f0-9]{64}");
        jdbc.update("insert into stripe_billing_failed_grace_preparations(id,admission_id,failure_claim_id,renewal_bill,predecessor_ready,grace_deadline,target,operation_key,fingerprint,prepared_at) values(?,?,?,?,?,?,?,?,?,?)",id,s.admission().id(),f.claimId(),f.billId(),s.ready().id(),java.sql.Timestamp.from(f.graceDeadline()),target,key,hash,java.sql.Timestamp.from(at));
    }
    private record Setup(OwnedFirstRenewal.Admission upstream,OwnedFirstRenewal.LaterReady ready,OwnedFirstRenewal.Boundary boundary,Admission admission) {}
    private Setup setup() {
        var upstream=paidAdmission();var first=advance(upstream,OwnedFirstRenewal.Step.BOUNDARY);var pending=boundary(upstream,first);
        var ready=advance(upstream,OwnedFirstRenewal.Step.FINALIZATION);
        if(time.persisted==null)time.selected=Instant.ofEpochSecond(receipt.periodEnd()+3600);
        else assertPersistedSampleUnchanged(); // Authenticate real READY2 through the concrete upstream selector before admission exists.
        var admission=service(store(),null).admit(upstream,ready,pending.boundary());
        if(time.persisted!=null)assertPersistedSampleUnchanged();
        return new Setup(upstream,ready,pending.boundary(),admission);
    }
    private static SubscriptionBillingTimePort.Sample persistedExpected() {
        return new SubscriptionBillingTimePort.Sample(Instant.ofEpochSecond(1702682000L),SubscriptionBillingTimePort.Mode.ISOLATED);
    }
    private Map<String,List<String>> selectorRows() {
        var state=new TreeMap<>(confirmationRows());
        for(var table:List.of("stripe_billing_first_renewals","stripe_billing_first_renewal_steps","stripe_billing_first_renewal_acks",
                "stripe_billing_first_renewal_ready","stripe_billing_first_renewal_boundary","stripe_billing_first_renewal_read_claims",
                "stripe_billing_first_renewal_publications","stripe_billing_clock_intents","stripe_billing_clock_ready",
                "payment_accounts","sites","customers"))state.put(table,rows(table));
        return state;
    }
    private void assertPersistedSampleUnchanged() {
        var before=financialState();var receipts=selectorRows();int calls=http.calls.size();
        var operational=Instant.parse("2026-11-08T12:34:56Z");
        assertThat(transaction.required(()->time.sample(billing.id(),site,customer,operational))).isEqualTo(persistedExpected());
        assertThat(time.persistedOperationalTimes.getLast()).isEqualTo(operational);
        assertThat(financialState()).isEqualTo(before);assertThat(selectorRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
    }
    @Test void persistedTimeRequiresAmbientTransactionAndDoesNotTouchTheFailedOwner() {
        time.usePersisted();setup();var before=financialState();var receipts=selectorRows();int calls=http.calls.size();
        assertThat(transaction.active()).isFalse();
        assertThatThrownBy(()->time.persisted.sample(billing.id(),site,customer,Instant.now())).hasMessage("OWNED_TIME_REQUIRES_CALLER_TRANSACTION");
        assertThat(financialState()).isEqualTo(before);assertThat(selectorRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
        assertPersistedSampleUnchanged();
    }
    @Test void persistedTimeRejectsForeignAccountForEnrolledSiteCustomerWithoutFallbackOrMutation() {
        time.usePersisted();setup();var before=financialState();var receipts=selectorRows();int calls=http.calls.size();
        var foreign=new PaymentAccountId(UUID.randomUUID());
        assertThatThrownBy(()->transaction.required(()->time.persisted.sample(foreign,site,customer,Instant.now())))
            .hasMessage("OWNED_TIME_AUTHORITY_MISSING");
        assertThat(financialState()).isEqualTo(before);assertThat(selectorRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
        assertPersistedSampleUnchanged();
    }
    @ParameterizedTest @ValueSource(strings={"credentials_ref='changed-inert-ref'","account_status='DISABLED'"})
    void persistedTimeRejectsAccountAuthorityDriftWithoutWritingAndRestoresAfterRollback(String mutation) {
        time.usePersisted();setup();var before=financialState();var receipts=selectorRows();int calls=http.calls.size();
        assertThatThrownBy(()->transaction.requiresNew(()->{
            assertThat(jdbc.update("update payment_accounts set "+mutation+" where id=?",billing.id().value())).isEqualTo(1);
            var drifted=selectorRows();
            assertThatThrownBy(()->time.persisted.sample(billing.id(),site,customer,Instant.now())).hasMessage("OWNED_TIME_AUTHORITY_MISSING");
            assertThat(selectorRows()).isEqualTo(drifted);assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);
            throw new IllegalStateException("rollback account drift control");
        })).hasMessage("rollback account drift control");
        assertThat(financialState()).isEqualTo(before);assertThat(selectorRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
        assertPersistedSampleUnchanged();
    }
    @Test void persistedTimeRejectsDamagedExactReadyAndAuthenticatesAgainAfterRollback() {
        time.usePersisted();var s=setup();var before=financialState();var receipts=selectorRows();int calls=http.calls.size();
        assertThatThrownBy(()->transaction.requiresNew(()->{
            // Negative-only corruption: disable only this immutable-evidence trigger in a transaction
            // that must roll back. No successful proof, admission, ACK or lifecycle row is inserted.
            jdbc.execute("alter table stripe_billing_first_renewal_ready disable trigger first_renewal_ready_immutable");
            assertThat(jdbc.update("update stripe_billing_first_renewal_ready set frozen_time=frozen_time+1 where id=?",s.ready().id())).isEqualTo(1);
            var damaged=selectorRows();
            assertThatThrownBy(()->time.persisted.sample(billing.id(),site,customer,Instant.now())).hasMessage("OWNED_TIME_AUTHORITY_MISSING");
            assertThat(selectorRows()).isEqualTo(damaged);assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);
            throw new IllegalStateException("rollback READY corruption control");
        })).hasMessage("rollback READY corruption control");
        assertThat(financialState()).isEqualTo(before);assertThat(selectorRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
        assertPersistedSampleUnchanged();
        assertThatThrownBy(()->jdbc.update("update stripe_billing_first_renewal_ready set frozen_time=frozen_time+1 where id=?",s.ready().id()))
            .isInstanceOf(RuntimeException.class); // The rolled-back trigger disablement did not escape.
        assertThat(selectorRows()).isEqualTo(receipts);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void persistedTimeUnscopedQueriesRejectAllMatchingScopesEvenAfterDurableStop(boolean stopped) {
        time.usePersisted();var s=setup();if(stopped)service(store(),null).stop(s.admission(),Reason.AUTHORITY_DRIFT);
        var before=financialState();var receipts=selectorRows();int calls=http.calls.size();
        assertThatThrownBy(()->time.requireUnscopedAllowed(null,null)).hasMessage("OWNED_TIME_UNSCOPED");
        assertThatThrownBy(()->time.requireUnscopedAllowed(site,null)).hasMessage("OWNED_TIME_UNSCOPED");
        assertThatThrownBy(()->time.requireUnscopedAllowed(null,customer)).hasMessage("OWNED_TIME_UNSCOPED");
        assertThatThrownBy(()->time.requireUnscopedAllowed(site,customer)).hasMessage("OWNED_TIME_UNSCOPED");
        var foreignSite=new SiteId(UUID.randomUUID());var foreignCustomer=new CustomerId(UUID.randomUUID());
        assertThatCode(()->time.requireUnscopedAllowed(foreignSite,null)).doesNotThrowAnyException();
        assertThatCode(()->time.requireUnscopedAllowed(null,foreignCustomer)).doesNotThrowAnyException();
        assertThatCode(()->time.requireUnscopedAllowed(site,foreignCustomer)).doesNotThrowAnyException();
        assertThatCode(()->time.requireUnscopedAllowed(foreignSite,customer)).doesNotThrowAnyException();
        if(stopped)assertThatThrownBy(()->transaction.required(()->time.persisted.sample(billing.id(),site,customer,Instant.now())))
            .hasMessage("OWNED_TIME_AUTHORITY_MISSING");
        else assertPersistedSampleUnchanged();
        assertThat(financialState()).isEqualTo(before);assertThat(selectorRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(stopped?1:0);
    }
    @Test void persistedTimeAbsentFailedLanePreservesPreparedHighestStepRefusalFromConcreteUpstream() {
        time.usePersisted();var upstream=paidAdmission();var first=advance(upstream,OwnedFirstRenewal.Step.BOUNDARY);
        var pending=boundary(upstream,first);renewals.prepare(upstream,OwnedFirstRenewal.Step.FINALIZATION,pending.boundary(),Instant.now());
        var before=financialState();var receipts=selectorRows();int calls=http.calls.size();
        assertThat(count("stripe_billing_failed_renewals")).isZero();
        assertThatThrownBy(()->transaction.required(()->time.persisted.sample(billing.id(),site,customer,Instant.now())))
            .hasMessage("OWNED_TIME_AUTHORITY_MISSING");
        assertThat(financialState()).isEqualTo(before);assertThat(selectorRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
    }
    @Test void persistedTimeUsesConcreteOrdinaryFallbackForUnenrolledOwnerAndDisjointSiteCustomer() {
        time.usePersisted();setup();var ordinaryCustomer=new CustomerId(UUID.randomUUID());
        // Owner prerequisite only: no clock, readiness, subscription, lifecycle or financial proof is seeded.
        jdbc.update("insert into customers(id,site_id,status,email_ciphertext,email_nonce,email_key_version,email_digest,email_digest_key_version,created_at,updated_at) values (?,?,'ACTIVE',decode(repeat('00',16),'hex'),decode(repeat('00',12),'hex'),'test-only',?,'v1',now(),now())",
            ordinaryCustomer.value(),site.value(),"digest-"+ordinaryCustomer.value());
        var before=financialState();var receipts=selectorRows();int calls=http.calls.size();var utc=Instant.parse("2026-11-08T12:34:56Z");
        var expected=new SubscriptionBillingTimePort.Sample(utc,SubscriptionBillingTimePort.Mode.ORDINARY);
        SubscriptionBillingTimePort.Sample ordinarySample=transaction.required(()->time.persisted.sample(billing.id(),site,ordinaryCustomer,utc));
        assertThat(ordinarySample).isEqualTo(expected);
        var foreignSite=new SiteId(UUID.randomUUID());
        SubscriptionBillingTimePort.Sample disjointSiteSample=transaction.required(()->time.persisted.sample(billing.id(),foreignSite,customer,utc));
        assertThat(disjointSiteSample).isEqualTo(expected);
        assertThatCode(()->time.requireUnscopedAllowed(site,ordinaryCustomer)).doesNotThrowAnyException();
        assertThat(financialState()).isEqualTo(before);assertThat(selectorRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
        assertPersistedSampleUnchanged();
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
        var ready=advance(u,OwnedFirstRenewal.Step.FINALIZATION);
        if(time.persisted==null)time.selected=Instant.ofEpochSecond(receipt.periodEnd()+3600);
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
    private record ReservedMethod(Setup setup,Failure failure,OwnedFailedRenewalMethod.Reservation reservation,
            OwnedFailedRenewalMethodService service,dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext context,
            String customerToken) {
        @Override public String toString(){return "ReservedMethod[redacted]";}
    }
    private record PendingMethod(Setup setup,Failure failure,OwnedFailedRenewalMethod.Reservation reservation,
            OwnedFailedRenewalMethodService service,dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext context,
            ProviderOperation setupStep,int callStart,String customerToken,boolean browser) {
        @Override public String toString(){return "PendingMethod[redacted]";}
    }
    private record BoundMethod(PendingMethod pending,SubscriptionBillingProofStore.Setup proof,SubscriptionOperation operation,int continuationStart) {
        @Override public String toString(){return "BoundMethod[redacted]";}
    }

    /** Genuine failed observer, service reservation, typed first dispatches, SDK replies and ordinary proof stores.
     * Provider-side confirmation is synthetic observation only; no browser capability is issued or claimed. */
    private PreparedMethod preparedMethod() {
        return preparedMethod(pendingMethod(false));
    }
    private PreparedMethod preparedMethod(PendingMethod pending) {
        var bound=boundMethod(pending);
        var s=pending.setup();var failure=pending.failure();var r=pending.reservation();var svc=pending.service();
        var context=pending.context();var setupStep=pending.setupStep();var callStart=pending.callStart();var before=financialState();
        int continuationStart=bound.continuationStart();
        var subscription=current();var o=bound.operation();var proof=bound.proof();
        var binding=bindings.findCustomer(billing.id(),site,customer).orElseThrow();var client=clients.create(credentials.resolve(billing));
        assertThat(lifecycleOperations.find(site,subscription.id(),o.id())).contains(o);
        ProviderOperation methodStep;
        if(pending.browser()){
            int methodStart=http.calls.size();http.reply(methodUpdateBody(bound));
            methodStep=methodProducer(pending,operations).apply(r,context);
            assertOriginalMethodRequest(bound,methodStep,methodStart);
        }else{
            var update=operations.reserveOwnedFailedRenewalMethodStep(billing,r,ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
            String methodOwner=UUID.randomUUID().toString();var updateClaim=operations.claim(update.id(),methodOwner,Instant.now(),Duration.ofSeconds(60)).orElseThrow();
            http.reply(methodUpdateBody(bound));
            var updateDispatch=operations.requireOwnedFailedRenewalMethodDispatch(billing,r,updateClaim,Instant.now());
            String updateKey=new ProviderOperationGuard().requireDispatch(updateDispatch,billing,update.fingerprint(),Instant.now());
            try {var reply=client.v1().subscriptions().update(subscription.providerReference(),com.stripe.param.SubscriptionUpdateParams.builder().setDefaultPaymentMethod(proof.method()).build(),writeOptions(updateKey));
                assertThat(reply.getId()).isEqualTo(subscription.providerReference());assertThat(reply.getObject()).isEqualTo("subscription");assertThat(reply.getLivemode()).isFalse();
                assertThat(reply.getCustomer()).isEqualTo(binding.providerReference());assertThat(reply.getDefaultPaymentMethod()).isEqualTo(proof.method());
            }catch(com.stripe.exception.StripeException failureSdk){throw new AssertionError(failureSdk);}
            methodStep=operations.acknowledge(updateDispatch.id(),methodOwner,updateDispatch.version(),subscription.providerReference());
        }
        assertThat(setupStep.version()).isEqualTo(2);assertThat(methodStep.version()).isEqualTo(2);
        assertThat(financialState()).isEqualTo(before);assertThat(lifecycleOperations.find(site,subscription.id(),o.id())).contains(o);
        assertThat(http.calls.get(callStart).method().name()+" "+http.calls.get(callStart).url().getPath()).isEqualTo("POST /v1/setup_intents");
        assertThat(http.calls.subList(continuationStart,http.calls.size()).stream().map(request->request.method().name()+" "+request.url().getPath()).toList())
            .containsExactly("GET /v1/setup_intents/seti_saved","GET /v1/payment_methods/pm_saved","POST /v1/subscriptions/"+subscription.providerReference());
        assertThat(http.calls.get(callStart).options().getIdempotencyKey()).isEqualTo(setupStep.operationKey());
        assertThat(http.calls.get(continuationStart+2).options().getIdempotencyKey()).isEqualTo(methodStep.operationKey());assertThat(http.replies).isEmpty();
        var processing=svc.recordMethodUpdateReply(r,context);assertThat(processing.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);assertThat(processing.version()).isEqualTo(1);
        assertThat(processing.providerReference()).isEqualTo(subscription.providerReference());assertThat(financialState()).isEqualTo(before);
        return new PreparedMethod(s,failure,r,svc,setupStep,methodStep);
    }
    /** Genuine independent succeeded SETUP/card retrieval and bind; hands original context/token on through pending. */
    private BoundMethod boundMethod(PendingMethod pending) {
        var before=financialState();int continuationStart=http.calls.size();var subscription=current();
        var o=lifecycleOperations.find(site,subscription.id(),pending.reservation().setupOperationId()).orElseThrow();
        var proof=methodBillingProofs.setup(billing.id(),o.id()).orElseThrow();
        http.reply(setupBody(o,pending.setupStep(),"succeeded"));http.reply(methodBody());
        if(pending.browser()){
            proof=bindingProducer(pending.service()).bind(pending.reservation(),pending.context());assertOriginalBindingReads(continuationStart);
        }else{
            var binding=bindings.findCustomer(billing.id(),site,customer).orElseThrow();var client=clients.create(credentials.resolve(billing));
            try {
                var succeeded=client.v1().setupIntents().retrieve(proof.reference());OwnedMethodRemoteChecks.requireSetup(succeeded,subscription,o,binding,pending.setupStep());
                assertThat(succeeded.getStatus()).isEqualTo("succeeded");assertThat(succeeded.getPaymentMethod()).isEqualTo("pm_saved");
                var method=client.v1().paymentMethods().retrieve(succeeded.getPaymentMethod());
                assertThat(method.getId()).isEqualTo("pm_saved");assertThat(method.getObject()).isEqualTo("payment_method");assertThat(method.getLivemode()).isFalse();
                assertThat(method.getCustomer()).isEqualTo(binding.providerReference());assertThat(method.getType()).isEqualTo("card");assertThat(method.getCard()).isNotNull();
                proof=methodBillingProofs.bindMethod(proof,method.getId());
            }catch(com.stripe.exception.StripeException failureSdk){throw new AssertionError(failureSdk);}
        }
        assertThat(lifecycleOperations.find(site,subscription.id(),o.id())).contains(o);assertThat(financialState()).isEqualTo(before);
        assertThat(http.calls.subList(continuationStart,http.calls.size()).stream().map(request->request.method().name()+" "+request.url().getPath()).toList())
            .containsExactly("GET /v1/setup_intents/seti_saved","GET /v1/payment_methods/pm_saved");
        assertThat(http.replies).isEmpty();return new BoundMethod(pending,proof,o,continuationStart);
    }
    private StripeBillingOwnedFailedRenewalBindingAdapter bindingProducer(OwnedFailedRenewalMethodService service){
        return new StripeBillingOwnedFailedRenewalBindingAdapter(service,credentials,clients,Clock.systemUTC());
    }
    private void assertOriginalBindingReads(int start){
        var reads=http.calls.subList(start,http.calls.size());assertThat(reads).hasSize(2);
        assertThat(reads.stream().map(r->r.method().name()+" "+r.url().getPath()).toList()).containsExactly("GET /v1/setup_intents/seti_saved","GET /v1/payment_methods/pm_saved");
        assertThat(reads).allSatisfy(r->{assertThat(r.params()).isNullOrEmpty();assertThat(r.options().getIdempotencyKey()).isNull();assertThat(r.options().getMaxNetworkRetries()).isZero();
            assertThat(r.options().getConnectTimeout()).isBetween(1,2000);assertThat(r.options().getReadTimeout()).isBetween(1,2000);assertThat(r.options().getStripeAccount()).isNull();
            assertThat(r.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");});
    }
    private static void bindingUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable work){
        assertThatThrownBy(work).isExactlyInstanceOf(PaymentApplicationException.class).hasMessage("Owned method binding unavailable").hasNoCause()
            .satisfies(failure->{var e=(PaymentApplicationException)failure;assertThat(e.code()).isEqualTo("OWNED_METHOD_BINDING_UNAVAILABLE");assertThat(e.status()).isEqualTo(409);assertThat(e.getSuppressed()).isEmpty();});
    }
    private BoundMethod boundBrowserMethod(int sessionSeconds) {
        var pending=pendingMethod(true,sessionSeconds);var root=browserRoot(pending);releaseBrowser(pending,root,selectBrowser(pending,root));
        assertThat(consumeBrowser(pending,root)).isTrue();return boundMethod(pending);
    }
    private StripeBillingOwnedFailedRenewalMethodAdapter methodProducer(PendingMethod p,dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore steps){
        return new StripeBillingOwnedFailedRenewalMethodAdapter(p.service(),steps,credentials,clients,digest,Clock.systemUTC());
    }
    private String methodUpdateBody(BoundMethod bound){
        return "{\"id\":\""+current().providerReference()+"\",\"object\":\"subscription\",\"livemode\":false,\"customer\":\""+bound.pending().reservation().binding().providerReference()+"\",\"default_payment_method\":\""+bound.proof().method()+"\"}";
    }
    private void assertOriginalMethodRequest(BoundMethod bound,ProviderOperation ack,int start){
        var p=bound.pending();var o=bound.operation();var proof=bound.proof();
        assertThat(http.calls).hasSize(start+1);var request=http.calls.get(start);
        assertThat(request.method().name()).isEqualTo("POST");assertThat(request.url().getPath()).isEqualTo("/v1/subscriptions/"+current().providerReference());
        assertThat(request.params()).containsOnlyKeys("default_payment_method").containsEntry("default_payment_method",proof.method());
        assertThat(request.options().getIdempotencyKey()).isEqualTo(o.providerOperationKey()+":METHOD");assertThat(request.options().getMaxNetworkRetries()).isZero();
        assertThat(request.options().getConnectTimeout()).isBetween(1,2000);assertThat(request.options().getReadTimeout()).isBetween(1,2000);
        assertThat(request.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");assertThat(request.options().getStripeAccount()).isNull();
        assertThat(ack.kind()).isEqualTo(ProviderOperation.Kind.SUBSCRIPTION_UPDATE);assertThat(ack.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(ack.version()).isEqualTo(2);
        assertThat(ack.providerReference()).isEqualTo(current().providerReference());assertThat(ack.operationKey()).isEqualTo(o.providerOperationKey()+":METHOD");
        assertThat(ack.fingerprint()).isEqualTo(StripeRequestFingerprint.of(digest,billing,"SUBSCRIPTION_METHOD_V1",o.id().toString(),o.providerOperationKey()+":METHOD",o.fingerprint(),current().providerReference(),p.reservation().binding().providerReference(),proof.step().toString(),proof.reference(),proof.method(),"consent:true"));
        assertThat(ack.fingerprint()).isEqualTo(jdbc.queryForObject("select owned_method_step_fingerprint(?,true)",String.class,p.reservation().id()));
        assertThat(ack.retryBefore()).isEqualTo(ack.firstDispatchedAt().plusSeconds(23*3600));assertThat(ack.leaseOwner()).isNull();assertThat(ack.leaseExpiresAt()).isNull();
        assertThat(methodBillingProofs.setup(billing.id(),o.id())).contains(proof);assertThat(operations.findById(p.setupStep().id())).contains(p.setupStep());assertThat(http.replies).isEmpty();
    }
    private dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore methodSteps(java.util.function.Consumer<String> before,java.util.function.Consumer<String> after){
        return (dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore.class},(proxy,method,args)->{
            assertThat(transaction.active()).isFalse();before.accept(method.getName());
            try{var result=method.invoke(operations,args);after.accept(method.getName());return result;}
            catch(InvocationTargetException failure){throw failure.getCause();}
        });
    }
    private static void methodUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable work){
        assertThatThrownBy(work).isExactlyInstanceOf(PaymentApplicationException.class).hasMessage("Owned method application unavailable").hasNoCause()
            .satisfies(failure->{var e=(PaymentApplicationException)failure;assertThat(e.code()).isEqualTo("OWNED_METHOD_APPLY_UNAVAILABLE");assertThat(e.status()).isEqualTo(409);assertThat(e.getSuppressed()).isEmpty();});
    }
    /** Source-only hosted Task 1 controls: genuine unbound original proof and committed browser receipts. */
    private PendingMethod committedBindingPending(int sessionSeconds){
        var p=pendingMethod(true,sessionSeconds);var root=browserRoot(p);releaseBrowser(p,root,selectBrowser(p,root));assertThat(consumeBrowser(p,root)).isTrue();return p;
    }
    @Test void genuineServiceBindsOnlyOriginalUnboundProofBeforeAnyMethodOrFinancialChange(){
        var p=committedBindingPending(0);var r=p.reservation();var before=financialState();int calls=http.calls.size();
        var proof=methodBillingProofs.setup(billing.id(),r.setupOperationId()).orElseThrow();assertThat(proof.method()).isNull();
        var c=p.service().inspectBrowserBindingPreparation(r,p.context());assertThat(c.setupProof()).isEqualTo(proof);assertThat(c.setupStep()).isEqualTo(p.setupStep());
        var bound=p.service().bindBrowserMethod(c,p.context(),"pm_saved");
        assertThat(bound).isEqualTo(new SubscriptionBillingProofStore.Setup(proof.operation(),proof.subscription(),proof.account(),proof.step(),proof.reference(),"pm_saved"));
        assertThat(methodBillingProofs.setup(billing.id(),r.setupOperationId())).contains(bound);
        assertThatThrownBy(()->p.service().inspectBrowserBindingPreparation(r,p.context())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->p.service().bindBrowserMethod(c,p.context(),"pm_other")).isInstanceOf(RuntimeException.class);
        assertBindingOnlyState(p,before,calls);
    }
    @Test void bindingUnboundPortRefusesUncommittedConsumptionEvenAfterReleasedSavepoint(){
        var p=pendingMethod(true);var root=browserRoot(p);releaseBrowser(p,root,selectBrowser(p,root));var before=financialState();int calls=http.calls.size();
        var session=((SubscriptionActor.Customer)p.context().actor()).session();
        assertThatThrownBy(()->p.service().inspectBrowserBindingPreparation(p.reservation(),p.context())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->browserPhase(p,pending->{
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{var savepoint=connection.setSavepoint();
                assertThat(browserStore().consumeBrowserFirstInTransaction(pending,root,"c".repeat(64),"d".repeat(64),browserNow())).isTrue();connection.releaseSavepoint(savepoint);return null;});
            return browserStore().requireBrowserUnboundSetupInTransaction(p.reservation(),session,browserNow());
        })).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_method_browser_consumptions")).isZero();assertThat(methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId()).orElseThrow().method()).isNull();
        assertBindingOnlyState(p,before,calls);
    }
    @Test void bindingFailureAfterActualConditionalUpdateRollsBackInOwningTransaction(){
        var p=committedBindingPending(0);var before=financialState();int calls=http.calls.size();var c=p.service().inspectBrowserBindingPreparation(p.reservation(),p.context());
        var wrote=new java.util.concurrent.atomic.AtomicBoolean();var original=browserStore();
        var broken=(OwnedFailedRenewalMethodStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{OwnedFailedRenewalMethodStore.class},(proxy,method,args)->{
            try{var result=method.invoke(original,args);if(method.getName().equals("bindBrowserSetupInTransaction")){
                assertThat(transaction.active()).isTrue();assertThat(jdbc.queryForObject("select method_reference from stripe_subscription_setup_proofs where operation_id=?",String.class,p.reservation().setupOperationId())).isEqualTo("pm_saved");wrote.set(true);
                throw new IllegalStateException("after actual conditional binding");}return result;
            }catch(InvocationTargetException failure){throw failure.getCause();}
        });
        assertThatThrownBy(()->methodService(broken).bindBrowserMethod(c,p.context(),"pm_saved")).hasMessage("after actual conditional binding");
        assertThat(wrote).isTrue();assertThat(methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId())).contains(c.setupProof());assertBindingOnlyState(p,before,calls);
    }
    @Test void competingServiceBindingCannotAdoptOrReplaceTheFirstCommittedMethod(){
        var p=committedBindingPending(0);var before=financialState();int calls=http.calls.size();
        var first=p.service().inspectBrowserBindingPreparation(p.reservation(),p.context());var competitor=p.service().inspectBrowserBindingPreparation(p.reservation(),p.context());
        var bound=p.service().bindBrowserMethod(first,p.context(),"pm_saved");
        assertThatThrownBy(()->p.service().bindBrowserMethod(competitor,p.context(),"pm_other")).isInstanceOf(RuntimeException.class);
        assertThat(methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId())).contains(bound);assertBindingOnlyState(p,before,calls);
    }
    @Test void secondLiveSessionWithOriginalKeyCannotBindAfterOriginalRevocation(){
        var p=committedBindingPending(0);var before=financialState();int calls=http.calls.size();var c=p.service().inspectBrowserBindingPreparation(p.reservation(),p.context());
        var original=((SubscriptionActor.Customer)p.context().actor()).session();
        TestClock.set(jdbc.queryForObject("select issued_at from customer_sessions where id=?",java.sql.Timestamp.class,original.sessionId().value()).toInstant());
        var issued=sessions.issue(site,customer,original.origin());var second=sessions.resolveRequired(issued.rawToken(),original.origin());TestClock.set(Instant.now());
        assertThat(second.sessionId()).isNotEqualTo(original.sessionId());assertThat(second.expiresAt()).isEqualTo(original.expiresAt());
        assertThat(jdbc.update("update customer_sessions set revoked_at=clock_timestamp(),revoked_reason='LOGOUT' where id=?",original.sessionId().value())).isEqualTo(1);
        assertThat(transaction.requiresNew(()->sessions.revalidate(second,site,second.origin(),CustomerSessionScope.SUBSCRIPTION_MANAGE))).isEqualTo(second);
        var context=new SubscriptionCommandContext(new SubscriptionActor.Customer(second),site,customer,second.origin(),p.context().idempotencyKey(),"second");
        assertThatThrownBy(()->p.service().inspectBrowserBindingPreparation(p.reservation(),context)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->p.service().bindBrowserMethod(c,context,"pm_saved")).isInstanceOf(RuntimeException.class);
        assertThat(methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId())).contains(c.setupProof());assertBindingOnlyState(p,before,calls);
    }
    @Test void bindingWaitAcrossOriginalExpiryCannotCommitProof() throws Exception {
        var p=committedBindingPending(20);var before=financialState();int calls=http.calls.size();var c=p.service().inspectBrowserBindingPreparation(p.reservation(),p.context());
        var session=((SubscriptionActor.Customer)p.context().actor()).session();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var holder=new java.util.concurrent.atomic.AtomicInteger();
        try(var workers=new BoundedWorkers()){
            var blocker=workers.submit(()->transaction.requiresNew(()->{
                assertThat(jdbc.queryForList("select id from stripe_billing_method_browser_roots where reservation_id=? for update",UUID.class,p.reservation().id())).hasSize(1);
                holder.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));locked.countDown();
                try{if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("binding expiry wait timed out");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                return true;
            }));
            try{
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                var attempt=workers.submit(()->{assertThatThrownBy(()->p.service().bindBrowserMethod(c,p.context(),"pm_saved"))
                    .isExactlyInstanceOf(IllegalStateException.class).hasMessage("Owned method browser authority unavailable");return true;});
                boolean waiting=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline){
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where datname=current_database() and state='active' and query like '%lock_owned_method_browser%' and ?=any(pg_blocking_pids(pid)))",Boolean.class,holder.get()));
                    if(waiting)break;Thread.sleep(20);
                }
                assertThat(waiting).as("binding worker waiting for the original enrollment lock").isTrue();
                var dbNow=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();assertThat(dbNow).isBefore(session.expiresAt());
                Thread.sleep(Duration.between(dbNow,session.expiresAt()).plusMillis(100));var expired=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();
                assertThat(expired).isAfterOrEqualTo(session.expiresAt());TestClock.set(expired);release.countDown();
                assertThat(blocker.get(10,TimeUnit.SECONDS)).isTrue();assertThat(attempt.get(10,TimeUnit.SECONDS)).isTrue();
            }finally{release.countDown();}
        }
        assertThat(methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId())).contains(c.setupProof());assertBindingOnlyState(p,before,calls);
    }
    private void assertBindingOnlyState(PendingMethod p,Map<String,Object> before,int calls){
        assertThat(http.calls).hasSize(calls);assertThat(financialState()).isEqualTo(before);
        var o=lifecycleOperations.find(site,current().id(),p.reservation().setupOperationId()).orElseThrow();assertThat(o.status()).isEqualTo(SubscriptionOperation.Status.RESERVED);assertThat(o.version()).isZero();
        assertThat(operations.findById(p.setupStep().id())).contains(p.setupStep());
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where resource_id=? and kind='SUBSCRIPTION_UPDATE'",Long.class,o.id())).isZero();
        assertThat(count("stripe_billing_failed_method_completions")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    /** New adapter controls are hosted source only; actual original receipt, JDBC and service remain authoritative. */
    @ParameterizedTest @ValueSource(strings={"reference","setup-status","setup-customer","method-reference","method-customer","card"})
    void originalBindingAdapterRejectsMalformedIndependentRemoteEvidenceWithoutChangingDebtOrProof(String bad){
        var p=committedBindingPending(0);var before=financialState();int start=http.calls.size();
        var proof=methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId()).orElseThrow();
        var operation=lifecycleOperations.find(site,current().id(),proof.operation()).orElseThrow();
        var setup=dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.json(setupBody(operation,p.setupStep(),"succeeded"));
        var card=dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.json(methodBody());boolean setupBad=Set.of("reference","setup-status","setup-customer").contains(bad);
        switch(bad){case "reference"->setup.addProperty("id","seti_foreign");case "setup-status"->setup.addProperty("status","processing");case "setup-customer"->setup.addProperty("customer","cus_foreign");
            case "method-reference"->card.addProperty("id","pm_foreign");case "method-customer"->card.addProperty("customer","cus_foreign");case "card"->card.remove("card");}
        http.reply(setup.toString());if(!setupBad)http.reply(card.toString());bindingUnavailable(()->bindingProducer(p.service()).bind(p.reservation(),p.context()));
        assertThat(methodBillingProofs.setup(billing.id(),proof.operation())).contains(proof);assertBindingOnlyState(p,before,start+(setupBad?1:2));assertThat(http.replies).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"key","revoked","second-session"})
    void bindingAdapterRequiresOriginalLiveManagementIdentityBeforeAnyIndependentGet(String bad){
        var p=committedBindingPending(0);var before=financialState();int start=http.calls.size();var proof=methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId()).orElseThrow();
        var context=p.context();var original=((SubscriptionActor.Customer)context.actor()).session();
        if(bad.equals("key"))context=new SubscriptionCommandContext(context.actor(),site,customer,context.origin(),"changed-original-key","binding-refusal");
        else{
            if(bad.equals("second-session")){
                TestClock.set(jdbc.queryForObject("select issued_at from customer_sessions where id=?",java.sql.Timestamp.class,original.sessionId().value()).toInstant());
                var issued=sessions.issue(site,customer,original.origin());var second=sessions.resolveRequired(issued.rawToken(),original.origin());TestClock.set(Instant.now());
                assertThat(transaction.requiresNew(()->sessions.revalidate(second,site,second.origin(),CustomerSessionScope.SUBSCRIPTION_MANAGE))).isEqualTo(second);
                context=new SubscriptionCommandContext(new SubscriptionActor.Customer(second),site,customer,second.origin(),context.idempotencyKey(),"binding-second");
            }
            assertThat(jdbc.update("update customer_sessions set revoked_at=clock_timestamp(),revoked_reason='LOGOUT' where id=?",original.sessionId().value())).isEqualTo(1);
        }
        var supplied=context;bindingUnavailable(()->bindingProducer(p.service()).bind(p.reservation(),supplied));
        assertThat(methodBillingProofs.setup(billing.id(),proof.operation())).contains(proof);assertBindingOnlyState(p,before,start);
    }
    @Test void bindingAdapterReadUncertaintyRetainsUnboundOriginalAndDoesNotMintBrowserPermission(){
        var p=committedBindingPending(0);var before=financialState();int start=http.calls.size();var proof=methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId()).orElseThrow();
        http.disconnect();bindingUnavailable(()->bindingProducer(p.service()).bind(p.reservation(),p.context()));
        assertThat(methodBillingProofs.setup(billing.id(),proof.operation())).contains(proof);assertBindingOnlyState(p,before,start+1);
        var browserReceipts=rows("stripe_billing_method_browser_consumptions");var bound=boundMethod(p);
        assertThat(bound.proof().method()).isEqualTo("pm_saved");assertThat(rows("stripe_billing_method_browser_consumptions")).isEqualTo(browserReceipts);
        assertBindingOnlyState(p,before,start+3);
        bindingUnavailable(()->bindingProducer(p.service()).bind(p.reservation(),p.context()));assertBindingOnlyState(p,before,start+3);
    }
    @ParameterizedTest @ValueSource(strings={"failure","revoked","expiry"})
    void bindingAdapterFinalActualTransactionReauthenticatesAndRollsBackItsProofWrite(String bad){
        var p=committedBindingPending(bad.equals("expiry")?20:0);var before=financialState();int start=http.calls.size();
        var proof=methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId()).orElseThrow();var session=((SubscriptionActor.Customer)p.context().actor()).session();
        var wrote=new java.util.concurrent.atomic.AtomicBoolean();var actual=browserStore();
        var delayed=(OwnedFailedRenewalMethodStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{OwnedFailedRenewalMethodStore.class},(proxy,method,args)->{
            try{var result=method.invoke(actual,args);if(method.getName().equals("bindBrowserSetupInTransaction")){
                assertThat(transaction.active()).isTrue();assertThat(jdbc.queryForObject("select method_reference from stripe_subscription_setup_proofs where operation_id=?",String.class,proof.operation())).isEqualTo("pm_saved");wrote.set(true);
                if(bad.equals("failure"))throw new IllegalStateException("private after actual binding failure");
                if(bad.equals("revoked"))assertThat(jdbc.update("update customer_sessions set revoked_at=clock_timestamp(),revoked_reason='LOGOUT' where id=?",session.sessionId().value())).isEqualTo(1);
                else{var wait=Duration.between(Instant.now(),session.expiresAt()).plusMillis(50);if(!wait.isNegative())Thread.sleep(wait);TestClock.set(Instant.now());}
            }return result;}catch(InvocationTargetException failure){throw failure.getCause();}
        });
        var operation=lifecycleOperations.find(site,current().id(),proof.operation()).orElseThrow();http.reply(setupBody(operation,p.setupStep(),"succeeded"));http.reply(methodBody());
        bindingUnavailable(()->bindingProducer(methodService(delayed)).bind(p.reservation(),p.context()));assertThat(wrote).isTrue();assertOriginalBindingReads(start);
        assertThat(methodBillingProofs.setup(billing.id(),proof.operation())).contains(proof);assertBindingOnlyState(p,before,start+2);assertThat(http.replies).isEmpty();
    }
    @Test void bindingAdapterConcurrentFinalBindingCannotAdoptEvenAnIdenticalCommittedMethod() throws Exception {
        var p=committedBindingPending(0);var before=financialState();int start=http.calls.size();var expected=p.service().inspectBrowserBindingPreparation(p.reservation(),p.context());
        var afterCardInspection=new CountDownLatch(1);var release=new CountDownLatch(1);
        var postCardInspected=new java.util.concurrent.atomic.AtomicBoolean();var winnerCommitted=new java.util.concurrent.atomic.AtomicBoolean();
        var finalTransactionEntered=new java.util.concurrent.atomic.AtomicBoolean();var finalTransactionRefused=new java.util.concurrent.atomic.AtomicBoolean();
        var committedWinner=new java.util.concurrent.atomic.AtomicReference<SubscriptionBillingProofStore.Setup>();
        var losingTransaction=new SubscriptionTransactionPort(){
            @Override public <T>T required(java.util.function.Supplier<T> work){return transaction.required(work);}
            @Override public boolean active(){return transaction.active();}
            @Override public <T>T requiresNew(java.util.function.Supplier<T> work){
                if(postCardInspected.get())return transaction.requiresNew(()->{
                    assertThat(transaction.active()).isTrue();assertThat(winnerCommitted).isTrue();
                    assertThat(jdbc.queryForObject("select method_reference from stripe_subscription_setup_proofs where operation_id=? and payment_account_id=?",
                        String.class,p.reservation().setupOperationId(),billing.id().value())).isEqualTo(committedWinner.get().method());
                    assertThat(transaction.active()).isTrue();
                    finalTransactionEntered.set(true);
                    try{
                        work.get();throw new AssertionError("losing final binding transaction unexpectedly succeeded");
                    }catch(RuntimeException failure){
                        assertThat(failure).isExactlyInstanceOf(IllegalStateException.class).hasMessage("Owned method browser authority unavailable").hasNoCause();
                        finalTransactionRefused.set(true);throw failure;
                    }
                });
                var result=transaction.requiresNew(work);
                // Both SDK GETs have returned before this real successful post-card inspection.
                // Wait only after its transaction commits, with no caller transaction or locks held.
                if(result instanceof OwnedFailedRenewalMethod.BindingPreparationContext && http.calls.size()==start+2){
                    assertThat(result).isEqualTo(expected);assertOriginalBindingReads(start);assertThat(http.replies).isEmpty();
                    assertThat(transaction.active()).isFalse();postCardInspected.set(true);afterCardInspection.countDown();
                    awaitRelease(release);assertThat(transaction.active()).isFalse();assertThat(winnerCommitted).isTrue();
                }
                return result;
            }
        };
        var loser=methodService(browserStore(),lifecycleOperations,losingTransaction);
        http.reply(setupBody(expected.original().operation(),p.setupStep(),"succeeded"));http.reply(methodBody());
        try(var workers=new BoundedWorkers()){
            var attempt=workers.submit(()->{bindingUnavailable(()->bindingProducer(loser).bind(p.reservation(),p.context()));return true;});
            try{
                assertThat(afterCardInspection.await(5,TimeUnit.SECONDS)).isTrue();assertThat(postCardInspected).isTrue();
                var winner=p.service().bindBrowserMethod(expected,p.context(),"pm_saved");
                assertThat(transaction.active()).isFalse();assertThat(winner.method()).isEqualTo("pm_saved");
                assertThat(methodBillingProofs.setup(billing.id(),winner.operation())).contains(winner);
                committedWinner.set(winner);winnerCommitted.set(true);release.countDown();
                assertThat(attempt.get(10,TimeUnit.SECONDS)).isTrue();
                assertThat(finalTransactionEntered).isTrue();assertThat(finalTransactionRefused).isTrue();
                bindingUnavailable(()->bindingProducer(p.service()).bind(p.reservation(),p.context()));
                assertThat(methodBillingProofs.setup(billing.id(),winner.operation())).contains(winner);
            }finally{release.countDown();}
        }
        assertOriginalBindingReads(start);assertBindingOnlyState(p,before,start+2);assertThat(http.replies).isEmpty();
    }
    @Test void genuineMethodWriterRequiresCommittedBrowserConsumptionAndIndependentBindingBeforeAnyPost(){
        var p=pendingMethod(true);var root=browserRoot(p);releaseBrowser(p,root,selectBrowser(p,root));
        var proof=methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId()).orElseThrow();var before=financialState();int start=http.calls.size();
        var producer=methodProducer(p,operations);methodUnavailable(()->producer.apply(p.reservation(),p.context()));
        assertThat(http.calls).hasSize(start);bindingUnavailable(()->bindingProducer(p.service()).bind(p.reservation(),p.context()));
        assertThat(http.calls).hasSize(start);
        assertThatThrownBy(()->browserPhase(p,pending->{
            assertThat(browserStore().consumeBrowserFirstInTransaction(pending,root,"c".repeat(64),"d".repeat(64),browserNow())).isTrue();
            return jdbc.update("update stripe_subscription_setup_proofs set method_reference='pm_saved' where operation_id=?",proof.operation());
        })).hasStackTraceContaining("Owned method browser consumption required");
        assertThat(count("stripe_billing_method_browser_consumptions")).isZero();methodUnavailable(()->producer.apply(p.reservation(),p.context()));
        assertThat(http.calls).hasSize(start);assertThat(methodBillingProofs.setup(billing.id(),proof.operation())).contains(proof);
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where resource_id=? and kind='SUBSCRIPTION_UPDATE'",Long.class,proof.operation())).isZero();
        assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(financialState()).isEqualTo(before);
    }
    @Test void genuineMethodWriterReturnsOnlyOriginalAckBeforeTheSeparateLifecycleTransaction(){
        var bound=boundBrowserMethod(0);var p=bound.pending();var before=financialState();int start=http.calls.size();http.reply(methodUpdateBody(bound));
        var producer=methodProducer(p,operations);var ack=producer.apply(p.reservation(),p.context());assertOriginalMethodRequest(bound,ack,start);
        assertThat(lifecycleOperations.find(site,current().id(),bound.operation().id())).contains(bound.operation());assertThat(financialState()).isEqualTo(before);
        assertThat(count("stripe_billing_failed_method_completions")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
        methodUnavailable(()->producer.apply(p.reservation(),p.context()));assertThat(http.calls).hasSize(start+1);assertThat(operations.findById(ack.id())).contains(ack);
        var processing=p.service().recordMethodUpdateReply(p.reservation(),p.context());assertThat(processing.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);assertThat(processing.version()).isEqualTo(1);
        assertThat(financialState()).isEqualTo(before);
    }
    @Test void genuineMethodTransportUncertaintyStopsOnceWithoutAnotherPostOrLifecycleChange(){
        var bound=boundBrowserMethod(0);var p=bound.pending();var before=financialState();int start=http.calls.size();http.disconnect();var producer=methodProducer(p,operations);
        methodUnavailable(()->producer.apply(p.reservation(),p.context()));methodUnavailable(()->producer.apply(p.reservation(),p.context()));
        var step=operations.find(billing.id(),bound.operation().providerOperationKey()+":METHOD").orElseThrow();
        assertThat(step.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(step.version()).isEqualTo(2);assertThat(step.leaseOwner()).isNull();assertThat(step.leaseExpiresAt()).isNull();
        assertThat(http.calls).hasSize(start+1);assertThat(http.calls.getLast().params()).containsOnlyKeys("default_payment_method").containsEntry("default_payment_method",bound.proof().method());
        assertThat(http.calls.getLast().options().getIdempotencyKey()).isEqualTo(step.operationKey());assertThat(http.calls.getLast().options().getMaxNetworkRetries()).isZero();
        assertThat(methodBillingProofs.setup(billing.id(),bound.operation().id())).contains(bound.proof());assertThat(operations.findById(p.setupStep().id())).contains(p.setupStep());
        assertThat(lifecycleOperations.find(site,current().id(),bound.operation().id())).contains(bound.operation());assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);assertThat(financialState()).isEqualTo(before);
    }
    @Test void genuineMethodAckCommittedBeforeThrownFailureIsRetainedWithoutRepeat(){
        var bound=boundBrowserMethod(0);var p=bound.pending();var before=financialState();int start=http.calls.size();http.reply(methodUpdateBody(bound));
        var steps=methodSteps(name->{},name->{if(name.equals("acknowledge"))throw new IllegalStateException("after actual METHOD ACK commit");});var producer=methodProducer(p,steps);
        methodUnavailable(()->producer.apply(p.reservation(),p.context()));var ack=operations.find(billing.id(),bound.operation().providerOperationKey()+":METHOD").orElseThrow();
        assertOriginalMethodRequest(bound,ack,start);methodUnavailable(()->producer.apply(p.reservation(),p.context()));assertThat(http.calls).hasSize(start+1);
        assertThat(operations.findById(ack.id())).contains(ack);assertThat(lifecycleOperations.find(site,current().id(),bound.operation().id())).contains(bound.operation());
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);assertThat(financialState()).isEqualTo(before);
    }
    @Test void genuineMethodAckSurvivesActualLifecycleSaveRollbackAndNeverAuthorizesAnotherPost(){
        var bound=boundBrowserMethod(0);var p=bound.pending();var before=financialState();int start=http.calls.size();http.reply(methodUpdateBody(bound));
        var producer=methodProducer(p,operations);var ack=producer.apply(p.reservation(),p.context());assertOriginalMethodRequest(bound,ack,start);
        var saved=new java.util.concurrent.atomic.AtomicBoolean();
        var broken=(SubscriptionOperationStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SubscriptionOperationStore.class},(proxy,method,args)->{
            try{
                var result=method.invoke(lifecycleOperations,args);
                if(method.getName().equals("save")){
                    assertThat(transaction.active()).isTrue();var actual=(SubscriptionOperation)result;assertThat(actual.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
                    assertThat(lifecycleOperations.find(site,current().id(),actual.id())).contains(actual);saved.set(true);
                    throw new IllegalStateException("after actual lifecycle save in owning transaction");
                }
                return result;
            }catch(InvocationTargetException failure){throw failure.getCause();}
        });
        assertThatThrownBy(()->methodService(browserStore(),broken).recordMethodUpdateReply(p.reservation(),p.context())).hasMessage("after actual lifecycle save in owning transaction");
        assertThat(saved).isTrue();assertThat(lifecycleOperations.find(site,current().id(),bound.operation().id())).contains(bound.operation());assertThat(operations.findById(ack.id())).contains(ack);
        methodUnavailable(()->producer.apply(p.reservation(),p.context()));assertThat(http.calls).hasSize(start+1);assertThat(count("stripe_billing_failed_method_completions")).isZero();
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);assertThat(financialState()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"revoked","expired"})
    void genuineFinalMethodAckMayCrossOriginalSessionBoundaryButLaterLifecycleReauthenticates(String mutation){
        var bound=boundBrowserMethod(mutation.equals("expired")?20:0);var p=bound.pending();var before=financialState();int start=http.calls.size();
        var session=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)p.context().actor()).session();
        var atAck=new java.util.concurrent.atomic.AtomicBoolean();http.reply(methodUpdateBody(bound));
        var steps=methodSteps(name->{if(name.equals("acknowledge")){
            // All adapter inspections succeeded. Prove the unchanged exact original session is live
            // immediately before only the final independent ACK transaction crosses this boundary.
            assertThat(transaction.requiresNew(()->sessions.revalidate(session,site,session.origin(),dev.genericpayments.platform.customers.domain.CustomerSessionScope.SUBSCRIPTION_MANAGE))).isEqualTo(session);
            var dbNow=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();assertThat(dbNow).isBefore(session.expiresAt());
            if(mutation.equals("revoked"))jdbc.update("update customer_sessions set revoked_at=clock_timestamp(),revoked_reason='LOGOUT' where id=?",session.sessionId().value());
            else{
                var wait=Duration.between(dbNow,session.expiresAt()).plusMillis(100);assertThat(wait).isPositive().isLessThan(Duration.ofSeconds(21));
                try{Thread.sleep(wait);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                var afterExpiry=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();assertThat(afterExpiry).isAfterOrEqualTo(session.expiresAt());
                assertThat(jdbc.queryForObject("select expires_at from customer_sessions where id=?",java.sql.Timestamp.class,session.sessionId().value()).toInstant()).isEqualTo(session.expiresAt());
                // CustomerSessionService uses the injected authentication clock, independently
                // of the real DBUTC used above. Advance it to that same proven expired instant.
                TestClock.set(afterExpiry);
            }
            atAck.set(true);
        }},name->{});
        var producer=methodProducer(p,steps);var ack=producer.apply(p.reservation(),p.context());assertThat(atAck).isTrue();assertOriginalMethodRequest(bound,ack,start);
        assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(lifecycleOperations.find(site,current().id(),bound.operation().id())).contains(bound.operation());assertThat(financialState()).isEqualTo(before);
        assertThatThrownBy(()->p.service().recordMethodUpdateReply(p.reservation(),p.context()))
            .isExactlyInstanceOf(PaymentApplicationException.class).hasMessage("Authentication failed").hasNoCause()
            .satisfies(failure->{var e=(PaymentApplicationException)failure;assertThat(e.code()).isEqualTo("AUTHENTICATION_FAILED");assertThat(e.status()).isEqualTo(401);});
        assertThat(lifecycleOperations.find(site,current().id(),bound.operation().id())).contains(bound.operation());assertThat(operations.findById(ack.id())).contains(ack);
        methodUnavailable(()->producer.apply(p.reservation(),p.context()));assertThat(http.calls).hasSize(start+1);assertThat(financialState()).isEqualTo(before);assertThat(count("stripe_billing_failed_method_completions")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"bill","enrollment"})
    void lifecycleWaitAcrossOriginalExpiryCannotSaveProcessingWithCommittedAck(String lockedRow) throws Exception {
        var bound=boundBrowserMethod(20);var p=bound.pending();int start=http.calls.size();var before=financialState();
        var session=((SubscriptionActor.Customer)p.context().actor()).session();
        http.reply(methodUpdateBody(bound));var ack=methodProducer(p,operations).apply(p.reservation(),p.context());assertOriginalMethodRequest(bound,ack,start);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var holder=new java.util.concurrent.atomic.AtomicInteger();
        try(var workers=new BoundedWorkers()){
            var blocker=workers.submit(()->transaction.requiresNew(()->{
                if(lockedRow.equals("bill"))bills.lock(site,p.failure().billId());
                else assertThat(jdbc.queryForList("select id from stripe_billing_method_browser_roots where reservation_id=? for update",
                    UUID.class,p.reservation().id())).hasSize(1);
                holder.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));locked.countDown();
                try{if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("lifecycle wait timed out");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                return true;
            }));
            try{
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                var attempt=workers.submit(()->{
                    assertThatThrownBy(()->p.service().recordMethodUpdateReply(p.reservation(),p.context()))
                        .isExactlyInstanceOf(IllegalStateException.class).hasMessage("Owned method browser authority unavailable");
                    return true;
                });
                String query=lockedRow.equals("bill")?"%from subscription_billing_records%for update%":"%lock_owned_method_browser%";
                boolean waiting=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline){
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from pg_stat_activity where datname=current_database() and state='active'
                        and query like ? and ?=any(pg_blocking_pids(pid)))
                        """,Boolean.class,query,holder.get()));
                    if(waiting)break;
                    Thread.sleep(20);
                }
                assertThat(waiting).as("lifecycle worker waiting on the held %s row",lockedRow).isTrue();
                var dbNow=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();
                assertThat(dbNow).isBefore(session.expiresAt());Thread.sleep(Duration.between(dbNow,session.expiresAt()).plusMillis(100));
                var expiredAt=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();
                assertThat(expiredAt).isAfterOrEqualTo(session.expiresAt());TestClock.set(expiredAt);
                release.countDown();assertThat(blocker.get(10,TimeUnit.SECONDS)).isTrue();assertThat(attempt.get(10,TimeUnit.SECONDS)).isTrue();
            }finally{release.countDown();}
        }
        assertLifecycleRefusalRetainsAck(bound,ack,start,before);
    }
    @Test void secondLiveSessionWithOriginalKeyCannotSaveProcessingAfterOriginalRevocation(){
        var bound=boundBrowserMethod(0);var p=bound.pending();int start=http.calls.size();var before=financialState();
        http.reply(methodUpdateBody(bound));var ack=methodProducer(p,operations).apply(p.reservation(),p.context());assertOriginalMethodRequest(bound,ack,start);
        var original=((SubscriptionActor.Customer)p.context().actor()).session();
        TestClock.set(jdbc.queryForObject("select issued_at from customer_sessions where id=?",java.sql.Timestamp.class,original.sessionId().value()).toInstant());
        var issued=sessions.issue(site,customer,original.origin());var second=sessions.resolveRequired(issued.rawToken(),original.origin());
        TestClock.set(Instant.now());assertThat(second.sessionId()).isNotEqualTo(original.sessionId());assertThat(second.expiresAt()).isEqualTo(original.expiresAt());
        assertThat(second.scopes()).isEqualTo(original.scopes());assertThat(second.origin()).isEqualTo(original.origin());
        assertThat(jdbc.update("update customer_sessions set revoked_at=clock_timestamp(),revoked_reason='LOGOUT' where id=?",original.sessionId().value())).isEqualTo(1);
        assertThat(transaction.requiresNew(()->sessions.revalidate(second,site,second.origin(),CustomerSessionScope.SUBSCRIPTION_MANAGE))).isEqualTo(second);
        var context=new SubscriptionCommandContext(new SubscriptionActor.Customer(second),site,customer,second.origin(),p.context().idempotencyKey(),"second");
        assertThatThrownBy(()->p.service().recordMethodUpdateReply(p.reservation(),context))
            .isExactlyInstanceOf(IllegalStateException.class).hasMessage("Owned method browser authority unavailable");
        assertLifecycleRefusalRetainsAck(bound,ack,start,before);
    }
    private void assertLifecycleRefusalRetainsAck(BoundMethod bound,ProviderOperation ack,int start,Map<String,Object> before) {
        var p=bound.pending();assertThat(operations.findById(ack.id())).contains(ack);
        assertThat(ack.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(ack.version()).isEqualTo(2);
        assertThat(lifecycleOperations.find(site,current().id(),bound.operation().id())).contains(bound.operation());
        assertThat(bound.operation().status()).isEqualTo(SubscriptionOperation.Status.RESERVED);assertThat(bound.operation().version()).isZero();
        methodUnavailable(()->methodProducer(p,operations).apply(p.reservation(),p.context()));assertThat(http.calls).hasSize(start+1);
        assertThat(count("stripe_billing_failed_method_completions")).isZero();assertThat(financialState()).isEqualTo(before);
    }
    /** Reuses the genuine original first dispatch and SDK ACK producer, stopping before any method binding. */
    private PendingMethod pendingMethod(boolean browser) {
        return pendingMethod(browser,0);
    }
    private PendingMethod pendingMethod(boolean browser,int sessionSeconds) {
        var reservedMethod=reservedMethod(browser,sessionSeconds);
        var s=reservedMethod.setup();var failure=reservedMethod.failure();var r=reservedMethod.reservation();var svc=reservedMethod.service();var context=reservedMethod.context();
        var before=financialState();var subscription=current();var o=lifecycleOperations.find(site,subscription.id(),r.setupOperationId()).orElseThrow();
        if(browser){
            int callStart=http.calls.size();var eventsBefore=rows("subscription_events");long outboxBefore=count("outbox_events");
            var proof=setupProducer(reservedMethod,operations,originalSetupProofs(reservedMethod,false,name->{})).prepare(r,context);
            var setupStep=operations.findById(proof.step()).orElseThrow();
            assertOriginalSetupRequest(reservedMethod,setupStep,callStart);
            assertThat(proof.operation()).isEqualTo(o.id());assertThat(proof.subscription()).isEqualTo(subscription.id());assertThat(proof.account()).isEqualTo(billing.id());assertThat(proof.method()).isNull();
            assertThat(setupStep.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(setupStep.version()).isEqualTo(2);
            assertThat(lifecycleOperations.find(site,subscription.id(),o.id())).contains(o);assertThat(o.status()).isEqualTo(SubscriptionOperation.Status.RESERVED);assertThat(o.version()).isZero();
            assertThat(jdbc.queryForObject("select count(*) from provider_operations where resource_id=? and kind='SUBSCRIPTION_UPDATE'",Long.class,o.id())).isZero();
            assertThat(count("stripe_billing_method_browser_roots")).isZero();assertThat(count("stripe_billing_failed_method_completions")).isZero();
            assertThat(rows("subscription_events")).isEqualTo(eventsBefore);assertThat(count("outbox_events")).isEqualTo(outboxBefore);assertThat(financialState()).isEqualTo(before);
            return new PendingMethod(s,failure,r,svc,context,setupStep,callStart,reservedMethod.customerToken(),true);
        }
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
        return new PendingMethod(s,failure,r,svc,context,setupStep,callStart,reservedMethod.customerToken(),false);
    }
    /** Genuine observer and original management reservation; stops before any provider SETUP step or proof. */
    private ReservedMethod reservedMethod(boolean browser,int sessionSeconds) {
        var s=setup();failedGraph(s);
        int samplesBeforeFailure=time.persistedSamples.size();
        var failure=new StripeBillingOwnedFailedRenewalObserver(service(store(),null),credentials,clients,Clock.systemUTC()).observe(s.admission());
        if(time.persisted!=null) {
            assertThat(time.persistedSamples.size()).isGreaterThan(samplesBeforeFailure);
            assertThat(current().debt().billingRecordId()).isEqualTo(failure.billId());
            assertPersistedSampleUnchanged();
        }
        var before=financialState();var methods=new JdbcOwnedFailedRenewalMethodStore(jdbc);var svc=methodService(methods);
        var authentication=managementAuthentication("genuine-owned-method");var context=authentication.context();
        if(sessionSeconds>0){
            var original=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)context.actor()).session();
            var expiry=Instant.now().plusSeconds(sessionSeconds).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            jdbc.update("update customer_sessions set expires_at=? where id=?",java.sql.Timestamp.from(expiry),original.sessionId().value());
            var changed=new dev.genericpayments.platform.customers.application.model.CustomerSessionContext(original.sessionId(),original.siteId(),original.customerId(),original.origin(),original.scopes(),expiry);
            context=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer(changed),site,customer,changed.origin(),context.idempotencyKey(),context.requestId());
        }
        var r=browser?svc.reserveBrowserMethod(s.admission(),context,true):svc.reserveMethod(s.admission(),context,true);
        if(time.persisted!=null) {
            assertThat(r.billingSample()).isEqualTo(persistedExpected());
            assertPersistedSampleUnchanged(); // Includes the genuine pending SETUP reservation.
        }
        var subscription=current();var o=lifecycleOperations.find(site,subscription.id(),r.setupOperationId()).orElseThrow();
        assertThat(o.status()).isEqualTo(SubscriptionOperation.Status.RESERVED);assertThat(o.version()).isZero();
        assertThat(financialState()).isEqualTo(before);
        assertThat(methodBillingProofs.setup(billing.id(),o.id())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where resource_id=?",Long.class,o.id())).isZero();
        return new ReservedMethod(s,failure,r,svc,context,authentication.rawToken());
    }
    private StripeBillingOwnedFailedRenewalSetupAdapter setupProducer(ReservedMethod p,dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore steps,SubscriptionBillingProofStore proofs){
        return new StripeBillingOwnedFailedRenewalSetupAdapter(p.service(),steps,proofs,credentials,clients,digest,Clock.systemUTC());
    }
    /** Queue the synthetic response on the caller after the real original claim exists. No SDK worker store IO. */
    private SubscriptionBillingProofStore originalSetupProofs(ReservedMethod p,boolean disconnect,java.util.function.Consumer<String> before){
        return (SubscriptionBillingProofStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SubscriptionBillingProofStore.class},(proxy,method,args)->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            before.accept(method.getName());
            try{
                var result=method.invoke(methodBillingProofs,args);
                if(method.getName().equals("authorizeSetup")){
                    if(disconnect)http.disconnect();
                    else{
                        var o=lifecycleOperations.find(site,current().id(),p.reservation().setupOperationId()).orElseThrow();
                        var claim=operations.find(billing.id(),o.providerOperationKey()+":SETUP").orElseThrow();
                        var body=dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.json(setupBody(o,claim,"requires_payment_method"));
                        body.getAsJsonObject("metadata").addProperty("account_id",billing.id().value().toString());
                        body.getAsJsonObject("metadata").addProperty("site_id",site.value().toString());body.getAsJsonObject("metadata").addProperty("customer_id",customer.value().toString());
                        // The producer ignores this field; only the later browser reader may consume it.
                        body.addProperty("client_secret","provider-private-unusable-secret");http.reply(body.toString());
                    }
                }
                return result;
            }catch(InvocationTargetException failure){throw failure.getCause();}
        });
    }
    private void assertOriginalSetupRequest(ReservedMethod p,ProviderOperation step,int start){
        assertThat(http.calls).hasSize(start+1);var request=http.calls.get(start);
        assertThat(request.method().name()).isEqualTo("POST");assertThat(request.url().getPath()).isEqualTo("/v1/setup_intents");
        assertThat(request.params()).containsOnlyKeys("customer","usage","allowed_payment_method_types","metadata")
            .containsEntry("customer",p.reservation().binding().providerReference()).containsEntry("usage","off_session").containsEntry("allowed_payment_method_types",List.of("card"));
        assertThat(request.params().get("metadata")).isEqualTo(Map.of("account_id",billing.id().value().toString(),"site_id",site.value().toString(),"customer_id",customer.value().toString(),"subscription_id",current().id().toString(),"operation_id",p.reservation().setupOperationId().toString()));
        assertThat(request.options().getIdempotencyKey()).isEqualTo(step.operationKey());assertThat(request.options().getMaxNetworkRetries()).isZero();
        assertThat(request.options().getConnectTimeout()).isBetween(1,2000);assertThat(request.options().getReadTimeout()).isBetween(1,2000);
        assertThat(request.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");assertThat(request.options().getStripeAccount()).isNull();assertThat(http.replies).isEmpty();
    }
    private static void setupUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable work){
        assertThatThrownBy(work).isExactlyInstanceOf(PaymentApplicationException.class).hasMessage("Owned method setup unavailable").hasNoCause();
    }
    @Test void genuineOriginalSetupTransportUncertaintyStopsOnceAndCannotRepeat(){
        var p=reservedMethod(true,0);var before=financialState();int start=http.calls.size();
        var producer=setupProducer(p,operations,originalSetupProofs(p,true,name->{}));
        setupUnavailable(()->producer.prepare(p.reservation(),p.context()));setupUnavailable(()->producer.prepare(p.reservation(),p.context()));
        assertThat(http.calls).hasSize(start+1);assertThat(http.calls.getLast().url().getPath()).isEqualTo("/v1/setup_intents");
        var step=operations.find(billing.id(),lifecycleOperations.find(site,current().id(),p.reservation().setupOperationId()).orElseThrow().providerOperationKey()+":SETUP").orElseThrow();
        assertThat(step.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId())).isEmpty();
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);assertThat(count("stripe_billing_method_browser_roots")).isZero();assertThat(financialState()).isEqualTo(before);
    }
    @Test void genuineOriginalSetupAckSurvivesProofFailureAndCannotRepeat(){
        var p=reservedMethod(true,0);var before=financialState();int start=http.calls.size();
        var producer=setupProducer(p,operations,originalSetupProofs(p,false,name->{if(name.equals("recordSetup"))transaction.requiresNew(()->{
            var o=lifecycleOperations.find(site,current().id(),p.reservation().setupOperationId()).orElseThrow();
            var step=operations.find(billing.id(),o.providerOperationKey()+":SETUP").orElseThrow();
            // Invoke the genuine SQL adapter inside this owned short transaction, then roll it back.
            new dev.genericpayments.platform.subscriptions.adapter.out.persistence.SubscriptionBillingProofPersistenceAdapter(jdbc).recordSetup(current(),o,step.id(),step.providerReference());
            throw new IllegalStateException("synthetic proof transaction failure");
        });}));
        setupUnavailable(()->producer.prepare(p.reservation(),p.context()));setupUnavailable(()->producer.prepare(p.reservation(),p.context()));
        var step=operations.find(billing.id(),lifecycleOperations.find(site,current().id(),p.reservation().setupOperationId()).orElseThrow().providerOperationKey()+":SETUP").orElseThrow();
        assertOriginalSetupRequest(p,step,start);assertThat(step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(step.version()).isEqualTo(2);
        assertThat(methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId())).isEmpty();assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);assertThat(financialState()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"revoked","expired"})
    void genuineUnboundSetupProofMayCommitAfterFinalInspectionButCannotIssueBrowserAuthority(String mutation){
        var p=reservedMethod(true,0);var before=financialState();int start=http.calls.size();
        var original=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)p.context().actor()).session();
        var producer=setupProducer(p,operations,originalSetupProofs(p,false,name->{if(name.equals("recordSetup")){
            if(mutation.equals("revoked"))jdbc.update("update customer_sessions set revoked_at=clock_timestamp(),revoked_reason='LOGOUT' where id=?",original.sessionId().value());
            else jdbc.update("update customer_sessions set expires_at=clock_timestamp() where id=?",original.sessionId().value());
        }}));
        var proof=producer.prepare(p.reservation(),p.context());var step=operations.findById(proof.step()).orElseThrow();assertOriginalSetupRequest(p,step,start);
        assertThat(methodBillingProofs.setup(billing.id(),proof.operation())).contains(proof);assertThat(proof.method()).isNull();assertThat(step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(financialState()).isEqualTo(before);
        var pending=new PendingMethod(p.setup(),p.failure(),p.reservation(),p.service(),p.context(),step,start,p.customerToken(),true);
        var query=new GetSubscriptionActionUseCase.Query(browserCommand(pending,null),current().id(),proof.operation());
        assertThatThrownBy(()->browserService(pending,browserStore()).get(query)).hasMessage("Owned method browser unavailable").hasNoCause();
        assertThat(http.calls).hasSize(start+1);assertThat(count("stripe_billing_method_browser_roots")).isZero();assertThat(financialState()).isEqualTo(before);
    }
    @Test void genuineRevocationBeforeFinalSetupInspectionPreservesAckAndRefusesProof(){
        var p=reservedMethod(true,0);var before=financialState();var original=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)p.context().actor()).session();
        var steps=(dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore.class},(proxy,method,args)->{
            try{var result=method.invoke(operations,args);if(method.getName().equals("acknowledge"))jdbc.update("update customer_sessions set revoked_at=clock_timestamp(),revoked_reason='LOGOUT' where id=?",original.sessionId().value());return result;}
            catch(InvocationTargetException failure){throw failure.getCause();}
        });
        int start=http.calls.size();setupUnavailable(()->setupProducer(p,steps,originalSetupProofs(p,false,name->{})).prepare(p.reservation(),p.context()));
        var o=lifecycleOperations.find(site,current().id(),p.reservation().setupOperationId()).orElseThrow();var step=operations.find(billing.id(),o.providerOperationKey()+":SETUP").orElseThrow();
        assertOriginalSetupRequest(p,step,start);assertThat(step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(methodBillingProofs.setup(billing.id(),o.id())).isEmpty();
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);assertThat(count("stripe_billing_method_browser_roots")).isZero();assertThat(financialState()).isEqualTo(before);
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

    private OwnedMethodBrowserService browserService(PendingMethod p,OwnedFailedRenewalMethodStore methods){
        return new OwnedMethodBrowserService(p.reservation(),p.service(),methods,new StripeOwnedMethodBrowserReader(credentials,clients,Clock.systemUTC()),
            new StripeOwnedMethodBrowserTokens(new StripeBillingGrantTokens(true,"v1",Map.of("v1",Base64.getEncoder().encodeToString(new byte[32])))),Clock.systemUTC());
    }
    private dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext browserCommand(PendingMethod p,String key){
        var c=p.context();return new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(c.actor(),c.siteId(),c.customerId(),c.origin(),key,"browser-issuer");
    }
    private void queuePendingBrowser(PendingMethod p){
        var o=lifecycleOperations.find(site,current().id(),p.reservation().setupOperationId()).orElseThrow();
        var body=dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.json(setupBody(o,p.setupStep(),"requires_payment_method"));
        body.addProperty("client_secret",p.setupStep().providerReference()+"_secret_INERT");http.reply(body.toString());
    }
    private org.springframework.test.web.servlet.MockMvc browserMvc(OwnedMethodBrowserService service){
        var u=ordinaryHttp;
        var explicit=new dev.genericpayments.platform.subscriptions.adapter.in.web.SubscriptionHttpUseCases(u.query(),u.create(),u.preview(),u.change(),u.clear(),u.cancel(),u.withdraw(),u.setup(),u.recover(),u.resume(),service,service,u.portal(),u.reconcile());
        var mapper=new tools.jackson.databind.ObjectMapper();
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new dev.genericpayments.platform.subscriptions.adapter.in.web.CustomerSubscriptionController(explicit),
            new dev.genericpayments.platform.subscriptions.adapter.in.web.SubscriptionConfirmationController(service),
            new dev.genericpayments.platform.subscriptions.adapter.in.web.SubscriptionClientSessionController(service))
            .setControllerAdvice(new dev.genericpayments.platform.shared.adapter.in.web.ApiExceptionHandler())
            .addFilters(new dev.genericpayments.platform.subscriptions.adapter.in.web.SubscriptionRequestPolicyFilter(mapper,true),
                new dev.genericpayments.platform.customers.adapter.in.web.CustomerSessionAuthenticationFilter(sessions,sites,mapper),
                new dev.genericpayments.platform.subscriptions.adapter.in.web.SubscriptionRequestPolicyFilter(mapper,false)).build();
    }
    private String browserPath(PendingMethod p){return "/api/v1/customer/subscriptions/"+current().id()+"/operations/"+p.reservation().setupOperationId();}
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder browserGet(PendingMethod p,String suffix){
        return get(browserPath(p)+suffix).header("Origin",p.context().origin()).header("Authorization","Bearer "+p.customerToken());
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder browserPost(PendingMethod p,String suffix,String key,String grant){
        return post(browserPath(p)+suffix).header("Origin",p.context().origin()).header("Authorization","Bearer "+p.customerToken()).header("Idempotency-Key",key).contentType("application/json").content("{\"action_token\":\""+grant+"\"}");
    }
    /** All four real HTTP boundaries, actual original session resolution and exact no-store wire.
     * Hosted only: standalone MVC creates a context and must never enter the offline pure selector. */
    private void controllerBrowserPermission(PendingMethod p,boolean legacy) throws Exception {
        var service=browserService(p,browserStore());var operation=lifecycleOperations.find(site,current().id(),p.reservation().setupOperationId()).orElseThrow();
        var original=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)p.context().actor()).session();
        assertThat(sessions.resolveRequired(p.customerToken(),original.origin())).isEqualTo(original);
        var before=financialState();int calls=http.calls.size();var mvc=browserMvc(service);var mapper=new tools.jackson.databind.ObjectMapper();
        try{
            var actionResult=mvc.perform(browserGet(p,"/action")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.kind").value("PROVIDER_CLIENT")).andReturn();
            var action=mapper.readTree(actionResult.getResponse().getContentAsString());String grant=action.get("secret").asString();
            // Another valid session for this same customer cannot inherit the original browser.
            TestClock.set(Instant.now());var replacement=sessions.issue(site,customer,original.origin());
            mvc.perform(browserGet(p,"/action").with(r->{r.removeHeader("Authorization");r.addHeader("Authorization","Bearer "+replacement.rawToken());return r;}))
                .andExpect(status().isConflict()).andExpect(header().string("Cache-Control","no-store"));
            for(String locator:List.of("/api/v1/customer/subscriptions/"+UUID.randomUUID()+"/operations/"+operation.id(),
                    "/api/v1/customer/subscriptions/"+operation.subscriptionId()+"/operations/"+UUID.randomUUID())){
                mvc.perform(get(locator+"/action").header("Origin",original.origin()).header("Authorization","Bearer "+p.customerToken()))
                    .andExpect(status().isConflict()).andExpect(header().string("Cache-Control","no-store"));
            }
            mvc.perform(browserGet(p,"/action").with(r->{r.removeHeader("Origin");return r;})).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
            mvc.perform(browserGet(p,"/action").with(r->{r.removeHeader("Origin");r.addHeader("Origin","https://foreign.test");return r;})).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
            assertThat(http.calls).hasSize(calls);queuePendingBrowser(p);
            String selectedPath=legacy?"/client-session":"/confirmation-session";
            var selected=mvc.perform(browserPost(p,selectedPath,"select",grant)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.provider").value("stripe")).andExpect(jsonPath("$.flow").value("SETUP"))
                .andExpect(jsonPath("$.client_secret").value(p.setupStep().providerReference()+"_secret_INERT")).andExpect(jsonPath("$.action_token").value(grant))
                .andExpect(jsonPath("$.attempt_id").doesNotExist()).andExpect(jsonPath("$.capability").doesNotExist()).andReturn();
            var selection=mapper.readTree(selected.getResponse().getContentAsString());assertThat(selection.get("expires_at").asString()).isEqualTo(action.get("expires_at").asString());
            if(!legacy)assertThat(selection.get("protocol").asString()).isEqualTo("BROWSER_CONFIRM_V1");
            mvc.perform(browserPost(p,legacy?"/confirmation-session":"/client-session","select-again",grant)).andExpect(status().isConflict()).andExpect(header().string("Cache-Control","no-store"));
            var confirmation=browserPost(p,"/action-confirmations","confirm",grant);
            mvc.perform(confirmation).andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.operation_id").value(operation.id().toString())).andExpect(jsonPath("$.subscription_id").value(operation.subscriptionId().toString()))
                .andExpect(jsonPath("$.status").value("RESERVED")).andExpect(jsonPath("$.action_available").value(false)).andExpect(jsonPath("$.next_action").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.browser_confirm_allowed").value(true)).andExpect(jsonPath("$.client_secret").doesNotExist());
            mvc.perform(browserPost(p,"/action-confirmations","confirm",grant)).andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.browser_confirm_allowed").value(false));
            mvc.perform(browserPost(p,"/action-confirmations","changed",grant)).andExpect(status().isConflict()).andExpect(header().string("Cache-Control","no-store"));
        }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
        assertThat(http.calls).hasSize(calls+1);assertThat(http.calls.getLast().url().getPath()).isEqualTo("/v1/setup_intents/"+p.setupStep().providerReference());
        assertThat(lifecycleOperations.find(site,operation.subscriptionId(),operation.id())).contains(operation);assertThat(operation.version()).isZero();assertThat(financialState()).isEqualTo(before);
        assertThat(methodBillingProofs.setup(billing.id(),operation.id()).orElseThrow().method()).isNull();
        assertThat(count("stripe_billing_method_browser_roots")).isEqualTo(1);assertThat(count("stripe_billing_method_browser_selections")).isEqualTo(1);assertThat(count("stripe_billing_method_browser_releases")).isEqualTo(1);assertThat(count("stripe_billing_method_browser_consumptions")).isEqualTo(1);
        assertThat(count("stripe_billing_failed_method_completions")).isZero();assertThat(count("payments")).isEqualTo(1);
    }
    @Test void ordinaryCustomerControllerAndQueriesStillRefuseTheEnrolledClockOwnerWithoutSdkCalls(){
        var p=pendingMethod(true);var original=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)p.context().actor()).session();
        var before=financialState();int calls=http.calls.size();var controller=new dev.genericpayments.platform.subscriptions.adapter.in.web.CustomerSubscriptionController(ordinaryHttp);
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.addHeader("Idempotency-Key","ordinary-denied");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(original,null,List.of()));
        try{
            assertThatThrownBy(()->controller.list(request,20,null,null,null)).isExactlyInstanceOf(IllegalStateException.class).hasMessage("OWNED_TIME_UNSCOPED");
            assertThatThrownBy(()->controller.action(request,current().id(),p.reservation().setupOperationId())).isExactlyInstanceOf(PaymentApplicationException.class)
                .satisfies(failure->assertThat(((PaymentApplicationException)failure).code()).isEqualTo("PROVIDER_CAPABILITY_UNAVAILABLE"));
            assertThatThrownBy(()->controller.confirm(request,current().id(),p.reservation().setupOperationId(),new dev.genericpayments.platform.subscriptions.adapter.in.web.SubscriptionTransport.SubscriptionActionRequest("bga1_"+"D".repeat(43)))).isExactlyInstanceOf(PaymentApplicationException.class)
                .satisfies(failure->assertThat(((PaymentApplicationException)failure).code()).isEqualTo("PROVIDER_CAPABILITY_UNAVAILABLE"));
        }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
        assertThat(http.calls).hasSize(calls);assertThat(financialState()).isEqualTo(before);assertThat(count("stripe_billing_method_browser_roots")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void browserPermissionThenIndependentMethodProofAndActualObserverCompleteOnlyTheMethod(boolean legacy) throws Exception {
        var pending=pendingMethod(true);var before=financialState();var debt=current().debt();var subscription=current();long sequence=observations.captureFence(subscription);
        controllerBrowserPermission(pending,legacy);
        // The browser receipt is permission only. The unchanged genuine continuation separately
        // reads succeeded SETUP + card, binds, dispatches METHOD and records its real ACK.
        var prepared=preparedMethod(pending);
        assertThat(financialState()).isEqualTo(before);assertThat(current().debt()).isEqualTo(debt);
        assertThat(lifecycleOperations.find(site,current().id(),pending.reservation().setupOperationId()).orElseThrow().status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        int start=http.calls.size();methodGraph(prepared);var completed=methodObserver(prepared.service()).observeMethod(prepared.reservation());assertObservationSlice(prepared,start,17);
        assertThat(completed.reservationId()).isEqualTo(pending.reservation().id());assertThat(current()).isEqualTo(subscription);assertThat(current().debt()).isEqualTo(debt);
        assertThat(observations.captureFence(current())).isEqualTo(sequence+1);var after=new TreeMap<>(financialState());after.put("sequence",before.get("sequence"));assertThat(after).isEqualTo(before);
        assertThat(lifecycleOperations.find(site,current().id(),pending.reservation().setupOperationId()).orElseThrow().status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(count("stripe_billing_failed_method_completions")).isEqualTo(1);assertThat(count("stripe_billing_failed_method_publications")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(count("payments")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void explicitBrowserIssuerCommitsOneReleaseAndOneActualReservedPermission(boolean legacy){
        var p=pendingMethod(true);var service=browserService(p,browserStore());var before=financialState();
        var operation=lifecycleOperations.find(site,current().id(),p.reservation().setupOperationId()).orElseThrow();int calls=http.calls.size();
        var action=service.get(new GetSubscriptionActionUseCase.Query(browserCommand(p,null),operation.subscriptionId(),operation.id()));
        assertThat(service.get(new GetSubscriptionActionUseCase.Query(browserCommand(p,null),operation.subscriptionId(),operation.id()))).isEqualTo(action);
        assertThat(http.calls).hasSize(calls);queuePendingBrowser(p);
        if(legacy){var session=service.issue(new CreateSubscriptionClientSessionUseCase.Command(browserCommand(p,"issue"),operation.subscriptionId(),operation.id(),action.secret()));
            assertThat(session.flow()).isEqualTo(dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientSession.Flow.SETUP);assertThat(session.actionToken()).isEqualTo(action.secret());}
        else{var session=service.select(new SubscriptionConfirmationUseCase.Scope(browserCommand(p,"select"),operation.subscriptionId(),operation.id()),action.secret());
            assertThat(session.protocol()).isEqualTo("BROWSER_CONFIRM_V1");assertThat(session.flow()).isEqualTo(dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientSession.Flow.SETUP);assertThat(session.attemptId()).isNull();assertThat(session.capability()).isNull();}
        assertThat(http.calls).hasSize(calls+1);assertThat(http.calls.getLast().method().name()).isEqualTo("GET");assertThat(http.calls.getLast().url().getPath()).isEqualTo("/v1/setup_intents/"+p.setupStep().providerReference());
        var confirmation=new ConfirmSubscriptionActionUseCase.Command(browserCommand(p,"confirm"),operation.subscriptionId(),operation.id(),action.secret());
        var first=service.confirm(confirmation);assertThat(first.status()).isEqualTo(SubscriptionOperation.Status.RESERVED);assertThat(first.operationId()).isEqualTo(operation.id());assertThat(first.nextAction()).isNull();assertThat(first.browserConfirmAllowed()).isTrue();
        assertThat(service.confirm(confirmation).browserConfirmAllowed()).isFalse();
        assertThatThrownBy(()->service.confirm(new ConfirmSubscriptionActionUseCase.Command(browserCommand(p,"changed"),operation.subscriptionId(),operation.id(),action.secret()))).hasMessage("Owned method browser unavailable").hasNoCause();
        assertThat(count("stripe_billing_method_browser_roots")).isEqualTo(1);assertThat(count("stripe_billing_method_browser_selections")).isEqualTo(1);assertThat(count("stripe_billing_method_browser_releases")).isEqualTo(1);assertThat(count("stripe_billing_method_browser_consumptions")).isEqualTo(1);
        assertThat(lifecycleOperations.find(site,operation.subscriptionId(),operation.id())).contains(operation);assertThat(financialState()).isEqualTo(before);
        assertThat(methodBillingProofs.setup(billing.id(),operation.id()).orElseThrow().method()).isNull();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(http.replies).isEmpty();
        assertThat(rows("stripe_billing_method_browser_roots").toString()).doesNotContain(action.secret(),"_secret_INERT","pk_test_");
    }
    private record RecoveryPrepared(PendingMethod pending,PreparedMethod prepared,OwnedFailedRenewalMethod.Completion completion,
            OwnedFailedRenewalMethod.Recovery recovery) {
        @Override public String toString(){return "RecoveryPrepared[redacted]";}
    }
    /** Genuine SETUP/unbound proof/ACK, browser HTTP permission, binding, METHOD, lifecycle and observer. */
    private RecoveryPrepared recoveryPrepared(boolean legacy,int sessionSeconds) throws Exception {
        var pending=pendingMethod(true,sessionSeconds);controllerBrowserPermission(pending,legacy);
        var prepared=preparedMethod(pending);int start=http.calls.size();methodGraph(prepared);
        var completion=methodObserver(prepared.service()).observeMethod(prepared.reservation());assertObservationSlice(prepared,start,17);
        var recovery=prepared.service().reserveRecovery(prepared.reservation(),completion);
        return new RecoveryPrepared(pending,prepared,completion,recovery);
    }
    private OwnedFailedRenewalMethod.RecoveryPreparationContext inspectRecovery(RecoveryPrepared p){
        return p.pending().service().inspectBrowserRecoveryPreparation(p.pending().reservation(),p.completion(),p.recovery(),p.pending().context());
    }
    private OwnedRecoveryConfirmService recoveryConfirmer(RecoveryPrepared p){
        return recoveryConfirmer(p,new JdbcOwnedRecoveryConfirmStore(jdbc));
    }
    private OwnedRecoveryConfirmService recoveryConfirmer(RecoveryPrepared p,OwnedRecoveryConfirmStore receipts){
        return new OwnedRecoveryConfirmService(p.pending().service(),receipts,transaction,Clock.systemUTC());
    }
    private InvocationBudget confirmationBudget(){return new InvocationBudget(Clock.systemUTC(),System::nanoTime);}
    private ConfirmReply confirmationReply(OwnedFailedRenewalMethod.RecoveryPreparationContext c){
        return new ConfirmReply(c.reservation().failure().paymentIntent(),c.binding().providerReference(),c.completion().method(),"EUR",1000,1000,0,"ch_confirmed",browserNow());
    }
    private Map<String,List<String>> confirmationRows(){
        var result=new TreeMap<>(recoveryPreparationRows());
        result.put("stripe_billing_failed_recovery_confirm_claims",rows("stripe_billing_failed_recovery_confirm_claims"));
        result.put("stripe_billing_failed_recovery_confirm_acks",rows("stripe_billing_failed_recovery_confirm_acks"));return result;
    }
    private OwnedRecoveryConfirmStore observingConfirmationStore(java.util.function.BiConsumer<java.lang.reflect.Method,Object[]> before,
            java.util.function.BiConsumer<java.lang.reflect.Method,Object> after){
        var actual=new JdbcOwnedRecoveryConfirmStore(jdbc);
        return (OwnedRecoveryConfirmStore)Proxy.newProxyInstance(OwnedRecoveryConfirmStore.class.getClassLoader(),new Class<?>[]{OwnedRecoveryConfirmStore.class},(proxy,m,args)->{
            before.accept(m,args);try{var result=m.invoke(actual,args);after.accept(m,result);return result;}
            catch(InvocationTargetException error){throw error.getCause();}
        });
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void dedicatedConfirmationCommitsOneOriginalClaimAndAckThenIndependentObserverSettlesOnlyOriginalDebt(boolean legacy) throws Exception {
        var p=recoveryPrepared(legacy,0);var c=inspectRecovery(p);var confirmer=recoveryConfirmer(p);var budget=confirmationBudget();
        var before=financialState();var original=jdbc.queryForMap("select * from payments where id=?",p.prepared().setup().upstream().payment());
        var setupBefore=jdbc.queryForMap("select * from subscription_operations where id=?",c.setupOperation().id());
        var recoverBefore=jdbc.queryForMap("select * from subscription_operations where id=?",c.recoveryOperation().id());int calls=http.calls.size();
        var claim=confirmer.claim(c,p.pending().context(),UUID.randomUUID().toString(),budget);
        assertThat(claim.kind()).isEqualTo(ProviderOperation.Kind.OWNED_RECOVERY_INTENT_CONFIRM);assertThat(claim.status()).isEqualTo(ProviderOperation.Status.DISPATCHING);assertThat(claim.version()).isEqualTo(1);
        assertThat(claim.resourceId()).isEqualTo(c.recoveryOperation().id());assertThat(claim.operationKey()).isEqualTo(c.recoveryOperation().providerOperationKey()+":CONFIRM");
        assertThat(claim.fingerprint()).isEqualTo(OwnedRecoveryConfirmService.fingerprint(c,digest));
        var receipt=jdbc.queryForMap("select * from stripe_billing_failed_recovery_confirm_claims where provider_operation=?",claim.id());
        assertThat(receipt).containsEntry("invocation",budget.invocation());
        assertThat(((java.sql.Timestamp)receipt.get("entered_at")).toInstant()).isEqualTo(budget.enteredAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(((java.sql.Timestamp)receipt.get("deadline_at")).toInstant()).isEqualTo(((java.sql.Timestamp)receipt.get("entered_at")).toInstant().plusSeconds(30));
        Boolean independentlyCommitted=jdbc.queryForObject("select recorded_transaction<>pg_current_xact_id() from stripe_billing_failed_recovery_confirm_claims where provider_operation=?",Boolean.class,claim.id());assertThat(independentlyCommitted).isTrue();
        assertThat(confirmer.inspectClaim(c,claim,p.pending().context(),budget)).isEqualTo(claim);
        var reply=confirmationReply(c);var ack=confirmer.acknowledge(c,claim,reply,p.pending().context(),budget);
        assertThat(ack.reply()).isEqualTo(reply);assertThat(ack.operation().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(ack.operation().version()).isEqualTo(2);
        assertThat(ack.operation().providerReference()).isEqualTo(p.pending().failure().paymentIntent());
        assertThat(count("stripe_billing_failed_recovery_confirm_claims")).isEqualTo(1);assertThat(count("stripe_billing_failed_recovery_confirm_acks")).isEqualTo(1);
        assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);
        assertThat(jdbc.queryForMap("select * from subscription_operations where id=?",c.setupOperation().id())).isEqualTo(setupBefore);
        assertThat(jdbc.queryForMap("select * from subscription_operations where id=?",c.recoveryOperation().id())).isEqualTo(recoverBefore);
        assertThat(jdbc.queryForObject("select count(*) from stripe_subscription_recovery_proofs where operation_id=? and pay_step is null and pay_kind is null",Long.class,c.recoveryOperation().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='INVOICE_PAY'",Long.class)).isZero();
        int observerStart=http.calls.size();recoveredGraph(p.prepared());var publication=methodObserver(p.pending().service()).observeRecovery(p.pending().reservation(),p.recovery());assertObservationSlice(p.prepared(),observerStart,15);
        assertThat(publication.publication().billId()).isEqualTo(c.reservation().failure().billId());assertThat(current().debt()).isNull();assertThat(count("payments")).isEqualTo(2);
        assertThat(jdbc.queryForMap("select * from payments where id=?",p.prepared().setup().upstream().payment())).isEqualTo(original);
        assertThat(jdbc.queryForObject("select provider_payment_reference from payments where id=?",String.class,publication.publication().paymentId())).isEqualTo(c.reservation().failure().paymentIntent());
        assertThat(operations.findById(claim.id())).contains(ack.operation());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void singleUseRecoveryCompositionConfirmsThenIndependentlyObservesOnlyOriginalBill(boolean legacy) throws Exception {
        assertSingleUseRecoveryComposition(legacy,false);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void persistedReadyTimeSingleUseRecoveryCompositionConfirmsThenIndependentlyObservesOnlyOriginalBill(boolean legacy) throws Exception {
        time.usePersisted();
        assertSingleUseRecoveryComposition(legacy,true);
    }

    private void assertSingleUseRecoveryComposition(boolean legacy,boolean persistedTime) throws Exception {
        // Both browser paths traverse the genuine original SETUP, controller permission, independent
        // binding/METHOD completion and original recovery. No positive financial evidence is DML seeded.
        var p=recoveryPrepared(legacy,0);var c=inspectRecovery(p);var before=financialState();
        if(persistedTime) {
            assertThat(time.selected).isNull();assertThat(time.samples).isZero();
            assertThat(time.beforeFailedAdmissionSamples).isGreaterThanOrEqualTo(2);
            assertThat(p.pending().reservation().billingSample()).isEqualTo(persistedExpected());
            assertPersistedSampleUnchanged();
            assertThat(jdbc.queryForObject("select frozen_time from stripe_billing_first_renewal_ready where id=?",Long.class,p.prepared().setup().ready().id()))
                .isEqualTo(1702682000L);
            assertThat(jdbc.queryForList("select billing_at from stripe_billing_failed_method_reads",java.sql.Timestamp.class))
                .isNotEmpty().allSatisfy(at->assertThat(at.toInstant()).isEqualTo(Instant.ofEpochSecond(1702682000L)));
        }
        var originalCash=jdbc.queryForMap("select * from payments where id=?",p.prepared().setup().upstream().payment());
        var originalBill=jdbc.queryForMap("select * from subscription_billing_records where id=?",c.reservation().failure().billId());
        var originalSetup=jdbc.queryForMap("select * from subscription_operations where id=?",c.setupOperation().id());
        var originalRecover=jdbc.queryForMap("select * from subscription_operations where id=?",c.recoveryOperation().id());
        int start=http.calls.size();methodGraph(p.prepared());
        var response=new com.google.gson.JsonObject();response.addProperty("id","pi_renewal");response.addProperty("object","payment_intent");
        response.addProperty("livemode",false);response.addProperty("status","succeeded");response.addProperty("customer",receipt.intent().customerReference());
        response.addProperty("payment_method","pm_saved");response.addProperty("currency","eur");response.addProperty("amount",1000);
        response.addProperty("amount_received",1000);response.addProperty("amount_capturable",0);response.addProperty("capture_method","automatic");
        response.add("payment_method_types",com.google.gson.JsonParser.parseString("[\"card\"]"));response.addProperty("latest_charge","ch_renewal");
        response.add("last_payment_error",com.google.gson.JsonNull.INSTANCE);response.add("next_action",com.google.gson.JsonNull.INSTANCE);http.reply(response.toString());
        var reader=new StripeBillingOwnedFailedRenewalRecoveryReader(p.pending().service(),methodBillingProofs,operations,credentials,clients,digest,Clock.systemUTC());
        var adapter=new StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter(p.pending().service(),reader,recoveryConfirmer(p),credentials,clients,digest,Clock.systemUTC());
        var recovery=new OwnedBillingFailedRenewalRecovery(p.pending().reservation(),p.completion(),p.recovery(),p.pending().context(),adapter,methodObserver(p.pending().service()));
        assertThat(recovery.state()).isEqualTo(State.NEW);assertThat(recovery.confirmation()).isEmpty();assertThat(recovery.publication()).isEmpty();
        recoveryCompositionRefused(recovery::observe);assertThat(recovery.state()).isEqualTo(State.NEW);assertThat(http.calls).hasSize(start);
        var ack=recovery.confirm();assertThat(recovery.state()).isEqualTo(State.CONFIRMED);assertThat(recovery.confirmation()).containsSame(ack);assertThat(recovery.publication()).isEmpty();
        if(persistedTime)assertPersistedSampleUnchanged();
        var calls=http.calls.subList(start,http.calls.size());assertThat(calls).hasSize(18);
        var paths=new ArrayList<>(List.of("/v1/test_helpers/test_clocks/clock_owned","/v1/customers/"+receipt.intent().customerReference(),"/v1/subscriptions/"+receipt.reference(),"/v1/invoices",
            "/v1/invoices/"+p.prepared().setup().upstream().invoice()+"/lines","/v1/invoice_payments","/v1/payment_intents/"+p.prepared().setup().upstream().paymentIntent(),"/v1/charges/"+graph.charge.get("id").getAsString(),
            "/v1/invoice_payments","/v1/invoices/in_renewal/lines","/v1/invoice_payments","/v1/payment_intents/pi_renewal","/v1/charges/ch_renewal","/v1/invoice_payments",
            "/v1/setup_intents/seti_saved","/v1/payment_methods/pm_saved","/v1/test_helpers/test_clocks/clock_owned","/v1/payment_intents/pi_renewal/confirm"));
        assertThat(calls.stream().map(r->r.url().getPath()).toList()).isEqualTo(paths);
        assertThat(calls.subList(0,17)).allSatisfy(r->{assertThat(r.method().name()).isEqualTo("GET");assertThat(r.options().getIdempotencyKey()).isNull();});
        var post=calls.getLast();assertThat(post.method().name()).isEqualTo("POST");
        assertThat(post.params()).containsExactlyInAnyOrderEntriesOf(Map.of("payment_method","pm_saved","off_session",true,"error_on_requires_action",true));
        assertThat(post.options().getIdempotencyKey()).isEqualTo(c.recoveryOperation().providerOperationKey()+":CONFIRM");
        assertThat(calls).allSatisfy(r->{assertThat(r.options().getMaxNetworkRetries()).isZero();assertThat(r.options().getReadTimeout()).isBetween(1,2000);assertThat(r.options().getConnectTimeout()).isBetween(1,2000);assertThat(r.headers().firstValue("Stripe-Version")).contains("2026-09-30.endive");});
        assertThat(http.replies).isEmpty();assertThat(ack.operation().kind()).isEqualTo(ProviderOperation.Kind.OWNED_RECOVERY_INTENT_CONFIRM);
        assertThat(ack.operation().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(ack.operation().version()).isEqualTo(2);
        assertThat(ack.operation().resourceId()).isEqualTo(c.recoveryOperation().id());assertThat(ack.operation().providerReference()).isEqualTo(c.reservation().failure().paymentIntent());
        assertThat(ack.reply().charge()).isEqualTo("ch_renewal"); // Receipt does not invent a distinct-charge assertion.
        assertThat(operations.findById(ack.operation().id())).contains(ack.operation());
        assertThat(count("stripe_billing_failed_recovery_confirm_claims")).isEqualTo(1);assertThat(count("stripe_billing_failed_recovery_confirm_acks")).isEqualTo(1);
        assertThat(financialState()).isEqualTo(before);assertThat(jdbc.queryForMap("select * from subscription_billing_records where id=?",c.reservation().failure().billId())).isEqualTo(originalBill);
        assertThat(jdbc.queryForMap("select * from subscription_operations where id=?",c.setupOperation().id())).isEqualTo(originalSetup);
        assertThat(jdbc.queryForMap("select * from subscription_operations where id=?",c.recoveryOperation().id())).isEqualTo(originalRecover);
        assertThat(jdbc.queryForObject("select count(*) from stripe_subscription_recovery_proofs where operation_id=? and pay_step is null and pay_kind is null",Long.class,c.recoveryOperation().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='INVOICE_PAY'",Long.class)).isZero();
        var acknowledged=confirmationRows();int confirmedCalls=http.calls.size();
        recoveryCompositionRefused(recovery::confirm);assertThat(recovery.state()).isEqualTo(State.CONFIRMED);
        assertThat(confirmationRows()).isEqualTo(acknowledged);assertThat(http.calls).hasSize(confirmedCalls);assertThat(financialState()).isEqualTo(before);
        var paymentEventsBeforeObserve=jdbc.queryForList("select * from payment_events");
        var subscriptionEventsBeforeObserve=jdbc.queryForList("select * from subscription_events");
        var callbacksBeforeObserve=jdbc.queryForList("select * from outbox_events");
        int observed=http.calls.size();recoveredGraph(p.prepared());
        var publication=recovery.observe();assertObservationSlice(p.prepared(),observed,15);
        if(persistedTime)assertPersistedSampleUnchanged();
        assertThat(recovery.state()).isEqualTo(State.COMPLETE);assertThat(recovery.confirmation()).containsSame(ack);assertThat(recovery.publication()).containsSame(publication);
        assertThat(publication.publication().billId()).isEqualTo(c.reservation().failure().billId());assertThat(current().debt()).isNull();
        assertThat(jdbc.queryForMap("select * from payments where id=?",p.prepared().setup().upstream().payment())).isEqualTo(originalCash);assertThat(count("payments")).isEqualTo(2);
        String recoveredPaymentReference=jdbc.queryForObject("select provider_payment_reference from payments where id=?",String.class,publication.publication().paymentId());
        assertThat(recoveredPaymentReference).isEqualTo(c.reservation().failure().paymentIntent());
        assertThat(jdbc.queryForObject("select settlement from subscription_billing_records where id=?",String.class,c.reservation().failure().billId())).isEqualTo("CONFIRMED_CASH");
        assertThat(http.calls.subList(start,http.calls.size()).stream().filter(r->r.method().name().equals("POST"))).hasSize(1);
        assertThat(operations.findById(ack.operation().id())).contains(ack.operation());
        var recoveredPayment=publication.publication().paymentId();var originalSubscription=c.subscription().id();var originalDebt=c.reservation().failure().billId();
        assertThat(jdbc.queryForMap("select * from payments where id=?",recoveredPayment))
            .containsEntry("site_id",site.value()).containsEntry("customer_id",customer.value()).containsEntry("payment_account_id",billing.id().value())
            .containsEntry("subscription_id",originalSubscription).containsEntry("billing_record_id",originalDebt).containsEntry("source","SUBSCRIPTION");
        var paymentEventsAfterObserve=jdbc.queryForList("select * from payment_events");
        assertThat(paymentEventsAfterObserve).containsAll(paymentEventsBeforeObserve).hasSize(paymentEventsBeforeObserve.size()+1);
        var recoveryPaymentEvents=paymentEventsAfterObserve.stream().filter(event->!paymentEventsBeforeObserve.contains(event)).toList();
        assertThat(recoveryPaymentEvents).singleElement().satisfies(event->assertThat(event)
            .containsEntry("payment_id",recoveredPayment).containsEntry("site_id",site.value()).containsEntry("payment_account_id",billing.id().value())
            .containsEntry("event_type","PAYMENT_SUCCEEDED").containsEntry("source","PROVIDER_EVENT").containsEntry("sequence_number",1L)
            .containsEntry("provider_object_id",c.reservation().failure().paymentIntent()).containsEntry("amount_minor",1000L).containsEntry("currency","EUR"));
        var recoveryPaymentEvent=recoveryPaymentEvents.getFirst();
        var subscriptionEventsAfterObserve=jdbc.queryForList("select * from subscription_events");
        assertThat(subscriptionEventsAfterObserve).containsAll(subscriptionEventsBeforeObserve).hasSize(subscriptionEventsBeforeObserve.size()+1);
        var recoverySubscriptionEvents=subscriptionEventsAfterObserve.stream().filter(event->!subscriptionEventsBeforeObserve.contains(event)).toList();
        assertThat(recoverySubscriptionEvents).singleElement().satisfies(event->assertThat(event)
            .containsEntry("subscription_id",originalSubscription).containsEntry("site_id",site.value()).containsEntry("payment_account_id",billing.id().value())
            .containsEntry("event_type","PERIOD_PAID").containsEntry("source","RECONCILIATION").containsEntry("billing_record_id",originalDebt)
            .containsEntry("revision",c.subscription().revision()+1).containsEntry("provider_reference",receipt.reference())
            .containsEntry("payment_id",null).containsEntry("operation_id",null));
        var recoverySubscriptionEvent=recoverySubscriptionEvents.getFirst();
        var callbacksAfterObserve=jdbc.queryForList("select * from outbox_events");
        assertThat(callbacksAfterObserve).containsAll(callbacksBeforeObserve).hasSize(callbacksBeforeObserve.size()+2);
        var recoveryCallbacks=callbacksAfterObserve.stream().filter(event->!callbacksBeforeObserve.contains(event)).toList();
        assertThat(recoveryCallbacks).extracting(event->event.get("callback_type")).containsExactlyInAnyOrder("payment.succeeded","subscription.updated");
        assertThat(recoveryCallbacks.stream().filter(event->event.get("source_kind").equals("PAYMENT")).toList()).singleElement()
            .satisfies(event->assertThat(event).containsEntry("id",recoveryPaymentEvent.get("id")).containsEntry("payment_id",recoveredPayment)
                .containsEntry("site_id",site.value()).containsEntry("payment_account_id",billing.id().value()).containsEntry("callback_type","payment.succeeded")
                .containsEntry("subscription_id",null).containsEntry("subscription_event_id",null).containsEntry("status","PENDING").containsEntry("attempt_count",0));
        assertThat(recoveryCallbacks.stream().filter(event->event.get("source_kind").equals("SUBSCRIPTION")).toList()).singleElement()
            .satisfies(event->assertThat(event).containsEntry("id",recoverySubscriptionEvent.get("id")).containsEntry("subscription_event_id",recoverySubscriptionEvent.get("id"))
                .containsEntry("subscription_id",originalSubscription).containsEntry("site_id",site.value()).containsEntry("payment_account_id",billing.id().value())
                .containsEntry("callback_type","subscription.updated").containsEntry("payment_id",null).containsEntry("status","PENDING").containsEntry("attempt_count",0));
        var paymentCallbackPayload=jdbc.queryForMap("""
            select payload->>'event_id' as event_id,payload->>'event_type' as event_type,payload->>'site_id' as site_id,
             payload->>'payment_id' as payment_id,payload->>'subscription_id' as subscription_id,payload->>'billing_record_id' as billing_record_id,
             payload->>'customer_id' as customer_id,payload->>'billing_kind' as billing_kind,payload->>'payment_status' as payment_status,
             payload->>'amount_minor' as amount_minor,payload->>'currency' as currency from outbox_events where id=?
            """,recoveryPaymentEvent.get("id"));
        assertThat(paymentCallbackPayload).containsEntry("event_id",recoveryPaymentEvent.get("id").toString()).containsEntry("event_type","payment.succeeded")
            .containsEntry("site_id",site.value().toString()).containsEntry("payment_id",recoveredPayment.toString()).containsEntry("subscription_id",originalSubscription.toString())
            .containsEntry("billing_record_id",originalDebt.toString()).containsEntry("customer_id",customer.value().toString()).containsEntry("billing_kind","RENEWAL")
            .containsEntry("payment_status","SUCCEEDED").containsEntry("amount_minor","1000").containsEntry("currency","EUR");
        var subscriptionCallbackPayload=jdbc.queryForMap("""
            select payload->>'event_id' as event_id,payload->>'event_type' as event_type,payload->>'site_id' as site_id,
             payload->>'subscription_id' as subscription_id,payload->>'billing_record_id' as billing_record_id,payload->>'customer_id' as customer_id,
             payload->>'revision' as revision,payload->>'state' as state,payload->>'payment_id' as payment_id from outbox_events where id=?
            """,recoverySubscriptionEvent.get("id"));
        assertThat(subscriptionCallbackPayload).containsEntry("event_id",recoverySubscriptionEvent.get("id").toString()).containsEntry("event_type","subscription.updated")
            .containsEntry("site_id",site.value().toString()).containsEntry("subscription_id",originalSubscription.toString()).containsEntry("billing_record_id",originalDebt.toString())
            .containsEntry("customer_id",customer.value().toString()).containsEntry("revision",Long.toString(c.subscription().revision()+1))
            .containsEntry("state","ACTIVE").containsEntry("payment_id",null);
        var settled=financialState();var settledReceipts=confirmationRows();int settledCalls=http.calls.size();
        recoveryCompositionRefused(recovery::confirm);recoveryCompositionRefused(recovery::observe);
        assertThat(recovery.state()).isEqualTo(State.COMPLETE);assertThat(recovery.confirmation()).containsSame(ack);assertThat(recovery.publication()).containsSame(publication);
        assertThat(http.calls).hasSize(settledCalls);assertThat(financialState()).isEqualTo(settled);assertThat(confirmationRows()).isEqualTo(settledReceipts);
        assertThat(count("stripe_billing_failed_method_publications")).isEqualTo(1);assertThat(count("stripe_billing_failed_recovery_confirm_acks")).isEqualTo(1);
        if(persistedTime) {
            assertThat(time.persistedSamples).isNotEmpty().allSatisfy(sample->assertThat(sample).isEqualTo(persistedExpected()));
            assertThat(time.persistedOperationalTimes).allSatisfy(utc->assertThat(utc).isNotEqualTo(Instant.ofEpochSecond(1702682000L)));
            assertThat(time.samples).isZero();assertThat(time.selected).isNull();
            assertThat(jdbc.queryForList("select billing_at from stripe_billing_failed_method_reads",java.sql.Timestamp.class))
                .hasSize(2).allSatisfy(at->assertThat(at.toInstant()).isEqualTo(Instant.ofEpochSecond(1702682000L)));
        }
    }

    private OwnedBillingFailedRenewalRecovery recoveryComposition(RecoveryPrepared p) {
        var reader=new StripeBillingOwnedFailedRenewalRecoveryReader(p.pending().service(),methodBillingProofs,operations,credentials,clients,digest,Clock.systemUTC());
        var adapter=new StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter(p.pending().service(),reader,recoveryConfirmer(p),credentials,clients,digest,Clock.systemUTC());
        return new OwnedBillingFailedRenewalRecovery(p.pending().reservation(),p.completion(),p.recovery(),p.pending().context(),adapter,methodObserver(p.pending().service()));
    }
    private void queueRecoveryCompositionConfirmation(RecoveryPrepared p) {
        methodGraph(p.prepared());
        var response=new com.google.gson.JsonObject();response.addProperty("id","pi_renewal");response.addProperty("object","payment_intent");
        response.addProperty("livemode",false);response.addProperty("status","succeeded");response.addProperty("customer",receipt.intent().customerReference());
        response.addProperty("payment_method","pm_saved");response.addProperty("currency","eur");response.addProperty("amount",1000);
        response.addProperty("amount_received",1000);response.addProperty("amount_capturable",0);response.addProperty("capture_method","automatic");
        response.add("payment_method_types",com.google.gson.JsonParser.parseString("[\"card\"]"));response.addProperty("latest_charge","ch_renewal");
        response.add("last_payment_error",com.google.gson.JsonNull.INSTANCE);response.add("next_action",com.google.gson.JsonNull.INSTANCE);http.reply(response.toString());
    }
    private static void recoveryCompositionRefused(Runnable work) {
        assertThatThrownBy(work::run).isExactlyInstanceOf(PaymentApplicationException.class)
            .hasMessage("Owned recovery scenario unavailable").hasNoCause().hasNoSuppressedExceptions()
            .satisfies(error->{var refusal=(PaymentApplicationException)error;
                assertThat(refusal.code()).isEqualTo("OWNED_RECOVERY_SCENARIO_UNAVAILABLE");assertThat(refusal.status()).isEqualTo(409);
                assertThat(error.toString()).doesNotContain("PRIVATE_PROVIDER_DETAIL","sk_test","pi_renewal","pm_saved");
            });
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void recoveryCompositionObserverFailureRetainsActualAckAndImmutableFirstCashWithoutPublication(boolean legacy) throws Exception {
        var p=recoveryPrepared(legacy,0);var recovery=recoveryComposition(p);var before=financialState();var debt=current().debt();
        var initial=jdbc.queryForMap("select * from payments where id=?",p.prepared().setup().upstream().payment());
        var bill=jdbc.queryForMap("select * from subscription_billing_records where id=?",p.pending().reservation().failure().billId());
        queueRecoveryCompositionConfirmation(p);var ack=recovery.confirm();
        var claims=rows("stripe_billing_failed_recovery_confirm_claims");var acks=rows("stripe_billing_failed_recovery_confirm_acks");var providerRows=rows("provider_operations");
        assertThat(recovery.state()).isEqualTo(State.CONFIRMED);assertThat(financialState()).isEqualTo(before);
        int observed=http.calls.size();recoveredGraph(p.prepared());
        replaceFailedReply(0,clock->clock.addProperty("id","clock_foreign"));
        recoveryCompositionRefused(recovery::observe);assertThat(recovery.state()).isEqualTo(State.FAILED);
        assertThat(recovery.confirmation()).containsSame(ack);assertThat(recovery.publication()).isEmpty();
        assertThat(operations.findById(ack.operation().id())).contains(ack.operation());
        assertThat(rows("stripe_billing_failed_recovery_confirm_claims")).isEqualTo(claims);assertThat(rows("stripe_billing_failed_recovery_confirm_acks")).isEqualTo(acks);
        assertThat(rows("provider_operations")).isEqualTo(providerRows);
        assertThat(http.calls).hasSize(observed+1);assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);
        assertThat(count("stripe_billing_failed_method_publications")).isZero();assertThat(count("payments")).isEqualTo(1);
        assertThat(financialState()).isEqualTo(before);assertThat(current().debt()).isEqualTo(debt);
        assertThat(jdbc.queryForMap("select * from payments where id=?",p.prepared().setup().upstream().payment())).isEqualTo(initial);
        assertThat(jdbc.queryForMap("select * from subscription_billing_records where id=?",p.pending().reservation().failure().billId())).isEqualTo(bill);
        int calls=http.calls.size();var receipts=confirmationRows();recoveryCompositionRefused(recovery::confirm);recoveryCompositionRefused(recovery::observe);
        assertThat(http.calls).hasSize(calls);assertThat(confirmationRows()).isEqualTo(receipts);assertThat(count("stripe_billing_failed_method_publications")).isZero();
        assertThat(http.calls.stream().filter(request->request.method().name().equals("POST")&&request.url().getPath().equals("/v1/payment_intents/pi_renewal/confirm"))).hasSize(1);
    }

    @Test void recoveryCompositionFreshInstanceCannotReplayAnAckBeforeTheOriginalObserverSettles() throws Exception {
        var p=recoveryPrepared(false,0);var owner=recoveryComposition(p);queueRecoveryCompositionConfirmation(p);
        var before=financialState();var ack=owner.confirm();var receipts=confirmationRows();int start=http.calls.size();
        var replay=recoveryComposition(p);recoveryCompositionRefused(replay::observe);assertThat(replay.state()).isEqualTo(State.NEW);assertThat(http.calls).hasSize(start);
        methodGraph(p.prepared());recoveryCompositionRefused(replay::confirm);
        assertObservationSlice(p.prepared(),start,17);assertThat(replay.state()).isEqualTo(State.FAILED);
        assertThat(replay.confirmation()).isEmpty();assertThat(replay.publication()).isEmpty();assertThat(owner.state()).isEqualTo(State.CONFIRMED);
        assertThat(owner.confirmation()).containsSame(ack);assertThat(confirmationRows()).isEqualTo(receipts);assertThat(financialState()).isEqualTo(before);
        assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(count("stripe_billing_failed_method_publications")).isZero();
        int observed=http.calls.size();recoveredGraph(p.prepared());var publication=owner.observe();assertObservationSlice(p.prepared(),observed,15);
        assertThat(owner.state()).isEqualTo(State.COMPLETE);assertThat(owner.publication()).containsSame(publication);assertThat(current().debt()).isNull();
        assertThat(count("payments")).isEqualTo(2);assertThat(count("stripe_billing_failed_method_publications")).isEqualTo(1);
        assertThat(count("stripe_billing_failed_recovery_confirm_claims")).isEqualTo(1);assertThat(count("stripe_billing_failed_recovery_confirm_acks")).isEqualTo(1);
        assertThat(http.calls.stream().filter(request->request.method().name().equals("POST")&&request.url().getPath().equals("/v1/payment_intents/pi_renewal/confirm"))).hasSize(1);
    }

    @Test void recoveryCompositionConcurrentPhaseRefusalsCannotPoisonTheActualObserverOwner() throws Exception {
        var p=recoveryPrepared(false,0);var recovery=recoveryComposition(p);queueRecoveryCompositionConfirmation(p);var ack=recovery.confirm();
        var entered=new CountDownLatch(1);var rejected=new CountDownLatch(1);var once=new java.util.concurrent.atomic.AtomicBoolean();
        int observed=http.calls.size();recoveredGraph(p.prepared());
        try(var workers=new BoundedWorkers()) {
            var loser=workers.submit(()->{
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                try {
                    assertThat(recovery.state()).isEqualTo(State.OBSERVING);recoveryCompositionRefused(recovery::confirm);recoveryCompositionRefused(recovery::observe);
                    assertThat(recovery.state()).isEqualTo(State.OBSERVING);assertThat(recovery.confirmation()).containsSame(ack);assertThat(recovery.publication()).isEmpty();return true;
                } finally {rejected.countDown();}
            });
            var previous=http.afterResponse;
            http.afterResponse=()->{
                previous.run();
                if(once.compareAndSet(false,true)){
                    entered.countDown();
                    try {assertThat(rejected.await(1,TimeUnit.SECONDS)).isTrue();}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
                }
            };
            var publication=recovery.observe();assertThat(loser.get(5,TimeUnit.SECONDS)).isTrue();assertThat(once).isTrue();
            assertObservationSlice(p.prepared(),observed,15);assertThat(recovery.state()).isEqualTo(State.COMPLETE);
            assertThat(recovery.confirmation()).containsSame(ack);assertThat(recovery.publication()).containsSame(publication);
        }
        assertThat(count("payments")).isEqualTo(2);assertThat(count("stripe_billing_failed_method_publications")).isEqualTo(1);
        assertThat(count("stripe_billing_failed_recovery_confirm_acks")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
        assertThat(http.calls.stream().filter(request->request.method().name().equals("POST")&&request.url().getPath().equals("/v1/payment_intents/pi_renewal/confirm"))).hasSize(1);
    }

    @Test void recoveryCompositionConcurrentRefusalsCannotPoisonTheActualConfirmationOwner() throws Exception {
        var p=recoveryPrepared(false,0);var recovery=recoveryComposition(p);var before=financialState();queueRecoveryCompositionConfirmation(p);
        var entered=new CountDownLatch(1);var rejected=new CountDownLatch(1);var once=new java.util.concurrent.atomic.AtomicBoolean();
        try(var workers=new BoundedWorkers()) {
            var loser=workers.submit(()->{
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                try {
                    assertThat(recovery.state()).isEqualTo(State.CONFIRMING);recoveryCompositionRefused(recovery::confirm);recoveryCompositionRefused(recovery::observe);
                    assertThat(recovery.state()).isEqualTo(State.CONFIRMING);assertThat(recovery.confirmation()).isEmpty();assertThat(recovery.publication()).isEmpty();return true;
                } finally {rejected.countDown();}
            });
            var previous=http.afterResponse;
            http.afterResponse=()->{
                previous.run();
                if(once.compareAndSet(false,true)){
                    entered.countDown();
                    try {assertThat(rejected.await(1,TimeUnit.SECONDS)).isTrue();}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
                }
            };
            var ack=recovery.confirm();assertThat(loser.get(5,TimeUnit.SECONDS)).isTrue();assertThat(once).isTrue();
            assertThat(recovery.state()).isEqualTo(State.CONFIRMED);assertThat(recovery.confirmation()).containsSame(ack);assertThat(recovery.publication()).isEmpty();
            assertThat(financialState()).isEqualTo(before);assertThat(count("payments")).isEqualTo(1);assertThat(count("stripe_billing_failed_method_publications")).isZero();
            assertThat(count("stripe_billing_failed_recovery_confirm_claims")).isEqualTo(1);assertThat(count("stripe_billing_failed_recovery_confirm_acks")).isEqualTo(1);
            int observed=http.calls.size();recoveredGraph(p.prepared());var publication=recovery.observe();assertObservationSlice(p.prepared(),observed,15);
            assertThat(recovery.state()).isEqualTo(State.COMPLETE);assertThat(recovery.publication()).containsSame(publication);
        }
        assertThat(count("payments")).isEqualTo(2);assertThat(count("stripe_billing_failed_method_publications")).isEqualTo(1);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
        assertThat(http.calls.stream().filter(request->request.method().name().equals("POST")&&request.url().getPath().equals("/v1/payment_intents/pi_renewal/confirm"))).hasSize(1);
    }

    @Test void dedicatedConfirmationFingerprintMatchesHandEnumeratedJavaAndSqlGolden() throws Exception {
        var p=recoveryPrepared(false,0);var c=inspectRecovery(p);var f=c.reservation().failure();var r=c.recoveryOperation();var completion=c.completion();var a=c.account();
        // Independent golden field ordering; do not call the production fingerprint encoder here.
        var facts=List.of(a.id().value().toString(),a.externalAccountId(),a.credentialsRef(),a.provider().name(),a.mode().name(),
            "OWNED_RECOVERY_CONFIRM_V1",r.id().toString(),r.providerOperationKey(),r.fingerprint(),c.reservation().id().toString(),
            f.billId().toString(),Long.toString(f.billVersion()),f.invoice(),f.allocation(),f.paymentIntent(),Long.toString(f.revision()),Long.toString(f.observationSequence()),
            c.binding().id().toString(),c.binding().providerReference(),completion.setupStepId().toString(),Long.toString(completion.setupStepVersion()),
            completion.methodStepId().toString(),Long.toString(completion.methodStepVersion()),completion.method(),"off_session:true","error_on_requires_action:true");
        StringBuilder canonical=new StringBuilder();facts.forEach(value->canonical.append(value.length()).append(':').append(value));String golden=digest.sha256Hex(canonical.toString());
        assertThat(OwnedRecoveryConfirmService.fingerprint(c,digest)).isEqualTo(golden);
        assertThat(jdbc.queryForObject("select owned_recovery_confirm_fingerprint(?)",String.class,c.reservation().id())).isEqualTo(golden);
        var budget=confirmationBudget();var claim=recoveryConfirmer(p).claim(c,p.pending().context(),"golden-claim",budget);assertThat(claim.fingerprint()).isEqualTo(golden);
    }

    @Test void dedicatedConfirmationDuplicateAndConcurrentFirstClaimantsNeverAdopt() throws Exception {
        var p=recoveryPrepared(false,0);var c=inspectRecovery(p);var before=financialState();int calls=http.calls.size();
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var workers=new BoundedWorkers()){
            java.util.concurrent.Callable<Object> attempt=()->{ready.countDown();awaitRelease(start);try{return recoveryConfirmer(p).claim(c,p.pending().context(),UUID.randomUUID().toString(),confirmationBudget());}catch(IllegalStateException refused){return refused;}};
            var first=workers.submit(attempt);var second=workers.submit(attempt);assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            var results=List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertThat(results.stream().filter(ProviderOperation.class::isInstance)).hasSize(1);assertThat(results.stream().filter(IllegalStateException.class::isInstance)).hasSize(1);
        }
        var original=confirmationRows();assertThatThrownBy(()->recoveryConfirmer(p).claim(c,p.pending().context(),"adopter",confirmationBudget())).isInstanceOf(RuntimeException.class);
        assertThat(confirmationRows()).isEqualTo(original);assertThat(count("stripe_billing_failed_recovery_confirm_claims")).isEqualTo(1);
        assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);
    }

    @ParameterizedTest @ValueSource(strings={"claim","ack"})
    void dedicatedConfirmationFinalMutationFailureRollsBackProviderAndReceiptTogether(String phase) throws Exception {
        var p=recoveryPrepared(false,0);var c=inspectRecovery(p);var budget=confirmationBudget();var normal=recoveryConfirmer(p);
        var claim=phase.equals("ack")?normal.claim(c,p.pending().context(),"owner",budget):null;
        var before=financialState();var receipts=confirmationRows();int calls=http.calls.size();var reached=new java.util.concurrent.atomic.AtomicBoolean();
        var broken=observingConfirmationStore((m,args)->{},(m,result)->{if(m.getName().equals(phase.equals("ack")?"acknowledgeInTransaction":"insertAndClaimInTransaction")){reached.set(true);throw new IllegalStateException("after real dedicated mutation");}});
        assertThatThrownBy(()->{if(claim==null)recoveryConfirmer(p,broken).claim(c,p.pending().context(),"owner",budget);else recoveryConfirmer(p,broken).acknowledge(c,claim,confirmationReply(c),p.pending().context(),budget);}).hasMessage("after real dedicated mutation");
        assertThat(reached).isTrue();assertThat(confirmationRows()).isEqualTo(receipts);assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);
    }

    @ParameterizedTest @ValueSource(strings={"ACKNOWLEDGED","UNKNOWN"})
    void dedicatedConfirmationBothTerminalsRequireIndependentlyCommittedFirstClaimEvenAfterImmediateConstraints(String terminal) throws Exception {
        var p=recoveryPrepared(false,0);var c=inspectRecovery(p);var before=financialState();var rows=confirmationRows();var reached=new java.util.concurrent.atomic.AtomicBoolean();
        var broken=observingConfirmationStore((m,args)->{},(m,result)->{if(m.getName().equals("insertAndClaimInTransaction")){
            var claim=(ProviderOperation)result;jdbc.execute("set constraints all immediate");reached.set(true);
            jdbc.update("update provider_operations set status=?,provider_reference=?,lease_owner=null,lease_expires_at=null,version=2 where id=?",terminal,terminal.equals("ACKNOWLEDGED")?c.reservation().failure().paymentIntent():null,claim.id());
        }});
        assertThatThrownBy(()->recoveryConfirmer(p,broken).claim(c,p.pending().context(),"owner",confirmationBudget())).isInstanceOf(RuntimeException.class);
        assertThat(reached).isTrue();assertThat(confirmationRows()).isEqualTo(rows);assertThat(financialState()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void dedicatedConfirmationCannotCommitAnOrphanReservedRowWithOrWithoutEarlyConstraints(boolean immediate) throws Exception {
        var p=recoveryPrepared(false,0);var c=inspectRecovery(p);var before=confirmationRows();var financial=financialState();
        assertThatThrownBy(()->transaction.requiresNew(()->{
            jdbc.update("insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,fingerprint) values(?,?,?,?,'OWNED_RECOVERY_INTENT_CONFIRM',?,?,?)",
                UUID.randomUUID(),billing.id().value(),billing.externalAccountId(),billing.credentialsRef(),c.recoveryOperation().id(),c.recoveryOperation().providerOperationKey()+":CONFIRM",OwnedRecoveryConfirmService.fingerprint(c,digest));
            if(immediate)jdbc.execute("set constraints all immediate");return true;
        })).isInstanceOf(RuntimeException.class);
        assertThat(confirmationRows()).isEqualTo(before);assertThat(financialState()).isEqualTo(financial);
    }

    @ParameterizedTest @ValueSource(strings={"delete","kind","resource","key","fingerprint","account","owner","lease","version-skip","reset","scheduler"})
    void dedicatedConfirmationSqlCannotDeleteConvertRetargetResetOrScheduleItsCommittedClaim(String mutation) throws Exception {
        var p=recoveryPrepared(false,0);var c=inspectRecovery(p);var budget=confirmationBudget();var claim=recoveryConfirmer(p).claim(c,p.pending().context(),"owner",budget);
        var before=confirmationRows();var financial=financialState();
        String sql=switch(mutation){
            case "delete"->"delete from provider_operations where id=?";
            case "kind"->"update provider_operations set kind='INVOICE_PAY' where id=?";
            case "resource"->"update provider_operations set resource_id=gen_random_uuid() where id=?";
            case "key"->"update provider_operations set operation_key=operation_key||':changed' where id=?";
            case "fingerprint"->"update provider_operations set fingerprint='changed' where id=?";
            case "account"->"update provider_operations set credentials_ref='changed' where id=?";
            case "owner"->"update provider_operations set lease_owner='changed' where id=?";
            case "lease"->"update provider_operations set lease_expires_at=lease_expires_at+interval '1 second' where id=?";
            case "version-skip"->"update provider_operations set status='UNKNOWN',version=3,lease_owner=null,lease_expires_at=null where id=?";
            case "reset"->"update provider_operations set status='RESERVED',version=0,lease_owner=null,lease_expires_at=null,first_dispatched_at=null,retry_before=null where id=?";
            default->"update provider_operations set recovery_attempts=recovery_attempts+1,next_attempt_at=clock_timestamp(),manual_review_required=true where id=?";
        };
        assertThatThrownBy(()->transaction.requiresNew(()->jdbc.update(sql,claim.id()))).as(mutation).isInstanceOf(RuntimeException.class);
        assertThat(confirmationRows()).isEqualTo(before);assertThat(financialState()).isEqualTo(financial);
    }

    @ParameterizedTest @ValueSource(strings={"claim-update","claim-delete","claim-xid","ack-update","ack-delete","missing-ack"})
    void dedicatedConfirmationSqlReceiptsAreImmutableAndAckCannotCommitWithoutExactAppendOnlyEvidence(String mutation) throws Exception {
        var p=recoveryPrepared(false,0);var c=inspectRecovery(p);var budget=confirmationBudget();var svc=recoveryConfirmer(p);var claim=svc.claim(c,p.pending().context(),"owner",budget);
        if(mutation.startsWith("ack-"))svc.acknowledge(c,claim,confirmationReply(c),p.pending().context(),budget);
        var before=confirmationRows();var financial=financialState();
        String sql=switch(mutation){
            case "claim-update"->"update stripe_billing_failed_recovery_confirm_claims set claimant='forged' where provider_operation=?";
            case "claim-delete"->"delete from stripe_billing_failed_recovery_confirm_claims where provider_operation=?";
            case "claim-xid"->"update stripe_billing_failed_recovery_confirm_claims set recorded_transaction=pg_current_xact_id() where provider_operation=?";
            case "ack-update"->"update stripe_billing_failed_recovery_confirm_acks set charge='ch_forged' where provider_operation=?";
            case "ack-delete"->"delete from stripe_billing_failed_recovery_confirm_acks where provider_operation=?";
            default->"update provider_operations set status='ACKNOWLEDGED',version=2,provider_reference='pi_renewal',lease_owner=null,lease_expires_at=null where id=?";
        };
        assertThatThrownBy(()->transaction.requiresNew(()->jdbc.update(sql,claim.id()))).isInstanceOf(RuntimeException.class);
        assertThat(confirmationRows()).isEqualTo(before);assertThat(financialState()).isEqualTo(financial);
    }

    @Test void dedicatedConfirmationExactReadableClaimCannotTransferCleanupToAnotherInvocation() throws Exception {
        var p=recoveryPrepared(false,0);var original=inspectRecovery(p);var ownerBudget=confirmationBudget();var svc=recoveryConfirmer(p);
        var owner=svc.claim(original,p.pending().context(),"original-owner",ownerBudget);
        var independentlyInspected=inspectRecovery(p);var otherBudget=confirmationBudget();
        var readback=operations.find(billing.id(),original.recoveryOperation().providerOperationKey()+":CONFIRM").orElseThrow();
        assertThat(operations.findById(owner.id())).contains(readback);assertThat(readback).isEqualTo(owner);
        assertThat(independentlyInspected).isEqualTo(original);assertThat(otherBudget.invocation()).isNotEqualTo(ownerBudget.invocation());
        var receipts=confirmationRows();var before=financialState();int calls=http.calls.size();
        svc.uncertain(independentlyInspected,readback,otherBudget);
        assertThat(confirmationRows()).isEqualTo(receipts);assertThat(operations.findById(owner.id())).contains(owner);
        assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);
        // Exercise the actual JDBC boundary as well as the public service, with no positive authority substitute.
        assertThatThrownBy(()->transaction.requiresNew(()->new JdbcOwnedRecoveryConfirmStore(jdbc).unknownInTransaction(independentlyInspected,readback,otherBudget)))
            .isInstanceOf(RuntimeException.class);
        svc.uncertain(independentlyInspected,readback,null);
        assertThatThrownBy(()->transaction.requiresNew(()->new JdbcOwnedRecoveryConfirmStore(jdbc).unknownInTransaction(independentlyInspected,readback,null)))
            .isInstanceOf(RuntimeException.class);
        assertThat(confirmationRows()).isEqualTo(receipts);assertThat(financialState()).isEqualTo(before);
        var session=((SubscriptionActor.Customer)p.pending().context().actor()).session();
        assertThat(jdbc.update("update customer_sessions set revoked_at=clock_timestamp(),revoked_reason='LOGOUT' where id=?",session.sessionId().value())).isEqualTo(1);
        var revoked=confirmationRows();
        assertThatThrownBy(()->svc.inspectClaim(original,owner,p.pending().context(),ownerBudget)).isInstanceOf(RuntimeException.class);
        svc.uncertain(independentlyInspected,readback,otherBudget);assertThat(confirmationRows()).isEqualTo(revoked);
        assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(financialState()).isEqualTo(before);
        svc.uncertain(independentlyInspected,readback,ownerBudget);
        assertThat(operations.findById(owner.id()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.UNKNOWN);
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);assertThat(count("stripe_billing_failed_recovery_confirm_acks")).isZero();
        assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);
        var stopped=confirmationRows();svc.uncertain(independentlyInspected,readback,ownerBudget);assertThat(confirmationRows()).isEqualTo(stopped);
    }

    @Test void dedicatedConfirmationAckRejectsDifferentInvocationAndUnknownConsumesOnlyOriginalOwner() throws Exception {
        var p=recoveryPrepared(false,0);var c=inspectRecovery(p);var budget=confirmationBudget();var svc=recoveryConfirmer(p);var claim=svc.claim(c,p.pending().context(),"owner",budget);var before=financialState();
        var receipt=confirmationRows();assertThatThrownBy(()->svc.acknowledge(c,claim,confirmationReply(c),p.pending().context(),confirmationBudget())).isInstanceOf(RuntimeException.class);
        assertThat(confirmationRows()).isEqualTo(receipt);
        var foreign=new ProviderOperation(claim.id(),claim.accountId(),claim.externalAccountId(),claim.credentialsRef(),claim.kind(),claim.resourceId(),claim.operationKey(),claim.fingerprint(),claim.status(),claim.providerReference(),claim.firstDispatchedAt(),claim.retryBefore(),"foreign",claim.leaseExpiresAt(),claim.version());
        svc.uncertain(c,foreign,budget);assertThat(confirmationRows()).isEqualTo(receipt);
        svc.uncertain(c,claim,budget);assertThat(operations.findById(claim.id()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.UNKNOWN);
        assertThat(count("stripe_billing_failed_renewal_stops")).isEqualTo(1);assertThat(count("stripe_billing_failed_recovery_confirm_acks")).isZero();assertThat(financialState()).isEqualTo(before);
        var stopped=confirmationRows();svc.uncertain(c,claim,budget);assertThat(confirmationRows()).isEqualTo(stopped);
        assertThatThrownBy(()->svc.claim(c,p.pending().context(),"retry",confirmationBudget())).isInstanceOf(RuntimeException.class);
    }

    @Test void dedicatedConfirmationGenericAndSchedulerPathsCannotMutateGenuineClaimOrTrustForgedCallerKind() throws Exception {
        var p=recoveryPrepared(false,0);var c=inspectRecovery(p);var budget=confirmationBudget();var claim=recoveryConfirmer(p).claim(c,p.pending().context(),"owner",budget);
        var before=confirmationRows();var financial=financialState();var scheduler=new dev.genericpayments.platform.payments.adapter.out.persistence.PendingProviderRecoveryPersistenceAdapter(jdbc,operations,Clock.systemUTC());
        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> forbidden=List.of(
            ()->operations.reserve(billing,claim.kind(),claim.resourceId(),claim.operationKey(),claim.fingerprint()),
            ()->operations.claim(claim.id(),"ordinary",Instant.now().plusSeconds(60),Duration.ofSeconds(60)),
            ()->operations.acknowledge(claim.id(),claim.leaseOwner(),claim.version(),"pi_renewal"),
            ()->operations.unknown(claim.id(),claim.leaseOwner(),claim.version()),()->operations.bindRecovered(claim,"pi_renewal",claim.fingerprint()),()->operations.lockRecovery(claim));
        forbidden.forEach(work->assertThatThrownBy(work).isInstanceOf(RuntimeException.class));assertThat(scheduler.claim(claim.id())).isEmpty();assertThat(scheduler.claimNext()).isEmpty();
        var forged=new ProviderOperation(claim.id(),claim.accountId(),claim.externalAccountId(),claim.credentialsRef(),ProviderOperation.Kind.PAYMENT_PREPARE,claim.resourceId(),claim.operationKey(),claim.fingerprint(),claim.status(),null,claim.firstDispatchedAt(),claim.retryBefore(),claim.leaseOwner(),claim.leaseExpiresAt(),claim.version());
        assertThatThrownBy(()->scheduler.finish(new dev.genericpayments.platform.payments.application.port.out.PendingProviderRecoveryPort.Claim(forged,1,browserNow().plusSeconds(120)),dev.genericpayments.platform.payments.application.port.out.PendingProviderRecoveryPort.Outcome.COMPLETE)).isInstanceOf(RuntimeException.class);
        assertThat(confirmationRows()).isEqualTo(before);assertThat(financialState()).isEqualTo(financial);
    }

    @ParameterizedTest @ValueSource(strings={"expiry","revocation"})
    void dedicatedConfirmationObservedReceiptLockWaitCannotExtendOriginalBrowserAuthority(String mutation) throws Exception {
        var p=recoveryPrepared(false,mutation.equals("expiry")?20:0);var c=inspectRecovery(p);var budget=confirmationBudget();var svc=recoveryConfirmer(p);var claim=svc.claim(c,p.pending().context(),"owner",budget);
        var session=((SubscriptionActor.Customer)p.pending().context().actor()).session();var before=confirmationRows();var financial=financialState();
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var entering=new CountDownLatch(1);var holderPid=new java.util.concurrent.atomic.AtomicInteger();var waiterPid=new java.util.concurrent.atomic.AtomicInteger();
        var observed=observingConfirmationStore((m,args)->{if(m.getName().equals("lockInTransaction")){waiterPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));entering.countDown();}},(m,result)->{});
        try(var workers=new BoundedWorkers()){
            var holder=workers.submit(()->transaction.requiresNew(()->{
                jdbc.queryForList("select id from provider_operations where id=? for update",UUID.class,claim.id());holderPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));locked.countDown();
                try{if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("confirmation receipt hold timed out");}
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}return true;
            }));
            try{
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();var refused=workers.submit(()->{assertThatThrownBy(()->recoveryConfirmer(p,observed).acknowledge(c,claim,confirmationReply(c),p.pending().context(),budget)).isInstanceOf(RuntimeException.class);return true;});
                assertThat(entering.await(5,TimeUnit.SECONDS)).isTrue();boolean blocked=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<until){blocked=Boolean.TRUE.equals(jdbc.queryForObject("select ?=any(pg_blocking_pids(?))",Boolean.class,holderPid.get(),waiterPid.get()));if(blocked)break;Thread.sleep(20);}
                assertThat(blocked).as("actual receipt lock wait observed").isTrue();assertThat(refused.isDone()).isFalse();
                if(mutation.equals("revocation"))jdbc.update("update customer_sessions set revoked_at=clock_timestamp(),revoked_reason='LOGOUT' where id=?",session.sessionId().value());
                else{var now=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();assertThat(now).isBefore(session.expiresAt());Thread.sleep(Duration.between(now,session.expiresAt()).plusMillis(50));assertThat(jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant()).isAfterOrEqualTo(session.expiresAt());}
                assertThat(holder.isDone()).as("holder still owns its transaction at authority expiry/revocation").isFalse();
                Boolean stillBlocked=jdbc.queryForObject("select ?=any(pg_blocking_pids(?))",Boolean.class,holderPid.get(),waiterPid.get());
                assertThat(stillBlocked).as("the same provider-row holder still blocks the financial transaction after expiry/revocation").isTrue();assertThat(refused.isDone()).isFalse();
                release.countDown();assertThat(holder.get(10,TimeUnit.SECONDS)).isTrue();assertThat(refused.get(10,TimeUnit.SECONDS)).isTrue();
            }finally{release.countDown();}
        }
        // Revocation is an intentional external authority mutation, so compare all confirmation and financial receipts.
        assertThat(rows("stripe_billing_failed_recovery_confirm_acks")).isEqualTo(before.get("stripe_billing_failed_recovery_confirm_acks"));assertThat(rows("provider_operations")).isEqualTo(before.get("provider_operations"));assertThat(financialState()).isEqualTo(financial);
    }

    @Test void dedicatedConfirmationDirectSqlRefusesContendedCanonicalOperationWithoutLockInversion() throws Exception {
        var p=recoveryPrepared(false,0);var c=inspectRecovery(p);var budget=confirmationBudget();var claim=recoveryConfirmer(p).claim(c,p.pending().context(),"owner",budget);
        var before=confirmationRows();var financial=financialState();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var workers=new BoundedWorkers()){
            var holder=workers.submit(()->transaction.requiresNew(()->{lifecycleOperations.lock(c.setupOperation().id());locked.countDown();awaitRelease(release);return true;}));
            try{
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->transaction.requiresNew(()->jdbc.update("update provider_operations set status='UNKNOWN',lease_owner=null,lease_expires_at=null,version=2 where id=?",claim.id())))
                    .isInstanceOf(RuntimeException.class).hasStackTraceContaining("Owned recovery confirmation authority contended");
            }finally{release.countDown();}
            assertThat(holder.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(confirmationRows()).isEqualTo(before);assertThat(financialState()).isEqualTo(financial);
    }

    @Test void dedicatedConfirmationDirectSqlAckRefusesOriginalRecoveryReferenceDrift() throws Exception {
        var p=recoveryPrepared(false,0);var c=inspectRecovery(p);var budget=confirmationBudget();var claim=recoveryConfirmer(p).claim(c,p.pending().context(),"owner",budget);
        var before=confirmationRows();var financial=financialState();int calls=http.calls.size();
        assertThatThrownBy(()->transaction.requiresNew(()->{
            assertThat(jdbc.update("update subscription_operations set provider_reference='sub_drifted' where id=?",c.recoveryOperation().id())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select version from subscription_operations where id=?",Long.class,c.recoveryOperation().id())).isEqualTo(c.recoveryOperation().version());
            // Invoke actual caller-transaction JDBC directly: SQL must refuse even without the Java method validator.
            return new JdbcOwnedRecoveryConfirmStore(jdbc).acknowledgeInTransaction(c,claim,confirmationReply(c),browserNow(),budget);
        })).isInstanceOf(RuntimeException.class);
        assertThat(confirmationRows()).isEqualTo(before);assertThat(financialState()).isEqualTo(financial);assertThat(http.calls).hasSize(calls);
    }

    @ParameterizedTest @ValueSource(strings={"lock","delete"})
    void dedicatedConfirmationDirectSqlAckRefusesContendedExactAllowedOrigin(String contention) throws Exception {
        var p=recoveryPrepared(false,0);var c=inspectRecovery(p);var budget=confirmationBudget();var claim=recoveryConfirmer(p).claim(c,p.pending().context(),"owner",budget);
        var before=confirmationRows();var financial=financialState();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var workers=new BoundedWorkers()){
            var holder=workers.submit(()->{
                assertThatThrownBy(()->transaction.requiresNew(()->{
                    if(contention.equals("lock"))assertThat(jdbc.queryForList("select site_id from site_allowed_origins where site_id=? and origin=? for update",UUID.class,c.subscription().siteId().value(),c.enrollment().origin())).containsExactly(c.subscription().siteId().value());
                    else assertThat(jdbc.update("delete from site_allowed_origins where site_id=? and origin=?",c.subscription().siteId().value(),c.enrollment().origin())).isEqualTo(1);
                    locked.countDown();try{if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("allowed origin hold timed out");}
                    catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
                    throw new NegativeOwnedPayReceiptRollback();
                })).isExactlyInstanceOf(NegativeOwnedPayReceiptRollback.class);return true;
            });
            try{
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();assertThat(holder.isDone()).isFalse();
                // Exact normalized ACK is generated by the actual JDBC store; only the origin's row is contended.
                assertThatThrownBy(()->transaction.requiresNew(()->new JdbcOwnedRecoveryConfirmStore(jdbc).acknowledgeInTransaction(c,claim,confirmationReply(c),browserNow(),budget)))
                    .isInstanceOf(RuntimeException.class).hasStackTraceContaining("Owned recovery confirmation authority contended");
                assertThat(holder.isDone()).isFalse();
            }finally{release.countDown();}
            assertThat(holder.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(confirmationRows()).isEqualTo(before);assertThat(financialState()).isEqualTo(financial);
    }
    private Map<String,List<String>> recoveryPreparationRows(){
        var state=new TreeMap<>(authorityRows());
        for(var table:List.of("provider_operations","stripe_subscription_setup_proofs","stripe_billing_failed_method_reservations",
                "stripe_billing_failed_method_reads","stripe_billing_failed_method_completions","stripe_billing_failed_method_recoveries",
                "stripe_billing_failed_method_publications","stripe_billing_failed_renewal_stops","stripe_billing_method_browser_roots",
                "stripe_billing_method_browser_selections","stripe_billing_method_browser_releases","stripe_billing_method_browser_consumptions",
                "subscription_events","outbox_events"))state.put(table,rows(table));
        return state;
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void ordinaryInvoicePayReservationRefusesGenuinePreparedOwnedRecoveryWithoutChangingAnyReceipt(boolean legacy) throws Exception {
        var p=recoveryPrepared(legacy,0);var context=inspectRecovery(p);var recover=context.recoveryOperation();
        var before=financialState();var receipts=recoveryPreparationRows();int calls=http.calls.size();
        assertThatThrownBy(()->operations.reserve(billing,ProviderOperation.Kind.INVOICE_PAY,recover.id(),
            recover.providerOperationKey()+":INVOICE_PAY","negative-owned-pay-admission"))
            .isInstanceOfSatisfying(PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
        assertThat(inspectRecovery(p)).isEqualTo(context);
        assertThat(financialState()).isEqualTo(before);assertThat(recoveryPreparationRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='INVOICE_PAY' and resource_id=?",Long.class,recover.id())).isZero();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void ordinaryLikeRecoveryAuthorityCannotBypassDurableClockOwnership(boolean legacy) throws Exception {
        var p=recoveryPrepared(legacy,0);var context=inspectRecovery(p);var recover=context.recoveryOperation();
        var before=financialState();var receipts=recoveryPreparationRows();int calls=http.calls.size();
        assertThatThrownBy(()->transaction.requiresNew(()->{
            // Deliberate negative authority drift only, fully rolled back. The customer-style
            // scope matches ordinary recovery but cannot remove durable clock ownership.
            String ordinaryScope="customer:"+customer.value();
            assertThat(jdbc.update("update subscription_operations set authority_scope=?,version=version+1,updated_at=clock_timestamp() where id=?",
                ordinaryScope,recover.id())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select authority_scope from subscription_operations where id=?",String.class,recover.id())).isEqualTo(ordinaryScope);
            var drifted=recoveryPreparationRows();var state=financialState();
            var direct=new JdbcProviderOperationStore(jdbc);
            assertThatThrownBy(()->direct.reserve(billing,ProviderOperation.Kind.INVOICE_PAY,recover.id(),
                recover.providerOperationKey()+":INVOICE_PAY","negative-authority-drift-pay"))
                .isInstanceOfSatisfying(PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
            assertThat(financialState()).isEqualTo(state);assertThat(recoveryPreparationRows()).isEqualTo(drifted);assertThat(http.calls).hasSize(calls);
            assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='INVOICE_PAY' and resource_id=?",Long.class,recover.id())).isZero();
            throw new NegativeOwnedPayReceiptRollback();
        })).isExactlyInstanceOf(NegativeOwnedPayReceiptRollback.class);
        assertThat(inspectRecovery(p)).isEqualTo(context);
        assertThat(financialState()).isEqualTo(before);assertThat(recoveryPreparationRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
    }
    @ParameterizedTest @ValueSource(strings={"claim","acknowledge","bind"})
    void ordinaryInvoicePayLegacyReceiptCannotMutateGenuinePreparedOwnedRecovery(String boundary) throws Exception {
        var p=recoveryPrepared(false,0);var context=inspectRecovery(p);var recover=context.recoveryOperation();
        var before=financialState();var receipts=recoveryPreparationRows();int calls=http.calls.size();
        assertThatThrownBy(()->transaction.requiresNew(()->{
            // Negative legacy input only. Seed just the provider receipt inside this rollback-only
            // transaction; it is neither a dispatch nor positive provider/financial evidence.
            var id=UUID.randomUUID();var first=Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            boolean reserved=boundary.equals("claim"),leased=boundary.equals("acknowledge");
            jdbc.update("""
                insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,
                 operation_key,fingerprint,status,first_dispatched_at,retry_before,lease_owner,lease_expires_at,version)
                values(?,?,?,?,'INVOICE_PAY',?,?,?,?,?,?,?,?,?)
                """,id,billing.id().value(),billing.externalAccountId(),billing.credentialsRef(),recover.id(),
                recover.providerOperationKey()+":INVOICE_PAY","negative-legacy-owned-pay",
                reserved?"RESERVED":leased?"DISPATCHING":"UNKNOWN",
                reserved?null:java.sql.Timestamp.from(first),reserved?null:java.sql.Timestamp.from(first.plus(Duration.ofHours(23))),
                leased?"negative-legacy-owner":null,leased?java.sql.Timestamp.from(first.plusSeconds(60)):null,reserved?0L:1L);
            // Direct store shares this transaction, so the seed cannot escape rollback or require
            // a committed synthetic receipt just to exercise the genuine public boundary.
            var direct=new JdbcProviderOperationStore(jdbc);var original=direct.findById(id).orElseThrow();
            var seeded=recoveryPreparationRows();var state=financialState();
            assertThatThrownBy(()->{
                switch(boundary){
                    case "claim"->direct.claim(id,"negative-first-claim",Instant.now(),Duration.ofSeconds(60));
                    case "acknowledge"->direct.acknowledge(id,"negative-legacy-owner",original.version(),p.pending().failure().invoice());
                    default->direct.bindRecovered(original,p.pending().failure().invoice(),original.fingerprint());
                }
            }).isInstanceOfSatisfying(PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
            assertThat(direct.findById(id)).contains(original);
            assertThat(financialState()).isEqualTo(state);assertThat(recoveryPreparationRows()).isEqualTo(seeded);assertThat(http.calls).hasSize(calls);
            throw new NegativeOwnedPayReceiptRollback();
        })).isExactlyInstanceOf(NegativeOwnedPayReceiptRollback.class);
        assertThat(inspectRecovery(p)).isEqualTo(context);
        assertThat(financialState()).isEqualTo(before);assertThat(recoveryPreparationRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
    }
    private static final class NegativeOwnedPayReceiptRollback extends RuntimeException {}
    @ParameterizedTest @ValueSource(strings={"recovery","setup","session"})
    void recoveryInspectionNeverLocksAnExistingForeignCallerLocator(String locator) throws Exception {
        var p=recoveryPrepared(false,0);var pending=p.pending();var r=pending.reservation();
        var original=((SubscriptionActor.Customer)pending.context().actor()).session();
        var suppliedReservation=r;var suppliedRecovery=p.recovery();var suppliedContext=pending.context();UUID foreignOperation=null;
        if(locator.equals("session")){
            var foreignCustomer=new CustomerId(UUID.randomUUID());
            customerStore.save(dev.genericpayments.platform.customers.domain.Customer.create(foreignCustomer,site,null,null,Instant.now()));
            TestClock.set(Instant.now());var issued=sessions.issue(site,foreignCustomer,original.origin());
            var realForeign=sessions.resolveRequired(issued.rawToken(),original.origin());
            assertThat(realForeign.customerId()).isEqualTo(foreignCustomer);
            var forged=new dev.genericpayments.platform.customers.application.model.CustomerSessionContext(realForeign.sessionId(),original.siteId(),original.customerId(),original.origin(),original.scopes(),original.expiresAt());
            suppliedContext=new SubscriptionCommandContext(new SubscriptionActor.Customer(forged),site,customer,original.origin(),pending.context().idempotencyKey(),"foreign-existing-session");
        }else{
            // Inert unrelated subscription and RESERVED operation only; no successful proof/ACK/completion is seeded.
            var foreignCustomer=new CustomerId(UUID.randomUUID());
            customerStore.save(dev.genericpayments.platform.customers.domain.Customer.create(foreignCustomer,site,null,null,Instant.now()));
            var foreignSubscription=UUID.randomUUID();foreignOperation=UUID.randomUUID();
            jdbc.update("""
                insert into subscriptions(id,site_id,customer_id,product_id,payment_account_id,currency,billing_price_id,
                  billing_price_key,billing_price_version,billing_amount_minor,billing_interval,billing_trial_days,
                  billing_trial_policy,billing_tax_mode,billing_status,created_at,updated_at)
                select ?,site_id,?,product_id,payment_account_id,currency,billing_price_id,
                  billing_price_key,billing_price_version,billing_amount_minor,billing_interval,billing_trial_days,
                  billing_trial_policy,billing_tax_mode,'INCOMPLETE',clock_timestamp(),clock_timestamp()
                from subscriptions where id=?
                """,foreignSubscription,foreignCustomer.value(),current().id());
            var at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var candidate=new SubscriptionOperation(foreignOperation,site,foreignCustomer,foreignSubscription,SubscriptionOperation.Kind.RECOVER,SubscriptionOperation.Status.RESERVED,
                "inert-foreign","inert-foreign","inert-foreign","inert-foreign:"+foreignOperation,0,1,null,null,at,at,0);
            assertThat(transaction.requiresNew(()->lifecycleOperations.claim(candidate)).operation()).isEqualTo(candidate);
            assertThat(lifecycleOperations.find(site,foreignSubscription,candidate.id())).contains(candidate);
            if(locator.equals("setup"))suppliedReservation=new OwnedFailedRenewalMethod.Reservation(r.id(),r.admission(),r.failure(),candidate.id(),r.requestFingerprint(),r.initialOperationVersion(),r.binding(),r.billingSample(),r.reservedAt());
            else{
                var a=p.recovery().association();
                suppliedRecovery=new OwnedFailedRenewalMethod.Recovery(r.id(),new Recovery(a.admissionId(),candidate.id(),a.billId(),a.allocation(),a.paymentIntent(),a.expectedRevision(),a.operationVersion(),a.associatedAt()),p.recovery().observationSequence());
            }
        }
        var locks=new ArrayList<UUID>();var authorityTargets=new ArrayList<SubscriptionCommandContext>();
        var trackedOperations=(SubscriptionOperationStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SubscriptionOperationStore.class},(proxy,method,args)->{
            if(method.getName().equals("lock"))locks.add((UUID)args[0]);
            try{return method.invoke(lifecycleOperations,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        });
        var trackedAuthority=(SubscriptionAuthorityPort)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SubscriptionAuthorityPort.class},(proxy,method,args)->{
            if(method.getName().equals("revalidate"))authorityTargets.add((SubscriptionCommandContext)args[0]);
            try{return method.invoke(authority,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        });
        var service=methodService(browserStore(),trackedOperations,transaction,trackedAuthority);
        var reservation=suppliedReservation;var recovery=suppliedRecovery;var context=suppliedContext;
        var before=financialState();var receipts=recoveryPreparationRows();int calls=http.calls.size();
        assertThatThrownBy(()->service.inspectBrowserRecoveryPreparation(reservation,p.completion(),recovery,context)).isInstanceOf(IllegalStateException.class);
        if(foreignOperation!=null)assertThat(locks).doesNotContain(foreignOperation);
        if(!locator.equals("recovery")){assertThat(locks).isEmpty();assertThat(authorityTargets).isEmpty();}
        for(int i=0;i<2;i++){
            locks.clear();var actual=service.inspectBrowserRecoveryPreparation(r,p.completion(),p.recovery(),pending.context());
            assertThat(actual.recovery()).isEqualTo(p.recovery());assertThat(locks).isSorted().contains(p.recovery().association().operationId());
        }
        assertThat(financialState()).isEqualTo(before);assertThat(recoveryPreparationRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void originalBrowserRecoveryPreparationDescribesTheSameReservedDebtWithoutAnyWriteOrSdkCall(boolean legacy) throws Exception {
        var p=recoveryPrepared(legacy,0);var pending=p.pending();var r=pending.reservation();
        var original=((SubscriptionActor.Customer)pending.context().actor()).session();
        assertThat(sessions.resolveRequired(pending.customerToken(),original.origin())).isEqualTo(original);
        var before=financialState();var receipts=recoveryPreparationRows();int calls=http.calls.size();
        var c=inspectRecovery(p);assertThat(inspectRecovery(p)).isEqualTo(c);
        assertThat(c.reservation()).isEqualTo(r);assertThat(c.completion()).isEqualTo(p.completion());assertThat(c.recovery()).isEqualTo(p.recovery());
        assertThat(c.enrollment()).isEqualTo(new OwnedMethodBrowser.Enrollment(original.sessionId().value(),original.origin(),original.expiresAt()));
        assertThat(c.account()).isEqualTo(billing);assertThat(c.subscription()).isEqualTo(current());assertThat(c.binding()).isEqualTo(r.binding());
        assertThat(c.billingSample()).isEqualTo(r.billingSample());assertThat(c.setupOperation().id()).isEqualTo(r.setupOperationId());
        assertThat(c.setupOperation().status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);assertThat(c.setupOperation().version()).isEqualTo(p.completion().setupFinalVersion());
        assertThat(c.recoveryOperation().id()).isEqualTo(p.recovery().association().operationId());assertThat(c.recoveryOperation().status()).isEqualTo(SubscriptionOperation.Status.RESERVED);
        assertThat(c.recoveryOperation().version()).isEqualTo(p.recovery().association().operationVersion());assertThat(c.toString()).isEqualTo("RecoveryPreparationContext[redacted]");
        assertThat(transaction.requiresNew(()->browserStore().requireBrowserRecoveryEnrollmentInTransaction(r,original,browserNow()))).isEqualTo(c.enrollment());
        // Earlier phases remain closed after completion; no fresh root/action can be minted.
        assertThatThrownBy(()->browserService(pending,browserStore()).get(new GetSubscriptionActionUseCase.Query(browserCommand(pending,null),current().id(),r.setupOperationId()))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->pending.service().inspectBrowserSetupPreparation(r,pending.context())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->pending.service().inspectBrowserMethodPreparation(r,pending.context())).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(before);assertThat(recoveryPreparationRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
        assertThat(count("stripe_billing_method_browser_roots")).isEqualTo(1);assertThat(count("stripe_billing_method_browser_consumptions")).isEqualTo(1);
        assertThat(count("stripe_billing_failed_method_completions")).isEqualTo(1);assertThat(count("stripe_billing_failed_method_recoveries")).isEqualTo(1);
        assertThat(count("payments")).isEqualTo(1);assertThat(count("stripe_billing_failed_method_publications")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"session","revoked","expired","scopes","missing-scope","site","customer","expiry-nanosecond","origin","key","ambient","future"})
    void recoveryPreparationRequiresTheExactLiveOriginalManagementAuthority(String mutation) throws Exception {
        var p=recoveryPrepared(false,0);var pending=p.pending();var context=pending.context();var original=((SubscriptionActor.Customer)context.actor()).session();var supplied=original;
        if(mutation.equals("session")){
            TestClock.set(jdbc.queryForObject("select issued_at from customer_sessions where id=?",java.sql.Timestamp.class,original.sessionId().value()).toInstant());
            var replacement=sessions.issue(site,customer,original.origin());supplied=sessions.resolveRequired(replacement.rawToken(),original.origin());TestClock.set(Instant.now());
            assertThat(supplied.sessionId()).isNotEqualTo(original.sessionId());assertThat(supplied.expiresAt()).isEqualTo(original.expiresAt());
            var live=supplied;assertThat(transaction.requiresNew(()->sessions.revalidate(live,site,live.origin(),CustomerSessionScope.SUBSCRIPTION_MANAGE))).isEqualTo(live);
        }
        if(mutation.equals("expiry-nanosecond"))supplied=new dev.genericpayments.platform.customers.application.model.CustomerSessionContext(original.sessionId(),original.siteId(),original.customerId(),original.origin(),original.scopes(),original.expiresAt().plusNanos(1));
        if(Set.of("missing-scope","site","customer").contains(mutation))supplied=new dev.genericpayments.platform.customers.application.model.CustomerSessionContext(original.sessionId(),mutation.equals("site")?new SiteId(UUID.randomUUID()):original.siteId(),mutation.equals("customer")?new CustomerId(UUID.randomUUID()):original.customerId(),original.origin(),mutation.equals("missing-scope")?Set.of(CustomerSessionScope.SUBSCRIPTION_READ):original.scopes(),original.expiresAt());
        if(mutation.equals("revoked"))jdbc.update("update customer_sessions set revoked_at=clock_timestamp(),revoked_reason='LOGOUT' where id=?",original.sessionId().value());
        if(mutation.equals("expired"))jdbc.update("update customer_sessions set expires_at=clock_timestamp() where id=?",original.sessionId().value());
        if(mutation.equals("scopes"))jdbc.update("update customer_sessions set scopes='[\"SUBSCRIPTION_MANAGE\",\"SUBSCRIPTION_READ\"]'::jsonb where id=?",original.sessionId().value());
        var changed=new SubscriptionCommandContext(new SubscriptionActor.Customer(supplied),site,customer,mutation.equals("origin")?"https://foreign.test":original.origin(),mutation.equals("key")?"foreign":context.idempotencyKey(),"recovery-inspect");
        var session=supplied;var before=financialState();var receipts=recoveryPreparationRows();int calls=http.calls.size();
        if(mutation.equals("ambient"))assertThatThrownBy(()->transaction.requiresNew(()->inspectRecovery(p))).isExactlyInstanceOf(IllegalStateException.class).hasMessage("Owned method transition requires an outer transaction boundary");
        else if(mutation.equals("future"))assertThatThrownBy(()->transaction.requiresNew(()->browserStore().requireBrowserRecoveryEnrollmentInTransaction(pending.reservation(),session,browserNow().plusSeconds(60)))).isExactlyInstanceOf(IllegalStateException.class).hasMessage("Owned method browser authority unavailable");
        else {
            assertThatThrownBy(()->pending.service().inspectBrowserRecoveryPreparation(pending.reservation(),p.completion(),p.recovery(),changed)).isInstanceOf(RuntimeException.class);
            if(!Set.of("key","origin").contains(mutation))assertThatThrownBy(()->transaction.requiresNew(()->browserStore().requireBrowserRecoveryEnrollmentInTransaction(pending.reservation(),session,browserNow()))).isExactlyInstanceOf(IllegalStateException.class).hasMessage("Owned method browser authority unavailable");
        }
        assertThat(financialState()).isEqualTo(before);assertThat(recoveryPreparationRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
    }
    @ParameterizedTest @ValueSource(strings={"stop","sequence","setup-version","setup-authority","recover-version","recover-status","bill-version","debt"})
    void recoveryPreparationRefusesInterveningDurableLineageOrDebtDriftWithoutAnotherMutation(String mutation) throws Exception {
        var p=recoveryPrepared(false,0);var pending=p.pending();var r=pending.reservation();
        assertThat(inspectRecovery(p).recovery()).isEqualTo(p.recovery());
        switch(mutation){
            case "stop"->service(store(),null).stop(r.admission(),Reason.AUTHORITY_DRIFT);
            case "sequence"->jdbc.update("update subscriptions set observation_sequence=observation_sequence+1 where id=?",current().id());
            case "setup-version"->jdbc.update("update subscription_operations set version=version+1,updated_at=clock_timestamp() where id=?",r.setupOperationId());
            case "setup-authority"->jdbc.update("update subscription_operations set authority_scope='foreign',version=version+1,updated_at=clock_timestamp() where id=?",r.setupOperationId());
            case "recover-version"->jdbc.update("update subscription_operations set version=version+1,updated_at=clock_timestamp() where id=?",p.recovery().association().operationId());
            case "recover-status"->jdbc.update("update subscription_operations set status='PROCESSING',version=version+1,updated_at=clock_timestamp() where id=?",p.recovery().association().operationId());
            case "bill-version"->jdbc.update("update subscription_billing_records set version=version+1 where id=?",r.failure().billId());
            default->jdbc.update("update subscriptions set grace_deadline=grace_deadline+interval '1 second' where id=?",current().id());
        }
        var before=financialState();var receipts=recoveryPreparationRows();int calls=http.calls.size();
        assertThatThrownBy(()->inspectRecovery(p)).isInstanceOf(RuntimeException.class);
        var session=((SubscriptionActor.Customer)pending.context().actor()).session();
        assertThatThrownBy(()->transaction.requiresNew(()->browserStore().requireBrowserRecoveryEnrollmentInTransaction(r,session,browserNow()))).isInstanceOf(IllegalStateException.class);
        assertThat(financialState()).isEqualTo(before);assertThat(recoveryPreparationRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
    }
    @Test void originalRecoveryAssociationPreventsReplacingItsInvoiceBeforeInspection() throws Exception {
        var p=recoveryPrepared(false,0);var before=financialState();var receipts=recoveryPreparationRows();int calls=http.calls.size();
        assertThatThrownBy(()->jdbc.update("update subscription_billing_records set provider_invoice_reference='in_foreign',version=version+1 where id=?",p.pending().failure().billId()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasStackTraceContaining("foreign key constraint");
        assertThat(inspectRecovery(p).recovery()).isEqualTo(p.recovery());assertThat(financialState()).isEqualTo(before);
        assertThat(recoveryPreparationRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
    }
    @ParameterizedTest @ValueSource(strings={"completion","completion-sequence","recovery","association","recovery-sequence","missing-completion","missing-recovery"})
    void recoveryPreparationAuthenticatesBothOriginalDurableReceipts(String mutation) throws Exception {
        var p=recoveryPrepared(false,0);var c=p.completion();var x=p.recovery();
        var completion=mutation.equals("missing-completion")?null:new OwnedFailedRenewalMethod.Completion(c.reservationId(),mutation.equals("completion")?UUID.randomUUID():c.claimId(),c.setupOperationId(),c.setupFinalVersion(),c.setupStepId(),c.setupStepVersion(),c.methodStepId(),c.methodStepVersion(),c.setupIntent(),c.method(),c.revision(),mutation.equals("completion-sequence")?c.observationSequence()+1:c.observationSequence(),c.completedAt());
        var a=x.association();var association=new Recovery(a.admissionId(),mutation.equals("association")?UUID.randomUUID():a.operationId(),a.billId(),a.allocation(),a.paymentIntent(),a.expectedRevision(),a.operationVersion(),a.associatedAt());
        var recovery=mutation.equals("missing-recovery")?null:new OwnedFailedRenewalMethod.Recovery(mutation.equals("recovery")?UUID.randomUUID():x.reservationId(),association,mutation.equals("recovery-sequence")?x.observationSequence()+1:x.observationSequence());
        var before=financialState();var receipts=recoveryPreparationRows();int calls=http.calls.size();
        assertThatThrownBy(()->p.pending().service().inspectBrowserRecoveryPreparation(p.pending().reservation(),completion,recovery,p.pending().context())).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(before);assertThat(recoveryPreparationRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);assertThat(inspectRecovery(p).completion()).isEqualTo(c);
    }
    @ParameterizedTest @ValueSource(strings={"root","selection","release","consumption","uncommitted-consumption","missing-enrollment","partial-enrollment"})
    void recoveryEnrollmentRefusesMissingPartialOrUncommittedOriginalBrowserEvidence(String mutation) throws Exception {
        var p=recoveryPrepared(false,0);var pending=p.pending();var r=pending.reservation();var session=((SubscriptionActor.Customer)pending.context().actor()).session();
        var before=financialState();var receipts=recoveryPreparationRows();int calls=http.calls.size();
        // Corruption controls are rollback-only. No successful completion, proof, ACK or permission is seeded.
        assertThatThrownBy(()->transaction.requiresNew(()->{
            if(mutation.endsWith("enrollment")){
                jdbc.execute("alter table stripe_billing_failed_method_reservations disable trigger failed_method_reservation_immutable");
                if(mutation.equals("partial-enrollment")){
                    jdbc.execute("alter table stripe_billing_failed_method_reservations drop constraint owned_method_browser_enrollment");
                    jdbc.update("update stripe_billing_failed_method_reservations set browser_origin=null where id=?",r.id());
                }else jdbc.update("update stripe_billing_failed_method_reservations set browser_session_id=null,browser_origin=null,browser_session_expires_at=null where id=?",r.id());
            }else{
                jdbc.execute("alter table stripe_billing_method_browser_consumptions disable trigger method_browser_consumption_immutable");
                if(mutation.equals("uncommitted-consumption"))jdbc.update("update stripe_billing_method_browser_consumptions set recorded_transaction=pg_current_xact_id() where root_id=(select id from stripe_billing_method_browser_roots where reservation_id=?)",r.id());
                else{
                    jdbc.update("delete from stripe_billing_method_browser_consumptions where root_id=(select id from stripe_billing_method_browser_roots where reservation_id=?)",r.id());
                    if(!mutation.equals("consumption")){
                        jdbc.execute("alter table stripe_billing_method_browser_releases disable trigger method_browser_release_immutable");
                        jdbc.update("delete from stripe_billing_method_browser_releases where root_id=(select id from stripe_billing_method_browser_roots where reservation_id=?)",r.id());
                    }
                    if(Set.of("root","selection").contains(mutation)){
                        jdbc.execute("alter table stripe_billing_method_browser_selections disable trigger method_browser_selection_immutable");
                        jdbc.update("delete from stripe_billing_method_browser_selections where root_id=(select id from stripe_billing_method_browser_roots where reservation_id=?)",r.id());
                    }
                    if(mutation.equals("root")){
                        jdbc.execute("alter table stripe_billing_method_browser_roots disable trigger method_browser_root_immutable");
                        jdbc.update("delete from stripe_billing_method_browser_roots where reservation_id=?",r.id());
                    }
                }
            }
            return browserStore().requireBrowserRecoveryEnrollmentInTransaction(r,session,browserNow());
        })).isExactlyInstanceOf(IllegalStateException.class).hasMessage("Owned method browser authority unavailable");
        assertThat(financialState()).isEqualTo(before);assertThat(recoveryPreparationRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);assertThat(inspectRecovery(p).recovery()).isEqualTo(p.recovery());
    }
    @Test void nonBrowserCompletionAndRecoveryNeverGainBrowserRecoveryEnrollment(){
        var prepared=preparedMethod();methodGraph(prepared);var completion=methodObserver(prepared.service()).observeMethod(prepared.reservation());var recovery=prepared.service().reserveRecovery(prepared.reservation(),completion);
        var context=managementContext("genuine-owned-method");var session=((SubscriptionActor.Customer)context.actor()).session();var before=financialState();var receipts=recoveryPreparationRows();int calls=http.calls.size();
        assertThatThrownBy(()->transaction.requiresNew(()->browserStore().requireBrowserRecoveryEnrollmentInTransaction(prepared.reservation(),session,browserNow()))).isExactlyInstanceOf(IllegalStateException.class).hasMessage("Owned method browser authority unavailable");
        assertThatThrownBy(()->prepared.service().inspectBrowserRecoveryPreparation(prepared.reservation(),completion,recovery,context)).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(before);assertThat(recoveryPreparationRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
    }
    @ParameterizedTest @ValueSource(strings={"expiry","revocation"})
    void recoveryEnrollmentRechecksFreshAuthorityAfterAnObservedBlockingOriginalOperationLock(String mutation) throws Exception {
        var p=recoveryPrepared(false,mutation.equals("expiry")?20:0);var pending=p.pending();var r=pending.reservation();var session=((SubscriptionActor.Customer)pending.context().actor()).session();
        var stale=browserNow();assertThat(stale).isBefore(session.expiresAt());
        assertThat(transaction.requiresNew(()->browserStore().requireBrowserRecoveryEnrollmentInTransaction(r,session,stale)).sessionId()).isEqualTo(session.sessionId().value());
        var before=financialState();var receipts=recoveryPreparationRows();int calls=http.calls.size();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var attempting=new CountDownLatch(1);
        var blockerPid=new java.util.concurrent.atomic.AtomicInteger();var workerPid=new java.util.concurrent.atomic.AtomicInteger();
        try(var workers=new BoundedWorkers()){
            var blocker=workers.submit(()->transaction.requiresNew(()->{
                lifecycleOperations.lock(r.setupOperationId());blockerPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));locked.countDown();
                try{if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("recovery enrollment wait timed out");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}return true;
            }));
            try{
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                var refused=workers.submit(()->{assertThatThrownBy(()->transaction.requiresNew(()->{
                    workerPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));attempting.countDown();
                    return browserStore().requireBrowserRecoveryEnrollmentInTransaction(r,session,stale);
                })).isExactlyInstanceOf(IllegalStateException.class).hasMessage("Owned method browser authority unavailable");return true;});
                assertThat(attempting.await(5,TimeUnit.SECONDS)).isTrue();boolean blocked=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline){
                    blocked=Boolean.TRUE.equals(jdbc.queryForObject("select ?=any(pg_blocking_pids(?))",Boolean.class,blockerPid.get(),workerPid.get()));
                    if(blocked)break;Thread.sleep(20);
                }
                assertThat(blocked).as("recovery validator actually waits for this operation-lock holder").isTrue();assertThat(refused.isDone()).isFalse();
                if(mutation.equals("revocation"))jdbc.update("update customer_sessions set revoked_at=clock_timestamp(),revoked_reason='LOGOUT' where id=?",session.sessionId().value());
                else{
                    var dbNow=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();assertThat(dbNow).isBefore(session.expiresAt());
                    var wait=Duration.between(dbNow,session.expiresAt()).plusMillis(100);assertThat(wait).isPositive().isLessThan(Duration.ofSeconds(21));Thread.sleep(wait);
                    assertThat(jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant()).isAfterOrEqualTo(session.expiresAt());
                    assertThat(jdbc.queryForObject("select expires_at from customer_sessions where id=?",java.sql.Timestamp.class,session.sessionId().value()).toInstant()).isEqualTo(session.expiresAt());
                }
                release.countDown();assertThat(blocker.get(10,TimeUnit.SECONDS)).isTrue();assertThat(refused.get(10,TimeUnit.SECONDS)).isTrue();
            }finally{release.countDown();}
        }
        assertThat(financialState()).isEqualTo(before);assertThat(recoveryPreparationRows()).isEqualTo(receipts);assertThat(http.calls).hasSize(calls);
    }

    @Test void explicitBrowserIssuerLostReleaseRollsBackSecretReceiptAndCannotReadAgain(){
        var p=pendingMethod(true);var real=browserStore();
        var broken=(OwnedFailedRenewalMethodStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{OwnedFailedRenewalMethodStore.class},(proxy,method,args)->{
            try{var result=method.invoke(real,args);if(method.getName().equals("finishBrowserSelectionInTransaction"))throw new IllegalStateException("after release write");return result;}
            catch(InvocationTargetException failure){throw failure.getCause();}
        });
        var service=browserService(p,broken);var before=financialState();var operation=lifecycleOperations.find(site,current().id(),p.reservation().setupOperationId()).orElseThrow();
        var action=service.get(new GetSubscriptionActionUseCase.Query(browserCommand(p,null),operation.subscriptionId(),operation.id()));queuePendingBrowser(p);int calls=http.calls.size();
        var scope=new SubscriptionConfirmationUseCase.Scope(browserCommand(p,"select"),operation.subscriptionId(),operation.id());
        assertThatThrownBy(()->service.select(scope,action.secret())).hasMessage("Owned method browser unavailable").hasNoCause();
        assertThat(count("stripe_billing_method_browser_selections")).isEqualTo(1);assertThat(count("stripe_billing_method_browser_releases")).isZero();
        assertThatThrownBy(()->service.select(scope,action.secret())).hasMessage("Owned method browser unavailable").hasNoCause();
        assertThatThrownBy(()->service.confirm(new ConfirmSubscriptionActionUseCase.Command(browserCommand(p,"confirm"),operation.subscriptionId(),operation.id(),action.secret()))).hasMessage("Owned method browser unavailable").hasNoCause();
        assertThat(count("stripe_billing_method_browser_consumptions")).isZero();assertThat(http.calls).hasSize(calls+1);assertThat(http.replies).isEmpty();assertThat(financialState()).isEqualTo(before);
        assertThat(lifecycleOperations.find(site,operation.subscriptionId(),operation.id())).contains(operation);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }

    @Test void browserMethodPreparationAuthenticatesCommittedBoundSetupWithoutMethodOrWrites(){
        var bound=boundBrowserMethod(0);var p=bound.pending();var r=p.reservation();var before=financialState();int calls=http.calls.size();
        var providerRows=rows("provider_operations");var proofRows=rows("stripe_subscription_setup_proofs");var receipts=authorityRows();
        var c=p.service().inspectBrowserMethodPreparation(r,p.context());
        assertThat(c.original().reservation()).isEqualTo(r);assertThat(c.original().operation()).isEqualTo(bound.operation());
        assertThat(c.original().account()).isEqualTo(billing);assertThat(c.original().subscription()).isEqualTo(current());
        assertThat(c.original().binding()).isEqualTo(r.binding());assertThat(c.original().billingSample()).isEqualTo(r.billingSample());
        assertThat(c.setupProof()).isEqualTo(bound.proof());assertThat(c.setupStep()).isEqualTo(p.setupStep());
        assertThat(c.setupProof().method()).isEqualTo("pm_saved");assertThat(c.setupStep().version()).isEqualTo(2);
        assertThat(c.toString()).isEqualTo("MethodPreparationContext[redacted]");assertThat(bound.toString()).isEqualTo("BoundMethod[redacted]");
        assertThat(operations.find(billing.id(),bound.operation().providerOperationKey()+":METHOD")).isEmpty();
        assertThat(transaction.requiresNew(()->browserStore().requireBrowserBoundSetupInTransaction(r,
            ((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)p.context().actor()).session(),browserNow())).setupProof()).isEqualTo(bound.proof());
        assertThat(rows("provider_operations")).isEqualTo(providerRows);assertThat(rows("stripe_subscription_setup_proofs")).isEqualTo(proofRows);
        assertThat(authorityRows()).isEqualTo(receipts);assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);
        assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(count("stripe_billing_failed_method_completions")).isZero();
    }
    @Test void browserMethodPreparationRemainsValidWhileItsOwnFirstMethodClaimExists(){
        var bound=boundBrowserMethod(0);var p=bound.pending();
        var update=operations.reserveOwnedFailedRenewalMethodStep(billing,p.reservation(),ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
        var claim=operations.claim(update.id(),UUID.randomUUID().toString(),Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        var before=financialState();var providerRows=rows("provider_operations");int calls=http.calls.size();
        assertThat(claim.status()).isEqualTo(ProviderOperation.Status.DISPATCHING);assertThat(claim.version()).isEqualTo(1);
        var c=p.service().inspectBrowserMethodPreparation(p.reservation(),p.context());
        assertThat(c.setupProof()).isEqualTo(bound.proof());assertThat(c.setupStep()).isEqualTo(p.setupStep());
        assertThat(operations.findById(claim.id())).contains(claim);assertThat(rows("provider_operations")).isEqualTo(providerRows);
        assertThat(lifecycleOperations.find(site,current().id(),bound.operation().id())).contains(bound.operation());
        assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void browserMethodPreparationRefusesMissingSetupProofWithoutStopping(){
        var reserved=reservedMethod(true,0);var before=financialState();int calls=http.calls.size();
        assertThatThrownBy(()->reserved.service().inspectBrowserMethodPreparation(reserved.reservation(),reserved.context())).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void browserMethodPreparationCannotUseReleasedOrUncommittedConsumptionToBind(){
        var p=pendingMethod(true);var root=browserRoot(p);releaseBrowser(p,root,selectBrowser(p,root));
        var proof=methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId()).orElseThrow();var before=financialState();int calls=http.calls.size();
        assertThatThrownBy(()->p.service().inspectBrowserMethodPreparation(p.reservation(),p.context())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->methodBillingProofs.bindMethod(proof,"pm_saved")).hasStackTraceContaining("Owned method browser consumption required");
        assertThatThrownBy(()->browserPhase(p,pending->{
            assertThat(browserStore().consumeBrowserFirstInTransaction(pending,root,"c".repeat(64),"d".repeat(64),browserNow())).isTrue();
            return jdbc.update("update stripe_subscription_setup_proofs set method_reference='pm_saved' where operation_id=?",proof.operation());
        })).hasStackTraceContaining("Owned method browser consumption required");
        assertThat(count("stripe_billing_method_browser_consumptions")).isZero();
        assertThat(consumeBrowser(p,root)).isTrue();
        // Committed permission alone is still unbound. No guard is disabled to fabricate a bound predecessor.
        assertThatThrownBy(()->p.service().inspectBrowserMethodPreparation(p.reservation(),p.context())).isInstanceOf(RuntimeException.class);
        assertThat(methodBillingProofs.setup(billing.id(),proof.operation())).contains(proof);assertThat(proof.method()).isNull();
        assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"fingerprint","key","credentials","external","version","future-first","old-first","after-root"})
    void browserMethodPreparationRefusesCorruptedOriginalSetupAckAndRollsBackOnlyTheCorruption(String mutation){
        var bound=boundBrowserMethod(0);var p=bound.pending();var before=financialState();var providerRows=rows("provider_operations");int calls=http.calls.size();
        var session=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)p.context().actor()).session();
        assertThatThrownBy(()->transaction.requiresNew(()->{
            // clock_timestamp is volatile; use one captured timestamp for the exact immutable 23h window.
            if(mutation.equals("future-first"))jdbc.update("update provider_operations set first_dispatched_at=?,retry_before=? where id=?",
                java.sql.Timestamp.from(session.expiresAt().plusSeconds(3600)),java.sql.Timestamp.from(session.expiresAt().plusSeconds(24*3600)),p.setupStep().id());
            else if(mutation.equals("after-root")){
                var root=browserStore().browserRootInTransaction(p.reservation().id()).orElseThrow();var shifted=root.issuedAt().plusNanos(1_000);
                assertThat(browserNow()).isAfter(shifted);
                jdbc.update("update provider_operations set first_dispatched_at=?,retry_before=? where id=?",
                    java.sql.Timestamp.from(shifted),java.sql.Timestamp.from(shifted.plusSeconds(23*3600)),p.setupStep().id());
            }
            else {
                String change=switch(mutation){
                    case "fingerprint"->"fingerprint='foreign'";case "key"->"operation_key=operation_key||':FOREIGN'";
                    case "credentials"->"credentials_ref='foreign'";case "external"->"external_account_id='acct_foreign'";case "version"->"version=3";
                    default->"first_dispatched_at=first_dispatched_at-interval '1 day',retry_before=retry_before-interval '1 day'";
                };
                jdbc.update("update provider_operations set "+change+" where id=?",p.setupStep().id());
            }
            return browserStore().requireBrowserBoundSetupInTransaction(p.reservation(),session,browserNow());
        })).isInstanceOf(IllegalStateException.class).hasMessage("Owned method transition authority unavailable");
        assertThat(rows("provider_operations")).isEqualTo(providerRows);assertThat(p.service().inspectBrowserMethodPreparation(p.reservation(),p.context()).setupProof()).isEqualTo(bound.proof());
        assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"method","reference","step","lease","window"})
    void boundProofAndAckShapeCorruptionRemainBlockedByTheExistingDatabaseGuards(String mutation){
        var bound=boundBrowserMethod(0);var p=bound.pending();var proofs=rows("stripe_subscription_setup_proofs");var steps=rows("provider_operations");
        var before=financialState();int calls=http.calls.size();
        assertThatThrownBy(()->transaction.requiresNew(()->{
            if(mutation.equals("method"))return jdbc.update("update stripe_subscription_setup_proofs set method_reference='pm_foreign' where operation_id=?",bound.operation().id());
            if(mutation.equals("reference"))return jdbc.update("update stripe_subscription_setup_proofs set setup_reference='seti_foreign' where operation_id=?",bound.operation().id());
            if(mutation.equals("step"))return jdbc.update("update stripe_subscription_setup_proofs set step_id=? where operation_id=?",UUID.randomUUID(),bound.operation().id());
            if(mutation.equals("lease"))return jdbc.update("update provider_operations set lease_owner='foreign',lease_expires_at=clock_timestamp()+interval '1 minute' where id=?",p.setupStep().id());
            return jdbc.update("update provider_operations set retry_before=retry_before+interval '1 second' where id=?",p.setupStep().id());
        })).isInstanceOf(RuntimeException.class);
        assertThat(rows("stripe_subscription_setup_proofs")).isEqualTo(proofs);assertThat(rows("provider_operations")).isEqualTo(steps);
        assertThat(p.service().inspectBrowserMethodPreparation(p.reservation(),p.context()).setupProof()).isEqualTo(bound.proof());
        assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"session","revoked","expired","scope","expiry-nanosecond","key","ambient"})
    void browserMethodPreparationRequiresTheExactLiveOriginalSessionAfterBinding(String mutation){
        var bound=boundBrowserMethod(0);var p=bound.pending();var context=p.context();
        var original=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)context.actor()).session();var supplied=original;
        if(mutation.equals("session")){
            TestClock.set(jdbc.queryForObject("select issued_at from customer_sessions where id=?",java.sql.Timestamp.class,original.sessionId().value()).toInstant());
            var second=sessions.issue(site,customer,original.origin());supplied=sessions.resolveRequired(second.rawToken(),original.origin());TestClock.set(Instant.now());
        }
        if(mutation.equals("expiry-nanosecond"))supplied=new dev.genericpayments.platform.customers.application.model.CustomerSessionContext(original.sessionId(),original.siteId(),original.customerId(),original.origin(),original.scopes(),original.expiresAt().plusNanos(1));
        if(mutation.equals("revoked"))jdbc.update("update customer_sessions set revoked_at=clock_timestamp(),revoked_reason='LOGOUT' where id=?",original.sessionId().value());
        if(mutation.equals("expired"))jdbc.update("update customer_sessions set expires_at=clock_timestamp() where id=?",original.sessionId().value());
        if(mutation.equals("scope"))jdbc.update("update customer_sessions set scopes='[\"SUBSCRIPTION_MANAGE\",\"SUBSCRIPTION_READ\"]'::jsonb where id=?",original.sessionId().value());
        var suppliedContext=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer(supplied),site,customer,original.origin(),mutation.equals("key")?"foreign":context.idempotencyKey(),"changed");
        var session=supplied;var before=financialState();var steps=rows("provider_operations");int calls=http.calls.size();
        if(mutation.equals("ambient"))assertThatThrownBy(()->transaction.requiresNew(()->p.service().inspectBrowserMethodPreparation(p.reservation(),suppliedContext))).isInstanceOf(IllegalStateException.class).hasMessage("Owned method transition requires an outer transaction boundary");
        else {
            assertThatThrownBy(()->p.service().inspectBrowserMethodPreparation(p.reservation(),suppliedContext)).isInstanceOf(RuntimeException.class);
            if(!mutation.equals("key"))assertThatThrownBy(()->transaction.requiresNew(()->browserStore().requireBrowserBoundSetupInTransaction(p.reservation(),session,browserNow()))).isInstanceOf(RuntimeException.class);
        }
        assertThat(rows("provider_operations")).isEqualTo(steps);assertThat(methodBillingProofs.setup(billing.id(),bound.operation().id())).contains(bound.proof());
        assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void browserMethodPreparationRechecksFreshDatabaseExpiryAfterItsOperationLock() throws Exception {
        var bound=boundBrowserMethod(20);var p=bound.pending();var session=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)p.context().actor()).session();
        var stale=browserNow();var before=financialState();int calls=http.calls.size();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var attempting=new CountDownLatch(1);
        var blockerPid=new java.util.concurrent.atomic.AtomicInteger();var workerPid=new java.util.concurrent.atomic.AtomicInteger();
        assertThat(stale).isBefore(session.expiresAt());
        assertThat(transaction.requiresNew(()->browserStore().requireBrowserBoundSetupInTransaction(p.reservation(),session,stale)).setupProof()).isEqualTo(bound.proof());
        try(var workers=new BoundedWorkers()){
            var blocker=workers.submit(()->transaction.requiresNew(()->{
                lifecycleOperations.lock(p.reservation().setupOperationId());blockerPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));locked.countDown();
                try{if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("expiry lock wait timed out");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                return true;
            }));
            try {
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                var refused=workers.submit(()->{assertThatThrownBy(()->transaction.requiresNew(()->{
                    workerPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));attempting.countDown();
                    return browserStore().requireBrowserBoundSetupInTransaction(p.reservation(),session,stale);
                }))
                    .isExactlyInstanceOf(IllegalStateException.class).hasMessage("Owned method browser authority unavailable");return true;});
                assertThat(attempting.await(5,TimeUnit.SECONDS)).isTrue();assertThat(blockerPid.get()).isPositive();assertThat(workerPid.get()).isPositive();
                boolean databaseBlocked=false;long lockDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<lockDeadline){
                    databaseBlocked=Boolean.TRUE.equals(jdbc.queryForObject("select ? = any(pg_blocking_pids(?))",Boolean.class,blockerPid.get(),workerPid.get()));
                    if(databaseBlocked)break;Thread.sleep(20);
                }
                assertThat(databaseBlocked).as("worker waits for this fixture's operation-lock holder").isTrue();
                assertThatThrownBy(()->refused.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                var databaseNow=jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant();
                assertThat(databaseNow).isBefore(session.expiresAt());
                var wait=Duration.between(databaseNow,session.expiresAt()).plusMillis(100);if(!wait.isNegative())Thread.sleep(wait);
                assertThat(jdbc.queryForObject("select clock_timestamp()",java.sql.Timestamp.class).toInstant()).isAfterOrEqualTo(session.expiresAt());
                release.countDown();assertThat(blocker.get(10,TimeUnit.SECONDS)).isTrue();assertThat(refused.get(10,TimeUnit.SECONDS)).isTrue();
            }finally{release.countDown();}
        }
        assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
        assertThat(methodBillingProofs.setup(billing.id(),bound.operation().id())).contains(bound.proof());
    }
    @Test void browserSetupPreparationAuthenticatesRealJdbcEnrollmentBeforeAnySetupAckOrProof(){
        var p=reservedMethod(true,0);var r=p.reservation();var before=financialState();var original=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)p.context().actor()).session();
        var operation=lifecycleOperations.find(site,current().id(),r.setupOperationId()).orElseThrow();int calls=http.calls.size();
        var reservations=rows("stripe_billing_failed_method_reservations");var events=rows("subscription_events");
        var prepared=p.service().inspectBrowserSetupPreparation(r,p.context());
        assertThat(prepared.reservation()).isEqualTo(r);assertThat(prepared.enrollment()).isEqualTo(new OwnedMethodBrowser.Enrollment(original.sessionId().value(),original.origin(),original.expiresAt()));
        assertThat(prepared.account()).isEqualTo(billing);assertThat(prepared.subscription()).isEqualTo(current());assertThat(prepared.operation()).isEqualTo(operation);
        assertThat(prepared.binding()).isEqualTo(r.binding());assertThat(prepared.billingSample()).isEqualTo(r.billingSample());
        assertThat(prepared.toString()).isEqualTo("SetupPreparationContext[redacted]");assertThat(p.toString()).isEqualTo("ReservedMethod[redacted]");
        assertThat(methodBillingProofs.setup(billing.id(),operation.id())).isEmpty();assertThat(jdbc.queryForObject("select count(*) from provider_operations where resource_id=?",Long.class,operation.id())).isZero();
        assertThat(operation.status()).isEqualTo(SubscriptionOperation.Status.RESERVED);assertThat(operation.version()).isZero();
        assertThat(financialState()).isEqualTo(before);assertThat(rows("stripe_billing_failed_method_reservations")).isEqualTo(reservations);assertThat(rows("subscription_events")).isEqualTo(events);
        assertThat(count("stripe_billing_method_browser_roots")).isZero();assertThat(count("stripe_billing_failed_method_completions")).isZero();assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(http.calls).hasSize(calls);
        assertThatThrownBy(()->OwnedMethodBrowserTestAccess.phase(p.service(),r,p.context(),value->value)).isInstanceOf(RuntimeException.class);
        assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
    }
    @Test void browserSetupEnrollmentRejectsOneNanosecondOriginalExpiryDriftDirectlyThroughJdbc(){
        var p=reservedMethod(true,0);var s=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)p.context().actor()).session();
        var before=financialState();int calls=http.calls.size();var store=browserStore();
        var original=transaction.requiresNew(()->store.requireBrowserSetupEnrollmentInTransaction(p.reservation(),s,browserNow()));
        assertThat(original.sessionExpiresAt()).isEqualTo(s.expiresAt());
        var changed=new dev.genericpayments.platform.customers.application.model.CustomerSessionContext(s.sessionId(),s.siteId(),s.customerId(),s.origin(),s.scopes(),s.expiresAt().plusNanos(1));
        assertThatThrownBy(()->transaction.requiresNew(()->store.requireBrowserSetupEnrollmentInTransaction(p.reservation(),changed,browserNow()))).isInstanceOf(IllegalStateException.class);
        assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();
        assertThat(count("stripe_billing_method_browser_roots")).isZero();assertThat(methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId())).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"session","non-browser","revoked","expired","scopes","future","key","origin"})
    void browserSetupPreparationRefusesReplacedOrDeadOriginalAuthorityWithoutStop(String mutation){
        var p=reservedMethod(!mutation.equals("non-browser"),0);var s=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)p.context().actor()).session();
        var context=p.context();var supplied=s;
        if(mutation.equals("session")){
            TestClock.set(jdbc.queryForObject("select issued_at from customer_sessions where id=?",java.sql.Timestamp.class,s.sessionId().value()).toInstant());
            var second=sessions.issue(site,customer,s.origin());supplied=sessions.resolveRequired(second.rawToken(),s.origin());TestClock.set(Instant.now());
            assertThat(supplied.sessionId()).isNotEqualTo(s.sessionId());assertThat(supplied.expiresAt()).isEqualTo(s.expiresAt());
            var replacement=supplied;assertThat(transaction.requiresNew(()->sessions.revalidate(replacement,site,replacement.origin(),dev.genericpayments.platform.customers.domain.CustomerSessionScope.SUBSCRIPTION_MANAGE))).isEqualTo(replacement);
            context=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer(supplied),site,customer,s.origin(),context.idempotencyKey(),"replacement");
        }
        if(mutation.equals("revoked"))jdbc.update("update customer_sessions set revoked_at=clock_timestamp(),revoked_reason='LOGOUT' where id=?",s.sessionId().value());
        if(mutation.equals("expired"))jdbc.update("update customer_sessions set expires_at=clock_timestamp() where id=?",s.sessionId().value());
        if(mutation.equals("scopes"))jdbc.update("update customer_sessions set scopes='[\"SUBSCRIPTION_MANAGE\",\"SUBSCRIPTION_READ\"]'::jsonb where id=?",s.sessionId().value());
        if(mutation.equals("key")||mutation.equals("origin"))context=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(context.actor(),site,customer,mutation.equals("origin")?"https://foreign.test":context.origin(),mutation.equals("key")?"changed":context.idempotencyKey(),"changed");
        var before=financialState();int calls=http.calls.size();var originalContext=context;var session=supplied;
        if(mutation.equals("future"))assertThatThrownBy(()->transaction.requiresNew(()->browserStore().requireBrowserSetupEnrollmentInTransaction(p.reservation(),session,browserNow().plusSeconds(60)))).isInstanceOf(RuntimeException.class);
        else {
            assertThatThrownBy(()->p.service().inspectBrowserSetupPreparation(p.reservation(),originalContext)).isInstanceOf(RuntimeException.class);
            // The JDBC check itself also refuses valid second sessions, revoked/expired rows and exact-scope drift.
            if(Set.of("session","non-browser","revoked","expired","scopes").contains(mutation))assertThatThrownBy(()->transaction.requiresNew(()->browserStore().requireBrowserSetupEnrollmentInTransaction(p.reservation(),session,browserNow()))).isInstanceOf(RuntimeException.class);
        }
        assertThat(financialState()).isEqualTo(before);assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(count("stripe_billing_method_browser_roots")).isZero();
        assertThat(methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId())).isEmpty();
    }
    @Test void browserSetupPreparationRechecksDatabaseExpiryAfterBlockingOperationLock() throws Exception {
        var p=reservedMethod(true,5);var session=((dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)p.context().actor()).session();var stale=browserNow();var before=financialState();
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var attempting=new CountDownLatch(1);
        try(var workers=new BoundedWorkers()){
            var blocker=workers.submit(()->transaction.requiresNew(()->{lifecycleOperations.lock(p.reservation().setupOperationId());locked.countDown();awaitRelease(release);return true;}));
            try{
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                var refused=workers.submit(()->{attempting.countDown();assertThatThrownBy(()->transaction.requiresNew(()->browserStore().requireBrowserSetupEnrollmentInTransaction(p.reservation(),session,stale))).isInstanceOf(RuntimeException.class);return true;});
                assertThat(attempting.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->refused.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                var wait=Duration.between(Instant.now(),session.expiresAt()).plusMillis(100);if(!wait.isNegative())Thread.sleep(wait);
                release.countDown();assertThat(blocker.get(10,TimeUnit.SECONDS)).isTrue();assertThat(refused.get(10,TimeUnit.SECONDS)).isTrue();
            }finally{release.countDown();}
        }
        assertThat(financialState()).isEqualTo(before);assertThat(count("stripe_billing_failed_renewal_stops")).isZero();assertThat(count("stripe_billing_method_browser_roots")).isZero();
        assertThat(methodBillingProofs.setup(billing.id(),p.reservation().setupOperationId())).isEmpty();
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
