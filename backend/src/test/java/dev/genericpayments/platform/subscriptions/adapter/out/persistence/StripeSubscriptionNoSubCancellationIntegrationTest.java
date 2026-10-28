package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.OwnedSetupReceipt;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.PendingSetup;
import dev.genericpayments.platform.subscriptions.application.port.in.CancelSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionEventStore;
import dev.genericpayments.platform.subscriptions.application.service.CreateSubscriptionFingerprint;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionCancellationTransaction;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionFactFixture;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionAccessPolicy;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionEvent;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import dev.genericpayments.platform.subscriptions.domain.TrialEligibility;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Hosted PostgreSQL only. Actual original CREATE/Setup receipt and local abandonment; no provider HTTP. */
class StripeSubscriptionNoSubCancellationIntegrationTest extends SubscriptionFactFixture {
    @Autowired ProviderOperationStore steps;
    @Autowired StripeBillingBindingPort bindings;
    @Autowired SubscriptionBillingProofStore billingProofs;
    @Autowired SubscriptionCancellationTransaction cancellations;
    @Autowired CancelSubscriptionUseCase cancellationUseCase;
    @Autowired SubscriptionEventStore events;
    @Autowired CallbackConfigurationPort callbackConfiguration;

    private static final String OWNER = "task9-never-dispatched-create";
    private Subscription original;
    private SubscriptionOperation createOperation;
    private ProviderOperation setupStep;
    private SubscriptionBillingProofStore.Setup setupProof;
    private TrialEligibility eligibility;

    @BeforeEach void requiredTrialFixture() {
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_NoSubCancellation',credentials_ref='stripe-sandbox-default' where id=?", account.id().value());
        account = accounts.findById(account.id()).orElseThrow();
        callbackConfiguration.configure(site.value(), "https://example.com/callback", true);
        price = requiredTrialPrice();
        var customerKey = "customer:" + account.id().value() + ":" + customer.id().value();
        var customerStep = acknowledge(steps.reserve(account, ProviderOperation.Kind.CUSTOMER_CREATE, customer.id().value(), customerKey, "task9-no-sub-customer"), nativeReference("cus_"));
        bindings.bindCustomer(account.id(), site, customer.id(), customerStep.providerReference(), customerStep.id());
        var admitted = commands.reserveCreate(command("no-sub-original-create"));
        assertThat(CreateSubscriptionFingerprint.startsTrial(admitted.operation().fingerprint())).isTrue();
        var setup = steps.reserve(account, ProviderOperation.Kind.SETUP_INTENT_CREATE, admitted.operation().id(), admitted.operation().providerOperationKey() + ":SETUP", "task9-no-sub-original-setup");
        setupStep = acknowledge(setup, nativeReference("seti_"));
        setupProof = billingProofs.recordSetup(admitted.subscription(), admitted.operation(), setupStep.id(), setupStep.providerReference());
        commands.recordSetupReceipt(admitted.operation().id(), new PendingSetup(new OwnedSetupReceipt(admitted.operation().id(), setupStep.id(), setupStep.providerReference()), null));
        original = sub(admitted.subscription().id());
        createOperation = op(admitted.operation().id());
        eligibility = trials.find(site, customer.id(), original.productId()).orElseThrow();
        assertThat(original.billingStatus()).isEqualTo(Subscription.Status.INCOMPLETE);
        assertThat(original.providerReference()).isNull();
        assertThat(createOperation.providerReference()).isNull();
        assertThat(eligibility.reservedByOperation()).isEqualTo(createOperation.id());
        assertThat(eligibility.consumedAt()).isNull();
    }

    @Test void noSubPublicCancellationAtomicallyClosesOriginalEligibilityJournalAndCallback() {
        var request = cancelCommand("abandon-absent-create-step");
        assertThat(steps.find(account.id(), createOperation.providerOperationKey())).isEmpty();
        var result = cancellationUseCase.cancel(request);
        assertAbandoned(result);
        assertThat(steps.find(account.id(), createOperation.providerOperationKey())).isEmpty();
        var committed = durableState();
        assertThat(cancellationUseCase.cancel(request)).isEqualTo(result);
        assertThat(durableState()).isEqualTo(committed);
    }

    @Test void neverDispatchedReservedCreateStepRemainsTruthfullyUnacknowledgedAndCannotStartAfterAbandonment() {
        var reserved = reserveCreateStep();
        var result = cancellationUseCase.cancel(cancelCommand("abandon-reserved-create-step"));
        assertAbandoned(result);
        assertThat(steps.findById(reserved.id())).contains(reserved);
        providerConflict(() -> steps.claim(reserved.id(), OWNER, Instant.now(), Duration.ofSeconds(60)));
        providerConflict(this::reserveCreateStep);
        assertThat(steps.findById(reserved.id())).contains(reserved);
        assertNoRemoteCancellationOrCash();
    }

    @Test void everDispatchedUnknownCreateCannotReleaseEligibilityOrInventLocalCancellationProof() {
        var claimed = claim(reserveCreateStep());
        var unknown = steps.unknown(claimed.id(), OWNER, claimed.version());
        var before = durableState();
        stateConflict(() -> cancellationUseCase.cancel(cancelCommand("abandon-unknown-create")));
        assertThat(durableState()).isEqualTo(before);
        assertThat(steps.findById(unknown.id())).contains(unknown);
        assertThat(trials.find(site, customer.id(), original.productId())).contains(eligibility);
        assertThat(trials.isEligible(site, customer.id(), original.productId())).isFalse();
        assertNoRemoteCancellationOrCash();
    }

    @Test void liveDispatchedCreateCannotBeAbandonedEvenBeforeItsRemoteReferenceArrives() {
        var claimed = claim(reserveCreateStep());
        var before = durableState();
        stateConflict(() -> cancellationUseCase.cancel(cancelCommand("abandon-live-dispatch")));
        assertThat(durableState()).isEqualTo(before);
        assertThat(steps.findById(claimed.id())).contains(claimed);
        assertThat(sub(original.id())).isEqualTo(original);
        assertThat(trials.find(site, customer.id(), original.productId())).contains(eligibility);
    }

    @Test void journalFailureRollsBackOriginalClosureCancelClaimEligibilityAndCallback() {
        var reserved = reserveCreateStep();
        var before = durableState();
        rejectInsert("subscription_events", "new.event_type='CANCELLED'", () -> cancellationUseCase.cancel(cancelCommand("abandon-journal-rollback")));
        assertThat(durableState()).isEqualTo(before);
        assertThat(steps.findById(reserved.id())).contains(reserved);
        assertThat(trials.find(site, customer.id(), original.productId())).contains(eligibility);
        assertAbandoned(cancellationUseCase.cancel(cancelCommand("abandon-journal-rollback")));
    }

    @Test void callbackFailureRollsBackJournalOriginalClosureAndEligibilityTogether() {
        var before = durableState();
        rejectInsert("outbox_events", "new.source_kind='SUBSCRIPTION'", () -> cancellationUseCase.cancel(cancelCommand("abandon-callback-rollback")));
        assertThat(durableState()).isEqualTo(before);
        assertThat(trials.find(site, customer.id(), original.productId())).contains(eligibility);
        assertThat(op(createOperation.id())).isEqualTo(createOperation);
        assertAbandoned(cancellationUseCase.cancel(cancelCommand("abandon-callback-rollback")));
    }

    @Test void firstDispatchWinningObservedAccountQueueKeepsOriginalTrialReserved() throws Exception {
        var reserved = reserveCreateStep();
        var request = cancelCommand("first-dispatch-wins");
        var candidate = cancellations.candidate(request.context(), original.id());
        try (var blocker = jdbc.getDataSource().getConnection(); var pool = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            lockAccount(blocker);
            int blockerPid = backendPid(blocker);
            var dispatch = pool.submit(() -> claim(reserved));
            Future<String> abandon = null;
            try {
                int dispatchPid = awaitAccountWait(blockerPid, "%for share%");
                abandon = pool.submit(() -> failureCode(() -> cancellations.abandon(request.context(), original.id(), request.mode(), candidate)));
                int abandonPid = awaitAccountWait(blockerPid, "%for%update%");
                assertThat(abandonPid).isNotEqualTo(dispatchPid);
            } finally { blocker.rollback(); }
            var claimed = dispatch.get(15, TimeUnit.SECONDS);
            assertThat(abandon).isNotNull();
            assertThat(abandon.get(15, TimeUnit.SECONDS)).isEqualTo("SUBSCRIPTION_STATE_CONFLICT");
            assertThat(steps.findById(reserved.id())).contains(claimed);
        }
        assertThat(sub(original.id())).isEqualTo(original);
        assertThat(op(createOperation.id())).isEqualTo(createOperation);
        assertThat(trials.find(site, customer.id(), original.productId())).contains(eligibility);
        assertThat(jdbc.queryForObject("select count(*) from subscription_operations where kind='CANCEL'", Integer.class)).isZero();
        assertThat(count("subscription_events")).isZero();
        assertThat(count("outbox_events")).isZero();
        assertNoRemoteCancellationOrCash();
    }

    @Test void abandonmentWinningObservedAccountQueueClosesBeforeFirstClaimCanDispatch() throws Exception {
        var reserved = reserveCreateStep();
        var request = cancelCommand("abandonment-wins");
        var candidate = cancellations.candidate(request.context(), original.id());
        try (var blocker = jdbc.getDataSource().getConnection(); var pool = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            lockAccount(blocker);
            int blockerPid = backendPid(blocker);
            var abandon = pool.submit(() -> cancellations.abandon(request.context(), original.id(), request.mode(), candidate));
            Future<String> dispatch = null;
            try {
                int abandonPid = awaitAccountWait(blockerPid, "%for%update%");
                dispatch = pool.submit(() -> failureCode(() -> claim(reserved)));
                int dispatchPid = awaitAccountWait(blockerPid, "%for share%");
                assertThat(dispatchPid).isNotEqualTo(abandonPid);
            } finally { blocker.rollback(); }
            assertAbandoned(abandon.get(15, TimeUnit.SECONDS));
            assertThat(dispatch).isNotNull();
            assertThat(dispatch.get(15, TimeUnit.SECONDS)).isEqualTo("PROVIDER_OPERATION_CONFLICT");
        }
        assertThat(steps.findById(reserved.id())).contains(reserved);
        assertNoRemoteCancellationOrCash();
    }

    @Test void positivelyUnusedTrialCanBeReservedByANewOriginalCreateWithoutLosingOldSetupReceipt() {
        var closed = cancellationUseCase.cancel(cancelCommand("abandon-before-new-create"));
        assertAbandoned(closed);
        var next = commands.reserveCreate(command("fresh-create-after-abandonment"));
        assertThat(next.owner()).isTrue();
        assertThat(next.subscription().id()).isNotEqualTo(original.id());
        assertThat(next.operation().id()).isNotEqualTo(createOperation.id());
        assertThat(CreateSubscriptionFingerprint.startsTrial(next.operation().fingerprint())).isTrue();
        assertThat(trials.find(site, customer.id(), original.productId())).hasValueSatisfying(e -> {
            assertThat(e.reservedByOperation()).isEqualTo(next.operation().id());
            assertThat(e.consumedAt()).isNull();
        });
        assertThat(billingProofs.setup(account.id(), createOperation.id())).contains(setupProof);
        assertThat(steps.findById(setupStep.id())).contains(setupStep);
        assertThat(sub(original.id()).billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
        assertNoRemoteCancellationOrCash();
    }

    private PriceId requiredTrialPrice() {
        var product = UUID.randomUUID(); var id = new PriceId(UUID.randomUUID());
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,?,'Required trial','ACTIVE',?,?)", product, site.value(), "p-" + product, Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,?,1,'SUBSCRIPTION',2000,'EUR','MONTH',3,'REQUIRED_BEFORE_TRIAL','NONE','PUBLISHED',?,?,?)", id.value(), site.value(), product, "p-" + id.value(), Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME));
        return id;
    }
    private CancelSubscriptionUseCase.Command cancelCommand(String key) {
        var context = new SubscriptionCommandContext(actor, site, customer.id(), "https://facts.test", key, "task9-no-sub-cancellation");
        return new CancelSubscriptionUseCase.Command(context, original.id(), CancelSubscriptionUseCase.Mode.PERIOD_END, null);
    }
    private ProviderOperation reserveCreateStep() {
        return steps.reserve(account, ProviderOperation.Kind.SUBSCRIPTION_CREATE, createOperation.id(), createOperation.providerOperationKey(), "task9-never-dispatched-create-body");
    }
    private ProviderOperation claim(ProviderOperation step) {
        return steps.claim(step.id(), OWNER, Instant.now(), Duration.ofSeconds(60)).orElseThrow();
    }
    private ProviderOperation acknowledge(ProviderOperation step, String reference) {
        var claimed = claim(step);
        return steps.acknowledge(claimed.id(), OWNER, claimed.version(), reference);
    }
    private void assertAbandoned(SubscriptionOperationResult result) {
        assertThat(result.subscriptionId()).isEqualTo(original.id());
        assertThat(result.status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(result.operationId()).isNotEqualTo(createOperation.id());
        var cancel = op(result.operationId());
        assertThat(cancel.kind()).isEqualTo(SubscriptionOperation.Kind.CANCEL);
        assertThat(cancel.providerReference()).isNull();
        assertThat(cancel.fingerprint()).startsWith("v1.cancel.abandon." + createOperation.id() + ".");
        var closedCreate = op(createOperation.id());
        assertThat(closedCreate.status()).isEqualTo(SubscriptionOperation.Status.FAILED);
        assertThat(closedCreate.safeFailureCode()).isEqualTo("ABANDONED_BEFORE_DISPATCH");
        assertThat(closedCreate.providerReference()).isNull();
        var closed = sub(original.id());
        assertThat(closed.billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
        assertThat(closed.terminalReason()).isEqualTo("CUSTOMER_ABANDONED");
        assertThat(closed.providerReference()).isNull();
        assertThat(closed.trial()).isNull();
        assertThat(closed.currentPeriod()).isNull();
        assertThat(closed.entitledPrice()).isNull();
        assertThat(closed.paidThrough()).isNull();
        assertThat(new SubscriptionAccessPolicy().evaluate(closed, INITIAL_TIME).allowed()).isFalse();
        assertThat(trials.find(site, customer.id(), original.productId())).hasValueSatisfying(e -> {
            assertThat(e.reservedByOperation()).isNull(); assertThat(e.consumedAt()).isNull();
        });
        assertThat(trials.isEligible(site, customer.id(), original.productId())).isTrue();
        var journal = events.list(site, original.id(), 10, 0);
        assertThat(journal).hasSize(1);
        var event = journal.getFirst();
        assertThat(event.type()).isEqualTo(SubscriptionEvent.Type.CANCELLED);
        assertThat(event.source()).isEqualTo(SubscriptionEvent.Source.API);
        assertThat(event.operationId()).isEqualTo(result.operationId());
        assertThat(event.revision()).isEqualTo(closed.revision());
        assertThat(event.providerReference()).isNull();
        var callback = jdbc.queryForMap("select site_id,source_kind,subscription_id,subscription_event_id,payment_account_id,payload->>'state' as state,payload->>'access_allowed' as access,payload->>'event_id' as event_id from outbox_events where subscription_id=?", original.id());
        assertThat(callback.get("site_id")).isEqualTo(site.value());
        assertThat(callback.get("source_kind")).isEqualTo("SUBSCRIPTION");
        assertThat(callback.get("subscription_id")).isEqualTo(original.id());
        assertThat(callback.get("subscription_event_id")).isEqualTo(event.id());
        assertThat(callback.get("payment_account_id")).isEqualTo(account.id().value());
        assertThat(callback.get("state")).isEqualTo("CANCELLED");
        assertThat(callback.get("access")).isEqualTo("false");
        assertThat(callback.get("event_id")).isEqualTo(event.id().toString());
        assertThat(billingProofs.setup(account.id(), createOperation.id())).contains(setupProof);
        assertThat(steps.findById(setupStep.id())).contains(setupStep);
        assertNoRemoteCancellationOrCash();
    }
    private Map<String, Object> durableState() {
        var state = new LinkedHashMap<String, Object>();
        state.put("subscription", sub(original.id())); state.put("original", op(createOperation.id()));
        state.put("eligibility", trials.find(site, customer.id(), original.productId()));
        state.put("operations", jdbc.queryForList("select id,kind,status,provider_reference,version from subscription_operations order by id"));
        state.put("events", events.list(site, original.id(), 10, 0));
        state.put("callbacks", jdbc.queryForList("select id,subscription_event_id,payment_account_id,payload from outbox_events order by id"));
        return state;
    }
    private void assertNoRemoteCancellationOrCash() {
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind in ('SUBSCRIPTION_CANCEL','SUBSCRIPTION_UPDATE','INVOICE_PAY')", Integer.class)).isZero();
        assertThat(count("stripe_subscription_cancellation_proofs")).isZero();
        assertThat(count("stripe_subscription_trial_proofs")).isZero();
        assertThat(count("payments")).isZero(); assertThat(count("refunds")).isZero();
        assertThat(count("subscription_billing_records")).isZero();
    }
    private void rejectInsert(String table, String condition, ThrowingCallable action) {
        jdbc.execute("create function task9_reject_abandonment() returns trigger language plpgsql as $$ begin if " + condition + " then raise exception 'injected abandonment rollback' using errcode='23514'; end if; return new; end; $$");
        jdbc.execute("create trigger task9_reject_abandonment before insert on " + table + " for each row execute function task9_reject_abandonment()");
        try { stateConflict(action); }
        finally {
            jdbc.execute("drop trigger task9_reject_abandonment on " + table);
            jdbc.execute("drop function task9_reject_abandonment()");
        }
    }
    private void lockAccount(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("select id from payment_accounts where id=? for update")) {
            query.setObject(1, account.id().value());
            try (var rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
    }
    private static int backendPid(Connection connection) throws SQLException {
        try (var query = connection.createStatement(); var rows = query.executeQuery("select pg_backend_pid()")) {
            assertThat(rows.next()).isTrue(); return rows.getInt(1);
        }
    }
    private int awaitAccountWait(int blocker, String lockKind) throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        do {
            // Hibernate may render PESSIMISTIC_WRITE as FOR NO KEY UPDATE; both update forms count.
            var waiting = jdbc.queryForList("select pid from pg_stat_activity where wait_event_type='Lock' and ?=any(pg_blocking_pids(pid)) and lower(query) like '%payment_accounts%' and lower(query) like ?", Integer.class, blocker, lockKind);
            if (!waiting.isEmpty()) return waiting.getFirst();
            Thread.sleep(10); // PostgreSQL's actual wait graph, not a sleep, determines queue readiness.
        } while (System.nanoTime() < until);
        throw new AssertionError("Expected actual Account wait using " + lockKind);
    }
    private static String failureCode(ThrowingCallable action) {
        try { action.call(); throw new AssertionError("Expected refusal"); }
        catch (PaymentApplicationException failure) { return failure.code(); }
        catch (RuntimeException failure) { throw failure; }
        catch (Throwable failure) { throw new AssertionError("Unexpected non-domain failure", failure); }
    }
    private static String nativeReference(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", ""); }
    private static void stateConflict(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class, e -> assertThat(e.code()).isEqualTo("SUBSCRIPTION_STATE_CONFLICT"));
    }
    private static void providerConflict(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class, e -> assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
    }
}
