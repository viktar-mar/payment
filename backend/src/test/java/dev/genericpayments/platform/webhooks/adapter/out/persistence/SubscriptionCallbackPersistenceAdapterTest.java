package dev.genericpayments.platform.webhooks.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.shared.application.port.out.PendingOutboxTelemetryPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SubscriptionCallbackPersistenceAdapterTest {
    private static final Instant UTC = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant BILLING = Instant.parse("2026-02-05T00:00:00Z");
    private final RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    private final RecordingTelemetry pending = new RecordingTelemetry();
    private final SubscriptionCallbackPersistenceAdapter adapter = new SubscriptionCallbackPersistenceAdapter(jdbc, pending);
    private final Subscription subscription = subscription();

    @BeforeEach void setUp() {
        TransactionSynchronizationManager.initSynchronization();
    }
    @AfterEach void tearDown() { TransactionSynchronizationManager.clearSynchronization(); }

    @Test void futureBillingSnapshotKeepsBillingPayloadAndQueuesAtUtc() throws Exception {
        // Using enqueue time for access, display or occurrence breaks this snapshot.
        adapter.snapshot(subscription, event(subscription, BILLING), access(BILLING), UTC);
        assertThat(jdbc.inserts).hasSize(1);
        var row = jdbc.inserts.getFirst();
        var json = new ObjectMapper().readTree((String) row[7]);
        assertThat(json.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(json.path("access_allowed").asBoolean()).isFalse();
        assertThat(json.path("access_valid_until").isNull()).isTrue();
        assertThat(json.path("occurred_at").asText()).isEqualTo("2026-02-05T00:00:00Z");
        assertThat(row[10]).isEqualTo(Timestamp.from(UTC));
        assertThat(row[11]).isEqualTo(Timestamp.from(UTC));
        assertThat(row[12]).isEqualTo(Timestamp.from(UTC));
        assertThat(pending.refreshes).isZero();
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        assertThat(pending.refreshes).isEqualTo(1);
    }

    @Test void ordinaryEqualTimeSnapshotRetainsAccessAndDisplay() throws Exception {
        adapter.snapshot(subscription, event(subscription, UTC), access(UTC), UTC);
        var json = new ObjectMapper().readTree((String) jdbc.inserts.getFirst()[7]);
        assertThat(json.path("state").asText()).isEqualTo("CANCEL_AT_PERIOD_END");
        assertThat(json.path("access_allowed").asBoolean()).isTrue();
        assertThat(json.path("access_valid_until").asText()).isEqualTo("2026-01-01T00:05:00Z");
        assertThat(json.path("occurred_at").asText()).isEqualTo("2026-01-01T00:00:00Z");
    }

    @Test void wrongOccurrenceRejectsBeforeAnySql() {
        reject(subscription, event(subscription, UTC.plusSeconds(1)), access(UTC), UTC);
    }

    @Test void incompleteAccessEqualityRejectsBeforeAnySql() {
        var correct = access(UTC);
        var wrong = List.of(
            new AccessDecision(true, AccessDecision.Reason.GRACE, correct.siteId(), correct.customerId(), correct.productId(), correct.subscriptionId(), correct.entitledPriceId(), correct.entitledPriceVersion(), correct.revision(), UTC, correct.validUntil()),
            new AccessDecision(true, correct.reason(), correct.siteId(), new CustomerId(UUID.randomUUID()), correct.productId(), correct.subscriptionId(), correct.entitledPriceId(), correct.entitledPriceVersion(), correct.revision(), UTC, correct.validUntil()),
            new AccessDecision(true, correct.reason(), correct.siteId(), correct.customerId(), correct.productId(), correct.subscriptionId(), correct.entitledPriceId(), correct.entitledPriceVersion(), correct.revision(), UTC, UTC.plusSeconds(299)));
        for (var candidate : wrong) reject(subscription, event(subscription, UTC), candidate, UTC);
    }

    @Test void everyEventOwnershipFieldRejectsBeforeAnySql() {
        var valid = event(subscription, UTC);
        for (var invalid : List.of(
            new SubscriptionEvent(valid.id(), new SiteId(UUID.randomUUID()), valid.subscriptionId(), valid.accountId(), 1, valid.revision(), valid.type(), valid.source(), UTC, UTC, null, null, null, null, "test", null),
            new SubscriptionEvent(valid.id(), valid.siteId(), UUID.randomUUID(), valid.accountId(), 1, valid.revision(), valid.type(), valid.source(), UTC, UTC, null, null, null, null, "test", null),
            new SubscriptionEvent(valid.id(), valid.siteId(), valid.subscriptionId(), new PaymentAccountId(UUID.randomUUID()), 1, valid.revision(), valid.type(), valid.source(), UTC, UTC, null, null, null, null, "test", null),
            new SubscriptionEvent(valid.id(), valid.siteId(), valid.subscriptionId(), valid.accountId(), 1, valid.revision() + 1, valid.type(), valid.source(), UTC, UTC, null, null, null, null, "test", null))) {
            reject(subscription, invalid, access(UTC), UTC);
        }
    }

    @Test void nullArgumentsRejectBeforeAnySql() {
        var valid = event(subscription, UTC);
        reject(null, valid, access(UTC), UTC);
        reject(subscription, null, access(UTC), UTC);
        reject(subscription, valid, null, UTC);
        reject(subscription, valid, access(UTC), null);
    }

    private void reject(Subscription s, SubscriptionEvent e, AccessDecision decision, Instant enqueue) {
        assertThatThrownBy(() -> adapter.snapshot(s, e, decision, enqueue)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.calls).isZero();
        assertThat(pending.refreshes).isZero();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }
    /** Recording mocks never create a DataSource or call JdbcTemplate's implementation. */
    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<Object[]> inserts = new ArrayList<>();
        private int calls;
        @Override public List<Map<String,Object>> queryForList(String sql, Object... args) {
            calls++;
            return List.of(Map.of("endpoint_url", "https://example.com/callback", "active_secret_version_id", UUID.randomUUID()));
        }
        @Override public int update(String sql, Object... args) {
            calls++;
            var row = new Object[args.length + 1];
            row[0] = sql;
            System.arraycopy(args, 0, row, 1, args.length);
            inserts.add(row);
            return 1;
        }
    }
    private static final class RecordingTelemetry implements PendingOutboxTelemetryPort {
        private int refreshes;
        @Override public void requestRefresh() { refreshes++; }
        @Override public void refreshPending() { throw new AssertionError("Unexpected synchronous refresh"); }
    }
    private AccessDecision access(Instant at) { return new SubscriptionAccessPolicy().evaluate(subscription, at); }
    private static SubscriptionEvent event(Subscription s, Instant at) {
        return new SubscriptionEvent(UUID.randomUUID(), s.siteId(), s.id(), s.paymentAccountId(), 1, s.revision(), SubscriptionEvent.Type.PERIOD_PAID, SubscriptionEvent.Source.PROVIDER_WEBHOOK, at, UTC, null, null, null, null, "test", null);
    }
    private static Subscription subscription() {
        var product = new ProductId(UUID.randomUUID());
        var terms = new SubscriptionPriceTerms(new PriceId(UUID.randomUUID()), "plan", 1, product, Money.of(2000, "EUR"), BillingInterval.MONTH, 0, TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL, TaxMode.NONE);
        var end = UTC.plusSeconds(86400);
        return new Subscription(UUID.randomUUID(), new SiteId(UUID.randomUUID()), new CustomerId(UUID.randomUUID()), product, new PaymentAccountId(UUID.randomUUID()), terms, terms, Subscription.Status.ACTIVE, "sub-test", new Subscription.Period(UTC, end), end, null, new Subscription.Cancellation(end), null, null, null, 1, UTC, UTC, null);
    }
}
