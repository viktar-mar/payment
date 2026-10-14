package dev.genericpayments.platform.customers.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.customers.adapter.out.security.*;
import dev.genericpayments.platform.customers.application.model.VerifiedIdentityEvidence;
import dev.genericpayments.platform.customers.application.model.CustomerAuthenticationResult;
import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.*;
import org.junit.jupiter.api.extension.ExtendWith;

@Import(CustomerAuthenticationRotationIntegrationTest.RotationDigestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class CustomerAuthenticationRotationIntegrationTest extends AuthenticationServiceIntegrationSupport {
    @Autowired BeginOidcAuthenticationUseCase begin;
    @Autowired ExchangeOidcTokenUseCase oidc;
    @Autowired ExchangeSiteTokenUseCase siteTokens;
    @Autowired RotationDigestPort rotation;
    @Autowired CustomerSessionResolver sessionResolver;
    @Autowired EmailNormalizationPort emails;
    @Autowired EncryptedSecretPort encryption;
    @Autowired IdentityResolutionTransaction resolutionTransactions;
    @Autowired CustomerStore customers;
    @Autowired GatedIdentityStore identityGate;

    @BeforeEach void useV1() { rotation.useV1(); }

    @Test void sdkSessionIssuedUnderV1WorksWhileV1IsRetainedAndFailsAfterRetirement() {
        var issued=(CustomerAuthenticationResult.AuthenticatedCustomer)siteTokens.exchange(
            new ExchangeSiteTokenUseCase.Command(SITE_KEY,SITE_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE,
                "site:sdk-session-rotation-subject:sdk-session-rotation-jti"));
        String rawSessionToken=issued.session().rawToken();
        assertThat(jdbc.queryForObject("select token_digest_key_version from customer_sessions",String.class)).isEqualTo("v1");

        rotation.useV2RetainingV1();
        assertThat(sessionResolver.resolveRequired(rawSessionToken,ORIGIN).sessionId()).isEqualTo(issued.session().sessionId());
        assertThat(jdbc.queryForObject("select token_digest_key_version from customer_sessions",String.class)).isEqualTo("v1");

        rotation.useV2Only();
        assertFailure(() -> sessionResolver.resolveRequired(rawSessionToken,ORIGIN));
    }

    @Test void retainedSubjectDigestResolvesTheOriginalCustomerAfterActiveKeyRotation() {
        var first=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        var original=(CustomerAuthenticationResult.AuthenticatedCustomer)oidc.exchange(new ExchangeOidcTokenUseCase.Command(
            first.attemptId(),SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE,first.nonce(),
            "oidc:rotation-subject:rotation-before:"+first.nonce()+":-"));

        rotation.useV2RetainingV1();
        var second=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        var after=(CustomerAuthenticationResult.AuthenticatedCustomer)oidc.exchange(new ExchangeOidcTokenUseCase.Command(
            second.attemptId(),SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE,second.nonce(),
            "oidc:rotation-subject:rotation-after:"+second.nonce()+":-"));

        assertThat(after.customerId()).isEqualTo(original.customerId());
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select subject_digest_key_version from customer_identities",String.class)).isEqualTo("v1");
        assertThat(jdbc.queryForList("select subject_digest_key_version from customer_identity_subject_aliases order by subject_digest_key_version",String.class))
            .containsExactly("v1","v2");
    }

    @Test void newerNodeWinningFirstIsDiscoverableByAV1OnlyNodeWithoutCreatingSplitOwnership() {
        var newer=identityService(RotationDigestPort.v2RetainingV1());
        var older=identityService(RotationDigestPort.v1Only());
        var evidence=evidence("newer-first-subject");

        Customer newerWinner=newer.resolve(siteId,CustomerAuthenticationMode.OIDC_TOKEN,evidence,Instant.parse("2026-09-13T10:15:31Z"));
        Customer olderResult=older.resolve(siteId,CustomerAuthenticationMode.OIDC_TOKEN,evidence,Instant.parse("2026-09-13T10:15:32Z"));

        assertThat(olderResult.id()).isEqualTo(newerWinner.id());
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        assertThat(jdbc.queryForList("select subject_digest_key_version from customer_identity_subject_aliases order by subject_digest_key_version",String.class))
            .containsExactly("v1","v2");
    }

    @Test void olderNodeWinningFirstIsBackfilledForTheNewActiveVersion() {
        var older=identityService(RotationDigestPort.v1Only());
        var newer=identityService(RotationDigestPort.v2RetainingV1());
        var evidence=evidence("older-first-subject");

        Customer olderWinner=older.resolve(siteId,CustomerAuthenticationMode.OIDC_TOKEN,evidence,Instant.parse("2026-09-13T10:15:31Z"));
        Customer newerResult=newer.resolve(siteId,CustomerAuthenticationMode.OIDC_TOKEN,evidence,Instant.parse("2026-09-13T10:15:32Z"));

        assertThat(newerResult.id()).isEqualTo(olderWinner.id());
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        assertThat(jdbc.queryForList("select subject_digest_key_version from customer_identity_subject_aliases order by subject_digest_key_version",String.class))
            .containsExactly("v1","v2");
    }

    @Test void concurrentV1OnlyAndV2RetainingV1NodesReturnOneDurableOwner() throws Exception {
        var older=identityService(RotationDigestPort.v1Only());
        var newer=identityService(RotationDigestPort.v2RetainingV1());
        var evidence=evidence("mixed-generation-race-subject");
        var bothAtAliasReservation=new CountDownLatch(2); var releaseAliasReservation=new CountDownLatch(1);
        identityGate.blockNextAliasReservations(bothAtAliasReservation,releaseAliasReservation);

        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            var olderCall=executor.submit(() -> { start.await(); return older.resolve(siteId,CustomerAuthenticationMode.OIDC_TOKEN,evidence,
                Instant.parse("2026-09-13T10:15:31Z")); });
            var newerCall=executor.submit(() -> { start.await(); return newer.resolve(siteId,CustomerAuthenticationMode.OIDC_TOKEN,evidence,
                Instant.parse("2026-09-13T10:15:31Z")); });
            start.countDown();
            assertThat(bothAtAliasReservation.await(5,TimeUnit.SECONDS)).isTrue();
            releaseAliasReservation.countDown();
            var ids=List.of(olderCall.get(10,TimeUnit.SECONDS),newerCall.get(10,TimeUnit.SECONDS)).stream().map(Customer::id).toList();
            assertThat(ids).containsOnly(ids.getFirst());
        }
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        assertThat(jdbc.queryForList("select subject_digest_key_version from customer_identity_subject_aliases order by subject_digest_key_version",String.class))
            .containsExactly("v1","v2");
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void oppositeActiveRetainedSetsSerializeWithoutDeadlockOrSensitiveConflictLogs(boolean reverseSubmission,CapturedOutput output) throws Exception {
        var v1Active=identityService(RotationDigestPort.v1RetainingV2());
        var v2Active=identityService(RotationDigestPort.v2RetainingV1());
        String subject="opposite-active-race-subject-"+reverseSubmission;
        var evidence=evidence(subject);
        var v1Digest=RotationDigestPort.v1RetainingV2().candidates(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,subject).stream()
            .filter(candidate -> candidate.keyVersion().equals("v1")).findFirst().orElseThrow();
        var v2Digest=RotationDigestPort.v2RetainingV1().candidates(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,subject).stream()
            .filter(candidate -> candidate.keyVersion().equals("v2")).findFirst().orElseThrow();
        var bothAtAliasReservation=new CountDownLatch(2); var releaseAliasReservation=new CountDownLatch(1);
        identityGate.blockNextAliasReservations(bothAtAliasReservation,releaseAliasReservation);

        List<Object> outcomes;
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            Callable<Object> v1Call=() -> resolveCapturingFailure(v1Active,evidence,start);
            Callable<Object> v2Call=() -> resolveCapturingFailure(v2Active,evidence,start);
            var first=executor.submit(reverseSubmission?v2Call:v1Call);
            var second=executor.submit(reverseSubmission?v1Call:v2Call);
            start.countDown();
            assertThat(bothAtAliasReservation.await(5,TimeUnit.SECONDS)).isTrue();
            releaseAliasReservation.countDown();
            outcomes=List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(Customer.class));
        var ids=outcomes.stream().map(Customer.class::cast).map(Customer::id).toList();
        assertThat(ids).containsOnly(ids.getFirst());
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(distinct identity_id) from customer_identity_subject_aliases",Integer.class)).isOne();
        assertThat(jdbc.queryForList("select subject_digest_key_version from customer_identity_subject_aliases order by subject_digest_key_version",String.class))
            .containsExactly("v1","v2");
        assertThat(output.getAll()).doesNotContain(subject,"fixture-token-id",v1Digest.value(),v2Digest.value(),
            "SQLState: 23505","SQLState: 40P01","duplicate key value violates unique constraint",
            "Key (site_id, issuer_key, subject_digest, subject_digest_key_version)");
    }

    @Test void splitAliasesFailClosedRegardlessOfWhichRetainedKeyIsActive() {
        KeyedDigestPort v2Active=RotationDigestPort.v2RetainingV1();
        String subject="deliberately-split-subject";
        String v1=reference(RotationDigestPort.v1Only().digest(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,subject));
        String v2=reference(v2Active.digest(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,subject));
        Customer first=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),siteId,null,null,Instant.parse("2026-09-13T10:15:30Z")));
        Customer second=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),siteId,null,null,Instant.parse("2026-09-13T10:15:30Z")));
        identityGate.create(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),siteId,first.id(),CustomerAuthenticationMode.OIDC_TOKEN,
            OIDC_PROVIDER,v1,Instant.parse("2026-09-13T10:15:30Z")),List.of(v1));
        identityGate.create(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),siteId,second.id(),CustomerAuthenticationMode.OIDC_TOKEN,
            OIDC_PROVIDER,v2,Instant.parse("2026-09-13T10:15:30Z")),List.of(v2));

        assertFailure(() -> identityService(v2Active).resolve(siteId,CustomerAuthenticationMode.OIDC_TOKEN,evidence(subject),Instant.parse("2026-09-13T10:15:31Z")));
        assertFailure(() -> identityService(RotationDigestPort.v1RetainingV2()).resolve(siteId,CustomerAuthenticationMode.OIDC_TOKEN,evidence(subject),Instant.parse("2026-09-13T10:15:31Z")));
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isEqualTo(2);
    }

    @Test void retainedJtiDigestRejectsAReplayAfterActiveKeyRotationWithoutPartialReservation() {
        var command=new ExchangeSiteTokenUseCase.Command(SITE_KEY,SITE_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE,
            "site:rotation-subject:rotation-jti");
        assertThat(siteTokens.exchange(command)).isInstanceOf(CustomerAuthenticationResult.AuthenticatedCustomer.class);

        rotation.useV2RetainingV1();
        assertFailure(() -> siteTokens.exchange(command));

        assertThat(jdbc.queryForObject("select count(*) from consumed_site_token_jtis",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select jti_digest_key_version from consumed_site_token_jtis",String.class)).isEqualTo("v1");
    }

    @Test void freshJtiAfterRotationAtomicallyReservesEveryRetainedDigest() {
        rotation.useV2RetainingV1();
        siteTokens.exchange(new ExchangeSiteTokenUseCase.Command(SITE_KEY,SITE_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE,
            "site:fresh-rotation-subject:fresh-rotation-jti"));

        assertThat(jdbc.queryForList("select jti_digest_key_version from consumed_site_token_jtis order by jti_digest_key_version",String.class))
            .containsExactly("v1","v2");
        assertThat(jdbc.queryForObject("select count(distinct issuer_key) from consumed_site_token_jtis",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(distinct consumed_at) from consumed_site_token_jtis",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(distinct expires_at) from consumed_site_token_jtis",Integer.class)).isOne();
    }

    @Test void concurrentSameJtiHasOneWinnerAndOneUniformFailureWithoutPartialCandidates() throws Exception {
        rotation.useV2RetainingV1();
        var command=new ExchangeSiteTokenUseCase.Command(SITE_KEY,SITE_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE,
            "site:concurrent-rotation-subject:concurrent-rotation-jti");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new CountDownLatch(1);
            Callable<Object> call=() -> {
                gate.await();
                try { return siteTokens.exchange(command); }
                catch(RuntimeException failure) { return failure; }
            };
            var first=executor.submit(call); var second=executor.submit(call); gate.countDown();
            var outcomes=List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(CustomerAuthenticationResult.AuthenticatedCustomer.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(PaymentApplicationException.class::isInstance).singleElement()
                .satisfies(value -> assertUniform((PaymentApplicationException)value));
        }
        assertThat(jdbc.queryForList("select jti_digest_key_version from consumed_site_token_jtis order by jti_digest_key_version",String.class))
            .containsExactly("v1","v2");
        assertThat(jdbc.queryForObject("select count(distinct consumed_at) from consumed_site_token_jtis",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
    }

    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PaymentApplicationException.class,
            CustomerAuthenticationRotationIntegrationTest::assertUniform);
    }
    private static void assertUniform(PaymentApplicationException error) {
        assertThat(error.code()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(error.status()).isEqualTo(401);
        assertThat(error).hasMessage("Authentication failed").hasNoCause();
    }

    private IdentityResolutionService identityService(KeyedDigestPort digest) {
        return new IdentityResolutionService(digest,emails,encryption,resolutionTransactions);
    }

    private Customer resolveCapturingFailure(IdentityResolutionService service,VerifiedIdentityEvidence evidence,CountDownLatch start) {
        try {
            start.await();
            return service.resolve(siteId,CustomerAuthenticationMode.OIDC_TOKEN,evidence,Instant.parse("2026-09-13T10:15:31Z"));
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static VerifiedIdentityEvidence evidence(String subject) {
        return new VerifiedIdentityEvidence(subject,OIDC_PROVIDER,Optional.empty(),Optional.of("fixture-token-id"),
            Instant.parse("2026-09-13T10:15:29Z"),Instant.parse("2026-09-13T10:20:29Z"));
    }
    private static String reference(KeyedDigestPort.DigestValue value) { return value.keyVersion()+":"+value.value(); }

    @TestConfiguration(proxyBeanMethods=false)
    static class RotationDigestConfiguration {
        @Bean @Primary RotationDigestPort rotationDigestPort() { return new RotationDigestPort(); }
        @Bean @Primary GatedIdentityStore gatedIdentityStore(dev.genericpayments.platform.customers.adapter.out.persistence.CustomerPersistenceAdapter delegate) {
            return new GatedIdentityStore(delegate);
        }
    }

    static final class RotationDigestPort implements KeyedDigestPort {
        private static final String V1=Base64.getEncoder().encodeToString(new byte[32]);
        private static final String V2=Base64.getEncoder().encodeToString(new byte[48]);
        private final AtomicReference<KeyedDigestPort> current=new AtomicReference<>();
        RotationDigestPort() { useV1(); }
        void useV1() { current.set(adapter("v1",Map.of("v1",V1))); }
        void useV2RetainingV1() { current.set(adapter("v2",Map.of("v1",V1,"v2",V2))); }
        void useV2Only() { current.set(adapter("v2",Map.of("v2",V2))); }
        static KeyedDigestPort v1Only() { return adapter("v1",Map.of("v1",V1)); }
        static KeyedDigestPort v2RetainingV1() { return adapter("v2",Map.of("v1",V1,"v2",V2)); }
        static KeyedDigestPort v1RetainingV2() { return adapter("v1",Map.of("v1",V1,"v2",V2)); }
        @Override public DigestValue digest(Purpose purpose,String value) { return current.get().digest(purpose,value); }
        @Override public List<DigestValue> candidates(Purpose purpose,String value) { return current.get().candidates(purpose,value); }
        @Override public boolean matches(Purpose purpose,String value,DigestValue expected) { return current.get().matches(purpose,value,expected); }
        @Override public String toString() { return "RotationDigestPort[redacted]"; }
        private static KeyedDigestPort adapter(String active,Map<String,String> keys) {
            return new HmacSha256KeyedDigestAdapter(new CustomerSecurityProperties(active,keys));
        }
    }

    static final class GatedIdentityStore implements CustomerIdentityStore {
        private final CustomerIdentityStore delegate;
        private final AtomicReference<Block> nextAliasReservation=new AtomicReference<>();
        GatedIdentityStore(CustomerIdentityStore delegate) { this.delegate=delegate; }
        void blockNextAliasReservations(CountDownLatch entered,CountDownLatch release) { nextAliasReservation.set(new Block(entered,release)); }
        @Override public Optional<CustomerIdentity> find(dev.genericpayments.platform.sites.domain.SiteId site,String issuer,String digest) { return delegate.find(site,issuer,digest); }
        @Override public Optional<CustomerIdentity> findForUpdate(dev.genericpayments.platform.sites.domain.SiteId site,String issuer,String digest) { return delegate.findForUpdate(site,issuer,digest); }
        @Override public List<SubjectAlias> reserveSubjectAliases(dev.genericpayments.platform.sites.domain.SiteId site,String issuer,List<String> digests) {
            Block block=nextAliasReservation.get();
            if(block!=null) {
                block.entered().countDown();
                await(block.release());
                if(block.entered().getCount()==0) nextAliasReservation.compareAndSet(block,null);
            }
            return delegate.reserveSubjectAliases(site,issuer,digests);
        }
        @Override public void saveSubjectAliases(CustomerIdentity identity,List<String> digests) {
            delegate.saveSubjectAliases(identity,digests);
        }
        @Override public List<CustomerIdentity> list(dev.genericpayments.platform.sites.domain.SiteId site,CustomerId customer) { return delegate.list(site,customer); }
        @Override public CustomerIdentity create(CustomerIdentity identity,List<String> digests) { return delegate.create(identity,digests); }
        @Override public CustomerIdentity update(CustomerIdentity identity) { return delegate.update(identity); }
        @Override public void deleteByCustomer(dev.genericpayments.platform.sites.domain.SiteId site,CustomerId customer) { delegate.deleteByCustomer(site,customer); }
        private static void await(CountDownLatch release) {
            try { if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("alias reservation release timeout"); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        }
        private record Block(CountDownLatch entered,CountDownLatch release) {}
    }
}
