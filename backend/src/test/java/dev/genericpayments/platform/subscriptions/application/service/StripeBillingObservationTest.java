package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.domain.Customer;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.sites.domain.Site;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Pure fact-service behavior. Database CAS/rollback is separately exercised in hosted integration tests. */
class StripeBillingObservationTest {
    static final Instant NOW=Instant.parse("2026-10-01T00:00:00Z");
    final Map<Class<?>,Object> dependencies=new HashMap<>();
    final MemoryObservations observations=new MemoryObservations();
    Subscription current;PaymentAccount account;SubscriptionFactService service;int saves,events;boolean actionReplyRecorded;boolean scheduleObserved=true;boolean originalFirst;boolean scheduleCompleted;
    @BeforeEach void setup() throws Exception {
        current=SubscriptionFixtures.active(NOW.minusSeconds(86400),NOW.plusSeconds(86400));
        account=PaymentAccount.create(current.paymentAccountId(),"billing",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_test","stripe-sandbox-default");
        var constructor=SubscriptionFactService.class.getConstructors()[0];
        for(var type:constructor.getParameterTypes())dependencies.put(type,type==Clock.class?Clock.fixed(NOW,ZoneOffset.UTC):type==SubscriptionObservationPort.class?observations:mock(type));
        service=(SubscriptionFactService)constructor.newInstance(Arrays.stream(constructor.getParameterTypes()).map(dependencies::get).toArray());
        when(dep(SubscriptionStore.class).findOwned(any(),any())).thenAnswer(i->Optional.of(current));
        when(dep(SubscriptionStore.class).lock(any(),any())).thenAnswer(i->current);
        when(dep(SubscriptionStore.class).save(any(),anyLong())).thenAnswer(i->{current=i.getArgument(0);saves++;return current;});
        when(dep(LockPaymentAccountPort.class).findByIdForUpdate(any())).thenAnswer(i->Optional.of(account));
        when(dep(LockSitePort.class).findByIdForUpdate(any())).thenReturn(Optional.of(mock(Site.class)));
        when(dep(CustomerStore.class).findByIdForUpdate(any(),any())).thenReturn(Optional.of(Customer.create(current.customerId(),current.siteId(),null,null,NOW)));
        when(dep(SubscriptionCatalogPort.class).terms(any(),any())).thenAnswer(i->current.billingPrice());
        when(dep(SubscriptionEventStore.class).append(any())).thenAnswer(i->{events++;return true;});
    }
    <T>T dep(Class<T> type){return type.cast(dependencies.get(type));}
    @Test void originalCashProrationAppliesTargetWithUnchangedFullItemPeriod(){prorationFact(false,true);}
    @Test void originalZeroProrationAppliesTargetWithoutPositiveCash(){prorationFact(true,true);verifyNoInteractions(dep(SubscriptionCashPort.class));}
    @Test void unownedProrationNeverAppliesTarget(){prorationFact(false,false);}
    @Test void originalCashProrationAppliesAfterActualActionReplyRevision(){actionReplyRecorded=true;prorationFact(false,true);}
    @Test void configuredOriginalScheduleCashAppliesOnlyAtItsExactPaidBoundary(){scheduledFact(true);}
    @Test void unownedTargetRenewalCannotReplacePendingPlanEntitlement(){scheduledFact(false);}
    @Test void firstTargetCashDoesNotNeedAnEarlierSchedulingObservation(){scheduleObserved=false;scheduledFact(true);}
    @Test void delayedImmediateCashTransitionProjectsLatestPaidTargetPeriod(){delayedPlan(ChangeTiming.IMMEDIATE,false,null);}
    @Test void delayedImmediateZeroTransitionProjectsLatestPaidTargetPeriod(){delayedPlan(ChangeTiming.IMMEDIATE,true,null);}
    @Test void delayedConfiguredTransitionProjectsLatestPaidTargetPeriod(){delayedPlan(ChangeTiming.NEXT_PERIOD,false,null);}
    @Test void delayedImmediateCashIsIndependentOfInvoiceOrdering(){originalFirst=true;delayedPlan(ChangeTiming.IMMEDIATE,false,null);}
    @Test void delayedConfiguredCashIsIndependentOfInvoiceOrdering(){originalFirst=true;delayedPlan(ChangeTiming.NEXT_PERIOD,false,null);}
    @Test void delayedConfiguredCashStillAppliesAfterSchedulingOperationCompleted(){scheduleCompleted=true;delayedPlan(ChangeTiming.NEXT_PERIOD,false,null);}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"missing","unpaid","currentUnpaid","foreign","newerIntent","revision","debt","cancel","terminal"})
    void delayedImmediateRenewalCannotSubstituteForOriginalTransition(String fault){delayedPlan(ChangeTiming.IMMEDIATE,false,fault);}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"missing","unpaid","currentUnpaid","foreign","newerIntent","revision","debt","cancel","terminal"})
    void delayedConfiguredRenewalCannotSubstituteForOriginalTransition(String fault){delayedPlan(ChangeTiming.NEXT_PERIOD,false,fault);}
    void delayedPlan(ChangeTiming timing,boolean zero,String fault){
        var s=current;var old=s.billingPrice();var id=UUID.randomUUID();
        var target=new SubscriptionPriceTerms(new dev.genericpayments.platform.catalog.domain.PriceId(UUID.randomUUID()),"delayed",1,s.productId(),dev.genericpayments.platform.catalog.domain.Money.of(2000,"EUR"),old.interval(),0,old.trialPolicy(),old.taxMode());
        var effective=timing==ChangeTiming.IMMEDIATE?NOW:s.paidThrough();
        var operation=new SubscriptionOperation(id,s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.CHANGE,scheduleCompleted?SubscriptionOperation.Status.SUCCEEDED:SubscriptionOperation.Status.PROCESSING,"test","key","v1.plan."+"a".repeat(64),"subscription:"+account.id().value()+":"+id+":CHANGE",s.revision(),1,s.providerReference(),null,NOW,NOW,0);
        current=SubscriptionPlanTransaction.withChange(s,new Subscription.Change("newerIntent".equals(fault)?UUID.randomUUID():id,target,effective,scheduleCompleted?Subscription.ChangeState.SCHEDULED:Subscription.ChangeState.UNKNOWN),NOW);
        if("revision".equals(fault))current=SubscriptionPlanTransaction.withChange(current,current.pendingChange(),NOW);
        if("terminal".equals(fault))current=current.cancel("PROVIDER_CANCELLED",NOW);
        if("cancel".equals(fault))current=new Subscription(current.id(),current.siteId(),current.customerId(),current.productId(),current.paymentAccountId(),current.billingPrice(),current.entitledPrice(),current.billingStatus(),current.providerReference(),current.currentPeriod(),current.paidThrough(),current.trial(),new Subscription.Cancellation(s.paidThrough()),current.pendingChange(),current.debt(),current.terminalReason(),current.revision(),current.createdAt(),current.updatedAt(),current.lastReconciledAt());
        if("debt".equals(fault))current=current.renewalFailed(UUID.randomUUID(),new Subscription.Period(s.paidThrough(),s.paidThrough().plusSeconds(2678400)),0,NOW);
        when(dep(SubscriptionOperationStore.class).unresolved(any(),any())).thenReturn(scheduleCompleted?List.of():List.of(operation));when(dep(SubscriptionOperationStore.class).lock(id)).thenReturn(operation);when(dep(SubscriptionOperationStore.class).find(s.siteId(),s.id(),id)).thenReturn(Optional.of(operation));when(dep(SubscriptionReadPreparation.class).financial(any())).thenReturn(scheduleCompleted?null:operation);
        when(dep(BillingRecordStore.class).upsert(any())).thenAnswer(i->i.getArgument(0));when(dep(SubscriptionCatalogPort.class).terms(any(),eq(target.priceId()))).thenReturn(target);when(dep(SubscriptionCatalogPort.class).terms(any(),eq(old.priceId()))).thenReturn(old);
        long due=timing==ChangeTiming.IMMEDIATE?(zero?0:500):0;
        var q=new SubscriptionPlanProofStore.Quote(UUID.randomUUID(),account.id(),s.siteId(),s.customerId(),s.id(),s.providerReference(),"cus_owned",old,old,target,"price_old","price_target","prod_owned","si_owned",s.currentPeriod(),s.paidThrough(),s.revision(),1,timing,NOW,NOW.plusSeconds(300),due,List.of());
        var proofs=mock(SubscriptionPlanProofStore.class);org.springframework.test.util.ReflectionTestUtils.setField(service,"planProofs",proofs);
        var firstPeriod=timing==ChangeTiming.IMMEDIATE?new Subscription.Period(NOW,s.paidThrough()):new Subscription.Period(s.paidThrough(),s.paidThrough().plusSeconds(2678400));
        var latest=new Subscription.Period(firstPeriod.end(),firstPeriod.end().plusSeconds(2678400));
        var historical=new Invoice("in_original",timing==ChangeTiming.IMMEDIATE?BillingRecord.Kind.PRORATION:BillingRecord.Kind.RENEWAL,target,timing==ChangeTiming.IMMEDIATE?old:null,firstPeriod,timing==ChangeTiming.IMMEDIATE?due:2000,"EUR","unpaid".equals(fault)?SettlementKind.OPEN:zero?SettlementKind.ZERO:SettlementKind.CASH,zero||"unpaid".equals(fault)?List.of():List.of(new CashObject("pi_original",timing==ChangeTiming.IMMEDIATE?due:2000,"EUR",dev.genericpayments.platform.payments.domain.PaymentStatus.SUCCEEDED,NOW)));
        var result=new SubscriptionPlanProofStore.Result("foreign".equals(fault)?UUID.randomUUID():id,s.id(),account.id(),q.id(),UUID.randomUUID(),s.providerReference(),historical.reference(),null,"il_credit","il_debit");
        when(proofs.proration(account.id(),historical.reference())).thenReturn(Optional.of(new SubscriptionPlanProofStore.Proration(q,result)));
        var schedule=new SubscriptionPlanProofStore.Schedule("foreign".equals(fault)?UUID.randomUUID():id,s.id(),account.id(),q.id(),UUID.randomUUID(),"sub_sched_owned",s.currentPeriod().start().getEpochSecond(),UUID.randomUUID(),firstPeriod.end().getEpochSecond(),null,null,new SubscriptionPlanProofStore.SourcePhase("none",null,"charge_automatically"));
        when(proofs.schedule(account.id(),id)).thenReturn(Optional.of(schedule));when(proofs.quote(account.id(),q.id())).thenReturn(Optional.of(q));
        var generic=new SubscriptionQuote(UUID.randomUUID(),s.siteId(),s.id(),"test",old.priceId(),old.version(),target,timing,due,2000,"EUR",effective,NOW.plusSeconds(300),true,s.revision(),1,q.reference());when(dep(SubscriptionQuoteStore.class).forOperation(s.siteId(),s.id(),id)).thenReturn(Optional.of(generic));
        var renewal=new Invoice("in_current",BillingRecord.Kind.RENEWAL,target,null,latest,2000,"EUR","currentUnpaid".equals(fault)?SettlementKind.OPEN:SettlementKind.CASH,"currentUnpaid".equals(fault)?List.of():List.of(new CashObject("pi_current",2000,"EUR",dev.genericpayments.platform.payments.domain.PaymentStatus.SUCCEEDED,latest.start())));
        var invoices="missing".equals(fault)?List.of(renewal):originalFirst?List.of(historical,renewal):List.of(renewal,historical);
        var at=latest.start().plusSeconds(100);org.springframework.test.util.ReflectionTestUtils.setField(service,"clock",Clock.fixed(at,ZoneOffset.UTC));
        var snapshot=new Snapshot(s.providerReference(),Subscription.Status.ACTIVE,target,latest,null,null,invoices,scheduleCompleted?null:operation.providerOperationKey(),false,0,at,scheduleCompleted?null:new OperationObservation(operation.providerOperationKey(),OperationState.APPLIED));
        apply(snapshot,new Fence(current.revision(),scheduleCompleted?null:id,scheduleCompleted?null:operation.version(),null,null,observations.captureFence(current)));
        if(fault==null){
            assertThat(current.entitledPrice()).isEqualTo(target);assertThat(current.currentPeriod()).isEqualTo(latest);assertThat(current.paidThrough()).isEqualTo(latest.end());assertThat(current.pendingChange()).isNull();
            if(!scheduleCompleted){var completed=org.mockito.ArgumentCaptor.forClass(SubscriptionOperation.class);verify(dep(SubscriptionOperationStore.class)).save(completed.capture(),eq(operation.version()));assertThat(completed.getValue().status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);}
            var recorded=org.mockito.ArgumentCaptor.forClass(SubscriptionEvent.class);verify(dep(SubscriptionEventStore.class),atLeastOnce()).append(recorded.capture());var bill=org.mockito.ArgumentCaptor.forClass(BillingRecord.class);verify(dep(BillingRecordStore.class),atLeastOnce()).upsert(bill.capture());var originalBill=bill.getAllValues().stream().filter(b->b.providerInvoiceReference().equals(historical.reference())).findFirst().orElseThrow();assertThat(recorded.getAllValues()).anyMatch(e->e.type()==SubscriptionEvent.Type.CHANGE_APPLIED&&originalBill.id().equals(e.billingRecordId()));
            int eventCount=events,saveCount=saves;when(dep(SubscriptionOperationStore.class).unresolved(any(),any())).thenReturn(List.of());when(dep(SubscriptionReadPreparation.class).financial(any())).thenReturn(null);apply(snapshot,fence());assertThat(events).isEqualTo(eventCount);assertThat(saves).isEqualTo(saveCount);
        }else{assertThat(current.entitledPrice()).isEqualTo(old);assertThat(current.paidThrough()).isEqualTo(s.paidThrough());if(timing==ChangeTiming.IMMEDIATE)verify(dep(SubscriptionOperationStore.class),never()).save(any(),anyLong());var recorded=org.mockito.ArgumentCaptor.forClass(SubscriptionEvent.class);verify(dep(SubscriptionEventStore.class),atLeast(0)).append(recorded.capture());assertThat(recorded.getAllValues()).noneMatch(e->e.type()==SubscriptionEvent.Type.CHANGE_APPLIED);}
    }
    void scheduledFact(boolean owned){
        var s=current;var old=s.billingPrice();var boundary=s.paidThrough();
        var target=new SubscriptionPriceTerms(new dev.genericpayments.platform.catalog.domain.PriceId(UUID.randomUUID()),"deferred",1,s.productId(),dev.genericpayments.platform.catalog.domain.Money.of(2000,"EUR"),old.interval(),0,old.trialPolicy(),old.taxMode());
        var id=UUID.randomUUID();current=SubscriptionPlanTransaction.withChange(s,new Subscription.Change(id,target,boundary,scheduleObserved?Subscription.ChangeState.SCHEDULED:Subscription.ChangeState.UNKNOWN),NOW);
        var paidPeriod=new Subscription.Period(boundary,boundary.plusSeconds(2678400));
        var q=new SubscriptionPlanProofStore.Quote(UUID.randomUUID(),account.id(),s.siteId(),s.customerId(),s.id(),s.providerReference(),"cus_owned",old,old,target,"price_old","price_target","prod_owned","si_owned",s.currentPeriod(),boundary,s.revision(),1,ChangeTiming.NEXT_PERIOD,NOW,NOW.plusSeconds(300),0,List.of());
        var schedule=new SubscriptionPlanProofStore.Schedule(id,s.id(),account.id(),q.id(),UUID.randomUUID(),"sub_sched_owned",s.currentPeriod().start().getEpochSecond(),UUID.randomUUID(),paidPeriod.end().getEpochSecond(),null,null,new SubscriptionPlanProofStore.SourcePhase("none",null,"charge_automatically"));
        var proofs=mock(SubscriptionPlanProofStore.class);org.springframework.test.util.ReflectionTestUtils.setField(service,"planProofs",proofs);when(proofs.schedule(account.id(),id)).thenReturn(owned?Optional.of(schedule):Optional.empty());when(proofs.quote(account.id(),q.id())).thenReturn(Optional.of(q));
        var generic=new SubscriptionQuote(UUID.randomUUID(),s.siteId(),s.id(),"test",old.priceId(),old.version(),target,ChangeTiming.NEXT_PERIOD,0,2000,"EUR",boundary,NOW.plusSeconds(300),true,s.revision(),1,q.reference());when(dep(SubscriptionQuoteStore.class).forOperation(s.siteId(),s.id(),id)).thenReturn(Optional.of(generic));
        when(dep(BillingRecordStore.class).upsert(any())).thenAnswer(i->i.getArgument(0));when(dep(SubscriptionCatalogPort.class).terms(any(),eq(target.priceId()))).thenReturn(target);when(dep(SubscriptionCatalogPort.class).terms(any(),eq(old.priceId()))).thenReturn(old);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"clock",Clock.fixed(boundary.plusSeconds(100),ZoneOffset.UTC));
        var invoice=new Invoice("in_scheduled",BillingRecord.Kind.RENEWAL,target,null,paidPeriod,2000,"EUR",SettlementKind.CASH,List.of(new CashObject("pi_scheduled",2000,"EUR",dev.genericpayments.platform.payments.domain.PaymentStatus.SUCCEEDED,boundary)));
        var snapshot=new Snapshot(s.providerReference(),Subscription.Status.ACTIVE,target,paidPeriod,null,null,List.of(invoice),null,false,0,boundary.plusSeconds(100));apply(snapshot,fence());
        assertThat(current.entitledPrice()).isEqualTo(owned?target:old);assertThat(current.paidThrough()).isEqualTo(owned?paidPeriod.end():boundary);
        if(owned){var event=org.mockito.ArgumentCaptor.forClass(SubscriptionEvent.class);verify(dep(SubscriptionEventStore.class),atLeastOnce()).append(event.capture());assertThat(event.getAllValues()).anySatisfy(e->assertThat(e.type()).isEqualTo(SubscriptionEvent.Type.CHANGE_APPLIED));}
    }
    void prorationFact(boolean zero,boolean owned){
        var s=current;var old=s.billingPrice();var target=new SubscriptionPriceTerms(new dev.genericpayments.platform.catalog.domain.PriceId(UUID.randomUUID()),"upgrade",1,s.productId(),dev.genericpayments.platform.catalog.domain.Money.of(2000,"EUR"),old.interval(),0,old.trialPolicy(),old.taxMode());
        var id=UUID.randomUUID();var o=new SubscriptionOperation(id,s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.CHANGE,SubscriptionOperation.Status.PROCESSING,"test","key","v1.plan."+"a".repeat(64),"subscription:"+account.id().value()+":"+id+":CHANGE",s.revision(),1,s.providerReference(),null,NOW,NOW,0);
        current=SubscriptionPlanTransaction.withChange(s,new Subscription.Change(id,target,NOW,Subscription.ChangeState.PAYMENT_PENDING),NOW);
        if(actionReplyRecorded)current=SubscriptionPlanTransaction.withChange(current,current.pendingChange(),NOW);
        when(dep(SubscriptionOperationStore.class).unresolved(any(),any())).thenReturn(List.of(o));when(dep(SubscriptionOperationStore.class).lock(id)).thenReturn(o);when(dep(SubscriptionReadPreparation.class).financial(any())).thenReturn(o);when(dep(BillingRecordStore.class).upsert(any())).thenAnswer(i->i.getArgument(0));when(dep(SubscriptionCatalogPort.class).terms(any(),eq(target.priceId()))).thenReturn(target);when(dep(SubscriptionCatalogPort.class).terms(any(),eq(old.priceId()))).thenReturn(old);
        var period=new Subscription.Period(NOW,s.paidThrough());long due=zero?0:500;
        var q=new SubscriptionPlanProofStore.Quote(UUID.randomUUID(),account.id(),s.siteId(),s.customerId(),s.id(),s.providerReference(),"cus_owned",old,old,target,"price_old","price_target","prod_owned","si_owned",s.currentPeriod(),s.paidThrough(),s.revision(),1,ChangeTiming.IMMEDIATE,NOW,NOW.plusSeconds(300),due,List.of(new SubscriptionPlanProofStore.Line("price_old","prod_owned","si_owned",-500,period,null,List.of()),new SubscriptionPlanProofStore.Line("price_target","prod_owned","si_owned",500+due,period,null,List.of())));
        var r=new SubscriptionPlanProofStore.Result(id,s.id(),account.id(),q.id(),UUID.randomUUID(),s.providerReference(),"in_change",null,"il_credit","il_debit");var proofs=mock(SubscriptionPlanProofStore.class);org.springframework.test.util.ReflectionTestUtils.setField(service,"planProofs",proofs);when(proofs.proration(account.id(),"in_change")).thenReturn(owned?Optional.of(new SubscriptionPlanProofStore.Proration(q,r)):Optional.empty());
        var generic=new SubscriptionQuote(UUID.randomUUID(),s.siteId(),s.id(),"test",old.priceId(),old.version(),target,ChangeTiming.IMMEDIATE,due,2000,"EUR",NOW,NOW.plusSeconds(300),true,s.revision(),1,q.reference());when(dep(SubscriptionQuoteStore.class).forOperation(s.siteId(),s.id(),id)).thenReturn(Optional.of(generic));
        var invoice=new Invoice("in_change",BillingRecord.Kind.PRORATION,target,old,period,due,"EUR",zero?SettlementKind.ZERO:SettlementKind.CASH,zero?List.of():List.of(new CashObject("pi_change",500,"EUR",dev.genericpayments.platform.payments.domain.PaymentStatus.SUCCEEDED,NOW)));
        var snapshot=new Snapshot(s.providerReference(),Subscription.Status.ACTIVE,target,s.currentPeriod(),null,null,List.of(invoice),o.providerOperationKey(),false,0,NOW,new OperationObservation(o.providerOperationKey(),OperationState.APPLIED));apply(snapshot,new Fence(current.revision(),id,o.version(),null,null,observations.captureFence(current)));
        assertThat(current.entitledPrice()).isEqualTo(owned?target:old);assertThat(current.currentPeriod()).isEqualTo(s.currentPeriod());assertThat(current.paidThrough()).isEqualTo(s.paidThrough());
        if(owned){assertThat(current.pendingChange()).isNull();var event=org.mockito.ArgumentCaptor.forClass(SubscriptionEvent.class);verify(dep(SubscriptionEventStore.class),atLeastOnce()).append(event.capture());assertThat(event.getAllValues()).anySatisfy(e->assertThat(e.type()).isEqualTo(SubscriptionEvent.Type.CHANGE_APPLIED));}
        else assertThat(current.pendingChange()).isNotNull();
    }
    @Test void delayedFirstTrialObservationKeepsHistoryAndFinalPaidEvent(){delayedTrial(false);}
    @Test void delayedTrialHistoryCannotReplaceTerminalProjectionOrCallback(){delayedTrial(true);}
    void delayedTrial(boolean terminal){
        var s=current;current=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),null,terminal?Subscription.Status.CANCELLED:Subscription.Status.INCOMPLETE,s.providerReference(),null,null,null,null,null,null,terminal?"PROVIDER_CANCELLED":null,0,s.createdAt(),s.updatedAt(),null);
        var id=UUID.randomUUID();var step=UUID.randomUUID();var trial=new Subscription.Period(NOW.minusSeconds(1209600),NOW.minusSeconds(100));var paid=new Subscription.Period(trial.end(),NOW.plusSeconds(86400));
        var operation=new SubscriptionOperation(id,s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.CREATE,SubscriptionOperation.Status.PROCESSING,"test","key","v1.trial."+"a".repeat(64),"subscription:"+account.id().value()+":"+id+":CREATE",0,1,s.providerReference(),null,NOW.minusSeconds(1209600),NOW.minusSeconds(1209600),0);
        var eligibility=new TrialEligibility(s.siteId(),s.customerId(),s.productId(),id,null,0);
        when(dep(TrialEligibilityStore.class).find(any(),any(),any())).thenReturn(Optional.of(eligibility));when(dep(SubscriptionOperationStore.class).unresolved(any(),any())).thenReturn(List.of(operation));when(dep(SubscriptionOperationStore.class).find(any(),any(),eq(id))).thenReturn(Optional.of(operation));when(dep(SubscriptionOperationStore.class).lock(id)).thenReturn(operation);when(dep(SubscriptionReadPreparation.class).financial(any())).thenReturn(operation);
        when(dep(BillingRecordStore.class).upsert(any())).thenAnswer(i->i.getArgument(0));
        var proofs=mock(SubscriptionBillingProofStore.class);org.springframework.test.util.ReflectionTestUtils.setField(service,"billingProofs",proofs);
        var request=new SubscriptionBillingProofStore.Trial(id,s.id(),account.id(),trial.end().getEpochSecond(),null,null,null,null,null);
        when(proofs.trial(account.id(),s.id())).thenReturn(Optional.of(request));when(proofs.bindTrialInTransaction(any(),eq(step),anyString(),eq("si_owned"),eq("in_trial"),eq(trial.start().getEpochSecond()))).thenReturn(new SubscriptionBillingProofStore.Trial(id,s.id(),account.id(),trial.end().getEpochSecond(),step,s.providerReference(),"si_owned","in_trial",trial.start().getEpochSecond()));
        var zero=new Invoice("in_trial",BillingRecord.Kind.INITIAL,s.billingPrice(),null,trial,0,"EUR",SettlementKind.ZERO,List.of());
        var cash=new Invoice("in_renewal",BillingRecord.Kind.RENEWAL,s.billingPrice(),null,paid,1000,"EUR",SettlementKind.CASH,List.of(new CashObject("pi_renewal",1000,"EUR",dev.genericpayments.platform.payments.domain.PaymentStatus.SUCCEEDED,NOW)));
        var evidence=new TrialEvidence(id,step,"in_trial","si_owned",trial,s.billingPrice(),null);
        var snapshot=new Snapshot(s.providerReference(),Subscription.Status.ACTIVE,s.billingPrice(),paid,trial,null,List.of(zero,cash),operation.providerOperationKey(),false,0,NOW,new OperationObservation(operation.providerOperationKey(),OperationState.APPLIED),List.of(),evidence);
        apply(snapshot,new Fence(current.revision(),id,operation.version(),null,null,observations.captureFence(current)));
        assertThat(current.billingStatus()).isEqualTo(terminal?Subscription.Status.CANCELLED:Subscription.Status.ACTIVE);assertThat(current.trial()).isEqualTo(trial);assertThat(current.paidThrough()).isEqualTo(terminal?null:paid.end());
        if(terminal){assertThat(events).isZero();verifyNoInteractions(dep(SubscriptionCallbackPort.class));}
        else{var event=org.mockito.ArgumentCaptor.forClass(SubscriptionEvent.class);verify(dep(SubscriptionEventStore.class),atLeastOnce()).append(event.capture());assertThat(event.getAllValues().getLast().type()).isEqualTo(SubscriptionEvent.Type.PERIOD_PAID);}
        verify(dep(TrialEligibilityStore.class)).consume(id,NOW);
        verify(proofs).bindTrialInTransaction(request,step,s.providerReference(),"si_owned","in_trial",trial.start().getEpochSecond());
    }
    Snapshot snapshot(Subscription.Status status,long revision){return new Snapshot(current.providerReference(),status,current.billingPrice(),current.currentPeriod(),null,null,List.of(),null,false,revision,NOW);}
    @Test void externalCancellationThenWithdrawalProjectsWithoutInventingLocalCommands(){
        var s=current;var boundary=s.paidThrough();
        var scheduled=new Snapshot(s.providerReference(),s.billingStatus(),s.billingPrice(),s.currentPeriod(),null,boundary,List.of(),null,false,0,NOW);
        apply(scheduled,fence());assertThat(current.cancellation().effectiveAt()).isEqualTo(boundary);
        apply(snapshot(Subscription.Status.ACTIVE,0),fence());
        assertThat(current.cancellation()).isNull();assertThat(current.paidThrough()).isEqualTo(s.paidThrough());
        verify(dep(SubscriptionOperationStore.class),never()).save(any(),anyLong());
        var recorded=org.mockito.ArgumentCaptor.forClass(SubscriptionEvent.class);verify(dep(SubscriptionEventStore.class),times(2)).append(recorded.capture());
        assertThat(recorded.getAllValues()).extracting(SubscriptionEvent::type).containsExactly(SubscriptionEvent.Type.CANCEL_SCHEDULED,SubscriptionEvent.Type.CANCEL_WITHDRAWN);
    }
    @Test void nullStateReadCapturedBeforeExternalSchedulingCannotClearIt(){
        var stale=fence();var old=snapshot(Subscription.Status.ACTIVE,0);var s=current;
        apply(new Snapshot(s.providerReference(),s.billingStatus(),s.billingPrice(),s.currentPeriod(),null,s.paidThrough(),List.of(),null,false,0,NOW),fence());
        assertThatThrownBy(()->apply(old,stale)).hasMessage("OBSERVATION_STALE");assertThat(current.cancellation()).isNotNull();
    }
    @Test void externalWithdrawalAtCapturedBoundaryDoesNotClearCancellation(){
        current=current.scheduleCancellation(current.paidThrough(),NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"clock",Clock.fixed(current.paidThrough(),ZoneOffset.UTC));
        apply(snapshot(Subscription.Status.ACTIVE,0),fence());assertThat(current.cancellation()).isNotNull();
    }
    @Test void externalWithdrawalCannotResolveAnUnknownLocalMutation(){
        current=current.scheduleCancellation(current.paidThrough(),NOW);var s=current;var id=UUID.randomUUID();
        var o=new SubscriptionOperation(id,s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.WITHDRAW_CANCEL,SubscriptionOperation.Status.UNKNOWN,"test","key","v1.cancel.withdraw."+s.paidThrough().getEpochSecond()+"/0."+"a".repeat(64),"subscription:"+account.id().value()+":"+id+":WITHDRAW_CANCEL",s.revision(),1,s.providerReference(),null,NOW,NOW,0);
        when(dep(SubscriptionOperationStore.class).unresolved(any(),any())).thenReturn(List.of(o));when(dep(SubscriptionOperationStore.class).lock(id)).thenReturn(o);when(dep(SubscriptionReadPreparation.class).financial(any())).thenReturn(o);
        apply(snapshot(Subscription.Status.ACTIVE,0),new Fence(s.revision(),id,o.version(),null,null,observations.captureFence(s)));
        assertThat(current.cancellation()).isNotNull();verify(dep(SubscriptionOperationStore.class),never()).save(any(),anyLong());
    }
    @Test void exactOriginalResumeCashActivatesPausedSubscription(){resumeFact("in_resume",1000,true);assertThat(current.billingStatus()).isEqualTo(Subscription.Status.ACTIVE);assertThat(current.paidThrough()).isEqualTo(NOW.plusSeconds(86400));}
    @Test void unrelatedCashCannotActivatePausedSubscription(){resumeFact("in_other",1000,true);assertThat(current.billingStatus()).isEqualTo(Subscription.Status.PAUSED);assertThat(current.paidThrough()).isNull();}
    @Test void acknowledgedResumeWithoutCashCannotActivatePausedSubscription(){resumeFact("in_resume",0,false);assertThat(current.billingStatus()).isEqualTo(Subscription.Status.PAUSED);assertThat(current.paidThrough()).isNull();}
    @Test void partialCashCannotActivatePausedSubscription(){assertThatThrownBy(()->resumeFact("in_resume",500,true)).isInstanceOf(IllegalArgumentException.class).hasMessage("Cash settlement needs matching successful objects");assertThat(current.billingStatus()).isEqualTo(Subscription.Status.PAUSED);assertThat(current.paidThrough()).isNull();}
    void resumeFact(String invoice,long captured,boolean cash){
        var s=current;current=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),null,Subscription.Status.PAUSED,s.providerReference(),null,null,null,null,null,null,null,0,s.createdAt(),s.updatedAt(),null);
        UUID id=UUID.randomUUID();var o=new SubscriptionOperation(id,s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.RESUME,SubscriptionOperation.Status.PROCESSING,"test","key","v1.resume.none."+"a".repeat(64),"subscription:"+account.id().value()+":"+id+":RESUME",0,1,s.providerReference(),null,NOW,NOW,0);
        when(dep(SubscriptionOperationStore.class).unresolved(any(),any())).thenReturn(List.of(o));when(dep(SubscriptionOperationStore.class).lock(id)).thenReturn(o);when(dep(SubscriptionReadPreparation.class).financial(any())).thenReturn(o);when(dep(BillingRecordStore.class).upsert(any())).thenAnswer(x->x.getArgument(0));
        var proofs=mock(SubscriptionBillingProofStore.class);org.springframework.test.util.ReflectionTestUtils.setField(service,"billingProofs",proofs);var period=new Subscription.Period(NOW,NOW.plusSeconds(86400));
        when(proofs.resume(account.id(),id)).thenReturn(Optional.of(new SubscriptionBillingProofStore.Resume(id,s.id(),account.id(),UUID.randomUUID(),s.providerReference(),"in_resume",period)));
        var bill=new Invoice(invoice,BillingRecord.Kind.RENEWAL,s.billingPrice(),null,period,1000,"EUR",cash?SettlementKind.CASH:SettlementKind.OPEN,cash?List.of(new CashObject("pi_resume",captured,"EUR",dev.genericpayments.platform.payments.domain.PaymentStatus.SUCCEEDED,NOW)):List.of());
        var snapshot=new Snapshot(s.providerReference(),Subscription.Status.ACTIVE,s.billingPrice(),period,null,null,List.of(bill),o.providerOperationKey(),false,0,NOW,new OperationObservation(o.providerOperationKey(),OperationState.APPLIED),List.of());
        apply(snapshot,new Fence(current.revision(),id,o.version(),null,null,observations.captureFence(current)));
    }
    Fence fence(){return new Fence(current.revision(),null,null,null,null,observations.captureFence(current));}
    void apply(Snapshot snapshot,Fence fence){service.apply(new ApplySubscriptionSnapshotUseCase.Command(account,current.id(),snapshot,SubscriptionEvent.Source.RECONCILIATION,"test","test",fence));}
    // Removing the mandatory Stripe fence enables the compatibility constructor to bypass all read ownership.
    @Test void stripeCompatibilityConstructorCannotBypassReadFence(){assertThatThrownBy(()->apply(snapshot(Subscription.Status.ACTIVE,0),null)).isInstanceOf(IllegalStateException.class);assertThat(saves).isZero();}
    @Test void stripePositiveRevisionCannotPoisonZeroReads(){assertThatThrownBy(()->apply(snapshot(Subscription.Status.ACTIVE,99),fence())).isInstanceOf(IllegalStateException.class);assertThat(observations.sequence).isZero();}
    @Test void oldFiveArgumentFenceCannotBypassSequence(){assertThatThrownBy(()->apply(snapshot(Subscription.Status.ACTIVE,0),new Fence(current.revision(),null,null,null,null))).isInstanceOf(IllegalStateException.class);}
    // Catches consuming lifecycle revision instead of the private read sequence and accepting a reversed no-op response.
    @Test void newerNoOpConsumesReadOnceWithoutInvalidatingLiveQuote(){
        var before=current;var captured=fence();var delayed=snapshot(Subscription.Status.PAUSED,0);
        var q=new SubscriptionQuote(UUID.randomUUID(),current.siteId(),current.id(),"test",current.billingPrice().priceId(),1,current.billingPrice(),ChangeTiming.NEXT_PERIOD,0,1000,"EUR",current.currentPeriod().end(),NOW.plusSeconds(300),true,current.revision(),0,"quote");
        apply(snapshot(Subscription.Status.ACTIVE,0),captured);
        assertThat(observations.sequence).isEqualTo(1);assertThat(current).isEqualTo(before);assertThat(saves).isZero();assertThat(events).isZero();
        assertThatCode(()->PlanChangeRules.requireQuoteCurrent(q,current.revision(),0,NOW)).doesNotThrowAnyException();
        assertThatThrownBy(()->apply(delayed,captured)).isInstanceOf(IllegalStateException.class).hasMessage("OBSERVATION_STALE");assertThat(current).isEqualTo(before);
    }
    @Test void changedLocalRevisionStillRejectsMatchingObservationSequence(){var f=new Fence(current.revision()-1,null,null,null,null,0L);assertThatThrownBy(()->apply(snapshot(Subscription.Status.PAUSED,0),f)).hasMessage("OBSERVATION_STALE");assertThat(observations.sequence).isZero();}
    @Test void foreignOperationDoesNotConsumeSequence(){var f=new Fence(current.revision(),UUID.randomUUID(),0L,null,null,0L);assertThatThrownBy(()->apply(snapshot(Subscription.Status.PAUSED,0),f)).hasMessage("OBSERVATION_STALE");assertThat(observations.sequence).isZero();}
    @Test void fakeEqualAndOlderRevisionsKeepOriginalProjectionRules(){
        account=PaymentAccount.create(current.paymentAccountId(),"fake",PaymentProviderType.FAKE,PaymentAccountMode.SANDBOX,"fake","fake");
        apply(snapshot(Subscription.Status.ACTIVE,10),null);apply(snapshot(Subscription.Status.ACTIVE,10),null);apply(snapshot(Subscription.Status.PAUSED,9),null);
        assertThat(current.billingStatus()).isEqualTo(Subscription.Status.ACTIVE);assertThat(saves).isZero();assertThat(observations.sequence).isZero();
        apply(snapshot(Subscription.Status.PAUSED,11),null);assertThat(current.billingStatus()).isEqualTo(Subscription.Status.PAUSED);
    }
    static class MemoryObservations implements SubscriptionObservationPort {
        long sequence;long provider=-1;
        public long captureFence(Subscription s){return sequence;}
        public void consumeFence(Subscription s,long expected){if(expected!=sequence)throw new IllegalStateException("OBSERVATION_STALE");sequence++;}
        public boolean acceptRevision(Subscription s,long revision){if(revision<provider)return false;provider=revision;return true;}
    }
}
