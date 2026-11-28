package dev.genericpayments.platform.provider.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertAll;

import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.Status;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Bare scripted JDBC only: actual public store admission; no DataSource, context, SDK or runtime. */
class OwnedRecoveryProviderGuardTest {
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final String OWNER = "negative-legacy-owner";
    private static final String INVOICE = "in_original";

    // Removing INVOICE_PAY from the common clock-intent exclusion must break each owned case.
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void reservationUsesCommonOwnedExclusionBeforeInsert(boolean owned) {
        var jdbc = new RecoveryJdbc(owned, null);
        verify(jdbc, () -> new JdbcProviderOperationStore(jdbc).reserve(jdbc.account, Kind.INVOICE_PAY,
            jdbc.resource, jdbc.key, jdbc.fingerprint), Status.RESERVED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void firstClaimUsesCommonOwnedExclusionBeforeDispatchUpdate(boolean owned) {
        var jdbc = new RecoveryJdbc(owned, Status.RESERVED);
        verify(jdbc, () -> new JdbcProviderOperationStore(jdbc).claim(jdbc.receipt.id(), OWNER, NOW,
            Duration.ofSeconds(60)).orElseThrow(), Status.DISPATCHING);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acknowledgmentUsesCommonOwnedExclusionBeforeAckUpdate(boolean owned) {
        var jdbc = new RecoveryJdbc(owned, Status.DISPATCHING);
        verify(jdbc, () -> new JdbcProviderOperationStore(jdbc).acknowledge(jdbc.receipt.id(), OWNER,
            jdbc.receipt.version(), INVOICE), Status.ACKNOWLEDGED);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void manualBindingUsesCommonOwnedExclusionBeforeBindingUpdate(boolean owned) {
        var jdbc = new RecoveryJdbc(owned, Status.UNKNOWN);
        verify(jdbc, () -> new JdbcProviderOperationStore(jdbc).bindRecovered(jdbc.receipt, INVOICE,
            jdbc.fingerprint), Status.ACKNOWLEDGED);
    }

    private void verify(RecoveryJdbc jdbc, java.util.function.Supplier<ProviderOperation> action, Status ordinaryStatus) {
        var original = jdbc.receipt;
        if (jdbc.owned) {
            var failure = catchThrowable(action::get);
            assertAll(
                () -> assertThat(failure).isInstanceOfSatisfying(PaymentApplicationException.class,
                    e -> assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT")),
                () -> assertThat(jdbc.mutations).as("owned receipt mutation must not be reached").isEmpty(),
                () -> assertThat(jdbc.receipt).as("original legacy receipt remains unchanged").isEqualTo(original),
                () -> assertThat(jdbc.ownershipReads).as("durable clock ownership is checked").isEqualTo(1));
        } else {
            var result = action.get();
            assertThat(result.status()).isEqualTo(ordinaryStatus);
            assertThat(jdbc.mutations).hasSize(1);
            assertThat(result.resourceId()).isEqualTo(jdbc.resource);
            assertThat(result.operationKey()).isEqualTo(jdbc.key);
            assertThat(result.fingerprint()).isEqualTo(jdbc.fingerprint);
            if (ordinaryStatus == Status.ACKNOWLEDGED) assertThat(result.providerReference()).isEqualTo(INVOICE);
        }
    }

    /** Models immutable Account/Site/Customer ownership and otherwise valid RECOVER joins.
     * It never manufactures a conflict: missing production admission reaches a real modeled mutation.
     * Legacy DISPATCHING/UNKNOWN receipts are negative inputs only, never financial evidence. */
    private static final class RecoveryJdbc extends JdbcTemplate {
        final PaymentAccount account = PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()), "guard",
            PaymentProviderType.STRIPE, PaymentAccountMode.SANDBOX, "acct_guard", "inert-alias");
        final UUID resource = UUID.randomUUID();
        final String key = "subscription:" + account.id().value() + ":" + resource + ":RECOVER:INVOICE_PAY";
        final String fingerprint = "original-request-fingerprint";
        final boolean owned;
        final List<String> mutations = new ArrayList<>();
        int ownershipReads;
        ProviderOperation receipt;

        RecoveryJdbc(boolean owned, Status initial) {
            this.owned = owned;
            if (initial != null) receipt = operation(UUID.randomUUID(), initial, initial == Status.RESERVED ? 0 : 1);
        }

        private ProviderOperation operation(UUID id, Status status, long version) {
            boolean dispatched = status != Status.RESERVED;
            boolean leased = status == Status.DISPATCHING;
            return new ProviderOperation(id, account.id(), account.externalAccountId(), account.credentialsRef(),
                Kind.INVOICE_PAY, resource, key, fingerprint, status, status == Status.ACKNOWLEDGED ? INVOICE : null,
                dispatched ? NOW.minusSeconds(120) : null, dispatched ? NOW.plusSeconds(82_000) : null,
                leased ? OWNER : null, leased ? NOW.plusSeconds(60) : null, version);
        }

        @Override @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
            assertThat(type).isEqualTo(UUID.class);
            if (sql.contains("join stripe_billing_clock_intents")) {
                assertThat(sql).contains("i.payment_account_id=s.payment_account_id", "i.site_id=s.site_id", "i.customer_id=s.customer_id");
                assertThat(args).containsExactly(resource, account.id().value());
                ownershipReads++;
                return (List<T>) (owned ? List.of(UUID.randomUUID()) : List.<UUID>of());
            }
            if (sql.contains("from payment_accounts")) {
                assertThat(args[0]).isEqualTo(account.id().value());
                return (List<T>) List.of(account.id().value());
            }
            if (sql.contains("from subscription_operations o join subscriptions s") && sql.contains("o.kind='RECOVER'")) {
                assertThat(args).containsExactly(resource, account.id().value(), key);
                return (List<T>) List.of(resource);
            }
            if (sql.contains("from subscription_operations o join subscription_billing_records b")) {
                assertThat(args).containsExactly(resource, account.id().value(), INVOICE);
                return (List<T>) List.of(resource);
            }
            throw new AssertionError("Unexpected scripted read: " + sql);
        }

        @Override public int update(String sql, Object... args) {
            assertThat(sql).contains("insert into provider_operations");
            assertThat(receipt).isNull();
            mutations.add("insert");
            receipt = operation((UUID) args[0], Status.RESERVED, 0);
            return 1;
        }

        @Override @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            if (sql.stripLeading().startsWith("update provider_operations")) {
                assertThat(receipt).isNotNull();
                mutations.add("update");
                var status = sql.contains("status='DISPATCHING'") && sql.contains("first_dispatched_at=coalesce")
                    ? Status.DISPATCHING : Status.ACKNOWLEDGED;
                receipt = operation(receipt.id(), status, receipt.version() + 1);
                return (List<T>) List.of(receipt);
            }
            assertThat(sql).contains("select * from provider_operations");
            return (List<T>) (receipt == null ? List.<ProviderOperation>of() : List.of(receipt));
        }
    }
}
