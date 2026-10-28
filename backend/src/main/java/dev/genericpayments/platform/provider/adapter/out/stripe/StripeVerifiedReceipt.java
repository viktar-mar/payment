package dev.genericpayments.platform.provider.adapter.out.stripe;

import dev.genericpayments.platform.payments.application.model.ProviderReferenceReceipt;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import java.util.Objects;

/** Authenticated adapter-local classification; contains only bounded application references. */
public sealed interface StripeVerifiedReceipt {
    record Reference(ProviderReferenceReceipt receipt) implements StripeVerifiedReceipt {
        public Reference { Objects.requireNonNull(receipt); }
    }
    record Billing(SubscriptionProviderEnvelope envelope) implements StripeVerifiedReceipt {
        public Billing { Objects.requireNonNull(envelope); }
    }
}
