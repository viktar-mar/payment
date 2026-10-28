package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase;
import dev.genericpayments.platform.customers.domain.Customer;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.customers.domain.CustomerStatus;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingGrantTokens;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingMutationAdapter;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientSession;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.ActionKind;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.NextAction;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Outcome;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.ProviderQuote;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Reply;
import dev.genericpayments.platform.subscriptions.application.port.in.PreviewSubscriptionChangeUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.ConfirmSubscriptionActionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionClientSessionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionActionStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionClientSessionPort;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderPort;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry;
import dev.genericpayments.platform.subscriptions.application.port.out.BillingRecordStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCatalogPort;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Line;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Quote;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Schedule;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.SourcePhase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionQuoteStore;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionFactFixture;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionPlanTransaction;
import dev.genericpayments.platform.subscriptions.domain.*;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;

/** Hosted PostgreSQL only: real Flyway/application fixture; synthetic owned loopback only, no local runtime. */
@Import(StripeSubscriptionPlanProofIntegrationTest.GrantPorts.class)
class StripeSubscriptionPlanProofIntegrationTest extends SubscriptionFactFixture {
    @TestConfiguration(proxyBeanMethods = false)
    static class GrantPorts {
        @Bean @Primary SubscriptionClientSessionPort planGrantClientFixture() { return mock(SubscriptionClientSessionPort.class); }
        @Bean @Primary SubscriptionProviderRegistry planGrantRegistryFixture() { return mock(SubscriptionProviderRegistry.class); }
    }
    @Autowired SubscriptionPlanProofStore proofs;
    @Autowired ProviderOperationStore steps;
    @Autowired StripeBillingBindingPort bindings;
    @Autowired SubscriptionPlanTransaction plans;
    @Autowired SubscriptionCatalogPort catalog;
    @Autowired SubscriptionQuoteStore commonQuotes;
    @Autowired AnonymizeCustomerUseCase anonymizer;
    @Autowired SubscriptionActionStore actions;
    @Autowired SubscriptionBillingProofStore billingProofs;
    @Autowired SubscriptionClientSessionPort clientSessions;
    @Autowired CreateSubscriptionClientSessionUseCase clientSessionUseCase;
    @Autowired ConfirmSubscriptionActionUseCase confirmations;
    @Autowired BillingRecordStore billingRecords;
    @Autowired dev.genericpayments.platform.partners.application.port.out.SiteApiKeyPort siteKeys;
    @Autowired PreviewSubscriptionChangeUseCase planPreview;
    @Autowired dev.genericpayments.platform.subscriptions.application.port.in.ChangeSubscriptionPlanUseCase planChange;
    @Autowired dev.genericpayments.platform.subscriptions.application.port.in.ReconcileSubscriptionUseCase reconciliation;
    @Autowired dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort catalogMappings;
    @Autowired dev.genericpayments.platform.sites.application.port.out.LoadSitePort siteLoader;
    @Autowired dev.genericpayments.platform.shared.application.port.out.DigestPort digest;
    @Autowired dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort callbackConfiguration;
    @Autowired dev.genericpayments.platform.webhooks.application.port.out.OutboxPort outbox;
    @Autowired dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort encryption;
    @Autowired dev.genericpayments.platform.webhooks.application.port.out.CallbackPayloadPort callbackPayload;
    @Autowired java.time.Clock businessClock;

    static final String OWNER = "plan-proof-owner";
    static final Subscription.Period SOURCE_PERIOD = new Subscription.Period(INITIAL_TIME.minusSeconds(86400), INITIAL_TIME.plusSeconds(29 * 86400));
    static final Instant TARGET_PHASE_END = Instant.parse("2026-11-12T10:15:30Z");
    Original original;
    String customerReference;

    @BeforeEach void stripePlanFixture() {
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_planProofs',credentials_ref='stripe-sandbox-default' where id=?", account.id().value());
        account = accounts.findById(account.id()).orElseThrow();
        customerReference = bindCustomer(customer);
        original = paidOriginal();
    }

    @Test void configuredCodecRoundTripsOneImmutableQuoteAcrossConcurrentSameIdReplay() throws Exception {
        var prepared = preview(original.subscription(), ChangeTiming.IMMEDIATE, "quote-replay");
        var q = prepared.quote();
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); return proofs.saveQuote(q); });
            var second = pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); return proofs.saveQuote(q); });
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(q);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(q);
        }
        // The actual injected production codec is exercised, including application SNAKE_CASE.
        assertThat(jdbc.queryForObject("select descriptor->>'subscription_reference' from stripe_plan_quotes where id=?", String.class, q.id())).isEqualTo(q.subscriptionReference());
        assertThat(jdbc.queryForObject("select descriptor->>'customer_reference' from stripe_plan_quotes where id=?", String.class, q.id())).isEqualTo(customerReference);
        assertThat(proofs.quote(account.id(), q.id())).contains(q);
        assertThat(proofs.quote(new PaymentAccountId(UUID.randomUUID()), q.id())).isEmpty();
        assertThat(count("stripe_plan_quotes")).isEqualTo(1);
        assertThat(sub(original.subscription().id()).pendingChange()).isNull();
    }

    @Test void quoteSqlRejectsSameAccountForeignCustomerAndNativeSubscriptionReferences() {
        var prepared = preview(original.subscription(), ChangeTiming.IMMEDIATE, "quote-remote-owners");
        var otherCustomer = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        var otherCustomerReference = bindCustomer(otherCustomer);
        var otherSubscription = paidOriginal();
        var row = copiedQuote(prepared.quote());
        var badCustomer = changedDescriptor(row, "customer_reference", otherCustomerReference);
        sqlState("23503", () -> insert("stripe_plan_quotes", badCustomer));
        var badSubscription = changedDescriptor(row, "subscription_reference", otherSubscription.subscription().providerReference());
        sqlState("23503", () -> insert("stripe_plan_quotes", badSubscription));
        assertThat(proofs.quote(account.id(), prepared.quote().id())).contains(prepared.quote());
        assertThat(count("stripe_plan_quotes")).isEqualTo(1);
    }

    @Test void quoteSqlRejectsExistingForeignOwnersDescriptorDriftAndOutOfBoundsEconomics() {
        var prepared = preview(original.subscription(), ChangeTiming.NEXT_PERIOD, "quote-sql-bounds");
        var q = prepared.quote();
        var other = paidOriginal();
        for (var owner : foreignOwners(other.subscription()).entrySet()) {
            var bad = changedDescriptor(copiedQuote(q), owner.getKey(), owner.getValue());
            sqlState("23503", () -> insert("stripe_plan_quotes", bad));
        }
        for (var mutation : List.of(
            Map.entry("expires_at", q.anchor().getEpochSecond()),
            Map.entry("expires_at", q.anchor().getEpochSecond() + 301),
            Map.entry("anchor", q.period().end().getEpochSecond()),
            Map.entry("period_end", q.period().start().getEpochSecond()),
            Map.entry("due_now_minor", 1L))) {
            var bad = copiedQuote(q); bad.put(mutation.getKey(), mutation.getValue());
            sqlState("23514", () -> insert("stripe_plan_quotes", bad));
        }
        var wrongDescriptor = copiedQuote(q);
        wrongDescriptor.put("descriptor", wrongDescriptor.get("descriptor").toString().replace(q.subscriptionReference(), "sub_DescriptorDrift"));
        sqlState("23514", () -> insert("stripe_plan_quotes", wrongDescriptor));
        var malformed = changedDescriptor(copiedQuote(q), "subscription_reference", "sub_bad_reference");
        sqlState("23514", () -> insert("stripe_plan_quotes", malformed));
        assertThat(count("stripe_plan_quotes")).isEqualTo(1);
    }

    @Test void quoteIdentityAndTermsCannotBeReplacedOrAdoptedAfterSourceRevisionChanges() {
        var p = preview(original.subscription(), ChangeTiming.IMMEDIATE, "quote-immutable");
        sqlState("23514", () -> jdbc.update("update stripe_plan_quotes set due_now_minor=due_now_minor+1 where id=?", p.quote().id()));
        sqlState("23514", () -> jdbc.update("update stripe_plan_quotes set descriptor=jsonb_set(descriptor,'{item}','\"si_Replaced\"'::jsonb) where id=?", p.quote().id()));
        var changed = new Quote(p.quote().id(), p.quote().account(), p.quote().site(), p.quote().customer(), p.quote().subscription(),
            p.quote().subscriptionReference(), p.quote().customerReference(), p.quote().source(), p.quote().entitled(), p.quote().target(),
            p.quote().sourcePrice(), p.quote().targetPrice(), p.quote().product(), "si_Replaced", p.quote().period(), p.quote().paidThrough(),
            p.quote().revision(), p.quote().policy(), p.quote().timing(), p.quote().anchor(), p.quote().expiresAt(), p.quote().dueNow(), p.quote().lines());
        providerConflict(() -> proofs.saveQuote(changed));
        var change = reserve(p);
        providerConflict(() -> proofs.saveQuote(p.quote()));
        assertThat(proofs.quote(account.id(), p.quote().id())).contains(p.quote());
        assertUnchangedEntitlement(change);
    }

    @Test void originalChangeResultKeepsExactQuoteStepAndFinalLineIdentityWithoutGrantingEntitlement() {
        var change = plan(original.subscription(), ChangeTiming.IMMEDIATE, "original-result");
        var step = claimed(change.operation(), ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
        var result = recordResult(change, step, "in_OriginalResult");
        assertThat(result.operation()).isEqualTo(change.operation().id());
        assertThat(result.quote()).isEqualTo(change.quote().id());
        assertThat(result.step()).isEqualTo(step.id());
        assertThat(result.creditLine()).isEqualTo(creditLine(change));
        assertThat(result.debitLine()).isEqualTo(debitLine(change));
        assertThat(proofs.result(account.id(), change.operation().id())).contains(result);
        assertThat(proofs.proration(account.id(), result.invoice())).hasValueSatisfying(p -> {
            assertThat(p.quote()).isEqualTo(change.quote()); assertThat(p.result()).isEqualTo(result);
        });
        assertThat(proofs.prorations(account.id(), change.subscription().id())).hasSize(1);
        assertThat(proofs.proration(new PaymentAccountId(UUID.randomUUID()), result.invoice())).isEmpty();
        assertThat(tx.required(() -> commonQuotes.forOperation(site, change.subscription().id(), change.operation().id()))).contains(change.common());
        assertThat(steps.findById(step.id()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        providerConflict(() -> recordResult(change, step, "in_OriginalResult")); // stale pre-ACK fence cannot commit again
        assertUnchangedEntitlement(change);
    }

    @Test void resultSqlRequiresOriginalChangeOwnersIntentQuoteAndAcknowledgedStepResource() {
        var change = plan(original.subscription(), ChangeTiming.IMMEDIATE, "result-sql");
        var reserved = reserveStep(change.operation(), ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
        var pending = resultRow(change, reserved, "in_ResultSql");
        sqlState("23503", () -> insert("stripe_plan_results", pending));
        var step = acknowledge(reserved, change.quote().subscriptionReference());
        var valid = resultRow(change, step, "in_ResultSql");
        var otherOriginal = paidOriginal();
        var other = plan(otherOriginal.subscription(), ChangeTiming.IMMEDIATE, "other-result-sql");
        var otherStep = acknowledge(reserveStep(other.operation(), ProviderOperation.Kind.SUBSCRIPTION_UPDATE), other.subscription().providerReference());
        for (var owner : foreignOwners(other.subscription()).entrySet()) reject("stripe_plan_results", valid, owner.getKey(), owner.getValue(), "23503");
        reject("stripe_plan_results", valid, "operation_id", original.operation().id(), "23503");
        reject("stripe_plan_results", valid, "operation_kind", "RESUME", "23514");
        reject("stripe_plan_results", valid, "common_quote_id", other.common().id(), "23503");
        reject("stripe_plan_results", valid, "quote_id", other.quote().id(), "23514");
        var crossQuote = new LinkedHashMap<>(valid);
        crossQuote.put("quote_id", other.quote().id()); crossQuote.put("quote_reference", other.quote().reference());
        sqlState("23503", () -> insert("stripe_plan_results", crossQuote));
        reject("stripe_plan_results", valid, "step_id", original.creationStep().id(), "23503");
        var crossStep = new LinkedHashMap<>(valid);
        crossStep.put("step_id", otherStep.id()); crossStep.put("subscription_reference", otherStep.providerReference());
        sqlState("23503", () -> insert("stripe_plan_results", crossStep));
        reject("stripe_plan_results", valid, "subscription_reference", otherStep.providerReference(), "23503");
        reject("stripe_plan_results", valid, "step_kind", "SUBSCRIPTION_CREATE", "23514");
        insert("stripe_plan_results", valid);
        assertThat(proofs.result(account.id(), change.operation().id())).hasValueSatisfying(r -> assertThat(r.step()).isEqualTo(step.id()));
        assertUnchangedEntitlement(change);
    }

    @Test void resultSqlRequiresDistinctNativeFinalLinesAndFreezesInvoiceExpiryAndLineIdentity() {
        var change = plan(original.subscription(), ChangeTiming.IMMEDIATE, "result-line-sql");
        var step = acknowledge(reserveStep(change.operation(), ProviderOperation.Kind.SUBSCRIPTION_UPDATE), change.subscription().providerReference());
        var valid = resultRow(change, step, "in_LineIdentity");
        reject("stripe_plan_results", valid, "credit_line", null, "23502");
        reject("stripe_plan_results", valid, "debit_line", null, "23502");
        reject("stripe_plan_results", valid, "credit_line", "il_bad_identity", "23514");
        reject("stripe_plan_results", valid, "debit_line", valid.get("credit_line"), "23514");
        reject("stripe_plan_results", valid, "pending_expiry", 0L, "23514");
        reject("stripe_plan_results", valid, "invoice_reference", "in_bad_identity", "23514");
        insert("stripe_plan_results", valid);
        for (String assignment : List.of("credit_line='il_Replaced'", "debit_line='il_Replaced'", "invoice_reference='in_Replaced'", "pending_expiry=pending_expiry+1", "quote_id='" + UUID.randomUUID() + "'")) {
            sqlState("23514", () -> jdbc.update("update stripe_plan_results set " + assignment + " where operation_id=?", change.operation().id()));
        }
        assertThat(proofs.result(account.id(), change.operation().id())).hasValueSatisfying(r -> {
            assertThat(r.creditLine()).isEqualTo(creditLine(change)); assertThat(r.debitLine()).isEqualTo(debitLine(change));
        });
    }

    @Test void failedResultInsertAndDuplicateInvoiceRollBackAcknowledgementBeforeExactRetry() {
        var change = plan(original.subscription(), ChangeTiming.IMMEDIATE, "result-rollback");
        var step = claimed(change.operation(), ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
        rejectProofWrite("stripe_plan_results", () -> recordResult(change, step, "in_AtomicResult"));
        assertThat(steps.findById(step.id())).contains(step);
        assertThat(proofs.result(account.id(), change.operation().id())).isEmpty();
        var result = recordResult(change, step, "in_AtomicResult");
        var other = plan(paidOriginal().subscription(), ChangeTiming.IMMEDIATE, "result-duplicate-invoice");
        var otherStep = claimed(other.operation(), ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
        sqlState("23505", () -> recordResult(other, otherStep, result.invoice()));
        assertThat(steps.findById(otherStep.id())).contains(otherStep);
        assertThat(proofs.result(account.id(), other.operation().id())).isEmpty();
        assertThat(recordResult(other, otherStep, "in_OtherAtomicResult").invoice()).isEqualTo("in_OtherAtomicResult");
    }

    @Test void resultReceiptRejectsStaleOwnerOperationSubscriptionPolicyAndAccountMode() {
        var change = plan(original.subscription(), ChangeTiming.IMMEDIATE, "result-fences");
        var step = claimed(change.operation(), ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
        providerConflict(() -> proofs.recordResult(change.subscription(), change.operation(), change.quote(), step, "foreign-owner", "in_Fenced", null, "il_Credit", "il_Debit"));
        var o = change.operation();
        var stale = new SubscriptionOperation(o.id(), o.siteId(), o.customerId(), o.subscriptionId(), o.kind(), o.status(), o.authorityScope(), o.keyDigest(), o.fingerprint(), o.providerOperationKey(), o.expectedRevision(), o.policyVersion(), o.providerReference(), o.safeFailureCode(), o.createdAt(), o.updatedAt(), o.version() + 1);
        providerConflict(() -> proofs.recordResult(change.subscription(), stale, change.quote(), step, OWNER, "in_Fenced", null, "il_Credit", "il_Debit"));
        providerConflict(() -> proofs.recordResult(original.subscription(), o, change.quote(), step, OWNER, "in_Fenced", null, "il_Credit", "il_Debit"));
        jdbc.update("update payment_accounts set account_mode='LIVE' where id=?", account.id().value());
        try { providerConflict(() -> recordResult(change, step, "in_Fenced")); }
        finally { jdbc.update("update payment_accounts set account_mode='SANDBOX' where id=?", account.id().value()); }
        jdbc.update("update payment_accounts set provider='FAKE' where id=?", account.id().value());
        try { providerConflict(() -> recordResult(change, step, "in_Fenced")); }
        finally { jdbc.update("update payment_accounts set provider='STRIPE' where id=?", account.id().value()); }
        jdbc.update("update site_subscription_policies set version=version+1 where site_id=?", site.value());
        providerConflict(() -> recordResult(change, step, "in_Fenced"));
        assertThat(steps.findById(step.id())).contains(step);
        assertThat(proofs.result(account.id(), o.id())).isEmpty();
        assertUnchangedEntitlement(change);
    }

    @Test void concurrentSameKeyReservationKeepsOneOriginalIntentAndProviderDispatchIdentity() throws Exception {
        var prepared = preview(original.subscription(), ChangeTiming.IMMEDIATE, "concurrent-original-key");
        var gate = new CyclicBarrier(2);
        SubscriptionPlanTransaction.Reserved first;
        SubscriptionPlanTransaction.Reserved second;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); return reserveOnly(prepared); });
            var b = pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); return reserveOnly(prepared); });
            first = a.get(20, TimeUnit.SECONDS); second = b.get(20, TimeUnit.SECONDS);
        }
        assertThat(first.operation().id()).isEqualTo(second.operation().id());
        assertThat(List.of(first.owner(), second.owner())).containsExactlyInAnyOrder(true, false);
        var owner = first.owner() ? first : second;
        plans.beforeMutation(prepared.context(), owner, true);
        var operation = op(owner.operation().id());
        var step = reserveStep(operation, ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
        assertThat(reserveStep(operation, ProviderOperation.Kind.SUBSCRIPTION_UPDATE)).isEqualTo(step);
        assertThat(reserveOnly(prepared).operation().id()).isEqualTo(operation.id());
        assertThat(count("subscription_change_intents")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where resource_id=? and kind='SUBSCRIPTION_UPDATE'", Integer.class, operation.id())).isEqualTo(1);
        assertThat(sub(original.subscription().id()).entitledPrice()).isEqualTo(original.subscription().entitledPrice());
    }

    @Test void concurrentResultWritersHaveOneReceiptWinnerAndNoSecondAcknowledgement() throws Exception {
        var change = plan(original.subscription(), ChangeTiming.IMMEDIATE, "concurrent-result");
        var step = claimed(change.operation(), ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> resultAfterGate(gate, change, step, "in_ConcurrentOne"));
            var second = pool.submit(() -> resultAfterGate(gate, change, step, "in_ConcurrentTwo"));
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        var result = proofs.result(account.id(), change.operation().id()).orElseThrow();
        assertThat(result.invoice()).isIn("in_ConcurrentOne", "in_ConcurrentTwo");
        assertThat(count("stripe_plan_results")).isEqualTo(1);
        assertThat(steps.findById(step.id()).orElseThrow().version()).isEqualTo(step.version() + 1);
        assertUnchangedEntitlement(change);
    }

    @Test void planReceiptWaitsForStepBeforeAccountWhileOrdinaryAckOwnsTheStep() throws Exception {
        var change = plan(original.subscription(), ChangeTiming.IMMEDIATE, "plan-lock-order");
        var step = claimed(change.operation(), ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
        var pool = Executors.newFixedThreadPool(2);
        try (var holder = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            holder.setAutoCommit(false);
            try {
                int holderPid = backendPid(holder);
                try (var lock = holder.prepareStatement("select id from payment_accounts where id=? for update")) {
                    lock.setObject(1, account.id().value());
                    try (var row = lock.executeQuery()) { assertThat(row.next()).isTrue(); }
                }
                var ordinary = pool.submit(() -> steps.acknowledge(step.id(), OWNER, step.version(), change.subscription().providerReference()));
                int ordinaryPid = awaitBlockedQuery(holderPid, "payment_accounts");
                var receipt = pool.submit(() -> {
                    try { recordResult(change, step, "in_ContendedResult"); return "unexpected receipt"; }
                    catch (PaymentApplicationException expected) { return expected.code(); }
                });
                try { awaitBlockedQuery(ordinaryPid, "provider_operations"); }
                finally { holder.rollback(); }
                assertThat(ordinary.get(10, TimeUnit.SECONDS).status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
                assertThat(receipt.get(10, TimeUnit.SECONDS)).isEqualTo("PROVIDER_OPERATION_CONFLICT");
                assertThat(proofs.result(account.id(), change.operation().id())).isEmpty();
                assertUnchangedEntitlement(change);
            } finally { holder.rollback(); }
        } finally {
            pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void ownedScheduleCreateConfigureAndClearKeepOriginalQuoteAndReleaseOnlyItsNativeReference() {
        var change = plan(original.subscription(), ChangeTiming.NEXT_PERIOD, "schedule-lifecycle");
        var created = createSchedule(change, "sub_sched_Owned");
        assertThat(created.updateStep()).isNull(); assertThat(created.releaseStep()).isNull();
        var update = claimed(change.operation(), ProviderOperation.Kind.SCHEDULE_UPDATE);
        var configured = proofs.recordScheduleUpdate(change.subscription(), change.operation(), created, update, OWNER, targetEnd(change));
        assertThat(configured.creationStep()).isEqualTo(created.creationStep());
        assertThat(configured.reference()).isEqualTo(created.reference());
        assertThat(configured.updateStep()).isEqualTo(update.id());
        assertThat(configured.targetEnd()).isEqualTo(targetEnd(change));
        var clear = clear(change);
        var release = claimed(clear.operation(), ProviderOperation.Kind.SCHEDULE_RELEASE);
        var released = proofs.recordRelease(clear.subscription(), clear.operation(), configured, release, OWNER);
        assertThat(released.operation()).isEqualTo(change.operation().id());
        assertThat(released.clearOperation()).isEqualTo(clear.operation().id());
        assertThat(released.releaseStep()).isEqualTo(release.id());
        assertThat(proofs.scheduleReference(account.id(), created.reference())).contains(released);
        assertThat(proofs.schedule(new PaymentAccountId(UUID.randomUUID()), change.operation().id())).isEmpty();
        assertThat(tx.required(() -> commonQuotes.forOperation(site, change.subscription().id(), clear.operation().id()))).contains(change.common());
        assertThat(steps.findById(release.id()).orElseThrow().providerReference()).isEqualTo(created.reference());
        assertThat(sub(change.subscription().id()).pendingChange().operationId()).isEqualTo(change.operation().id());
        assertUnchangedEntitlement(change);
    }

    @Test void scheduleSqlRejectsPartialConfigurationReleaseAndMutationOfBoundGroups() {
        var change = plan(original.subscription(), ChangeTiming.NEXT_PERIOD, "schedule-groups");
        var created = createSchedule(change, "sub_sched_Groups");
        var update = acknowledge(reserveStep(change.operation(), ProviderOperation.Kind.SCHEDULE_UPDATE), created.reference());
        sqlState("23514", () -> jdbc.update("update stripe_plan_schedules set update_step=? where operation_id=?", update.id(), change.operation().id()));
        sqlState("23514", () -> jdbc.update("update stripe_plan_schedules set target_end=? where operation_id=?", targetEnd(change), change.operation().id()));
        sqlState("23514", () -> jdbc.update("update stripe_plan_schedules set update_step=?,target_end=phase_start where operation_id=?", update.id(), change.operation().id()));
        sqlState("23514", () -> jdbc.update("update stripe_plan_schedules set clear_operation=?,release_step=? where operation_id=?", change.operation().id(), update.id(), change.operation().id()));
        jdbc.update("update stripe_plan_schedules set update_step=?,target_end=? where operation_id=?", update.id(), targetEnd(change), change.operation().id());
        var configured = proofs.schedule(account.id(), change.operation().id()).orElseThrow();
        sqlState("23514", () -> jdbc.update("update stripe_plan_schedules set update_step=null,target_end=null where operation_id=?", change.operation().id()));
        sqlState("23514", () -> jdbc.update("update stripe_plan_schedules set target_end=target_end+1 where operation_id=?", change.operation().id()));
        var clear = clear(change);
        var release = acknowledge(reserveStep(clear.operation(), ProviderOperation.Kind.SCHEDULE_RELEASE), created.reference());
        sqlState("23514", () -> jdbc.update("update stripe_plan_schedules set clear_operation=? where operation_id=?", clear.operation().id(), change.operation().id()));
        sqlState("23514", () -> jdbc.update("update stripe_plan_schedules set release_step=? where operation_id=?", release.id(), change.operation().id()));
        jdbc.update("update stripe_plan_schedules set clear_operation=?,release_step=? where operation_id=?", clear.operation().id(), release.id(), change.operation().id());
        for (String assignment : List.of("clear_operation=null,release_step=null", "phase_start=phase_start+1", "schedule_reference='sub_sched_Replaced'", "quote_id='" + UUID.randomUUID() + "'")) {
            sqlState("23514", () -> jdbc.update("update stripe_plan_schedules set " + assignment + " where operation_id=?", change.operation().id()));
        }
        assertThat(proofs.schedule(account.id(), change.operation().id()).orElseThrow().updateStep()).isEqualTo(configured.updateStep());
    }

    @Test void scheduleSqlBindsOriginalOwnersCreationKindStepReferenceAndCommonQuote() {
        var change = plan(original.subscription(), ChangeTiming.NEXT_PERIOD, "schedule-sql");
        var reserved = reserveStep(change.operation(), ProviderOperation.Kind.SCHEDULE_CREATE);
        var pending = scheduleRow(change, reserved, "sub_sched_Sql");
        sqlState("23503", () -> insert("stripe_plan_schedules", pending));
        var step = acknowledge(reserved, "sub_sched_Sql");
        var valid = scheduleRow(change, step, "sub_sched_Sql");
        var other = plan(paidOriginal().subscription(), ChangeTiming.NEXT_PERIOD, "schedule-sql-other");
        var otherSchedule = createSchedule(other, "sub_sched_Foreign");
        for (var owner : foreignOwners(other.subscription()).entrySet()) reject("stripe_plan_schedules", valid, owner.getKey(), owner.getValue(), "23503");
        reject("stripe_plan_schedules", valid, "common_quote_id", other.common().id(), "23503");
        reject("stripe_plan_schedules", valid, "operation_id", original.operation().id(), "23503");
        reject("stripe_plan_schedules", valid, "creation_kind", "SUBSCRIPTION_CREATE", "23514");
        reject("stripe_plan_schedules", valid, "creation_step", otherSchedule.creationStep(), "23503");
        reject("stripe_plan_schedules", valid, "schedule_reference", "sub_sched_Unbound", "23503");
        reject("stripe_plan_schedules", valid, "phase_start", 0L, "23514");
        var crossQuote = new LinkedHashMap<>(valid);
        crossQuote.put("quote_id", other.quote().id()); crossQuote.put("quote_reference", other.quote().reference());
        sqlState("23503", () -> insert("stripe_plan_schedules", crossQuote));
        insert("stripe_plan_schedules", valid);
        var update = acknowledge(reserveStep(change.operation(), ProviderOperation.Kind.SCHEDULE_UPDATE), "sub_sched_Sql");
        var otherUpdate = acknowledge(reserveStep(other.operation(), ProviderOperation.Kind.SCHEDULE_UPDATE), otherSchedule.reference());
        sqlState("23503", () -> jdbc.update("update stripe_plan_schedules set update_step=?,target_end=? where operation_id=?", otherUpdate.id(), targetEnd(change), change.operation().id()));
        jdbc.update("update stripe_plan_schedules set update_step=?,target_end=? where operation_id=?", update.id(), targetEnd(change), change.operation().id());
        var clear = clear(change);
        var release = acknowledge(reserveStep(clear.operation(), ProviderOperation.Kind.SCHEDULE_RELEASE), "sub_sched_Sql");
        sqlState("23503", () -> jdbc.update("update stripe_plan_schedules set clear_operation=?,release_step=? where operation_id=?", other.operation().id(), release.id(), change.operation().id()));
        jdbc.update("update stripe_plan_schedules set clear_operation=?,release_step=? where operation_id=?", clear.operation().id(), release.id(), change.operation().id());
    }

    @Test void failedScheduleCreateConfigureAndReleaseEachRollBackTheirExactAcknowledgement() {
        var change = plan(original.subscription(), ChangeTiming.NEXT_PERIOD, "schedule-atomic");
        var create = claimed(change.operation(), ProviderOperation.Kind.SCHEDULE_CREATE);
        rejectProofWrite("stripe_plan_schedules", () -> proofs.recordSchedule(change.subscription(), change.operation(), change.quote(), create, OWNER, "sub_sched_Atomic", SOURCE_PERIOD.start().getEpochSecond(), new SourcePhase("none", null, "charge_automatically")));
        assertThat(steps.findById(create.id())).contains(create);
        assertThat(proofs.schedule(account.id(), change.operation().id())).isEmpty();
        var created = proofs.recordSchedule(change.subscription(), change.operation(), change.quote(), create, OWNER, "sub_sched_Atomic", SOURCE_PERIOD.start().getEpochSecond(), new SourcePhase("none", null, "charge_automatically"));
        var update = claimed(change.operation(), ProviderOperation.Kind.SCHEDULE_UPDATE);
        rejectProofWrite("stripe_plan_schedules", () -> proofs.recordScheduleUpdate(change.subscription(), change.operation(), created, update, OWNER, targetEnd(change)));
        assertThat(steps.findById(update.id())).contains(update);
        assertThat(proofs.schedule(account.id(), change.operation().id())).contains(created);
        var configured = proofs.recordScheduleUpdate(change.subscription(), change.operation(), created, update, OWNER, targetEnd(change));
        var clear = clear(change);
        var release = claimed(clear.operation(), ProviderOperation.Kind.SCHEDULE_RELEASE);
        rejectProofWrite("stripe_plan_schedules", () -> proofs.recordRelease(clear.subscription(), clear.operation(), configured, release, OWNER));
        assertThat(steps.findById(release.id())).contains(release);
        assertThat(proofs.schedule(account.id(), change.operation().id())).contains(configured);
        var released = proofs.recordRelease(clear.subscription(), clear.operation(), configured, release, OWNER);
        assertThat(released.releaseStep()).isEqualTo(release.id());
        assertUnchangedEntitlement(change);
    }

    @Test void crossQuoteScheduleCannotBeConfiguredOrReleasedAndOtherKindsRetainReferenceUniqueness() {
        var change = plan(original.subscription(), ChangeTiming.NEXT_PERIOD, "schedule-exact-owner");
        var created = createSchedule(change, "sub_sched_Unique");
        var other = plan(paidOriginal().subscription(), ChangeTiming.NEXT_PERIOD, "schedule-foreign-owner");
        var colliding = claimed(other.operation(), ProviderOperation.Kind.SCHEDULE_CREATE);
        providerConflict(() -> steps.acknowledge(colliding.id(), OWNER, colliding.version(), created.reference()));
        assertThat(steps.findById(colliding.id())).contains(colliding);
        var otherCreated = proofs.recordSchedule(other.subscription(), other.operation(), other.quote(), colliding, OWNER, "sub_sched_Other", SOURCE_PERIOD.start().getEpochSecond(), new SourcePhase("none", null, "charge_automatically"));
        var update = claimed(change.operation(), ProviderOperation.Kind.SCHEDULE_UPDATE);
        providerConflict(() -> proofs.recordScheduleUpdate(change.subscription(), change.operation(), otherCreated, update, OWNER, targetEnd(change)));
        assertThat(steps.findById(update.id())).contains(update);
        var configured = proofs.recordScheduleUpdate(change.subscription(), change.operation(), created, update, OWNER, targetEnd(change));
        var clear = clear(change);
        var release = claimed(clear.operation(), ProviderOperation.Kind.SCHEDULE_RELEASE);
        providerConflict(() -> proofs.recordRelease(clear.subscription(), clear.operation(), otherCreated, release, OWNER));
        assertThat(steps.findById(release.id())).contains(release);
        assertThat(proofs.recordRelease(clear.subscription(), clear.operation(), configured, release, OWNER).reference()).isEqualTo(created.reference());
    }

    @Test void anonymizationRetainsQuotesFinalLinesSchedulesAndOriginalAcknowledgedReceipts() {
        var change = plan(original.subscription(), ChangeTiming.IMMEDIATE, "privacy-result");
        var result = recordResult(change, claimed(change.operation(), ProviderOperation.Kind.SUBSCRIPTION_UPDATE), "in_Privacy");
        finish(change.operation());
        var deferred = plan(paidOriginal().subscription(), ChangeTiming.NEXT_PERIOD, "privacy-schedule");
        var created = createSchedule(deferred, "sub_sched_Privacy");
        var configured = proofs.recordScheduleUpdate(deferred.subscription(), deferred.operation(), created, claimed(deferred.operation(), ProviderOperation.Kind.SCHEDULE_UPDATE), OWNER, targetEnd(deferred));
        var clear = clear(deferred);
        var released = proofs.recordRelease(clear.subscription(), clear.operation(), configured, claimed(clear.operation(), ProviderOperation.Kind.SCHEDULE_RELEASE), OWNER);
        finish(clear.operation());
        for (var s : List.of(sub(change.subscription().id()), sub(deferred.subscription().id()))) subscriptions.save(s.cancel("CONFIRMED", INITIAL_TIME), s.revision());
        var receipts = jdbc.queryForList("select * from provider_operations order by id");
        var descriptors = jdbc.queryForList("select * from stripe_plan_quotes order by id");
        var erased = anonymizer.anonymize(new AnonymizeCustomerUseCase.Command(site, customer.id(), customer.id().value(), "erase-plan-proofs", "ADMIN", "00000000-0000-0000-0000-000000000001", "task8-proof-privacy", "127.0.0.1"));
        assertThat(erased.status()).isEqualTo(CustomerStatus.ANONYMIZED);
        assertThat(jdbc.queryForList("select * from provider_operations order by id")).isEqualTo(receipts);
        assertThat(jdbc.queryForList("select * from stripe_plan_quotes order by id")).isEqualTo(descriptors);
        assertThat(proofs.quote(account.id(), change.quote().id())).contains(change.quote());
        assertThat(proofs.result(account.id(), change.operation().id())).contains(result);
        assertThat(proofs.schedule(account.id(), deferred.operation().id())).contains(released);
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema=current_schema() and table_name in ('stripe_plan_quotes','stripe_plan_results','stripe_plan_schedules')", String.class))
            .doesNotContain("client_secret", "payload", "email", "provider_status", "paid");
    }

    @Test void changeGrantPersistsItsExactOriginalResultAndRetainsVersionedDescriptor() {
        var owned = changeGrantCase("grant-original");
        reserveGrant(owned.grant());
        var stored = storedGrant(owned.grant());
        assertThat(stored.binding()).isEqualTo(owned.grant().binding());
        assertThat(stored.binding().schema()).isEqualTo("change-v1");
        assertThat(stored.binding().stepKind()).isEqualTo("SUBSCRIPTION_UPDATE");
        assertThat(stored.binding().flow()).isEqualTo(SubscriptionClientSession.Flow.PAYMENT);
        assertThat(stored.descriptor()).isEqualTo(owned.grant().descriptor());
        assertThat(jdbc.queryForObject("select billing_change_operation from subscription_action_capabilities where id=?", UUID.class, stored.id())).isEqualTo(owned.change().operation().id());
        assertThat(jdbc.queryForObject("select i.quote_id from subscription_action_capabilities a join stripe_plan_results r on r.operation_id=a.billing_change_operation join subscription_change_intents i on i.operation_id=r.operation_id where a.id=? and r.quote_id=? and r.step_id=? and r.invoice_reference=?", UUID.class,
            stored.id(), owned.change().quote().id(), stored.binding().stepId(), stored.binding().invoiceReference())).isEqualTo(owned.change().common().id());
        sqlState("23514", () -> jdbc.update("update subscription_action_capabilities set billing_change_operation=null where id=?", stored.id()));
        sqlState("23514", () -> jdbc.update("update subscription_action_capabilities set billing_invoice_reference='in_ReplacedGrant' where id=?", stored.id()));
        assertUnchangedEntitlement(owned.change());
    }

    @Test void changeGrantNeedsTheStoredResultAndCannotUseACreateDescriptorOrForeignActualResult() {
        var change = plan(original.subscription(), ChangeTiming.IMMEDIATE, "grant-no-result");
        var step = acknowledge(changeStep(change), change.subscription().providerReference());
        var noResult = grant(change.subscription(), change.operation(), paymentBinding(change, step.id(), "in_MissingResult", "change-v1"));
        subscriptionConflict(() -> reserveGrant(noResult.grant()));
        var createDescriptor = grant(change.subscription(), change.operation(), paymentBinding(change, step.id(), "in_MissingResult", "payment-v1"));
        subscriptionConflict(() -> reserveGrant(createDescriptor.grant()));
        var other = changeGrantCase(paidOriginal().subscription(), "grant-foreign-result");
        var foreign = grant(change.subscription(), change.operation(), new SubscriptionClientGrant.Binding(other.grant().binding().stepId(), change.operation().providerOperationKey(), other.grant().binding().proof(),
            change.subscription().providerReference(), customerReference, other.grant().binding().invoiceReference(), nativeReference("inpay_"), nativeReference("pi_"), "change-v1", null));
        subscriptionConflict(() -> reserveGrant(foreign.grant()));
        assertThat(count("subscription_action_capabilities")).isZero();
        assertThat(proofs.result(account.id(), change.operation().id())).isEmpty();
        assertUnchangedEntitlement(change);
    }

    @Test void changeGrantSqlRejectsForeignResultInvoiceStepOwnersAndMissingDiscriminant() {
        var owned = changeGrantCase("grant-sql-owner");
        var other = changeGrantCase(paidOriginal().subscription(), "grant-sql-foreign");
        reserveGrant(owned.grant());
        var valid = jdbc.queryForMap("select * from subscription_action_capabilities where id=?", owned.grant().id());
        rejectGrant(valid, "billing_change_operation", null, "23514");
        rejectGrant(valid, "billing_change_operation", other.change().operation().id(), "23514");
        rejectGrant(valid, "billing_invoice_reference", other.grant().binding().invoiceReference(), "23503");
        rejectGrant(valid, "billing_step_id", other.grant().binding().stepId(), "23503");
        rejectGrant(valid, "billing_schema", "payment-v1", "23514");
        rejectGrant(valid, "consent_purpose", "CREATE", "23514");
        var foreignReference = grantCopy(valid);
        foreignReference.put("billing_subscription_reference", other.change().subscription().providerReference());
        foreignReference.put("billing_step_reference", other.change().subscription().providerReference());
        sqlState("23503", () -> insert("subscription_action_capabilities", foreignReference));
        for (var owner : foreignOwners(other.change().subscription()).entrySet()) rejectGrant(valid, owner.getKey(), owner.getValue(), "23503");
        var foreignQuote = new LinkedHashMap<>(valid);
        foreignQuote.put("id", UUID.randomUUID()); foreignQuote.put("token_digest", StripeBillingGrantTokens.hash(nativeReference("grant_")));
        foreignQuote.put("superseded_at", valid.get("expires_at"));
        foreignQuote.put("operation_id", other.change().operation().id()); foreignQuote.put("billing_change_operation", other.change().operation().id());
        foreignQuote.put("subscription_id", other.change().subscription().id()); foreignQuote.put("billing_operation_key", other.change().operation().providerOperationKey());
        sqlState("23503", () -> insert("subscription_action_capabilities", foreignQuote));
        assertThat(count("subscription_action_capabilities")).isEqualTo(1);
    }

    @Test void configuredChangeClientSessionAndConfirmationPermitOnlyTheNewConsumedClaim() {
        var workflow = grantWorkflow("change-client-new");
        var issued = clientSessionUseCase.issue(clientCommand(workflow, workflow.context()));
        assertThat(issued.flow()).isEqualTo(SubscriptionClientSession.Flow.PAYMENT);
        assertThat(issued.actionToken()).isEqualTo(workflow.owned().raw());
        assertThat(issued.clientSecret()).isEqualTo(workflow.secret());
        var session = ((SubscriptionActor.Customer) workflow.context().actor()).session();
        assertThat(storedGrant(workflow.owned().grant()).sessionId()).isEqualTo(session.sessionId().value());
        assertThat(storedGrant(workflow.owned().grant()).origin()).isEqualTo(session.origin());
        var command = confirmationCommand(workflow, workflow.context());
        var first = confirmations.confirm(command);
        var replay = confirmations.confirm(command);
        assertThat(first.browserConfirmAllowed()).isTrue();
        assertThat(replay.browserConfirmAllowed()).isFalse();
        assertThat(first.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        assertThat(storedGrant(workflow.owned().grant()).consumedAt()).isNotNull();
        assertThat(storedGrant(workflow.owned().grant()).descriptor()).isEqualTo(workflow.owned().grant().descriptor());
        subscriptionConflict(() -> clientSessionUseCase.issue(clientCommand(workflow, workflow.context())));
        var changedKey = new SubscriptionCommandContext(workflow.context().actor(), site, customer.id(), workflow.context().origin(), "another-confirmation", "task8-change-grant");
        subscriptionConflict(() -> confirmations.confirm(confirmationCommand(workflow, changedKey)));
        verify(workflow.provider(), times(1)).confirmAction(any(), eq(workflow.owned().raw()));
        assertThat(jdbc.queryForObject("select row_to_json(a)::text from subscription_action_capabilities a where id=?", String.class, workflow.owned().grant().id())).doesNotContain(workflow.owned().raw(), workflow.secret());
        assertUnchangedEntitlement(workflow.owned().change());
    }

    @Test void lostChangeConfirmationAckConsumesItsGrantAndNeverReturnsNewBrowserPermission() {
        var workflow = grantWorkflow("change-client-lost-ack");
        clientSessionUseCase.issue(clientCommand(workflow, workflow.context()));
        doThrow(new IllegalStateException("lost original CHANGE acknowledgement")).when(workflow.provider()).confirmAction(any(), eq(workflow.owned().raw()));
        var command = confirmationCommand(workflow, workflow.context());
        var first = confirmations.confirm(command);
        assertThat(first.browserConfirmAllowed()).isFalse();
        assertThat(first.status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);
        assertThat(confirmations.confirm(command).browserConfirmAllowed()).isFalse();
        assertThat(storedGrant(workflow.owned().grant()).consumedAt()).isNotNull();
        verify(workflow.provider(), times(1)).confirmAction(any(), eq(workflow.owned().raw()));
        assertUnchangedEntitlement(workflow.owned().change());
    }

    @Test void concurrentChangeConfirmationsHaveOneNewPermissionAndOneProviderContinuation() throws Exception {
        var workflow = grantWorkflow("change-client-concurrent");
        clientSessionUseCase.issue(clientCommand(workflow, workflow.context()));
        var gate = new CyclicBarrier(2);
        var command = confirmationCommand(workflow, workflow.context());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); return confirmations.confirm(command).browserConfirmAllowed(); });
            var b = pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); return confirmations.confirm(command).browserConfirmAllowed(); });
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        verify(workflow.provider(), times(1)).confirmAction(any(), eq(workflow.owned().raw()));
        assertThat(storedGrant(workflow.owned().grant()).consumedAt()).isNotNull();
        assertUnchangedEntitlement(workflow.owned().change());
    }

    @Test void changeClientMaterialStaysBoundToItsOriginalCustomerSessionAndOrigin() {
        var workflow = grantWorkflow("change-client-session-owner");
        clientSessionUseCase.issue(clientCommand(workflow, workflow.context()));
        var anotherSession = new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site, customer.id(), "https://facts.test").rawToken(), "https://facts.test"));
        var anotherContext = new SubscriptionCommandContext(anotherSession, site, customer.id(), "https://facts.test", "other-session", "task8-change-grant");
        subscriptionConflict(() -> clientSessionUseCase.issue(clientCommand(workflow, anotherContext)));
        jdbc.update("insert into site_allowed_origins values (?,'https://plan-other.test')", site.value());
        var otherOrigin = new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site, customer.id(), "https://plan-other.test").rawToken(), "https://plan-other.test"));
        var otherOriginContext = new SubscriptionCommandContext(otherOrigin, site, customer.id(), "https://plan-other.test", "other-origin", "task8-change-grant");
        subscriptionConflict(() -> clientSessionUseCase.issue(clientCommand(workflow, otherOriginContext)));
        var otherCustomer = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, grantNow()));
        var foreignActor = new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site, otherCustomer.id(), "https://facts.test").rawToken(), "https://facts.test"));
        var foreign = new SubscriptionCommandContext(foreignActor, site, otherCustomer.id(), "https://facts.test", "foreign-customer", "task8-change-grant");
        assertThatThrownBy(() -> clientSessionUseCase.issue(clientCommand(workflow, foreign))).isInstanceOfSatisfying(PaymentApplicationException.class, failure -> assertThat(failure.code()).isEqualTo("SUBSCRIPTION_NOT_FOUND"));
        verify(clientSessions, times(1)).retrieve(any(), any(), eq(workflow.owned().raw()));
        assertThat(storedGrant(workflow.owned().grant()).consumedAt()).isNull();
        assertUnchangedEntitlement(workflow.owned().change());
    }

    @Test void legacyPaymentSetupAndResumeGrantsRetainNullChangeAuthorityAndConsumedCanonicalBytes() {
        refreshGrantActor();
        var s = original.subscription();
        var createBinding = new SubscriptionClientGrant.Binding(original.creationStep().id(), original.operation().providerOperationKey(), "a".repeat(64), s.providerReference(), customerReference, "in_LegacyCreate", "inpay_LegacyCreate", "pi_LegacyCreate");
        assertLegacyGrant(grant(s, original.operation(), createBinding));
        var setup = legacyOperation(SubscriptionOperation.Kind.SETUP, s);
        var setupStep = acknowledge(steps.reserve(account, ProviderOperation.Kind.SETUP_INTENT_CREATE, setup.id(), setup.providerOperationKey() + ":SETUP", "legacy-setup-" + setup.id()), "seti_LegacySetup");
        billingProofs.recordSetup(s, setup, setupStep.id(), setupStep.providerReference());
        var setupBinding = new SubscriptionClientGrant.Binding(setupStep.id(), setup.providerOperationKey(), "b".repeat(64), s.providerReference(), customerReference, null, null, null, "setup-v1", setupStep.providerReference());
        assertLegacyGrant(grant(s, setup, setupBinding));
        finish(setup);
        subscriptions.save(s.pause(INITIAL_TIME), s.revision());
        var paused = sub(s.id());
        var resume = legacyOperation(SubscriptionOperation.Kind.RESUME, paused);
        var resumeStep = acknowledge(reserveStep(resume, ProviderOperation.Kind.SUBSCRIPTION_UPDATE), paused.providerReference());
        billingProofs.recordResume(paused, resume, resumeStep.id(), "in_LegacyResume", new Subscription.Period(SOURCE_PERIOD.end(), TARGET_PHASE_END));
        var resumeBinding = new SubscriptionClientGrant.Binding(resumeStep.id(), resume.providerOperationKey(), "c".repeat(64), paused.providerReference(), customerReference, "in_LegacyResume", "inpay_LegacyResume", "pi_LegacyResume", "resume-v1", null);
        assertLegacyGrant(grant(paused, resume, resumeBinding));
        assertThat(jdbc.queryForObject("select count(*) from subscription_action_capabilities where billing_change_operation is not null", Integer.class)).isZero();
        assertThat(sub(s.id()).entitledPrice()).isEqualTo(original.subscription().entitledPrice());
        assertThat(count("payments")).isZero();
    }

    @Test void originalChangeReplayKeepsItsExpiredRetainedQuoteWithoutNewOperationQuoteOrEvent() {
        var replay = replayCase("original-replay-expired", false);
        plans.beforeOriginalReplay(replay.prepared().context(), replay.reserved());
        plans.reply(replay.reserved(), new Reply(Outcome.UNKNOWN, null, null));
        var before = replayRows();
        TestClock.set(replay.prepared().common().expiresAt().plusSeconds(1));
        var original = reserveOnly(replay.prepared());
        assertThat(original.owner()).isFalse();
        assertThat(original.operation().id()).isEqualTo(replay.reserved().operation().id());
        assertThat(original.operation().status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);
        assertThat(original.quote()).isEqualTo(replay.prepared().common());
        assertThat(tx.required(() -> commonQuotes.forOperation(site, original.subscription().id(), original.operation().id()))).contains(replay.prepared().common());
        plans.beforeOriginalReplay(replay.prepared().context(), original);
        assertThat(replayRows()).isEqualTo(before);
        assertThat(sub(original.subscription().id()).entitledPrice()).isEqualTo(replay.prepared().quote().entitled());
        assertThat(count("payments")).isZero();
        // Native dispatch/lease/retry-window decisions belong to the provider adapter, outside this local phase.
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where resource_id=?", Integer.class, original.operation().id())).isZero();
    }

    @Test void originalReplayRejectsStaleOperationVersionAndForeignRetainedQuoteAccountOrSubscription() {
        var prepared = preview(original.subscription(), ChangeTiming.IMMEDIATE, "original-replay-binding");
        var alternate = preview(original.subscription(), ChangeTiming.IMMEDIATE, "original-replay-alternate");
        var owner = reserveOnly(prepared);
        plans.beforeMutation(prepared.context(), owner, true);
        var processing = reserveOnly(prepared);
        plans.reply(processing, new Reply(Outcome.UNKNOWN, null, null));
        replayStale(() -> plans.beforeOriginalReplay(prepared.context(), processing));
        var current = reserveOnly(prepared);
        replayStale(() -> plans.beforeOriginalReplay(prepared.context(), replayWith(current, current.subscription(), current.account(), alternate.common())));
        var other = paidOriginal();
        assertThatThrownBy(() -> plans.beforeOriginalReplay(prepared.context(), replayWith(current, other.subscription(), current.account(), current.quote())))
            .isInstanceOfSatisfying(PaymentApplicationException.class, failure -> assertThat(failure.code()).isEqualTo("SUBSCRIPTION_STATE_CONFLICT"));
        var foreign = foreignOwners(other.subscription());
        var otherAccount = accounts.findById(new PaymentAccountId((UUID) foreign.get("payment_account_id"))).orElseThrow();
        replayStale(() -> plans.beforeOriginalReplay(prepared.context(), replayWith(current, current.subscription(), otherAccount, current.quote())));
        assertThat(op(current.operation().id())).isEqualTo(current.operation());
        assertThat(tx.required(() -> commonQuotes.forOperation(site, current.subscription().id(), current.operation().id()))).contains(prepared.common());
        finish(current.operation());
        var completed = reserveOnly(prepared);
        assertThat(completed.operation().status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        replayStale(() -> plans.beforeOriginalReplay(prepared.context(), completed));
    }

    @Test void originalReplayRevalidatesCustomerSitePrincipalQuoteScopeAndRevokedSession() {
        var replay = replayCase("original-replay-authority", true);
        var c = replay.prepared().context();
        var r = replay.reserved();
        var other = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        var otherActor = new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site, other.id(), "https://facts.test").rawToken(), "https://facts.test"));
        var otherContext = new SubscriptionCommandContext(otherActor, site, other.id(), "https://facts.test", c.idempotencyKey(), "task8-replay-authority");
        replayStale(() -> plans.beforeOriginalReplay(otherContext, r));
        var foreign = foreignOwners(paidOriginal().subscription());
        var foreignSite = new dev.genericpayments.platform.sites.domain.SiteId((UUID) foreign.get("site_id"));
        var foreignSiteContext = new SubscriptionCommandContext(c.actor(), foreignSite, customer.id(), c.origin(), c.idempotencyKey(), "task8-replay-site");
        customerUnavailable(() -> plans.beforeOriginalReplay(foreignSiteContext, r));
        var key = new dev.genericpayments.platform.partners.domain.SiteApiKey(new dev.genericpayments.platform.partners.domain.SiteApiKeyId(UUID.randomUUID()), site,
            "abcdefghijkl", "$argon2id$fixture", Set.of(dev.genericpayments.platform.partners.domain.PartnerScope.SUBSCRIPTIONS_MANAGE),
            dev.genericpayments.platform.partners.domain.SiteApiKey.Status.ACTIVE, INITIAL_TIME, null, null, null);
        siteKeys.insert(key);
        var partner = new SubscriptionActor.Partner(new dev.genericpayments.platform.partners.application.port.in.AuthenticatedSite(site, key.id(), key.scopes()));
        var partnerContext = new SubscriptionCommandContext(partner, site, customer.id(), null, c.idempotencyKey(), "task8-replay-principal");
        replayStale(() -> plans.beforeOriginalReplay(partnerContext, r));
        jdbc.update("update subscription_quotes set authority_scope='foreign-plan-scope' where id=?", r.quote().id());
        try {
            var foreignScope = tx.required(() -> commonQuotes.lock(site, r.quote().id()));
            replayStale(() -> plans.beforeOriginalReplay(c, replayWith(r, r.subscription(), r.account(), foreignScope)));
        } finally { jdbc.update("update subscription_quotes set authority_scope=? where id=?", r.quote().authorityScope(), r.quote().id()); }
        jdbc.update("update sites set status='DISABLED' where id=?", site.value());
        try { customerUnavailable(() -> plans.beforeOriginalReplay(c, r)); }
        finally { jdbc.update("update sites set status='ACTIVE' where id=?", site.value()); }
        jdbc.update("update customers set status='ANONYMIZED',anonymized_at=? where id=?", Timestamp.from(INITIAL_TIME), customer.id().value());
        try { customerUnavailable(() -> plans.beforeOriginalReplay(c, r)); }
        finally { jdbc.update("update customers set status='ACTIVE',anonymized_at=null where id=?", customer.id().value()); }
        sessions.logout(((SubscriptionActor.Customer) c.actor()).session());
        assertThatThrownBy(() -> plans.beforeOriginalReplay(c, r)).isInstanceOfSatisfying(PaymentApplicationException.class, failure -> assertThat(failure.code()).isEqualTo("AUTHENTICATION_FAILED"));
        assertThat(op(r.operation().id())).isEqualTo(r.operation());
    }

    @Test void originalReplayRefusesFakeLiveDisabledAccountsAndChangedOrDisabledPolicy() {
        var replay = replayCase("original-replay-account-policy", true);
        for (var mutation : List.of(Map.entry("provider", "FAKE"), Map.entry("account_mode", "LIVE"), Map.entry("account_status", "DISABLED"))) {
            jdbc.update("update payment_accounts set " + mutation.getKey() + "=? where id=?", mutation.getValue(), account.id().value());
            try {
                var current = reserveOnly(replay.prepared()); // Real nonowner reservation captures the changed Account, avoiding a mere stale-object rejection.
                assertThat(current.owner()).isFalse();
                replayStale(() -> plans.beforeOriginalReplay(replay.prepared().context(), current));
            } finally { jdbc.update("update payment_accounts set provider='STRIPE',account_mode='SANDBOX',account_status='ACTIVE' where id=?", account.id().value()); }
        }
        var version = policies.load(site).version();
        jdbc.update("update site_subscription_policies set version=version+1 where site_id=?", site.value());
        try { replayStale(() -> plans.beforeOriginalReplay(replay.prepared().context(), reserveOnly(replay.prepared()))); }
        finally { jdbc.update("update site_subscription_policies set version=? where site_id=?", version, site.value()); }
        jdbc.update("update site_subscription_policies set enabled=false where site_id=?", site.value());
        try { replayStale(() -> plans.beforeOriginalReplay(replay.prepared().context(), reserveOnly(replay.prepared()))); }
        finally { jdbc.update("update site_subscription_policies set enabled=true where site_id=?", site.value()); }
        plans.beforeOriginalReplay(replay.prepared().context(), reserveOnly(replay.prepared()));
        assertThat(op(replay.reserved().operation().id())).isEqualTo(replay.reserved().operation());
    }

    @Test void originalChangeReplayRejectsPendingOwnerTargetEffectiveStateRevisionAndPaidBoundaryDrift() {
        var replay = replayCase("original-replay-pending", true);
        var r = replay.reserved();
        for (var mutation : List.<Map<String, Object>>of(
            Map.of("pending_operation_id", original.operation().id()),
            Map.of("pending_amount_minor", r.quote().target().regularAmount().minorUnits() + 1),
            Map.of("pending_effective_at", Timestamp.from(r.quote().effectiveAt().plusSeconds(1))),
            Map.of("pending_state", "SCHEDULED"),
            Map.of("revision", r.subscription().revision() + 1))) {
            rejectReplaySubscriptionMutation(replay, mutation);
        }
        TestClock.set(r.subscription().paidThrough());
        var fresh = replayContext(replay.prepared());
        var current = plans.reserve(fresh, r.subscription().id(), r.quote().id(), replay.prepared().candidate(), true, false);
        assertThat(current.owner()).isFalse();
        replayStale(() -> plans.beforeOriginalReplay(fresh, current));
        assertThat(sub(r.subscription().id()).pendingChange()).isEqualTo(r.subscription().pendingChange());
    }

    @Test void originalReplayRejectsRealDebtCancellationPausedAndTerminalSnapshots() {
        var replay = replayCase("original-replay-debt-terminal", true);
        var r = replay.reserved();
        var period = new Subscription.Period(SOURCE_PERIOD.end(), TARGET_PHASE_END);
        var debt = billingRecords.upsert(new BillingRecord(UUID.randomUUID(), site, r.subscription().id(), account.id(), "in_ReplayDebt", BillingRecord.Kind.RENEWAL,
            r.subscription().billingPrice(), null, period, r.subscription().billingPrice().regularAmount().minorUnits(), "EUR", BillingRecord.Settlement.OPEN, 0, INITIAL_TIME, INITIAL_TIME));
        rejectReplaySubscriptionMutation(replay, Map.of("debt_billing_record_id", debt.id(), "debt_period_start", Timestamp.from(period.start()), "debt_period_end", Timestamp.from(period.end()), "grace_deadline", Timestamp.from(SOURCE_PERIOD.end().plusSeconds(3 * 86400))));
        rejectReplaySubscriptionMutation(replay, Map.of("cancellation_effective_at", Timestamp.from(SOURCE_PERIOD.end())));
        rejectReplaySubscriptionMutation(replay, Map.of("billing_status", "PAUSED"));
        rejectReplaySubscriptionMutation(replay, Map.of("billing_status", "CANCELLED", "terminal_reason", "CONFIRMED"));
        plans.beforeOriginalReplay(replay.prepared().context(), r);
        assertThat(count("payments")).isZero();
        assertThat(sub(r.subscription().id()).entitledPrice()).isEqualTo(replay.prepared().quote().entitled());
    }

    @Test void originalClearReplayRetainsItsExpiredQuoteAndRejectsForeignPendingOwnerOrBoundary() {
        var change = plan(original.subscription(), ChangeTiming.NEXT_PERIOD, "clear-replay-original");
        var created = createSchedule(change, "sub_sched_ReplayClear");
        proofs.recordScheduleUpdate(change.subscription(), change.operation(), created, claimed(change.operation(), ProviderOperation.Kind.SCHEDULE_UPDATE), OWNER, targetEnd(change));
        var clear = clear(change);
        var c = context("clear-" + change.operation().id());
        var candidate = plans.candidate(c, clear.subscription().id());
        TestClock.set(change.common().expiresAt().plusSeconds(1));
        var replay = plans.reserve(c, clear.subscription().id(), null, candidate, true, true);
        assertThat(replay.owner()).isFalse();
        assertThat(replay.operation().id()).isEqualTo(clear.operation().id());
        assertThat(replay.quote()).isEqualTo(change.common());
        assertThat(tx.required(() -> commonQuotes.forOperation(site, replay.subscription().id(), replay.operation().id()))).contains(change.common());
        assertThat(tx.required(() -> commonQuotes.forOperation(site, replay.subscription().id(), change.operation().id()))).contains(change.common());
        var before = replayRows();
        plans.beforeOriginalReplay(c, replay);
        assertThat(replayRows()).isEqualTo(before);
        jdbc.update("update subscriptions set pending_operation_id=? where id=?", original.operation().id(), replay.subscription().id());
        try { replayStale(() -> plans.beforeOriginalReplay(c, replay)); }
        finally { jdbc.update("update subscriptions set pending_operation_id=? where id=?", change.operation().id(), replay.subscription().id()); }
        TestClock.set(replay.subscription().pendingChange().effectiveAt());
        var fresh = replayContext(new Prepared(replay.subscription(), c, candidate, change.quote(), change.common()));
        replayStale(() -> plans.beforeOriginalReplay(fresh, replay));
        assertThat(proofs.schedule(account.id(), change.operation().id()).orElseThrow().releaseStep()).isNull();
        assertThat(sub(replay.subscription().id()).pendingChange().operationId()).isEqualTo(change.operation().id());
        assertUnchangedEntitlement(change);
    }

    @Test void configuredScheduleSourcePhaseReloadsFiniteModesAndOptionalPaymentMethod() {
        var phases = List.of(new SourcePhase("none", null, "charge_automatically"),
            new SourcePhase("create_prorations", "pm_SourceCreate", "charge_automatically"),
            new SourcePhase("always_invoice", "pm_SourceInvoice", "charge_automatically"));
        for (int i = 0; i < phases.size(); i++) {
            var phase = phases.get(i);
            var change = plan(i == 0 ? original.subscription() : paidOriginal().subscription(), ChangeTiming.NEXT_PERIOD, "source-phase-codec-" + i);
            var step = claimed(change.operation(), ProviderOperation.Kind.SCHEDULE_CREATE);
            var saved = proofs.recordSchedule(change.subscription(), change.operation(), change.quote(), step, OWNER, "sub_sched_Source" + i, SOURCE_PERIOD.start().getEpochSecond(), phase);
            assertThat(saved.sourcePhase()).isEqualTo(phase);
            assertThat(proofs.schedule(account.id(), change.operation().id())).contains(saved);
            assertThat(proofs.scheduleReference(account.id(), saved.reference())).contains(saved);
            assertThat(jdbc.queryForObject("select source_proration_behavior from stripe_plan_schedules where operation_id=?", String.class, change.operation().id())).isEqualTo(phase.prorationBehavior());
            assertThat(jdbc.queryForObject("select source_default_payment_method from stripe_plan_schedules where operation_id=?", String.class, change.operation().id())).isEqualTo(phase.defaultPaymentMethod());
            assertThat(jdbc.queryForObject("select source_collection_method from stripe_plan_schedules where operation_id=?", String.class, change.operation().id())).isEqualTo("charge_automatically");
            var update = claimed(change.operation(), ProviderOperation.Kind.SCHEDULE_UPDATE);
            var configured = proofs.recordScheduleUpdate(change.subscription(), change.operation(), saved, update, OWNER, targetEnd(change));
            assertThat(configured.sourcePhase()).isEqualTo(phase);
            assertThat(proofs.schedules(account.id(), change.subscription().id())).containsExactly(new SubscriptionPlanProofStore.ScheduleProof(change.quote(), configured));
        }
        assertThat(count("stripe_plan_schedules")).isEqualTo(3);
        assertThat(count("payments")).isZero();
    }

    @Test void scheduleSourcePhaseSqlRejectsUnknownModesMethodsAndNonAutomaticCollection() {
        var change = plan(original.subscription(), ChangeTiming.NEXT_PERIOD, "source-phase-sql");
        var step = acknowledge(reserveStep(change.operation(), ProviderOperation.Kind.SCHEDULE_CREATE), "sub_sched_SourceSql");
        var row = scheduleRow(change, step, "sub_sched_SourceSql");
        reject("stripe_plan_schedules", row, "source_proration_behavior", null, "23502");
        reject("stripe_plan_schedules", row, "source_proration_behavior", "invented", "23514");
        reject("stripe_plan_schedules", row, "source_default_payment_method", "pm_not_native", "23514");
        reject("stripe_plan_schedules", row, "source_default_payment_method", "cus_Source", "23514");
        reject("stripe_plan_schedules", row, "source_collection_method", null, "23502");
        reject("stripe_plan_schedules", row, "source_collection_method", "send_invoice", "23514");
        row.put("source_proration_behavior", "create_prorations"); row.put("source_default_payment_method", "pm_SqlSource");
        insert("stripe_plan_schedules", row);
        assertThat(proofs.schedule(account.id(), change.operation().id())).hasValueSatisfying(s ->
            assertThat(s.sourcePhase()).isEqualTo(new SourcePhase("create_prorations", "pm_SqlSource", "charge_automatically")));
        assertUnchangedEntitlement(change);
    }

    @Test void sourcePhaseReceiptRollbackAndDriftCannotChangeExistingConfiguration() {
        var change = plan(original.subscription(), ChangeTiming.NEXT_PERIOD, "source-phase-rollback");
        var phase = new SourcePhase("always_invoice", "pm_RetainedSource", "charge_automatically");
        var create = claimed(change.operation(), ProviderOperation.Kind.SCHEDULE_CREATE);
        rejectProofWrite("stripe_plan_schedules", () -> proofs.recordSchedule(change.subscription(), change.operation(), change.quote(), create, OWNER,
            "sub_sched_SourceAtomic", SOURCE_PERIOD.start().getEpochSecond(), phase));
        assertThat(steps.findById(create.id())).contains(create);
        assertThat(proofs.schedule(account.id(), change.operation().id())).isEmpty();
        var saved = proofs.recordSchedule(change.subscription(), change.operation(), change.quote(), create, OWNER,
            "sub_sched_SourceAtomic", SOURCE_PERIOD.start().getEpochSecond(), phase);
        for (String assignment : List.of("source_proration_behavior='none'", "source_default_payment_method='pm_Replacement'", "source_default_payment_method=null", "source_collection_method='send_invoice'")) {
            sqlState("23514", () -> jdbc.update("update stripe_plan_schedules set " + assignment + " where operation_id=?", change.operation().id()));
        }
        var changedSource = new Schedule(saved.operation(), saved.subscription(), saved.account(), saved.quote(), saved.creationStep(), saved.reference(),
            saved.phaseStart(), saved.updateStep(), saved.targetEnd(), saved.clearOperation(), saved.releaseStep(), new SourcePhase("none", "pm_Replacement", "charge_automatically"));
        var update = claimed(change.operation(), ProviderOperation.Kind.SCHEDULE_UPDATE);
        providerConflict(() -> proofs.recordScheduleUpdate(change.subscription(), change.operation(), changedSource, update, OWNER, targetEnd(change)));
        assertThat(steps.findById(update.id())).contains(update);
        assertThat(proofs.schedule(account.id(), change.operation().id())).contains(saved);
        var configured = proofs.recordScheduleUpdate(change.subscription(), change.operation(), saved, update, OWNER, targetEnd(change));
        // Receipt API requires the exact live step; replay reads retain the committed descriptor.
        // Neither an old DISPATCHING snapshot nor changed source fields can grant a new write.
        providerConflict(() -> proofs.recordSchedule(change.subscription(), change.operation(), change.quote(), create, OWNER,
            saved.reference(), saved.phaseStart(), phase));
        providerConflict(() -> proofs.recordSchedule(change.subscription(), change.operation(), change.quote(), create, OWNER,
            saved.reference(), saved.phaseStart(), changedSource.sourcePhase()));
        assertThat(proofs.scheduleReference(account.id(), saved.reference())).contains(configured);
        assertThat(proofs.schedules(account.id(), change.subscription().id())).containsExactly(new SubscriptionPlanProofStore.ScheduleProof(change.quote(), configured));
        assertThat(configured.sourcePhase()).isEqualTo(phase);
        assertThat(count("stripe_plan_schedules")).isEqualTo(1);
        assertUnchangedEntitlement(change);
    }

    @Test void realPublicPlanChangeSdkReceiptThenVerifiedCashDeliversSignedCallbackAndReplaysWithoutMoney() throws Exception {
        var live = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var fullPeriod = new Subscription.Period(live.minusSeconds(86400), live.plusSeconds(29 * 86400));
        try (var http = new dev.genericpayments.platform.support.StripeHttpFixture()) {
            var flow = connectedPlanReceipt(http, live, fullPeriod);
            var source = flow.source(); var graph = flow.graph(); var command = flow.command();
            var changed = flow.changed(); var savedQuote = flow.quote(); var receipt = flow.receipt();
            var proofId = savedQuote.id(); var targetTerms = savedQuote.target(); var targetId = targetTerms.priceId();
            enqueuePlanCashRead(http, graph, source.providerReference());
            reconciliation.reconcile(new dev.genericpayments.platform.subscriptions.application.port.in.ReconcileSubscriptionUseCase.Command(context("connected-cash-one"), source.id()));
            var settled = sub(source.id());
            assertThat(settled.billingPrice()).isEqualTo(targetTerms);
            assertThat(settled.entitledPrice()).isEqualTo(targetTerms);
            assertThat(settled.currentPeriod()).isEqualTo(fullPeriod);
            assertThat(settled.paidThrough()).isEqualTo(fullPeriod.end());
            assertThat(settled.pendingChange()).isNull();
            assertThat(op(changed.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
            assertThat(jdbc.queryForMap("select source,provider_payment_reference,amount_minor,currency,status from payments"))
                .containsEntry("source", "SUBSCRIPTION").containsEntry("provider_payment_reference", "pi_Connected")
                .containsEntry("amount_minor", 1000L).containsEntry("currency", "EUR").containsEntry("status", "SUCCEEDED");
            var bill = jdbc.queryForObject("select id from subscription_billing_records where provider_invoice_reference='in_ConnectedChange' and kind='PRORATION' and settlement='CONFIRMED_CASH'", UUID.class);
            assertThat(jdbc.queryForObject("select billed_price_id from subscription_billing_records where id=?", UUID.class, bill)).isEqualTo(targetId.value());
            assertThat(jdbc.queryForObject("select period_start from subscription_billing_records where id=?", Timestamp.class, bill).toInstant()).isEqualTo(live);
            assertThat(jdbc.queryForObject("select period_end from subscription_billing_records where id=?", Timestamp.class, bill).toInstant()).isEqualTo(fullPeriod.end());
            assertThat(jdbc.queryForObject("select count(*) from payment_events where event_type='PAYMENT_SUCCEEDED'", Integer.class)).isEqualTo(1);
            var changeEvent = jdbc.queryForObject("select id from subscription_events where subscription_id=? and event_type='CHANGE_APPLIED'", UUID.class, source.id());
            assertThat(jdbc.queryForObject("select billing_record_id from subscription_events where id=?", UUID.class, changeEvent)).isEqualTo(bill);
            var stableRows = connectedFinancialRows();
            int callsBeforeReplay = http.calls().size();
            assertThat(planChange.change(command).operationId()).isEqualTo(changed.operationId());
            assertThat(http.calls()).hasSize(callsBeforeReplay);
            enqueuePlanCashRead(http, graph, source.providerReference());
            reconciliation.reconcile(new dev.genericpayments.platform.subscriptions.application.port.in.ReconcileSubscriptionUseCase.Command(context("connected-cash-repeat"), source.id()));
            assertThat(connectedFinancialRows()).isEqualTo(stableRows);
            assertThat(http.calls()).hasSize(32);
            assertThat(http.calls().stream().filter(c -> c.path().equals("/v1/invoice_payments"))).hasSize(4);
            assertThat(http.calls().stream().filter(c -> c.path().equals("/v1/charges/ch_Connected"))).hasSize(2);
            assertThat(proofs.result(account.id(), changed.operationId())).contains(receipt);
            assertThat(proofs.quote(account.id(), proofId)).contains(savedQuote);
            assertThat(http.calls().stream().filter(c -> c.method().equals("POST") && c.path().equals("/v1/subscriptions/" + source.providerReference())))
                .singleElement().satisfies(c -> {
                    assertThat(c.header("Idempotency-Key")).isEqualTo(op(changed.operationId()).providerOperationKey());
                    assertThat(c.form()).containsEntry("payment_behavior", "pending_if_incomplete").containsEntry("proration_behavior", "always_invoice")
                        .containsEntry("proration_date", Long.toString(live.getEpochSecond())).containsEntry("items[0][id]", "si_Connected")
                        .containsEntry("items[0][price]", "price_ConnectedTarget").containsEntry("items[0][quantity]", "1");
                });
            assertThat(http.calls().stream().filter(c -> c.method().equals("POST") && c.path().equals("/v1/invoices/create_preview"))).hasSize(2);
            assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='PAYMENT_PREPARE'", Integer.class)).isZero();

            deliverConnectedCallbacks(source, targetId,
                Map.of(bill, new ConnectedCashCallback("PRORATION", 1000, new Subscription.Period(live, fullPeriod.end()))),
                Map.of(changeEvent, bill), live.plusSeconds(300), java.time.Clock.fixed(live, java.time.ZoneOffset.UTC), live, "connected-plan-delivery");
            assertThat(count("payments")).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from payment_events where event_type='PAYMENT_SUCCEEDED'", Integer.class)).isEqualTo(1);
        }
    }

    @Test void delayedConnectedPlanCashWithProrationFirstProjectsLatestRenewalAndSignsBothBills() throws Exception {
        delayedConnectedPlanCash(false);
    }
    @Test void delayedConnectedPlanCashWithRenewalFirstProjectsLatestRenewalAndSignsBothBills() throws Exception {
        delayedConnectedPlanCash(true);
    }
    private void delayedConnectedPlanCash(boolean renewalFirst) throws Exception {
        var live = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        // Honest artificial source period, not INITIAL cash or provider calendar evidence.
        var fullPeriod = new Subscription.Period(live.minusSeconds(86400), live.plusSeconds(2));
        try (var http = new dev.genericpayments.platform.support.StripeHttpFixture()) {
            var flow = connectedPlanReceipt(http, live, fullPeriod);
            var source = flow.source(); var graph = flow.graph(); var command = flow.command();
            var changed = flow.changed(); var savedQuote = flow.quote(); var receipt = flow.receipt();
            var proofId = savedQuote.id(); var targetTerms = savedQuote.target(); var targetId = targetTerms.priceId();
            var currentPeriod = new Subscription.Period(fullPeriod.end(), fullPeriod.end().plusSeconds(30 * 86400));
            var observedAt = currentPeriod.start().plusSeconds(1);
            // Only application/native observation time advances. Original native dispatch clock stays live.
            TestClock.set(observedAt);
            actor = new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site, customer.id(), "https://facts.test").rawToken(), "https://facts.test"));
            advanceConnectedPlanGraph(graph, currentPeriod, observedAt);
            enqueueDelayedPlanCashRead(http, graph, source.providerReference(), renewalFirst);
            reconciliation.reconcile(new dev.genericpayments.platform.subscriptions.application.port.in.ReconcileSubscriptionUseCase.Command(context("delayed-cash-one"), source.id()));
            var settled = sub(source.id());
            assertThat(settled.billingPrice()).isEqualTo(targetTerms);
            assertThat(settled.entitledPrice()).isEqualTo(targetTerms);
            assertThat(settled.currentPeriod()).isEqualTo(currentPeriod);
            assertThat(settled.paidThrough()).isEqualTo(currentPeriod.end());
            assertThat(settled.pendingChange()).isNull();
            assertThat(op(changed.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
            var originalBill = jdbc.queryForObject("select id from subscription_billing_records where provider_invoice_reference='in_ConnectedChange' and kind='PRORATION' and settlement='CONFIRMED_CASH'", UUID.class);
            var currentBill = jdbc.queryForObject("select id from subscription_billing_records where provider_invoice_reference='in_ConnectedRenewal' and kind='RENEWAL' and settlement='CONFIRMED_CASH'", UUID.class);
            assertThat(currentBill).isNotEqualTo(originalBill);
            assertThat(jdbc.queryForObject("select period_start from subscription_billing_records where id=?", Timestamp.class, originalBill).toInstant()).isEqualTo(live);
            assertThat(jdbc.queryForObject("select period_end from subscription_billing_records where id=?", Timestamp.class, originalBill).toInstant()).isEqualTo(fullPeriod.end());
            assertThat(jdbc.queryForObject("select period_start from subscription_billing_records where id=?", Timestamp.class, currentBill).toInstant()).isEqualTo(currentPeriod.start());
            assertThat(jdbc.queryForObject("select period_end from subscription_billing_records where id=?", Timestamp.class, currentBill).toInstant()).isEqualTo(currentPeriod.end());
            assertThat(jdbc.queryForList("select billed_price_id from subscription_billing_records", UUID.class)).containsOnly(targetId.value());
            var changeEvent = jdbc.queryForObject("select id from subscription_events where subscription_id=? and event_type='CHANGE_APPLIED'", UUID.class, source.id());
            var periodEvent = jdbc.queryForObject("select id from subscription_events where subscription_id=? and event_type='PERIOD_PAID'", UUID.class, source.id());
            assertThat(periodEvent).isNotEqualTo(changeEvent);
            assertThat(jdbc.queryForObject("select billing_record_id from subscription_events where id=?", UUID.class, changeEvent)).isEqualTo(originalBill);
            assertThat(jdbc.queryForObject("select billing_record_id from subscription_events where id=?", UUID.class, periodEvent)).isEqualTo(currentBill);
            assertThat(jdbc.queryForObject("select count(*) from payments where source='SUBSCRIPTION' and status='SUCCEEDED' and currency='EUR'", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select amount_minor from payments where provider_payment_reference='pi_Connected'", Long.class)).isEqualTo(1000);
            assertThat(jdbc.queryForObject("select amount_minor from payments where provider_payment_reference='pi_ConnectedRenewal'", Long.class)).isEqualTo(4000);
            assertThat(jdbc.queryForObject("select billing_record_id from payments where provider_payment_reference='pi_Connected'", UUID.class)).isEqualTo(originalBill);
            assertThat(jdbc.queryForObject("select billing_record_id from payments where provider_payment_reference='pi_ConnectedRenewal'", UUID.class)).isEqualTo(currentBill);
            assertThat(jdbc.queryForObject("select count(*) from payment_events where event_type='PAYMENT_SUCCEEDED'", Integer.class)).isEqualTo(2);
            var stableRows = connectedFinancialRows();
            int callsBeforeReplay = http.calls().size();
            var replay = new dev.genericpayments.platform.subscriptions.application.port.in.ChangeSubscriptionPlanUseCase.Command(context("connected-change"), source.id(), command.quoteId());
            assertThat(planChange.change(replay).operationId()).isEqualTo(changed.operationId());
            assertThat(http.calls()).hasSize(callsBeforeReplay);
            enqueueDelayedPlanCashRead(http, graph, source.providerReference(), !renewalFirst);
            reconciliation.reconcile(new dev.genericpayments.platform.subscriptions.application.port.in.ReconcileSubscriptionUseCase.Command(context("delayed-cash-repeat"), source.id()));
            assertThat(connectedFinancialRows()).isEqualTo(stableRows);
            assertThat(proofs.result(account.id(), changed.operationId())).contains(receipt);
            assertThat(proofs.quote(account.id(), proofId)).contains(savedQuote);
            assertThat(http.calls()).hasSize(42);
            assertThat(http.calls().stream().filter(c -> c.method().equals("POST") && c.path().equals("/v1/subscriptions/" + source.providerReference())))
                .singleElement().satisfies(c -> assertThat(c.header("Idempotency-Key")).isEqualTo(op(changed.operationId()).providerOperationKey()));
            assertThat(http.calls().stream().filter(c -> c.method().equals("POST") && c.path().equals("/v1/invoices/create_preview"))).hasSize(2);
            assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='PAYMENT_PREPARE'", Integer.class)).isZero();

            // Real-time callback boundary: no synthetic future signing or security-clock margin.
            awaitConnectedRealTime(observedAt);
            var deliveryClock = java.time.Clock.systemUTC();
            var dueAt = deliveryClock.instant();
            deliverConnectedCallbacks(source, targetId,
                Map.of(originalBill, new ConnectedCashCallback("PRORATION", 1000, new Subscription.Period(live, fullPeriod.end())),
                    currentBill, new ConnectedCashCallback("RENEWAL", 4000, currentPeriod)),
                Map.of(changeEvent, originalBill, periodEvent, currentBill), observedAt.plusSeconds(300), deliveryClock, dueAt, "delayed-plan-delivery");
            assertThat(outbox.claimAutomatic("delayed-plan-empty", 20, deliveryClock.instant(), Duration.ofSeconds(30))).isEmpty();
            assertThat(connectedFinancialRows()).isEqualTo(stableRows);
            assertThat(count("payments")).isEqualTo(2);
        }
    }

    private ConnectedPlanReceipt connectedPlanReceipt(dev.genericpayments.platform.support.StripeHttpFixture http, Instant live, Subscription.Period fullPeriod) {
        TestClock.set(live);
        actor = new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site, customer.id(), "https://facts.test").rawToken(), "https://facts.test"));
        var seeded = sub(original.subscription().id());
        // Honest source fixture seed: initial cash settlement is outside this scenario's evidence.
        subscriptions.save(seeded.confirmPaid(seeded.billingPrice(), fullPeriod, live), seeded.revision());
        var source = sub(seeded.id());
        var targetId = target(source, ChangeTiming.IMMEDIATE);
        var targetTerms = catalog.published(site, targetId);
        for (var mapping : List.of(Map.entry(source.billingPrice().priceId(), "price_ConnectedSource"), Map.entry(targetId, "price_ConnectedTarget"))) {
            catalogMappings.save(new dev.genericpayments.platform.catalog.application.model.ProviderCatalogMapping(account.id(), source.productId(),
                new dev.genericpayments.platform.catalog.application.model.ProviderProductRef("prod_Connected"), mapping.getKey(),
                new dev.genericpayments.platform.catalog.application.model.ProviderPriceRef(mapping.getValue()), live));
        }
        var graph = connectedPlanGraph(source, targetTerms, fullPeriod, live);
        var credentials = new dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver(
            new dev.genericpayments.platform.provider.adapter.out.stripe.StripeSandboxProperties(true, "sk_test_" + "A".repeat(32), "pk_test_" + "B".repeat(32), "whsec_" + "C".repeat(32)));
        var factory = new dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory() {
            @Override protected com.stripe.StripeClient.StripeClientBuilder builder(dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentials c) {
                assertThat(tx.active()).isFalse();
                return super.builder(c).setApiBase(http.baseUri().toString()).setHttpClient(closedPlanTransport(http.baseUri()));
            }
        };
        var verification = new dev.genericpayments.platform.provider.adapter.out.stripe.StripeAccountVerificationAdapter(credentials, factory);
        var customerAdapter = new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingCustomerAdapter(
            credentials, factory, steps, bindings, digest, verification, accounts, siteLoader, customers);
        var realProvider = new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeSubscriptionProviderAdapter(
            credentials, factory, verification, steps, bindings, customerAdapter, digest, accounts, siteLoader, customers, subscriptions, operations, catalog, catalogMappings, businessClock);
        org.springframework.test.util.ReflectionTestUtils.setField(realProvider, "planProofs", proofs);
        org.springframework.test.util.ReflectionTestUtils.setField(realProvider, "providerClock", java.time.Clock.fixed(live, java.time.ZoneOffset.UTC));
        reset(providers);
        when(providers.forAccount(any())).thenAnswer(call -> { assertThat(call.getArgument(0, dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount.class)).isEqualTo(account); return realProvider; });
        callbackConfiguration.configure(site.value(), "https://example.invalid/plan-callback", true);

        enqueuePlanRead(http, graph, source.providerReference(), "source");
        http.enqueue("POST", "/v1/invoices/create_preview", 200, graph.get("preview").toString());
        var quote = planPreview.preview(new PreviewSubscriptionChangeUseCase.Query(context("connected-preview"), source.id(), targetId, ChangeTiming.IMMEDIATE));
        var proofId = UUID.fromString(quote.providerQuoteReference().substring("bq1:".length()));
        var savedQuote = proofs.quote(account.id(), proofId).orElseThrow();
        assertThat(savedQuote.anchor()).isEqualTo(live);
        assertThat(savedQuote.source()).isEqualTo(source.billingPrice());
        assertThat(savedQuote.target()).isEqualTo(targetTerms);
        assertThat(savedQuote.dueNow()).isEqualTo(1000);
        assertThat(savedQuote.lines()).extracting(Line::amount).containsExactly(-1000L, 2000L);
        assertThat(sub(source.id()).entitledPrice()).isEqualTo(source.entitledPrice());

        // Public change performs its own fresh preview before the actual guarded SDK update.
        enqueuePlanRead(http, graph, source.providerReference(), "source");
        http.enqueue("POST", "/v1/invoices/create_preview", 200, graph.get("preview").toString());
        enqueuePlanRead(http, graph, source.providerReference(), "source");
        http.enqueue("POST", "/v1/subscriptions/" + source.providerReference(), 200, graph.get("target").toString());
        http.enqueue("GET", "/v1/invoices/in_ConnectedChange", 200, graph.get("invoice").toString());
        http.enqueue("GET", "/v1/invoices/in_ConnectedChange/lines", 200, connectedPlanPage(graph.get("credit"), graph.get("debit")));
        var command = new dev.genericpayments.platform.subscriptions.application.port.in.ChangeSubscriptionPlanUseCase.Command(context("connected-change"), source.id(), quote.id());
        var changed = planChange.change(command);
        assertThat(changed.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        var receipt = proofs.result(account.id(), changed.operationId()).orElseThrow();
        assertThat(receipt.quote()).isEqualTo(proofId);
        assertThat(receipt.reference()).isEqualTo(source.providerReference());
        assertThat(receipt.invoice()).isEqualTo("in_ConnectedChange");
        assertThat(receipt.creditLine()).isEqualTo("il_ConnectedCredit");
        assertThat(receipt.debitLine()).isEqualTo("il_ConnectedDebit");
        var ownedStep = steps.find(account.id(), op(changed.operationId()).providerOperationKey()).orElseThrow();
        assertThat(receipt.step()).isEqualTo(ownedStep.id());
        assertThat(ownedStep.kind()).isEqualTo(ProviderOperation.Kind.SUBSCRIPTION_UPDATE);
        assertThat(ownedStep.resourceId()).isEqualTo(changed.operationId());
        assertThat(ownedStep.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        var afterAck = sub(source.id());
        assertThat(afterAck.billingPrice()).isEqualTo(source.billingPrice());
        assertThat(afterAck.entitledPrice()).isEqualTo(source.entitledPrice());
        assertThat(afterAck.currentPeriod()).isEqualTo(fullPeriod);
        assertThat(afterAck.paidThrough()).isEqualTo(fullPeriod.end());
        assertThat(count("payments")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from subscription_events where event_type='CHANGE_APPLIED'", Integer.class)).isZero();

        return new ConnectedPlanReceipt(source, graph, command, changed, savedQuote, receipt);
    }

    private record ConnectedPlanReceipt(Subscription source, Map<String,com.google.gson.JsonObject> graph,
        dev.genericpayments.platform.subscriptions.application.port.in.ChangeSubscriptionPlanUseCase.Command command,
        dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult changed, Quote quote, SubscriptionPlanProofStore.Result receipt) {}
    private record ConnectedCashCallback(String kind, long amount, Subscription.Period period) {}

    private void deliverConnectedCallbacks(Subscription source, PriceId target, Map<UUID,ConnectedCashCallback> expectedCash,
            Map<UUID,UUID> lifecycleBills, Instant accessUntil, java.time.Clock deliveryClock, Instant dueAt, String owner) {
        var cashCallbacks = new HashSet<UUID>();
        var lifecycleCallbacks = new HashSet<UUID>();
        var claims = outbox.claimAutomatic(owner, 20, dueAt, Duration.ofSeconds(30));
        // Both paths also have exactly one original CHANGE_REQUESTED callback.
        assertThat(claims).hasSize(expectedCash.size() + lifecycleBills.size() + 1);
        for (var claim : claims) {
            byte[] secret = Base64.getUrlDecoder().decode(encryption.decrypt(claim.secret()));
            try {
                new dev.genericpayments.platform.webhooks.application.service.CallbackDeliveryService(outbox, encryption, callbackPayload, (endpoint, headers, body) -> {
                    assertThat(tx.active()).isFalse();
                    assertThat(endpoint.toString()).isEqualTo("https://example.invalid/plan-callback");
                    long at = Long.parseLong(headers.get("Payment-Timestamp"));
                    assertThat(at).isBetween(dueAt.getEpochSecond(), deliveryClock.instant().getEpochSecond());
                    assertThat(headers.get("Payment-Signature")).isEqualTo("v1=" + new dev.genericpayments.platform.webhooks.application.service.CallbackSigner().sign(secret, at, body));
                    var payload = connectedPlanJson(new String(body, java.nio.charset.StandardCharsets.UTF_8));
                    assertThat(headers.get("Payment-Event-Id")).isEqualTo(payload.get("event_id").getAsString());
                    assertThat(payload.get("site_id").getAsString()).isEqualTo(site.value().toString());
                    assertThat(payload.get("subscription_id").getAsString()).isEqualTo(source.id().toString());
                    assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("client_secret", "sk_test_");
                    var eventId = UUID.fromString(payload.get("event_id").getAsString());
                    if (payload.get("event_type").getAsString().equals("payment.succeeded")) {
                        var billId = UUID.fromString(payload.get("billing_record_id").getAsString());
                        var expected = expectedCash.get(billId);
                        assertThat(expected).isNotNull();
                        assertThat(cashCallbacks.add(billId)).isTrue();
                        assertThat(payload.get("amount_minor").getAsLong()).isEqualTo(expected.amount());
                        assertThat(payload.get("billing_kind").getAsString()).isEqualTo(expected.kind());
                        assertThat(payload.get("currency").getAsString()).isEqualTo("EUR");
                        assertThat(payload.get("price_id").getAsString()).isEqualTo(target.value().toString());
                        assertThat(payload.get("billing_record_id").getAsString()).isEqualTo(billId.toString());
                        assertThat(payload.get("period_start").getAsString()).isEqualTo(expected.period().start().toString());
                        assertThat(payload.get("period_end").getAsString()).isEqualTo(expected.period().end().toString());
                    }
                    if (lifecycleBills.containsKey(eventId)) {
                        assertThat(lifecycleCallbacks.add(eventId)).isTrue();
                        assertThat(payload.get("billing_record_id").getAsString()).isEqualTo(lifecycleBills.get(eventId).toString());
                        assertThat(payload.get("access_allowed").getAsBoolean()).isTrue();
                        assertThat(payload.get("access_valid_until").getAsString()).isEqualTo(accessUntil.toString());
                    }
                    return dev.genericpayments.platform.webhooks.application.port.out.CallbackHttpPort.Result.success(204);
                }, deliveryClock).deliver(claim);
            } finally { Arrays.fill(secret, (byte) 0); }
        }
        assertThat(cashCallbacks).containsExactlyInAnyOrderElementsOf(expectedCash.keySet());
        assertThat(lifecycleCallbacks).containsExactlyInAnyOrderElementsOf(lifecycleBills.keySet());
        assertThat(jdbc.queryForList("select status from outbox_events", String.class)).containsOnly("DELIVERED");
    }

    private static void awaitConnectedRealTime(Instant observedAt) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Instant.now().isBefore(observedAt) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(Instant.now()).isAfterOrEqualTo(observedAt);
    }
    private void advanceConnectedPlanGraph(Map<String,com.google.gson.JsonObject> graph, Subscription.Period period, Instant observedAt) {
        var item = graph.get("target").getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject();
        item.addProperty("current_period_start", period.start().getEpochSecond()); item.addProperty("current_period_end", period.end().getEpochSecond());
        graph.get("target").addProperty("latest_invoice", "in_ConnectedRenewal");
        var invoice = graph.get("invoice").deepCopy(); invoice.addProperty("id", "in_ConnectedRenewal");
        invoice.addProperty("billing_reason", "subscription_cycle"); invoice.addProperty("created", observedAt.getEpochSecond());
        for (String field : List.of("amount_due", "amount_paid", "total", "subtotal")) invoice.addProperty(field, 4000);
        graph.put("renewalInvoice", invoice);
        var line = graph.get("debit").deepCopy(); line.addProperty("id", "il_ConnectedRenewal"); line.addProperty("invoice", "in_ConnectedRenewal"); line.addProperty("amount", 4000);
        line.getAsJsonObject("period").addProperty("start", period.start().getEpochSecond()); line.getAsJsonObject("period").addProperty("end", period.end().getEpochSecond());
        line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details").addProperty("proration", false); graph.put("renewalLine", line);
        var allocation = graph.get("allocation").deepCopy(); allocation.addProperty("id", "inpay_ConnectedRenewal"); allocation.addProperty("invoice", "in_ConnectedRenewal");
        allocation.addProperty("amount_paid", 4000); allocation.addProperty("amount_requested", 4000);
        allocation.getAsJsonObject("payment").addProperty("payment_intent", "pi_ConnectedRenewal");
        allocation.getAsJsonObject("status_transitions").addProperty("paid_at", observedAt.getEpochSecond()); graph.put("renewalAllocation", allocation);
        var intent = graph.get("intent").deepCopy(); intent.addProperty("id", "pi_ConnectedRenewal"); intent.addProperty("amount", 4000); intent.addProperty("amount_received", 4000); intent.addProperty("latest_charge", "ch_ConnectedRenewal"); graph.put("renewalIntent", intent);
        var charge = graph.get("charge").deepCopy(); charge.addProperty("id", "ch_ConnectedRenewal"); charge.addProperty("payment_intent", "pi_ConnectedRenewal");
        charge.addProperty("amount", 4000); charge.addProperty("amount_captured", 4000); charge.addProperty("created", observedAt.getEpochSecond()); graph.put("renewalCharge", charge);
    }
    private void enqueueDelayedPlanCashRead(dev.genericpayments.platform.support.StripeHttpFixture http, Map<String,com.google.gson.JsonObject> graph, String subscription, boolean renewalFirst) {
        enqueuePlanRead(http, graph, subscription, "target");
        http.enqueue("GET", "/v1/invoices", 200, renewalFirst ? connectedPlanPage(graph.get("renewalInvoice"), graph.get("invoice")) : connectedPlanPage(graph.get("invoice"), graph.get("renewalInvoice")));
        for (boolean renewal : renewalFirst ? List.of(true, false) : List.of(false, true)) {
            String invoice = renewal ? "in_ConnectedRenewal" : "in_ConnectedChange";
            http.enqueue("GET", "/v1/invoices/" + invoice + "/lines", 200, renewal ? connectedPlanPage(graph.get("renewalLine")) : connectedPlanPage(graph.get("credit"), graph.get("debit")));
            http.enqueue("GET", "/v1/invoice_payments", 200, connectedPlanPage(graph.get(renewal ? "renewalAllocation" : "allocation")));
            http.enqueue("GET", "/v1/payment_intents/" + (renewal ? "pi_ConnectedRenewal" : "pi_Connected"), 200, graph.get(renewal ? "renewalIntent" : "intent").toString());
            http.enqueue("GET", "/v1/charges/" + (renewal ? "ch_ConnectedRenewal" : "ch_Connected"), 200, graph.get(renewal ? "renewalCharge" : "charge").toString());
            http.enqueue("GET", "/v1/invoice_payments", 200, connectedPlanPage(graph.get(renewal ? "renewalAllocation" : "allocation")));
        }
    }

    private Map<String,Integer> connectedFinancialRows() {
        var rows = new LinkedHashMap<String,Integer>();
        for (String table : List.of("payments", "payment_events", "subscription_billing_records", "subscription_events", "outbox_events", "stripe_plan_quotes", "stripe_plan_results")) rows.put(table, count(table));
        return rows;
    }
    private void enqueuePlanRead(dev.genericpayments.platform.support.StripeHttpFixture http, Map<String,com.google.gson.JsonObject> graph, String subscription, String state) {
        http.enqueue("GET", "/v1/account", 200, "{\"id\":\"" + account.externalAccountId() + "\",\"object\":\"account\"}");
        http.enqueue("GET", "/v1/customers/" + customerReference, 200, graph.get("customer").toString());
        http.enqueue("GET", "/v1/subscriptions/" + subscription, 200, graph.get(state).toString());
    }
    private void enqueuePlanCashRead(dev.genericpayments.platform.support.StripeHttpFixture http, Map<String,com.google.gson.JsonObject> graph, String subscription) {
        enqueuePlanRead(http, graph, subscription, "target");
        http.enqueue("GET", "/v1/invoices", 200, connectedPlanPage(graph.get("invoice")));
        http.enqueue("GET", "/v1/invoices/in_ConnectedChange/lines", 200, connectedPlanPage(graph.get("credit"), graph.get("debit")));
        http.enqueue("GET", "/v1/invoice_payments", 200, connectedPlanPage(graph.get("allocation")));
        http.enqueue("GET", "/v1/payment_intents/pi_Connected", 200, graph.get("intent").toString());
        http.enqueue("GET", "/v1/charges/ch_Connected", 200, graph.get("charge").toString());
        http.enqueue("GET", "/v1/invoice_payments", 200, connectedPlanPage(graph.get("allocation")));
    }
    private Map<String,com.google.gson.JsonObject> connectedPlanGraph(Subscription source, SubscriptionPriceTerms target, Subscription.Period fullPeriod, Instant anchor) {
        var graph = new HashMap<String,com.google.gson.JsonObject>();
        String subscription = source.providerReference();
        graph.put("customer", connectedPlanJson("{\"id\":\"" + customerReference + "\",\"object\":\"customer\",\"livemode\":false}"));
        graph.put("source", connectedPlanJson("""
            {"id":"%s","object":"subscription","livemode":false,"customer":"%s","status":"active","currency":"eur",
             "collection_method":"charge_automatically","billing_mode":{"type":"classic"},"payment_settings":{"payment_method_types":["card"],"save_default_payment_method":"on_subscription"},
             "items":{"object":"list","has_more":false,"data":[{"id":"si_Connected","object":"subscription_item","subscription":"%s","quantity":1,
              "current_period_start":%d,"current_period_end":%d,"price":{"id":"price_ConnectedSource","object":"price","livemode":false,"product":"prod_Connected","currency":"eur","unit_amount":2000,
              "tax_behavior":"inclusive","type":"recurring","recurring":{"interval":"month","interval_count":1,"usage_type":"licensed"},"metadata":{"local_price_id":"%s","local_price_version":"1"}}}]}}
            """.formatted(subscription, customerReference, subscription, fullPeriod.start().getEpochSecond(), fullPeriod.end().getEpochSecond(), source.billingPrice().priceId().value())));
        var remoteTarget = graph.get("source").deepCopy();
        var targetPrice = remoteTarget.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject().getAsJsonObject("price");
        targetPrice.addProperty("id", "price_ConnectedTarget"); targetPrice.addProperty("unit_amount", 4000);
        targetPrice.getAsJsonObject("metadata").addProperty("local_price_id", target.priceId().value().toString());
        remoteTarget.addProperty("latest_invoice", "in_ConnectedChange"); graph.put("target", remoteTarget);
        graph.put("invoice", connectedPlanJson("""
            {"id":"in_ConnectedChange","object":"invoice","livemode":false,"customer":"%s","currency":"eur","status":"paid","billing_reason":"subscription_update","created":%d,
             "collection_method":"charge_automatically","amount_due":1000,"amount_paid":1000,"amount_remaining":0,"amount_paid_off_stripe":0,"amount_overpaid":0,"total":1000,"subtotal":1000,
             "starting_balance":0,"ending_balance":0,"pre_payment_credit_notes_amount":0,"post_payment_credit_notes_amount":0,"discounts":[],"total_discount_amounts":[],"total_pretax_credit_amounts":[],"total_taxes":[],
             "parent":{"type":"subscription_details","subscription_details":{"subscription":"%s"}}}
            """.formatted(customerReference, anchor.getEpochSecond(), subscription)));
        for (var spec : List.of(Map.entry("credit", -1000), Map.entry("debit", 2000))) {
            graph.put(spec.getKey(), connectedPlanJson("""
                {"id":"il_Connected%s","object":"line_item","livemode":false,"invoice":"in_ConnectedChange","subscription":"%s","currency":"eur","quantity":1,"amount":%d,"discount_amounts":[],"pretax_credit_amounts":[],
                 "period":{"start":%d,"end":%d},"parent":{"type":"subscription_item_details","subscription_item_details":{"subscription":"%s","subscription_item":"si_Connected","proration":true}},
                 "pricing":{"type":"price_details","price_details":{"price":"price_Connected%s","product":"prod_Connected"}}}
                """.formatted(spec.getKey().equals("credit") ? "Credit" : "Debit", subscription, spec.getValue(), anchor.getEpochSecond(), fullPeriod.end().getEpochSecond(), subscription, spec.getKey().equals("credit") ? "Source" : "Target")));
        }
        var preview = graph.get("invoice").deepCopy();
        preview.addProperty("id", "upcoming_Connected"); preview.addProperty("status", "draft"); preview.addProperty("amount_paid", 0); preview.addProperty("amount_remaining", 1000);
        var previewCredit = graph.get("credit").deepCopy(); previewCredit.addProperty("id", "il_PreviewCredit"); previewCredit.addProperty("invoice", "upcoming_Connected");
        var previewDebit = graph.get("debit").deepCopy(); previewDebit.addProperty("id", "il_PreviewDebit"); previewDebit.addProperty("invoice", "upcoming_Connected");
        preview.add("lines", com.google.gson.JsonParser.parseString(connectedPlanPage(previewCredit, previewDebit))); graph.put("preview", preview);
        graph.put("allocation", connectedPlanJson("{\"id\":\"inpay_Connected\",\"object\":\"invoice_payment\",\"livemode\":false,\"invoice\":\"in_ConnectedChange\",\"currency\":\"eur\",\"status\":\"paid\",\"is_default\":true,\"amount_paid\":1000,\"amount_requested\":1000,\"payment\":{\"type\":\"payment_intent\",\"payment_intent\":\"pi_Connected\"},\"status_transitions\":{\"paid_at\":" + anchor.getEpochSecond() + "}}"));
        graph.put("intent", connectedPlanJson("{\"id\":\"pi_Connected\",\"object\":\"payment_intent\",\"livemode\":false,\"customer\":\"" + customerReference + "\",\"currency\":\"eur\",\"amount\":1000,\"amount_received\":1000,\"status\":\"succeeded\",\"capture_method\":\"automatic\",\"payment_method_types\":[\"card\"],\"latest_charge\":\"ch_Connected\"}"));
        graph.put("charge", connectedPlanJson("{\"id\":\"ch_Connected\",\"object\":\"charge\",\"livemode\":false,\"customer\":\"" + customerReference + "\",\"payment_intent\":\"pi_Connected\",\"currency\":\"eur\",\"amount\":1000,\"amount_captured\":1000,\"paid\":true,\"captured\":true,\"status\":\"succeeded\",\"created\":" + anchor.getEpochSecond() + ",\"payment_method_details\":{\"type\":\"card\"},\"amount_refunded\":0,\"refunded\":false}"));
        return graph;
    }
    private static String connectedPlanPage(com.google.gson.JsonObject... objects) {
        var data = new com.google.gson.JsonArray(); for (var object : objects) data.add(object);
        var page = new com.google.gson.JsonObject(); page.addProperty("object", "list"); page.addProperty("has_more", false); page.add("data", data); return page.toString();
    }
    private static com.google.gson.JsonObject connectedPlanJson(String text) { return com.google.gson.JsonParser.parseString(text).getAsJsonObject(); }
    private static com.stripe.net.HttpClient closedPlanTransport(java.net.URI owned) {
        return new com.stripe.net.HttpClient() {
            private final java.net.http.HttpClient transport = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
            @Override public com.stripe.net.StripeResponse request(com.stripe.net.StripeRequest request) throws com.stripe.exception.ApiConnectionException {
                var uri = java.net.URI.create(request.url().toString());
                if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() != owned.getPort() || uri.getUserInfo() != null || uri.getFragment() != null || !uri.getPath().startsWith("/v1/"))
                    throw new com.stripe.exception.ApiConnectionException("Unowned fixture destination refused");
                var builder = java.net.http.HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5));
                request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
                var content = request.content(); if (content != null) builder.header("Content-Type", content.contentType());
                builder.method(request.method().name(), content == null ? java.net.http.HttpRequest.BodyPublishers.noBody() : java.net.http.HttpRequest.BodyPublishers.ofByteArray(content.byteArrayContent()));
                var pending = transport.sendAsync(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                try {
                    var response = pending.get(5, TimeUnit.SECONDS);
                    if (response.statusCode() >= 300 && response.statusCode() < 400) throw new com.stripe.exception.ApiConnectionException("Fixture redirect refused");
                    return new com.stripe.net.StripeResponse(response.statusCode(), com.stripe.net.HttpHeaders.of(response.headers().map()), response.body());
                } catch (InterruptedException interrupted) {
                    pending.cancel(true); Thread.currentThread().interrupt(); throw new com.stripe.exception.ApiConnectionException("Fixture request interrupted");
                } catch (ExecutionException | TimeoutException unavailable) {
                    pending.cancel(true); throw new com.stripe.exception.ApiConnectionException("Fixture request unavailable");
                }
            }
        };
    }

    private record ReplayCase(Prepared prepared, SubscriptionPlanTransaction.Reserved reserved) {}
    private ReplayCase replayCase(String key, boolean unknown) {
        var prepared = preview(original.subscription(), ChangeTiming.IMMEDIATE, key);
        var owner = reserveOnly(prepared);
        plans.beforeMutation(prepared.context(), owner, true);
        if (unknown) plans.reply(owner, new Reply(Outcome.UNKNOWN, null, null));
        var replay = reserveOnly(prepared);
        assertThat(replay.owner()).isFalse();
        assertThat(replay.operation().id()).isEqualTo(owner.operation().id());
        assertThat(replay.quote()).isEqualTo(prepared.common());
        return new ReplayCase(prepared, replay);
    }
    private static SubscriptionPlanTransaction.Reserved replayWith(SubscriptionPlanTransaction.Reserved r, Subscription s,
            dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount account, SubscriptionQuote quote) {
        return new SubscriptionPlanTransaction.Reserved(r.operation(), s, account, quote, false);
    }
    private SubscriptionCommandContext replayContext(Prepared prepared) {
        var fresh = new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site, customer.id(), "https://facts.test").rawToken(), "https://facts.test"));
        return new SubscriptionCommandContext(fresh, site, customer.id(), "https://facts.test", prepared.context().idempotencyKey(), "task8-original-replay");
    }
    private Map<String, List<Map<String, Object>>> replayRows() {
        return Map.of("operations", jdbc.queryForList("select * from subscription_operations order by id"), "quotes", jdbc.queryForList("select * from subscription_quotes order by id"),
            "intents", jdbc.queryForList("select * from subscription_change_intents order by operation_id"), "descriptors", jdbc.queryForList("select * from stripe_plan_quotes order by id"),
            "subscriptions", jdbc.queryForList("select * from subscriptions order by id"), "events", jdbc.queryForList("select * from subscription_events order by id"),
            "outbox", jdbc.queryForList("select * from outbox_events order by id"), "steps", jdbc.queryForList("select * from provider_operations order by id"));
    }
    private void rejectReplaySubscriptionMutation(ReplayCase replay, Map<String, Object> mutation) {
        var id = replay.reserved().subscription().id();
        var before = jdbc.queryForMap("select * from subscriptions where id=?", id);
        var restore = new LinkedHashMap<String, Object>();
        mutation.keySet().forEach(column -> restore.put(column, before.get(column)));
        updateSubscription(id, mutation);
        try { replayStale(() -> plans.beforeOriginalReplay(replay.prepared().context(), replay.reserved())); }
        finally { updateSubscription(id, restore); }
    }
    private void updateSubscription(UUID id, Map<String, Object> values) {
        var arguments = new ArrayList<>(values.values()); arguments.add(id);
        jdbc.update("update subscriptions set " + String.join(",", values.keySet().stream().map(column -> column + "=?").toList()) + " where id=?", arguments.toArray());
    }
    private static void replayStale(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class, failure -> assertThat(failure.code()).isEqualTo("SUBSCRIPTION_QUOTE_STALE"));
    }
    private static void customerUnavailable(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class, failure -> assertThat(failure.code()).isEqualTo("CUSTOMER_NOT_AVAILABLE"));
    }

    private record Grant(SubscriptionClientGrant grant, String raw) {}
    private record ChangeGrant(Plan change, SubscriptionPlanProofStore.Result result, SubscriptionClientGrant grant, String raw) {}
    private record GrantWorkflow(ChangeGrant owned, SubscriptionCommandContext context, SubscriptionProviderPort provider, String secret) {}
    private ChangeGrant changeGrantCase(String key) { return changeGrantCase(original.subscription(), key); }
    private ChangeGrant changeGrantCase(Subscription source, String key) {
        var change = plan(source, ChangeTiming.IMMEDIATE, key);
        var step = claim(changeStep(change));
        var result = proofs.recordResult(change.subscription(), change.operation(), change.quote(), step, OWNER, nativeReference("in_"), grantNow().plusSeconds(1800).getEpochSecond(), creditLine(change), debitLine(change));
        var grant = grant(change.subscription(), change.operation(), paymentBinding(change, result.step(), result.invoice(), "change-v1"));
        return new ChangeGrant(change, result, grant.grant(), grant.raw());
    }
    private ProviderOperation changeStep(Plan change) {
        var o = change.operation();
        return steps.reserve(account, ProviderOperation.Kind.SUBSCRIPTION_UPDATE, o.id(), o.providerOperationKey(), StripeBillingMutationAdapter.changeFingerprint(account, o, change.quote()));
    }
    private SubscriptionClientGrant.Binding paymentBinding(Plan change, UUID step, String invoice, String schema) {
        var proof = StripeBillingGrantTokens.hash(SubscriptionClientGrant.canonical("task8-owned-grant", change.operation().id(), change.quote().id(), step, invoice, creditLine(change), debitLine(change)));
        return new SubscriptionClientGrant.Binding(step, change.operation().providerOperationKey(), proof, change.subscription().providerReference(), customerReference, invoice, nativeReference("inpay_"), nativeReference("pi_"), schema, null);
    }
    private Grant grant(Subscription s, SubscriptionOperation o, SubscriptionClientGrant.Binding binding) {
        var raw = "bga1_" + UUID.randomUUID().toString().replace("-", "") + "C".repeat(11);
        var now = grantNow();
        return new Grant(new SubscriptionClientGrant(UUID.randomUUID(), site, customer.id(), account.id(), s.id(), o.id(), "v1", binding, StripeBillingGrantTokens.hash(raw), now, now.plusSeconds(600), null, null, null, null, null), raw);
    }
    private void reserveGrant(SubscriptionClientGrant grant) { tx.required(() -> { operations.lock(grant.operation()); actions.reserveBilling(grant); return null; }); }
    private SubscriptionClientGrant storedGrant(SubscriptionClientGrant grant) { return tx.required(() -> actions.billing(grant.tokenDigest()).orElseThrow()); }
    private static Instant grantNow() { return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private void refreshGrantActor() {
        TestClock.set(grantNow());
        actor = new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site, customer.id(), "https://facts.test").rawToken(), "https://facts.test"));
    }
    private GrantWorkflow grantWorkflow(String key) {
        var owned = changeGrantCase(key);
        reserveGrant(owned.grant());
        refreshGrantActor();
        reset(clientSessions, providers);
        var provider = mock(SubscriptionProviderPort.class);
        when(providers.forAccount(any())).thenReturn(provider);
        var action = new NextAction(ActionKind.PROVIDER_CLIENT, owned.raw(), owned.grant().expiresAt());
        when(provider.nextAction(any())).thenAnswer(call -> { assertThat(tx.active()).isFalse(); return action; });
        when(provider.confirmAction(any(), eq(owned.raw()))).thenAnswer(call -> {
            assertThat(tx.active()).isFalse(); assertThat(storedGrant(owned.grant()).consumedAt()).isNotNull();
            return new Reply(Outcome.ACCEPTED, owned.change().subscription().providerReference(), null);
        });
        var secret = "pi_change_secret_memory_fixture";
        when(clientSessions.retrieve(any(), any(), eq(owned.raw()))).thenAnswer(call -> {
            assertThat(tx.active()).isFalse();
            return new SubscriptionClientSession("stripe", SubscriptionClientSession.Flow.PAYMENT, "pk_test_fixture", secret, owned.raw(), owned.grant().expiresAt());
        });
        return new GrantWorkflow(owned, context("confirm-" + key), provider, secret);
    }
    private CreateSubscriptionClientSessionUseCase.Command clientCommand(GrantWorkflow workflow, SubscriptionCommandContext context) {
        return new CreateSubscriptionClientSessionUseCase.Command(context, workflow.owned().change().subscription().id(), workflow.owned().change().operation().id(), workflow.owned().raw());
    }
    private ConfirmSubscriptionActionUseCase.Command confirmationCommand(GrantWorkflow workflow, SubscriptionCommandContext context) {
        return new ConfirmSubscriptionActionUseCase.Command(context, workflow.owned().change().subscription().id(), workflow.owned().change().operation().id(), workflow.owned().raw());
    }
    private Map<String, Object> grantCopy(Map<String, Object> valid) {
        var bad = new LinkedHashMap<>(valid);
        bad.put("id", UUID.randomUUID()); bad.put("token_digest", StripeBillingGrantTokens.hash(nativeReference("grant_"))); bad.put("superseded_at", valid.get("expires_at"));
        return bad;
    }
    private void rejectGrant(Map<String, Object> valid, String column, Object value, String state) {
        var bad = grantCopy(valid); bad.put(column, value);
        sqlState(state, () -> insert("subscription_action_capabilities", bad));
    }
    private SubscriptionOperation legacyOperation(SubscriptionOperation.Kind kind, Subscription s) {
        var id = UUID.randomUUID();
        var fingerprint = kind == SubscriptionOperation.Kind.RESUME ? "v1.resume.none." + "a".repeat(64) : "legacy-setup-" + id;
        var candidate = new SubscriptionOperation(id, site, customer.id(), s.id(), kind, SubscriptionOperation.Status.RESERVED, "legacy-grant-authority", "legacy-" + id, fingerprint,
            "subscription:" + account.id().value() + ":" + id + ":" + kind, s.revision(), policies.load(site).version(), s.providerReference(), null, INITIAL_TIME, INITIAL_TIME, 0);
        return tx.required(() -> operations.claim(candidate).operation());
    }
    private void assertLegacyGrant(Grant legacy) {
        var g = legacy.grant(); var b = g.binding();
        var bytes = SubscriptionClientGrant.canonical(g.id(), g.site().value(), g.customer().value(), g.account().value(), g.subscription(), g.operation(), g.keyVersion(), b.stepId(), b.operationKey(), b.proof(), b.subscriptionReference(), b.customerReference(), b.invoiceReference(), b.allocationReference(), b.intentReference(), g.issuedAt(), g.expiresAt());
        var expected = b.schema().equals("payment-v1") ? bytes : SubscriptionClientGrant.canonical("gp:stripe-billing-descriptor:" + b.schema(), bytes, b.setupReference());
        assertThat(g.descriptor()).isEqualTo(expected);
        reserveGrant(g);
        assertThat(storedGrant(g).descriptor()).isEqualTo(expected);
        var row = jdbc.queryForMap("select * from subscription_action_capabilities where id=?", g.id());
        assertThat(row).containsEntry("billing_change_operation", null);
        rejectGrant(row, "billing_change_operation", g.operation(), "23514");
        var session = ((SubscriptionActor.Customer) actor).session();
        tx.required(() -> { actions.bindSession(g.tokenDigest(), session.sessionId().value(), session.origin(), g.expiresAt(), g.issuedAt()); return null; });
        var key = "legacy-confirm-" + g.operation();
        var claim = tx.required(() -> actions.claimBilling(site, customer.id(), account.id(), g.subscription(), g.operation(), g.tokenDigest(), session.sessionId().value(), session.origin(), "legacy-authority", key, "legacy-fingerprint", g.issuedAt()));
        assertThat(claim).isEqualTo(SubscriptionActionStore.ClaimDisposition.NEW);
        assertThat(tx.required(() -> actions.claimBilling(site, customer.id(), account.id(), g.subscription(), g.operation(), g.tokenDigest(), session.sessionId().value(), session.origin(), "legacy-authority", key, "legacy-fingerprint", g.issuedAt()))).isEqualTo(SubscriptionActionStore.ClaimDisposition.REPLAY);
        assertThat(storedGrant(g).descriptor()).isEqualTo(expected);
        assertThat(storedGrant(g).consumedAt()).isNotNull();
        sqlState("23514", () -> jdbc.update("update subscription_action_capabilities set consumed_at=null where id=?", g.id()));
    }
    private static void subscriptionConflict(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class, failure -> assertThat(failure.code()).isEqualTo("SUBSCRIPTION_CONFLICT"));
    }

    private record Original(Subscription subscription, SubscriptionOperation operation, ProviderOperation creationStep) {}
    private record Prepared(Subscription subscription, SubscriptionCommandContext context, SubscriptionPlanTransaction.Candidate candidate, Quote quote, SubscriptionQuote common) {}
    private record Plan(Subscription subscription, SubscriptionOperation operation, Quote quote, SubscriptionQuote common) {}
    private record Clear(Subscription subscription, SubscriptionOperation operation) {}

    private Original paidOriginal() {
        price = price(0);
        var reserved = commands.reserveCreate(command("plan-create-" + UUID.randomUUID()));
        var creation = acknowledge(reserveStep(reserved.operation(), ProviderOperation.Kind.SUBSCRIPTION_CREATE), nativeReference("sub_"));
        commands.recordReply(reserved.operation().id(), new Reply(Outcome.ACCEPTED, creation.providerReference(), null));
        var incomplete = sub(reserved.subscription().id());
        // Seed an already settled source period. This suite tests receipt SQL, never settlement normalization.
        subscriptions.save(incomplete.confirmPaid(incomplete.billingPrice(), SOURCE_PERIOD, INITIAL_TIME), incomplete.revision());
        finish(reserved.operation());
        return new Original(sub(incomplete.id()), op(reserved.operation().id()), creation);
    }
    private String bindCustomer(Customer owner) {
        var key = "customer:" + account.id().value() + ":" + owner.id().value();
        var reserved = steps.reserve(account, ProviderOperation.Kind.CUSTOMER_CREATE, owner.id().value(), key, "plan-customer-" + owner.id().value());
        var acknowledged = acknowledge(reserved, nativeReference("cus_"));
        return bindings.bindCustomer(account.id(), site, owner.id(), acknowledged.providerReference(), acknowledged.id()).providerReference();
    }
    private Prepared preview(Subscription subscription, ChangeTiming timing, String key) {
        var context = context(key);
        var target = target(subscription, timing);
        var query = new PreviewSubscriptionChangeUseCase.Query(context, subscription.id(), target, timing);
        var candidate = plans.candidate(context, subscription.id());
        var preview = plans.preview(query, candidate, true);
        var source = candidate.subscription();
        var product = nativeReference("prod_");
        var sourcePrice = nativeReference("price_");
        var targetPrice = nativeReference("price_");
        var item = nativeReference("si_");
        var interval = new Subscription.Period(INITIAL_TIME, source.paidThrough());
        var lines = timing == ChangeTiming.IMMEDIATE
            ? List.of(new Line(sourcePrice, product, item, -1000, interval, null, List.of()), new Line(targetPrice, product, item, 2000, interval, null, List.of()))
            : List.<Line>of();
        var quote = new Quote(UUID.randomUUID(), account.id(), site, customer.id(), source.id(), source.providerReference(), customerReference,
            source.billingPrice(), source.entitledPrice(), preview.target(), sourcePrice, targetPrice, product, item, source.currentPeriod(), source.paidThrough(),
            source.revision(), candidate.policy().version(), timing, INITIAL_TIME, INITIAL_TIME.plusSeconds(300), timing == ChangeTiming.IMMEDIATE ? 1000 : 0, lines);
        assertThat(proofs.saveQuote(quote)).isEqualTo(quote);
        var provider = new ProviderQuote(quote.reference(), quote.dueNow(), quote.target().regularAmount().minorUnits(), quote.target().regularAmount().currency(),
            timing == ChangeTiming.IMMEDIATE ? quote.anchor() : quote.period().end(), quote.expiresAt(), true);
        var common = plans.savePreview(query, preview, provider, timing, true);
        return new Prepared(source, context, candidate, quote, common);
    }
    private PriceId target(Subscription s, ChangeTiming timing) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,?,1,'SUBSCRIPTION',?,'EUR','MONTH',0,'OPTIONAL_DURING_TRIAL','NONE','PUBLISHED',?,?,?)",
            id, site.value(), s.productId().value(), "p-" + id, timing == ChangeTiming.IMMEDIATE ? 4000 : 1000, Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME));
        var target = new PriceId(id);
        policies.saveTransition(new PlanTransition(site, s.productId(), s.billingPrice().priceId(), target, timing == ChangeTiming.IMMEDIATE ? PlanTransition.Direction.UPGRADE : PlanTransition.Direction.DOWNGRADE, true, policies.load(site).version()));
        assertThat(catalog.published(site, target).productId()).isEqualTo(s.productId());
        return target;
    }
    private SubscriptionCommandContext context(String key) { return new SubscriptionCommandContext(actor, site, customer.id(), "https://facts.test", key, "task8-plan-proof"); }
    private SubscriptionPlanTransaction.Reserved reserveOnly(Prepared prepared) { return plans.reserve(prepared.context(), prepared.subscription().id(), prepared.common().id(), prepared.candidate(), true, false); }
    private Plan reserve(Prepared prepared) {
        var reserved = reserveOnly(prepared);
        plans.beforeMutation(prepared.context(), reserved, true);
        return new Plan(sub(prepared.subscription().id()), op(reserved.operation().id()), prepared.quote(), prepared.common());
    }
    private Plan plan(Subscription source, ChangeTiming timing, String key) { return reserve(preview(source, timing, key)); }
    private ProviderOperation reserveStep(SubscriptionOperation operation, ProviderOperation.Kind kind) {
        var suffix = switch (kind) { case SCHEDULE_CREATE, SCHEDULE_UPDATE, SCHEDULE_RELEASE -> ":" + kind; default -> ""; };
        return steps.reserve(account, kind, operation.id(), operation.providerOperationKey() + suffix, "plan-proof-" + operation.id() + "-" + kind);
    }
    private ProviderOperation claimed(SubscriptionOperation operation, ProviderOperation.Kind kind) { return claim(reserveStep(operation, kind)); }
    private ProviderOperation claim(ProviderOperation reserved) { return steps.claim(reserved.id(), OWNER, Instant.now(), Duration.ofSeconds(60)).orElseThrow(); }
    private ProviderOperation acknowledge(ProviderOperation reserved, String reference) {
        var claimed = claim(reserved);
        return steps.acknowledge(claimed.id(), OWNER, claimed.version(), reference);
    }
    private SubscriptionPlanProofStore.Result recordResult(Plan change, ProviderOperation step, String invoice) {
        return proofs.recordResult(change.subscription(), change.operation(), change.quote(), step, OWNER, invoice, INITIAL_TIME.plusSeconds(1800).getEpochSecond(), creditLine(change), debitLine(change));
    }
    private static String creditLine(Plan p) { return "il_Credit" + p.operation().id().toString().replace("-", ""); }
    private static String debitLine(Plan p) { return "il_Debit" + p.operation().id().toString().replace("-", ""); }
    private Schedule createSchedule(Plan change, String reference) {
        return proofs.recordSchedule(change.subscription(), change.operation(), change.quote(), claimed(change.operation(), ProviderOperation.Kind.SCHEDULE_CREATE), OWNER, reference, SOURCE_PERIOD.start().getEpochSecond(), new SourcePhase("none", null, "charge_automatically"));
    }
    private long targetEnd(Plan change) { return TARGET_PHASE_END.getEpochSecond(); }
    private Clear clear(Plan change) {
        var s = sub(change.subscription().id());
        var scheduled = new Subscription.Change(change.operation().id(), change.quote().target(), change.quote().period().end(), Subscription.ChangeState.SCHEDULED);
        subscriptions.save(withChange(s, scheduled), s.revision());
        finish(change.operation());
        var context = context("clear-" + change.operation().id());
        var candidate = plans.candidate(context, s.id());
        var reserved = plans.reserve(context, s.id(), null, candidate, true, true);
        plans.beforeMutation(context, reserved, true);
        return new Clear(sub(s.id()), op(reserved.operation().id()));
    }
    private static Subscription withChange(Subscription s, Subscription.Change pending) {
        return new Subscription(s.id(), s.siteId(), s.customerId(), s.productId(), s.paymentAccountId(), s.billingPrice(), s.entitledPrice(), s.billingStatus(), s.providerReference(), s.currentPeriod(), s.paidThrough(), s.trial(), s.cancellation(), pending, s.debt(), s.terminalReason(), s.revision() + 1, s.createdAt(), INITIAL_TIME, s.lastReconciledAt());
    }
    private void finish(SubscriptionOperation operation) {
        tx.required(() -> {
            var o = operations.lock(operation.id());
            return operations.save(new SubscriptionOperation(o.id(), o.siteId(), o.customerId(), o.subscriptionId(), o.kind(), SubscriptionOperation.Status.SUCCEEDED, o.authorityScope(), o.keyDigest(), o.fingerprint(), o.providerOperationKey(), o.expectedRevision(), o.policyVersion(), o.providerReference(), null, o.createdAt(), o.updatedAt(), o.version() + 1), o.version());
        });
    }
    private void assertUnchangedEntitlement(Plan plan) {
        var s = sub(plan.subscription().id());
        assertThat(s.billingPrice()).isEqualTo(plan.quote().source());
        assertThat(s.entitledPrice()).isEqualTo(plan.quote().entitled());
        assertThat(s.currentPeriod()).isEqualTo(plan.quote().period());
        assertThat(s.paidThrough()).isEqualTo(plan.quote().paidThrough());
        assertThat(count("payments")).isZero();
    }
    private Map<String, Object> ownerRow(Plan p) {
        var row = new LinkedHashMap<String, Object>();
        row.put("operation_id", p.operation().id()); row.put("site_id", p.subscription().siteId().value()); row.put("customer_id", p.subscription().customerId().value());
        row.put("subscription_id", p.subscription().id()); row.put("payment_account_id", p.subscription().paymentAccountId().value());
        row.put("quote_id", p.quote().id()); row.put("common_quote_id", p.common().id()); row.put("quote_reference", p.quote().reference());
        return row;
    }
    private Map<String, Object> resultRow(Plan p, ProviderOperation step, String invoice) {
        var row = ownerRow(p);
        row.put("step_id", step.id()); row.put("subscription_reference", p.subscription().providerReference()); row.put("invoice_reference", invoice);
        row.put("pending_expiry", INITIAL_TIME.plusSeconds(1800).getEpochSecond()); row.put("credit_line", creditLine(p)); row.put("debit_line", debitLine(p));
        return row;
    }
    private Map<String, Object> scheduleRow(Plan p, ProviderOperation step, String reference) {
        var row = ownerRow(p);
        row.put("schedule_reference", reference); row.put("creation_step", step.id()); row.put("phase_start", SOURCE_PERIOD.start().getEpochSecond());
        row.put("source_proration_behavior", "none"); row.put("source_default_payment_method", null); row.put("source_collection_method", "charge_automatically");
        return row;
    }
    private Map<String, Object> copiedQuote(Quote q) {
        var row = new LinkedHashMap<>(jdbc.queryForMap("select * from stripe_plan_quotes where id=?", q.id()));
        var next = UUID.randomUUID();
        row.put("id", next); row.put("reference", "bq1:" + next);
        row.put("descriptor", row.get("descriptor").toString().replace(q.id().toString(), next.toString()));
        return row;
    }
    private Map<String, Object> changedDescriptor(Map<String, Object> source, String column, Object value) {
        var row = new LinkedHashMap<>(source);
        var before = row.put(column, value);
        row.put("descriptor", row.get("descriptor").toString().replace(before.toString(), value.toString()));
        return row;
    }
    private Map<String, Object> foreignOwners(Subscription other) {
        var otherCustomer = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        UUID otherAccount = UUID.randomUUID(), otherSite = UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,?,'STRIPE','SANDBOX','ACTIVE','acct_otherPlan','stripe-sandbox-default',now(),0)", otherAccount, "plan-account-" + otherAccount);
        jdbc.update("insert into sites(id,site_key,display_name,status,payment_account_id,payment_method_policy,created_at,updated_at,version) values (?,?,'Other','ACTIVE',?,'AUTOMATIC',now(),now(),0)", otherSite, "plan-site-" + otherSite, otherAccount);
        return Map.of("site_id", otherSite, "customer_id", otherCustomer.id().value(), "subscription_id", other.id(), "payment_account_id", otherAccount);
    }
    private void insert(String table, Map<String, Object> row) {
        var markers = row.keySet().stream().map(key -> key.equals("descriptor") ? "?::jsonb" : "?").toList();
        jdbc.update("insert into " + table + "(" + String.join(",", row.keySet()) + ") values (" + String.join(",", markers) + ")", row.values().toArray());
    }
    private void reject(String table, Map<String, Object> valid, String column, Object value, String state) {
        var bad = new LinkedHashMap<>(valid); bad.put(column, value);
        sqlState(state, () -> insert(table, bad));
    }
    private void rejectProofWrite(String table, ThrowingCallable action) {
        jdbc.execute("create function task8_reject_plan_proof() returns trigger language plpgsql as $$ begin raise exception 'injected plan proof failure' using errcode='23514'; end; $$");
        jdbc.execute("create trigger task8_reject_plan_proof before insert or update on " + table + " for each row execute function task8_reject_plan_proof()");
        try { sqlState("23514", action); }
        finally {
            jdbc.execute("drop trigger task8_reject_plan_proof on " + table);
            jdbc.execute("drop function task8_reject_plan_proof()");
        }
    }
    private boolean resultAfterGate(CyclicBarrier gate, Plan change, ProviderOperation step, String invoice) throws Exception {
        gate.await(10, TimeUnit.SECONDS);
        try { recordResult(change, step, invoice); return true; }
        catch (PaymentApplicationException failure) { assertThat(failure.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"); return false; }
    }
    private static int backendPid(Connection connection) throws SQLException {
        try (var query = connection.createStatement(); var row = query.executeQuery("select pg_backend_pid()")) { row.next(); return row.getInt(1); }
    }
    private int awaitBlockedQuery(int blocker, String table) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            var waiting = jdbc.queryForList("select pid from pg_stat_activity where ?=any(pg_blocking_pids(pid)) and query like ?", Integer.class, blocker, "%" + table + "%");
            if (!waiting.isEmpty()) return waiting.getFirst();
            Thread.sleep(10);
        } while (System.nanoTime() < until);
        throw new AssertionError("Expected actual PostgreSQL lock wait on " + table);
    }
    private static String nativeReference(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", ""); }
    private static void providerConflict(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class, failure -> assertThat(failure.code()).isEqualTo("PROVIDER_OPERATION_CONFLICT"));
    }
    private static void sqlState(String expected, ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DataIntegrityViolationException.class, failure ->
            assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class, cause -> assertThat(cause.getSQLState()).isEqualTo(expected)));
    }
}
