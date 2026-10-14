package dev.genericpayments.platform.webhooks.adapter.out.persistence;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
class CallbackSecretPersistenceIntegrationTest extends CheckoutTestFixture {
    @Autowired CallbackConfigurationPort callbacks;
    @Autowired EncryptedSecretPort encryption;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Test void configurationEncryptsAndRevealsOnlyFirstAndRotation() {
        var created=callbacks.configure(site.value(),"https://example.com/callback",true);
        assertThat(java.util.Base64.getUrlDecoder().decode(created.secret())).hasSize(32);
        var row=jdbc.queryForMap("select * from site_callback_secret_versions");
        assertThat(new String((byte[])row.get("ciphertext"),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(created.secret());
        assertThat(callbacks.configure(site.value(),"https://example.com/new",false).secret()).isNull();
        var rotated=callbacks.rotate(site.value());
        assertThat(rotated.configuration().secretVersion()).isEqualTo(2);
        assertThat(rotated.toString()).doesNotContain(rotated.secret());
        assertThat(jdbc.queryForObject("select retire_after from site_callback_secret_versions where secret_version=1",java.sql.Timestamp.class).toInstant()).isEqualTo(INITIAL_TIME.plusSeconds(86400));
    }
    @Test void nonexistentConfigurationRotationFailsWithoutSecretFragment() {
        assertThatThrownBy(()->callbacks.rotate(site.value())).isInstanceOf(RuntimeException.class);
        assertThat(count("site_callback_secret_versions")).isZero();assertThat(count("site_callback_configs")).isZero();
    }
    @Test void configurationFailureRollsBackNewSecret() {
        jdbc.execute("create function task8_config_failure() returns trigger language plpgsql as $$ begin raise exception 'injected'; end; $$");
        jdbc.execute("create trigger task8_config_failure before insert on site_callback_configs for each row execute function task8_config_failure()");
        try{assertThatThrownBy(()->callbacks.configure(site.value(),"https://example.com/callback",true)).isInstanceOf(RuntimeException.class);assertThat(count("site_callback_secret_versions")).isZero();}
        finally{jdbc.execute("drop trigger task8_config_failure on site_callback_configs");jdbc.execute("drop function task8_config_failure()");}
    }
    @Test void cleanupDestroysOnlyExpiredUnreferencedMaterialAndNeverShortensRetirement() {
        callbacks.configure(site.value(),"https://example.com/callback",true);
        jdbc.update("update site_callback_secret_versions set retire_after=?",java.sql.Timestamp.from(INITIAL_TIME.plusSeconds(172800)));
        callbacks.rotate(site.value());
        assertThat(callbacks.cleanup(INITIAL_TIME.plusSeconds(86400))).isZero();assertThat(callbacks.cleanup(INITIAL_TIME.plusSeconds(172800))).isEqualTo(1);
        assertThat(jdbc.queryForMap("select ciphertext,nonce,master_key_version from site_callback_secret_versions where secret_version=1")).containsEntry("ciphertext",null).containsEntry("nonce",null).containsEntry("master_key_version",null);
    }
    @Test void rotationLocksConfigurationBeforeSecretToAvoidCurrentRetryLockInversion() throws Exception {
        var config=callbacks.configure(site.value(),"https://example.com/callback",true).configuration();
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
                jdbc.queryForList("select site_id from site_callback_configs where site_id=? for update",site.value());
                executor.submit(()->callbacks.rotate(site.value()));
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(true){jdbc.queryForList("select pg_stat_clear_snapshot()");if(jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and query like '%site_callback_configs%'",Integer.class)>0)break;if(System.nanoTime()>deadline)throw new AssertionError("Rotation did not reach configuration lock");Thread.onSpinWait();}
                // A current retry already holding the configuration must still be able to lock its selected secret.
                assertThatCode(()->jdbc.queryForList("select id from site_callback_secret_versions where id=? for update nowait",config.secretId())).doesNotThrowAnyException();
            });
        }
        assertThat(callbacks.find(site.value()).orElseThrow().secretVersion()).isEqualTo(2);
    }
}
