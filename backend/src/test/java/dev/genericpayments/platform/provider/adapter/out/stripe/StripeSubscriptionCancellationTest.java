package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;

import com.stripe.exception.ApiConnectionException;
import com.stripe.net.RequestOptions;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.common.EmptyParam;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Lookup;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionCancellationRules;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionFixtures;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionRuleViolation;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCancellationProofStore;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;

class StripeSubscriptionCancellationTest {
    @Test
    void savedReceiptMustStillBelongToTheOriginalDispatchWindow() throws Exception {
        try(var c=new CancellationCase()){
            c.current();c.fixture.graph.http.respond("POST","/v1/subscriptions/sub_owned",c.response(false));
            assertThat(c.fixture.adapter.cancel(c.command(),false).outcome()).isEqualTo(Outcome.ACCEPTED);
            var r=c.receipts.get(c.fixture.operation.id());var step=c.fixture.steps.findById(r.step()).orElseThrow();
            c.receipts.put(r.operation(),new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCancellationProofStore.Receipt(r.operation(),r.subscription(),r.account(),r.step(),r.reference(),r.mode(),r.boundary(),r.priorOperation(),step.firstDispatchedAt().minusSeconds(1)));
            assertThatThrownBy(()->c.fixture.adapter.cancel(c.command(),false)).isInstanceOf(PaymentApplicationException.class);
        }
    }
    private static final String SUBSCRIPTION = "/v1/subscriptions/sub_owned";
    private static final long BOUNDARY = 1802678400L;

    @Test
    void originalPeriodEndDispatchStoresExactReceiptWithoutChangingAccess() throws Exception {
        try (var c = new CancellationCase()) {
            var original = c.fixture.local;
            c.current();
            c.fixture.graph.http.respond("POST", SUBSCRIPTION, c.response(false));
            assertThatCode(() -> assertThat(c.fixture.adapter.cancel(c.command(), false).outcome()).isEqualTo(Outcome.ACCEPTED)).doesNotThrowAnyException();
            var step = c.fixture.steps.only();
            assertThat(step.kind()).isEqualTo(ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
            assertThat(step.resourceId()).isEqualTo(c.fixture.operation.id());
            assertThat(step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
            assertThat(c.receipts.get(c.fixture.operation.id()).boundary()).isEqualTo(c.end);
            assertThat(c.fixture.local).isEqualTo(original);
            var post = c.fixture.graph.http.calls().getLast();
            assertThat(post.form()).containsExactlyInAnyOrderEntriesOf(Map.of("cancel_at", Long.toString(c.end.getEpochSecond()), "proration_behavior", "none"));
            assertThat(post.header("Idempotency-Key")).isEqualTo(c.fixture.operation.providerOperationKey());
            int calls = c.fixture.graph.http.calls().size();
            assertThat(c.fixture.adapter.cancel(c.command(), false).outcome()).isEqualTo(Outcome.ACCEPTED);
            assertThat(c.fixture.graph.http.calls()).hasSize(calls);
        }
    }

    @Test
    void originalWithdrawalMustReferenceTheExactOwnedTimelyCancellation() throws Exception {
        try (var c = new CancellationCase()) {
            c.current();c.fixture.graph.http.respond("POST", SUBSCRIPTION, c.response(false));
            assertThatCode(() -> c.fixture.adapter.cancel(c.command(), false)).doesNotThrowAnyException();
            var prior = c.fixture.operation.id();
            c.fixture.local = c.fixture.local.scheduleCancellation(c.end, c.now);
            c.operation(SubscriptionOperation.Kind.WITHDRAW_CANCEL, "withdraw", c.end);
            c.fixture.graph.subscription.addProperty("cancel_at", c.end.getEpochSecond());
            c.current();c.fixture.graph.http.respond("POST", SUBSCRIPTION, c.response(true));
            assertThat(c.fixture.adapter.withdrawCancel(c.command()).outcome()).isEqualTo(Outcome.ACCEPTED);
            var receipt = c.receipts.get(c.fixture.operation.id());
            assertThat(receipt.priorOperation()).isEqualTo(prior);
            assertThat(receipt.mode()).isEqualTo(SubscriptionCancellationProofStore.Mode.WITHDRAW);
            assertThat(receipt.acknowledgedAt()).isBefore(c.end);
            assertThat(c.fixture.local.cancellation().effectiveAt()).isEqualTo(c.end);
            assertThat(c.fixture.graph.http.calls().getLast().form()).containsExactlyInAnyOrderEntriesOf(Map.of("cancel_at", "", "cancel_at_period_end", "false", "proration_behavior", "none"));
        }
    }

    @Test
    void lostCancellationResponseReplaysOnlyOriginalStepAndKeyInsideWindow() throws Exception {
        try (var c = new CancellationCase()) {
            c.current();c.fixture.graph.http.disconnectNext("POST", SUBSCRIPTION);
            assertThatCode(() -> assertThat(c.fixture.adapter.cancel(c.command(), false).outcome()).isEqualTo(Outcome.UNKNOWN)).doesNotThrowAnyException();
            var first = c.fixture.steps.only();
            assertThat(c.receipts).isEmpty();
            c.current();c.fixture.graph.http.respond("POST", SUBSCRIPTION, c.response(false));
            assertThat(c.fixture.adapter.cancel(c.command(), false).outcome()).isEqualTo(Outcome.ACCEPTED);
            assertThat(c.fixture.steps.only().id()).isEqualTo(first.id());
            assertThat(c.fixture.steps.only().firstDispatchedAt()).isEqualTo(first.firstDispatchedAt());
            assertThat(c.fixture.graph.http.calls().stream().filter(x -> x.method().equals("POST"))).hasSize(2)
                .allSatisfy(x -> assertThat(x.header("Idempotency-Key")).isEqualTo(c.fixture.operation.providerOperationKey()));
        }
    }

    @Test
    void attachedScheduleWithoutPendingOwnerCannotBeReleasedOrCancelled() throws Exception {
        try (var c = new CancellationCase()) {
            c.fixture.graph.subscription.addProperty("schedule", "sub_sched_unowned");
            c.current();
            assertThatCode(() -> assertThat(c.fixture.adapter.cancel(c.command(), false).outcome()).isEqualTo(Outcome.UNKNOWN)).doesNotThrowAnyException();
            assertThat(c.fixture.graph.http.calls()).allSatisfy(x -> assertThat(x.method()).isEqualTo("GET"));
            assertThat(c.receipts).isEmpty();
        }
    }

    @Test
    void durableTimelyWithdrawalCanFirstBeObservedAfterItsCapturedDeadline() throws Exception {
        try (var c = new CancellationCase()) {
            c.current();c.fixture.graph.http.respond("POST",SUBSCRIPTION,c.response(false));
            c.fixture.adapter.cancel(c.command(),false);
            c.fixture.local=c.fixture.local.scheduleCancellation(c.end,c.now);
            c.operation(SubscriptionOperation.Kind.WITHDRAW_CANCEL,"withdraw",c.end);
            c.fixture.graph.subscription.addProperty("cancel_at",c.end.getEpochSecond());
            c.current();c.fixture.graph.http.respond("POST",SUBSCRIPTION,c.response(true));
            c.fixture.adapter.withdrawCancel(c.command());
            c.fixture.graph.subscription.add("cancel_at",com.google.gson.JsonNull.INSTANCE);
            c.fixture.graph.subscription.addProperty("cancel_at_period_end",false);
            org.springframework.test.util.ReflectionTestUtils.setField(c.fixture.adapter,"clock",Clock.fixed(c.end.plusSeconds(1),ZoneOffset.UTC));
            c.fixture.identity();c.fixture.graph.graph();
            var read=c.fixture.adapter.retrieve(new Lookup(c.fixture.account,c.fixture.local.id(),"sub_owned",c.fixture.operation.providerOperationKey()));
            assertThat(read.operationObservation()).isEqualTo(new OperationObservation(c.fixture.operation.providerOperationKey(),OperationState.APPLIED));
            assertThat(c.fixture.graph.http.requestCount("POST",SUBSCRIPTION)).isEqualTo(2);
        }
    }

    @Test
    void explicitSameKeyPublicRetryReachesTheOriginalGuardedCancellation() throws Exception {
        try (var c = new CancellationCase()) {
            c.current();c.fixture.graph.http.disconnectNext("POST",SUBSCRIPTION);
            c.fixture.adapter.cancel(c.command(),false);
            var original=c.fixture.steps.only();
            var o=c.fixture.operation;
            c.fixture.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),SubscriptionOperation.Status.UNKNOWN,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),null,o.createdAt(),c.now,o.version()+1);
            var phases=mock(dev.genericpayments.platform.subscriptions.application.service.SubscriptionCancellationTransaction.class);
            var registry=mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry.class);
            var candidate=new dev.genericpayments.platform.subscriptions.application.service.SubscriptionCancellationTransaction.Candidate(c.fixture.local,c.fixture.account,dev.genericpayments.platform.subscriptions.domain.SubscriptionPolicy.defaults(c.fixture.local.siteId()));
            var reserved=new dev.genericpayments.platform.subscriptions.application.service.SubscriptionCancellationTransaction.Reserved(c.fixture.operation,c.fixture.local,c.fixture.account,false);
            when(phases.candidate(any(),any())).thenReturn(candidate);
            when(phases.reserve(any(),any(),any(),any(),any(),any(),anyBoolean(),anyBoolean())).thenReturn(reserved);
            when(phases.result(any())).thenReturn(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult(o.id(),o.subscriptionId(),SubscriptionOperation.Status.UNKNOWN,null));
            when(phases.reply(any(),any())).thenReturn(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult(o.id(),o.subscriptionId(),SubscriptionOperation.Status.PROCESSING,null));
            when(registry.forAccount(c.fixture.account)).thenReturn(c.fixture.adapter);
            var service=new dev.genericpayments.platform.subscriptions.application.service.SubscriptionCancellationService(phases,registry);
            var context=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(null,c.fixture.local.siteId(),c.fixture.local.customerId(),"https://shop.test","original","retry");
            c.fixture.identity();c.fixture.graph.graph();c.current();c.fixture.graph.http.respond("POST",SUBSCRIPTION,c.response(false));
            service.cancel(new dev.genericpayments.platform.subscriptions.application.port.in.CancelSubscriptionUseCase.Command(context,c.fixture.local.id(),dev.genericpayments.platform.subscriptions.application.port.in.CancelSubscriptionUseCase.Mode.PERIOD_END,null));
            assertThat(c.fixture.graph.http.requestCount("POST",SUBSCRIPTION)).isEqualTo(2);
            assertThat(c.fixture.steps.only().id()).isEqualTo(original.id());
            assertThat(c.fixture.steps.only().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        }
    }

    @Test
    void safePaidImmediateCancellationSuppressesInvoiceAndProrationAndRetainsLocalMoney() throws Exception {
        try(var c=new CancellationCase()){
            c.operation(SubscriptionOperation.Kind.CANCEL,"immediate",null);
            var before=c.fixture.local;
            c.current();c.fixture.graph.http.respond("GET","/v1/invoiceitems",StripeBillingSnapshotMapperTest.page(false));
            c.fixture.graph.http.respond("GET","/v1/invoices",StripeBillingSnapshotMapperTest.page(false));
            var canceled=c.fixture.graph.subscription.deepCopy();canceled.addProperty("status","canceled");canceled.addProperty("canceled_at",c.now.getEpochSecond());canceled.addProperty("ended_at",c.now.getEpochSecond());
            c.fixture.graph.http.respond("DELETE",SUBSCRIPTION,canceled.toString());
            assertThatCode(()->assertThat(c.fixture.adapter.cancel(c.command(),true).outcome()).isEqualTo(Outcome.ACCEPTED)).doesNotThrowAnyException();
            var call=c.fixture.graph.http.calls().getLast();
            var params=new HashMap<>(call.query());params.putAll(call.form());
            assertThat(call.method()).isEqualTo("DELETE");
            assertThat(params).containsExactlyInAnyOrderEntriesOf(Map.of("invoice_now","false","prorate","false"));
            assertThat(c.fixture.steps.only().kind()).isEqualTo(ProviderOperation.Kind.SUBSCRIPTION_CANCEL);
            assertThat(c.fixture.local).isEqualTo(before);
            assertThat(c.receipts.get(c.fixture.operation.id()).mode()).isEqualTo(SubscriptionCancellationProofStore.Mode.IMMEDIATE);
        }
    }

    @Test
    void immediateCancellationRefusesForeignCollectableCustomerInvoice() throws Exception {
        try(var c=new CancellationCase()){
            c.operation(SubscriptionOperation.Kind.CANCEL,"immediate",null);
            c.current();c.fixture.graph.http.respond("GET","/v1/invoiceitems",StripeBillingSnapshotMapperTest.page(false));
            var foreign=c.fixture.graph.invoice.deepCopy();foreign.addProperty("status","open");foreign.getAsJsonObject("parent").getAsJsonObject("subscription_details").addProperty("subscription","sub_other");
            c.fixture.graph.http.respond("GET","/v1/invoices",StripeBillingSnapshotMapperTest.page(false,foreign));
            assertThatCode(()->assertThat(c.fixture.adapter.cancel(c.command(),true).outcome()).isEqualTo(Outcome.UNKNOWN)).doesNotThrowAnyException();
            assertThat(c.fixture.graph.http.requestCount("DELETE",SUBSCRIPTION)).isZero();
            assertThat(c.receipts).isEmpty();
        }
    }

    @Test
    void neverDispatchedRequiredTrialCanBeExplicitlyAbandonedWithoutNativeCancellation() throws Exception {
        try(var c=new AbandonCase()){
            assertThatCode(()->assertThat(c.transaction.abandon(c.context,c.subscription.id(),dev.genericpayments.platform.subscriptions.application.port.in.CancelSubscriptionUseCase.Mode.PERIOD_END,c.candidate()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED)).doesNotThrowAnyException();
            assertThat(c.subscription.billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
            assertThat(c.subscription.providerReference()).isNull();
            assertThat(c.operations.get(c.original.id()).status()).isEqualTo(SubscriptionOperation.Status.FAILED);
            assertThat(c.eligibility.reservedByOperation()).isNull();
            assertThat(c.events).singleElement().satisfies(e->assertThat(e.type()).isEqualTo(dev.genericpayments.platform.subscriptions.domain.SubscriptionEvent.Type.CANCELLED));
            assertThat(c.base.fixture.graph.http.calls()).isEmpty();
            assertThat(c.base.fixture.steps.find(c.base.fixture.account.id(),c.original.providerOperationKey())).isEmpty();
        }
    }

    @Test
    void everDispatchedUnknownCreateCannotBeAbandonedOrReleaseTrialEligibility() throws Exception {
        try(var c=new AbandonCase()){
            var step=c.base.fixture.steps.reserve(c.base.fixture.account,ProviderOperation.Kind.SUBSCRIPTION_CREATE,c.original.id(),c.original.providerOperationKey(),"create-immutable");
            var claim=c.base.fixture.steps.claim(step.id(),"owner",c.base.now,java.time.Duration.ofSeconds(60)).orElseThrow();
            c.base.fixture.steps.unknown(claim.id(),"owner",claim.version());
            assertThatThrownBy(()->c.transaction.abandon(c.context,c.subscription.id(),dev.genericpayments.platform.subscriptions.application.port.in.CancelSubscriptionUseCase.Mode.PERIOD_END,c.candidate())).isInstanceOf(PaymentApplicationException.class);
            assertThat(c.subscription.billingStatus()).isEqualTo(Subscription.Status.INCOMPLETE);
            assertThat(c.eligibility.reservedByOperation()).isEqualTo(c.original.id());
            assertThat(c.events).isEmpty();assertThat(c.base.fixture.graph.http.calls()).isEmpty();
        }
    }

    /** Actual transaction/service logic, in-memory port effects only; no Spring/DB bootstrap. */
    static final class AbandonCase implements AutoCloseable {
        final CancellationCase base=new CancellationCase();
        final Map<UUID,SubscriptionOperation> operations=new HashMap<>();
        final List<dev.genericpayments.platform.subscriptions.domain.SubscriptionEvent> events=new ArrayList<>();
        final dev.genericpayments.platform.subscriptions.application.port.out.TrialEligibilityStore trials=mock(dev.genericpayments.platform.subscriptions.application.port.out.TrialEligibilityStore.class);
        final dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore billing=mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore.class);
        final dev.genericpayments.platform.subscriptions.application.service.SubscriptionCancellationTransaction transaction;
        final dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext context;
        final SubscriptionOperation original;
        Subscription subscription;
        dev.genericpayments.platform.subscriptions.domain.TrialEligibility eligibility;
        AbandonCase() throws Exception {
            var f=base.fixture;var s=f.local;var t=s.billingPrice();
            var terms=new dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms(t.priceId(),t.priceKey(),t.version(),t.productId(),t.regularAmount(),t.interval(),7,dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL,t.taxMode());
            subscription=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),terms,null,Subscription.Status.INCOMPLETE,null,null,null,null,null,null,null,null,s.revision(),base.now.minusSeconds(20),base.now,null);
            var id=UUID.randomUUID();
            original=new SubscriptionOperation(id,s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.CREATE,SubscriptionOperation.Status.PROCESSING,"customer:owned","original","v1.trial."+"a".repeat(64),"subscription:"+f.account.id().value()+":"+id+":CREATE",0,1,null,null,base.now.minusSeconds(20),base.now,1);
            operations.put(id,original);
            eligibility=new dev.genericpayments.platform.subscriptions.domain.TrialEligibility(s.siteId(),s.customerId(),s.productId(),id,null,1);
            var setup=f.steps.reserve(f.account,ProviderOperation.Kind.SETUP_INTENT_CREATE,id,original.providerOperationKey()+":SETUP","setup-immutable");
            setup=f.steps.claim(setup.id(),"setup-owner",base.now,java.time.Duration.ofSeconds(60)).orElseThrow();
            setup=f.steps.acknowledge(setup.id(),"setup-owner",setup.version(),"seti_owned");
            when(billing.setup(f.account.id(),id)).thenReturn(Optional.of(new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore.Setup(id,s.id(),f.account.id(),setup.id(),"seti_owned","pm_owned")));
            when(trials.find(any(),any(),any())).thenAnswer(i->Optional.of(eligibility));
            doAnswer(i->{eligibility=new dev.genericpayments.platform.subscriptions.domain.TrialEligibility(s.siteId(),s.customerId(),s.productId(),null,null,eligibility.version()+1);return null;}).when(trials).releaseConfirmedFailure(id);
            var ops=mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionOperationStore.class);
            when(ops.lock(any())).thenAnswer(i->operations.get(i.getArgument(0)));
            when(ops.claim(any())).thenAnswer(i->{SubscriptionOperation proposed=i.getArgument(0);var old=operations.values().stream().filter(o->o.kind()==proposed.kind()&&o.keyDigest().equals(proposed.keyDigest())).findFirst().orElse(null);if(old!=null)return new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionOperationStore.Claim(old,false);operations.put(proposed.id(),proposed);return new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionOperationStore.Claim(proposed,true);});
            when(ops.save(any(),anyLong())).thenAnswer(i->{SubscriptionOperation o=i.getArgument(0);operations.put(o.id(),o);return o;});
            when(ops.unresolved(any(),any())).thenAnswer(i->operations.values().stream().filter(o->o.status()!=SubscriptionOperation.Status.SUCCEEDED&&o.status()!=SubscriptionOperation.Status.FAILED).toList());
            var subs=mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionStore.class);
            when(subs.find(any(),any())).thenAnswer(i->Optional.of(subscription));when(subs.lock(any(),any())).thenAnswer(i->subscription);
            when(subs.save(any(),anyLong())).thenAnswer(i->{subscription=i.getArgument(0);return subscription;});
            var account=mock(dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort.class);when(account.findByIdForUpdate(f.account.id())).thenReturn(Optional.of(f.account));
            var site=f.sites.findById(s.siteId());var customer=f.customerStore.findById(s.siteId(),s.customerId());
            var sites=mock(dev.genericpayments.platform.sites.application.port.out.LockSitePort.class);when(sites.findByIdForUpdate(s.siteId())).thenReturn(site);
            when(f.customerStore.findByIdForUpdate(s.siteId(),s.customerId())).thenReturn(customer);
            var policies=mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPolicyPort.class);when(policies.load(s.siteId())).thenReturn(new dev.genericpayments.platform.subscriptions.domain.SubscriptionPolicy(s.siteId(),1,true,3,false,false,false,Set.of()));
            var tx=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort(){public <T>T required(java.util.function.Supplier<T>w){return w.get();}public <T>T requiresNew(java.util.function.Supplier<T>w){return w.get();}public boolean active(){return false;}};
            transaction=new dev.genericpayments.platform.subscriptions.application.service.SubscriptionCancellationTransaction(tx,ops,subs,mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionAuthorityPort.class),policies,account,sites,f.customerStore,Clock.fixed(base.now,ZoneOffset.UTC));
            var eventStore=mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionEventStore.class);when(eventStore.append(any())).thenAnswer(i->events.add(i.getArgument(0)));
            for(var field:Map.of("trials",trials,"billingProofs",billing,"providerSteps",f.steps,"events",eventStore,"callbacks",mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCallbackPort.class)).entrySet())org.springframework.test.util.ReflectionTestUtils.setField(transaction,field.getKey(),field.getValue());
            var session=new dev.genericpayments.platform.customers.application.model.CustomerSessionContext(new dev.genericpayments.platform.customers.domain.CustomerSessionId(UUID.randomUUID()),s.siteId(),s.customerId(),"https://shop.test",Set.of(),base.now.plusSeconds(600));
            context=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer(session),s.siteId(),s.customerId(),session.origin(),"abandon","abandon-request");
        }
        dev.genericpayments.platform.subscriptions.application.service.SubscriptionCancellationTransaction.Candidate candidate(){return new dev.genericpayments.platform.subscriptions.application.service.SubscriptionCancellationTransaction.Candidate(subscription,base.fixture.account,new dev.genericpayments.platform.subscriptions.domain.SubscriptionPolicy(subscription.siteId(),1,true,3,false,false,false,Set.of()));}
        public void close(){base.close();}
    }

    /** Existing closed loopback composition plus one in-memory typed receipt boundary. */
    static final class CancellationCase implements AutoCloseable {
        final StripeSubscriptionCreateTest fixture = new StripeSubscriptionCreateTest();
        final SubscriptionCancellationProofStore proofs = mock(SubscriptionCancellationProofStore.class);
        final Map<UUID, SubscriptionCancellationProofStore.Receipt> receipts = new HashMap<>();
        final Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        final Instant end = now.plusSeconds(600);
        CancellationCase() throws Exception {
            fixture.setup();
            var s = fixture.graph.local;
            fixture.local = new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),s.providerReference(),new Subscription.Period(now.minusSeconds(100),end),end,null,null,null,null,null,s.revision(),now.minusSeconds(100),now,null);
            fixture.graph.item().addProperty("current_period_start", now.minusSeconds(100).getEpochSecond());
            fixture.graph.item().addProperty("current_period_end", end.getEpochSecond());
            fixture.graph.line.getAsJsonObject("period").addProperty("start",now.minusSeconds(100).getEpochSecond());
            fixture.graph.line.getAsJsonObject("period").addProperty("end",end.getEpochSecond());
            org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"clock",Clock.fixed(now,ZoneOffset.UTC));
            org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"providerClock",Clock.fixed(now,ZoneOffset.UTC));
            org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"cancellationProofs",proofs);
            when(proofs.receipt(any(),any())).thenAnswer(i -> Optional.ofNullable(receipts.get(i.getArgument(1))));
            when(proofs.activeSchedule(any(),any(),any())).thenAnswer(i -> receipts.values().stream().filter(r -> r.mode()==SubscriptionCancellationProofStore.Mode.PERIOD_END&&r.boundary().equals(i.getArgument(2))&&receipts.values().stream().noneMatch(w -> r.operation().equals(w.priorOperation()))).findFirst());
            when(proofs.record(any(),any(),any(),anyString(),any(),any(),any(),any())).thenAnswer(i -> {
                Subscription sub=i.getArgument(0);SubscriptionOperation op=i.getArgument(1);ProviderOperation step=i.getArgument(2);
                fixture.steps.acknowledge(step.id(),i.getArgument(3),step.version(),sub.providerReference());
                var r=new SubscriptionCancellationProofStore.Receipt(op.id(),sub.id(),sub.paymentAccountId(),step.id(),sub.providerReference(),i.getArgument(4),i.getArgument(5),i.getArgument(6),i.getArgument(7));receipts.put(op.id(),r);return r;
            });
            operation(SubscriptionOperation.Kind.CANCEL,"period_end",end);
            when(fixture.operations.unresolved(any(),any())).thenAnswer(i -> List.of(fixture.operation));
        }
        void operation(SubscriptionOperation.Kind kind,String mode,Instant boundary) {
            var s=fixture.local;var id=UUID.randomUUID();
            fixture.operation=new SubscriptionOperation(id,s.siteId(),s.customerId(),s.id(),kind,SubscriptionOperation.Status.PROCESSING,"customer:owned","key","v1.cancel."+mode+"."+(boundary==null?"none":boundary.getEpochSecond()+"/"+boundary.getNano())+"."+"a".repeat(64),"subscription:"+fixture.account.id().value()+":"+id+":"+kind,s.revision(),1,s.providerReference(),null,now,now,1);
            when(fixture.operations.find(eq(s.siteId()),eq(s.id()),any())).thenAnswer(i -> fixture.operation.id().equals(i.getArgument(2))?Optional.of(fixture.operation):Optional.empty());
        }
        Command command(){return new Command(fixture.account,fixture.operation,fixture.local.billingPrice(),null,"sub_owned",null,null,false,false);}
        void current(){fixture.identity();fixture.graph.http.respond("GET","/v1/customers/cus_owned",fixture.graph.customer.toString());fixture.graph.http.respond("GET",SUBSCRIPTION,fixture.graph.subscription.toString());}
        String response(boolean withdraw){var r=fixture.graph.subscription.deepCopy();if(withdraw)r.add("cancel_at",com.google.gson.JsonNull.INSTANCE);else r.addProperty("cancel_at",end.getEpochSecond());r.addProperty("cancel_at_period_end",false);return r.toString();}
        public void close(){fixture.close();}
    }

    @Test
    void periodEndWireUsesExactCapturedSecondAndNoProrationWithOriginalKey() throws Exception {
        try (var http = new StripeBillingHttpFixture()) {
            http.respond("POST", SUBSCRIPTION, subscription(BOUNDARY));
            var result = http.client().v1().subscriptions().update("sub_owned",
                SubscriptionUpdateParams.builder().setCancelAt(BOUNDARY)
                    .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE).build(),
                RequestOptions.builder().setIdempotencyKey("original-cancel-wire").build());

            assertThat(result.getId()).isEqualTo("sub_owned");
            assertThat(result.getCancelAt()).isEqualTo(BOUNDARY);
            assertThat(http.calls()).singleElement().satisfies(call -> {
                assertThat(call.method()).isEqualTo("POST");
                assertThat(call.path()).isEqualTo(SUBSCRIPTION);
                assertThat(call.form()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                    "cancel_at", Long.toString(BOUNDARY), "proration_behavior", "none"));
                assertThat(call.header("Idempotency-Key")).isEqualTo("original-cancel-wire");
                assertThat(call.header("Stripe-Version")).isEqualTo("2026-09-30.endive");
                assertThat(call.header("Stripe-Account")).isNull();
            });
        }
    }

    @Test
    void withdrawalWireClearsExplicitCancelAtAndPeriodEndFlagOnly() throws Exception {
        try (var http = new StripeBillingHttpFixture()) {
            http.respond("POST", SUBSCRIPTION, subscription(null));
            var result = http.client().v1().subscriptions().update("sub_owned",
                SubscriptionUpdateParams.builder().setCancelAt(EmptyParam.EMPTY)
                    .setCancelAtPeriodEnd(false)
                    .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE).build(),
                RequestOptions.builder().setIdempotencyKey("original-withdraw-wire").build());

            assertThat(result.getId()).isEqualTo("sub_owned");
            assertThat(result.getCancelAt()).isNull();
            assertThat(result.getCancelAtPeriodEnd()).isFalse();
            assertThat(http.calls()).singleElement().satisfies(call -> {
                assertThat(call.method()).isEqualTo("POST");
                assertThat(call.path()).isEqualTo(SUBSCRIPTION);
                assertThat(call.form()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                    "cancel_at", "", "cancel_at_period_end", "false", "proration_behavior", "none"));
                assertThat(call.header("Idempotency-Key")).isEqualTo("original-withdraw-wire");
                assertThat(call.header("Stripe-Version")).isEqualTo("2026-09-30.endive");
            });
        }
    }

    @Test
    void disconnectedCancelWireHasNoSdkRetryAndExplicitReplayKeepsBodyAndKey() throws Exception {
        try (var http = new StripeBillingHttpFixture()) {
            var params = SubscriptionUpdateParams.builder().setCancelAt(BOUNDARY)
                .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.NONE).build();
            var options = RequestOptions.builder().setIdempotencyKey("original-unknown-cancel-wire").build();
            http.disconnectNext("POST", SUBSCRIPTION);
            assertThatThrownBy(() -> http.client().v1().subscriptions().update("sub_owned", params, options))
                .isInstanceOf(ApiConnectionException.class);
            assertThat(http.requestCount("POST", SUBSCRIPTION)).isEqualTo(1);

            // SDK contract only: platform permission/window/receipt admission is a deferred test.
            http.respond("POST", SUBSCRIPTION, subscription(BOUNDARY));
            http.client().v1().subscriptions().update("sub_owned", params, options);
            assertThat(http.requestCount("POST", SUBSCRIPTION)).isEqualTo(2);
            assertThat(http.calls().get(1).form()).isEqualTo(http.calls().getFirst().form());
            assertThat(http.calls()).allSatisfy(call ->
                assertThat(call.header("Idempotency-Key")).isEqualTo("original-unknown-cancel-wire"));
        }
    }

    @Test
    void currentPaidBoundaryAfterProjectionDoesNotReuseHistoricalSourceEnd() {
        var source = SubscriptionFixtures.active(Instant.ofEpochSecond(1800000000L), Instant.ofEpochSecond(BOUNDARY));
        var nextEnd = source.paidThrough().plusSeconds(2678400);
        var current = source.confirmPaid(source.billingPrice(),
            new Subscription.Period(source.paidThrough(), nextEnd), source.paidThrough().plusSeconds(1));

        // Consume an already projected current period; Task8 owns transition/cash normalization.
        assertThat(SubscriptionCancellationRules.liveBoundary(current, source.paidThrough().plusSeconds(2)))
            .isEqualTo(nextEnd).isNotEqualTo(source.paidThrough());
        assertThat(SubscriptionCancellationRules.liveBoundary(current, nextEnd)).isNull();
    }

    @Test
    void withdrawalRefusesAtExactCapturedDeadline() {
        var source = SubscriptionFixtures.active(Instant.ofEpochSecond(1800000000L), Instant.ofEpochSecond(BOUNDARY));
        var captured = source.paidThrough();
        var scheduled = source.scheduleCancellation(captured, source.currentPeriod().start().plusSeconds(1));
        assertThatCode(() -> SubscriptionCancellationRules.requireWithdrawable(scheduled, captured.minusNanos(1)))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> SubscriptionCancellationRules.requireWithdrawable(scheduled, captured))
            .isInstanceOf(SubscriptionRuleViolation.class);
        assertThat(scheduled.cancellation().effectiveAt()).isEqualTo(captured);
    }

    @Test
    void cancellationStateReadCannotWriteOrInventOriginalOperationProof() throws Exception {
        var fixture = new StripeSubscriptionCreateTest();
        try {
            fixture.setup();
            fixture.local = fixture.graph.local;
            fixture.graph.subscription.addProperty("cancel_at", BOUNDARY);
            fixture.identity();
            fixture.graph.graph();
            var result = fixture.adapter.retrieve(new Lookup(fixture.account, fixture.local.id(), "sub_owned", null));

            assertThat(result.cancellationAt()).isEqualTo(Instant.ofEpochSecond(BOUNDARY));
            assertThat(result.operationKey()).isNull();
            assertThat(result.operationObservation()).isNull();
            assertThat(result.invoices()).singleElement().satisfies(invoice ->
                assertThat(invoice.settlement()).isEqualTo(SettlementKind.CASH));
            assertThat(fixture.graph.http.calls()).allSatisfy(call -> assertThat(call.method()).isEqualTo("GET"));
            assertThat(fixture.steps.rows).isEmpty();
        } finally {
            fixture.close();
        }
    }

    @Test
    void foreignSubscriptionReferenceRefusesBeforeAnyProviderReadOrMutation() throws Exception {
        var fixture = new StripeSubscriptionCreateTest();
        try {
            fixture.setup();
            fixture.local = fixture.graph.local;
            assertThatThrownBy(() -> fixture.adapter.retrieve(
                new Lookup(fixture.account, fixture.local.id(), "sub_foreign", null)))
                .isInstanceOf(PaymentApplicationException.class);
            assertThat(fixture.graph.http.calls()).isEmpty();
            assertThat(fixture.steps.rows).isEmpty();
        } finally {
            fixture.close();
        }
    }

    private static String subscription(Long cancelAt) {
        return "{\"id\":\"sub_owned\",\"object\":\"subscription\",\"livemode\":false,"
            + "\"customer\":\"cus_owned\",\"status\":\"active\",\"cancel_at_period_end\":false,"
            + "\"cancel_at\":" + cancelAt + "}";
    }
}
