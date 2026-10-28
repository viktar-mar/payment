package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import dev.genericpayments.platform.webhooks.application.service.SubscriptionInboxWorker;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Direct caller tests: all IO/continuation ports are mocks; transactions only invoke Suppliers. */
class StripeSetupContinuationCallerTest {
    final Instant now = Instant.parse("2026-10-28T12:00:00Z");
    final UUID operationId = UUID.randomUUID();
    final SubscriptionStore subscriptions = mock(SubscriptionStore.class);
    final SubscriptionOperationStore operations = mock(SubscriptionOperationStore.class);
    final LoadPaymentAccountPort accounts = mock(LoadPaymentAccountPort.class);
    final SubscriptionInboxPort inbox = mock(SubscriptionInboxPort.class);
    final SubscriptionObjectPort objects = mock(SubscriptionObjectPort.class);
    final SubscriptionInboxResolutionPort resolution = mock(SubscriptionInboxResolutionPort.class);
    final SubscriptionReadPreparation reads = mock(SubscriptionReadPreparation.class);
    final SubscriptionObservationPort observations = mock(SubscriptionObservationPort.class);
    final SubscriptionProviderPort provider = mock(SubscriptionProviderPort.class);
    final SubscriptionProviderRegistry providers = mock(SubscriptionProviderRegistry.class);
    final ApplySubscriptionSnapshotUseCase facts = mock(ApplySubscriptionSnapshotUseCase.class);
    final ContinueOriginalSubscriptionSetupUseCase continuation = mock(ContinueOriginalSubscriptionSetupUseCase.class);
    final SubscriptionSweepPort sweep = mock(SubscriptionSweepPort.class);
    final SubscriptionWorkerConfigurationPort properties = mock(SubscriptionWorkerConfigurationPort.class);
    final SubscriptionTelemetryPort telemetry = mock(SubscriptionTelemetryPort.class);
    final SubscriptionAuthorityPort authority = mock(SubscriptionAuthorityPort.class);
    final Clock clock = mock(Clock.class);
    final SubscriptionTransactionPort tx = new SubscriptionTransactionPort() {
        public <T> T required(Supplier<T> work) { return work.get(); }
        public <T> T requiresNew(Supplier<T> work) { return work.get(); }
        public boolean active() { return false; }
    };
    final SubscriptionInboxPort.Claim inboxClaim = new SubscriptionInboxPort.Claim(UUID.randomUUID(), "owned-setup");
    Subscription pending;
    Subscription prepared;
    Subscription afterFacts;
    SubscriptionOperation currentOperation;
    PaymentAccount account;
    SubscriptionProviderEnvelope envelope;
    Snapshot snapshot;
    SubscriptionSweepPort.Claim initialSweep;
    SubscriptionSweepPort.Claim continuedSweep;
    SubscriptionSweepPort.Claim finishedSweep;

    @BeforeEach void fixture() {
        var seed = SubscriptionFixtures.active(now.minusSeconds(20), now.plusSeconds(100));
        var p = seed.billingPrice();
        var required = new SubscriptionPriceTerms(p.priceId(), p.priceKey(), p.version(), p.productId(), p.regularAmount(), p.interval(), 14,
            TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL, p.taxMode());
        pending = new Subscription(seed.id(), seed.siteId(), seed.customerId(), seed.productId(), seed.paymentAccountId(), required, null,
            Subscription.Status.INCOMPLETE, null, null, null, null, null, null, null, null, 1, seed.createdAt(), now, null);
        prepared = withReferenceAndRevision("sub_afterSetup", 2);
        afterFacts = withReferenceAndRevision("sub_afterSetup", 3);
        currentOperation = operation(prepared.providerReference(), 8);
        account = account(PaymentProviderType.STRIPE);
        envelope = envelope(account);
        snapshot = snapshot(prepared, 0);
        initialSweep = new SubscriptionSweepPort.Claim(pending.siteId(), pending.id(), "owned-setup", UUID.randomUUID(), pending.revision());
        continuedSweep = new SubscriptionSweepPort.Claim(pending.siteId(), pending.id(), initialSweep.owner(), initialSweep.leaseToken(), prepared.revision());
        finishedSweep = new SubscriptionSweepPort.Claim(pending.siteId(), pending.id(), initialSweep.owner(), initialSweep.leaseToken(), afterFacts.revision());
        when(clock.instant()).thenReturn(now);
        when(accounts.findById(account.id())).thenReturn(Optional.of(account));
        when(providers.forAccount(account)).thenReturn(provider);
        when(inbox.claim(anyString(), eq(20), eq(now), eq(Duration.ofSeconds(30)))).thenReturn(List.of(inboxClaim));
        when(inbox.lock(inboxClaim, now)).thenReturn(envelope);
        when(resolution.resolve(envelope)).thenReturn(Optional.of(pending.id()));
        when(reads.financial(prepared)).thenReturn(currentOperation);
        when(observations.captureFence(prepared)).thenReturn(71L);
        when(provider.retrieve(any())).thenReturn(snapshot);
        when(properties.delay()).thenReturn(Duration.ofSeconds(30));
        when(properties.backoff(anyInt())).thenReturn(Duration.ofSeconds(30));
    }

    @Test void inboxSetupReceiptFinishesBeforeReloadingFinancialOperationAndCapturingFreshObservation() {
        when(continuation.inbox(account, pending.id(), inboxClaim)).thenReturn(true);
        when(subscriptions.findOwned(account.id(), pending.id())).thenReturn(Optional.of(prepared));
        inboxWorker().runOnce();

        var order = inOrder(resolution, continuation, subscriptions, reads, observations, provider, facts, inbox);
        order.verify(resolution).resolve(envelope);
        order.verify(continuation).inbox(account, pending.id(), inboxClaim);
        order.verify(subscriptions).findOwned(account.id(), pending.id());
        order.verify(reads).financial(prepared);
        order.verify(observations).captureFence(prepared);
        order.verify(provider).retrieve(argThat(lookup -> lookup.reference().equals(prepared.providerReference())
            && lookup.providerOperationKey().equals(currentOperation.providerOperationKey())));
        order.verify(inbox).lock(inboxClaim, now);
        order.verify(facts).apply(argThat(command -> command.snapshot() == snapshot && command.fence().revision() == prepared.revision()
            && command.fence().operationId().equals(currentOperation.id()) && command.fence().operationVersion().equals(currentOperation.version())
            && command.fence().observationSequence().equals(71L)));
        order.verify(inbox).complete(inboxClaim);
        verify(reads, never()).financial(pending);
        verify(observations, never()).captureFence(pending);
        verify(inbox, never()).fail(any(), anyString(), any());
    }

    @Test void pendingRequiredSetupKeepsInboxHintRetryableWithoutRetrievingANullSubscription() {
        assertThat(pending.providerReference()).isNull();
        when(continuation.inbox(account, pending.id(), inboxClaim)).thenReturn(false);
        when(subscriptions.findOwned(account.id(), pending.id())).thenReturn(Optional.of(pending));
        inboxWorker().runOnce();

        verify(continuation).inbox(account, pending.id(), inboxClaim);
        verify(inbox).fail(inboxClaim, "SETUP_PENDING", now);
        verify(inbox, never()).complete(any());
        verifyNoInteractions(provider, facts, reads, observations);
    }

    @Test void sweepSetupReceiptReloadsOriginalOperationAndUsesTheReturnedRevisionClaim() {
        when(subscriptions.find(pending.siteId(), pending.id())).thenReturn(Optional.of(pending), Optional.of(prepared), Optional.of(afterFacts));
        when(continuation.sweep(account, pending.id(), initialSweep)).thenReturn(continuedSweep);
        when(sweep.advanceRevision(continuedSweep, afterFacts.revision(), now)).thenReturn(finishedSweep);
        reconciliation().reconcileClaim(initialSweep);

        var order = inOrder(subscriptions, continuation, reads, observations, provider, facts, sweep);
        order.verify(subscriptions).find(pending.siteId(), pending.id());
        order.verify(continuation).sweep(account, pending.id(), initialSweep);
        order.verify(subscriptions).find(pending.siteId(), pending.id());
        order.verify(reads).financial(prepared);
        order.verify(observations).captureFence(prepared);
        order.verify(provider).retrieve(argThat(lookup -> prepared.providerReference().equals(lookup.reference())
            && currentOperation.providerOperationKey().equals(lookup.providerOperationKey())));
        order.verify(facts).apply(argThat(command -> command.snapshot() == snapshot && command.fence().revision() == prepared.revision()
            && command.fence().operationId().equals(currentOperation.id()) && command.fence().operationVersion().equals(currentOperation.version())
            && command.fence().lease().equals(continuedSweep) && command.fence().observationSequence().equals(71L)));
        order.verify(subscriptions).find(pending.siteId(), pending.id());
        order.verify(sweep).advanceRevision(continuedSweep, afterFacts.revision(), now);
        order.verify(sweep).finish(finishedSweep, now.plusSeconds(30), true);
        verify(sweep, never()).advanceRevision(eq(initialSweep), anyLong(), any());
        verify(reads, never()).financial(pending);
        verify(observations, never()).captureFence(pending);
    }

    @Test void pendingRequiredSetupSweepDoesNotObserveANonexistentRemoteSubscription() {
        assertThat(pending.providerReference()).isNull();
        when(subscriptions.find(pending.siteId(), pending.id())).thenReturn(Optional.of(pending));
        when(continuation.sweep(account, pending.id(), initialSweep)).thenReturn(initialSweep);
        when(reads.financial(pending)).thenReturn(operation(null, 7));
        reconciliation().reconcileClaim(initialSweep);

        verify(continuation).sweep(account, pending.id(), initialSweep);
        verifyNoInteractions(provider, facts, observations);
        verify(sweep, never()).finish(any(), any(), eq(true));
    }

    @ParameterizedTest
    @CsvSource({"INBOX,CLAIM_LOST", "INBOX,CONTINUATION_FAILED", "SWEEP,CLAIM_LOST", "SWEEP,CONTINUATION_FAILED"})
    void failedOrLostContinuationStopsBeforeFreshRetrieveAndApply(String caller, String failure) {
        if (caller.equals("INBOX")) {
            when(continuation.inbox(account, pending.id(), inboxClaim)).thenThrow(new IllegalStateException(failure));
            inboxWorker().runOnce();
            verify(inbox).fail(inboxClaim, "PROCESSING_FAILED", now);
            verify(inbox, never()).complete(any());
        } else {
            when(subscriptions.find(pending.siteId(), pending.id())).thenReturn(Optional.of(pending));
            when(continuation.sweep(account, pending.id(), initialSweep)).thenThrow(new IllegalStateException(failure));
            reconciliation().reconcileClaim(initialSweep);
            verify(sweep).finish(initialSweep, now.plusSeconds(30), false);
            verify(sweep, never()).advanceRevision(any(), anyLong(), any());
        }
        verifyNoInteractions(provider, facts, reads, observations);
    }

    @Test void fakeSetupHintUsesItsExistingCallerPathWithoutInvokingStripeContinuation() {
        account = account(PaymentProviderType.FAKE);
        envelope = envelope(account);
        when(accounts.findById(account.id())).thenReturn(Optional.of(account));
        when(providers.forAccount(account)).thenReturn(provider);
        when(inbox.lock(inboxClaim, now)).thenReturn(envelope);
        when(objects.resolve(account.provider(), account.id(), SubscriptionObjectPort.Kind.SETUP, envelope.objectReference())).thenReturn(Optional.of(prepared.id()));
        when(subscriptions.findOwned(account.id(), pending.id())).thenReturn(Optional.of(prepared));
        when(provider.retrieve(any())).thenReturn(snapshot(prepared, 1));
        inboxWorker().runOnce();

        verifyNoInteractions(continuation, resolution, observations);
        verify(provider).retrieve(argThat(lookup -> prepared.providerReference().equals(lookup.reference())));
        verify(facts).apply(argThat(command -> command.fence().observationSequence() == null));
        verify(inbox).complete(inboxClaim);
    }

    @Test void fakeSweepUsesItsExistingCallerPathWithoutInvokingStripeContinuation() {
        account = account(PaymentProviderType.FAKE);
        when(accounts.findById(account.id())).thenReturn(Optional.of(account));
        when(providers.forAccount(account)).thenReturn(provider);
        when(subscriptions.find(prepared.siteId(), prepared.id())).thenReturn(Optional.of(prepared));
        var claim = new SubscriptionSweepPort.Claim(prepared.siteId(), prepared.id(), "fake-owner", UUID.randomUUID(), prepared.revision());
        when(sweep.advanceRevision(claim, prepared.revision(), now)).thenReturn(claim);
        when(provider.retrieve(any())).thenReturn(snapshot(prepared, 1));
        reconciliation().reconcileClaim(claim);

        verifyNoInteractions(continuation, observations);
        verify(provider).retrieve(argThat(lookup -> prepared.providerReference().equals(lookup.reference())));
        verify(facts).apply(argThat(command -> command.fence().observationSequence() == null && command.fence().lease().equals(claim)));
        verify(sweep).finish(claim, now.plusSeconds(30), true);
    }

    private SubscriptionInboxWorker inboxWorker() {
        var worker = new SubscriptionInboxWorker(inbox, tx, objects, subscriptions, operations, accounts, providers, facts, clock, 20, 30, reads, telemetry, observations, resolution);
        ReflectionTestUtils.setField(worker, "continuation", continuation);
        return worker;
    }
    private SubscriptionReconciliationService reconciliation() {
        var service = new SubscriptionReconciliationService(subscriptions, operations, tx, authority, accounts, providers, facts, reads, sweep, telemetry, clock, properties, observations);
        ReflectionTestUtils.setField(service, "continuation", continuation);
        return service;
    }
    private Subscription withReferenceAndRevision(String reference, long revision) {
        return new Subscription(pending.id(), pending.siteId(), pending.customerId(), pending.productId(), pending.paymentAccountId(), pending.billingPrice(), null,
            Subscription.Status.INCOMPLETE, reference, null, null, null, null, null, null, null, revision, pending.createdAt(), now, null);
    }
    private SubscriptionOperation operation(String reference, long version) {
        return new SubscriptionOperation(operationId, pending.siteId(), pending.customerId(), pending.id(), SubscriptionOperation.Kind.CREATE,
            reference == null ? SubscriptionOperation.Status.REQUIRES_ACTION : SubscriptionOperation.Status.PROCESSING, "customer-fixture", "original-key",
            "v1.trial." + "a".repeat(64), "subscription:" + pending.paymentAccountId().value() + ":" + operationId + ":CREATE",
            0, 1, reference, null, pending.createdAt(), now, version);
    }
    private PaymentAccount account(PaymentProviderType providerType) {
        return PaymentAccount.create(pending.paymentAccountId(), "setup-caller", providerType, PaymentAccountMode.SANDBOX, "acct_owned", "stripe-sandbox-default");
    }
    private SubscriptionProviderEnvelope envelope(PaymentAccount account) {
        return new SubscriptionProviderEnvelope(account.provider(), account.id(), "evt_setupOwned", "seti_owned", "SUBSCRIPTION_SETUP_CHANGED", now, now);
    }
    private Snapshot snapshot(Subscription subscription, long revision) {
        return new Snapshot(subscription.providerReference(), subscription.billingStatus(), subscription.billingPrice(), null, null, null, List.of(), null, false, revision, now);
    }
}
