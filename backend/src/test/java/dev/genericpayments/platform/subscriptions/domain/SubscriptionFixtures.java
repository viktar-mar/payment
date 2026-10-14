package dev.genericpayments.platform.subscriptions.domain;

import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.UUID;

public final class SubscriptionFixtures {
    private SubscriptionFixtures() {}

    public static Subscription active(Instant start, Instant end) {
        var terms = terms(new ProductId(UUID.randomUUID()), "basic", 1000);
        return new Subscription(UUID.randomUUID(), new SiteId(UUID.randomUUID()),
            new CustomerId(UUID.randomUUID()), terms.productId(), new PaymentAccountId(UUID.randomUUID()),
            terms, terms, Subscription.Status.ACTIVE, "fake-subscription", new Subscription.Period(start, end),
            end, null, null, null, null, null, 1, start, start, null);
    }

    static SubscriptionPriceTerms terms(ProductId product, String key, long amount) {
        return new SubscriptionPriceTerms(new PriceId(UUID.randomUUID()), key, 1, product,
            Money.of(amount, "EUR"), BillingInterval.MONTH, 14,
            TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL, TaxMode.NONE);
    }

    static Subscription facts(Subscription s, Subscription.Status status, SubscriptionPriceTerms billing,
                              SubscriptionPriceTerms entitled, Subscription.Period period, Instant paidThrough,
                              Subscription.Period trial, Subscription.Cancellation cancellation,
                              Subscription.Change change, Subscription.Debt debt) {
        return new Subscription(s.id(), s.siteId(), s.customerId(), s.productId(), s.paymentAccountId(),
            billing, entitled, status, s.providerReference(), period, paidThrough, trial, cancellation,
            change, debt, status == Subscription.Status.CANCELLED ? "confirmed" : null,
            s.revision(), s.createdAt(), s.updatedAt(), s.lastReconciledAt());
    }
}
