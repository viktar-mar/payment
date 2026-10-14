package dev.genericpayments.platform.customers.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.extension.ExtendWith;

@Import(CustomerPersistenceIntegrationTest.SqlCaptureConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class CustomerPersistenceIntegrationTest extends PostgresIntegrationTest {
    static final Queue<String> SQL=new ConcurrentLinkedQueue<>();
    @TestConfiguration(proxyBeanMethods=false)
    static class SqlCaptureConfiguration {
        @Bean HibernatePropertiesCustomizer captureCustomerSql() {
            return properties -> properties.put("hibernate.session_factory.statement_inspector",(StatementInspector) statement -> { SQL.add(statement); return statement; });
        }
    }
    @Autowired CustomerStore customers;
    @Autowired CustomerIdentityStore identities;
    @Autowired IdentityProviderConfigurationStore providers;
    @Autowired EncryptedSecretPort encryption;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    SiteId site, other;
    static final List<String> TABLES = List.of("customers","customer_identities","customer_identity_subject_aliases","identity_provider_configurations",
        "customer_sessions","oidc_authentication_attempts","consumed_site_token_jtis","identity_link_proofs","identity_link_proof_subject_aliases",
        "email_otp_challenges","customer_rate_limit_buckets","customer_mail_outbox","customer_idempotency_records");
    @BeforeEach void prepare() {
        clearDatabase(); site = new SiteId(UUID.randomUUID()); other = new SiteId(UUID.randomUUID());
        UUID account = UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'customer-account','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        for (SiteId s : List.of(site,other)) jdbc.update("insert into sites values (?,?, 'Customers',?,'ACTIVE','AUTOMATIC',now(),now(),0)",s.value(),s.value().toString(),account);
    }
    Customer customer(SiteId s) { return Customer.create(new CustomerId(UUID.randomUUID()),s,null,null,INITIAL_TIME); }
    CustomerIdentity createIdentity(CustomerIdentity identity) { return identities.create(identity,List.of(identity.subjectDigest())); }
    String ref(CustomerId id) { return "customer-email:" + id.value(); }
    private <T> T inNewTransaction(Supplier<T> action) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> action.get());
    }
    @Test void nanosecondCustomerCreateThenTouchAcrossTransactionsReturnsCanonicalTimes() {
        var now = Instant.parse("2026-10-10T12:00:00.123456789Z");
        var canonical = Instant.parse("2026-10-10T12:00:00.123456Z");
        var created = inNewTransaction(() -> customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,now)));
        var touched = inNewTransaction(() -> customers.save(created.touch(now.plusSeconds(1))));
        var loaded = inNewTransaction(() -> customers.findById(site,created.id()).orElseThrow());
        assertThat(created.createdAt()).isEqualTo(canonical);
        assertThat(created.updatedAt()).isEqualTo(canonical);
        assertThat(touched.createdAt()).isEqualTo(canonical);
        assertThat(touched.updatedAt()).isEqualTo(canonical.plusSeconds(1));
        assertThat(loaded.createdAt()).isEqualTo(touched.createdAt());
        assertThat(loaded.updatedAt()).isEqualTo(touched.updatedAt());
        assertThat(loaded.version()).isEqualTo(1);
        var changedCreation = Customer.reconstitute(loaded.id(),site,loaded.status(),null,null,canonical.plusNanos(1_000),loaded.updatedAt(),null,loaded.version());
        var regressedUpdate = Customer.reconstitute(loaded.id(),site,loaded.status(),null,null,loaded.createdAt(),canonical,null,loaded.version());
        for (var invalid : List.of(changedCreation,regressedUpdate))
            assertThatThrownBy(() -> inNewTransaction(() -> customers.save(invalid))).isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inNewTransaction(() -> customers.save(created.touch(now.plusSeconds(2))))).isInstanceOf(OptimisticLockingFailureException.class);
    }
    @Test void nanosecondIdentityCreateThenTouchAcrossTransactionsReturnsCanonicalTimes() {
        var now = Instant.parse("2026-10-10T12:00:00.123456789Z");
        var canonical = Instant.parse("2026-10-10T12:00:00.123456Z");
        var owner = customers.save(customer(site));
        var created = inNewTransaction(() -> createIdentity(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,owner.id(),CustomerAuthenticationMode.SITE_TOKEN,"nano-issuer","v1:bmFubw",now)));
        var touched = inNewTransaction(() -> identities.update(created.touch(now.plusSeconds(1))));
        var loaded = inNewTransaction(() -> identities.find(site,"nano-issuer","v1:bmFubw").orElseThrow());
        assertThat(created.createdAt()).isEqualTo(canonical);
        assertThat(created.verifiedAt()).isEqualTo(canonical);
        assertThat(created.lastUsedAt()).isEqualTo(canonical);
        assertThat(touched.createdAt()).isEqualTo(canonical);
        assertThat(touched.verifiedAt()).isEqualTo(canonical);
        assertThat(touched.lastUsedAt()).isEqualTo(canonical.plusSeconds(1));
        assertThat(loaded.createdAt()).isEqualTo(touched.createdAt());
        assertThat(loaded.verifiedAt()).isEqualTo(touched.verifiedAt());
        assertThat(loaded.lastUsedAt()).isEqualTo(touched.lastUsedAt());
        var changedCreation = CustomerIdentity.reconstitute(loaded.id(),site,owner.id(),loaded.mode(),loaded.issuerKey(),loaded.subjectDigest(),canonical,canonical.minusNanos(1_000),loaded.lastUsedAt());
        var changedVerification = CustomerIdentity.reconstitute(loaded.id(),site,owner.id(),loaded.mode(),loaded.issuerKey(),loaded.subjectDigest(),canonical.plusNanos(1_000),canonical,loaded.lastUsedAt());
        var regressedUse = CustomerIdentity.reconstitute(loaded.id(),site,owner.id(),loaded.mode(),loaded.issuerKey(),loaded.subjectDigest(),canonical,canonical,canonical);
        for (var invalid : List.of(changedCreation,changedVerification,regressedUse))
            assertThatThrownBy(() -> inNewTransaction(() -> identities.update(invalid))).isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
    @Test void nanosecondAnonymizationRemainsIdempotentAcrossTransactions() {
        var now = Instant.parse("2026-10-10T12:00:00.123456789Z");
        var canonical = Instant.parse("2026-10-10T12:00:00.123456Z");
        var created = inNewTransaction(() -> customers.save(customer(site)));
        var anonymized = inNewTransaction(() -> customers.save(created.anonymize(now)));
        var repeated = inNewTransaction(() -> customers.save(anonymized.anonymize(now.plusSeconds(1))));
        var loaded = inNewTransaction(() -> customers.findById(site,created.id()).orElseThrow());
        assertThat(anonymized.anonymizedAt()).contains(canonical);
        assertThat(anonymized.updatedAt()).isEqualTo(canonical);
        assertThat(repeated.anonymizedAt()).contains(canonical);
        assertThat(repeated.updatedAt()).isEqualTo(canonical);
        assertThat(loaded.anonymizedAt()).isEqualTo(repeated.anonymizedAt());
        assertThat(loaded.version()).isEqualTo(anonymized.version());
        var changedAnonymization = Customer.reconstitute(loaded.id(),site,loaded.status(),null,null,loaded.createdAt(),canonical.plusNanos(1_000),canonical.plusNanos(1_000),loaded.version());
        var reactivated = Customer.reconstitute(loaded.id(),site,CustomerStatus.ACTIVE,null,null,loaded.createdAt(),canonical.plusSeconds(1),null,loaded.version());
        for (var invalid : List.of(changedAnonymization,reactivated))
            assertThatThrownBy(() -> inNewTransaction(() -> customers.save(invalid))).isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
    @Test void nanosecondProviderClockTimesAreCanonicalBeforeWriting() {
        var now = Instant.parse("2026-10-10T12:00:00.123456789Z");
        var canonical = Instant.parse("2026-10-10T12:00:00.123456Z");
        TestClock.set(now);
        var created = inNewTransaction(() -> providers.save(IdentityProviderConfiguration.create(site,"nano-provider",new IdentityProviderConfiguration.EmailOtpConfiguration("sender-ref","otp-template"))));
        TestClock.set(now.plusSeconds(1));
        inNewTransaction(() -> providers.save(created.disable()));
        var times = jdbc.queryForObject("select created_at,updated_at from identity_provider_configurations where site_id=? and provider_key='nano-provider'",(row,index) -> List.of(row.getTimestamp(1).toInstant(),row.getTimestamp(2).toInstant()),site.value());
        assertThat(times).containsExactly(canonical,canonical.plusSeconds(1));
    }
    @Test void customerMappingPreservesEncryptedEmailStatusTimesAndVersionWithoutPlaintext() {
        var id = new CustomerId(UUID.randomUUID());
        var value = Customer.create(id,site,ref(id),"v2:c2l0ZS1lbWFpbA",INITIAL_TIME);
        var secret = encryption.encrypt("person@example.test");
        var saved = customers.save(value,secret);
        var stored = jdbc.queryForMap("select * from customers where site_id=? and id=?",site.value(),id.value());
        assertThat(stored.get("email_ciphertext")).isEqualTo(secret.ciphertext());
        assertThat(stored.get("email_nonce")).isEqualTo(secret.nonce());
        assertThat(stored.get("email_key_version")).isEqualTo("test-v1");
        assertThat(stored.get("email_digest")).isEqualTo("c2l0ZS1lbWFpbA");
        assertThat(stored.get("email_digest_key_version")).isEqualTo("v2");
        assertThat(stored.toString()).doesNotContain("person@example.test");
        assertThat(customers.findEncryptedEmail(site,id).orElseThrow().ciphertext()).isEqualTo(secret.ciphertext());
        var touched = customers.save(saved.touch(INITIAL_TIME.plusSeconds(3)));
        assertThat(touched.version()).isEqualTo(1);
        assertThat(touched.createdAt()).isEqualTo(INITIAL_TIME);
        assertThat(touched.updatedAt()).isEqualTo(INITIAL_TIME.plusSeconds(3));
        assertThatThrownBy(() -> customers.save(saved.touch(INITIAL_TIME.plusSeconds(4)))).isInstanceOf(OptimisticLockingFailureException.class);
        var anonymized = customers.save(touched.anonymize(INITIAL_TIME.plusSeconds(5)));
        var loaded = customers.findById(site,id).orElseThrow();
        assertThat(loaded.status()).isEqualTo(CustomerStatus.ANONYMIZED);
        assertThat(loaded.version()).isEqualTo(2);
        assertThat(loaded.anonymizedAt()).contains(INITIAL_TIME.plusSeconds(5));
        assertThat(loaded.encryptedEmailRef()).isEmpty();
        assertThat(customers.findEncryptedEmail(site,id)).isEmpty();
        assertThat(customers.findByEmailDigest(site,"v2:c2l0ZS1lbWFpbA")).isEmpty();
        assertThat(anonymized.anonymize(INITIAL_TIME.plusSeconds(6))).isSameAs(anonymized);
    }
    @Test void allCustomerReadsWritesAndLocksAreSiteScoped() {
        var saved = customers.save(customer(site));
        assertThat(customers.findById(other,saved.id())).isEmpty();
        assertThat(customers.findEncryptedEmail(other,saved.id())).isEmpty();
        assertThat(customers.list(other,20,0)).isEmpty();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> assertThat(customers.findByIdForUpdate(other,saved.id())).isEmpty());
        var forged = Customer.create(saved.id(),other,null,null,INITIAL_TIME);
        assertThatThrownBy(() -> customers.save(forged)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(customers.findById(site,saved.id())).isPresent();
    }
    @Test void emailLookupIsSiteScopedAndDoesNotMergeCustomers() {
        for (SiteId s : List.of(site,site,other)) {
            var id = new CustomerId(UUID.randomUUID());
            customers.save(Customer.create(id,s,ref(id),"v1:ZW1haWw",INITIAL_TIME),encryption.encrypt("person@example.test"));
        }
        assertThat(customers.findByEmailDigest(site,"v1:ZW1haWw")).hasSize(2);
        assertThat(customers.findByEmailDigest(other,"v1:ZW1haWw")).hasSize(1);
        assertThat(customers.findByEmailDigest(site,"v2:ZW1haWw")).isEmpty();
    }
    @Test void implicitEmailSaveRequiresUnchangedPersistedReferenceAndDigest() {
        var id=new CustomerId(UUID.randomUUID());
        var c=Customer.create(id,site,ref(id),"v1:ZW1haWw",INITIAL_TIME);
        assertThatThrownBy(() -> customers.save(c)).isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
        var saved=customers.save(c,encryption.encrypt("person@example.test"));
        assertThatThrownBy(() -> customers.save(saved.attachEmail(ref(id),"v2:ZW1haWw",INITIAL_TIME))).isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> customers.save(saved.attachEmail("unknown-reference","v1:ZW1haWw",INITIAL_TIME))).isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> customers.save(customer(site),encryption.encrypt("person@example.test"))).isInstanceOf(InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(customers.findByEmailDigest(site,"v1:ZW1haWw")).hasSize(1);
    }
    @ParameterizedTest @CsvSource(delimiter='|',value={
        "customers|version=-1", "customers|status='UNKNOWN'", "customers|email_nonce=decode('00','hex')",
        "customers|email_key_version=null", "customers|email_digest_key_version=' '",
        "customers|status='ANONYMIZED'", "customers|updated_at=created_at-interval '1 second'",
        "customer_identities|subject_digest_key_version=' '", "customer_identities|mode='UNKNOWN'",
        "identity_provider_configurations|version=-1", "identity_provider_configurations|status='UNKNOWN'",
        "identity_provider_configurations|public_jwk_set='{}'", "identity_provider_configurations|public_jwk_set='[]'",
        "identity_provider_configurations|jwks_source_type='STATIC_PUBLIC'",
        "customer_sessions|scopes='[]'", "customer_sessions|scopes='[\"ADMIN\"]'", "customer_sessions|token_digest_key_version=' '",
        "customer_sessions|expires_at=issued_at", "customer_sessions|revoked_at=issued_at-interval '1 second'",
        "oidc_authentication_attempts|intent='UNKNOWN'", "oidc_authentication_attempts|expires_at=created_at",
        "consumed_site_token_jtis|jti_digest_key_version=' '", "consumed_site_token_jtis|expires_at=consumed_at",
        "identity_link_proofs|token_digest=''", "identity_link_proofs|expires_at=created_at",
        "email_otp_challenges|email_nonce=null", "email_otp_challenges|email_ciphertext=decode('00','hex')",
        "email_otp_challenges|attempt_count=6", "email_otp_challenges|consumed_at=created_at-interval '1 second'",
        "customer_rate_limit_buckets|request_count=-1", "customer_rate_limit_buckets|bucket_type='UNKNOWN'",
        "customer_mail_outbox|lease_owner='worker'", "customer_mail_outbox|status='PROCESSING'",
        "customer_mail_outbox|attempt_count=9", "customer_mail_outbox|payload_key_version=null",
        "customer_mail_outbox|payload_nonce=decode('00','hex')", "customer_mail_outbox|status='SENT'",
        "customer_idempotency_records|fingerprint='raw-token'", "customer_idempotency_records|status='COMPLETED'",
        "customer_idempotency_records|response_ciphertext=decode('00','hex')", "customer_idempotency_records|version=-1"
    })
    void v5RejectsInvalidSecurityAndLifecycleStorage(String table,String change) {
        var id=new CustomerId(UUID.randomUUID());
        var c=customers.save(Customer.create(id,site,ref(id),"v1:ZW1haWw",INITIAL_TIME),encryption.encrypt("person@example.test"));
        seedAllTables(c);
        assertThatThrownBy(() -> jdbc.update("update " + table + " set " + change)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void identityUniquenessVersionedDigestsAndCompositeOwnershipAreEnforced() {
        var first = customers.save(customer(site)); var second = customers.save(customer(other));
        var identity = CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,first.id(),CustomerAuthenticationMode.SITE_TOKEN,"issuer-key","v1:c3ViamVjdA",INITIAL_TIME);
        createIdentity(identity);
        assertThat(identities.find(other,"issuer-key","v1:c3ViamVjdA")).isEmpty();
        assertThat(identities.list(other,first.id())).isEmpty();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> assertThat(identities.findForUpdate(other,"issuer-key","v1:c3ViamVjdA")).isEmpty());
        assertThatThrownBy(() -> createIdentity(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,first.id(),CustomerAuthenticationMode.SITE_TOKEN,"issuer-key","v1:c3ViamVjdA",INITIAL_TIME))).isInstanceOf(SubjectAliasOwnershipConflictException.class).hasMessage("Identity alias ownership conflict");
        assertThatThrownBy(() -> createIdentity(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,second.id(),CustomerAuthenticationMode.SITE_TOKEN,"issuer-key","v2:c3ViamVjdA",INITIAL_TIME))).isInstanceOf(DataIntegrityViolationException.class);
        createIdentity(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),other,second.id(),CustomerAuthenticationMode.SITE_TOKEN,"issuer-key","v1:c3ViamVjdA",INITIAL_TIME));
        identities.update(identity.touch(INITIAL_TIME.plusSeconds(7)));
        assertThat(identities.find(site,"issuer-key","v1:c3ViamVjdA").orElseThrow().lastUsedAt()).isEqualTo(INITIAL_TIME.plusSeconds(7));
        assertThat(identities.find(site,"issuer-key","v1:c3ViamVjdA").orElseThrow().createdAt()).isEqualTo(INITIAL_TIME);
    }
    @Test void identityAliasConflictRollsBackEveryAliasAndPreservesTheOriginalOwnerWithoutSensitiveDatabaseLogs(CapturedOutput output) {
        var first=customers.save(customer(site)); var second=customers.save(customer(site));
        String originalReference="v2:Y29sbGlzaW9u";
        String contenderReference="v1:c2VtYW50aWM";
        String partialReference="v1:YWFhYQ";
        var original=createIdentity(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,first.id(),
            CustomerAuthenticationMode.OIDC_TOKEN,"alias-provider",originalReference,INITIAL_TIME));
        var contender=createIdentity(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,second.id(),
            CustomerAuthenticationMode.OIDC_TOKEN,"alias-provider",contenderReference,INITIAL_TIME));

        assertThatThrownBy(() -> inNewTransaction(() -> {
            identities.saveSubjectAliases(contender,List.of(partialReference,originalReference));
            return null;
        })).isInstanceOf(SubjectAliasOwnershipConflictException.class).hasMessage("Identity alias ownership conflict").hasNoCause();
        assertThatThrownBy(() -> createIdentity(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,second.id(),
            CustomerAuthenticationMode.OIDC_TOKEN,"alias-provider",originalReference,INITIAL_TIME)))
            .isInstanceOf(SubjectAliasOwnershipConflictException.class).hasMessage("Identity alias ownership conflict").hasNoCause();

        assertThat(jdbc.queryForObject("select count(*) from customer_identity_subject_aliases where subject_digest='YWFhYQ'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select identity_id from customer_identity_subject_aliases where site_id=? and issuer_key='alias-provider' and subject_digest='Y29sbGlzaW9u'",UUID.class,site.value()))
            .isEqualTo(original.id().value());
        assertThat(jdbc.queryForObject("select count(*) from customer_identity_subject_aliases",Integer.class)).isEqualTo(2);
        assertThat(output.getAll()).doesNotContain("YWFhYQ","Y29sbGlzaW9u","c2VtYW50aWM",
            partialReference,originalReference,contenderReference,"SQLState: 23505",
            "duplicate key value violates unique constraint","Key (site_id, issuer_key, subject_digest, subject_digest_key_version)");
    }
    @Test void deleteByCustomerCascadesEveryIdentityAliasWithoutTouchingUnrelatedAliases() {
        var removedCustomer=customers.save(customer(site));
        var retainedCustomer=customers.save(customer(site));
        var otherSiteCustomer=customers.save(customer(other));
        var removedIdentity=identities.create(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,removedCustomer.id(),
            CustomerAuthenticationMode.OIDC_TOKEN,"delete-provider","v1:cmVtb3ZlZA",INITIAL_TIME),List.of("v1:cmVtb3ZlZA","v2:cmVtb3ZlZA","v3:cmVtb3ZlZA"));
        var retainedIdentity=identities.create(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,retainedCustomer.id(),
            CustomerAuthenticationMode.OIDC_TOKEN,"delete-provider","v1:cmV0YWluZWQ",INITIAL_TIME),List.of("v1:cmV0YWluZWQ","v2:cmV0YWluZWQ"));
        var otherSiteIdentity=identities.create(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),other,otherSiteCustomer.id(),
            CustomerAuthenticationMode.OIDC_TOKEN,"delete-provider","v1:b3RoZXItc2l0ZQ",INITIAL_TIME),List.of("v1:b3RoZXItc2l0ZQ","v2:b3RoZXItc2l0ZQ"));

        identities.deleteByCustomer(site,removedCustomer.id());

        assertThat(jdbc.queryForObject("select count(*) from customer_identities where customer_id=?",Integer.class,removedCustomer.id().value())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer_identity_subject_aliases where identity_id=?",Integer.class,removedIdentity.id().value())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities where customer_id=?",Integer.class,retainedCustomer.id().value())).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identity_subject_aliases where identity_id=?",Integer.class,retainedIdentity.id().value())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from customer_identities where customer_id=?",Integer.class,otherSiteCustomer.id().value())).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identity_subject_aliases where identity_id=?",Integer.class,otherSiteIdentity.id().value())).isEqualTo(2);
    }
    @Test void providersRoundTripEveryVariantWithIsolationAndOptimisticVersions() {
        var oidc = new IdentityProviderConfiguration.OidcTokenConfiguration("https://issuer.test",Set.of("audience"),new JwksSource.Remote(java.net.URI.create("https://issuer.test/keys")),Set.of("RS256"),new IdentityProviderConfiguration.VerifiedEmailClaimMapping("email","email_verified"));
        var siteToken = new IdentityProviderConfiguration.SiteTokenConfiguration("https://site.test",Set.of("site-audience"),new JwksSource.StaticPublicJwkSet("public-keys-ref"),Set.of("ES256"));
        var email = new IdentityProviderConfiguration.EmailOtpConfiguration("sender-ref","otp-template");
        int number=0;
        for (var config : List.of(oidc,siteToken,email)) {
            var value = IdentityProviderConfiguration.create(site,"provider-" + number++,config);
            var saved = config==siteToken ? providers.save(value,publicKeys()) : providers.save(value);
            assertThat(providers.find(site,value.providerKey())).contains(saved);
            assertThat(providers.find(other,value.providerKey())).isEmpty();
            new TransactionTemplate(transactions).executeWithoutResult(tx -> assertThat(providers.findForUpdate(other,value.providerKey())).isEmpty());
            assertThat(providers.save(saved.disable()).version()).isEqualTo(1);
            assertThatThrownBy(() -> providers.save(saved.disable())).isInstanceOf(OptimisticLockingFailureException.class);
        }
        assertThat(providers.list(site)).hasSize(3); assertThat(providers.list(other)).isEmpty();
    }
    private IdentityProviderConfigurationStore.PublicJwkSet publicKeys() {
        return new IdentityProviderConfigurationStore.PublicJwkSet("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"public-key\",\"n\":\"AQAB\",\"e\":\"AQAB\"}]}");
    }
    @Test void staticPublicKeyPayloadIsExplicitSiteScopedAndExclusiveWithRemoteLocation() {
        var config=new IdentityProviderConfiguration.SiteTokenConfiguration("https://site.test",Set.of("aud"),new JwksSource.StaticPublicJwkSet("keys-ref"),Set.of("RS256"));
        var value=IdentityProviderConfiguration.create(site,"static-keys",config);
        assertThatThrownBy(() -> providers.save(value)).isInstanceOf(InvalidDataAccessApiUsageException.class);
        var saved=providers.save(value,publicKeys());
        assertThat(providers.findPublicJwkSet(site,"static-keys").orElseThrow().canonicalJson()).contains("public-key");
        assertThat(providers.findPublicJwkSet(other,"static-keys")).isEmpty();
        assertThat(providers.find(site,"static-keys").orElseThrow().toString()).doesNotContain("public-key","AQAB");
        assertThat(publicKeys().toString()).doesNotContain("public-key","AQAB");
        assertThat(jdbc.queryForObject("select jwks_location from identity_provider_configurations where site_id=? and provider_key='static-keys'",String.class,site.value())).isNull();
        for(String update:List.of("jwks_location='https://site.test/keys'","public_jwk_set=null","public_jwk_set='[]'","public_jwk_set='{\"keys\":[]}'","public_jwk_set_ref=null"))
            assertThatThrownBy(() -> jdbc.update("update identity_provider_configurations set "+update+" where site_id=?",site.value())).isInstanceOf(DataIntegrityViolationException.class);
        var changed=new IdentityProviderConfiguration(site,"static-keys",saved.status(),new IdentityProviderConfiguration.SiteTokenConfiguration("https://site.test",Set.of("aud"),new JwksSource.StaticPublicJwkSet("changed-ref"),Set.of("RS256")),saved.version());
        assertThatThrownBy(() -> providers.save(changed)).isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThat(providers.save(saved.disable()).version()).isEqualTo(1);
        assertThat(providers.findPublicJwkSet(site,"static-keys")).isPresent();
        var remote=IdentityProviderConfiguration.create(site,"remote-keys",new IdentityProviderConfiguration.SiteTokenConfiguration("https://site.test",Set.of("aud"),new JwksSource.Remote(java.net.URI.create("https://site.test/keys")),Set.of("RS256")));
        assertThatThrownBy(() -> providers.save(remote,publicKeys())).isInstanceOf(InvalidDataAccessApiUsageException.class);
        providers.save(remote);
        assertThat(providers.findPublicJwkSet(site,"remote-keys")).isEmpty();
        assertThatThrownBy(() -> jdbc.update("update identity_provider_configurations set public_jwk_set=?::jsonb where provider_key='remote-keys'",publicKeys().canonicalJson())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void paymentOwnershipAllowsSameSiteAndGuestsButRejectsCrossSite() {
        var customer = customers.save(customer(site));
        UUID account=jdbc.queryForObject("select payment_account_id from sites where id=?",UUID.class,site.value());
        UUID payment=UUID.randomUUID();
        jdbc.update("insert into payments(id,site_id,customer_id,payment_account_id,source,external_order_id,amount_minor,currency,tax_mode,status,created_at,updated_at) values (?,?,?,?,'DYNAMIC','owned',2000,'EUR','NONE','CREATED',now(),now())",payment,site.value(),customer.id().value(),account);
        assertThatThrownBy(() -> jdbc.update("update payments set site_id=? where id=?",other.value(),payment)).hasMessageContaining("fk_payments_customer_site");
        jdbc.update("update payments set customer_id=null where id=?",payment);
        jdbc.update("update payments set site_id=? where id=?",other.value(),payment);
        assertThat(jdbc.queryForObject("select customer_id from payments where id=?",UUID.class,payment)).isNull();
    }
    @Test void customerLockSerializesConcurrentTransactionsAndRequiresCallerTransaction() throws Exception {
        var saved=customers.save(customer(site));
        assertThatThrownBy(() -> customers.findByIdForUpdate(site,saved.id())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var acquired=new CountDownLatch(1); var release=new CountDownLatch(1); var secondStarted=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                customers.findByIdForUpdate(site,saved.id()).orElseThrow(); acquired.countDown();
                try { if (!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("lock release timeout"); } catch(InterruptedException ex){throw new RuntimeException(ex);} return true;
            }));
            assertThat(acquired.await(5,TimeUnit.SECONDS)).isTrue();
            Future<Boolean> second=executor.submit(() -> new TransactionTemplate(transactions).execute(tx -> { secondStarted.countDown(); return customers.findByIdForUpdate(site,saved.id()).isPresent(); }));
            try { assertThat(secondStarted.await(5,TimeUnit.SECONDS)).isTrue(); assertThatThrownBy(() -> second.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            assertThat(first.get(5,TimeUnit.SECONDS)).isTrue(); assertThat(second.get(5,TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void reconstitutionRejectsInvalidStoredStates() {
        var id=new CustomerId(UUID.randomUUID());
        assertThatThrownBy(() -> Customer.reconstitute(id,site,CustomerStatus.ACTIVE,null,null,INITIAL_TIME,INITIAL_TIME,null,-1)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Customer.reconstitute(id,site,CustomerStatus.ANONYMIZED,null,null,INITIAL_TIME,INITIAL_TIME,null,1)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Customer.reconstitute(id,site,CustomerStatus.ACTIVE,null,null,INITIAL_TIME,INITIAL_TIME.minusSeconds(1),null,0)).isInstanceOf(DomainValidationException.class);
    }
    @Test void generatedUpdatesKeepSitePredicatesAndOptimisticVersionPredicates() {
        var c=customers.save(customer(site));
        var i=createIdentity(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,c.id(),CustomerAuthenticationMode.EMAIL_OTP,"email-otp","v1:ZW1haWw",INITIAL_TIME));
        var p=providers.save(IdentityProviderConfiguration.create(site,"email-otp",new IdentityProviderConfiguration.EmailOtpConfiguration("sender-ref","otp-template")));
        SQL.clear();
        customers.save(c.touch(INITIAL_TIME.plusSeconds(1)));
        identities.update(i.touch(INITIAL_TIME.plusSeconds(1)));
        providers.save(p.disable());
        var updates=SQL.stream().filter(sql -> sql.startsWith("update ")).toList();
        assertThat(updates).hasSize(3);
        for(String sql:updates) assertThat(sql.substring(sql.indexOf(" where "))).contains("site_id=?","version=?");
    }
    @Test void completeV5CleanupLeavesEveryCustomerTableEmpty() {
        var c=customers.save(customer(site));
        seedAllTables(c);
        for(String table:TABLES) assertThat(jdbc.queryForObject("select count(*) from " + table,Long.class)).as(table+" seeded").isPositive();
        clearDatabase();
        for(String table:TABLES) assertThat(jdbc.queryForObject("select count(*) from " + table,Long.class)).as(table).isZero();
    }
    private void seedAllTables(Customer c) {
        providers.save(IdentityProviderConfiguration.create(site,"email-otp",new IdentityProviderConfiguration.EmailOtpConfiguration("sender-ref","otp-template")));
        createIdentity(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,c.id(),CustomerAuthenticationMode.EMAIL_OTP,"email-otp","v1:ZW1haWw",INITIAL_TIME));
        jdbc.update("insert into customer_sessions(id,site_id,customer_id,origin,token_digest,token_digest_key_version,scopes,issued_at,last_used_at,expires_at) values (?,?,?,'https://shop.test','digest','v1','[\"CUSTOMER_READ\"]',now(),now(),now()+interval '30 minutes')",UUID.randomUUID(),site.value(),c.id().value());
        jdbc.update("insert into oidc_authentication_attempts(id,site_id,provider_key,origin,intent,nonce_digest,nonce_digest_key_version,created_at,expires_at) values (?,?, 'email-otp','https://shop.test','AUTHENTICATE','digest','v1',now(),now()+interval '5 minutes')",UUID.randomUUID(),site.value());
        jdbc.update("insert into consumed_site_token_jtis(id,site_id,issuer_key,jti_digest,jti_digest_key_version,consumed_at,expires_at) values (?,?,'email-otp','digest','v1',now(),now()+interval '5 minutes')",UUID.randomUUID(),site.value());
        UUID proof=UUID.randomUUID();
        jdbc.update("insert into identity_link_proofs(id,site_id,origin,mode,issuer_key,subject_digest,subject_digest_key_version,token_digest,token_digest_key_version,verified_at,created_at,expires_at) values (?,?,'https://shop.test','EMAIL_OTP','email-otp','digest','v1','proof','v1',now(),now(),now()+interval '5 minutes')",proof,site.value());
        jdbc.update("insert into identity_link_proof_subject_aliases(site_id,proof_id,subject_digest,subject_digest_key_version) values (?,?,'digest','v1')",site.value(),proof);
        UUID challenge=UUID.randomUUID();
        jdbc.update("insert into email_otp_challenges(id,site_id,origin,intent,provider_key,email_digest,email_digest_key_version,email_ciphertext,email_nonce,email_key_version,otp_digest,otp_digest_key_version,created_at,expires_at) values (?,?,'https://shop.test','AUTHENTICATE','email-otp','digest','v1',decode(repeat('ab',16),'hex'),decode(repeat('00',12),'hex'),'test-v1','otp','v1',now(),now()+interval '10 minutes')",challenge,site.value());
        jdbc.update("insert into customer_rate_limit_buckets(id,site_id,bucket_type,bucket_digest,bucket_digest_key_version,window_started_at,expires_at) values (?,?,'SITE','digest','v1',now(),now()+interval '10 minutes')",UUID.randomUUID(),site.value());
        jdbc.update("insert into customer_mail_outbox(id,site_id,challenge_id,payload_ciphertext,payload_nonce,payload_key_version,next_attempt_at,created_at,updated_at) values (?,?,?,decode(repeat('ab',16),'hex'),decode(repeat('00',12),'hex'),'test-v1',now(),now(),now())",UUID.randomUUID(),site.value(),challenge);
        jdbc.update("insert into customer_idempotency_records(id,site_id,scope,idempotency_key,fingerprint,created_at,updated_at,expires_at) values (?,?,'ANONYMIZE','digest',?,now(),now(),now()+interval '1 day')",UUID.randomUUID(),site.value(),"a".repeat(64));
    }
}
