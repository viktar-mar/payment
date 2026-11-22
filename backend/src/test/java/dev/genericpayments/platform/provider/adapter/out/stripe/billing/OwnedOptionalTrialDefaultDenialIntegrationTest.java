package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort;
import dev.genericpayments.platform.support.OwnedOptionalTrialFixture;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;

@Import(OwnedOptionalTrialDefaultDenialIntegrationTest.ClockOnly.class)
class OwnedOptionalTrialDefaultDenialIntegrationTest extends OwnedOptionalTrialFixture {
    @Autowired StripeCredentialResolver credentials;
    @Autowired StripeClientFactory clients;
    @Autowired ApplySubscriptionSnapshotUseCase facts;

    @TestConfiguration(proxyBeanMethods=false)
    static class ClockOnly {
        @Bean @Primary SubscriptionBillingTimePort timing(JdbcTemplate jdbc) {
            return new SubscriptionBillingTimePort() {
                public Sample sample(PaymentAccountId account, SiteId site, CustomerId customer, Instant at) {
                    long frozen = jdbc.queryForObject("""
                        select frozen_time from stripe_billing_optional_trial_acknowledgements
                        where account_id=? and site_id=? and customer_id=?
                        """, Long.class,account.value(),site.value(),customer.value());
                    return new Sample(Instant.ofEpochSecond(frozen),Mode.ISOLATED);
                }
                public void requireUnscopedAllowed(SiteId site, CustomerId customer) { throw new IllegalStateException(); }
            };
        }
    }

    @Test void genuineAckAndExactIsolatedClockStillRequireAnExplicitTrialProofAdapter() {
        var r = reserveOptionalTrial();
        var scope = new OwnedOptionalTrialCreate.Scope(r.owner().run(),r.owner().intent(),
            r.reserved().subscription().id(),r.reserved().operation().id());
        var a = new JdbcOwnedOptionalTrialCreateStore(jdbc).admitted(scope);
        OwnedOptionalTrialSdkReplies.queue(http,a,"");
        OwnedOptionalTrialCreate.withDatabase(jdbc,credentials,clients,verification).create(billing,scope);
        var forged = OwnedOptionalTrialPublicationIntegrationTest.forgedCommand(jdbc,billing,a);
        assertThatThrownBy(() -> transaction.required(() -> { facts.apply(forged); return null; }))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("ISOLATED_BILLING_UNSUPPORTED");
        assertThat(count("subscription_billing_records")).isZero();
        assertThat(count("subscription_events")).isZero();
        assertThat(count("payments")).isZero();
        assertThat(jdbc.queryForObject("select billing_status from subscriptions",String.class)).isEqualTo("INCOMPLETE");
    }
}
