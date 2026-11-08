package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.acceptance.runtime.OwnedBillingFailedRenewalRecovery;
import dev.genericpayments.acceptance.runtime.OwnedBillingFailedRenewalRecovery.State;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedFailedRenewalRecoveryConfirmAdapterTest.Fixture;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnedRecoveryPreparationMappingTest.GRAPH_PATHS;
import static org.assertj.core.api.Assertions.*;

/** Real original service/receipt harness and official SDK memory transport; no financial authority is replaced. */
class OwnedBillingFailedRenewalRecoveryTest {
    private static OwnedBillingFailedRenewalRecovery recovery(Fixture f) {
        var observer=new StripeBillingOwnedFailedRenewalMethodObserver(f.g.local.service(),f.g.sdk.credentials,
            f.g.sdk.clients,f.g.sdk.digest,f.g.local.clock());
        return new OwnedBillingFailedRenewalRecovery(f.g.local.reservation(),f.g.local.completion(),
            f.g.local.recovery(),f.g.local.command(),f.adapter(),observer);
    }

    @Test void constructionAndOutOfOrderObserveCannotAuthenticateReadClaimOrInventReceipts() {
        var f=new Fixture();var before=f.g.local.state();var receipts=f.confirmation.receiptState();
        var trace=new ArrayList<>(f.g.local.trace());var recovery=recovery(f);
        assertThat(recovery.state()).isEqualTo(State.NEW);assertThat(recovery.confirmation()).isEmpty();assertThat(recovery.publication()).isEmpty();
        assertThat(recovery.toString()).isEqualTo("OwnedBillingFailedRenewalRecovery[redacted]");
        refused(recovery::observe);
        assertThat(recovery.state()).isEqualTo(State.NEW);assertThat(f.clientCreations).hasValue(0);
        assertThat(f.g.sdk.http.calls).isEmpty();assertThat(f.confirmation.receiptState()).isEqualTo(receipts);
        assertThat(f.g.local.state()).isEqualTo(before);assertThat(f.g.local.trace()).isEqualTo(trace);
        f.queue();var ack=recovery.confirm();assertThat(recovery.confirmation()).containsSame(ack);
        assertThat(recovery.state()).isEqualTo(State.CONFIRMED);
    }

    @Test void actualSeventeenGetsAndOneOriginalPostPublishOnlyTheExactCommittedAck() {
        var f=new Fixture();var recovery=recovery(f);f.queue();var before=f.g.local.state();
        var ack=recovery.confirm();
        assertThat(recovery.state()).isEqualTo(State.CONFIRMED);assertThat(recovery.confirmation()).containsSame(ack);
        assertThat(recovery.publication()).isEmpty();assertThat(f.confirmation.acknowledgments()).containsExactly(ack);
        assertThat(f.confirmation.acknowledgments().getFirst()).isSameAs(ack);
        assertThat(ack.operation()).isEqualTo(f.confirmation.row());assertThat(ack.operation().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(ack.operation().providerReference()).isEqualTo("pi_renewal");assertThat(ack.reply().method()).isEqualTo("pm_owned");
        var paths=new ArrayList<String>();paths.add("/v1/test_helpers/test_clocks/clock_owned");paths.addAll(GRAPH_PATHS);
        paths.add("/v1/test_helpers/test_clocks/clock_owned");paths.add("/v1/payment_intents/pi_renewal/confirm");
        assertThat(f.g.sdk.http.calls.stream().map(r->r.url().getPath()).toList()).isEqualTo(paths);
        assertThat(f.gets()).hasSize(17).allSatisfy(r->{assertThat(r.options().getIdempotencyKey()).isNull();f.options(r);});
        assertThat(f.posts()).singleElement().satisfies(post->{
            assertThat(post.params()).containsExactlyInAnyOrderEntriesOf(Map.of("payment_method","pm_owned","off_session",true,"error_on_requires_action",true));
            assertThat(post.options().getIdempotencyKey()).isEqualTo(f.g.original.recoveryOperation().providerOperationKey()+":CONFIRM");f.options(post);
        });
        assertThat(f.g.sdk.http.replies).isEmpty();assertThat(f.g.local.state()).isEqualTo(before);
        assertThat(f.g.local.trace()).doesNotContain("stop","facts","publication","binding-write");
        var receipts=f.confirmation.receiptState();var trace=new ArrayList<>(f.g.local.trace());
        refused(recovery::confirm);assertThat(recovery.state()).isEqualTo(State.CONFIRMED);
        assertThat(recovery.confirmation()).containsSame(ack);assertThat(recovery.publication()).isEmpty();
        assertThat(f.g.sdk.http.calls).hasSize(18);assertThat(f.confirmation.receiptState()).isEqualTo(receipts);
        assertThat(f.g.local.state()).isEqualTo(before);assertThat(f.g.local.trace()).isEqualTo(trace);
    }

    @Test void concurrentConfirmAndObserveRefusalsCannotPoisonTheFirstConfirmationOwner() throws Exception {
        var f=new Fixture();var recovery=recovery(f);f.queue();var before=f.g.local.state();
        var entered=new CountDownLatch(1);var rejected=new CountDownLatch(1);var once=new AtomicBoolean();
        var loser=new FutureTask<Void>(()->{
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(recovery.state()).isEqualTo(State.CONFIRMING);
                refused(recovery::confirm);refused(recovery::observe);
                assertThat(recovery.state()).isEqualTo(State.CONFIRMING);
                assertThat(recovery.confirmation()).isEmpty();assertThat(recovery.publication()).isEmpty();return null;
            } finally {rejected.countDown();}
        });
        var caller=Thread.ofVirtual().start(loser);
        f.g.local.afterAuthentication(()->{if(once.compareAndSet(false,true)){
            entered.countDown();await(rejected);
        }});
        try {
            var ack=recovery.confirm();loser.get(1,TimeUnit.SECONDS);
            assertThat(recovery.state()).isEqualTo(State.CONFIRMED);assertThat(recovery.confirmation()).containsSame(ack);
            assertThat(f.gets()).hasSize(17);assertThat(f.posts()).hasSize(1);
            assertThat(f.confirmation.acknowledgments()).containsExactly(ack);assertThat(f.g.local.state()).isEqualTo(before);
            assertThat(f.g.local.trace()).doesNotContain("stop","facts","publication");
        } finally {caller.interrupt();loser.cancel(true);}
    }

    @Test void failedActualConfirmationConsumesTheInstanceWithoutInventingAnAckOrRetrying() {
        var f=new Fixture();var recovery=recovery(f);f.g.queueClocks();var bad=f.body();bad.addProperty("amount_received",999);f.g.sdk.http.reply(bad.toString());
        refused(recovery::confirm);assertThat(recovery.state()).isEqualTo(State.FAILED);
        assertThat(recovery.confirmation()).isEmpty();assertThat(recovery.publication()).isEmpty();f.onlyOwnUnknown();
        var receipts=f.confirmation.receiptState();var before=f.g.local.state();int calls=f.g.sdk.http.calls.size();
        refused(recovery::confirm);refused(recovery::observe);
        assertThat(recovery.state()).isEqualTo(State.FAILED);assertThat(f.g.sdk.http.calls).hasSize(calls);
        assertThat(f.confirmation.receiptState()).isEqualTo(receipts);assertThat(f.g.local.state()).isEqualTo(before);
    }

    @Test void actualAuthenticationErrorPropagatesUnchangedAfterFailedStatePublication() {
        var f=new Fixture();var recovery=recovery(f);var error=new AssertionError("PRIVATE_PROVIDER_DETAIL");
        f.g.local.afterAuthentication(()->{throw error;});
        assertThat(catchThrowable(recovery::confirm)).isSameAs(error);
        assertThat(recovery.state()).isEqualTo(State.FAILED);assertThat(recovery.confirmation()).isEmpty();assertThat(recovery.publication()).isEmpty();
        assertThat(f.g.sdk.http.calls).isEmpty();assertThat(f.confirmation.row()).isNull();assertThat(f.g.local.trace()).doesNotContain("stop","facts","publication");
        refused(recovery::confirm);refused(recovery::observe);
    }

    @Test void timelyCommittedAckIsReturnedEvenWhenItsCommitReturnConsumesTheOriginalBudget() {
        var f=new Fixture();var recovery=recovery(f);f.queue();
        f.confirmation.afterCommit(()->{if(f.confirmation.row()!=null&&f.confirmation.row().status()==ProviderOperation.Status.ACKNOWLEDGED){
            f.nanos.addAndGet(TimeUnit.SECONDS.toNanos(31));f.g.local.at(f.entry.plusSeconds(31));
        }});
        var ack=recovery.confirm();assertThat(recovery.state()).isEqualTo(State.CONFIRMED);
        assertThat(recovery.confirmation()).containsSame(ack);assertThat(f.confirmation.acknowledgments()).containsExactly(ack);
        assertThat(f.posts()).hasSize(1);assertThat(recovery.publication()).isEmpty();assertThat(f.g.local.trace()).doesNotContain("stop","facts","publication");
    }

    @Test void privateRuntimeFailureIsCauseFreeAndCannotEscapeThroughStateOrDiagnosticText() {
        var f=new Fixture();var recovery=recovery(f);var failure=new IllegalStateException("PRIVATE_PROVIDER_DETAIL sk_test_private",new Exception("private cause"));
        failure.addSuppressed(new IllegalStateException("private suppressed"));f.g.local.afterAuthentication(()->{throw failure;});
        refused(recovery::confirm);assertThat(recovery.state()).isEqualTo(State.FAILED);
        assertThat(recovery.confirmation()).isEmpty();assertThat(recovery.publication()).isEmpty();assertThat(f.g.sdk.http.calls).isEmpty();
        assertThat(f.confirmation.row()).isNull();assertThat(recovery.toString()).isEqualTo("OwnedBillingFailedRenewalRecovery[redacted]");
    }

    @Test void realObserverFailureAfterAckKeepsDurableConfirmationAndCannotRetryOrPublish() {
        var f=new Fixture();var recovery=recovery(f);f.queue();var ack=recovery.confirm();
        var receipt=f.confirmation.receiptState();var financial=f.financial();
        // The actual recovered observer claims its own read, then rejects a foreign opening clock.
        f.g.sdk.http.reply("{\"id\":\"clock_foreign\",\"object\":\"test_helpers.test_clock\",\"livemode\":false,\"status\":\"ready\"}");
        refused(recovery::observe);assertThat(recovery.state()).isEqualTo(State.FAILED);
        assertThat(recovery.confirmation()).containsSame(ack);assertThat(recovery.publication()).isEmpty();
        assertThat(f.confirmation.receiptState()).isEqualTo(receipt);assertThat(f.financial()).isEqualTo(financial);
        assertThat(f.g.local.trace().stream().filter("stop"::equals)).hasSize(1);
        assertThat(f.g.local.trace()).doesNotContain("facts","publication");assertThat(f.posts()).hasSize(1);
        int calls=f.g.sdk.http.calls.size();refused(recovery::observe);refused(recovery::confirm);
        assertThat(f.g.sdk.http.calls).hasSize(calls);assertThat(f.confirmation.receiptState()).isEqualTo(receipt);
    }

    @Test void actualObserverSdkErrorPropagatesUnchangedAndKeepsThePreviouslyCommittedAck() {
        var f=new Fixture();var recovery=recovery(f);f.queue();var ack=recovery.confirm();
        var receipt=f.confirmation.receiptState();var error=new AssertionError("PRIVATE_PROVIDER_DETAIL");
        f.g.sdk.http.reply(f.g.clockBody());f.g.sdk.http.afterResponse=()->{throw error;};
        assertThat(catchThrowable(recovery::observe)).isSameAs(error);
        assertThat(recovery.state()).isEqualTo(State.FAILED);assertThat(recovery.confirmation()).containsSame(ack);
        assertThat(recovery.publication()).isEmpty();assertThat(f.confirmation.receiptState()).isEqualTo(receipt);
        assertThat(f.g.local.trace()).doesNotContain("stop","facts","publication");
        int calls=f.g.sdk.http.calls.size();refused(recovery::observe);assertThat(f.g.sdk.http.calls).hasSize(calls);
    }

    private static void await(CountDownLatch latch) {
        try {assertThat(latch.await(1,TimeUnit.SECONDS)).isTrue();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }
    private static void refused(Runnable call) {
        assertThatThrownBy(call::run).isExactlyInstanceOf(PaymentApplicationException.class)
            .hasMessage("Owned recovery scenario unavailable").hasNoCause().hasNoSuppressedExceptions()
            .satisfies(error->{var refusal=(PaymentApplicationException)error;
                assertThat(refusal.code()).isEqualTo("OWNED_RECOVERY_SCENARIO_UNAVAILABLE");assertThat(refusal.status()).isEqualTo(409);
                assertThat(error.toString()).doesNotContain("PRIVATE_PROVIDER_DETAIL","sk_test","pm_owned","pi_renewal","private");
            });
    }
}
