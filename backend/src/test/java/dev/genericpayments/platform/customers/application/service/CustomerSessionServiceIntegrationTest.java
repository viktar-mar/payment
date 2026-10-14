package dev.genericpayments.platform.customers.application.service;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.customers.application.model.CustomerSessionCreation;
import dev.genericpayments.platform.customers.application.port.out.CustomerSessionResolver;
import dev.genericpayments.platform.customers.application.port.out.CustomerSessionStore;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import dev.genericpayments.platform.customers.adapter.out.persistence.CustomerSessionPersistenceAdapter;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(CustomerSessionServiceIntegrationTest.SessionGateConfiguration.class)
class CustomerSessionServiceIntegrationTest extends PostgresIntegrationTest {
    private static final String SITE_KEY="session-site";
    private static final String ORIGIN="https://shop.example.test";
    private static final String OTHER_ORIGIN="https://other.example.test";

    @Autowired CustomerSessionService sessions;
    @Autowired CustomerSessionResolver resolver;
    @Autowired CustomerStore customers;
    @Autowired JdbcTemplate jdbc;
    @Autowired ControllableCustomerSessionStore sessionGate;

    private SiteId siteId;
    private Customer customer;

    @BeforeEach
    void fixture() {
        sessionGate.reset();
        clearDatabase();
        siteId=new SiteId(UUID.randomUUID());
        UUID account=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'session-account','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        jdbc.update("insert into sites values (?,?, 'Sessions',?,'ACTIVE','AUTOMATIC',now(),now(),0)",siteId.value(),SITE_KEY,account);
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,?),(?,?)",siteId.value(),ORIGIN,siteId.value(),OTHER_ORIGIN);
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'SITE_TOKEN')",siteId.value());
        customer=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),siteId,null,null,INITIAL_TIME));
    }

    @Test
    void issuanceReturnsThirtyTwoRandomBytesOnceAndPersistsOnlyThePurposeSeparatedDigest() {
        CustomerSessionCreation created=sessions.issue(siteId,customer.id(),ORIGIN);

        assertThat(Base64.getUrlDecoder().decode(created.rawToken())).hasSize(32);
        assertThat(created.expiresAt()).isEqualTo(INITIAL_TIME.plus(Duration.ofMinutes(30)));
        assertThat(created.scopes()).containsExactlyInAnyOrder(CustomerSessionScope.CUSTOMER_READ,
            CustomerSessionScope.CHECKOUT_CREATE,CustomerSessionScope.IDENTITY_LINK);
        Map<String,Object> stored=jdbc.queryForMap("select * from customer_sessions where id=?",created.sessionId().value());
        assertThat(stored.toString()).doesNotContain(created.rawToken());
        assertThat(stored.get("token_digest")).asString().doesNotContain(created.rawToken());
        assertThat(stored.get("token_digest_key_version")).isEqualTo("v1");
        assertThat(created.toString()).doesNotContain(created.rawToken());
    }

    @Test
    void successfulUseIsExactlyBoundAndNeverExtendsTheAbsoluteExpiry() {
        CustomerSessionCreation created=sessions.issue(siteId,customer.id(),ORIGIN);
        TestClock.set(INITIAL_TIME.plusSeconds(600));

        var context=resolver.resolveRequired(created.rawToken(),ORIGIN);

        assertThat(context.sessionId()).isEqualTo(created.sessionId());
        assertThat(context.siteId()).isEqualTo(siteId);
        assertThat(context.customerId()).isEqualTo(customer.id());
        assertThat(context.origin()).isEqualTo(ORIGIN);
        assertThat(context.expiresAt()).isEqualTo(created.expiresAt());
        assertThat(jdbc.queryForObject("select last_used_at from customer_sessions where id=?",Timestamp.class,created.sessionId().value()).toInstant())
            .isEqualTo(INITIAL_TIME.plusSeconds(600));
        assertThat(jdbc.queryForObject("select expires_at from customer_sessions where id=?",Timestamp.class,created.sessionId().value()).toInstant())
            .isEqualTo(created.expiresAt());
    }

    @Test
    void optionalResolverDistinguishesAbsentFromEverySuppliedInvalidCredential() {
        CustomerSessionCreation created=sessions.issue(siteId,customer.id(),ORIGIN);

        assertThat(resolver.resolveOptional(null,siteId,ORIGIN)).isEmpty();
        for(String value:List.of("","Basic value","Bearer","Bearer invalid","Bearer  "+created.rawToken(),
                "Bearer "+created.rawToken()+" extra","Bearer "+created.rawToken()+",other")) {
            assertAuthenticationFailure(() -> resolver.resolveOptional(value,siteId,ORIGIN));
        }
    }

    @Test
    void validSessionCannotCrossOriginOrSiteBoundaries() {
        CustomerSessionCreation created=sessions.issue(siteId,customer.id(),ORIGIN);
        assertAuthenticationFailure(() -> resolver.resolveRequired(created.rawToken(),OTHER_ORIGIN));

        SiteId otherSite=new SiteId(UUID.randomUUID());
        UUID account=jdbc.queryForObject("select id from payment_accounts",UUID.class);
        jdbc.update("insert into sites values (?,?, 'Other',?,'ACTIVE','AUTOMATIC',now(),now(),0)",otherSite.value(),"other-session-site",account);
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,?)",otherSite.value(),ORIGIN);
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'SITE_TOKEN')",otherSite.value());
        assertAuthenticationFailure(() -> resolver.resolveOptional("Bearer "+created.rawToken(),otherSite,ORIGIN));
    }

    @Test
    void expiredRevokedAnonymizedAndDisabledSiteSessionsAllFailClosed() {
        CustomerSessionCreation expired=sessions.issue(siteId,customer.id(),ORIGIN);
        TestClock.set(expired.expiresAt());
        assertAuthenticationFailure(() -> resolver.resolveRequired(expired.rawToken(),ORIGIN));

        TestClock.set(INITIAL_TIME);
        CustomerSessionCreation revoked=sessions.issue(siteId,customer.id(),ORIGIN);
        sessions.logout(resolver.resolveRequired(revoked.rawToken(),ORIGIN));
        assertAuthenticationFailure(() -> resolver.resolveRequired(revoked.rawToken(),ORIGIN));

        CustomerSessionCreation anonymized=sessions.issue(siteId,customer.id(),ORIGIN);
        jdbc.update("update customers set status='ANONYMIZED',anonymized_at=?,updated_at=? where id=?",
            Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME),customer.id().value());
        assertAuthenticationFailure(() -> resolver.resolveRequired(anonymized.rawToken(),ORIGIN));

        jdbc.update("update customers set status='ACTIVE',anonymized_at=null where id=?",customer.id().value());
        CustomerSessionCreation disabled=sessions.issue(siteId,customer.id(),ORIGIN);
        jdbc.update("update sites set status='DISABLED' where id=?",siteId.value());
        assertAuthenticationFailure(() -> resolver.resolveRequired(disabled.rawToken(),ORIGIN));
    }

    @Test
    void aRealServiceLogoutThatWinsTheRowLockPreventsTheLaterUseAndRemainsIdempotent() throws Exception {
        CustomerSessionCreation created=sessions.issue(siteId,customer.id(),ORIGIN);
        var gate=sessionGate.blockNextLockFrom("logout-winner-");

        try(var winnerExecutor=namedVirtualExecutor("logout-winner-");
                var laterExecutor=namedVirtualExecutor("later-use-")) {
            Future<?> winner=winnerExecutor.submit(() -> sessions.logout(created.context()));
            gate.awaitLocked();
            Future<Throwable> laterUse=laterExecutor.submit(() -> catchThrowable(
                () -> resolver.resolveRequired(created.rawToken(),ORIGIN)));
            gate.release();
            winner.get(5,TimeUnit.SECONDS);
            assertThat(laterUse.get(5,TimeUnit.SECONDS)).isInstanceOf(PaymentApplicationException.class);
        }

        assertThatCode(() -> sessions.logout(created.context())).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select revoked_reason from customer_sessions where id=?",String.class,created.sessionId().value()))
            .isEqualTo("LOGOUT");
    }

    @Test
    void aRealServiceUseThatWinsTheRowLockCompletesBeforeTheLaterLogoutRevokesTheSession() throws Exception {
        CustomerSessionCreation created=sessions.issue(siteId,customer.id(),ORIGIN);
        TestClock.set(INITIAL_TIME.plusSeconds(60));
        var gate=sessionGate.blockNextLockFrom("use-winner-");

        try(var winnerExecutor=namedVirtualExecutor("use-winner-");
                var laterExecutor=namedVirtualExecutor("later-logout-")) {
            Future<dev.genericpayments.platform.customers.application.model.CustomerSessionContext> winner=
                winnerExecutor.submit(() -> resolver.resolveRequired(created.rawToken(),ORIGIN));
            gate.awaitLocked();
            Future<?> laterLogout=laterExecutor.submit(() -> sessions.logout(created.context()));
            gate.release();
            assertThat(winner.get(5,TimeUnit.SECONDS).sessionId()).isEqualTo(created.sessionId());
            laterLogout.get(5,TimeUnit.SECONDS);
        }

        assertAuthenticationFailure(() -> resolver.resolveRequired(created.rawToken(),ORIGIN));
        assertThat(jdbc.queryForObject("select revoked_reason from customer_sessions where id=?",String.class,created.sessionId().value()))
            .isEqualTo("LOGOUT");
    }

    private static ExecutorService namedVirtualExecutor(String prefix) {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(prefix,0).factory());
    }

    @Test
    void committedLogoutCannotBeUndoneByALaterUseWithAPreviouslyManagedCandidate() throws Exception {
        CustomerSessionCreation created=sessions.issue(siteId,customer.id(),ORIGIN);
        var logoutGate=sessionGate.blockNextLockFrom("logout-winner-");
        var candidateGate=sessionGate.blockNextCandidatesFrom("later-use-");

        try(var winners=namedVirtualExecutor("logout-winner-"); var later=namedVirtualExecutor("later-use-")) {
            try {
                Future<?> logout=winners.submit(() -> sessions.logout(created.context()));
                logoutGate.awaitLocked();
                Throwable lockFailure=catchThrowable(() -> jdbc.queryForObject(
                    "select id from customer_sessions where id=? for update nowait",UUID.class,created.sessionId().value()));
                assertThat(lockFailure).isInstanceOf(org.springframework.dao.DataAccessException.class);
                Throwable root=lockFailure;
                while(root.getCause()!=null) root=root.getCause();
                assertThat(root).isInstanceOf(java.sql.SQLException.class);
                assertThat(((java.sql.SQLException)root).getSQLState()).isEqualTo("55P03");

                Future<Throwable> use=later.submit(() -> catchThrowable(
                    () -> resolver.resolveRequired(created.rawToken(),ORIGIN)));
                candidateGate.awaitLocked();
                assertThat(sessionGate.laterCandidate.revokedAt()).isEmpty();
                assertThat(sessionGate.laterCandidate.version()).isZero();
                logoutGate.release();
                logout.get(5,TimeUnit.SECONDS);
                assertThat(jdbc.queryForObject("select revoked_reason from customer_sessions where id=?",String.class,created.sessionId().value()))
                    .isEqualTo("LOGOUT");
                assertThat(jdbc.queryForObject("select version from customer_sessions where id=?",Long.class,created.sessionId().value()))
                    .isEqualTo(1L);

                candidateGate.release();
                Throwable result=use.get(5,TimeUnit.SECONDS);
                Assertions.assertAll(
                    () -> assertThat(sessionGate.laterLocked.revokedAt()).as("locked state reflects committed logout").isPresent(),
                    () -> assertThat(sessionGate.laterLocked.version()).as("locked state reflects committed version").isEqualTo(1L),
                    () -> assertAuthenticationFailure(() -> { if(result!=null) throw result; }),
                    () -> assertThat(jdbc.queryForObject("select revoked_reason from customer_sessions where id=?",String.class,created.sessionId().value()))
                        .isEqualTo("LOGOUT"),
                    () -> assertThat(jdbc.queryForObject("select version from customer_sessions where id=?",Long.class,created.sessionId().value()))
                        .isEqualTo(1L));
            } finally {
                logoutGate.release();
                candidateGate.release();
            }
        }

        assertThatCode(() -> sessions.logout(created.context())).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select revoked_reason from customer_sessions where id=?",String.class,created.sessionId().value()))
            .isEqualTo("LOGOUT");
        assertThat(jdbc.queryForObject("select version from customer_sessions where id=?",Long.class,created.sessionId().value()))
            .isEqualTo(1L);
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class SessionGateConfiguration {
        @Bean @Primary ControllableCustomerSessionStore controllableCustomerSessionStore(
                CustomerSessionPersistenceAdapter delegate) {
            return new ControllableCustomerSessionStore(delegate);
        }
    }

    static final class ControllableCustomerSessionStore implements CustomerSessionStore {
        private final CustomerSessionStore delegate;
        private final AtomicReference<LockGate> nextGate=new AtomicReference<>();
        private final AtomicReference<LockGate> nextCandidateGate=new AtomicReference<>();
        volatile CustomerSession laterCandidate;
        volatile CustomerSession laterLocked;

        ControllableCustomerSessionStore(CustomerSessionStore delegate) { this.delegate=delegate; }

        LockGate blockNextLockFrom(String threadPrefix) {
            LockGate gate=new LockGate(threadPrefix,new CountDownLatch(1),new CountDownLatch(1));
            if(!nextGate.compareAndSet(null,gate)) throw new IllegalStateException("A session lock gate is already armed");
            return gate;
        }

        LockGate blockNextCandidatesFrom(String threadPrefix) {
            LockGate gate=new LockGate(threadPrefix,new CountDownLatch(1),new CountDownLatch(1));
            if(!nextCandidateGate.compareAndSet(null,gate)) throw new IllegalStateException("A candidate gate is already armed");
            return gate;
        }

        void reset() {
            LockGate gate=nextGate.getAndSet(null);
            if(gate!=null) gate.release();
            LockGate candidateGate=nextCandidateGate.getAndSet(null);
            if(candidateGate!=null) candidateGate.release();
            laterCandidate=null;
            laterLocked=null;
        }

        @Override public List<CustomerSession> findCandidates(
                List<dev.genericpayments.platform.customers.application.port.out.KeyedDigestPort.DigestValue> digests) {
            List<CustomerSession> result=delegate.findCandidates(digests);
            LockGate gate=nextCandidateGate.get();
            if(gate!=null && Thread.currentThread().getName().startsWith(gate.threadPrefix())
                    && nextCandidateGate.compareAndSet(gate,null)) {
                laterCandidate=result.getFirst();
                gate.blockWhileHoldingLock();
            }
            return result;
        }

        @Override public Optional<CustomerSession> findByIdForUpdate(CustomerSessionId id) {
            Optional<CustomerSession> result=delegate.findByIdForUpdate(id);
            if(Thread.currentThread().getName().startsWith("later-use-")) laterLocked=result.orElse(null);
            LockGate gate=nextGate.get();
            if(gate!=null && Thread.currentThread().getName().startsWith(gate.threadPrefix())
                    && nextGate.compareAndSet(gate,null)) gate.blockWhileHoldingLock();
            return result;
        }

        @Override public CustomerSession save(CustomerSession session) { return delegate.save(session); }
    }

    record LockGate(String threadPrefix,CountDownLatch locked,CountDownLatch releaseLatch) {
        void awaitLocked() throws Exception {
            if(!locked.await(5,TimeUnit.SECONDS)) throw new TimeoutException("Session row lock was not reached");
        }
        void blockWhileHoldingLock() {
            locked.countDown();
            try {
                if(!releaseLatch.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Session row lock gate timed out");
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        void release() { releaseLatch.countDown(); }
    }

    @Test
    void postgresMicrosecondPrecisionIsCanonicalAtThePublicBoundary() {
        Instant precise=Instant.parse("2026-09-13T10:15:30.123456789Z");
        TestClock.set(precise);
        CustomerSessionCreation created=sessions.issue(siteId,customer.id(),ORIGIN);
        assertThat(created.expiresAt()).isEqualTo(Instant.parse("2026-09-13T10:45:30.123456Z"));
        assertThat(resolver.resolveRequired(created.rawToken(),ORIGIN).expiresAt()).isEqualTo(created.expiresAt());
    }

    private static void assertAuthenticationFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(PaymentApplicationException.class,error -> {
            assertThat(error.code()).isEqualTo("AUTHENTICATION_FAILED");
            assertThat(error.status()).isEqualTo(401);
            assertThat(error).hasMessage("Authentication failed").hasNoCause();
        });
    }
}
