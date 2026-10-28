package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** Hosted PostgreSQL proof: no Stripe API or credentials; all rows are fresh test fixture data. */
class StripeBillingObservationIntegrationTest extends SubscriptionFactFixture {
    @Autowired SubscriptionObservationPort observations;
    @Autowired SubscriptionCatalogPort catalog;
    @Autowired SubscriptionReadPreparation readPreparation;
    @Autowired SubscriptionPlanTransaction plans;
    @Autowired SubscriptionSweepPort sweep;
    Subscription original;
    @BeforeEach void stripeFixture(){
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_fixture',credentials_ref='stripe-sandbox-default' where id=?",account.id().value());
        account=accounts.findById(account.id()).orElseThrow();var terms=catalog.terms(site,price);
        original=tx.required(()->subscriptions.save(new Subscription(UUID.randomUUID(),site,customer.id(),terms.productId(),account.id(),terms,terms,Subscription.Status.ACTIVE,"sub_fixture",
            new Subscription.Period(INITIAL_TIME.minusSeconds(3600),INITIAL_TIME.plusSeconds(86400)),INITIAL_TIME.plusSeconds(86400),null,null,null,null,null,0,INITIAL_TIME,INITIAL_TIME,null),-1));
    }
@Test void sequenceDefaultsAndConstraintAreIndependentOfLifecycle(){
    assertThat(sequence()).isZero();
    assertThatThrownBy(()->jdbc.update("update subscriptions set observation_sequence=-1 where id=?",original.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(sequence()).isZero();assertThat(sub(original.id())).isEqualTo(original);
    assertThatThrownBy(()->observations.consumeFence(original,0)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
}
    Fence capture(){var s=sub(original.id());var op=readPreparation.financial(s);return new Fence(s.revision(),op==null?null:op.id(),op==null?null:op.version(),null,null,observations.captureFence(s));}
    Snapshot snapshot(Subscription.Status status,List<Invoice> invoices){var s=sub(original.id());return new Snapshot(s.providerReference(),status,s.billingPrice(),s.currentPeriod(),s.trial(),null,invoices,null,false,0,INITIAL_TIME);}
    void apply(Snapshot snapshot,Fence fence){tx.required(()->{facts.apply(new ApplySubscriptionSnapshotUseCase.Command(account,original.id(),snapshot,SubscriptionEvent.Source.RECONCILIATION,UUID.randomUUID().toString(),"stripe-observation",fence));return null;});}
    long sequence(){return jdbc.queryForObject("select observation_sequence from subscriptions where id=?",Long.class,original.id());}
    Invoice invoice(String ref,SubscriptionPriceTerms terms,Subscription.Period period,SettlementKind settlement){return new Invoice(ref,BillingRecord.Kind.RENEWAL,terms,null,period,terms.regularAmount().minorUnits(),"EUR",settlement,settlement==SettlementKind.CASH?List.of(new CashObject("pi_"+ref,terms.regularAmount().minorUnits(),"EUR",PaymentStatus.SUCCEEDED,INITIAL_TIME)):List.of());}
    @Test void reversedNoOpResponseConsumesOnlyPrivateSequence(){
        var fence=capture();var delayed=snapshot(Subscription.Status.PAUSED,List.of());var before=sub(original.id());int eventCount=count("subscription_events"),outbox=count("outbox_events");
        apply(snapshot(Subscription.Status.ACTIVE,List.of()),fence);
        assertThat(sequence()).isEqualTo(1);assertThat(sub(original.id())).isEqualTo(before);assertThat(count("subscription_events")).isEqualTo(eventCount);assertThat(count("outbox_events")).isEqualTo(outbox);
        assertThatThrownBy(()->apply(delayed,fence)).hasMessage("OBSERVATION_STALE");assertThat(sub(original.id())).isEqualTo(before);
    }
    @Test void rejectedProjectionRollsBackConsumedSequenceAndAllFacts(){
        var fence=capture();var good=snapshot(Subscription.Status.ACTIVE,List.of());
        var malformed=new Snapshot("sub_foreign",good.status(),good.billingPrice(),good.period(),null,null,List.of(),null,false,0,INITIAL_TIME);
        assertThatThrownBy(()->apply(malformed,fence)).isInstanceOf(IllegalArgumentException.class);assertThat(sequence()).isZero();assertThat(count("payments")).isZero();
        apply(good,fence);assertThat(sequence()).isEqualTo(1);
    }
    @Test void casIsSingleUseAcrossTransactions() throws Exception {
        var barrier=new CyclicBarrier(2);try(var threads=Executors.newVirtualThreadPerTaskExecutor()){
            Callable<Boolean> task=()->{barrier.await();try{tx.requiresNew(()->{observations.consumeFence(original,0);return null;});return true;}catch(IllegalStateException stale){return false;}};
            var first=threads.submit(task);var second=threads.submit(task);assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }assertThat(sequence()).isEqualTo(1);assertThat(sub(original.id())).isEqualTo(original);
    }
    @Test void captureAndConsumptionRequireExactOwnerAndLocalRevision(){
        var foreign=new Subscription(original.id(),original.siteId(),original.customerId(),original.productId(),new PaymentAccountId(UUID.randomUUID()),original.billingPrice(),original.entitledPrice(),original.billingStatus(),original.providerReference(),original.currentPeriod(),original.paidThrough(),null,null,null,null,null,original.revision(),INITIAL_TIME,INITIAL_TIME,null);
        assertThatThrownBy(()->observations.captureFence(foreign)).hasMessage("OBSERVATION_STALE");
        assertThatThrownBy(()->tx.required(()->{observations.consumeFence(foreign,0);return null;})).hasMessage("OBSERVATION_STALE");
        assertThatThrownBy(()->apply(snapshot(Subscription.Status.ACTIVE,List.of()),new Fence(99,null,null,null,null,0L))).hasMessage("OBSERVATION_STALE");assertThat(sequence()).isZero();
    }
    @Test void missingOrPositiveRevisionFenceCannotBypassStripeContract(){
        var good=snapshot(Subscription.Status.ACTIVE,List.of());assertThatThrownBy(()->apply(good,null)).hasMessage("OBSERVATION_STALE");
        assertThatThrownBy(()->apply(good,new Fence(original.revision(),null,null,null,null))).hasMessage("OBSERVATION_STALE");
        var bad=new Snapshot(good.subscriptionReference(),good.status(),good.billingPrice(),good.period(),null,null,List.of(),null,false,10,INITIAL_TIME);
        assertThatThrownBy(()->apply(bad,capture())).hasMessage("OBSERVATION_STALE");assertThat(sequence()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"paid","plan","cancel","grace"})
    void equalTimestampDelayedResponseCannotReverseAcceptedFacts(String change){
        var fence=capture();var delayed=snapshot(Subscription.Status.ACTIVE,List.of());Snapshot fresh;
        var next=new Subscription.Period(INITIAL_TIME,INITIAL_TIME.plusSeconds(30*86400));
        if(change.equals("cancel"))fresh=snapshot(Subscription.Status.CANCELLED,List.of());
        else if(change.equals("grace")){var due=invoice("in_due",original.billingPrice(),next,SettlementKind.OPEN);fresh=new Snapshot(original.providerReference(),Subscription.Status.PAST_DUE,original.billingPrice(),next,null,null,List.of(due),null,false,0,INITIAL_TIME);}
        else{var terms=change.equals("plan")?target():original.billingPrice();var paid=invoice("in_paid",terms,next,SettlementKind.CASH);fresh=new Snapshot(original.providerReference(),Subscription.Status.ACTIVE,terms,next,null,null,List.of(paid),null,false,0,INITIAL_TIME);}
        apply(fresh,fence);var accepted=sub(original.id());int payments=count("payments"),events=count("subscription_events"),outbox=count("outbox_events");
        assertThatThrownBy(()->apply(delayed,fence)).hasMessage("OBSERVATION_STALE");assertThat(sub(original.id())).isEqualTo(accepted);assertThat(count("payments")).isEqualTo(payments);assertThat(count("subscription_events")).isEqualTo(events);assertThat(count("outbox_events")).isEqualTo(outbox);
        if(change.equals("grace")){var deadline=accepted.debt().graceDeadline();apply(fresh,capture());assertThat(sub(original.id()).debt().graceDeadline()).isEqualTo(deadline);}
    }
    @Test void liveQuoteAndReservedPlanSurviveUnchangedRead(){
        var target=target();var context=context("plan-change");var query=new PreviewSubscriptionChangeUseCase.Query(context,original.id(),target.priceId(),ChangeTiming.NEXT_PERIOD);
        var candidate=plans.candidate(context,original.id());var preview=plans.preview(query,candidate,true);
        var quote=plans.savePreview(query,preview,new ProviderQuote("quote_fixture",0,target.regularAmount().minorUnits(),"EUR",original.currentPeriod().end(),INITIAL_TIME.plusSeconds(300),true),ChangeTiming.NEXT_PERIOD,true);
        apply(snapshot(Subscription.Status.ACTIVE,List.of()),capture());assertThat(sub(original.id()).revision()).isEqualTo(quote.subscriptionRevision());
        var reserved=plans.reserve(context,original.id(),quote.id(),candidate,true,false);var before=sub(original.id());
        apply(snapshot(Subscription.Status.ACTIVE,List.of()),capture());assertThat(sub(original.id())).isEqualTo(before);
        assertThatCode(()->plans.beforeMutation(context,reserved,true)).doesNotThrowAnyException();assertThat(op(reserved.operation().id()).status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
    }
    @Test void changedOperationVersionAndLeaseCannotUseReadFence(){
        var id=UUID.randomUUID();var operation=new SubscriptionOperation(id,site,customer.id(),original.id(),SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Status.RESERVED,"test","key","fingerprint","setup",original.revision(),0,original.providerReference(),null,INITIAL_TIME,INITIAL_TIME,0);
        tx.required(()->operations.claim(operation));var fence=capture();
        jdbc.update("update subscription_operations set version=version+1 where id=?",id);
        assertThatThrownBy(()->apply(snapshot(Subscription.Status.ACTIVE,List.of()),fence)).hasMessage("OBSERVATION_STALE");assertThat(sequence()).isZero();
        var current=capture();var staleLease=new SubscriptionSweepPort.Claim(site,original.id(),"foreign",UUID.randomUUID(),original.revision());
        assertThatThrownBy(()->apply(snapshot(Subscription.Status.ACTIVE,List.of()),new Fence(current.revision(),current.operationId(),current.operationVersion(),staleLease,null,current.observationSequence()))).hasMessage("CLAIM_LOST");assertThat(sequence()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"PARTIALLY_REFUNDED","REFUNDED"})
    void grossCashReplayDoesNotDowngradeRefundOrBlockRenewal(String refunded){
        var history=invoice("in_history",original.billingPrice(),original.currentPeriod(),SettlementKind.CASH);apply(snapshot(Subscription.Status.ACTIVE,List.of(history)),capture());
        long amount=refunded.equals("REFUNDED")?2000:500;
        jdbc.update("update payments set status=?,refunded_amount_minor=? where provider_payment_reference='pi_in_history'",refunded,amount);
        var next=new Subscription.Period(INITIAL_TIME,INITIAL_TIME.plusSeconds(30*86400));var renewal=invoice("in_renewal",original.billingPrice(),next,SettlementKind.CASH);
        apply(new Snapshot(original.providerReference(),Subscription.Status.ACTIVE,original.billingPrice(),next,null,null,List.of(history,renewal),null,false,0,INITIAL_TIME),capture());
        assertThat(count("payments")).isEqualTo(2);assertThat(jdbc.queryForObject("select status from payments where provider_payment_reference='pi_in_history'",String.class)).isEqualTo(refunded);
        assertThat(jdbc.queryForObject("select refunded_amount_minor from payments where provider_payment_reference='pi_in_history'",Long.class)).isEqualTo(amount);assertThat(sub(original.id()).paidThrough()).isEqualTo(next.end());
    }
    @Test void freshTerminalReadImportsHistoryWithoutRestoringAccess(){
        apply(snapshot(Subscription.Status.CANCELLED,List.of()),capture());var cancelled=sub(original.id());
        var history=invoice("in_history",original.billingPrice(),original.currentPeriod(),SettlementKind.CASH);apply(snapshot(Subscription.Status.ACTIVE,List.of(history)),capture());
        assertThat(sub(original.id())).isEqualTo(cancelled);assertThat(count("payments")).isEqualTo(1);
    }
    @Test void fakeMaxEqualSemanticsRemainIndependent(){
        jdbc.update("update payment_accounts set provider='FAKE' where id=?",account.id().value());account=accounts.findById(account.id()).orElseThrow();
        var active=new Snapshot(original.providerReference(),Subscription.Status.ACTIVE,original.billingPrice(),original.currentPeriod(),null,null,List.of(),null,false,10,INITIAL_TIME);
        apply(active,null);apply(active,null);apply(new Snapshot(original.providerReference(),Subscription.Status.PAUSED,original.billingPrice(),original.currentPeriod(),null,null,List.of(),null,false,9,INITIAL_TIME),null);
        assertThat(sub(original.id())).isEqualTo(original);assertThat(sequence()).isZero();assertThat(jdbc.queryForObject("select observed_provider_revision from subscriptions where id=?",Long.class,original.id())).isEqualTo(10);
    }
    SubscriptionCommandContext context(String key){return new SubscriptionCommandContext(actor,site,customer.id(),"https://facts.test",key,"stripe-test");}
    SubscriptionPriceTerms target(){
        UUID id=UUID.randomUUID();jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,?,1,'SUBSCRIPTION',3000,'EUR','MONTH',0,'OPTIONAL_DURING_TRIAL','NONE','PUBLISHED',?,?,?)",id,site.value(),original.productId().value(),"target-"+id,Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME));
        var result=catalog.terms(site,new PriceId(id));policies.saveTransition(new PlanTransition(site,original.productId(),original.billingPrice().priceId(),result.priceId(),PlanTransition.Direction.UPGRADE,true,policies.load(site).version()));return result;
    }
}
