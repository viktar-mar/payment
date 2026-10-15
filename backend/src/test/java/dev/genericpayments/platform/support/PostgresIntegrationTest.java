package dev.genericpayments.platform.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@Import({PostgresIntegrationTest.ClockConfiguration.class, PostgresIntegrationTest.ContainerConfiguration.class})
public abstract class PostgresIntegrationTest {
    public static final Instant INITIAL_TIME = Instant.parse("2026-09-13T10:15:30Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetClock() {
        TestClock.set(INITIAL_TIME);
    }

    protected void clearDatabase() {
        truncateDatabase(jdbcTemplate);
    }

    public static void truncateDatabase(JdbcTemplate jdbc) {
        jdbc.execute("""
            truncate table subscription_portal_sessions, subscription_action_capabilities, subscription_quotes,
              subscription_events, subscription_event_sequences, subscription_provider_objects,
              subscription_billing_records, subscription_trial_eligibility, subscription_operations, subscriptions,
              customer_mail_outbox, customer_idempotency_records, identity_link_proofs,
              email_otp_challenges, oidc_authentication_attempts, consumed_site_token_jtis,
              customer_rate_limit_buckets, customer_sessions, customer_identity_subject_aliases, customer_identities, identity_provider_configurations,
              outbox_deliveries, outbox_retry_requests, outbox_events, provider_inbox, provider_operations, fake_provider_operations, fake_provider_objects,
              checkout_sessions, idempotency_records, site_callback_configs,
              site_callback_secret_versions, site_api_keys, payment_events, payment_event_sequences,
              refunds, provider_charge_receipts, payment_client_attempts, payment_attempts, payments, donations, donation_campaign_suggested_amounts,
              donation_campaigns, customers, admin_sessions, admin_users, audit_log,
              provider_price_mappings, provider_product_mappings, prices, price_version_counters,
              products, site_allowed_payment_methods, site_authentication_modes,
              site_allowed_origins, sites, payment_accounts cascade
            """);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        Clock testClock() {
            return new TestClock();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ContainerConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgresContainer() {
            return new PostgreSQLContainer<>("postgres:18-alpine");
        }
    }

    public static final class TestClock extends Clock {
        private static volatile Instant instant = INITIAL_TIME;

        public static void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
