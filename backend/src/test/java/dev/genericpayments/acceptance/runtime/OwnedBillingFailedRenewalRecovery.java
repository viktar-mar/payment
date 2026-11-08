package dev.genericpayments.acceptance.runtime;

import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedRenewalMethodObserver;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Completion;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Publication;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Recovery;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Reservation;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.ConfirmationAck;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit test-support phases; the original concrete delegates retain all financial authority. */
public final class OwnedBillingFailedRenewalRecovery {
    public enum State { NEW, CONFIRMING, CONFIRMED, OBSERVING, COMPLETE, FAILED }

    private final Reservation reservation;
    private final Completion completion;
    private final Recovery recovery;
    private final SubscriptionCommandContext originalContext;
    private final StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter confirmer;
    private final StripeBillingOwnedFailedRenewalMethodObserver observer;
    private final AtomicReference<State> state=new AtomicReference<>(State.NEW);
    private volatile ConfirmationAck confirmation;
    private volatile Publication publication;

    public OwnedBillingFailedRenewalRecovery(Reservation reservation,Completion completion,Recovery recovery,
            SubscriptionCommandContext originalContext,StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter confirmer,
            StripeBillingOwnedFailedRenewalMethodObserver observer) {
        this.reservation=Objects.requireNonNull(reservation);this.completion=Objects.requireNonNull(completion);
        this.recovery=Objects.requireNonNull(recovery);this.originalContext=Objects.requireNonNull(originalContext);
        this.confirmer=Objects.requireNonNull(confirmer);this.observer=Objects.requireNonNull(observer);
    }

    public ConfirmationAck confirm() {
        // Refusal does not enter the owner's failure path or touch its delegates.
        if(!state.compareAndSet(State.NEW,State.CONFIRMING))throw unavailable();
        try {
            var acknowledged=Objects.requireNonNull(confirmer.confirm(reservation,completion,recovery,originalContext));
            confirmation=acknowledged;
            state.set(State.CONFIRMED);
            return acknowledged;
        } catch(RuntimeException failure) {
            state.set(State.FAILED);
            throw unavailable();
        } catch(Error failure) {
            state.set(State.FAILED);
            throw failure;
        }
    }

    public Publication observe() {
        if(!state.compareAndSet(State.CONFIRMED,State.OBSERVING))throw unavailable();
        try {
            var published=Objects.requireNonNull(observer.observeRecovery(reservation,recovery));
            publication=published;
            state.set(State.COMPLETE);
            return published;
        } catch(RuntimeException failure) {
            state.set(State.FAILED);
            throw unavailable();
        } catch(Error failure) {
            state.set(State.FAILED);
            throw failure;
        }
    }

    public State state() { return state.get(); }
    public Optional<ConfirmationAck> confirmation() { return Optional.ofNullable(confirmation); }
    public Optional<Publication> publication() { return Optional.ofNullable(publication); }

    private static PaymentApplicationException unavailable() {
        return new PaymentApplicationException("OWNED_RECOVERY_SCENARIO_UNAVAILABLE",409,"Owned recovery scenario unavailable");
    }

    @Override public String toString() { return "OwnedBillingFailedRenewalRecovery[redacted]"; }
}
