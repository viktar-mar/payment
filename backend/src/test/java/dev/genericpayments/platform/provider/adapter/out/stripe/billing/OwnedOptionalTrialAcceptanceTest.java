package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult;
import dev.genericpayments.platform.subscriptions.application.port.in.ResumeSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnedOptionalTrialAcceptanceTest {
    private final PaymentAccount account = mock(PaymentAccount.class);
    private final OwnedOptionalTrialCreate.Scope scope =
        new OwnedOptionalTrialCreate.Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final OwnedOptionalTrialAdmission admission = mock(OwnedOptionalTrialAdmission.class);
    private final OwnedOptionalTrialCreate create = mock(OwnedOptionalTrialCreate.class);
    private final OwnedOptionalTrialPublication publication = mock(OwnedOptionalTrialPublication.class);
    private final OwnedOptionalTrialClockAdvance clock = mock(OwnedOptionalTrialClockAdvance.class);
    private final OwnedOptionalTrialSetup setup = mock(OwnedOptionalTrialSetup.class);
    private final OwnedOptionalTrialResume resume = mock(OwnedOptionalTrialResume.class);
    private final OwnedOptionalTrialPaymentConfirmation confirmation = mock(OwnedOptionalTrialPaymentConfirmation.class);
    private final OwnedOptionalTrialAcceptance acceptance =
        new OwnedOptionalTrialAcceptance(admission, create, publication, clock, setup, resume, confirmation);
    private final OwnedOptionalTrialAdmission.Request request = mock(OwnedOptionalTrialAdmission.Request.class);
    private final SubscriptionCommandContext setupContext = mock(SubscriptionCommandContext.class);
    private final SubscriptionCommandContext resumeContext = mock(SubscriptionCommandContext.class);
    private final OwnedOptionalTrialSetup.Prepared prepared = mock(OwnedOptionalTrialSetup.Prepared.class);
    private final OwnedOptionalTrialSetup.SetupPermit permit = mock(OwnedOptionalTrialSetup.SetupPermit.class);
    private final UUID setupId = UUID.randomUUID();
    private final UUID resumeId = UUID.randomUUID();
    private final ResumeSubscriptionUseCase.Command resumeCommand =
        new ResumeSubscriptionUseCase.Command(resumeContext, scope.subscription(), true);

    @Test void followsTheOriginalTrialThroughTheSameResumePayment() {
        var session = open();
        assertThat(session.snapshot().status()).isEqualTo(OwnedOptionalTrialAcceptance.Status.TRIALING);
        session.advance();
        assertThat(session.prepareSetup(setupContext, true)).isSameAs(permit);
        session.completeSetup();
        session.resume(resumeContext, true);
        assertThat(session.snapshot().status()).isEqualTo(OwnedOptionalTrialAcceptance.Status.OPEN);
        session.pay();
        assertThat(session.snapshot()).isEqualTo(new OwnedOptionalTrialAcceptance.Snapshot(scope,
            OwnedOptionalTrialAcceptance.Status.PAID, setupId, resumeId));

        var calls = inOrder(admission, create, publication, setup);
        calls.verify(admission).admit(request);
        calls.verify(create).create(account, scope);
        calls.verify(publication).publish(account, scope);
        calls.verify(publication).advanceAndPause(account, scope, clock);
        calls.verify(setup).prepare(account, scope, setupContext, true);
        calls.verify(setup).claimBrowserPermit(account, scope, setupContext, prepared);
        calls.verify(setup).complete(account, scope, setupContext, prepared);
        calls.verify(publication).startResume(account, scope, setupId, resumeCommand, resume);
        calls.verify(publication).observeResume(account, scope, setupId, resumeId, resume, false);
        calls.verify(publication).confirmResumePayment(account, scope, setupId, resumeId, confirmation);
        calls.verify(publication).observeResume(account, scope, setupId, resumeId, resume, true);

        assertThatThrownBy(session::pay).isInstanceOf(IllegalStateException.class);
        verify(publication, times(1)).confirmResumePayment(account, scope, setupId, resumeId, confirmation);
    }

    @Test void anUncertainConfirmStopsTheSessionAndDoesNotPublishPaid() {
        var session = open();
        session.advance();
        session.prepareSetup(setupContext, true);
        session.completeSetup();
        session.resume(resumeContext, true);
        doThrow(new IllegalStateException("synthetic private detail")).when(publication)
            .confirmResumePayment(account, scope, setupId, resumeId, confirmation);

        assertThatThrownBy(session::pay).isInstanceOf(IllegalStateException.class)
            .hasMessage("OPTIONAL_TRIAL_PAYMENT_CONFIRMATION").hasNoCause();
        assertThat(session.snapshot().status()).isEqualTo(OwnedOptionalTrialAcceptance.Status.STOPPED);
        assertThatThrownBy(session::pay).isInstanceOf(IllegalStateException.class);
        verify(publication, times(1)).confirmResumePayment(account, scope, setupId, resumeId, confirmation);
        verify(publication, never()).observeResume(account, scope, setupId, resumeId, resume, true);
    }

    @Test void resumeIsUnavailableBeforeSetupCompletes() {
        var session = open();
        session.advance();
        session.prepareSetup(setupContext, true);
        assertThatThrownBy(() -> session.resume(resumeContext, true)).isInstanceOf(IllegalStateException.class);
        verify(publication, never()).startResume(any(), any(), any(), any(), any());
    }

    @Test void stopsAfterAnUncertainResumeAndRetainsTheOriginalOperationForEvidence() {
        var session = open();
        session.advance();
        session.prepareSetup(setupContext, true);
        session.completeSetup();
        when(publication.startResume(account, scope, setupId, resumeCommand, resume)).thenReturn(
            new OwnedOptionalTrialResume.Attempt(
                new SubscriptionOperationResult(resumeId, scope.subscription(), SubscriptionOperation.Status.UNKNOWN, null),
                OwnedOptionalTrialResume.Stage.STRIPE_PAYMENT_BEHAVIOR));
        assertThatThrownBy(() -> session.resume(resumeContext, true)).isInstanceOf(IllegalStateException.class)
            .hasMessage("OPTIONAL_TRIAL_RESUME_STRIPE_PAYMENT_BEHAVIOR").hasNoCause();
        assertThat(session.snapshot()).isEqualTo(new OwnedOptionalTrialAcceptance.Snapshot(scope,
            OwnedOptionalTrialAcceptance.Status.STOPPED, setupId, resumeId));
        assertThatThrownBy(session::pay).isInstanceOf(IllegalStateException.class);
        verify(publication, never()).observeResume(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test void anUnprovenCaptureStopsWithoutASecondConfirmation() {
        var session = open();
        session.advance();
        session.prepareSetup(setupContext, true);
        session.completeSetup();
        session.resume(resumeContext, true);
        doThrow(new IllegalStateException("mismatched capture")).when(publication)
            .observeResume(account, scope, setupId, resumeId, resume, true);

        assertThatThrownBy(session::pay).isInstanceOf(IllegalStateException.class)
            .hasMessage("OPTIONAL_TRIAL_PAYMENT_OBSERVATION").hasNoCause();
        assertThat(session.snapshot().status()).isEqualTo(OwnedOptionalTrialAcceptance.Status.STOPPED);
        assertThatThrownBy(session::pay).isInstanceOf(IllegalStateException.class);
        verify(publication, times(1)).confirmResumePayment(account, scope, setupId, resumeId, confirmation);
    }

    @Test void setupRequiresExplicitRecurringConsentBeforeMakingAProviderCall() {
        var session = open();
        session.advance();
        assertThatThrownBy(() -> session.prepareSetup(setupContext, false))
            .isInstanceOf(IllegalStateException.class).hasMessage("OPTIONAL_TRIAL_SETUP_PERMIT");
        verifyNoInteractions(setup);
    }

    private OwnedOptionalTrialAcceptance.Session open() {
        when(admission.admit(request)).thenReturn(new OwnedOptionalTrialAdmission.Admitted(account, scope, 1700000000L, 1700604800L));
        when(setup.prepare(account, scope, setupContext, true)).thenReturn(prepared);
        when(prepared.operation()).thenReturn(setupId);
        when(setup.claimBrowserPermit(account, scope, setupContext, prepared)).thenReturn(permit);
        when(publication.startResume(account, scope, setupId, resumeCommand, resume)).thenReturn(
            new OwnedOptionalTrialResume.Attempt(
                new SubscriptionOperationResult(resumeId, scope.subscription(), SubscriptionOperation.Status.PROCESSING, null), null));
        return acceptance.open(request);
    }
}
