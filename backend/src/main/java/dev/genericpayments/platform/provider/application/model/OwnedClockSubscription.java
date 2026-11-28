package dev.genericpayments.platform.provider.application.model;

import dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Descriptive immutable evidence. Only persisted, revalidated relations confer authority. */
public record OwnedClockSubscription(UUID id, ReadyClock clock, UUID customerBinding, UUID customerCreationOperation,
        String customerReference, UUID subscription, UUID createOperation, String operationKey,
        String requestFingerprint, long expectedRevision, long policyVersion, SubscriptionPriceTerms terms,
        String priceReference, String productReference, String fingerprint, Instant admittedAt) {
    public OwnedClockSubscription {
        Objects.requireNonNull(id); Objects.requireNonNull(clock); Objects.requireNonNull(customerBinding);
        Objects.requireNonNull(customerCreationOperation); Objects.requireNonNull(subscription); Objects.requireNonNull(createOperation);
        Objects.requireNonNull(terms); Objects.requireNonNull(admittedAt);
        if(customerReference==null || !customerReference.matches("cus_[A-Za-z0-9]{1,251}")
            || !Objects.equals(operationKey,"subscription:"+clock.owner().account().value()+":"+createOperation+":CREATE")
            || requestFingerprint==null || !requestFingerprint.matches("v1\\.paid\\.[a-f0-9]{64}")
            || expectedRevision<0 || policyVersion<0 || terms.trialDays()!=0
            || priceReference==null || !priceReference.matches("price_[A-Za-z0-9]{1,249}")
            || productReference==null || !productReference.matches("prod_[A-Za-z0-9]{1,250}")
            || fingerprint==null || !fingerprint.matches("[a-f0-9]{64}") || admittedAt.isBefore(clock.observedAt()))
            throw new IllegalArgumentException("Invalid owned Subscription intent");
    }
}
