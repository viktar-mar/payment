package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.application.port.out.StripeBillingClockStore.StopReason;
import dev.genericpayments.platform.sites.adapter.out.persistence.SitePersistenceAdapter;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.sites.domain.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Lookup;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionConfirmationProvider;
import dev.genericpayments.platform.support.StripeBillingClockConfirmationCompositionFixture;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.*;
import static org.assertj.core.api.Assertions.*;

/** Finite contention checks run only in hosted CI with the real PostgreSQL transactions. */
@Import(StripeBillingClockConfirmationRaceIntegrationTest.Gates.class)
class StripeBillingClockConfirmationRaceIntegrationTest extends StripeBillingClockConfirmationCompositionFixture {
    @Autowired SiteGate siteGate;
    @Autowired ConfirmationJdbc jdbcGate;

    @Test void twoReadyServiceRequestsCompeteForOneActualDurableClaim() throws Exception {
        beginConfirmation();issueAndSelect();
        openGraph();openGraph();openGraph();http.reply(response("succeeded").toString());http.reply(clockJson(owner));
        var bothPreflight=new CountDownLatch(2);var io=new Object();
        var competing=receiptBoundService(new SubscriptionConfirmationProvider() {
            public String capability(SubscriptionClientGrant root,SubscriptionConfirmationChain chain,SubscriptionConfirmationAttempt attempt) {
                return confirmationProvider.capability(root,chain,attempt);
            }
            public Material read(Lookup lookup,SubscriptionClientGrant root,SubscriptionConfirmationAttempt.Receipt predecessor) {
                Material material;
                // Keep the shared inert response queue's graph boundaries intact. The actual provider
                // and its real guarded phases still perform every read before either claim proceeds.
                synchronized(io){material=confirmationProvider.read(lookup,root,predecessor);}
                bothPreflight.countDown();await(bothPreflight);return material;
            }
            public Outcome confirm(Lookup lookup,SubscriptionClientGrant root,SubscriptionConfirmationAttempt attempt,String token) {
                return confirmationProvider.confirm(lookup,root,attempt,token);
            }
        });
        inspectSdk(this::assertClaimBeforePost);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->competing.confirm(scope,selected.attemptId(),selected.capability(),TOKEN));
            var second=pool.submit(()->competing.confirm(scope,selected.attemptId(),selected.capability(),TOKEN));
            var a=first.get(20,TimeUnit.SECONDS);var b=second.get(20,TimeUnit.SECONDS);
            assertThat(List.of(a.status().state(),b.status().state())).contains(SubscriptionConfirmationAttempt.State.OBSERVE)
                .allSatisfy(state->assertThat(state).isIn(SubscriptionConfirmationAttempt.State.OBSERVE,SubscriptionConfirmationAttempt.State.DISPATCHING));
            assertThat(confirmPosts()).isEqualTo(1);assertThat(http.replies).isEmpty();
            assertThat(count("subscription_confirmation_attempts")).isEqualTo(1);
            assertThat(jdbc.queryForObject("select state from subscription_confirmation_attempts",String.class)).isEqualTo("OBSERVE");
            assertThat(jdbc.queryForObject("select version from subscription_confirmation_attempts",Long.class)).isEqualTo(2L);
            assertNoSuccess();
        } finally {shutdown(pool);}
    }

    @Test void concurrentReplayDuringActualConsumedPostHasOneServiceWinner() throws Exception {
        beginConfirmation();issueAndSelect();queueConfirm("succeeded");
        var dispatched=new CountDownLatch(1);var release=new CountDownLatch(1);
        inspectSdk(request->{
            assertClaimBeforePost(request);
            if(confirmPost(request)){dispatched.countDown();await(release);}
        });
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->confirmation.confirm(scope,selected.attemptId(),selected.capability(),TOKEN));
            assertThat(dispatched.await(10,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->confirmation.confirm(scope,selected.attemptId(),selected.capability(),TOKEN));
            var replay=second.get(10,TimeUnit.SECONDS);
            assertThat(replay.status().state()).isEqualTo(SubscriptionConfirmationAttempt.State.DISPATCHING);
            assertThat(replay.nextActionAllowed()).isFalse();assertThat(replay.status().retryAllowed()).isFalse();
            assertThat(confirmPosts()).isEqualTo(1);assertNoSuccess();
            release.countDown();
            var finished=first.get(15,TimeUnit.SECONDS);
            assertThat(finished.status().state()).isEqualTo(SubscriptionConfirmationAttempt.State.OBSERVE);
            assertThat(confirmation.confirm(scope,selected.attemptId(),selected.capability(),TOKEN).status()).isEqualTo(finished.status());
            assertThat(confirmPosts()).isEqualTo(1);assertThat(http.replies).isEmpty();
            assertThat(count("subscription_confirmation_attempts")).isEqualTo(1);assertNoSuccess();
        } finally {release.countDown();shutdown(pool);}
    }

    @Test void stopCompletesWhileActualSdkPostIsUnlockedAndPreventsLatePublication() throws Exception {
        beginConfirmation();issueAndSelect();queueConfirm("succeeded");
        var dispatched=new CountDownLatch(1);var release=new CountDownLatch(1);
        inspectSdk(request->{assertClaimBeforePost(request);if(confirmPost(request)){dispatched.countDown();await(release);}});
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->confirmation.confirm(scope,selected.attemptId(),selected.capability(),TOKEN));
            assertThat(dispatched.await(10,TimeUnit.SECONDS)).isTrue();
            var stop=pool.submit(()->{clocks.stop(owner,StopReason.AUTHORITY_REVOKED,Instant.now());return true;});
            assertThat(stop.get(10,TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThatThrownBy(()->first.get(15,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(RuntimeException.class);
            assertThat(stopped(owner)).isEqualTo("AUTHORITY_REVOKED");
            assertThat(jdbc.queryForObject("select state from subscription_confirmation_attempts",String.class)).isEqualTo("DISPATCHING");
            assertThat(jdbc.queryForObject("select consumed_at from subscription_action_capabilities",java.sql.Timestamp.class)).isNotNull();
            int calls=http.calls.size();
            assertThatThrownBy(()->confirmation.confirm(scope,selected.attemptId(),selected.capability(),TOKEN)).isInstanceOf(RuntimeException.class);
            assertThat(http.calls).hasSize(calls);assertThat(confirmPosts()).isEqualTo(1);assertNoSuccess();
        } finally {release.countDown();shutdown(pool);}
    }

    @Test void guardedConfirmationPhaseAndStopFinishWithoutNestedProofTransactionWait() throws Exception {
        beginConfirmation();issueAndSelect();int calls=http.calls.size();
        var pool=Executors.newFixedThreadPool(2);siteGate.arm();
        try {
            var read=pool.submit(()->{
                Thread.currentThread().setName("owned-locked-phase");
                return confirmation.current(scope);
            });
            assertThat(siteGate.locked.await(10,TimeUnit.SECONDS)).isTrue();
            // Stop owns only Account. It must finish while the phase is paused after real Site lock.
            var stop=pool.submit(()->{clocks.stop(owner,StopReason.AUTHORITY_REVOKED,Instant.now());return true;});
            assertThat(stop.get(10,TimeUnit.SECONDS)).isTrue();
            siteGate.release();
            assertThatThrownBy(()->read.get(15,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(RuntimeException.class);
            assertThat(http.calls).hasSize(calls);assertThat(confirmPosts()).isZero();assertNoSuccess();
        } finally {siteGate.release();shutdown(pool);}
    }

    @Test void realCashFactPublicationAndConfirmationPhaseShareOperationFirstLockOrder() throws Exception {
        beginConfirmation();issueAndSelect();confirm("succeeded");
        var fresh=fence();var paid=paidObservation();
        var pool=Executors.newFixedThreadPool(2);siteGate.arm();jdbcGate.arm();
        try {
            var publication=pool.submit(()->{
                Thread.currentThread().setName("owned-locked-phase");apply(paid,fresh);return true;
            });
            assertThat(siteGate.locked.await(10,TimeUnit.SECONDS)).isTrue();
            var confirmationRead=pool.submit(()->{
                Thread.currentThread().setName("owned-confirmation-contender");return confirmation.current(scope);
            });
            // This is the actual confirmation phase's first operation SELECT FOR UPDATE.
            assertThat(jdbcGate.operationAttempt.await(10,TimeUnit.SECONDS)).isTrue();
            siteGate.release();
            assertThat(publication.get(15,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->confirmationRead.get(15,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(RuntimeException.class);
            assertPaid();assertThat(confirmPosts()).isEqualTo(1);
        } finally {siteGate.release();jdbcGate.armed=false;shutdown(pool);}
    }

    private static void await(CountDownLatch gate) {
        try {if(!gate.await(10,TimeUnit.SECONDS))throw new AssertionError("Hosted contention gate timed out");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }
    private static void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).isTrue();
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Gates {
        @Bean @Primary SiteGate confirmationSiteGate(SitePersistenceAdapter delegate,JdbcTemplate jdbc){return new SiteGate(delegate,jdbc);}
        @Bean @Primary ConfirmationJdbc confirmationGateJdbc(DataSource data){return new ConfirmationJdbc(data);}
    }
    static final class SiteGate implements LockSitePort {
        private final SitePersistenceAdapter delegate;private final JdbcTemplate jdbc;
        volatile boolean armed;
        volatile CountDownLatch locked=new CountDownLatch(1),proceed=new CountDownLatch(0);
        SiteGate(SitePersistenceAdapter delegate,JdbcTemplate jdbc){this.delegate=delegate;this.jdbc=jdbc;}
        void arm(){locked=new CountDownLatch(1);proceed=new CountDownLatch(1);armed=true;}
        void release(){armed=false;proceed.countDown();}
        public Optional<Site> findByKeyForUpdate(SiteKey key){return delegate.findByKeyForUpdate(key);}
        public Optional<Site> findByIdForUpdate(SiteId id){
            var result=delegate.findByIdForUpdate(id);
            if(armed&&Thread.currentThread().getName().equals("owned-locked-phase")){
                jdbc.execute("set local lock_timeout='8s'");locked.countDown();await(proceed);
            }
            return result;
        }
    }
    static final class ConfirmationJdbc extends JdbcTemplate {
        volatile boolean armed;
        volatile CountDownLatch operationAttempt=new CountDownLatch(1);
        ConfirmationJdbc(DataSource data){super(data);}
        void arm(){operationAttempt=new CountDownLatch(1);armed=true;}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
            if(armed&&Thread.currentThread().getName().equals("owned-confirmation-contender")
                &&sql.equals("select * from subscription_operations where id=? for update")){
                super.execute("set local lock_timeout='8s'");operationAttempt.countDown();
            }
            return super.query(sql,mapper,args);
        }
    }
}
