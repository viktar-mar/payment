package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import dev.genericpayments.platform.catalog.domain.BillingInterval;
import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountMode;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.provider.application.port.out.StripePortalConfigurationPort.Scope;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPolicy;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

class BillingPortalSqlIntegrationTest extends PostgresIntegrationTest {
    @Autowired DataSource source;
    @Autowired ObjectMapper json;

    @Test void ownedPortalReceiptsPreserveTheOriginalPaidPeriodAndCash() throws Exception {
        var dir = Path.of("../infrastructure");
        try (var connection = source.getConnection(); var sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                sql.execute(Files.readString(dir.resolve("fixtures/stripe-billing-cancellation-proof-rows.sql")));
                sql.execute(Files.readString(dir.resolve("fixtures/stripe-billing-portal-proof-rows.sql")));
                try (var descriptor = connection.prepareStatement(
                    "update pg_temp.stripe_portal_session_intents set descriptor=?::jsonb")) {
                    descriptor.setString(1, json.writeValueAsString(portalScope()));
                    descriptor.executeUpdate();
                }
                sql.execute("prepare owned_portal_proof as " + Files.readString(dir.resolve("stripe-billing-portal-proof.sql")));
                sql.execute("call pg_temp.assert_portal_proof()");
            } finally {
                connection.rollback();
            }
        }
    }

    static Scope portalScope() {
        var accountId = new PaymentAccountId(id(1));
        var siteId = new SiteId(id(2));
        var customerId = new CustomerId(id(3));
        var productId = new ProductId(id(7));
        var account = PaymentAccount.create(accountId, "stripe-sandbox-default", PaymentProviderType.STRIPE,
            PaymentAccountMode.SANDBOX, "acct_1UPiMpELswAd8tpR", "stripe-sandbox-default");
        var binding = new CustomerBinding(id(54), accountId, siteId, customerId, "cus_orig", id(30), 0);
        var time = Instant.parse("2026-09-30T00:00:00Z");
        var end = time.plusSeconds(30 * 86400);
        var operation = new SubscriptionOperation(id(55), siteId, customerId, id(4), SubscriptionOperation.Kind.PORTAL,
            SubscriptionOperation.Status.RESERVED, "customer:" + customerId.value(), "3".repeat(64), "4".repeat(64),
            "subscription:" + accountId.value() + ":" + id(55) + ":PORTAL", 3, 1, "sub_orig", null, time, time, 0);
        var terms = new SubscriptionPriceTerms(new PriceId(id(8)), "fixture-price", 1, productId, new Money(2000, "EUR"),
            BillingInterval.MONTH, 0, TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL, TaxMode.INCLUSIVE);
        var member = new Subscription(id(4), siteId, customerId, productId, accountId, terms, terms,
            Subscription.Status.ACTIVE, "sub_orig", new Subscription.Period(time, end), end, null, null, null, null,
            null, 3, time, time, time);
        var returnUrl = "http://127.0.0.1:18081/?site_key=s5a-fixture&locale=en";
        var policy = new SubscriptionPolicy(siteId, 1, true, 3, false, true, false, Set.of(returnUrl));
        return new Scope(account, binding, operation, policy, List.of(member), returnUrl);
    }

    private static UUID id(int number) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(number));
    }
}
