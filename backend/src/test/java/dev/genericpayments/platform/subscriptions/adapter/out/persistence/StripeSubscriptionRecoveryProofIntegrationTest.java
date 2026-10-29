package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingGrantTokens;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.customers.domain.Customer;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionFactFixture;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionRecoveryTransaction;
import dev.genericpayments.platform.subscriptions.domain.*;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** Hosted PostgreSQL only. Real configured stores/Flyway; external provider boundaries alone are fixtures. */
@Import(StripeSubscriptionRecoveryProofIntegrationTest.ExternalPorts.class)
class StripeSubscriptionRecoveryProofIntegrationTest extends SubscriptionFactFixture {
    @TestConfiguration(proxyBeanMethods = false)
    static class ExternalPorts {
        @Bean @Primary SubscriptionProviderRegistry recoveryProviderFixture() { return mock(SubscriptionProviderRegistry.class); }
        @Bean @Primary SubscriptionClientSessionPort recoveryClientFixture() { return mock(SubscriptionClientSessionPort.class); }
    }
    @Autowired SubscriptionRecoveryProofStore proofs;
    @Autowired ProviderOperationStore steps;
    @Autowired StripeBillingBindingPort bindings;
    @Autowired BillingRecordStore bills;
    @Autowired SubscriptionRecoveryTransaction recovery;
    @Autowired SubscriptionActionStore actions;
    @Autowired SubscriptionClientSessionPort clientSessions;
    @Autowired CreateSubscriptionClientSessionUseCase clientUseCase;
    @Autowired ConfirmSubscriptionActionUseCase confirmations;
    static final String OWNER = "recovery-proof-owner";
    static final String TABLE = "stripe_subscription_recovery_proofs";
    static final Instant PAID_THROUGH = INITIAL_TIME;
    static final Instant GRACE = INITIAL_TIME.plusSeconds(3 * 86400);
    Original original;
    SubscriptionProviderPort provider;
    String customerReference;

    @BeforeEach void recoveryFixture() {
        reset(providers, clientSessions);
        provider = mock(SubscriptionProviderPort.class);
        when(providers.forAccount(any())).thenReturn(provider);
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_RecoveryProof',credentials_ref='stripe-sandbox-default' where id=?", account.id().value());
        account = accounts.findById(account.id()).orElseThrow();
        var customerKey = "customer:" + account.id().value() + ":" + customer.id().value();
        var customerStep = steps.reserve(account, ProviderOperation.Kind.CUSTOMER_CREATE, customer.id().value(), customerKey, "recovery-customer");
        var customerAck = acknowledge(customerStep, nativeReference("cus_"));
        customerReference = bindings.bindCustomer(account.id(), site, customer.id(), customerAck.providerReference(), customerAck.id()).providerReference();
        original = original();
        TestClock.set(INITIAL_TIME.plusSeconds(60));
        when(provider.retrieve(any())).thenAnswer(call -> {
            assertThat(tx.active()).as("Provider read is outside platform transactions").isFalse();
            var lookup = (Lookup) call.getArgument(0);
            return snapshot(sub(lookup.subscriptionId()));
        });
    }

    @Test void existingActionAssociationSavesExactReceiptWithoutPayStepAckCashOrGraceExtension() {
        var p = pending(original, "association");
        var before = sub(p.subscription().id());
        var result = associate(p);
        assertThat(result).isEqualTo(new SubscriptionRecoveryProofStore.Receipt(p.operation().id(), before.id(), account.id(), p.bill().id(), before.providerReference(), p.bill().providerInvoiceReference(), original.operation().id(), original.step().id(), null, allocation(p), intent(p)));
        assertThat(associate(p)).isEqualTo(result);
        assertThat(proofs.receipt(account.id(), p.operation().id())).contains(result);
        assertThat(proofs.receipt(new PaymentAccountId(UUID.randomUUID()), p.operation().id())).isEmpty();
        assertThat(proofs.bill(account.id(), before.id(), p.bill().id())).contains(p.bill());
        assertThat(proofs.origin(account.id(), before.id())).contains(p.origin());
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='INVOICE_PAY'", Integer.class)).isZero();
        assertThat(count(TABLE)).isEqualTo(1);
        assertUnchanged(before);
    }

    @Test void recoveryReceiptSqlRequiresExactCreateBillSubscriptionAccountAndPayDiscriminant() {
        var p = pending(original, "foreign-proof");
        var other = original();
        var foreign = pending(other, "foreign-proof-other");
        var foreignAccount = UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,?,'STRIPE','SANDBOX','ACTIVE','acct_ForeignRecovery','stripe-sandbox-default',now(),0)", foreignAccount, "recovery-account-" + foreignAccount);
        var foreignCustomer = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        var row = receiptRow(p, null);
        reject(row, "payment_account_id", foreignAccount, "23503");
        reject(row, "customer_id", foreignCustomer.id().value(), "23503");
        reject(row, "origin_operation", other.operation().id(), "23503");
        reject(row, "origin_step", other.step().id(), "23503");
        reject(row, "billing_record_id", foreign.bill().id(), "23503");
        reject(row, "subscription_id", other.subscription().id(), "23503");
        reject(row, "invoice_reference", foreign.bill().providerInvoiceReference(), "23503");
        reject(row, "operation_id", original.operation().id(), "23503");
        reject(row, "operation_kind", "CREATE", "23514");
        reject(row, "origin_operation_kind", "RECOVER", "23514");
        reject(row, "allocation_reference", null, "23514");
        reject(row, "intent_reference", "pi_bad_reference", "23514");
        var unacknowledged = payStep(p);
        var payRow = receiptRow(p, unacknowledged.id());
        sqlState("23503", () -> insert(TABLE, payRow));
        reject(row, "pay_kind", "INVOICE_PAY", "23514");
        insert(TABLE, row);
        assertThat(proofs.receipt(account.id(), p.operation().id())).hasValueSatisfying(r -> assertThat(r.payStep()).isNull());
    }

    @Test void recoveryReceiptCannotBeReboundAfterItsOriginalAssociation() {
        var p = pending(original, "immutable-proof");
        var saved = associate(p);
        sqlState("23514", () -> jdbc.update("update " + TABLE + " set allocation_reference='inpay_Replaced' where operation_id=?", p.operation().id()));
        sqlState("23514", () -> jdbc.update("update " + TABLE + " set billing_record_id=? where operation_id=?", UUID.randomUUID(), p.operation().id()));
        evidenceConflict(() -> proofs.associate(p.subscription(), p.operation(), p.bill(), p.origin(), "inpay_Replaced", intent(p)));
        assertThat(proofs.receipt(account.id(), p.operation().id())).contains(saved);
        assertUnchanged(p.subscription());
    }

    @Test void actualPayAcknowledgementAndReceiptCommitAtomicallyAndRetryUsesSameStep() {
        var p = pending(original, "atomic-pay");
        var claimed = claim(payStep(p));
        jdbc.execute("create function task9_reject_recovery() returns trigger language plpgsql as $$ begin raise exception 'injected receipt failure' using errcode='23514'; end; $$");
        jdbc.execute("create trigger task9_reject_recovery before insert on " + TABLE + " for each row execute function task9_reject_recovery()");
        try { sqlState("23514", () -> recordPay(p, claimed, OWNER)); }
        finally {
            jdbc.execute("drop trigger task9_reject_recovery on " + TABLE);
            jdbc.execute("drop function task9_reject_recovery()");
        }
        assertThat(steps.findById(claimed.id())).contains(claimed);
        assertThat(proofs.receipt(account.id(), p.operation().id())).isEmpty();
        var saved = recordPay(p, claimed, OWNER);
        assertThat(saved.payStep()).isEqualTo(claimed.id());
        var ack = steps.findById(claimed.id()).orElseThrow();
        assertThat(ack.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(ack.providerReference()).isEqualTo(p.bill().providerInvoiceReference());
        assertThat(count(TABLE)).isEqualTo(1);
        assertUnchanged(p.subscription());
    }

    @Test void actualPayReceiptRequiresLiveOriginalOwnerAndExactStepVersion() {
        var p = pending(original, "lease-pay");
        var reserved = payStep(p);
        var claimed = claim(reserved);
        providerConflict(() -> recordPay(p, claimed, "foreign-owner"));
        providerConflict(() -> recordPay(p, reserved, OWNER));
        jdbc.update("update provider_operations set lease_expires_at=clock_timestamp()-interval '1 second' where id=?", claimed.id());
        var expired = steps.findById(claimed.id()).orElseThrow();
        providerConflict(() -> recordPay(p, expired, OWNER));
        assertThat(steps.findById(claimed.id()).orElseThrow().status()).isEqualTo(ProviderOperation.Status.DISPATCHING);
        assertThat(proofs.receipt(account.id(), p.operation().id())).isEmpty();
    }

    @Test void associationCannotReplaceAnAlreadyDispatchedPayWithAnExistingAction() {
        var p = pending(original, "association-after-pay");
        var dispatched = claim(payStep(p));
        evidenceConflict(() -> associate(p));
        assertThat(steps.findById(dispatched.id())).contains(dispatched);
        assertThat(proofs.receipt(account.id(), p.operation().id())).isEmpty();
    }

    @Test void originalUnknownRecoveryReplaysItsOperationAndExcludesAnotherKey() {
        var p = pending(original, "unknown-original");
        var pay = claim(payStep(p));
        steps.unknown(pay.id(), OWNER, pay.version());
        recovery.recordReply(p.operation().id(), new Reply(Outcome.UNKNOWN, p.subscription().providerReference(), null));
        var replay = recovery.reserve(p.command());
        assertThat(replay.owner()).isFalse();
        assertThat(replay.operation().id()).isEqualTo(p.operation().id());
        assertThat(replay.operation().providerOperationKey()).isEqualTo(p.operation().providerOperationKey());
        ruleConflict(() -> recovery.reserve(new RecoverSubscriptionUseCase.Command(context("unknown-successor"), p.subscription().id(), p.bill().id())));
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='INVOICE_PAY'", Integer.class)).isEqualTo(1);
        assertThat(proofs.receipt(account.id(), p.operation().id())).isEmpty();
        assertUnchanged(p.subscription());
    }

    @Test void everDispatchedUnknownPayExcludesAnotherInvoiceOwnerEvenAfterLifecycleFailure() {
        var p = pending(original, "failed-unknown-pay");
        var pay = claim(payStep(p));
        steps.unknown(pay.id(), OWNER, pay.version());
        recovery.recordReply(p.operation().id(), new Reply(Outcome.DECLINED, p.subscription().providerReference(), null));
        var next = pending(original, "new-owner-after-failure");
        assertThat(proofs.available(account.id(), next.subscription().id(), next.bill().providerInvoiceReference(), next.operation().id())).isFalse();
        evidenceConflict(() -> associate(next));
        assertThat(proofs.receipt(account.id(), next.operation().id())).isEmpty();
    }

    @Test void savedAssociationExcludesSuccessorOwnerWithoutInventingAPayStep() {
        var p = pending(original, "retained-association"); var saved = associate(p);
        recovery.recordReply(p.operation().id(), new Reply(Outcome.DECLINED, p.subscription().providerReference(), null));
        var successor = pending(original, "cannot-adopt-association");
        assertThat(proofs.available(account.id(), successor.subscription().id(), successor.bill().providerInvoiceReference(), successor.operation().id())).isFalse();
        evidenceConflict(() -> associate(successor));
        assertThat(proofs.receipt(account.id(), p.operation().id())).contains(saved);
        assertThat(proofs.receipt(account.id(), successor.operation().id())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='INVOICE_PAY'", Integer.class)).isZero();
        assertUnchanged(p.subscription());
    }

    @Test void receiptAuthorityRejectsChangedCurrentPolicyRevisionAccountAndAlias() {
        var p = pending(original, "receipt-current");
        for (var mutation : List.of(
            Map.entry("account_mode", "LIVE"), Map.entry("account_status", "DISABLED"),
            Map.entry("external_account_id", "acct_ForeignRecovery"), Map.entry("credentials_ref", "foreign-alias"))) {
            var before = jdbc.queryForObject("select " + mutation.getKey() + " from payment_accounts where id=?", String.class, account.id().value());
            jdbc.update("update payment_accounts set " + mutation.getKey() + "=? where id=?", mutation.getValue(), account.id().value());
            try { evidenceConflict(() -> associate(p)); }
            finally { jdbc.update("update payment_accounts set " + mutation.getKey() + "=? where id=?", before, account.id().value()); }
        }
        jdbc.update("update site_subscription_policies set version=version+1 where site_id=?", site.value());
        try { evidenceConflict(() -> associate(p)); }
        finally { jdbc.update("update site_subscription_policies set version=version-1 where site_id=?", site.value()); }
        jdbc.update("update subscriptions set revision=revision+1 where id=?", p.subscription().id());
        evidenceConflict(() -> associate(p));
        assertThat(count(TABLE)).isZero();
    }

    @Test void freshAuthorityIsRecheckedAfterExternalRecoveryRead() {
        var s = original.subscription();
        when(provider.retrieve(any())).thenAnswer(call -> {
            assertThat(tx.active()).isFalse();
            jdbc.update("update customer_sessions set revoked_at=?,revoked_reason='ADMIN' where revoked_at is null", Timestamp.from(INITIAL_TIME.plusSeconds(60)));
            return snapshot(s);
        });
        assertThatThrownBy(() -> pending(original, "revoked-after-read")).isInstanceOf(PaymentApplicationException.class);
        assertThat(jdbc.queryForObject("select count(*) from subscription_operations where kind='RECOVER'", Integer.class)).isZero();
        assertThat(count(TABLE)).isZero();
    }

    @Test void recoveryGrantPreservesPriorCreateConsentAndExactDistinctReceiptResource() {
        var policy = policies.load(site);
        policies.save(new SubscriptionPolicy(site, policy.version(), true, 4, false, false, false, Set.of()), policy.version());
        var p = pending(original, "grant-provenance");
        assertThat(p.operation().policyVersion()).isNotEqualTo(original.operation().policyVersion());
        associate(p);
        var g = grant(p);
        reserveGrant(g.grant());
        var row = jdbc.queryForMap("select * from subscription_action_capabilities where id=?", g.grant().id());
        assertThat(row).containsEntry("billing_schema", "recovery-v1").containsEntry("consent_purpose", "RECOVER")
            .containsEntry("billing_step_id", original.step().id()).containsEntry("billing_step_resource_id", original.operation().id())
            .containsEntry("billing_recovery_operation", p.operation().id()).containsEntry("consent_policy_version", original.operation().policyVersion());
        assertThat(((Timestamp) row.get("consent_at")).toInstant()).isEqualTo(original.operation().createdAt()).isNotEqualTo(p.operation().createdAt());
        assertThat(storedGrant(g.grant()).descriptor()).isEqualTo(g.grant().descriptor());
        assertThat(storedGrant(g.grant()).binding().schema()).isEqualTo("recovery-v1");
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='INVOICE_PAY'", Integer.class)).isZero();
        sqlState("23514", () -> jdbc.update("update subscription_action_capabilities set billing_step_resource_id=operation_id where id=?", g.grant().id()));
        assertUnchanged(p.subscription());
    }

    @Test void recoveryGrantNeedsActualReceiptAndRejectsLegacyOrForeignAllocationBindings() {
        var p = pending(original, "grant-proof-required");
        var g = grant(p);
        storeConflict(() -> reserveGrant(g.grant()));
        associate(p);
        var b = g.grant().binding();
        var foreign = new SubscriptionClientGrant.Binding(b.stepId(), b.operationKey(), b.proof(), b.subscriptionReference(), b.customerReference(), b.invoiceReference(), "inpay_Foreign", b.intentReference(), "recovery-v1", null);
        storeConflict(() -> reserveGrant(withBinding(g.grant(), foreign)));
        var legacy = new SubscriptionClientGrant.Binding(b.stepId(), b.operationKey(), b.proof(), b.subscriptionReference(), b.customerReference(), b.invoiceReference(), b.allocationReference(), b.intentReference());
        storeConflict(() -> reserveGrant(withBinding(g.grant(), legacy)));
        assertThat(count("subscription_action_capabilities")).isZero();
    }

    @Test void recoveryGrantSqlRequiresReceiptCompositeForeignKeyAndConsentDiscriminant() {
        var p = pending(original, "grant-sql"); associate(p);
        var g = grant(p); reserveGrant(g.grant());
        var row = jdbc.queryForMap("select * from subscription_action_capabilities where id=?", g.grant().id());
        rejectGrant(row, "billing_recovery_operation", null, "23514");
        rejectGrant(row, "billing_step_resource_id", p.operation().id(), "23503");
        rejectGrant(row, "billing_allocation_reference", "inpay_Foreign", "23503");
        rejectGrant(row, "billing_intent_reference", "pi_Foreign", "23503");
        rejectGrant(row, "consent_purpose", "CREATE", "23514");
        rejectGrant(row, "recurring_consent", false, "23514");
        rejectGrant(row, "billing_schema", "payment-v1", "23514");
        assertThat(count("subscription_action_capabilities")).isEqualTo(1);
    }

    @Test void configuredRecoveryClientSessionAndConfirmationGiveOnlyNewClaimBrowserPermission() {
        var w = workflow("public-new");
        var issued = clientUseCase.issue(clientCommand(w));
        assertThat(issued.flow()).isEqualTo(SubscriptionClientSession.Flow.PAYMENT);
        assertThat(issued.actionToken()).isEqualTo(w.grant().raw());
        var first = confirmations.confirm(confirmCommand(w));
        var replay = confirmations.confirm(confirmCommand(w));
        assertThat(first.browserConfirmAllowed()).isTrue();
        assertThat(replay.browserConfirmAllowed()).isFalse();
        assertThat(first.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        assertThat(storedGrant(w.grant().grant()).consumedAt()).isNotNull();
        assertThat(proofs.available(account.id(), w.pending().subscription().id(), w.pending().bill().providerInvoiceReference(), w.pending().operation().id())).isFalse();
        storeConflict(() -> reserveGrant(grant(w.pending()).grant()));
        evidenceConflict(() -> associate(w.pending()));
        assertThat(jdbc.queryForObject("select row_to_json(g)::text from subscription_action_capabilities g where id=?", String.class, w.grant().grant().id())).doesNotContain(w.grant().raw(), "pi_recovery_secret_memory_fixture");
        assertUnchanged(w.pending().subscription());
    }

    @Test void retainedRecoveryGrantCannotReleaseClientMaterialAfterEnabledPolicyVersionChanges() {
        var w = workflow("retained-client-policy");
        var before = storedGrant(w.grant().grant());
        var receipt = proofs.receipt(account.id(), w.pending().operation().id()).orElseThrow();
        changeEnabledPolicy(w);
        ruleConflict(() -> clientUseCase.issue(clientCommand(w)));
        assertThat(storedGrant(w.grant().grant())).isEqualTo(before);
        assertThat(proofs.receipt(account.id(), w.pending().operation().id())).contains(receipt);
        verify(clientSessions, never()).retrieve(any(), any(), anyString());
        assertUnchanged(w.pending().subscription());
    }

    @Test void retainedBoundRecoveryGrantCannotBeNewlyConsumedAfterEnabledPolicyVersionChanges() {
        var w = workflow("retained-confirm-policy");
        clientUseCase.issue(clientCommand(w));
        var before = storedGrant(w.grant().grant());
        assertThat(before.sessionId()).isNotNull();
        assertThat(before.consumedAt()).isNull();
        changeEnabledPolicy(w);
        ruleConflict(() -> confirmations.confirm(confirmCommand(w)));
        assertThat(storedGrant(w.grant().grant())).isEqualTo(before);
        verify(provider, never()).confirmAction(any(), anyString());
        assertUnchanged(w.pending().subscription());
    }

    @Test void policyVersionChangingDuringExternalClientReadPreventsFinalRecoverySessionBinding() {
        var w = workflow("during-client-policy");
        var before = storedGrant(w.grant().grant());
        var externalRead = new java.util.concurrent.atomic.AtomicBoolean();
        when(clientSessions.retrieve(any(), any(), eq(w.grant().raw()))).thenAnswer(call -> {
            assertThat(tx.active()).isFalse();
            externalRead.set(true);
            changeEnabledPolicy(w);
            return new SubscriptionClientSession("stripe", SubscriptionClientSession.Flow.PAYMENT, "pk_test_fixture", "pi_recovery_secret_memory_fixture", w.grant().raw(), w.grant().grant().expiresAt());
        });
        ruleConflict(() -> clientUseCase.issue(clientCommand(w)));
        assertThat(externalRead).isTrue();
        assertThat(storedGrant(w.grant().grant())).isEqualTo(before);
        assertUnchanged(w.pending().subscription());
    }

    @Test void policyVersionChangingAfterNewClaimNeverReturnsRecoveryBrowserPermissionOrReopensGrant() {
        var w = workflow("after-new-policy");
        clientUseCase.issue(clientCommand(w));
        var before = storedGrant(w.grant().grant());
        var receipt = proofs.receipt(account.id(), w.pending().operation().id()).orElseThrow();
        var acknowledged = new java.util.concurrent.atomic.AtomicBoolean();
        when(provider.confirmAction(any(), eq(w.grant().raw()))).thenAnswer(call -> {
            assertThat(tx.active()).isFalse();
            assertThat(storedGrant(w.grant().grant()).consumedAt()).isNotNull();
            acknowledged.set(true);
            changeEnabledPolicy(w);
            return new Reply(Outcome.ACCEPTED, w.pending().subscription().providerReference(), null);
        });
        var result = confirmations.confirm(confirmCommand(w));
        assertThat(acknowledged).isTrue();
        assertThat(result.browserConfirmAllowed()).isFalse();
        var spent = storedGrant(w.grant().grant());
        assertThat(spent.consumedAt()).isNotNull();
        assertThat(spent.descriptor()).isEqualTo(before.descriptor());
        assertThat(spent.sessionId()).isEqualTo(before.sessionId());
        assertThat(proofs.receipt(account.id(), w.pending().operation().id())).contains(receipt);
        assertThat(confirmations.confirm(confirmCommand(w)).browserConfirmAllowed()).isFalse();
        assertThat(storedGrant(w.grant().grant()).consumedAt()).isEqualTo(spent.consumedAt());
        assertUnchanged(w.pending().subscription());
    }

    @Test void lostRecoveryConfirmationAckNeverReopensConsumedBrowserPermission() {
        var w = workflow("lost-ack"); clientUseCase.issue(clientCommand(w));
        doThrow(new IllegalStateException("lost original acknowledgement")).when(provider).confirmAction(any(), eq(w.grant().raw()));
        var first = confirmations.confirm(confirmCommand(w));
        assertThat(first.status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);
        assertThat(first.browserConfirmAllowed()).isFalse();
        assertThat(confirmations.confirm(confirmCommand(w)).browserConfirmAllowed()).isFalse();
        assertThat(storedGrant(w.grant().grant()).consumedAt()).isNotNull();
        ruleConflict(() -> recovery.reserve(new RecoverSubscriptionUseCase.Command(context("lost-ack-successor"), w.pending().subscription().id(), w.pending().bill().id())));
        assertUnchanged(w.pending().subscription());
    }

    @Test void revokedSessionDuringExternalClientReadCannotBindOrConsumeRecoveryGrant() {
        var w = workflow("client-authority-gap");
        when(clientSessions.retrieve(any(), any(), eq(w.grant().raw()))).thenAnswer(call -> {
            assertThat(tx.active()).isFalse();
            jdbc.update("update customer_sessions set revoked_at=?,revoked_reason='ADMIN' where revoked_at is null", Timestamp.from(Instant.now()));
            return new SubscriptionClientSession("stripe", SubscriptionClientSession.Flow.PAYMENT, "pk_test_fixture", "pi_recovery_secret_memory_fixture", w.grant().raw(), w.grant().grant().expiresAt());
        });
        assertThatThrownBy(() -> clientUseCase.issue(clientCommand(w))).isInstanceOfSatisfying(PaymentApplicationException.class, e -> {
            assertThat(e.code()).isNotBlank();
            assertThat(e.getMessage()).doesNotContain(w.grant().raw(), "pi_recovery_secret_memory_fixture");
        });
        var stored = storedGrant(w.grant().grant());
        assertThat(stored.sessionId()).isNull();
        assertThat(stored.consumedAt()).isNull();
        assertUnchanged(w.pending().subscription());
    }

    @Test void concurrentRecoveryConfirmationsHaveOneNewBrowserPermission() throws Exception {
        var w = workflow("concurrent-confirm"); clientUseCase.issue(clientCommand(w));
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); return confirmations.confirm(confirmCommand(w)).browserConfirmAllowed(); });
            var b = pool.submit(() -> { gate.await(10, TimeUnit.SECONDS); return confirmations.confirm(confirmCommand(w)).browserConfirmAllowed(); });
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(storedGrant(w.grant().grant()).consumedAt()).isNotNull();
        sqlState("23514", () -> jdbc.update("update subscription_action_capabilities set consumed_at=null where id=?", w.grant().grant().id()));
        assertThat(count(TABLE)).isEqualTo(1);
        assertUnchanged(w.pending().subscription());
    }

    @Test void payReceiptRechecksPinnedAccountAfterActualPostgresLockWait() throws Exception {
        var p = pending(original, "account-lock"); var pay = claim(payStep(p));
        try (var blocker = jdbc.getDataSource().getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            int pid;
            try (var q = blocker.createStatement(); var r = q.executeQuery("select pg_backend_pid()")) { r.next(); pid = r.getInt(1); }
            try (var lock = blocker.prepareStatement("select id from payment_accounts where id=? for update")) { lock.setObject(1, account.id().value()); lock.executeQuery().close(); }
            var result = pool.submit(() -> { try { recordPay(p, pay, OWNER); return "committed"; } catch (PaymentApplicationException e) { return e.code(); } });
            try {
                awaitAccountWait(pid);
                try (var update = blocker.prepareStatement("update payment_accounts set credentials_ref='revoked-recovery-alias' where id=?")) { update.setObject(1, account.id().value()); update.executeUpdate(); }
                blocker.commit();
                assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo("PROVIDER_EVIDENCE_MISMATCH");
            } finally { blocker.rollback(); }
        }
        assertThat(steps.findById(pay.id())).contains(pay);
        assertThat(proofs.receipt(account.id(), p.operation().id())).isEmpty();
        assertUnchanged(p.subscription());
    }

    private record Original(Subscription subscription, SubscriptionOperation operation, ProviderOperation step) {}
    private record Pending(Subscription subscription, SubscriptionOperation operation, BillingRecord bill, SubscriptionRecoveryProofStore.Origin origin, RecoverSubscriptionUseCase.Command command) {}
    private record Grant(SubscriptionClientGrant grant, String raw) {}
    private record Workflow(Pending pending, Grant grant, SubscriptionCommandContext context) {}

    private Original original() {
        price = price(0);
        var admitted = commands.reserveCreate(command("recovery-create-" + UUID.randomUUID()));
        var reserved = steps.reserve(account, ProviderOperation.Kind.SUBSCRIPTION_CREATE, admitted.operation().id(), admitted.operation().providerOperationKey(), "recovery-create-proof-" + admitted.operation().id());
        var ack = acknowledge(reserved, nativeReference("sub_"));
        commands.recordReply(admitted.operation().id(), new Reply(Outcome.ACCEPTED, ack.providerReference(), null));
        var incomplete = sub(admitted.subscription().id());
        var at = admitted.operation().createdAt();
        // Seed a settled source period, as in the Plan SQL fixture. This does not claim native CASH normalization.
        subscriptions.save(incomplete.confirmPaid(incomplete.billingPrice(), new Subscription.Period(INITIAL_TIME.minusSeconds(30 * 86400), PAID_THROUGH), at), incomplete.revision());
        finish(admitted.operation().id());
        var s = sub(incomplete.id());
        var bill = bills.upsert(new BillingRecord(UUID.randomUUID(), site, s.id(), account.id(), nativeReference("in_"), BillingRecord.Kind.RENEWAL, s.billingPrice(), null,
            new Subscription.Period(PAID_THROUGH, PAID_THROUGH.plusSeconds(30 * 86400)), 2000, "EUR", BillingRecord.Settlement.OPEN, 0, at, at));
        subscriptions.save(s.renewalFailed(bill.id(), bill.period(), 3, at), s.revision());
        return new Original(sub(s.id()), op(admitted.operation().id()), ack);
    }
    private Pending pending(Original source, String key) {
        var s = sub(source.subscription().id()); var bill = tx.required(() -> bills.lock(site, s.debt().billingRecordId()));
        var command = new RecoverSubscriptionUseCase.Command(context(key), s.id(), bill.id());
        var reserved = recovery.reserve(command);
        assertThat(reserved.owner()).isTrue();
        recovery.recordReply(reserved.operation().id(), new Reply(Outcome.ACCEPTED, s.providerReference(), null));
        return new Pending(sub(s.id()), op(reserved.operation().id()), bill, proofs.origin(account.id(), s.id()).orElseThrow(), command);
    }
    private Snapshot snapshot(Subscription s) {
        var bill = tx.required(() -> bills.lock(site, s.debt().billingRecordId()));
        var invoice = new Invoice(bill.providerInvoiceReference(), bill.kind(), bill.billedPrice(), null, bill.period(), 2000, "EUR", SettlementKind.OPEN, List.of());
        return new Snapshot(s.providerReference(), s.billingStatus(), s.billingPrice(), s.currentPeriod(), s.trial(), null, List.of(invoice), null, true, 0, INITIAL_TIME.plusSeconds(60));
    }
    private SubscriptionCommandContext context(String key) { return new SubscriptionCommandContext(actor, site, customer.id(), "https://facts.test", key, "task9-recovery-sql"); }
    private ProviderOperation payStep(Pending p) { return steps.reserve(account, ProviderOperation.Kind.INVOICE_PAY, p.operation().id(), p.operation().providerOperationKey() + ":INVOICE_PAY", "recovery-pay-" + p.operation().id()); }
    private ProviderOperation claim(ProviderOperation p) { return steps.claim(p.id(), OWNER, Instant.now(), Duration.ofSeconds(60)).orElseThrow(); }
    private ProviderOperation acknowledge(ProviderOperation p, String reference) { var claimed = claim(p); return steps.acknowledge(claimed.id(), OWNER, claimed.version(), reference); }
    private SubscriptionRecoveryProofStore.Receipt associate(Pending p) { return proofs.associate(p.subscription(), p.operation(), p.bill(), p.origin(), allocation(p), intent(p)); }
    private SubscriptionRecoveryProofStore.Receipt recordPay(Pending p, ProviderOperation step, String owner) { return proofs.recordPay(p.subscription(), p.operation(), p.bill(), p.origin(), step, owner, null, null); }
    private static String allocation(Pending p) { return "inpay_" + p.operation().id().toString().replace("-", ""); }
    private static String intent(Pending p) { return "pi_" + p.operation().id().toString().replace("-", ""); }
    private void finish(UUID id) {
        tx.required(() -> { var o = operations.lock(id); operations.save(new SubscriptionOperation(o.id(), o.siteId(), o.customerId(), o.subscriptionId(), o.kind(), SubscriptionOperation.Status.SUCCEEDED, o.authorityScope(), o.keyDigest(), o.fingerprint(), o.providerOperationKey(), o.expectedRevision(), o.policyVersion(), o.providerReference(), null, o.createdAt(), o.updatedAt(), o.version() + 1), o.version()); return null; });
    }
    private Grant grant(Pending p) {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var raw = "bga1_" + UUID.randomUUID().toString().replace("-", "") + "R".repeat(11);
        var binding = new SubscriptionClientGrant.Binding(p.origin().step(), p.operation().providerOperationKey(), "a".repeat(64), p.subscription().providerReference(), customerReference, p.bill().providerInvoiceReference(), allocation(p), intent(p), "recovery-v1", null);
        return new Grant(new SubscriptionClientGrant(UUID.randomUUID(), site, customer.id(), account.id(), p.subscription().id(), p.operation().id(), "v1", binding, StripeBillingGrantTokens.hash(raw), now, now.plusSeconds(600), null, null, null, null, null), raw);
    }
    private static SubscriptionClientGrant withBinding(SubscriptionClientGrant g, SubscriptionClientGrant.Binding binding) { return new SubscriptionClientGrant(g.id(), g.site(), g.customer(), g.account(), g.subscription(), g.operation(), g.keyVersion(), binding, g.tokenDigest(), g.issuedAt(), g.expiresAt(), null, null, null, null, null); }
    private void reserveGrant(SubscriptionClientGrant g) { tx.required(() -> { operations.lock(g.operation()); actions.reserveBilling(g); return null; }); }
    private SubscriptionClientGrant storedGrant(SubscriptionClientGrant g) { return tx.required(() -> actions.billing(g.tokenDigest()).orElseThrow()); }
    private Workflow workflow(String key) {
        var p = pending(original, key); associate(p); var g = grant(p); reserveGrant(g.grant());
        TestClock.set(Instant.now());
        actor = new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site, customer.id(), "https://facts.test").rawToken(), "https://facts.test"));
        when(provider.nextAction(any())).thenAnswer(call -> { assertThat(tx.active()).isFalse(); return new NextAction(ActionKind.PROVIDER_CLIENT, g.raw(), g.grant().expiresAt()); });
        when(provider.confirmAction(any(), eq(g.raw()))).thenAnswer(call -> { assertThat(tx.active()).isFalse(); assertThat(storedGrant(g.grant()).consumedAt()).isNotNull(); return new Reply(Outcome.ACCEPTED, p.subscription().providerReference(), null); });
        when(clientSessions.retrieve(any(), any(), eq(g.raw()))).thenAnswer(call -> { assertThat(tx.active()).isFalse(); return new SubscriptionClientSession("stripe", SubscriptionClientSession.Flow.PAYMENT, "pk_test_fixture", "pi_recovery_secret_memory_fixture", g.raw(), g.grant().expiresAt()); });
        return new Workflow(p, g, context("confirm-" + key));
    }
    private CreateSubscriptionClientSessionUseCase.Command clientCommand(Workflow w) { return new CreateSubscriptionClientSessionUseCase.Command(w.context(), w.pending().subscription().id(), w.pending().operation().id(), w.grant().raw()); }
    private ConfirmSubscriptionActionUseCase.Command confirmCommand(Workflow w) { return new ConfirmSubscriptionActionUseCase.Command(w.context(), w.pending().subscription().id(), w.pending().operation().id(), w.grant().raw()); }
    private void changeEnabledPolicy(Workflow w) {
        var current = policies.load(site);
        assertThat(current.enabled()).isTrue();
        assertThat(current.version()).isEqualTo(w.pending().operation().policyVersion());
        var saved = policies.save(new SubscriptionPolicy(site, current.version(), true, current.graceDays() + 1,
            current.allowImmediateCancellation(), current.portalEnabled(), current.deferUpgrades(), current.portalReturnUrls()), current.version());
        assertThat(saved.enabled()).isTrue();
        assertThat(saved.version()).isEqualTo(w.pending().operation().policyVersion() + 1);
        assertThat(op(w.pending().operation().id()).policyVersion()).isEqualTo(w.pending().operation().policyVersion());
    }
    private void assertUnchanged(Subscription before) {
        assertThat(sub(before.id())).isEqualTo(before);
        assertThat(sub(before.id()).paidThrough()).isEqualTo(PAID_THROUGH);
        assertThat(sub(before.id()).debt().graceDeadline()).isEqualTo(GRACE);
        for (var table : List.of("payments", "payment_attempts", "provider_charge_receipts", "refunds", "payment_events", "outbox_events")) assertThat(count(table)).as(table).isZero();
    }
    private Map<String, Object> receiptRow(Pending p, UUID pay) {
        var row = new LinkedHashMap<String, Object>();
        row.put("operation_id", p.operation().id()); row.put("site_id", site.value()); row.put("customer_id", customer.id().value()); row.put("subscription_id", p.subscription().id()); row.put("payment_account_id", account.id().value());
        row.put("operation_kind", "RECOVER"); row.put("billing_record_id", p.bill().id()); row.put("subscription_reference", p.subscription().providerReference()); row.put("invoice_reference", p.bill().providerInvoiceReference());
        row.put("origin_operation", p.origin().operation()); row.put("origin_operation_kind", "CREATE"); row.put("origin_step", p.origin().step()); row.put("origin_kind", "SUBSCRIPTION_CREATE");
        row.put("pay_step", pay); row.put("pay_kind", pay == null ? null : "INVOICE_PAY"); row.put("allocation_reference", pay == null ? allocation(p) : null); row.put("intent_reference", pay == null ? intent(p) : null);
        return row;
    }
    private void insert(String table, Map<String, Object> row) { jdbc.update("insert into " + table + "(" + String.join(",", row.keySet()) + ") values (" + String.join(",", Collections.nCopies(row.size(), "?")) + ")", row.values().toArray()); }
    private void reject(Map<String, Object> row, String column, Object value, String state) { var bad = new LinkedHashMap<>(row); bad.put(column, value); sqlState(state, () -> insert(TABLE, bad)); }
    private void rejectGrant(Map<String, Object> row, String column, Object value, String state) {
        var bad = new LinkedHashMap<>(row); bad.put("id", UUID.randomUUID()); bad.put("token_digest", StripeBillingGrantTokens.hash(nativeReference("grant_"))); bad.put("superseded_at", row.get("expires_at")); bad.put(column, value);
        sqlState(state, () -> insert("subscription_action_capabilities", bad));
    }
    private void awaitAccountWait(int blocker) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            var blocked = jdbc.queryForList("select pid from pg_stat_activity where ?=any(pg_blocking_pids(pid)) and query like '%payment_accounts%'", Integer.class, blocker);
            if (!blocked.isEmpty()) return;
            Thread.sleep(10);
        } while (System.nanoTime() < until);
        throw new AssertionError("Expected actual Account row lock wait");
    }
    private static String nativeReference(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", ""); }
    private static void evidenceConflict(ThrowingCallable action) { code("PROVIDER_EVIDENCE_MISMATCH", action); }
    private static void providerConflict(ThrowingCallable action) { code("PROVIDER_OPERATION_CONFLICT", action); }
    private static void storeConflict(ThrowingCallable action) { code("SUBSCRIPTION_CONFLICT", action); }
    private static void ruleConflict(ThrowingCallable action) { code("SUBSCRIPTION_STATE_CONFLICT", action); }
    private static void code(String expected, ThrowingCallable action) { assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class, e -> assertThat(e.code()).isEqualTo(expected)); }
    private static void sqlState(String expected, ThrowingCallable action) {
        assertThatThrownBy(action).satisfies(failure -> { Throwable cause = failure; while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause(); assertThat(cause).isInstanceOfSatisfying(SQLException.class, sql -> assertThat(sql.getSQLState()).isEqualTo(expected)); });
    }
}
