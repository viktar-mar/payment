package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import java.util.*;
import org.junit.jupiter.api.Test;

class SubscriptionFactsIntegrationTest extends SubscriptionFactFixture {
    @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext context;
    @Test void trialReservationMustBeRevalidatedAfterCandidateReadBeforeAnyGrant(){
        price=price(3);var r=start();var snapshot=retrieve(r);var firstRead=new java.util.concurrent.atomic.AtomicBoolean(true);
        var observed=(dev.genericpayments.platform.subscriptions.application.port.out.TrialEligibilityStore)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{dev.genericpayments.platform.subscriptions.application.port.out.TrialEligibilityStore.class},(proxy,method,args)->{
            var result=method.invoke(trials,args);
            // Deterministic stale-read boundary: candidate ownership ceases before lifecycle locks.
            if(method.getName().equals("find")&&firstRead.getAndSet(false))jdbc.update("update subscription_trial_eligibility set reserved_by_operation=null where reserved_by_operation=?",r.operationId());
            return result;
        });
        var constructor=SubscriptionFactService.class.getConstructors()[0];var dependencies=Arrays.stream(constructor.getParameterTypes()).map(type->type==dev.genericpayments.platform.subscriptions.application.port.out.TrialEligibilityStore.class?observed:context.getBean(type)).toArray();
        final SubscriptionFactService service;
        try{service=(SubscriptionFactService)constructor.newInstance(dependencies);}catch(ReflectiveOperationException exception){throw new AssertionError(exception);}
        assertThatThrownBy(()->tx.required(()->{service.apply(new ApplySubscriptionSnapshotUseCase.Command(account,r.subscriptionId(),snapshot,SubscriptionEvent.Source.RECONCILIATION,"stale-owner","test"));return null;})).isInstanceOf(IllegalArgumentException.class);
        assertThat(sub(r.subscriptionId()).trial()).isNull();assertThat(count("subscription_events")).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=SubscriptionOperation.Kind.class,names={"CHANGE","CANCEL","WITHDRAW_CANCEL","RECOVER","CLEAR_CHANGE","RESUME"})
    void unrelatedHistoricalPaidPeriodDoesNotConfirmAnotherMutation(SubscriptionOperation.Kind kind){
        var r=start();var s=retrieve(r);apply(r.subscriptionId(),s,"paid");
        var o=new SubscriptionOperation(UUID.randomUUID(),site,customer.id(),r.subscriptionId(),kind,SubscriptionOperation.Status.RESERVED,"internal","pending","pending","pending-original",sub(r.subscriptionId()).revision(),0,s.subscriptionReference(),null,INITIAL_TIME,INITIAL_TIME,0);
        tx.required(()->operations.claim(o));
        var unsupported=new Snapshot(s.subscriptionReference(),s.status(),s.billingPrice(),s.period(),null,null,s.invoices(),s.operationKey(),true,10,INITIAL_TIME,new OperationObservation(o.providerOperationKey(),OperationState.APPLIED));
        apply(r.subscriptionId(),unsupported,"unrelated");assertThat(op(o.id()).status()).isEqualTo(SubscriptionOperation.Status.RESERVED);
    }
    @Test void transportTimeoutReconcilesOriginalCreateKeyWithoutSecondProviderMutation(){
        var reserved=commands.reserveCreate(command("timeout"));var s=reserved.subscription();var o=reserved.operation();
        var provider=providers.forAccount(account);
        provider.create(new Command(account,o,s.billingPrice(),null,null,null,null,true,CreateSubscriptionFingerprint.startsTrial(o.fingerprint())));
        // Provider committed, but its response was lost. Local receipt has no reference.
        commands.recordReply(o.id(),new Reply(Outcome.UNKNOWN,null,null));
        var observed=provider.retrieve(new Lookup(account,s.id(),null,o.providerOperationKey()));
        apply(s.id(),observed,"original-key");apply(s.id(),provider.retrieve(new Lookup(account,s.id(),null,o.providerOperationKey())),"restart");
        assertThat(op(o.id()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);assertThat(sub(s.id()).paidThrough()).isNotNull();
        assertThat(count("fake_subscription_operations")).isEqualTo(1);assertThat(count("payments")).isEqualTo(1);
    }
    @Test void setupAppliedMarkerNeedsActualReadyMethodNotHistoricalPaidPeriod(){
        var r=start();var s=retrieve(r);apply(r.subscriptionId(),s,"paid");var setup=setup(r.subscriptionId());
        var notReady=new Snapshot(s.subscriptionReference(),s.status(),s.billingPrice(),s.period(),null,null,s.invoices(),setup.providerOperationKey(),false,10,INITIAL_TIME,new OperationObservation(setup.providerOperationKey(),OperationState.APPLIED));
        apply(r.subscriptionId(),notReady,"not-ready");
        assertThat(op(setup.id()).status()).isEqualTo(SubscriptionOperation.Status.RESERVED);
        var ready=new Snapshot(s.subscriptionReference(),s.status(),s.billingPrice(),s.period(),null,null,s.invoices(),setup.providerOperationKey(),true,11,INITIAL_TIME,new OperationObservation(setup.providerOperationKey(),OperationState.APPLIED));
        apply(r.subscriptionId(),ready,"ready");assertThat(op(setup.id()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
    }
    @Test void invoiceCanProgressThroughUnsupportedThenOpenThenCashWithoutLosingVersion(){
        var r=start();var s=retrieve(r);var invoice=s.invoices().getFirst();
        long revision=10;
        for(var settlement:List.of(SettlementKind.UNSUPPORTED,SettlementKind.OPEN,SettlementKind.CASH)){
            var observed=new Invoice(invoice.reference(),invoice.kind(),invoice.price(),null,invoice.period(),invoice.amountDueMinor(),invoice.currency(),settlement,settlement==SettlementKind.CASH?invoice.cashObjects():List.of());
            var snap=new Snapshot(s.subscriptionReference(),s.status(),s.billingPrice(),s.period(),null,null,List.of(observed),s.operationKey(),true,revision++,INITIAL_TIME,s.operationObservation());
            assertThatCode(()->apply(r.subscriptionId(),snap,"progress")).doesNotThrowAnyException();
        }
        assertThat(sub(r.subscriptionId()).paidThrough()).isEqualTo(s.period().end());
    }
    @Test void maximumSafeCashReferenceRemainsImportableAndDeduplicated(){
        var r=start();var s=retrieve(r);var i=s.invoices().getFirst();
        var invoice=new Invoice(i.reference(),i.kind(),i.price(),null,i.period(),2000,"EUR",SettlementKind.CASH,List.of(new CashObject("x".repeat(255),2000,"EUR",PaymentStatus.SUCCEEDED,INITIAL_TIME)));
        var snapshot=new Snapshot(s.subscriptionReference(),s.status(),s.billingPrice(),s.period(),null,null,List.of(invoice),s.operationKey(),true,10,INITIAL_TIME,s.operationObservation());
        assertThatCode(()->apply(r.subscriptionId(),snapshot,"cash")).doesNotThrowAnyException();apply(r.subscriptionId(),snapshot,"replay");assertThat(count("payments")).isEqualTo(1);
    }
    @Test void confirmedZeroConditionalProrationAppliesExactPendingTargetWithoutPayment(){
        var r=start();var initial=retrieve(r);apply(r.subscriptionId(),initial,"initial-paid");var s=sub(r.subscriptionId());var at=s.paidThrough().minusSeconds(1);TestClock.set(at);
        var targetId=UUID.randomUUID();jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) select ?,site_id,product_id,?,1,price_type,2001,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at from prices where id=?",targetId,"p-"+targetId,price.value());
        var target=new SubscriptionPriceTerms(new dev.genericpayments.platform.catalog.domain.PriceId(targetId),"p-"+targetId,1,s.productId(),new dev.genericpayments.platform.catalog.domain.Money(2001,"EUR"),s.billingPrice().interval(),0,s.billingPrice().trialPolicy(),s.billingPrice().taxMode());
        var operation=new SubscriptionOperation(UUID.randomUUID(),site,customer.id(),s.id(),SubscriptionOperation.Kind.CHANGE,SubscriptionOperation.Status.RESERVED,"internal","change","change","change-exact",s.revision(),0,s.providerReference(),null,at,at,0);
        tx.required(()->{operations.claim(operation);subscriptions.save(new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),new Subscription.Change(operation.id(),target,at,Subscription.ChangeState.PAYMENT_PENDING),s.debt(),s.terminalReason(),s.revision()+1,s.createdAt(),at,s.lastReconciledAt()),s.revision());return null;});
        var period=new Subscription.Period(at,s.paidThrough());var zero=new Invoice("zero-proration",BillingRecord.Kind.PRORATION,target,s.billingPrice(),period,0,"EUR",SettlementKind.ZERO,List.of());
        var snapshot=new Snapshot(s.providerReference(),Subscription.Status.ACTIVE,target,period,null,null,List.of(zero),operation.providerOperationKey(),true,10,at,new OperationObservation(operation.providerOperationKey(),OperationState.APPLIED));
        apply(s.id(),snapshot,"unlinked-zero-change");
        assertThat(sub(s.id()).entitledPrice()).as("An operation marker without its durable exact quote is not authority").isEqualTo(s.entitledPrice());
        var quotes= new dev.genericpayments.platform.subscriptions.adapter.out.persistence.SubscriptionQuotePersistenceAdapter(jdbc,java.time.Clock.fixed(at,java.time.ZoneOffset.UTC));
        var quote=new SubscriptionQuote(UUID.randomUUID(),site,s.id(),"test-authority",s.billingPrice().priceId(),s.billingPrice().version(),target,ChangeTiming.IMMEDIATE,0,2001,"EUR",at,at.plusSeconds(60),true,s.revision(),0,"zero-quote");
        tx.required(()->{quotes.save(quote);quotes.bind(operation,quote);return null;});
        apply(s.id(),snapshot,"zero-change");
        assertThat(sub(s.id()).entitledPrice()).isEqualTo(target);assertThat(sub(s.id()).pendingChange()).isNull();assertThat(count("payments")).isEqualTo(1);assertThat(op(operation.id()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        int events=count("subscription_events");apply(s.id(),snapshot,"restart");assertThat(count("subscription_events")).isEqualTo(events);
    }
    @Test void terminalObservationOfAlreadyStartedTrialConsumesReservationRatherThanReleasingIt(){
        price=price(3);var r=start();var s=retrieve(r);TestClock.set(s.trial().end());
        apply(r.subscriptionId(),new Snapshot(s.subscriptionReference(),Subscription.Status.CANCELLED,s.billingPrice(),s.period(),s.trial(),null,List.of(),s.operationKey(),false,10,s.trial().end(),s.operationObservation()),"ended-trial");
        assertThat(sub(r.subscriptionId()).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);assertThat(trials.find(site,customer.id(),s.billingPrice().productId()).orElseThrow().consumedAt()).isNotNull();assertThat(count("payments")).isZero();
    }
    @Test void renewalDebtCapturesPolicyOnceAndInitialDebtCapturesNullGrace(){
        var r=start();var s=retrieve(r);apply(r.subscriptionId(),s,"initial");var end=s.period().end();TestClock.set(end);
        var period=new Subscription.Period(end,end.plusSeconds(86400));
        var invoice=new Invoice("renewal-failed",BillingRecord.Kind.RENEWAL,s.billingPrice(),null,period,2000,"EUR",SettlementKind.OPEN,List.of(new CashObject("renewal-attempt",2000,"EUR",PaymentStatus.FAILED,end)));
        var failed=new Snapshot(s.subscriptionReference(),Subscription.Status.PAST_DUE,s.billingPrice(),period,null,null,List.of(invoice),s.operationKey(),true,10,end);
        apply(r.subscriptionId(),failed,"failure");
        assertThat(sub(r.subscriptionId()).debt()).isNotNull();assertThat(sub(r.subscriptionId()).debt().graceDeadline()).isEqualTo(end.plusSeconds(3*86400));
        var captured=jdbc.queryForMap("select captured_policy_version,grace_deadline from subscription_billing_records where provider_invoice_reference='renewal-failed'");
        assertThat(captured.get("captured_policy_version")).isEqualTo(policies.load(site).version());
        var p=policies.load(site);policies.save(new SubscriptionPolicy(site,p.version(),true,9,false,false,false,Set.of()),p.version());
        long revision=sub(r.subscriptionId()).revision();apply(r.subscriptionId(),failed,"repeat");
        assertThat(sub(r.subscriptionId()).revision()).isEqualTo(revision);assertThat(jdbc.queryForMap("select captured_policy_version,grace_deadline from subscription_billing_records where provider_invoice_reference='renewal-failed'")).isEqualTo(captured);
    }
    @Test void initialFailureHasCapturedNullGraceAndRollsMetadataBackWithProjection(){
        var r=start();var s=retrieve(r);var invoice=s.invoices().getFirst();
        var failed=new Snapshot(s.subscriptionReference(),Subscription.Status.PAST_DUE,s.billingPrice(),s.period(),null,null,List.of(new Invoice("initial-failure",BillingRecord.Kind.INITIAL,s.billingPrice(),null,invoice.period(),2000,"EUR",SettlementKind.OPEN,List.of(new CashObject("initial-attempt",2000,"EUR",PaymentStatus.FAILED,INITIAL_TIME)))),s.operationKey(),true,10,INITIAL_TIME);
        assertThatThrownBy(()->tx.required(()->{facts.apply(new ApplySubscriptionSnapshotUseCase.Command(account,r.subscriptionId(),failed,SubscriptionEvent.Source.RECONCILIATION,"failed","test"));throw new IllegalStateException("rollback");})).hasMessage("rollback");
        assertThat(count("subscription_billing_records")).isZero();apply(r.subscriptionId(),failed,"initial-debt");
        assertThat(sub(r.subscriptionId()).debt()).isNotNull();assertThat(sub(r.subscriptionId()).debt().graceDeadline()).isNull();
        assertThat(jdbc.queryForObject("select captured_policy_version from subscription_billing_records",Long.class)).isNotNull();
        assertThat(new SubscriptionAccessPolicy().evaluate(sub(r.subscriptionId()),INITIAL_TIME).allowed()).isFalse();
    }
    @Test void newerPauseAndCancellationScheduleCannotBeReversedByStaleCash(){
        var r=start();var s=retrieve(r);apply(r.subscriptionId(),s,"paid");
        var paused=new Snapshot(s.subscriptionReference(),Subscription.Status.PAUSED,s.billingPrice(),s.period(),null,null,s.invoices(),s.operationKey(),true,20,INITIAL_TIME);
        apply(r.subscriptionId(),paused,"paused");assertThat(sub(r.subscriptionId()).billingStatus()).isEqualTo(Subscription.Status.PAUSED);
        long revision=sub(r.subscriptionId()).revision();apply(r.subscriptionId(),s,"stale");assertThat(sub(r.subscriptionId()).billingStatus()).isEqualTo(Subscription.Status.PAUSED);assertThat(sub(r.subscriptionId()).revision()).isEqualTo(revision);
        var scheduled=new Snapshot(s.subscriptionReference(),Subscription.Status.ACTIVE,s.billingPrice(),s.period(),null,s.period().end(),s.invoices(),s.operationKey(),true,21,INITIAL_TIME);
        apply(r.subscriptionId(),scheduled,"schedule");assertThat(sub(r.subscriptionId()).cancellation().effectiveAt()).isEqualTo(s.period().end());
        apply(r.subscriptionId(),s,"stale-again");assertThat(sub(r.subscriptionId()).cancellation()).isNotNull();
    }
    @Test void zeroTrialInvoiceIsSupportedWithoutPaymentOrReconciliation(){
        price=price(3);var r=start();var s=retrieve(r);
        var zero=new Invoice("free-trial",BillingRecord.Kind.INITIAL,s.billingPrice(),null,s.trial(),0,"EUR",SettlementKind.ZERO,List.of());
        apply(r.subscriptionId(),new Snapshot(s.subscriptionReference(),s.status(),s.billingPrice(),s.period(),s.trial(),null,List.of(zero),s.operationKey(),true,s.providerRevision(),s.observedAt(),s.operationObservation()),"free");
        assertThat(count("payments")).isZero();assertThat(sub(r.subscriptionId()).trial()).isNotNull();
        assertThat(jdbc.queryForList("select event_type from subscription_events",String.class)).containsExactly("TRIAL_STARTED");
    }
    @Test void futureTrialDoesNotConsumeReservationBeforeAuthoritativeStart(){
        price=price(3);var r=start();var s=retrieve(r);var future=new Subscription.Period(INITIAL_TIME.plusSeconds(60),INITIAL_TIME.plusSeconds(3600));
        var snap=new Snapshot(s.subscriptionReference(),Subscription.Status.TRIALING,s.billingPrice(),future,future,null,List.of(),s.operationKey(),true,10,INITIAL_TIME,s.operationObservation());
        apply(r.subscriptionId(),snap,"future-trial");
        assertThat(trials.find(site,customer.id(),s.billingPrice().productId()).orElseThrow().consumedAt()).isNull();
        assertThat(sub(r.subscriptionId()).trial()).isNull();
        TestClock.set(future.start());apply(r.subscriptionId(),snap,"future-trial");
        assertThat(trials.find(site,customer.id(),s.billingPrice().productId()).orElseThrow().consumedAt()).isEqualTo(future.start());
    }
    @Test void openActualAttemptAndNewSuccessfulAttemptRemainTwoPaymentsWithoutInventedSuccess(){
        var r=start();var s=retrieve(r);var source=s.invoices().getFirst();
        var failed=new CashObject("attempt-failed",2000,"EUR",PaymentStatus.FAILED,INITIAL_TIME);
        var open=new Invoice("attempt-invoice",source.kind(),source.price(),null,source.period(),2000,"EUR",SettlementKind.OPEN,List.of(failed));
        var pending=new Snapshot(s.subscriptionReference(),Subscription.Status.INCOMPLETE,s.billingPrice(),s.period(),null,null,List.of(open),null,false,10,INITIAL_TIME,new OperationObservation(op(r.operationId()).providerOperationKey(),OperationState.PENDING));
        apply(r.subscriptionId(),pending,"open");assertThat(count("payments")).isEqualTo(1);assertThat(sub(r.subscriptionId()).paidThrough()).isNull();
        var paid=new Invoice(open.reference(),open.kind(),open.price(),null,open.period(),2000,"EUR",SettlementKind.CASH,List.of(failed,new CashObject("attempt-paid",2000,"EUR",PaymentStatus.SUCCEEDED,INITIAL_TIME)));
        var confirmed=new Snapshot(s.subscriptionReference(),Subscription.Status.ACTIVE,s.billingPrice(),s.period(),null,null,List.of(paid),s.operationKey(),true,11,INITIAL_TIME,s.operationObservation());
        apply(r.subscriptionId(),confirmed,"paid");apply(r.subscriptionId(),confirmed,"duplicate");
        assertThat(count("payments")).isEqualTo(2);assertThat(jdbc.queryForObject("select count(*) from payment_events where event_type='PAYMENT_SUCCEEDED'",Integer.class)).isEqualTo(1);
        assertThat(sub(r.subscriptionId()).billingStatus()).isEqualTo(Subscription.Status.ACTIVE);
    }
    @Test void terminalPreservedWhenCashArrivesLateAndOldSnapshotCannotReverseNewerState(){
        var r=start();var s=retrieve(r);
        var cancelled=new Snapshot(s.subscriptionReference(),Subscription.Status.CANCELLED,s.billingPrice(),null,null,null,List.of(),s.operationKey(),false,20,INITIAL_TIME);
        apply(r.subscriptionId(),cancelled,"cancelled");long revision=sub(r.subscriptionId()).revision();
        apply(r.subscriptionId(),s,"late-cash");assertThat(count("payments")).isEqualTo(1);assertThat(sub(r.subscriptionId()).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);assertThat(sub(r.subscriptionId()).paidThrough()).isNull();assertThat(sub(r.subscriptionId()).revision()).isEqualTo(revision);
    }
    @Test void referencedFailedCreateRetainsReservationThenLaterSetupStartConsumesOriginalOwner(){
        price=price(3);var reserved=commands.reserveCreate(command("failed-trial"));var id=reserved.subscription().id();
        commands.recordReply(reserved.operation().id(),new Reply(Outcome.DECLINED,"trial-contract",null));
        var terms=sub(id).billingPrice();
        var absent=new Snapshot("trial-contract",Subscription.Status.INCOMPLETE,terms,null,null,null,List.of(),null,false,1,INITIAL_TIME);
        apply(id,absent,"absent");assertThat(trials.find(site,customer.id(),terms.productId()).orElseThrow().reservedByOperation()).isEqualTo(reserved.operation().id());
        var setup=setup(id);
        var period=new Subscription.Period(INITIAL_TIME,INITIAL_TIME.plusSeconds(86400));
        var started=new Snapshot("trial-contract",Subscription.Status.TRIALING,terms,period,period,null,List.of(),setup.providerOperationKey(),true,2,INITIAL_TIME,new OperationObservation(setup.providerOperationKey(),OperationState.APPLIED));
        apply(id,started,"actual-start");apply(id,started,"restart-duplicate");
        assertThat(op(reserved.operation().id()).status()).isEqualTo(SubscriptionOperation.Status.FAILED);
        assertThat(op(setup.id()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(trials.find(site,customer.id(),terms.productId()).orElseThrow().consumedAt()).isEqualTo(INITIAL_TIME);
        assertThat(count("subscription_events")).isEqualTo(1);assertThat(count("payments")).isZero();
    }
    @Test void referencedFailedCreateReleasesOnlyAtDefinitiveTerminalPreStartObservation(){
        price=price(3);var reserved=commands.reserveCreate(command("terminal-trial"));var id=reserved.subscription().id();
        commands.recordReply(reserved.operation().id(),new Reply(Outcome.DECLINED,"trial-terminal",null));
        apply(id,new Snapshot("trial-terminal",Subscription.Status.CANCELLED,sub(id).billingPrice(),null,null,null,List.of(),null,false,1,INITIAL_TIME),"terminal");
        assertThat(trials.find(site,customer.id(),sub(id).productId()).orElseThrow().reservedByOperation()).isNull();
        assertThat(op(reserved.operation().id()).status()).isEqualTo(SubscriptionOperation.Status.FAILED);
    }
    @Test void invoiceLessUnknownPositiveDeclineClosesOnlyOperationNotLiveContract(){
        var reserved=commands.reserveCreate(command("unknown"));var id=reserved.subscription().id();commands.recordReply(reserved.operation().id(),new Reply(Outcome.UNKNOWN,null,null));
        var declined=new Snapshot("unknown-contract",Subscription.Status.INCOMPLETE,sub(id).billingPrice(),null,null,null,List.of(),null,false,2,INITIAL_TIME,new OperationObservation(reserved.operation().providerOperationKey(),OperationState.DECLINED));
        apply(id,declined,"declined");int count=count("subscription_events");apply(id,declined,"repeat");
        assertThat(op(reserved.operation().id()).status()).isEqualTo(SubscriptionOperation.Status.FAILED);assertThat(sub(id).nonTerminal()).isTrue();assertThat(count("payments")).isZero();assertThat(count("subscription_events")).isEqualTo(count);
    }
    private SubscriptionOperation setup(UUID subscription){
        var o=new SubscriptionOperation(UUID.randomUUID(),site,customer.id(),subscription,SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Status.RESERVED,"internal","digest","fingerprint","setup-original",sub(subscription).revision(),0,sub(subscription).providerReference(),null,INITIAL_TIME,INITIAL_TIME,0);
        return tx.required(()->operations.claim(o).operation());
    }
    @Test void authoritativeCashCommitsOnePaymentAndEntitlementAndDuplicateObservationDoesNothing(){
        var r=start();var snapshot=retrieve(r);apply(r.subscriptionId(),snapshot,"one");
        assertThat(sub(r.subscriptionId()).billingStatus()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(count("payments")).isEqualTo(1);assertThat(count("payment_events")).isEqualTo(1);
        int events=count("subscription_events");long revision=sub(r.subscriptionId()).revision();
        apply(r.subscriptionId(),snapshot,"different-hint");
        assertThat(count("subscription_events")).isEqualTo(events);assertThat(sub(r.subscriptionId()).revision()).isEqualTo(revision);
        assertThat(op(r.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
    }
    @Test void trialConsumesActualOriginalReservationWithoutPayment(){
        price=price(3);var r=start();assertThat(trials.find(site,customer.id(),sub(r.subscriptionId()).productId()).orElseThrow().consumedAt()).isNull();
        apply(r.subscriptionId(),retrieve(r),"trial");assertThat(sub(r.subscriptionId()).billingStatus()).isEqualTo(Subscription.Status.TRIALING);
        assertThat(count("payments")).isZero();assertThat(trials.find(site,customer.id(),sub(r.subscriptionId()).productId()).orElseThrow().consumedAt()).isEqualTo(INITIAL_TIME);
    }
    @Test void rollbackAndMandatoryBoundaryProtectCashProjectionAndJournal(){
        var r=start();var c=new ApplySubscriptionSnapshotUseCase.Command(account,r.subscriptionId(),retrieve(r),SubscriptionEvent.Source.RECONCILIATION,"rollback","task7");
        assertThatThrownBy(()->facts.apply(c)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(()->tx.required(()->{facts.apply(c);throw new IllegalStateException("rollback");})).hasMessage("rollback");
        assertThat(count("payments")).isZero();assertThat(count("subscription_billing_records")).isZero();assertThat(count("subscription_events")).isZero();
        assertThat(sub(r.subscriptionId()).billingStatus()).isEqualTo(Subscription.Status.INCOMPLETE);
        assertThat(jdbc.queryForObject("select observed_provider_revision from subscriptions where id=?",Long.class,r.subscriptionId())).isNull();
        apply(r.subscriptionId(),c.snapshot(),"retry");assertThat(count("payments")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select observed_provider_revision from subscriptions where id=?",Long.class,r.subscriptionId())).isEqualTo(c.snapshot().providerRevision());
    }
    @Test void futureConfirmedCashIsRetainedAndSameSnapshotCanGrantAtActualBoundary(){
        var r=start();var initial=retrieve(r);var terms=initial.billingPrice();var future=new Subscription.Period(INITIAL_TIME.plusSeconds(60),INITIAL_TIME.plusSeconds(3600));
        var invoice=new Invoice("future",BillingRecord.Kind.INITIAL,terms,null,future,2000,"EUR",SettlementKind.CASH,List.of(new CashObject("future-cash",2000,"EUR",PaymentStatus.SUCCEEDED,INITIAL_TIME)));
        var snap=new Snapshot(initial.subscriptionReference(),Subscription.Status.ACTIVE,terms,future,null,null,List.of(invoice),initial.operationKey(),true,10,INITIAL_TIME,initial.operationObservation());
        apply(r.subscriptionId(),snap,"future");assertThat(count("payments")).isEqualTo(1);assertThat(sub(r.subscriptionId()).paidThrough()).isNull();
        TestClock.set(future.start());apply(r.subscriptionId(),snap,"future");assertThat(sub(r.subscriptionId()).paidThrough()).isEqualTo(future.end());assertThat(count("payments")).isEqualTo(1);
    }
    @Test void unexpectedZeroRegularInvoiceRequiresReconciliationAndNeverGrantsPaidAccess(){
        var r=start();var s=retrieve(r);var i=s.invoices().getFirst();
        var zero=new Invoice(i.reference(),i.kind(),i.price(),null,i.period(),0,"EUR",SettlementKind.ZERO,List.of());
        apply(r.subscriptionId(),new Snapshot(s.subscriptionReference(),s.status(),s.billingPrice(),s.period(),null,null,List.of(zero),s.operationKey(),true,s.providerRevision(),s.observedAt(),s.operationObservation()),"zero");
        assertThat(count("payments")).isZero();assertThat(sub(r.subscriptionId()).paidThrough()).isNull();
        assertThat(jdbc.queryForList("select event_type from subscription_events",String.class)).contains("RECONCILIATION_REQUIRED");
    }
}
