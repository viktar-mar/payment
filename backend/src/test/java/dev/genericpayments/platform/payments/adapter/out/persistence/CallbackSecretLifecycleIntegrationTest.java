package dev.genericpayments.platform.payments.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CallbackSecretLifecycleIntegrationTest extends PaymentDatabaseFixture {
    private static final Instant RETIRE_AFTER = INITIAL_TIME.plusSeconds(86_400);
    @Autowired PlatformTransactionManager transactions;

    @Test void retirementDestroysMaterialWithoutDeletingDeliveredHistory() {
        UUID oldSecret = secret(1), newSecret = secret(2);
        configure(oldSecret);
        UUID event = outbox(oldSecret, "DELIVERED");
        jdbc.update("insert into outbox_deliveries(id,outbox_event_id,attempt_number,delivery_mode,status,http_status,started_at,finished_at) values (?,?,1,'AUTOMATIC','SUCCEEDED',200,?,?)",
            UUID.randomUUID(), event, Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME.plusSeconds(1)));
        configure(newSecret);

        assertThatCode(() -> destroy(oldSecret, RETIRE_AFTER)).doesNotThrowAnyException();

        var retired = jdbc.queryForMap("select id,site_id,secret_version,ciphertext,nonce,master_key_version,destroyed_at from site_callback_secret_versions where id=?", oldSecret);
        assertThat(retired).containsEntry("id", oldSecret).containsEntry("site_id", site).containsEntry("secret_version", 1)
            .containsEntry("ciphertext", null).containsEntry("nonce", null).containsEntry("master_key_version", null)
            .containsEntry("destroyed_at", Timestamp.from(RETIRE_AFTER));
        assertThat(jdbc.queryForObject("select active_secret_version_id from site_callback_configs where site_id=?", UUID.class, site)).isEqualTo(newSecret);
        assertThat(jdbc.queryForMap("select secret_version_id,status from outbox_events where id=?", event))
            .containsEntry("secret_version_id", oldSecret).containsEntry("status", "DELIVERED");
        assertThat(jdbc.queryForObject("select count(*) from outbox_deliveries where outbox_event_id=?", Long.class, event)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update outbox_events set secret_version_id=? where id=?", newSecret, event))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("outbox facts are immutable");
    }

    @Test void activeConfigurationPreventsDestructionEvenAfterOverlap() {
        UUID secret = secret(1);
        configure(secret);
        assertThatThrownBy(() -> destroy(secret, RETIRE_AFTER)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("callback secret is still in use");
        assertThat(jdbc.queryForObject("select master_key_version from site_callback_secret_versions where id=?", String.class, secret)).isEqualTo("local-v1");
    }

    @Test void pendingRetryPreventsDestructionAfterRotationAndOverlap() {
        UUID oldSecret = secret(1), newSecret = secret(2);
        configure(newSecret);
        UUID event = outbox(oldSecret, "PENDING");
        assertThatThrownBy(() -> destroy(oldSecret, RETIRE_AFTER)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("callback secret is still in use");
        jdbc.update("update outbox_events set status='DEAD' where id=?", event);
        destroy(oldSecret, RETIRE_AFTER);
        assertThat(jdbc.queryForObject("select status from outbox_events where id=?", String.class, event)).isEqualTo("DEAD");
    }

    @Test void destructionRequiresAnEstablishedUnshortenedRetirementDeadline() {
        UUID secret = secret(1);
        assertThatThrownBy(() -> destroy(secret, RETIRE_AFTER.minusSeconds(1))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update site_callback_secret_versions set retire_after=?,ciphertext=null,nonce=null,master_key_version=null,destroyed_at=? where id=?",
            Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME), secret)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update site_callback_secret_versions set retire_after=? where id=?", Timestamp.from(INITIAL_TIME), secret))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update site_callback_secret_versions set retire_after=null where id=?", secret))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into site_callback_secret_versions(id,site_id,secret_version,ciphertext,nonce,master_key_version,created_at) values (?,?,2,decode('ab','hex'),decode('000000000000000000000000','hex'),'local-v1',?)",
            UUID.randomUUID(), site, Timestamp.from(INITIAL_TIME));
        assertThatThrownBy(() -> jdbc.update("update site_callback_secret_versions set ciphertext=null,nonce=null,master_key_version=null,destroyed_at=? where secret_version=2", Timestamp.from(RETIRE_AFTER)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void materialMustClearTogetherAndDestructionCannotBeReversed() {
        UUID secret = secret(1);
        for (String field : new String[]{"ciphertext", "nonce", "master_key_version"})
            assertThatThrownBy(() -> jdbc.update("update site_callback_secret_versions set " + field + "=null where id=?", secret))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_callback_secrets_material");
        assertThatThrownBy(() -> jdbc.update("update site_callback_secret_versions set destroyed_at=? where id=?", Timestamp.from(RETIRE_AFTER), secret))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_callback_secrets_material");
        destroy(secret, RETIRE_AFTER);
        assertThatThrownBy(() -> jdbc.update("update site_callback_secret_versions set ciphertext=decode('ab','hex'),nonce=decode('000000000000000000000000','hex'),master_key_version='local-v2',destroyed_at=null where id=?", secret))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("destroyed callback secret is immutable");
        assertThatThrownBy(() -> jdbc.update("update site_callback_secret_versions set destroyed_at=? where id=?", Timestamp.from(RETIRE_AFTER.plusSeconds(1)), secret))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("destroyed callback secret is immutable");
        assertThatThrownBy(() -> jdbc.update("delete from site_callback_secret_versions where id=?", secret))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("callback secret identity is retained");
        assertThatThrownBy(() -> jdbc.update("update site_callback_secret_versions set secret_version=3 where id=?", secret))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void destroyedMaterialCannotBeReactivatedOrUsedByANewOrRetriedOutbox() {
        UUID secret = secret(1);
        UUID delivered = outbox(secret, "DELIVERED");
        destroy(secret, RETIRE_AFTER);
        assertThatThrownBy(() -> configure(secret)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("callback secret material is destroyed");
        assertThatThrownBy(() -> outbox(secret, "PENDING")).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("callback secret material is destroyed");
        assertThatThrownBy(() -> jdbc.update("update outbox_events set status='PENDING' where id=?", delivered))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("callback secret material is destroyed");
    }

    @Test void pendingReferenceSerializesAgainstConcurrentDestruction() throws Exception {
        UUID secret = secret(1);
        withHeldTransaction(() -> outbox(secret, "PENDING"), () -> assertLockTimeout(() -> destroy(secret, RETIRE_AFTER)));
        assertThatThrownBy(() -> destroy(secret, RETIRE_AFTER)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("callback secret is still in use");
    }

    @Test void destructionSerializesAgainstConcurrentActivation() throws Exception {
        UUID secret = secret(1);
        withHeldTransaction(() -> destroy(secret, RETIRE_AFTER), () -> assertLockTimeout(() -> configure(secret)));
        assertThatThrownBy(() -> configure(secret)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("callback secret material is destroyed");
    }

    private UUID secret(int version) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into site_callback_secret_versions(id,site_id,secret_version,ciphertext,nonce,master_key_version,created_at,retire_after) values (?,?,?,decode('ab','hex'),decode('000000000000000000000000','hex'),'local-v1',?,?)",
            id, site, version, Timestamp.from(INITIAL_TIME), Timestamp.from(RETIRE_AFTER));
        return id;
    }

    private void configure(UUID secret) {
        jdbc.update("insert into site_callback_configs(site_id,endpoint_url,active_secret_version_id,enabled,created_at,updated_at) values (?,'https://example.test/callback',?,true,?,?) on conflict(site_id) do update set active_secret_version_id=excluded.active_secret_version_id",
            site, secret, Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME));
    }

    private UUID outbox(UUID secret, String status) {
        UUID event = UUID.randomUUID();
        long sequence = jdbc.queryForObject("select count(*)+1 from payment_events where payment_id=?", Long.class, payment);
        jdbc.update("insert into payment_events(id,payment_id,site_id,payment_account_id,sequence_number,event_type,source,occurred_at,received_at,request_id,payload_hash) values (?,?,?,?,?,'PAYMENT_SUCCEEDED','PROVIDER_EVENT',?,?,'request',?)",
            event, payment, site, account, sequence, Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME), "a".repeat(64));
        jdbc.update("insert into outbox_events(id,site_id,payment_id,payment_account_id,callback_type,payload,endpoint_url,secret_version_id,status,next_attempt_at,created_at,updated_at) values (?,?,?,?,'payment.succeeded','{}','https://example.test/callback',?,?,?,?,?)",
            event, site, payment, account, secret, status, Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME));
        return event;
    }

    private void destroy(UUID secret, Instant at) {
        jdbc.update("update site_callback_secret_versions set ciphertext=null,nonce=null,master_key_version=null,destroyed_at=? where id=?", Timestamp.from(at), secret);
    }

    private void assertLockTimeout(Runnable operation) {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.execute("set local lock_timeout='200ms'");
            operation.run();
        })).hasRootCauseInstanceOf(java.sql.SQLException.class).rootCause().extracting("SQLState").isEqualTo("55P03");
    }

    private void withHeldTransaction(Runnable holder, Runnable contender) throws Exception {
        CountDownLatch acquired = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<?> running = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                holder.run();
                acquired.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timed out"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
            }));
            try {
                assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
                contender.run();
            } finally { release.countDown(); }
            running.get(10, TimeUnit.SECONDS);
        }
    }
}
