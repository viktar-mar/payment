package dev.genericpayments.platform.payments.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord.*;
import dev.genericpayments.platform.payments.application.service.IdempotencyService;
import dev.genericpayments.platform.payments.application.service.IdempotencyService.*;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.Operation;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord;
import dev.genericpayments.platform.payments.application.port.out.IdempotencyPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class IdempotencyConcurrencyIntegrationTest extends PostgresIntegrationTest {
    @Autowired IdempotencyService service;
    @Autowired IdempotencyCleanupJob cleanup;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EncryptedSecretPort encryption;
    @Autowired IdempotencyPort records;
    private final UUID site=UUID.fromString("aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb");
    private final Scope scope=Scope.partner(site,Operation.PAYMENT_CREATE);
    private final String fingerprint="a".repeat(64);
    @BeforeEach void clear(){clearDatabase();}
    private Owner begin(){return (Owner)service.begin(scope,"key",fingerprint);}

    @ParameterizedTest @EnumSource(ResourceType.class)
    void everyAcceptedCompletedSecretShapeLoadsAndReplays(ResourceType type) {
        var resource=new Resource(type,UUID.randomUUID());
        for (SecretState state:SecretState.values()) {
            var material=state == SecretState.LIVE ? encryption.encrypt("replay-fragment") : null;
            var secretExpiry=state == SecretState.NONE ? null : (state == SecretState.LIVE ? INITIAL_TIME.plusSeconds(60) : INITIAL_TIME);
            String key=state.name();
            jdbc.update("""
                insert into idempotency_records(id,scope,idempotency_key,fingerprint,status,resource_type,resource_id,response_status,
                  response_secret_state,response_secret_ciphertext,response_secret_nonce,response_secret_key_version,response_secret_expires_at,
                  expires_at,created_at,updated_at)
                values (?,?,?,?,'COMPLETED',?,?,201,?,?,?,?,?,?,?,?)
                """,UUID.randomUUID(),scope.value(),key,fingerprint,type.name(),resource.id(),state.name(),
                material == null ? null : material.ciphertext(),material == null ? null : material.nonce(),material == null ? null : material.keyVersion(),
                secretExpiry == null ? null : java.sql.Timestamp.from(secretExpiry),java.sql.Timestamp.from(INITIAL_TIME.plus(Duration.ofDays(30))),
                java.sql.Timestamp.from(INITIAL_TIME.minusSeconds(60)),java.sql.Timestamp.from(INITIAL_TIME));
            var claim=records.claim(IdempotencyRecord.begin(UUID.randomUUID(),scope,key,fingerprint,INITIAL_TIME),INITIAL_TIME);
            assertThat(claim.owner()).isFalse();
            assertThat(claim.record().secretState()).isEqualTo(state);
            assertThat(claim.record().resource()).isEqualTo(resource);
            var outcome=service.begin(scope,key,fingerprint);
            if (state == SecretState.EXPIRED) {
                assertThat(outcome).isEqualTo(new ExpiredSecret(resource,201));
            } else {
                var replay=(Completed)outcome;
                assertThat(replay.resource()).isEqualTo(resource);
                assertThat(replay.responseStatus()).isEqualTo(201);
                assertThat(replay.secret()).isEqualTo(state == SecretState.LIVE ? Optional.of("replay-fragment") : Optional.empty());
            }
        }
    }

    @Test void concurrentFirstCallHasExactlyOneOwnerAndOneInProgressReplay() throws Exception {
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Outcome> work=() -> {ready.countDown();start.await();return service.begin(scope,"key",fingerprint);};
            var first=pool.submit(work);var second=pool.submit(work); assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue(); start.countDown();
            var outcomes=List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(Owner.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(InProgress.class::isInstance).hasSize(1);
            assertThat(jdbc.queryForObject("select count(*) from idempotency_records",Integer.class)).isEqualTo(1);
        }
    }

    @Test void mismatchIsBusinessConflictAndScopeSeparatesSitesAndActors() {
        begin(); assertThat(service.begin(scope,"key","b".repeat(64))).isInstanceOf(Conflict.class);
        assertThat(service.begin(Scope.partner(UUID.randomUUID(),Operation.PAYMENT_CREATE),"key",fingerprint)).isInstanceOf(Owner.class);
        assertThat(service.begin(Scope.admin(site,UUID.randomUUID(),Operation.PAYMENT_CREATE),"key",fingerprint)).isInstanceOf(Owner.class);
        assertThat(scope.value()).isEqualTo("partner:aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb:payments:create");
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records",Integer.class)).isEqualTo(3);
    }

    @Test void completionReplaysOriginalSecretReferenceAndExpiresWithoutReplacement() {
        var owner=begin(); var resource=new Resource(ResourceType.PAYMENT,UUID.randomUUID());
        TestClock.set(INITIAL_TIME.plusSeconds(5));
        var tokenExpiry=INITIAL_TIME.plusSeconds(60);
        service.complete(owner,resource,201,new SecretFragment("temporary-fragment",tokenExpiry));
        var replay=(Completed)service.begin(scope,"key",fingerprint);
        assertThat(replay.resource()).isEqualTo(resource); assertThat(replay.responseStatus()).isEqualTo(201);
        assertThat(replay.secret()).isEqualTo(Optional.of("temporary-fragment"));
        assertThat(jdbc.queryForObject("select response_secret_key_version from idempotency_records",String.class)).isEqualTo("test-v1");
        assertThat(new String(jdbc.queryForObject("select response_secret_ciphertext from idempotency_records",byte[].class),StandardCharsets.UTF_8))
            .doesNotContain("temporary-fragment");
        assertThat(jdbc.queryForObject("select expires_at from idempotency_records",java.sql.Timestamp.class).toInstant())
            .isEqualTo(INITIAL_TIME.plusSeconds(5).plus(Duration.ofDays(30)));
        TestClock.set(tokenExpiry);
        var expired=(ExpiredSecret)service.begin(scope,"key",fingerprint);
        assertThat(expired.resource()).isEqualTo(resource); assertThat(expired.responseStatus()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select response_secret_state from idempotency_records",String.class)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForMap("select response_secret_ciphertext,response_secret_nonce,response_secret_key_version from idempotency_records").values()).containsOnlyNulls();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records",Integer.class)).isEqualTo(1);
    }

    @Test void validatesCompletionAndRejectsStaleOrMismatchedOwner() {
        var owner=begin();var resource=new Resource(ResourceType.PAYMENT,UUID.randomUUID());
        assertThatIllegalArgumentException().isThrownBy(() -> service.complete(owner,resource,99,null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.complete(owner,resource,201,new SecretFragment("fragment",INITIAL_TIME)));
        assertThatIllegalArgumentException().isThrownBy(() -> new SecretFragment("fragment",null));
        var mismatch=new Owner(UUID.randomUUID(),owner.scope(),owner.key(),owner.fingerprint(),owner.version());
        assertThatThrownBy(() -> service.complete(mismatch,resource,201,null)).hasRootCauseInstanceOf(IllegalStateException.class);
        var stale=new Owner(owner.id(),owner.scope(),owner.key(),owner.fingerprint(),owner.version()+1);
        assertThatThrownBy(() -> service.complete(stale,resource,201,null)).hasRootCauseInstanceOf(IllegalStateException.class);
        service.complete(owner,resource,201,null);
        assertThatThrownBy(() -> service.complete(owner,new Resource(ResourceType.PAYMENT,UUID.randomUUID()),200,null)).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(((Completed)service.begin(scope,"key",fingerprint)).resource()).isEqualTo(resource);
    }

    @Test void cleanupDeletesOnlyExpiredCompletedRowsUsingClock() {
        var owner=begin();service.complete(owner,new Resource(ResourceType.PAYMENT,UUID.randomUUID()),201,null);
        service.begin(scope,"unfinished",fingerprint);
        TestClock.set(INITIAL_TIME.plus(Duration.ofDays(30)).minusSeconds(1)); assertThat(cleanup.cleanup()).isZero();
        TestClock.set(INITIAL_TIME.plus(Duration.ofDays(30))); assertThat(cleanup.cleanup()).isEqualTo(1);
        assertThat(jdbc.queryForList("select status from idempotency_records",String.class)).containsExactly("IN_PROGRESS");
    }

    @Test void cleanupShredsExpiredSecretsWithoutReplayAndPreservesExpiredOutcome() {
        var owner=begin(); var resource=new Resource(ResourceType.PAYMENT,UUID.randomUUID());
        service.complete(owner,resource,201,new SecretFragment("fragment",INITIAL_TIME.plusSeconds(60)));
        TestClock.set(INITIAL_TIME.plusSeconds(59)); cleanup.cleanup();
        assertThat(jdbc.queryForObject("select response_secret_state from idempotency_records",String.class)).isEqualTo("LIVE");
        TestClock.set(INITIAL_TIME.plusSeconds(60)); assertThat(cleanup.cleanup()).isZero();
        assertThat(jdbc.queryForObject("select response_secret_state from idempotency_records",String.class)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForMap("select response_secret_ciphertext,response_secret_nonce,response_secret_key_version from idempotency_records").values()).containsOnlyNulls();
        assertThat(service.begin(scope,"key",fingerprint)).isInstanceOf(ExpiredSecret.class);
        assertThatThrownBy(() -> service.complete(owner,resource,201,new SecretFragment("replacement",INITIAL_TIME.plusSeconds(120)))).hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test void secretCannotOutliveRecordRetention() {
        var owner=begin();
        assertThatIllegalArgumentException().isThrownBy(() -> service.complete(owner,new Resource(ResourceType.PAYMENT,UUID.randomUUID()),201,
            new SecretFragment("fragment",INITIAL_TIME.plus(Duration.ofDays(30)).plusSeconds(1))));
    }

    @Test void replayUsesRealRowLockAndLockFailuresAreNotBusinessConflicts() throws Exception {
        begin(); var transaction=new TransactionTemplate(transactions);
        try(var executor=Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from idempotency_records where scope=? and idempotency_key='key' for update",UUID.class,scope.value());
                var result=executor.submit(() -> {
                    try {
                        transaction.executeWithoutResult(other -> {
                            jdbc.execute("set local lock_timeout='150ms'");
                            service.begin(scope,"key",fingerprint);
                        });
                        return null;
                    } catch(RuntimeException failed) { return failed; }
                });
                try {
                    Throwable failed=result.get(5,TimeUnit.SECONDS);
                    assertThat(failed).isNotNull();
                    while(failed.getCause()!=null) failed=failed.getCause();
                    assertThat(failed).isInstanceOf(java.sql.SQLException.class);
                    assertThat(((java.sql.SQLException)failed).getSQLState()).isEqualTo("55P03");
                } catch(Exception failed) { throw new AssertionError(failed); }
            });
        }
        assertThat(service.begin(scope,"key",fingerprint)).isInstanceOf(InProgress.class);
    }

    @Test void completionParticipatesInBusinessTransactionRollback() {
        var owner=begin(); var transaction=new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            service.complete(owner,new Resource(ResourceType.PAYMENT,UUID.randomUUID()),201,null);
            status.setRollbackOnly();
        });
        assertThat(service.begin(scope,"key",fingerprint)).isInstanceOf(InProgress.class);
        service.complete(owner,new Resource(ResourceType.PAYMENT,UUID.randomUUID()),201,null);
        assertThat(((Completed)service.begin(scope,"key",fingerprint)).secret()).isEmpty();
        assertThat(jdbc.queryForObject("select response_secret_state from idempotency_records",String.class)).isEqualTo("NONE");
        assertThat(jdbc.queryForMap("select response_secret_ciphertext,response_secret_nonce,response_secret_key_version,response_secret_expires_at from idempotency_records").values()).containsOnlyNulls();
    }

    @Test void databaseEnforcesSecretStateShapesAndRetentionBoundary() {
        var owner=begin();
        service.complete(owner,new Resource(ResourceType.PAYMENT,UUID.randomUUID()),201,null);
        assertThatThrownBy(() -> jdbc.update("update idempotency_records set response_secret_state='EXPIRED'"))
            .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("update idempotency_records set response_secret_state='LIVE'"))
            .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("update idempotency_records set response_secret_state='UNKNOWN'"))
            .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("update idempotency_records set response_secret_expires_at=expires_at"))
            .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("update idempotency_records set response_secret_state='EXPIRED',response_secret_expires_at=expires_at+interval '1 second'"))
            .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(jdbc.queryForObject("select response_secret_state from idempotency_records",String.class)).isEqualTo("NONE");
    }
}
