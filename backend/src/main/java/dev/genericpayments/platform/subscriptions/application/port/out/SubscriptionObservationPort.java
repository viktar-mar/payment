package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
public interface SubscriptionObservationPort {
    /** Under the owned subscription lock, retain max revision; equal observations remain applicable. */
    boolean acceptRevision(Subscription subscription,long providerRevision);
}
