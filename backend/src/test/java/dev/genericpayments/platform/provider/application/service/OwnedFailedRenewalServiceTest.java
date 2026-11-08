package dev.genericpayments.platform.provider.application.service;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalStore;
import dev.genericpayments.platform.subscriptions.application.service.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.domain.*;
import java.time.*;
import java.util.*;
import java.lang.reflect.Proxy;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Real orchestration + real FactService with inert memory ports and explicit rollback boundary; no runtime IO. */
class OwnedFailedRenewalServiceTest {
    static final Instant UTC=Instant.parse("2026-11-01T12:00:00Z");
    @Test void admissionAndReadUseExactIsolatedOwnerAndUtcWindow() {
        var h=new Harness();assertThat(h.service.admit(h.upstream,h.ready,h.boundary)).isEqualTo(h.admission);
        var read=h.service.beginFailedRead(h.admission,UUID.randomUUID());
        assertThat(read.startedAt()).isEqualTo(UTC);assertThat(read.deadlineAt()).isEqualTo(UTC.plusSeconds(30));
        assertThat(read.fence()).isEqualTo(new Fence(2,null,null,null,null,0L));assertThat(h.cash).isEmpty();
        assertThat(h.trace).containsSubsequence("operations","lifecycle","account","subscription","bill");
    }
    @Test void inspectedContextIsAuthenticatedAndPublicationPinsFactSample() {
        var h=new Harness(true);var read=h.service.beginFailedRead(h.admission,UUID.randomUUID());
        var context=h.service.inspectFailedRead(h.admission,read);
        assertThat(context.account()).isEqualTo(h.account);assertThat(context.subscription()).isEqualTo(h.s);
        assertThat(context.binding()).isEqualTo(h.binding);assertThat(context.billingAt()).isEqualTo(Instant.ofEpochSecond(4700));
        assertThat(h.effects).isZero();assertThat(h.stopped).isFalse();
        assertThat(h.trace).containsSubsequence("begin","operations","lifecycle","account","subscription","bill","commit");
        h.factTimeDrift=true;
        assertThatThrownBy(()->h.service.publishFailure(h.admission,read,new FailedEvidence(h.snapshot(false),"inpay_renewal","pi_renewal",h.utc),context)).hasMessage("BILLING_TIME_CHANGED");
        assertThat(h.effects).isZero();assertThat(h.s.debt()).isNull();assertThat(h.failure).isNull();assertThat(h.stopped).isTrue();
    }
    @Test void inspectionRejectsDefaultConstructorAndChangedBindingWithoutStopping() {
        var legacy=new Harness();var read=legacy.service.beginFailedRead(legacy.admission,UUID.randomUUID());
        assertThatThrownBy(()->legacy.service.inspectFailedRead(legacy.admission,read)).isInstanceOf(IllegalStateException.class);
        assertThat(legacy.stopped).isFalse();
        var h=new Harness(true);var r=h.service.beginFailedRead(h.admission,UUID.randomUUID());var context=h.service.inspectFailedRead(h.admission,r);
        h.binding=new dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding(h.binding.id(),h.binding.accountId(),h.binding.siteId(),h.binding.customerId(),h.binding.providerReference(),h.binding.creationOperationId(),1);
        assertThatThrownBy(()->h.service.publishFailure(h.admission,r,new FailedEvidence(h.snapshot(false),"inpay_renewal","pi_renewal",h.utc),context)).isInstanceOf(IllegalStateException.class);
        assertThat(h.effects).isZero();assertThat(h.stopped).isTrue();
    }
    @Test void inspectionRejectsInvalidClaimsAndTimeWithoutEffectsOrStop() {
        for(var bad:List.of("expired","future","foreign","stale","time","ambient")) {
            var h=new Harness(true);var original=h.service.beginFailedRead(h.admission,UUID.randomUUID());var read=original;
            if(bad.equals("expired"))h.utc=original.deadlineAt();
            if(bad.equals("future"))h.utc=original.startedAt().minusSeconds(1);
            if(bad.equals("foreign"))read=new ReadClaim(original.id(),UUID.randomUUID(),original.phase(),original.invocation(),original.startedAt(),original.deadlineAt(),original.fence());
            if(bad.equals("stale"))h.sequence++;
            if(bad.equals("time"))h.billingSecond=4701;
            if(bad.equals("ambient"))h.active=true;
            var supplied=read;assertThatThrownBy(()->h.service.inspectFailedRead(h.admission,supplied)).isInstanceOf(RuntimeException.class);
            assertThat(h.effects).isZero();assertThat(h.stopped).isFalse();
        }
    }
    @Test void contextPublicationSucceedsAndReceiptFailureRollsBackBeforeOneStop() {
        var h=new Harness(true);var read=h.service.beginFailedRead(h.admission,UUID.randomUUID());var context=h.service.inspectFailedRead(h.admission,read);
        var result=h.service.publishFailure(h.admission,read,new FailedEvidence(h.snapshot(false),"inpay_renewal","pi_renewal",h.utc),context);
        assertThat(result).isEqualTo(h.failure);assertThat(h.s.debt()).isNotNull();assertThat(h.cash).containsOnly("pi_initial");
        var broken=new Harness(true);var r=broken.service.beginFailedRead(broken.admission,UUID.randomUUID());var c=broken.service.inspectFailedRead(broken.admission,r);broken.failReceipt=true;
        assertThatThrownBy(()->broken.service.publishFailure(broken.admission,r,new FailedEvidence(broken.snapshot(false),"inpay_renewal","pi_renewal",broken.utc),c)).hasMessage("receipt failed");
        assertThat(broken.effects).isZero();assertThat(broken.failure).isNull();assertThat(broken.s.debt()).isNull();assertThat(broken.trace).containsSubsequence("rollback","begin","stop","commit");assertThat(broken.trace.stream().filter("stop"::equals)).hasSize(1);
    }
    @Test void factSampleModeChangeRejectsBeforeEffects() {
        var h=new Harness(true);var read=h.service.beginFailedRead(h.admission,UUID.randomUUID());var context=h.service.inspectFailedRead(h.admission,read);h.factModeDrift=true;
        assertThatThrownBy(()->h.service.publishFailure(h.admission,read,new FailedEvidence(h.snapshot(false),"inpay_renewal","pi_renewal",h.utc),context)).hasMessage("BILLING_TIME_CHANGED");
        assertThat(h.effects).isZero();assertThat(h.sequence).isZero();assertThat(h.stopped).isTrue();
    }
    @Test void inspectionRejectsInvalidBindingIdentityAndPublicationRejectsForgedContext() {
        for(var bad:List.of("id","owner","creation","reference")) {
            var h=new Harness(true);var r=h.service.beginFailedRead(h.admission,UUID.randomUUID());var b=h.binding;
            h.binding=new dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding(bad.equals("id")?UUID.randomUUID():b.id(),bad.equals("owner")?new PaymentAccountId(UUID.randomUUID()):b.accountId(),b.siteId(),b.customerId(),bad.equals("reference")?"cus_foreign":b.providerReference(),bad.equals("creation")?UUID.randomUUID():b.creationOperationId(),b.version());
            assertThatThrownBy(()->h.service.inspectFailedRead(h.admission,r)).isInstanceOf(IllegalStateException.class);assertThat(h.stopped).isFalse();assertThat(h.effects).isZero();
        }
        var h=new Harness(true);var r=h.service.beginFailedRead(h.admission,UUID.randomUUID());var c=h.service.inspectFailedRead(h.admission,r);
        var forged=new OwnedFailedRenewalService.FailedReadContext(c.account(),c.subscription(),c.binding(),c.billingAt().plusSeconds(1));
        assertThatThrownBy(()->h.service.publishFailure(h.admission,r,new FailedEvidence(h.snapshot(false),"inpay_renewal","pi_renewal",h.utc),forged)).isInstanceOf(IllegalStateException.class);
        assertThat(h.effects).isZero();assertThat(h.stopped).isTrue();
    }
    @Test void failureUsesRealFactsAndFixedGraceWithReceiptAfterProjection() {
        var h=new Harness();h.fail();
        assertThat(h.s.billingStatus()).isEqualTo(Subscription.Status.PAST_DUE);assertThat(h.failure.billId()).isEqualTo(h.s.debt().billingRecordId());
        assertThat(h.failure.graceDeadline()).isEqualTo(h.boundary.period().start().plus(Duration.ofDays(3)));
        assertThat(h.records).hasSize(2);assertThat(h.cash).containsExactly("pi_initial");assertThat(h.events).contains(SubscriptionEvent.Type.RENEWAL_FAILED);
        assertThat(h.callbacks).isEqualTo(1);assertThat(h.trace).containsSubsequence("facts","failureReceipt");
    }
    @Test void recoveredPublicationPaysSameBillAndPiAndCompletesReservedOperationThenReplaysReadOnly() {
        var h=new Harness();h.fail();var failure=h.failure;
        var recovery=h.service.reserveExistingRecovery(h.admission);assertThat(recovery.billId()).isEqualTo(failure.billId());assertThat(h.sequence).isEqualTo(failure.observationSequence());
        assertThat(h.trace).containsSubsequence("claim","operations","lifecycle","account","subscription","bill","associate");
        var operation=h.ops.get(recovery.operationId());assertThat(operation.authorityScope()).isEqualTo(OwnedFailedRenewal.recoveryAuthority(h.admission));assertThat(operation.fingerprint()).startsWith("v1.recover."+failure.billId()+".");
        var read=h.service.beginRecoveredRead(h.admission,UUID.randomUUID());
        var publication=h.service.publishRecovery(h.admission,read,new RecoveredEvidence(h.snapshot(true),UTC));
        assertThat(publication.billId()).isEqualTo(failure.billId());assertThat(h.s.debt()).isNull();assertThat(h.s.billingStatus()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(h.ops.get(recovery.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(h.cash).containsExactly("pi_initial","pi_renewal");assertThat(h.events).contains(SubscriptionEvent.Type.PERIOD_PAID);assertThat(h.records).hasSize(2);
        var effects=h.effects;assertThat(h.service.replayPublication(h.admission)).isEqualTo(publication);assertThat(h.effects).isEqualTo(effects);
    }
    @Test void ordinaryAndForeignOwnerSamplesCannotAdmitOrRead() {
        for(boolean ordinary:List.of(true,false)){var h=new Harness();if(ordinary)h.isolated=false;else h.foreign=true;
            assertThatThrownBy(()->h.service.beginFailedRead(h.admission,UUID.randomUUID())).isInstanceOf(IllegalStateException.class);assertThat(h.reads).isEmpty();assertThat(h.effects).isZero();}
    }
    @Test void expiredFutureStaleAndForeignReadCannotApplyFacts() {
        for(var bad:List.of("expired","future","stale","foreign")){var h=new Harness();var read=h.service.beginFailedRead(h.admission,UUID.randomUUID());
            var accepted=UTC;if(bad.equals("expired")){h.utc=UTC.plusSeconds(30);accepted=h.utc;}if(bad.equals("future"))accepted=UTC.plusSeconds(1);if(bad.equals("stale"))h.sequence++;
            if(bad.equals("foreign"))read=new ReadClaim(read.id(),UUID.randomUUID(),read.phase(),read.invocation(),read.startedAt(),read.deadlineAt(),read.fence());
            var r=read;var evidence=new FailedEvidence(h.snapshot(false),"inpay_renewal","pi_renewal",accepted);
            assertThatThrownBy(()->h.service.publishFailure(h.admission,r,evidence)).isInstanceOf(RuntimeException.class);assertThat(h.effects).isZero();assertThat(h.stopped).isTrue();}
    }
    @Test void duplicateClaimsAndDuplicateReservationCannotCreateSuccessorAuthority() {
        var h=new Harness();h.service.beginFailedRead(h.admission,UUID.randomUUID());assertThatThrownBy(()->h.service.beginFailedRead(h.admission,UUID.randomUUID())).isInstanceOf(RuntimeException.class);assertThat(h.reads).hasSize(1);
        var recovery=new Harness();recovery.fail();recovery.service.reserveExistingRecovery(recovery.admission);var before=recovery.ops.size();assertThatThrownBy(()->recovery.service.reserveExistingRecovery(recovery.admission)).isInstanceOf(RuntimeException.class);assertThat(recovery.ops).hasSize(before);
    }
    @Test void freshOperationIdentitySurvivesDatabaseMicrosecondPrecision() {
        var h=new Harness();h.fail();h.utc=UTC.plusNanos(123456789);h.normalizeClaim=true;
        var recovery=h.service.reserveExistingRecovery(h.admission);
        assertThat(h.ops.get(recovery.operationId()).createdAt()).isEqualTo(h.utc.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
    @Test void nonOwnerClaimAndUnknownOperationAreNeverAdopted() {
        var h=new Harness();h.fail();h.claimOwner=false;assertThatThrownBy(()->h.service.reserveExistingRecovery(h.admission)).isInstanceOf(RuntimeException.class);assertThat(h.recovery).isNull();assertThat(h.stopped).isTrue();assertThat(h.ops).hasSize(1);
        var unknown=new Harness();unknown.fail();var r=unknown.service.reserveExistingRecovery(unknown.admission);var o=unknown.ops.get(r.operationId());unknown.ops.put(o.id(),new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),SubscriptionOperation.Status.UNKNOWN,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),null,o.createdAt(),o.updatedAt(),o.version()));
        assertThatThrownBy(()->unknown.service.beginRecoveredRead(unknown.admission,UUID.randomUUID())).isInstanceOf(RuntimeException.class);assertThat(unknown.reads).doesNotContainKey(Phase.RECOVERED);
    }
    @Test void foreignPiAndUnsupportedSnapshotFailBeforeFactEffects() {
        for(var bad:List.of("pi","marker","invoice")){var h=new Harness();h.fail();h.service.reserveExistingRecovery(h.admission);var read=h.service.beginRecoveredRead(h.admission,UUID.randomUUID());var v=h.snapshot(true);var invoices=new ArrayList<>(v.invoices());var i=invoices.get(1);
            if(bad.equals("pi"))invoices.set(1,new Invoice(i.reference(),i.kind(),i.price(),null,i.period(),i.amountDueMinor(),i.currency(),i.settlement(),List.of(new CashObject("pi_foreign",1000,"EUR",dev.genericpayments.platform.payments.domain.PaymentStatus.SUCCEEDED,UTC))));
            if(bad.equals("invoice"))invoices.set(1,new Invoice("in_foreign",i.kind(),i.price(),null,i.period(),i.amountDueMinor(),i.currency(),i.settlement(),i.cashObjects()));
            var snapshot=new Snapshot(v.subscriptionReference(),v.status(),v.billingPrice(),v.period(),null,null,invoices,bad.equals("marker")?"foreign":v.operationKey(),true,0,UTC,v.operationObservation());var before=h.effects;
            assertThatThrownBy(()->h.service.publishRecovery(h.admission,read,new RecoveredEvidence(snapshot,UTC))).isInstanceOf(RuntimeException.class);assertThat(h.effects).isEqualTo(before);assertThat(h.publication).isNull();}
    }
    @Test void receiptFailureRollsBackRealFactEffectsBeforeSeparateTerminalStop() {
        var h=new Harness();h.fail();h.service.reserveExistingRecovery(h.admission);var read=h.service.beginRecoveredRead(h.admission,UUID.randomUUID());var before=h.s;var effects=h.effects;h.failReceipt=true;
        assertThatThrownBy(()->h.service.publishRecovery(h.admission,read,new RecoveredEvidence(h.snapshot(true),UTC))).hasMessage("receipt failed");
        assertThat(h.s).isEqualTo(before);assertThat(h.effects).isEqualTo(effects);assertThat(h.cash).containsExactly("pi_initial");assertThat(h.publication).isNull();assertThat(h.stopped).isTrue();assertThat(h.trace).containsSubsequence("publicationReceipt","rollback","begin","stop","commit");
    }
    @Test void explicitStopRequiresNoLiveAuthorityAndAmbientTransactionsAreRefused() {
        var h=new Harness();h.foreign=true;h.service.stop(h.admission,Reason.AUTHORITY_DRIFT);assertThat(h.stopped).isTrue();assertThat(h.trace).doesNotContain("lifecycle");
        var nested=new Harness();nested.active=true;assertThatThrownBy(()->nested.service.beginFailedRead(nested.admission,UUID.randomUUID())).isInstanceOf(IllegalStateException.class);assertThat(nested.reads).isEmpty();
    }
    @Test void gracePreparationAndExactReloadUseOneCallerTransactionAndOriginalReadySample() {
        var h=new Harness(false,"alias",900000);h.fail();var effects=h.effects;var sequence=h.sequence;var callbacks=h.callbacks;var cash=Set.copyOf(h.cash);
        h.trace.clear();h.utc=UTC.plusNanos(123456789);
        var p=h.service.prepareGraceLastSecond(h.admission);
        assertThat(p.target()).isEqualTo(h.failure.graceDeadline().getEpochSecond()-1);
        assertThat(p.preparedAt()).isEqualTo(h.utc.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(h.trace).containsExactly("begin","graceLocks","operations","lifecycle","account","subscription","bill","bill","gracePrepare","commit");
        assertThat(h.service.requireGraceLastSecond(h.admission,p)).isEqualTo(p);
        assertThat(h.effects).isEqualTo(effects);assertThat(h.sequence).isEqualTo(sequence);assertThat(h.callbacks).isEqualTo(callbacks);assertThat(h.cash).isEqualTo(cash);assertThat(h.billingSecond).isEqualTo(4700);assertThat(h.stopped).isFalse();
        assertThatThrownBy(()->h.service.prepareGraceLastSecond(h.admission)).isInstanceOf(IllegalStateException.class);assertThat(h.preparation).isEqualTo(p);assertThat(h.stopped).isFalse();
        var forged=new GraceLastSecondPreparation(UUID.randomUUID(),p.admissionId(),p.failureClaimId(),p.billId(),p.predecessorReadyId(),p.graceDeadline(),p.target(),p.operationKey(),p.fingerprint(),p.preparedAt());
        assertThatThrownBy(()->h.service.requireGraceLastSecond(h.admission,forged)).isInstanceOf(IllegalStateException.class);assertThat(h.preparation).isEqualTo(p);assertThat(h.stopped).isFalse();
    }
    @Test void graceRefusalsHaveNoStopNoEffectsAndRollbackStoreExceptions() {
        for(var bad:List.of("ambient","failure","time","ordinary","foreign","debt","stop","recovery","store","lock")) {
            var h=new Harness(false,"alias",900000);if(!bad.equals("failure"))h.fail();var effects=h.effects;var callbacks=h.callbacks;
            if(bad.equals("ambient"))h.active=true;if(bad.equals("time"))h.billingSecond++;if(bad.equals("ordinary"))h.isolated=false;if(bad.equals("foreign"))h.foreign=true;
            if(bad.equals("debt"))h.failure=new Failure(h.failure.admissionId(),h.failure.claimId(),h.failure.billId(),h.failure.billVersion()+1,h.failure.invoice(),h.failure.allocation(),h.failure.paymentIntent(),h.failure.period(),h.failure.graceDeadline(),h.failure.revision(),h.failure.observationSequence(),h.failure.recordedAt());
            if(bad.equals("stop"))h.stopped=true;if(bad.equals("recovery"))h.service.reserveExistingRecovery(h.admission);
            h.failGrace=bad.equals("store");h.failGraceLocks=bad.equals("lock");h.trace.clear();var alreadyStopped=h.stopped;
            assertThatThrownBy(()->h.service.prepareGraceLastSecond(h.admission)).isInstanceOf(RuntimeException.class);
            assertThat(h.preparation).isNull();assertThat(h.effects).isEqualTo(effects);assertThat(h.callbacks).isEqualTo(callbacks);assertThat(h.stopped).isEqualTo(alreadyStopped);assertThat(h.trace).doesNotContain("stop");
            if(bad.equals("ambient"))assertThat(h.trace).isEmpty();else assertThat(h.trace).startsWith("begin","graceLocks").endsWith("rollback");
            if(bad.equals("lock"))assertThat(h.trace).doesNotContain("operations","lifecycle");
        }
    }
    static UUID id(long n) {return new UUID(0,n);}
    static OwnedFirstRenewal.Admission upstream() {return upstream("alias");}
    static OwnedFirstRenewal.Admission upstream(String credentialsRef) {
        var owner=new ClockOwner(id(1),id(2),new PaymentAccountId(id(3)),new SiteId(id(4)),new CustomerId(id(5)),"acct_owned",credentialsRef,"2026-09-30.endive",1000,"billing_clock_"+id(1),"clock-key","a".repeat(64),UTC.minusSeconds(100));
        var clock=new ReadyClock(owner,id(6),"clock_owned",1000,2000,3000,UTC.minusSeconds(50),"b".repeat(64));
        var terms=new SubscriptionPriceTerms(new PriceId(id(7)),"monthly",1,new ProductId(id(8)),Money.of(1000,"EUR"),BillingInterval.MONTH,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
        var intent=new OwnedClockSubscription(id(9),clock,id(10),id(11),"cus_owned",id(12),id(13),"subscription:"+id(3)+":"+id(13)+":CREATE","v1.paid."+"d".repeat(64),0,1,terms,"price_owned","prod_owned","c".repeat(64),UTC.minusSeconds(10));
        var receipt=new OwnedClockSubscriptionReceipt(intent,id(15),"sub_owned",42,"si_owned",900,1100,UTC.minusSeconds(5));
        return new OwnedFirstRenewal.Admission(id(16),receipt,"a".repeat(40),id(17),id(18),1,id(19),0,id(20),0,"in_initial","inpay_initial","pi_initial",2,1,"e".repeat(64),UTC);
    }
    static OwnedFirstRenewal.LaterReady ready(OwnedFirstRenewal.Admission upstream,OwnedFirstRenewal.Step step,UUID previous,UUID boundary,long target) {
        var advance=new OwnedFirstRenewal.Advance(id(20+step.number()),upstream.id(),step,target,previous,boundary,"billing-renewal-advance-v1:"+upstream.id()+":"+step.number(),"e".repeat(64),UTC);
        var operation=new ProviderOperation(id(50+step.number()),upstream.receipt().intent().clock().owner().account(),"acct_owned",upstream.receipt().intent().clock().owner().credentialsRef(),ProviderOperation.Kind.TEST_CLOCK_ADVANCE,advance.id(),advance.operationKey(),advance.fingerprint(),ProviderOperation.Status.ACKNOWLEDGED,"clock_owned",UTC,null,null,null,1);
        var name=upstream.receipt().intent().clock().owner().name();
        var ack=new OwnedFirstRenewal.Ack(advance,operation,"owner",UTC.plusSeconds(60),new OwnedFirstRenewal.TestClockAck("clock_owned","test_helpers.test_clock",false,name,target,"ready",2000,3000,null),UTC);
        return new OwnedFirstRenewal.LaterReady(id(60+step.number()),upstream.id(),ack,new OwnedFirstRenewal.TestClockReady("clock_owned","test_helpers.test_clock",false,name,target,"ready",2000,3000,null),UTC);
    }

    static <T>T port(Class<T> type,BiFunction<String,Object[],Object> f){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->f.apply(m.getName(),a)));}
    static <T>T inert(Class<T> type){return port(type,(m,a)->{throw new AssertionError(type.getSimpleName()+"."+m);});}
    static class Harness {
        final OwnedFirstRenewal.Admission upstream;
        final OwnedFirstRenewal.LaterReady first,ready;
        final OwnedFirstRenewal.Boundary boundary;
        final Admission admission;
        final ClockOwner owner;
        Subscription s;
        final PaymentAccount account;
        Map<UUID,SubscriptionOperation> ops=new LinkedHashMap<>();Map<String,BillingRecord> records=new LinkedHashMap<>();Map<Phase,ReadClaim> reads=new HashMap<>();
        Set<String> cash=new LinkedHashSet<>();List<SubscriptionEvent.Type> events=new ArrayList<>();List<String> trace=new ArrayList<>();
        final List<UUID> operationLockTargets=new ArrayList<>();
        long sequence,billingSecond=4700;int effects,callbacks,timeCalls;boolean active,isolated=true,foreign,claimOwner=true,stopped,failReceipt,normalizeClaim,factTimeDrift,factModeDrift;Instant utc=UTC;
        dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding binding;
        Failure failure;Recovery recovery;Publication publication;GraceLastSecondPreparation preparation;boolean failGrace,failGraceLocks;final OwnedFailedRenewalService service;
        SubscriptionOperationStore operationsPort;SubscriptionStore subscriptionsPort;BillingRecordStore billsPort;
        SubscriptionObservationPort observationsPort;SubscriptionBillingTimePort timePort;SubscriptionTransactionPort transactionPort;Clock clockPort;SubscriptionFactService factsPort;
        SubscriptionIsolatedMethodProofPort methodProof=SubscriptionIsolatedMethodProofPort.denied();
        SubscriptionIsolatedRecoveryProofPort successorProof;SubscriptionBillingProofStore.Setup methodSetup;
        Harness(){this(false);}
        Harness(boolean withBinding){this(withBinding,"alias");}
        Harness(boolean withBinding,String credentialsRef){
            this(withBinding,credentialsRef,10000);
        }
        Harness(boolean withBinding,String credentialsRef,long periodEnd){
            upstream=upstream(credentialsRef);first=ready(upstream,OwnedFirstRenewal.Step.BOUNDARY,null,null,1100);
            boundary=new OwnedFirstRenewal.Boundary(id(70),upstream.id(),first,"si_owned","in_renewal",new Subscription.Period(Instant.ofEpochSecond(1100),Instant.ofEpochSecond(periodEnd)),"a".repeat(64),UTC);
            ready=ready(upstream,OwnedFirstRenewal.Step.FINALIZATION,first.id(),boundary.id(),4700);
            admission=new Admission(id(71),upstream,ready,boundary,upstream.sourceSha(),UTC);owner=upstream.receipt().intent().clock().owner();
            s=new Subscription(upstream.receipt().intent().subscription(),owner.site(),owner.customer(),upstream.receipt().intent().terms().productId(),owner.account(),upstream.receipt().intent().terms(),upstream.receipt().intent().terms(),Subscription.Status.ACTIVE,"sub_owned",new Subscription.Period(Instant.ofEpochSecond(900),Instant.ofEpochSecond(1100)),Instant.ofEpochSecond(1100),null,null,null,null,null,2,UTC,UTC,null);
            account=PaymentAccount.create(owner.account(),"owned",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_owned",credentialsRef);
            binding=new dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding(upstream.receipt().intent().customerBinding(),owner.account(),owner.site(),owner.customer(),upstream.receipt().intent().customerReference(),upstream.receipt().intent().customerCreationOperation(),0);
            var initialOperation=new SubscriptionOperation(upstream.receipt().intent().createOperation(),owner.site(),owner.customer(),s.id(),SubscriptionOperation.Kind.CREATE,SubscriptionOperation.Status.SUCCEEDED,"create","digest",upstream.receipt().intent().requestFingerprint(),upstream.receipt().intent().operationKey(),0,1,"sub_owned",null,UTC,UTC,1);ops.put(initialOperation.id(),initialOperation);
            records.put("in_initial",new BillingRecord(upstream.bill(),s.siteId(),s.id(),s.paymentAccountId(),"in_initial",BillingRecord.Kind.INITIAL,s.billingPrice(),null,s.currentPeriod(),1000,"EUR",BillingRecord.Settlement.CONFIRMED_CASH,0,UTC,UTC));
            var operations=port(SubscriptionOperationStore.class,(m,a)->switch(m){
                case "unresolved"->ops.values().stream().filter(o->o.siteId().equals(a[0])&&o.subscriptionId().equals(a[1])&&o.status()!=SubscriptionOperation.Status.SUCCEEDED).toList();
                case "lock"->{trace.add("operations");operationLockTargets.add((UUID)a[0]);yield ops.get(a[0]);}
                case "find"->Optional.ofNullable(ops.get(a[2]));
                case "claim"->{trace.add("claim");var o=(SubscriptionOperation)a[0];if(normalizeClaim)o=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),o.status(),o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),null,o.createdAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS),o.updatedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS),o.version());ops.put(o.id(),o);yield new SubscriptionOperationStore.Claim(o,claimOwner);}
                case "save"->{effects++;var o=(SubscriptionOperation)a[0];ops.put(o.id(),o);yield o;}
                default->throw new AssertionError(m);
            });
            var trials=port(TrialEligibilityStore.class,(m,a)->Optional.empty());
            var subscriptions=port(SubscriptionStore.class,(m,a)->switch(m){case "findOwned"->Optional.of(s);case "lock"->{trace.add("subscription");yield s;}case "save"->{effects++;s=(Subscription)a[0];yield s;}default->throw new AssertionError(m);});
            var bills=port(BillingRecordStore.class,(m,a)->switch(m){case "find"->Optional.ofNullable(records.get(a[3]));case "lock"->{trace.add("bill");yield records.values().stream().filter(b->b.id().equals(a[1])).findFirst().orElseThrow();}case "upsert"->{effects++;var b=(BillingRecord)a[0];var old=records.get(b.providerInvoiceReference());if(old!=null)b=new BillingRecord(b.id(),b.siteId(),b.subscriptionId(),b.accountId(),b.providerInvoiceReference(),b.kind(),b.billedPrice(),b.previousPrice(),b.period(),b.amountDueMinor(),b.currency(),b.settlement(),old.version()+(old.settlement()!=b.settlement()?1:0),b.createdAt(),b.updatedAt());records.put(b.providerInvoiceReference(),b);yield b;}default->throw new AssertionError(m);});
            var observations=port(SubscriptionObservationPort.class,(m,a)->switch(m){case "captureFence"->sequence;case "consumeFence"->{if(!Objects.equals(a[1],sequence))throw new IllegalStateException("stale");sequence++;yield null;}default->throw new AssertionError(m);});
            var time=new SubscriptionBillingTimePort(){public Sample sample(PaymentAccountId account,SiteId site,CustomerId customer,Instant at){assertThat(account).isEqualTo(owner.account());assertThat(site).isEqualTo(owner.site());assertThat(customer).isEqualTo(owner.customer());assertThat(at).isEqualTo(utc);timeCalls++;return new Sample(Instant.ofEpochSecond(factTimeDrift&&timeCalls%2==0?4701:billingSecond),isolated&&!foreign&&!(factModeDrift&&timeCalls%2==0)?Mode.ISOLATED:Mode.ORDINARY);}public void requireUnscopedAllowed(SiteId site,CustomerId customer){throw new AssertionError();}};
            var clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return utc;}};
            var store=port(OwnedFailedRenewalStore.class,(m,a)->{if(!active)throw new AssertionError("outside tx");return switch(m){
                case "admitInTransaction"->admission;
                case "lockGraceLastSecondInTransaction"->{trace.add("graceLocks");current((Admission)a[0]);if(failGraceLocks)throw new IllegalStateException("lock unavailable");yield null;}
                case "prepareGraceLastSecondInTransaction"->{trace.add("gracePrepare");current((Admission)a[0]);if(preparation!=null)throw new IllegalStateException("duplicate preparation");var f=(Failure)a[1];var at=(Instant)a[2];var id=UUID.randomUUID();var target=OwnedFailedRenewal.graceLastSecondTarget(admission,f);var key="billing-failed-grace-v1:"+admission.id()+":1";preparation=new GraceLastSecondPreparation(id,admission.id(),f.claimId(),f.billId(),admission.ready().id(),f.graceDeadline(),target,key,OwnedFailedRenewal.graceLastSecondFingerprint(id,admission,f,target,key,at),at);if(failGrace)throw new IllegalStateException("grace receipt failed");yield preparation;}
                case "requireGraceLastSecondInTransaction"->{current((Admission)a[0]);if(preparation==null||!preparation.equals(a[1]))throw new IllegalStateException("preparation unavailable");yield preparation;}
                case "requireCurrentInTransaction"->{current((Admission)a[0]);if(recovery==null&&ops.size()>1)throw new IllegalStateException("transient unassociated");yield admission;}
                case "claimReadInTransaction"->{current((Admission)a[0]);var phase=(Phase)a[1];if(reads.containsKey(phase))throw new IllegalStateException("duplicate read");var r=new ReadClaim(UUID.randomUUID(),admission.id(),phase,(UUID)a[2],(Instant)a[4],((Instant)a[4]).plusSeconds(30),(Fence)a[3]);reads.put(phase,r);yield r;}
                case "requireReadInTransaction"->{current((Admission)a[0]);var r=(ReadClaim)a[1];if(!r.equals(reads.get(r.phase()))||!utc.isBefore(r.deadlineAt()))throw new IllegalStateException("read unavailable");yield r;}
                case "recordFailureInTransaction"->{trace.add("failureReceipt");if(failReceipt)throw new IllegalStateException("receipt failed");assertThat(s.debt()).isNotNull();var b=records.get("in_renewal");failure=new Failure(admission.id(),((ReadClaim)a[1]).id(),b.id(),b.version(),b.providerInvoiceReference(),"inpay_renewal","pi_renewal",b.period(),s.debt().graceDeadline(),s.revision(),sequence,utc);yield failure;}
                case "requireFailureInTransaction"->{current((Admission)a[0]);if(recovery!=null)throw new IllegalStateException("already reserved");yield Objects.requireNonNull(failure);}
                case "associateInTransaction"->{trace.add("associate");current((Admission)a[0]);if(recovery!=null)throw new IllegalStateException("duplicate");var o=(SubscriptionOperation)a[1];recovery=new Recovery(admission.id(),o.id(),failure.billId(),(String)a[2],(String)a[3],o.expectedRevision(),o.version(),utc);yield recovery;}
                case "requireRecoveryInTransaction"->{current((Admission)a[0]);yield Objects.requireNonNull(recovery);}
                case "recordPublicationInTransaction"->{trace.add("publicationReceipt");if(failReceipt)throw new IllegalStateException("receipt failed");assertThat(s.debt()).isNull();assertThat(ops.get(recovery.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);publication=new Publication(admission.id(),((ReadClaim)a[1]).id(),recovery.operationId(),failure.billId(),UUID.randomUUID(),s.revision(),utc);yield publication;}
                case "publicationInTransaction"->{current((Admission)a[0]);yield Optional.ofNullable(publication);}
                case "stopInTransaction"->{assertThat(a[0]).isEqualTo(admission);trace.add("stop");stopped=true;yield null;}
                case "requireRecoveryFactInTransaction"->{var command=(dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Command)a[0];assertThat(command.factKey()).isEqualTo(OwnedFailedRenewal.factKey(reads.get(Phase.RECOVERED)));assertThat(command.fence()).isEqualTo(reads.get(Phase.RECOVERED).fence());assertThat(s.debt()).isNotNull();yield null;}
                default->throw new AssertionError(m);
            };});
            var site=Site.create(s.siteId(),SiteKey.of("site"),"Site",s.paymentAccountId(),Set.of(java.net.URI.create("https://test.example")),Set.of(AuthenticationMode.SITE_TOKEN),PaymentMethodPolicy.automatic());
            var tx=new SubscriptionTransactionPort(){public <T>T required(Supplier<T> work){return requiresNew(work);}public boolean active(){return active;}public <T>T requiresNew(Supplier<T> work){if(active)throw new AssertionError("nested transaction");trace.add("begin");active=true;var oldS=s;var oldOps=new LinkedHashMap<>(ops);var oldBills=new LinkedHashMap<>(records);var oldReads=new HashMap<>(reads);var oldCash=new LinkedHashSet<>(cash);var oldEvents=new ArrayList<>(events);var oldFailure=failure;var oldRecovery=recovery;var oldPublication=publication;var oldPreparation=preparation;var oldSeq=sequence;var oldEffects=effects;var oldCallbacks=callbacks;try{var result=work.get();trace.add("commit");return result;}catch(RuntimeException e){s=oldS;ops=oldOps;records=oldBills;reads=oldReads;cash=oldCash;events=oldEvents;failure=oldFailure;recovery=oldRecovery;publication=oldPublication;preparation=oldPreparation;sequence=oldSeq;effects=oldEffects;callbacks=oldCallbacks;trace.add("rollback");throw e;}finally{active=false;}}};
            var facts=new SubscriptionFactService(subscriptions,operations,trials,port(dev.genericpayments.platform.sites.application.port.out.LockSitePort.class,(m,a)->Optional.of(site)),port(dev.genericpayments.platform.customers.application.port.out.CustomerStore.class,(m,a)->Optional.of(Customer.create(s.customerId(),s.siteId(),null,null,UTC))),id->Optional.of(account),bills,
                c->{effects++;cash.add(c.providerPaymentReference());return null;},port(SubscriptionEventStore.class,(m,a)->{effects++;events.add(((SubscriptionEvent)a[0]).type());return true;}),port(SubscriptionCatalogPort.class,(m,a)->s.billingPrice()),clock,
                (sub,event,access,enqueued)->{effects++;callbacks++;assertThat(event.receivedAt()).isEqualTo(utc);},port(SubscriptionObjectPort.class,(m,a)->{effects++;return null;}),
                (b,v,deadline)->new BillingMetadataPort.Captured(v,deadline),port(SubscriptionPolicyPort.class,(m,a)->new SubscriptionPolicy(s.siteId(),1,true,3,false,false,false,Set.of())),observations,value->"digest",inert(SubscriptionQuoteStore.class),inert(SubscriptionSweepPort.class),new SubscriptionReadPreparation(operations,trials),inert(SubscriptionAuthorityPort.class),time,
                (c,s,o,b,u)->{if(successorProof!=null)successorProof.requireAuthorized(c,s,o,b,u);else new dev.genericpayments.platform.provider.adapter.out.persistence.OwnedFailedRenewalFactProof(store,tx,time).requireAuthorized(c,s,o,b,u);},
                (c,s,o,b,u)->methodProof.requireAuthorized(c,s,o,b,u),
                port(SubscriptionBillingProofStore.class,(m,a)->{if(!m.equals("setup"))throw new AssertionError(m);return Optional.ofNullable(methodSetup);}));
            operationsPort=operations;subscriptionsPort=subscriptions;billsPort=bills;observationsPort=observations;timePort=time;transactionPort=tx;clockPort=clock;factsPort=facts;

            var bindings=port(dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.class,(m,a)->Optional.of(binding));
            service=withBinding?new OwnedFailedRenewalService(store,tx,operations,(siteId,customer)->trace.add("lifecycle"),id->{trace.add("account");return Optional.of(account);},subscriptions,bills,new SubscriptionReadPreparation(operations,trials),observations,c->{trace.add("facts");facts.apply(c);},trials,time,clock,bindings):new OwnedFailedRenewalService(store,tx,operations,(siteId,customer)->trace.add("lifecycle"),id->{trace.add("account");return Optional.of(account);},subscriptions,bills,new SubscriptionReadPreparation(operations,trials),observations,c->{trace.add("facts");facts.apply(c);},trials,time,clock);
        }
        void current(Admission supplied){if(!admission.equals(supplied)||stopped)throw new IllegalStateException("authority unavailable");}
        void fail(){var read=service.beginFailedRead(admission,UUID.randomUUID());service.publishFailure(admission,read,new FailedEvidence(snapshot(false),"inpay_renewal","pi_renewal",utc));}
        Snapshot snapshot(boolean recovered){var terms=s.billingPrice();var initial=new Invoice("in_initial",BillingRecord.Kind.INITIAL,terms,null,new Subscription.Period(Instant.ofEpochSecond(900),Instant.ofEpochSecond(1100)),1000,"EUR",SettlementKind.CASH,List.of(new CashObject("pi_initial",1000,"EUR",dev.genericpayments.platform.payments.domain.PaymentStatus.SUCCEEDED,UTC.minusSeconds(1))));var renewal=new Invoice("in_renewal",BillingRecord.Kind.RENEWAL,terms,null,boundary.period(),1000,"EUR",recovered?SettlementKind.CASH:SettlementKind.OPEN,recovered?List.of(new CashObject("pi_renewal",1000,"EUR",dev.genericpayments.platform.payments.domain.PaymentStatus.SUCCEEDED,utc)):List.of());var key=recovered?ops.get(recovery.operationId()).providerOperationKey():null;return new Snapshot("sub_owned",recovered?Subscription.Status.ACTIVE:Subscription.Status.PAST_DUE,terms,boundary.period(),null,null,List.of(initial,renewal),key,true,0,utc,recovered?new OperationObservation(key,OperationState.APPLIED):null);}
    }
}
