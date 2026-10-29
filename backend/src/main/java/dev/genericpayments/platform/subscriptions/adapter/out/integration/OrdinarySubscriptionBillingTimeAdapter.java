package dev.genericpayments.platform.subscriptions.adapter.out.integration;

import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** No override registration or native clock activation exists in the production adapter. */
@Component
public class OrdinarySubscriptionBillingTimeAdapter implements SubscriptionBillingTimePort {
    public Sample sample(PaymentAccountId account, SiteId site, CustomerId customer, Instant at) {
        return new Sample(at, Mode.ORDINARY);
    }
    public void requireUnscopedAllowed(SiteId site, CustomerId customer) {}
}
