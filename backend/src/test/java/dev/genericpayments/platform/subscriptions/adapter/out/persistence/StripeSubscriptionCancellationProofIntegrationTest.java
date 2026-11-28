package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.customers.domain.Customer;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.CancelSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCancellationProofStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCancellationProofStore.Mode;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionCancellationTransaction;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionFactFixture;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** Hosted PostgreSQL only. Authored offline; no claim of provider or settlement verification. */
class StripeSubscriptionCancellationProofIntegrationTest extends SubscriptionFactFixture {
    @Autowired SubscriptionCancellationProofStore proofs;
    @Autowired ProviderOperationStore steps;
    @Autowired StripeBillingBindingPort bindings;
    @Autowired SubscriptionCancellationTransaction cancellations;

    private static final String OWNER = "task9-cancellation-proof-owner";
    private static final String TABLE = "stripe_subscription_cancellation_proofs";
    private Owner local;
    private Owner foreign;
    private Original original;
    private Original other;
    private Instant boundary;

    @BeforeEach void cancellationFixture() {
        // Real UTC dispatch/receipt security remains independent of the business fixture clock.
        boundary = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_CancellationProofs',credentials_ref='stripe-sandbox-default' where id=?", account.id().value());
        account = accounts.findById(account.id()).orElseThrow();
        var policy = policies.load(site);
        policies.save(new SubscriptionPolicy(site, policy.version(), true, 3, true, false, false, Set.of()), policy.version());
        local = new Owner(site, customer, account, actor, price, "https://facts.test");
        // Seed every policy before any lifecycle operation; later tests change policy only to test refusal.
        foreign = foreignOwner();
        original = paidOriginal(local);
        other = paidOriginal(foreign);
    }

    @Test void periodEndReceiptReloadsExactOriginalIdentityWithAtomicAcknowledgement() {
        var pending = cancel(Mode.PERIOD_END);
        proofs.authorize(pending.subscription(), pending.operation());
        var received = received(pending);
        var saved = record(pending, received);
        assertThat(saved.operation()).isEqualTo(pending.operation().id());
        assertThat(saved.subscription()).isEqualTo(original.subscription().id());
        assertThat(saved.account()).isEqualTo(account.id());
        assertThat(saved.step()).isEqualTo(pending.step().id());
        assertThat(saved.reference()).isEqualTo(original.subscription().providerReference());
        assertThat(saved.mode()).isEqualTo(Mode.PERIOD_END);
        assertThat(saved.boundary()).isEqualTo(boundary);
        assertThat(saved.priorOperation()).isNull();
        assertThat(saved.acknowledgedAt()).isEqualTo(received);
        assertThat(new SubscriptionCancellationProofPersistenceAdapter(jdbc, steps).receipt(account.id(), saved.operation())).contains(saved);
        assertThat(proofs.receipt(foreign.account().id(), saved.operation())).isEmpty();
        assertThat(proofs.activeSchedule(account.id(), saved.subscription(), boundary)).contains(saved);
        assertAcknowledged(pending);
        assertNoLifecycleOrCashChange(pending.subscription());
    }

    @Test void immediateReceiptUsesItsOwnCancelKindAndHasNoInventedBoundary() {
        var pending = cancel(Mode.IMMEDIATE);
        assertThat(pending.step().kind()).isEqualTo(ProviderOperation.Kind.SUBSCRIPTION_CANCEL);
        var saved = record(pending, received(pending));
        assertThat(saved.mode()).isEqualTo(Mode.IMMEDIATE);
        assertThat(saved.boundary()).isNull();
        assertThat(saved.priorOperation()).isNull();
        assertThat(proofs.activeSchedule(account.id(), saved.subscription(), boundary)).isEmpty();
        assertAcknowledged(pending);
        assertNoLifecycleOrCashChange(pending.subscription());
    }

    @Test void withdrawalConsumesOnlyItsExactOriginalScheduleAndKeepsBothReceipts() {
        var first = cancel(Mode.PERIOD_END);
        var scheduled = record(first, received(first));
        var withdrawal = withdraw(first);
        var saved = record(withdrawal, received(withdrawal));
        assertThat(saved.priorOperation()).isEqualTo(first.operation().id());
        assertThat(saved.boundary()).isEqualTo(boundary);
        assertThat(saved.reference()).isEqualTo(scheduled.reference());
        assertThat(saved.step()).isNotEqualTo(scheduled.step());
        assertThat(proofs.receipt(account.id(), scheduled.operation())).contains(scheduled);
        assertThat(proofs.receipt(account.id(), saved.operation())).contains(saved);
        assertThat(proofs.activeSchedule(account.id(), saved.subscription(), boundary)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='SUBSCRIPTION_UPDATE' and provider_reference=?", Integer.class, saved.reference())).isEqualTo(2);
        assertAcknowledged(withdrawal);
        assertNoLifecycleOrCashChange(withdrawal.subscription());
    }

    @Test void activeScheduleRequiresOneExactAccountSubscriptionAndCapturedBoundary() {
        var pending = cancel(Mode.PERIOD_END);
        var saved = record(pending, received(pending));
        assertThat(proofs.activeSchedule(foreign.account().id(), saved.subscription(), boundary)).isEmpty();
        assertThat(proofs.activeSchedule(account.id(), other.subscription().id(), boundary)).isEmpty();
        assertThat(proofs.activeSchedule(account.id(), saved.subscription(), boundary.plusSeconds(1))).isEmpty();
        finish(pending.operation());
        // Two otherwise valid originals without a projected cancellation are deliberately ambiguous.
        var duplicate = cancel(Mode.PERIOD_END);
        record(duplicate, received(duplicate));
        evidenceConflict(() -> proofs.activeSchedule(account.id(), saved.subscription(), boundary));
    }

    @Test void groupedSqlForeignKeysRejectEachOwnerComponentAndDifferentOriginalOperation() {
        var pending = cancel(Mode.PERIOD_END);
        var ack = acknowledge(pending.step(), pending.subscription().providerReference());
        var row = proofRow(pending, ack, received(pending));
        reject(row, "site_id", foreign.site().value(), "23503");
        reject(row, "customer_id", foreign.customer().id().value(), "23503");
        reject(row, "subscription_id", other.subscription().id(), "23503");
        reject(row, "payment_account_id", foreign.account().id().value(), "23503");
        reject(row, "operation_id", original.operation().id(), "23503");
        reject(row, "step_id", original.creationStep().id(), "23503");
        insert(row);
        assertThat(proofs.receipt(account.id(), pending.operation().id())).hasValueSatisfying(p -> assertThat(p.step()).isEqualTo(ack.id()));
    }

    @Test void sqlRequiresAcknowledgedOriginalKindAndActualNativeSubscriptionReference() {
        var pending = cancel(Mode.PERIOD_END);
        var row = proofRow(pending, pending.step(), received(pending));
        sqlState("23503", () -> insert(row)); // DISPATCHING with no native reference cannot support a receipt.
        acknowledge(pending.step(), pending.subscription().providerReference());
        reject(row, "subscription_reference", "in_WrongObject", "23514");
        reject(row, "subscription_reference", "sub_not_native", "23514");
        reject(row, "subscription_reference", other.subscription().providerReference(), "23503");
        reject(row, "step_kind", "SUBSCRIPTION_CANCEL", "23514");
        reject(row, "operation_kind", "RECOVER", "23514");
        insert(row);
        assertThat(count(TABLE)).isEqualTo(1);
    }

    @Test void sqlModeChecksRequireWholeSecondTimelyBoundaryAndFinitePriorGroup() {
        var pending = cancel(Mode.PERIOD_END);
        var ack = acknowledge(pending.step(), pending.subscription().providerReference());
        var at = received(pending);
        var row = proofRow(pending, ack, at);
        reject(row, "mode", "UNKNOWN", "23514");
        reject(row, "boundary", null, "23514");
        reject(row, "boundary", Timestamp.from(boundary.plusNanos(1000)), "23514");
        reject(row, "acknowledged_at", Timestamp.from(boundary), "23514");
        reject(row, "acknowledged_at", Timestamp.from(boundary.plusSeconds(1)), "23514");
        reject(row, "prior_operation", original.operation().id(), "23514");
        reject(row, "prior_mode", "IMMEDIATE", "23514");
        insert(row);
    }

    @Test void withdrawalSqlForeignKeyRequiresPriorPeriodEndSameOwnerAndSameBoundary() {
        var first = cancel(Mode.PERIOD_END);
        record(first, received(first));
        finish(first.operation());
        var immediate = cancel(Mode.IMMEDIATE);
        record(immediate, received(immediate));
        finish(immediate.operation());
        var pending = withdraw(first);
        var ack = acknowledge(pending.step(), pending.subscription().providerReference());
        var row = proofRow(pending, ack, received(pending));
        reject(row, "prior_operation", original.operation().id(), "23503");
        // This prior receipt has the same Account/Subscription; only its non-PERIOD_END group differs.
        reject(row, "prior_operation", immediate.operation().id(), "23503");
        reject(row, "boundary", Timestamp.from(boundary.plusSeconds(1)), "23503");
        reject(row, "prior_operation", null, "23514");
        var foreignPending = cancel(other, foreign, Mode.PERIOD_END);
        record(foreignPending, received(foreignPending));
        reject(row, "prior_operation", foreignPending.operation().id(), "23503");
        insert(row);
        assertThat(proofs.activeSchedule(account.id(), original.subscription().id(), boundary)).isEmpty();
        finish(pending.operation());
        var duplicate = withdraw(first);
        var duplicateAck = acknowledge(duplicate.step(), duplicate.subscription().providerReference());
        sqlState("23505", () -> insert(proofRow(duplicate, duplicateAck, received(duplicate))));
    }

    @Test void everySavedReceiptComponentIsImmutableIncludingItsOriginalAcknowledgementTime() {
        var pending = cancel(Mode.PERIOD_END);
        var saved = record(pending, received(pending));
        var changes = new LinkedHashMap<String, Object>();
        changes.put("operation_id", UUID.randomUUID()); changes.put("site_id", foreign.site().value());
        changes.put("customer_id", foreign.customer().id().value()); changes.put("subscription_id", other.subscription().id());
        changes.put("payment_account_id", foreign.account().id().value()); changes.put("operation_kind", "WITHDRAW_CANCEL");
        changes.put("step_id", original.creationStep().id()); changes.put("step_kind", "SUBSCRIPTION_CANCEL");
        changes.put("subscription_reference", "sub_Replaced"); changes.put("mode", "IMMEDIATE");
        changes.put("boundary", Timestamp.from(boundary.plusSeconds(1))); changes.put("prior_operation", UUID.randomUUID());
        changes.put("prior_mode", "IMMEDIATE"); changes.put("acknowledged_at", Timestamp.from(saved.acknowledgedAt().plusNanos(1000)));
        changes.forEach((column, value) -> sqlState("23514", () -> jdbc.update("update " + TABLE + " set " + column + "=? where operation_id=?", value, saved.operation())));
        assertThat(proofs.receipt(account.id(), saved.operation())).contains(saved);
    }

    @Test void receiptRefusesWrongModeBoundaryPriorAndForeignSubscriptionWithoutAcknowledging() {
        var pending = cancel(Mode.PERIOD_END);
        var received = received(pending);
        evidenceConflict(() -> proofs.record(pending.subscription(), pending.operation(), pending.step(), OWNER, Mode.IMMEDIATE, boundary, null, received));
        evidenceConflict(() -> proofs.record(pending.subscription(), pending.operation(), pending.step(), OWNER, Mode.PERIOD_END, boundary.plusSeconds(1), null, received));
        evidenceConflict(() -> proofs.record(pending.subscription(), pending.operation(), pending.step(), OWNER, Mode.PERIOD_END, boundary, original.operation().id(), received));
        evidenceConflict(() -> proofs.record(other.subscription(), pending.operation(), pending.step(), OWNER, Mode.PERIOD_END, boundary, null, received));
        assertStillUnacknowledged(pending);
    }

    @Test void receiptRefusesStaleLifecycleOperationAndCurrentRevision() {
        var pending = cancel(Mode.PERIOD_END);
        var o = pending.operation();
        var stale = copyOperation(o, o.status(), o.version() + 1);
        evidenceConflict(() -> proofs.record(pending.subscription(), stale, pending.step(), OWNER, pending.mode(), boundary, null, received(pending)));
        jdbc.update("update subscriptions set revision=revision+1 where id=?", pending.subscription().id());
        evidenceConflict(() -> record(pending, received(pending)));
        assertStillUnacknowledged(pending);
    }

    @Test void receiptRefusesPolicyDriftAndLiveOrInactiveOriginalAccount() {
        var pending = cancel(Mode.PERIOD_END);
        jdbc.update("update payment_accounts set account_mode='LIVE' where id=?", account.id().value());
        try { evidenceConflict(() -> record(pending, received(pending))); }
        finally { jdbc.update("update payment_accounts set account_mode='SANDBOX' where id=?", account.id().value()); }
        jdbc.update("update payment_accounts set account_status='DISABLED' where id=?", account.id().value());
        try { evidenceConflict(() -> record(pending, received(pending))); }
        finally { jdbc.update("update payment_accounts set account_status='ACTIVE' where id=?", account.id().value()); }
        jdbc.update("update site_subscription_policies set version=version+1 where site_id=?", site.value());
        evidenceConflict(() -> record(pending, received(pending)));
        assertStillUnacknowledged(pending);
    }

    @Test void receiptRefusesForeignCustomerArgumentAndChangedOriginalSubscriptionReference() {
        var pending = cancel(Mode.PERIOD_END);
        var s = pending.subscription();
        var foreignCustomer = new Subscription(s.id(), s.siteId(), foreign.customer().id(), s.productId(), s.paymentAccountId(), s.billingPrice(), s.entitledPrice(), s.billingStatus(), s.providerReference(), s.currentPeriod(), s.paidThrough(), s.trial(), s.cancellation(), s.pendingChange(), s.debt(), s.terminalReason(), s.revision(), s.createdAt(), s.updatedAt(), s.lastReconciledAt());
        evidenceConflict(() -> proofs.record(foreignCustomer, pending.operation(), pending.step(), OWNER, pending.mode(), boundary, null, received(pending)));
        jdbc.update("update subscription_operations set provider_reference=? where id=?", other.subscription().providerReference(), pending.operation().id());
        providerConflict(() -> record(pending, received(pending)));
        assertStillUnacknowledged(pending);
    }

    @Test void cancellationStepReservationRequiresExactOriginalKeyKindResourceAndFingerprint() {
        var context = context(local, "resource-guards");
        var s = original.subscription();
        var r = cancellations.reserve(context, s.id(), CancelSubscriptionUseCase.Mode.PERIOD_END, null,
            cancellations.candidate(context, s.id()), admissionSnapshot(s), true, false);
        var o = r.operation();
        providerConflict(() -> steps.reserve(account, ProviderOperation.Kind.SUBSCRIPTION_UPDATE, o.id(), o.providerOperationKey() + ":METHOD", "task9-body"));
        providerConflict(() -> steps.reserve(account, ProviderOperation.Kind.SUBSCRIPTION_UPDATE, original.operation().id(), o.providerOperationKey(), "task9-body"));
        providerConflict(() -> steps.reserve(account, ProviderOperation.Kind.SUBSCRIPTION_CANCEL, o.id(), o.providerOperationKey(), "task9-body"));
        providerConflict(() -> steps.reserve(foreign.account(), ProviderOperation.Kind.SUBSCRIPTION_UPDATE, o.id(), o.providerOperationKey(), "task9-body"));
        var step = steps.reserve(account, ProviderOperation.Kind.SUBSCRIPTION_UPDATE, o.id(), o.providerOperationKey(), "task9-body");
        assertThat(steps.reserve(account, step.kind(), o.id(), o.providerOperationKey(), "task9-body")).isEqualTo(step);
        providerConflict(() -> steps.reserve(account, step.kind(), o.id(), o.providerOperationKey(), "changed-body"));
        assertThat(step.firstDispatchedAt()).isNull();
        assertThat(count(TABLE)).isZero();
    }

    @Test void actualClaimAndAcknowledgementRecheckPinnedAliasAndOwnedNativeResource() {
        var pending = cancel(Mode.PERIOD_END);
        providerConflict(() -> steps.acknowledge(pending.step().id(), OWNER, pending.step().version(), other.subscription().providerReference()));
        providerConflict(() -> steps.acknowledge(pending.step().id(), "foreign-owner", pending.step().version(), pending.subscription().providerReference()));
        providerConflict(() -> steps.acknowledge(pending.step().id(), OWNER, pending.step().version() + 1, pending.subscription().providerReference()));
        assertThat(steps.claim(pending.step().id(), "competing-owner", Instant.now(), Duration.ofSeconds(60))).isEmpty();
        steps.unknown(pending.step().id(), OWNER, pending.step().version());
        var unknown = steps.findById(pending.step().id()).orElseThrow();
        jdbc.update("update payment_accounts set credentials_ref='rotated-test-alias' where id=?", account.id().value());
        try { providerConflict(() -> steps.claim(unknown.id(), "new-owner", Instant.now(), Duration.ofSeconds(60))); }
        finally { jdbc.update("update payment_accounts set credentials_ref=? where id=?", account.credentialsRef(), account.id().value()); }
        assertThat(steps.findById(unknown.id())).contains(unknown);
        assertThat(count(TABLE)).isZero();
    }

    @Test void proofInsertFailureRollsBackAcknowledgementAndAllowsOnlyTheSameOriginalRetry() {
        var pending = cancel(Mode.PERIOD_END);
        var received = received(pending);
        jdbc.execute("create function task9_reject_cancellation_proof() returns trigger language plpgsql as $$ begin raise exception 'injected receipt failure' using errcode='23514'; end; $$");
        jdbc.execute("create trigger task9_reject_cancellation_proof before insert on " + TABLE + " for each row execute function task9_reject_cancellation_proof()");
        try { sqlState("23514", () -> record(pending, received)); }
        finally {
            jdbc.execute("drop trigger task9_reject_cancellation_proof on " + TABLE);
            jdbc.execute("drop function task9_reject_cancellation_proof()");
        }
        assertStillUnacknowledged(pending);
        var saved = record(pending, received);
        assertThat(saved.step()).isEqualTo(pending.step().id());
        assertThat(saved.acknowledgedAt()).isEqualTo(received);
        assertAcknowledged(pending);
        assertNoLifecycleOrCashChange(pending.subscription());
    }

    @Test void receiptWaitsForExactStepBeforeTakingAccountLock() throws Exception {
        var pending = cancel(Mode.PERIOD_END);
        try (var blocker = jdbc.getDataSource().getConnection(); var probe = jdbc.getDataSource().getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false); probe.setAutoCommit(false);
            lock(blocker, "select id from provider_operations where id=? for update", pending.step().id());
            int blockerPid = backendPid(blocker);
            var result = pool.submit(() -> record(pending, received(pending)));
            try {
                int waiter = awaitBlockedQuery(blockerPid, "provider_operations");
                assertThat(waiter).isNotEqualTo(blockerPid);
                // NOWAIT succeeds only if the waiting receipt has not acquired Account first.
                lock(probe, "select id from payment_accounts where id=? for update nowait", account.id().value());
                probe.rollback();
                assertThat(result.isDone()).isFalse();
            } finally { probe.rollback(); blocker.rollback(); }
            assertThat(result.get(15, TimeUnit.SECONDS).operation()).isEqualTo(pending.operation().id());
        }
        assertAcknowledged(pending);
        assertNoLifecycleOrCashChange(pending.subscription());
    }

    @Test void receiptRetainsStepLockWhileWaitingForAccountRatherThanReorderingIt() throws Exception {
        var pending = cancel(Mode.PERIOD_END);
        try (var blocker = jdbc.getDataSource().getConnection(); var probe = jdbc.getDataSource().getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false); probe.setAutoCommit(false);
            lock(blocker, "select id from payment_accounts where id=? for update", account.id().value());
            var result = pool.submit(() -> record(pending, received(pending)));
            try {
                awaitBlockedQuery(backendPid(blocker), "payment_accounts");
                assertThatThrownBy(() -> lock(probe, "select id from provider_operations where id=? for update nowait", pending.step().id()))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("55P03"));
            } finally { probe.rollback(); blocker.rollback(); }
            assertThat(result.get(15, TimeUnit.SECONDS).step()).isEqualTo(pending.step().id());
        }
        assertAcknowledged(pending);
    }

    @Test void receiptSecurityClockMustFollowRealClaimAndPrecedeExactBoundary() {
        var pending = cancel(Mode.PERIOD_END);
        evidenceConflict(() -> record(pending, pending.step().firstDispatchedAt().minusNanos(1000)));
        evidenceConflict(() -> record(pending, received(pending).plusNanos(1)));
        evidenceConflict(() -> record(pending, pending.step().retryBefore()));
        evidenceConflict(() -> record(pending, received(pending).plusSeconds(300)));
        evidenceConflict(() -> record(pending, boundary));
        assertStillUnacknowledged(pending);
        assertThat(record(pending, received(pending)).boundary()).isEqualTo(boundary);
    }

    @Test void withdrawalCannotReplacePriorOperationOrAcknowledgeAtItsDeadline() {
        var first = cancel(Mode.PERIOD_END);
        var saved = record(first, received(first));
        var pending = withdraw(first);
        evidenceConflict(() -> proofs.record(pending.subscription(), pending.operation(), pending.step(), OWNER, Mode.WITHDRAW, boundary, other.operation().id(), received(pending)));
        evidenceConflict(() -> record(pending, boundary));
        assertStillUnacknowledged(pending);
        assertThat(proofs.activeSchedule(account.id(), pending.subscription().id(), boundary)).contains(saved);
        record(pending, received(pending));
        assertThat(proofs.activeSchedule(account.id(), pending.subscription().id(), boundary)).isEmpty();
    }

    @Test void repeatedUpdateReferencesDoNotRemoveUniquenessForCreateOrCustomerKinds() {
        var pending = cancel(Mode.PERIOD_END);
        record(pending, received(pending));
        var anotherCustomer = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        var key = "customer:" + account.id().value() + ":" + anotherCustomer.id().value();
        var reserved = steps.reserve(account, ProviderOperation.Kind.CUSTOMER_CREATE, anotherCustomer.id().value(), key, "task9-second-customer");
        var claimed = claim(reserved);
        String existingCustomer = bindings.findCustomer(account.id(), site, customer.id()).orElseThrow().providerReference();
        providerConflict(() -> steps.acknowledge(claimed.id(), OWNER, claimed.version(), existingCustomer));
        assertThat(steps.findById(claimed.id())).contains(claimed);
        var second = commands.reserveCreate(new CreateSubscriptionUseCase.Command(context(local, "other-product-create"), price(0), true));
        var createStep = claim(steps.reserve(account, ProviderOperation.Kind.SUBSCRIPTION_CREATE, second.operation().id(), second.operation().providerOperationKey(), "task9-second-create"));
        providerConflict(() -> steps.acknowledge(createStep.id(), OWNER, createStep.version(), original.subscription().providerReference()));
        assertThat(steps.findById(createStep.id())).contains(createStep);
        assertThat(proofs.receipt(account.id(), pending.operation().id())).isPresent();
        assertThat(count("payments")).isZero();
    }

    private record Owner(SiteId site, Customer customer, PaymentAccount account, SubscriptionActor actor, PriceId price, String origin) {}
    private record Original(Subscription subscription, SubscriptionOperation operation, ProviderOperation creationStep) {}
    private record Pending(Subscription subscription, SubscriptionOperation operation, ProviderOperation step, Mode mode, Instant boundary, UUID prior) {}

    private Owner foreignOwner() {
        var foreignSite = new SiteId(UUID.randomUUID()); var foreignAccount = new PaymentAccountId(UUID.randomUUID());
        jdbc.update("insert into payment_accounts values (?,?,'STRIPE','SANDBOX','ACTIVE','acct_ForeignCancellation','stripe-sandbox-default',now(),0)", foreignAccount.value(), "cancel-account-" + foreignAccount.value());
        jdbc.update("insert into sites values (?,?, 'Foreign cancellation',?,'ACTIVE','AUTOMATIC',now(),now(),0)", foreignSite.value(), "cancel-site-" + foreignSite.value(), foreignAccount.value());
        String origin = "https://foreign-cancellation.test";
        jdbc.update("insert into site_allowed_origins values (?,?)", foreignSite.value(), origin);
        jdbc.update("insert into site_authentication_modes values (?,'SITE_TOKEN')", foreignSite.value());
        var foreignCustomer = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), foreignSite, null, null, INITIAL_TIME));
        policies.save(new SubscriptionPolicy(foreignSite, 0, true, 3, true, false, false, Set.of()), 0);
        var foreignActor = new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(foreignSite, foreignCustomer.id(), origin).rawToken(), origin));
        var product = UUID.randomUUID(); var foreignPrice = new PriceId(UUID.randomUUID());
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,?,'Foreign plan','ACTIVE',?,?)", product, foreignSite.value(), "p-" + product, Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,?,1,'SUBSCRIPTION',2000,'EUR','MONTH',0,'OPTIONAL_DURING_TRIAL','NONE','PUBLISHED',?,?,?)", foreignPrice.value(), foreignSite.value(), product, "p-" + foreignPrice.value(), Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME));
        return new Owner(foreignSite, foreignCustomer, accounts.findById(foreignAccount).orElseThrow(), foreignActor, foreignPrice, origin);
    }

    private Original paidOriginal(Owner owner) {
        String customerKey = "customer:" + owner.account().id().value() + ":" + owner.customer().id().value();
        var customerStep = steps.reserve(owner.account(), ProviderOperation.Kind.CUSTOMER_CREATE, owner.customer().id().value(), customerKey, "task9-customer-" + owner.customer().id().value());
        var customerAck = acknowledge(customerStep, nativeReference("cus_"));
        bindings.bindCustomer(owner.account().id(), owner.site(), owner.customer().id(), customerAck.providerReference(), customerAck.id());
        var admitted = commands.reserveCreate(new CreateSubscriptionUseCase.Command(context(owner, "create-" + UUID.randomUUID()), owner.price(), true));
        var createStep = steps.reserve(owner.account(), ProviderOperation.Kind.SUBSCRIPTION_CREATE, admitted.operation().id(), admitted.operation().providerOperationKey(), "task9-create-" + admitted.operation().id());
        var createAck = acknowledge(createStep, nativeReference("sub_"));
        commands.recordReply(admitted.operation().id(), new Reply(Outcome.ACCEPTED, createAck.providerReference(), null));
        var incomplete = subscriptions.find(owner.site(), admitted.subscription().id()).orElseThrow();
        // Honest precondition: seed an already paid source period. No normalization/cash proof is tested here.
        var period = new Subscription.Period(INITIAL_TIME.minusSeconds(86400), boundary);
        subscriptions.save(incomplete.confirmPaid(incomplete.billingPrice(), period, INITIAL_TIME), incomplete.revision());
        finish(admitted.operation());
        return new Original(subscriptions.find(owner.site(), incomplete.id()).orElseThrow(), op(admitted.operation().id()), createAck);
    }

    private Pending cancel(Mode mode) { return cancel(original, local, mode); }
    private Pending cancel(Original source, Owner owner, Mode mode) {
        var s = subscriptions.find(owner.site(), source.subscription().id()).orElseThrow();
        var c = context(owner, "cancel-" + UUID.randomUUID());
        var requested = mode == Mode.IMMEDIATE ? CancelSubscriptionUseCase.Mode.IMMEDIATE : CancelSubscriptionUseCase.Mode.PERIOD_END;
        var reserved = cancellations.reserve(c, s.id(), requested, mode == Mode.IMMEDIATE ? s.id() : null,
            cancellations.candidate(c, s.id()), admissionSnapshot(s), true, false);
        assertThat(reserved.owner()).isTrue();
        assertThat(reserved.operation().status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        var kind = mode == Mode.IMMEDIATE ? ProviderOperation.Kind.SUBSCRIPTION_CANCEL : ProviderOperation.Kind.SUBSCRIPTION_UPDATE;
        var step = claim(steps.reserve(owner.account(), kind, reserved.operation().id(), reserved.operation().providerOperationKey(), "task9-cancel-body-" + reserved.operation().id()));
        return new Pending(reserved.subscription(), reserved.operation(), step, mode, mode == Mode.IMMEDIATE ? null : boundary, null);
    }

    private Pending withdraw(Pending prior) {
        var s = subscriptions.find(prior.subscription().siteId(), prior.subscription().id()).orElseThrow();
        if (s.cancellation() == null) subscriptions.save(s.scheduleCancellation(boundary, INITIAL_TIME), s.revision());
        finish(prior.operation());
        s = subscriptions.find(s.siteId(), s.id()).orElseThrow();
        var c = context(local, "withdraw-" + UUID.randomUUID());
        var reserved = cancellations.reserve(c, s.id(), null, null, cancellations.candidate(c, s.id()), admissionSnapshot(s), true, true);
        assertThat(reserved.owner()).isTrue();
        var step = claim(steps.reserve(account, ProviderOperation.Kind.SUBSCRIPTION_UPDATE, reserved.operation().id(), reserved.operation().providerOperationKey(), "task9-withdraw-body-" + reserved.operation().id()));
        return new Pending(reserved.subscription(), reserved.operation(), step, Mode.WITHDRAW, boundary, prior.operation().id());
    }

    private Snapshot admissionSnapshot(Subscription s) {
        // Synthetic admission input agrees with the seeded source; this does not test Stripe normalization.
        var cash = new CashObject("pi_FixturePaid", 2000, "EUR", PaymentStatus.SUCCEEDED, INITIAL_TIME);
        var invoice = new Invoice("in_FixturePaid", BillingRecord.Kind.INITIAL, s.billingPrice(), null, s.currentPeriod(), 2000, "EUR", SettlementKind.CASH, List.of(cash));
        return new Snapshot(s.providerReference(), s.billingStatus(), s.billingPrice(), s.currentPeriod(), null,
            s.cancellation() == null ? null : s.cancellation().effectiveAt(), List.of(invoice), null, true, 0, INITIAL_TIME);
    }

    private SubscriptionCommandContext context(Owner owner, String key) {
        return new SubscriptionCommandContext(owner.actor(), owner.site(), owner.customer().id(), owner.origin(), key, "task9-cancellation-sql");
    }
    private ProviderOperation claim(ProviderOperation step) {
        return steps.claim(step.id(), OWNER, Instant.now(), Duration.ofSeconds(60)).orElseThrow();
    }
    private ProviderOperation acknowledge(ProviderOperation step, String reference) {
        var claimed = step.status() == ProviderOperation.Status.DISPATCHING ? step : claim(step);
        return steps.acknowledge(claimed.id(), OWNER, claimed.version(), reference);
    }
    private Instant received(Pending pending) {
        var at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(at).isAfterOrEqualTo(pending.step().firstDispatchedAt()).isBefore(pending.step().retryBefore());
        if (pending.boundary() != null) assertThat(at).isBefore(pending.boundary());
        return at;
    }
    private SubscriptionCancellationProofStore.Receipt record(Pending p, Instant at) {
        return proofs.record(p.subscription(), p.operation(), p.step(), OWNER, p.mode(), p.boundary(), p.prior(), at);
    }
    private void finish(SubscriptionOperation operation) {
        tx.required(() -> {
            var current = operations.lock(operation.id());
            return operations.save(copyOperation(current, SubscriptionOperation.Status.SUCCEEDED, current.version() + 1), current.version());
        });
    }
    private static SubscriptionOperation copyOperation(SubscriptionOperation o, SubscriptionOperation.Status status, long version) {
        return new SubscriptionOperation(o.id(), o.siteId(), o.customerId(), o.subscriptionId(), o.kind(), status, o.authorityScope(), o.keyDigest(), o.fingerprint(), o.providerOperationKey(), o.expectedRevision(), o.policyVersion(), o.providerReference(), o.safeFailureCode(), o.createdAt(), o.updatedAt(), version);
    }
    private void assertAcknowledged(Pending p) {
        assertThat(steps.findById(p.step().id())).hasValueSatisfying(ack -> {
            assertThat(ack.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
            assertThat(ack.providerReference()).isEqualTo(p.subscription().providerReference());
            assertThat(ack.operationKey()).isEqualTo(p.operation().providerOperationKey());
            assertThat(ack.fingerprint()).isEqualTo(p.step().fingerprint());
            assertThat(ack.version()).isEqualTo(p.step().version() + 1);
            assertThat(ack.leaseOwner()).isNull();
        });
    }
    private void assertStillUnacknowledged(Pending p) {
        assertThat(steps.findById(p.step().id())).contains(p.step());
        assertThat(proofs.receipt(p.subscription().paymentAccountId(), p.operation().id())).isEmpty();
    }
    private void assertNoLifecycleOrCashChange(Subscription expected) {
        assertThat(subscriptions.find(expected.siteId(), expected.id())).contains(expected);
        assertThat(count("payments")).isZero();
        assertThat(count("refunds")).isZero();
        assertThat(count("subscription_billing_records")).isZero();
    }

    private Map<String, Object> proofRow(Pending p, ProviderOperation step, Instant at) {
        var row = new LinkedHashMap<String, Object>();
        row.put("operation_id", p.operation().id()); row.put("site_id", p.subscription().siteId().value());
        row.put("customer_id", p.subscription().customerId().value()); row.put("subscription_id", p.subscription().id());
        row.put("payment_account_id", p.subscription().paymentAccountId().value()); row.put("operation_kind", p.operation().kind().name());
        row.put("step_id", step.id()); row.put("step_kind", step.kind().name());
        row.put("subscription_reference", p.subscription().providerReference()); row.put("mode", p.mode().name());
        row.put("boundary", p.boundary() == null ? null : Timestamp.from(p.boundary())); row.put("prior_operation", p.prior());
        row.put("acknowledged_at", Timestamp.from(at));
        return row;
    }
    private void insert(Map<String, Object> row) {
        jdbc.update("insert into " + TABLE + "(" + String.join(",", row.keySet()) + ") values (" + String.join(",", Collections.nCopies(row.size(), "?")) + ")", row.values().toArray());
    }
    private void reject(Map<String, Object> row, String column, Object value, String state) {
        var changed = new LinkedHashMap<>(row); changed.put(column, value);
        sqlState(state, () -> insert(changed));
    }
    private static void lock(Connection connection, String sql, UUID id) throws SQLException {
        try (var query = connection.prepareStatement(sql)) {
            query.setObject(1, id);
            try (var rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
    }
    private static int backendPid(Connection connection) throws SQLException {
        try (var query = connection.createStatement(); var rows = query.executeQuery("select pg_backend_pid()")) {
            assertThat(rows.next()).isTrue(); return rows.getInt(1);
        }
    }
    private int awaitBlockedQuery(int blocker, String table) throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        do {
            var waiting = jdbc.queryForList("select pid from pg_stat_activity where wait_event_type='Lock' and ?=any(pg_blocking_pids(pid)) and query like ?", Integer.class, blocker, "%" + table + "%");
            if (!waiting.isEmpty()) return waiting.getFirst();
            Thread.sleep(10); // Poll only; PostgreSQL's observed blocker is the synchronization proof.
        } while (System.nanoTime() < until);
        throw new AssertionError("Expected an actual PostgreSQL lock wait on " + table);
    }
    private static String nativeReference(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", ""); }
    private static void evidenceConflict(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class, e -> assertThat(e.code()).isEqualTo("PROVIDER_EVIDENCE_MISMATCH"));
    }
    private static void providerConflict(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class, e -> assertThat(e.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
    }
    private static void sqlState(String expected, ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DataIntegrityViolationException.class, e ->
            assertThat(e.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class, sql -> assertThat(sql.getSQLState()).isEqualTo(expected)));
    }
}
