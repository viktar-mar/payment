package dev.genericpayments.platform.provider.adapter.out.stripe;

import dev.genericpayments.platform.payments.application.model.ProviderResult;

/** No SDK text, body, or nested cause crosses the provider boundary. */
final class StripePaymentErrorMapper {
    private StripePaymentErrorMapper() {}

    static ProviderResult map(Exception failure) {
        // Preparation failures cannot prove whether a provider object was created.
        return new ProviderResult.Unknown(null);
    }
}
