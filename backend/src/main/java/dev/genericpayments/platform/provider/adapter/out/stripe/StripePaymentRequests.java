package dev.genericpayments.platform.provider.adapter.out.stripe;

import com.stripe.param.PaymentIntentCreateParams;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.payments.domain.PaymentSource;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.util.Locale;

/** Only immutable payment facts enter the Stripe request; no saved payment method or Customer. */
final class StripePaymentRequests {
    private StripePaymentRequests() {}

    static PaymentIntentCreateParams prepare(Payment payment) {
        if (payment.source() == PaymentSource.SUBSCRIPTION || payment.taxMode() == TaxMode.EXCLUSIVE
                || !"EUR".equals(payment.money().currency()) || payment.money().minorUnits() < 50
                || payment.money().minorUnits() > 99_999_999) throw unavailable();
        return PaymentIntentCreateParams.builder()
            .setAmount(payment.money().minorUnits())
            .setCurrency(payment.money().currency().toLowerCase(Locale.ROOT))
            .addAllowedPaymentMethodType(PaymentIntentCreateParams.AllowedPaymentMethodType.CARD)
            .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.AUTOMATIC)
            .putMetadata("payment_id", payment.id().value().toString())
            .putMetadata("site_id", payment.siteId().value().toString())
            .putMetadata("source", payment.source().name()).build();
    }

    static PaymentApplicationException unavailable() {
        return new PaymentApplicationException("PAYMENT_CAPABILITY_UNAVAILABLE", 422, "Payment capability unavailable");
    }
}
