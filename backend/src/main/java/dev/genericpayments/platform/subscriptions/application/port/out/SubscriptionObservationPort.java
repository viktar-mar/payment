package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
public interface SubscriptionObservationPort {
    /** Capture before provider IO; exact owned lifecycle revision must still match. */
    long captureFence(Subscription subscription);
    /** Single-use CAS under the projection transaction. Rolls back with its effects. */
    void consumeFence(Subscription subscription,long sequence);
    /** Under the owned subscription lock, retain max revision; equal observations remain applicable. */
    boolean acceptRevision(Subscription subscription,long providerRevision);
}
