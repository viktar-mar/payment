package dev.genericpayments.platform.support;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/** Hosted-only composition. Renewal selection is deliberately absent for the initial RED checkpoint. */
public final class OwnedFirstRenewalBillingTime implements SubscriptionBillingTimePort {
    private final OwnedSubscriptionBillingTime original;
    public Object sampledConnectionHolder;
    public String sampledTransaction;

    public OwnedFirstRenewalBillingTime(JdbcTemplate jdbc,OwnedSubscriptionBillingTime original) {
        this.original=original;
    }

    @Override public Sample sample(PaymentAccountId account,SiteId site,CustomerId customer,Instant utcAt) {
        var sample=original.sample(account,site,customer,utcAt);
        sampledConnectionHolder=original.sampledConnectionHolder;
        sampledTransaction=original.sampledTransaction;
        return sample;
    }

    @Override public void requireUnscopedAllowed(SiteId site,CustomerId customer) {
        original.requireUnscopedAllowed(site,customer);
    }
}
