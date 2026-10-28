package dev.genericpayments.platform.provider.application.service;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.Status;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProviderOperationGuardTest {
    private final Instant now = Instant.parse("2026-10-14T10:00:00Z");
    private final PaymentAccount account = PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),
        "test-account", PaymentProviderType.STRIPE, PaymentAccountMode.SANDBOX, "acct_test", "stripe-sandbox-default");

    @Test
    void expiredWriteWindowNeverPermitsBlindReplay() {
        var dispatched = Instant.parse("2026-10-14T10:00:00Z");
        assertThat(ProviderOperationGuard.withinWriteWindow(dispatched,
            dispatched.plus(Duration.ofHours(23)))).isFalse();
        assertThat(ProviderOperationGuard.withinWriteWindow(dispatched,
            dispatched.plus(Duration.ofHours(23)).minusNanos(1))).isTrue();
    }

    @Test
    void clockRollbackCannotExtendTheWriteWindow() {
        var dispatched = Instant.parse("2026-10-14T10:00:00Z");
        assertThat(ProviderOperationGuard.withinWriteWindow(dispatched, dispatched.minusNanos(1))).isFalse();
        assertThat(ProviderOperationGuard.withinWriteWindow(dispatched, dispatched)).isTrue();
    }

    @Test
    void onlyLiveClaimReturnsTheOriginalOperationKey() {
        assertThat(new ProviderOperationGuard().requireDispatch(operation(Status.DISPATCHING, now.plusSeconds(60)),
            account, "facts-one", now)).isEqualTo("original-key");
    }

    @Test
    void unclaimedUnknownAndReservedOperationsNeverAuthorizeWrites() {
        for (var status : new Status[] {Status.RESERVED, Status.UNKNOWN, Status.FAILED, Status.ACKNOWLEDGED}) {
            assertThatThrownBy(() -> new ProviderOperationGuard().requireDispatch(operation(status, null), account, "facts-one", now))
                .isInstanceOfSatisfying(PaymentApplicationException.class,
                    error -> assertThat(error.code()).isIn("PROVIDER_OPERATION_CONFLICT", "PROVIDER_RESULT_UNKNOWN"));
        }
    }

    @Test
    void expiredLeaseOrWriteWindowCannotAuthorizeWrites() {
        assertUnknown(operation(Status.DISPATCHING, now), now);
        assertUnknown(operation(Status.DISPATCHING, now.plus(Duration.ofDays(2))), now.plus(Duration.ofHours(23)));
        assertUnknown(operation(Status.DISPATCHING, now.plusSeconds(60)), now.minusNanos(1));
    }

    @Test
    void changedFingerprintAccountOrAliasConflictsEvenWithinLiveLease() {
        var operation = operation(Status.DISPATCHING, now.plusSeconds(60));
        assertConflict(operation, account, "changed-money");
        assertConflict(operation, new PaymentAccount(new PaymentAccountId(UUID.randomUUID()), account.key(), account.provider(),
            account.mode(), account.status(), account.externalAccountId(), account.credentialsRef()), "facts-one");
        assertConflict(operation, new PaymentAccount(account.id(), account.key(), account.provider(), account.mode(),
            account.status(), "acct_foreign", account.credentialsRef()), "facts-one");
        assertConflict(operation, new PaymentAccount(account.id(), account.key(), account.provider(), account.mode(),
            account.status(), account.externalAccountId(), "different-alias"), "facts-one");
    }

    @Test
    void acknowledgedReceiptNeverBecomesADispatchKey() {
        assertConflict(operation(Status.ACKNOWLEDGED, null), account, "facts-one");
    }

    private ProviderOperation operation(Status status, Instant expiry) {
        return new ProviderOperation(UUID.randomUUID(), account.id(), account.externalAccountId(), account.credentialsRef(),
            Kind.PAYMENT_PREPARE, UUID.randomUUID(), "original-key", "facts-one", status,
            status == Status.ACKNOWLEDGED ? "pi_existing" : null, now, now.plus(Duration.ofHours(23)),
            expiry == null ? null : "unique-acquisition", expiry, 1);
    }

    private void assertUnknown(ProviderOperation operation, Instant timestamp) {
        assertThatThrownBy(() -> new ProviderOperationGuard().requireDispatch(operation, account, "facts-one", timestamp))
            .isInstanceOfSatisfying(PaymentApplicationException.class,
                error -> assertThat(error.code()).isEqualTo("PROVIDER_RESULT_UNKNOWN"));
    }

    private void assertConflict(ProviderOperation operation, PaymentAccount supplied, String fingerprint) {
        assertThatThrownBy(() -> new ProviderOperationGuard().requireDispatch(operation, supplied, fingerprint, now))
            .isInstanceOfSatisfying(PaymentApplicationException.class,
                error -> assertThat(error.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
    }
}
