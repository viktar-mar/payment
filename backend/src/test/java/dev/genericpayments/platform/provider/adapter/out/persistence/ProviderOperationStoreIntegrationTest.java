package dev.genericpayments.platform.provider.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.application.service.ProviderTestFixture;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.Status;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ProviderOperationStoreIntegrationTest extends ProviderTestFixture {
    @Autowired ProviderOperationStore operations;
    @Autowired PlatformTransactionManager transactionManager;

    private static final Duration LEASE = Duration.ofSeconds(60);

    @Test
    void reservationProducesADurableReceipt() {
        var operation = reservePayment("prepare-one");
        assertThat(operation).isNotNull();
        assertThat(operations.find(account.id(), "prepare-one")).contains(operation);
    }

    @Test
    void concurrentReservationsReturnOneDurableIdentity() throws Exception {
        var payment = save.save(payment());
        var results = race(() -> operations.reserve(account, Kind.PAYMENT_PREPARE,
            payment.id().value(), "prepare-one", "facts-one"));

        assertThat(results.getFirst()).isEqualTo(results.getLast());
        assertThat(results.getFirst().status()).isEqualTo(Status.RESERVED);
        assertThat(results.getFirst().firstDispatchedAt()).isNull();
        assertThat(results.getFirst().retryBefore()).isNull();
        assertThat(count("provider_operations")).isEqualTo(1);
        assertThat(operations.find(account.id(), "prepare-one")).contains(results.getFirst());
    }

    @Test
    void reservationRejectsChangedFingerprintKeyAndResource() {
        var payment = save.save(payment());
        var other = save.save(payment());
        var reserved = operations.reserve(account, Kind.PAYMENT_PREPARE, payment.id().value(), "prepare-one", "facts-one");

        conflict(() -> operations.reserve(account, Kind.PAYMENT_PREPARE, payment.id().value(), "prepare-one", "changed-amount"));
        conflict(() -> operations.reserve(account, Kind.PAYMENT_PREPARE, payment.id().value(), "new-key", "facts-one"));
        conflict(() -> operations.reserve(account, Kind.PAYMENT_PREPARE, other.id().value(), "prepare-one", "facts-one"));
        assertThat(operations.find(account.id(), "prepare-one")).contains(reserved);
        assertThat(count("provider_operations")).isEqualTo(1);
    }

    @Test
    void reservationPinsOriginalExternalAccountAndCredentialAlias() {
        var payment = save.save(payment());
        var reserved = operations.reserve(account, Kind.PAYMENT_PREPARE, payment.id().value(), "prepare-one", "facts-one");
        var changedIdentity = new PaymentAccount(account.id(), account.key(), account.provider(), account.mode(),
            account.status(), "different-external-account", account.credentialsRef());
        var changedAlias = new PaymentAccount(account.id(), account.key(), account.provider(), account.mode(),
            account.status(), account.externalAccountId(), "different-alias");

        conflict(() -> operations.reserve(changedIdentity, Kind.PAYMENT_PREPARE, payment.id().value(), "prepare-one", "facts-one"));
        conflict(() -> operations.reserve(changedAlias, Kind.PAYMENT_PREPARE, payment.id().value(), "prepare-one", "facts-one"));
        assertThat(operations.find(account.id(), "prepare-one")).contains(reserved);
    }

    @Test
    void unknownResourceAndWrongKindNeverReserve() {
        var payment = save.save(payment());
        conflict(() -> operations.reserve(account, Kind.PAYMENT_PREPARE, UUID.randomUUID(), "missing", "facts"));
        conflict(() -> operations.reserve(account, Kind.PRODUCT_CREATE, payment.id().value(), "wrong-kind", "facts"));
        conflict(() -> operations.reserve(account, null, payment.id().value(), "null-kind", "facts"));
        assertThat(count("provider_operations")).isZero();
    }

    @Test
    void concurrentClaimsAllowOnlyOneLiveOwner() throws Exception {
        var reserved = reservePayment("prepare-one");
        var now = utcNow();
        var results = race(() -> operations.claim(reserved.id(), UUID.randomUUID().toString(), now, LEASE));

        assertThat(results.stream().filter(java.util.Optional::isPresent)).hasSize(1);
        var claimed = results.stream().flatMap(java.util.Optional::stream).findFirst().orElseThrow();
        assertThat(claimed.status()).isEqualTo(Status.DISPATCHING);
        assertThat(claimed.firstDispatchedAt()).isEqualTo(now);
        assertThat(claimed.retryBefore()).isEqualTo(now.plus(Duration.ofHours(23)));
        assertThat(claimed.leaseExpiresAt()).isEqualTo(now.plusSeconds(60));
        assertThat(claimed.version()).isEqualTo(reserved.version() + 1);
    }

    @Test
    void crashAfterClaimPreservesKeyAndFirstDispatchOnReacquisition() {
        var reserved = reservePayment("prepare-one");
        var now = utcNow();
        var claimed = operations.claim(reserved.id(), "first", now, LEASE).orElseThrow();
        assertThat(operations.claim(reserved.id(), "second", now.plusSeconds(59), LEASE)).isEmpty();
        var recovered = operations.claim(reserved.id(), "second", now.plusSeconds(60), LEASE).orElseThrow();
        assertThat(recovered.operationKey()).isEqualTo("prepare-one");
        assertThat(recovered.firstDispatchedAt()).isEqualTo(claimed.firstDispatchedAt());
        assertThat(recovered.retryBefore()).isEqualTo(claimed.retryBefore());
        assertThat(recovered.version()).isEqualTo(claimed.version() + 1);
    }

    @Test
    void expiredRetryWindowCannotBeClaimedOrRekeyed() {
        var reserved = reservePayment("prepare-one");
        var first = utcNow().minus(Duration.ofHours(23));
        operations.claim(reserved.id(), "first", first, LEASE).orElseThrow();
        assertThat(operations.claim(reserved.id(), "retry", first.plus(Duration.ofHours(23)), LEASE)).isEmpty();
        conflict(() -> operations.reserve(account, reserved.kind(), reserved.resourceId(), "replacement-key", reserved.fingerprint()));
    }

    @Test
    void independentReservationAndClaimSurviveCallerRollback() {
        var payment = save.save(payment());
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            var reserved = operations.reserve(account, Kind.PAYMENT_PREPARE, payment.id().value(), "prepare-one", "facts-one");
            operations.claim(reserved.id(), "dispatch", utcNow(), LEASE).orElseThrow();
            throw new IllegalStateException("injected caller rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(operations.find(account.id(), "prepare-one").orElseThrow().status()).isEqualTo(Status.DISPATCHING);
    }

    @Test
    void staleOwnerAndVersionCannotAcknowledgeOrMarkUnknown() {
        var reserved = reservePayment("prepare-one");
        var now = utcNow();
        var stale = operations.claim(reserved.id(), "first", now.minusSeconds(120), LEASE).orElseThrow();
        var current = operations.claim(reserved.id(), "second", now, LEASE).orElseThrow();
        conflict(() -> operations.acknowledge(stale.id(), stale.leaseOwner(), stale.version(), "pi_stale"));
        conflict(() -> operations.unknown(stale.id(), stale.leaseOwner(), stale.version()));
        conflict(() -> operations.acknowledge(current.id(), current.leaseOwner(), stale.version(), "pi_stale"));
        conflict(() -> operations.unknown(current.id(), current.leaseOwner(), stale.version()));
        assertThat(operations.acknowledge(current.id(), current.leaseOwner(), current.version(), "pi_current").providerReference())
            .isEqualTo("pi_current");
    }

    @Test
    void reusedOwnerStillCannotCommitAnEarlierAcquisitionVersion() {
        var reserved = reservePayment("prepare-one");
        var now = utcNow();
        var stale = operations.claim(reserved.id(), "same-worker", now.minusSeconds(120), LEASE).orElseThrow();
        var current = operations.claim(reserved.id(), "same-worker", now, LEASE).orElseThrow();
        conflict(() -> operations.acknowledge(stale.id(), "same-worker", stale.version(), "pi_stale"));
        assertThat(operations.unknown(current.id(), "same-worker", current.version()).status()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void expiredLeaseCannotAcknowledgeEvenIfNobodyReclaimedIt() {
        var reserved = reservePayment("prepare-one");
        var expired = operations.claim(reserved.id(), "expired", utcNow().minusSeconds(120), LEASE).orElseThrow();
        conflict(() -> operations.acknowledge(expired.id(), "expired", expired.version(), "pi_late"));
        conflict(() -> operations.unknown(expired.id(), "expired", expired.version()));
    }

    @Test
    void unknownReplayUsesOriginalKeyAndDeadlineAndAcknowledgedReplayUsesReference() {
        var reserved = reservePayment("prepare-one");
        var first = operations.claim(reserved.id(), "first", utcNow(), LEASE).orElseThrow();
        var unknown = operations.unknown(first.id(), "first", first.version());
        assertThat(unknown.status()).isEqualTo(Status.UNKNOWN);
        assertThat(unknown.leaseOwner()).isNull();
        var replay = operations.claim(first.id(), "replay", utcNow(), LEASE).orElseThrow();
        assertThat(replay.operationKey()).isEqualTo("prepare-one");
        assertThat(replay.retryBefore()).isEqualTo(first.retryBefore());
        var acknowledged = operations.acknowledge(replay.id(), "replay", replay.version(), "pi_existing");
        assertThat(operations.claim(replay.id(), "again", utcNow(), LEASE)).isEmpty();
        assertThat(operations.reserve(account, reserved.kind(), reserved.resourceId(), "prepare-one", "facts-one"))
            .isEqualTo(acknowledged);
        assertThat(acknowledged.providerReference()).isEqualTo("pi_existing");
    }

    @Test
    void acknowledgementSurvivesLocalRollbackAfterProviderResponse() {
        var reserved = reservePayment("prepare-one");
        var claimed = operations.claim(reserved.id(), "first", utcNow(), LEASE).orElseThrow();
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            operations.acknowledge(claimed.id(), "first", claimed.version(), "pi_created");
            throw new IllegalStateException("injected failure after provider response");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(operations.find(account.id(), "prepare-one").orElseThrow().providerReference()).isEqualTo("pi_created");
        assertThat(count("payment_events")).isZero();
        assertThat(jdbc.queryForObject("select status from payments where id=?", String.class, reserved.resourceId())).isEqualTo("CREATED");
    }

    @Test
    void recoveredBindingRequiresMatchingSnapshotAndNoLiveLease() {
        var reserved = reservePayment("prepare-one");
        var claimed = operations.claim(reserved.id(), "first", utcNow(), LEASE).orElseThrow();
        conflict(() -> operations.bindRecovered(claimed, "pi_recovered", "facts-one"));
        var unknown = operations.unknown(claimed.id(), "first", claimed.version());
        conflict(() -> operations.bindRecovered(claimed, "pi_recovered", "facts-one"));
        conflict(() -> operations.bindRecovered(unknown, "pi_recovered", "wrong-facts"));
        var forged = new ProviderOperation(unknown.id(), unknown.accountId(), "acct_wrong", unknown.credentialsRef(),
            unknown.kind(), unknown.resourceId(), unknown.operationKey(), unknown.fingerprint(), unknown.status(),
            unknown.providerReference(), unknown.firstDispatchedAt(), unknown.retryBefore(), unknown.leaseOwner(), unknown.leaseExpiresAt(), unknown.version());
        conflict(() -> operations.bindRecovered(forged, "pi_recovered", "facts-one"));
        assertThat(operations.bindRecovered(unknown, "pi_recovered", "facts-one").providerReference()).isEqualTo("pi_recovered");
    }

    @Test
    void recoveredReferenceCollisionCannotRebindAnotherResource() {
        var first = reservePayment("prepare-one");
        var second = reservePayment("prepare-two");
        var now = utcNow().minus(Duration.ofHours(24));
        var firstExpired = operations.claim(first.id(), "first", now, LEASE).orElseThrow();
        var secondExpired = operations.claim(second.id(), "second", now, LEASE).orElseThrow();
        operations.bindRecovered(firstExpired, "pi_shared", "facts-one");
        conflict(() -> operations.bindRecovered(secondExpired, "pi_shared", "facts-one"));
        assertThat(operations.find(account.id(), "prepare-two").orElseThrow().providerReference()).isNull();
        assertThat(count("payment_events")).isZero();
    }

    @Test
    void everyKindChecksLocalResourceOwnership() {
        var payment = save.save(payment());
        var productId = UUID.randomUUID();
        var priceId = UUID.randomUUID();
        var refundId = UUID.randomUUID();
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,'product','Product','ACTIVE',now(),now())", productId, site.value());
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,tax_mode,status,created_at,updated_at) values (?,?,?,'price',1,'ONE_TIME',2000,'EUR','NONE','DRAFT',now(),now())", priceId, site.value(), productId);
        jdbc.update("insert into refunds(id,payment_id,amount_minor,currency,status,provider_operation_key,created_at,updated_at) values (?,?,100,'EUR','PENDING','refund-key',now(),now())", refundId, payment.id().value());
        var foreign = PaymentAccount.create(new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(UUID.randomUUID()),
            "foreign-account", account.provider(), account.mode(), "foreign-external", account.credentialsRef());
        jdbc.update("insert into payment_accounts values (?, ?, 'FAKE', 'SANDBOX', 'ACTIVE', ?, ?, now(), 0)",
            foreign.id().value(), foreign.key(), foreign.externalAccountId(), foreign.credentialsRef());
        var resources = java.util.Map.of(Kind.PRODUCT_CREATE, productId, Kind.PRICE_CREATE, priceId,
            Kind.PAYMENT_PREPARE, payment.id().value(), Kind.REFUND_CREATE, refundId);
        resources.forEach((kind, id) -> {
            conflict(() -> operations.reserve(foreign, kind, id, kind.name(), "facts"));
            assertThat(operations.reserve(account, kind, id, kind.name(), "facts").resourceId()).isEqualTo(id);
        });
        assertThat(count("provider_operations")).isEqualTo(4);
    }

    @Test
    void databaseRejectsAliasedKeysAndMissingDispatchBounds() {
        var operation = reservePayment("prepare-one");
        assertThatThrownBy(() -> jdbc.update("""
            insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,
                resource_id,operation_key,fingerprint,status)
            select ?,payment_account_id,external_account_id,credentials_ref,kind,?,operation_key,fingerprint,status
            from provider_operations where id=?
            """, UUID.randomUUID(), UUID.randomUUID(), operation.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update provider_operations set status='DISPATCHING' where id=?", operation.id()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update provider_operations set kind='UNKNOWN_KIND' where id=?", operation.id()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select recovery_attempts from provider_operations where id=?", Integer.class, operation.id())).isZero();
        assertThat(jdbc.queryForObject("select manual_review_required from provider_operations where id=?", Boolean.class, operation.id())).isFalse();
    }

    @Test
    void failedAcknowledgementTransactionRetainsOriginalDispatchIdentityForReplay() {
        var reserved = reservePayment("prepare-one");
        var claimed = operations.claim(reserved.id(), "first", utcNow(), LEASE).orElseThrow();
        jdbc.execute("""
            create function task3_reject_acknowledgement() returns trigger language plpgsql as $$
            begin
                if new.provider_reference='pi_injected_failure' then
                    raise exception 'injected acknowledgement rollback' using errcode='23514';
                end if;
                return new;
            end; $$
            """);
        jdbc.execute("create trigger task3_reject_acknowledgement before update on provider_operations for each row execute function task3_reject_acknowledgement()");
        try {
            conflict(() -> operations.acknowledge(claimed.id(), "first", claimed.version(), "pi_injected_failure"));
            assertThat(operations.find(account.id(), "prepare-one")).contains(claimed);
        } finally {
            jdbc.execute("drop trigger task3_reject_acknowledgement on provider_operations");
            jdbc.execute("drop function task3_reject_acknowledgement()");
        }
        operations.unknown(claimed.id(), "first", claimed.version());
        var replay = operations.claim(claimed.id(), "replay", utcNow(), LEASE).orElseThrow();
        assertThat(replay.operationKey()).isEqualTo("prepare-one");
        assertThat(replay.firstDispatchedAt()).isEqualTo(claimed.firstDispatchedAt());
        assertThat(count("payment_events")).isZero();
    }

    @Test
    void changedPersistedAccountIdentityPreventsFirstDispatch() {
        var reserved = reservePayment("prepare-one");
        jdbc.update("update payment_accounts set external_account_id='different-account' where id=?", account.id().value());
        conflict(() -> operations.claim(reserved.id(), "first", utcNow(), LEASE));
        assertThat(operations.find(account.id(), "prepare-one")).contains(reserved);
    }

    @Test
    void freshPriceVersionCanReserveWhileChangedDispatchedVersionConflicts() {
        var productId = UUID.randomUUID();
        var firstPrice = UUID.randomUUID();
        var secondPrice = UUID.randomUUID();
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,'product','Product','ACTIVE',now(),now())", productId, site.value());
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,tax_mode,status,created_at,updated_at) values (?,?,?,'price',1,'ONE_TIME',2000,'EUR','NONE','DRAFT',now(),now()),(?,?,?,'price',2,'ONE_TIME',3000,'EUR','NONE','DRAFT',now(),now())",
            firstPrice, site.value(), productId, secondPrice, site.value(), productId);
        var original = operations.reserve(account, Kind.PRICE_CREATE, firstPrice, "price-one", "version-one-2000-eur-none");
        operations.claim(original.id(), "first", utcNow(), LEASE).orElseThrow();
        conflict(() -> operations.reserve(account, Kind.PRICE_CREATE, firstPrice, "price-one", "version-one-3000-eur-none"));
        var next = operations.reserve(account, Kind.PRICE_CREATE, secondPrice, "price-two", "version-two-3000-eur-none");
        assertThat(next.resourceId()).isEqualTo(secondPrice);
        assertThat(count("provider_operations")).isEqualTo(2);
    }

    @Test
    void invalidClaimLeaseOrOwnerCannotMutateReservation() {
        var reserved = reservePayment("prepare-one");
        conflict(() -> operations.claim(reserved.id(), "", utcNow(), LEASE));
        conflict(() -> operations.claim(reserved.id(), "first", utcNow(), Duration.ofDays(1)));
        conflict(() -> operations.claim(reserved.id(), "first", utcNow(), Duration.ZERO));
        assertThat(operations.find(account.id(), "prepare-one")).contains(reserved);
    }

    private ProviderOperation reservePayment(String key) {
        var payment = save.save(payment());
        return operations.reserve(account, Kind.PAYMENT_PREPARE, payment.id().value(), key, "facts-one");
    }

    private static Instant utcNow() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    private static void conflict(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class,
            error -> assertThat(error.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
    }

    private static <T> List<T> race(Callable<T> operation) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<T> concurrent = () -> { barrier.await(10, TimeUnit.SECONDS); return operation.call(); };
            var first = pool.submit(concurrent);
            var second = pool.submit(concurrent);
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
    }
}
