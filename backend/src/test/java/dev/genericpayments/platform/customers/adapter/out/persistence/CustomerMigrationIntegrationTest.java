package dev.genericpayments.platform.customers.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class CustomerMigrationIntegrationTest extends PostgresIntegrationTest {
    @Autowired DataSource source;
    private String schema;
    private SingleConnectionDataSource isolated;
    private JdbcTemplate jdbc;
    private UUID site, payment, key;

    @BeforeEach void legacyDatabase() throws Exception {
        schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        migrate("4");
        isolated = new SingleConnectionDataSource(source.getConnection(), true);
        jdbc = new JdbcTemplate(isolated);
        jdbc.execute("set search_path to " + schema);
        UUID account = UUID.randomUUID(); site = UUID.randomUUID(); payment = UUID.randomUUID(); key = UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'legacy','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)", account);
        jdbc.update("insert into sites values (?,'legacy','Legacy',?,'ACTIVE','AUTOMATIC',now(),now(),0)", site, account);
        jdbc.update("insert into payments(id,site_id,payment_account_id,source,external_order_id,amount_minor,currency,tax_mode,status,created_at,updated_at) values (?,?,?,'DYNAMIC','legacy-order',2000,'EUR','NONE','CREATED',now(),now())", payment,site,account);
        jdbc.update("insert into site_api_keys(id,site_id,prefix,key_hash,scopes,status,created_at) values (?,?,'legacy-prefix','$argon2id$v=19$legacy','[\"payments:create\",\"payments:read\"]','ACTIVE',now())",key,site);
    }
    private void migrate(String target) {
        var config = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema);
        if (target != null) config.target(target);
        config.load().migrate();
    }
    @AfterEach void removeIsolatedSchema() {
        if (jdbc != null) { jdbc.execute("set search_path to public"); jdbc.execute("drop schema " + schema + " cascade"); isolated.destroy(); }
    }
    @Test void existingGuestPaymentsAndApiKeysSurviveV4ToCurrent() {
        migrate(null);
        assertThat(jdbc.queryForObject("select customer_id from payments where id=?", UUID.class,payment)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from customers",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select scopes::text from site_api_keys where id=?",String.class,key))
            .isEqualTo("[\"payments:create\", \"payments:read\"]");
        jdbc.update("update site_api_keys set scopes='[\"customers:read\",\"customers:manage\"]' where id=?",key);
        for (String invalid : new String[]{"[]", "{}", "[\"admin\"]", "[\"customers:read\",null]"})
            assertThatThrownBy(() -> jdbc.update("update site_api_keys set scopes=?::jsonb where id=?",invalid,key)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("select version from flyway_schema_history where success and version is not null order by installed_rank",String.class)).containsExactly("1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23","24","25");
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema=? and table_name='email_otp_challenges'",String.class,schema))
            .contains("provider_key");
    }

    @Test void currentMigrationAddsTheExactDigestLookupIndexUsedBySessionResolution() {
        migrate(null);

        String indexDefinition=jdbc.queryForObject("""
            select indexdef from pg_indexes where schemaname=? and indexname='idx_customer_sessions_digest_lookup'
            """,String.class,schema);
        assertThat(indexDefinition).contains("(token_digest_key_version, token_digest)");

        jdbc.execute("set enable_seqscan=off");
        try {
            String plan=String.join("\n",jdbc.queryForList("""
                explain (costs off) select * from customer_sessions
                 where token_digest_key_version='v1' and token_digest='digest'
                """,String.class));
            assertThat(plan).contains("idx_customer_sessions_digest_lookup");
        } finally {
            jdbc.execute("reset enable_seqscan");
        }
    }

    @Test void v7BackfillsEveryExistingSemanticIdentityWithItsOriginalSubjectAlias() {
        migrate("6");
        UUID customer=UUID.randomUUID(); UUID identity=UUID.randomUUID();
        jdbc.update("insert into customers(id,site_id,status,created_at,updated_at) values (?,?,'ACTIVE',now(),now())",customer,site);
        jdbc.update("""
            insert into customer_identities(id,site_id,customer_id,mode,issuer_key,subject_digest,subject_digest_key_version,
              verified_at,created_at,last_used_at) values (?,?,?,'OIDC_TOKEN','legacy-provider','legacydigest','v1',now(),now(),now())
            """,identity,site,customer);

        migrate(null);

        assertThat(jdbc.queryForMap("select site_id,issuer_key,subject_digest,subject_digest_key_version,identity_id from customer_identity_subject_aliases"))
            .containsEntry("site_id",site).containsEntry("issuer_key","legacy-provider")
            .containsEntry("subject_digest","legacydigest").containsEntry("subject_digest_key_version","v1")
            .containsEntry("identity_id",identity);
    }
    @Test void legacyOrphanFailsWithNamedPreconditionBeforeAddingForeignKey() {
        jdbc.update("update payments set customer_id=? where id=?",UUID.randomUUID(),payment);
        assertThatThrownBy(() -> migrate(null)).hasStackTraceContaining("orphan payment customer_id")
            .rootCause().isInstanceOfSatisfying(org.postgresql.util.PSQLException.class,error -> {
                assertThat(error.getSQLState()).isEqualTo("23514");
                assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("ck_payments_customer_migration_precondition");
            });
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conname='fk_payments_customer_site' and connamespace=?::regnamespace",Long.class,schema)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments where customer_id is not null",Long.class)).isEqualTo(1);
    }

    @Test void v9RefusesToFabricateAnIssuerForAnExistingOtpChallenge() {
        migrate("8");
        UUID challenge=UUID.randomUUID();
        jdbc.update("""
            insert into email_otp_challenges(id,site_id,origin,intent,email_digest,email_digest_key_version,
              email_ciphertext,email_nonce,email_key_version,otp_digest,otp_digest_key_version,created_at,expires_at)
            values (?,?,'https://legacy.example.test','AUTHENTICATE','emaildigest','v1',decode(repeat('ab',16),'hex'),
              decode(repeat('00',12),'hex'),'legacy-key','otpdigest','v1',now(),now()+interval '10 minutes')
            """,challenge,site);

        assertThatThrownBy(() -> migrate(null)).hasStackTraceContaining("existing email OTP challenges require explicit issuer migration")
            .rootCause().isInstanceOfSatisfying(org.postgresql.util.PSQLException.class,error -> {
                assertThat(error.getSQLState()).isEqualTo("23514");
                assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("ck_email_otp_provider_migration_precondition");
            });
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema=? and table_name='email_otp_challenges'",String.class,schema))
            .doesNotContain("provider_key");
    }

    @Test void v10BackfillsProofSubjectAliasAndEnforcesSiteScopedCascade() {
        migrate("9");
        UUID proof=UUID.randomUUID();
        jdbc.update("""
            insert into identity_link_proofs(id,site_id,origin,mode,issuer_key,subject_digest,subject_digest_key_version,
              token_digest,token_digest_key_version,verified_at,created_at,expires_at)
            values (?,?,'https://legacy.example.test','SITE_TOKEN','legacy-provider','legacy-subject-digest','v1',
              'legacy-token-digest','v1',now(),now(),now()+interval '5 minutes')
            """,proof,site);

        migrate(null);

        assertThat(jdbc.queryForMap("select site_id,proof_id,subject_digest,subject_digest_key_version from identity_link_proof_subject_aliases"))
            .containsEntry("site_id",site).containsEntry("proof_id",proof)
            .containsEntry("subject_digest","legacy-subject-digest").containsEntry("subject_digest_key_version","v1");
        UUID otherSite=UUID.randomUUID();
        UUID account=jdbc.queryForObject("select payment_account_id from sites where id=?",UUID.class,site);
        jdbc.update("""
            insert into sites(id,site_key,display_name,payment_account_id,status,payment_method_policy,created_at,updated_at,version)
            values (?,'proof-alias-other','Proof alias other',?,'ACTIVE','AUTOMATIC',now(),now(),0)
            """,otherSite,account);
        assertThatThrownBy(() -> jdbc.update("""
            insert into identity_link_proof_subject_aliases(site_id,proof_id,subject_digest,subject_digest_key_version)
            values (?,?,'cross-site-digest','v1')
            """,otherSite,proof)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        jdbc.update("delete from identity_link_proofs where id=?",proof);
        assertThat(jdbc.queryForObject("select count(*) from identity_link_proof_subject_aliases",Long.class)).isZero();
    }
}
