package dev.genericpayments.platform.subscriptions.application.port.out;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import java.time.Instant;
import java.util.Objects;

/** Exact-owner lifecycle evaluation, separate from the caller's operational clock.
 * Isolated mode is limited to unchanged-term paid/unpaid projection, exact reads and expiry.
 * It does not authorize provider commands, trials or native TestClock use. */
public interface SubscriptionBillingTimePort {
    enum Mode { ORDINARY, ISOLATED }
    record Sample(Instant billingAt, Mode mode) {
        public Sample { Objects.requireNonNull(billingAt); Objects.requireNonNull(mode); }
        public boolean isolated() { return mode == Mode.ISOLATED; }
    }
    /** Ownership must come from an authenticated/locked Subscription, never request time/clock IDs. */
    Sample sample(PaymentAccountId account, SiteId site, CustomerId customer, Instant operationalAt);
    /** Mandatory before SQL selecting a list or deadline candidates. Null site means global;
     * null customer means all Customers. Implementations must refuse any scope that can include
     * isolated time. Ordinary adapters permit every scope. */
    void requireUnscopedAllowed(SiteId site, CustomerId customer);

    /** Compatibility for direct constructors and historical Fake clock injection. */
    static SubscriptionBillingTimePort ordinary() {
        return new SubscriptionBillingTimePort() {
            public Sample sample(PaymentAccountId account, SiteId site, CustomerId customer, Instant at) {
                return new Sample(at, Mode.ORDINARY);
            }
            public void requireUnscopedAllowed(SiteId site, CustomerId customer) {}
        };
    }
}
