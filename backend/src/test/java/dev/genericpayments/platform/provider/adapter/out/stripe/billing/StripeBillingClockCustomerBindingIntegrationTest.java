package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingClockStore.StopReason;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Lookup;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionObjectPort;
import dev.genericpayments.platform.support.StripeBillingClockCustomerFixture;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

/** Authored/compiled offline; execution requires the hosted PostgreSQL/Spring gate. */
class StripeBillingClockCustomerBindingIntegrationTest extends StripeBillingClockCustomerFixture {
    @Test void realProxiedCompositionPersistsExactLineageAndSameRunReplayIsLocal() {
        for (var bean : List.of(clocks, operations, bindings, customers, clockAdapter, provider, actions, inbox))
            assertThat(AopUtils.isAopProxy(bean)).as(bean.getClass().getName()).isTrue();
        var before = Instant.now().minusSeconds(1);
        var owner = admit();
        http.afterResponse = () -> {
            assertDispatchUtc(clockOperation(owner), before);
            http.afterResponse = () -> assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        };
        var ready = createReady(owner);
        http.afterResponse = () -> assertDispatchUtc(customerOperation(owner), before);
        var binding = createCustomer(owner);
        var customerAck = customerOperation(owner);
        assertThat(clockOperation(owner).status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(customerAck.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(ready.owner()).isEqualTo(owner);
        assertThat(ready.creationOperation()).isEqualTo(clockOperation(owner).id());
        assertThat(ready.frozenTime()).isEqualTo(FROZEN);
        assertThat(ready.providerCreated()).isEqualTo(PROVIDER_CREATED).isNotEqualTo(FROZEN);
        assertThat(ready.providerDeletesAfter()).isEqualTo(PROVIDER_DELETES);
        assertThat(owner.admittedAt()).isBetween(before, Instant.now());
        assertThat(ready.observedAt()).isBetween(before, Instant.now());
        assertThat(ready.observedAt().getEpochSecond()).isNotIn(FROZEN, PROVIDER_CREATED, PROVIDER_DELETES);
        assertThat(customerAck.fingerprint()).isEqualTo(ready.customerFingerprint())
            .isEqualTo(StripeBillingClockIdentity.customerFingerprint(digest, owner, ready.creationOperation(), ready.reference()));
        assertThat(binding.creationOperationId()).isEqualTo(customerAck.id());
        assertThat(jdbc.queryForObject("""
            select count(*) from stripe_billing_clock_intents i
            join stripe_billing_clock_ready r on r.intent_id=i.id and r.payment_account_id=i.payment_account_id
              and r.site_id=i.site_id and r.customer_id=i.customer_id and r.frozen_time=i.initial_frozen_time
            join provider_operations clock_op on clock_op.id=r.creation_operation_id and clock_op.resource_id=i.id
              and clock_op.payment_account_id=i.payment_account_id and clock_op.kind='TEST_CLOCK_CREATE'
              and clock_op.status='ACKNOWLEDGED' and clock_op.provider_reference=r.reference
              and clock_op.operation_key=i.create_key and clock_op.fingerprint=i.create_fingerprint
            join provider_operations customer_op on customer_op.payment_account_id=i.payment_account_id
              and customer_op.resource_id=i.customer_id and customer_op.kind='CUSTOMER_CREATE'
              and customer_op.status='ACKNOWLEDGED' and customer_op.fingerprint=r.customer_fingerprint
            join stripe_billing_customers b on b.creation_operation_id=customer_op.id
              and b.payment_account_id=i.payment_account_id and b.site_id=i.site_id and b.customer_id=i.customer_id
              and b.provider_reference=customer_op.provider_reference
            where i.id=? and i.run_id=? and b.id=? and customer_op.operation_key=?
            """, Integer.class, owner.intent(), owner.run(), binding.id(), owner.customerKey())).isEqualTo(1);
        assertThat(http.calls).hasSize(3);
        assertThat(http.calls.stream().map(r -> r.method().name() + " " + r.url().getPath()).toList())
            .containsExactly("POST /v1/test_helpers/test_clocks", "GET /v1/test_helpers/test_clocks/clock_owned", "POST /v1/customers");
        assertThat(http.calls.getFirst().params()).containsExactlyInAnyOrderEntriesOf(Map.of("frozen_time", FROZEN, "name", owner.name()));
        assertThat(http.calls.getFirst().headers().map().get("Idempotency-Key")).containsExactly(owner.createKey());
        assertThat(http.calls.getLast().params()).containsEntry("test_clock", ready.reference());
        assertThat(http.calls.getLast().headers().map().get("Idempotency-Key")).containsExactly(owner.customerKey());
        for (var request : http.calls) {
            assertThat(request.options().getMaxNetworkRetries()).isZero();
            assertThat(request.headers().map().get("Stripe-Version")).containsExactly(StripeBillingTestClockAdapter.API_VERSION);
        }
        assertThat(clocks.admitFresh(billing, site, customer, owner.run(), owner.apiVersion(), FROZEN, Instant.now())).isEqualTo(owner);
        assertThat(clockAdapter.createFresh(billing, owner)).isEqualTo(ready);
        assertThat(customers.ensureOwnedCustomer(billing, owner)).isEqualTo(binding);
        assertThat(http.calls).hasSize(3);
        assertThat(verification.accounts).containsExactly(billing, billing);
        assertThat(count("provider_operations")).isEqualTo(2);
        assertThat(count("stripe_billing_customers")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update stripe_billing_customers set provider_reference='cus_changed' where id=?", binding.id()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(bindings.findCustomer(billing.id(), site, customer)).contains(binding);
    }

    @Test void intentAndOriginalAckToReadyGapNeverFallBackOrResumeRead() {
        var owner = admit();
        denied("PROVIDER_OPERATION_CONFLICT", () -> customers.ensureOwnedCustomer(billing, owner));
        rejectOrdinary(owner);
        assertThat(http.calls).isEmpty(); assertThat(verification.accounts).isEmpty();
        http.afterResponse = () -> http.afterResponse = () -> {
            assertThat(clockOperation(owner).status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
            assertThat(clocks.ready(owner.intent())).isEmpty();
            denied("PROVIDER_OPERATION_CONFLICT", () -> clockAdapter.createFresh(billing, owner));
            denied("PROVIDER_OPERATION_CONFLICT", () -> customers.ensureOwnedCustomer(billing, owner));
            rejectOrdinary(owner);
            assertThat(stopped(owner)).isNull();
            assertThat(http.calls).hasSize(2);
        };
        var ready = createReady(owner);
        assertThat(clocks.ready(owner.intent())).contains(ready);
        rejectOrdinary(owner);
        assertThat(http.calls).hasSize(2);
        assertThat(count("provider_operations")).isEqualTo(1);
        assertThat(count("stripe_billing_customers")).isZero();
    }

    @Test void restartFromPersistedClockAckCannotRetrieveOrCreateCustomer() {
        var owner = admit();
        ack(operations.reserve(billing, ProviderOperation.Kind.TEST_CLOCK_CREATE, owner.intent(), owner.createKey(), owner.createFingerprint()), "clock_owned");
        denied("PROVIDER_OPERATION_CONFLICT", () -> clockAdapter.createFresh(billing, owner));
        denied("PROVIDER_OPERATION_CONFLICT", () -> customers.ensureOwnedCustomer(billing, owner));
        rejectOrdinary(owner);
        assertThat(http.calls).isEmpty(); assertThat(verification.accounts).isEmpty();
        assertThat(clocks.ready(owner.intent())).isEmpty();
    }

    @Test void actualBindingRollbackLeavesAckAndOnlyBindingFailedRepairNeedsNoSdk() {
        var owner = admit(); createReady(owner);
        jdbc.execute("create function clock_composition_reject_binding() returns trigger language plpgsql as $$ begin raise exception 'injected local binding failure' using errcode='23514'; end; $$");
        try {
            jdbc.execute("create trigger clock_composition_reject_binding before insert on stripe_billing_customers for each row execute function clock_composition_reject_binding()");
            assertThatThrownBy(() -> createCustomer(owner)).isInstanceOf(PaymentApplicationException.class);
        } finally {
            try { jdbc.execute("drop trigger if exists clock_composition_reject_binding on stripe_billing_customers"); }
            finally { jdbc.execute("drop function if exists clock_composition_reject_binding()"); }
        }
        var ack = customerOperation(owner);
        assertThat(ack.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(count("stripe_billing_customers")).isZero();
        assertThat(stopped(owner)).isEqualTo("BINDING_FAILED");
        var binding = customers.ensureOwnedCustomer(billing, owner);
        assertThat(binding.creationOperationId()).isEqualTo(ack.id());
        assertThat(customers.ensureOwnedCustomer(billing, owner)).isEqualTo(binding);
        assertThat(http.calls).hasSize(3); assertThat(verification.accounts).containsExactly(billing, billing);
        assertThat(count("provider_operations")).isEqualTo(2);
        denied("PROVIDER_OPERATION_CONFLICT", () -> clocks.requireActive(owner));
        denied("PROVIDER_OPERATION_CONFLICT", () -> operations.bindRecovered(ack, "cus_owned", ack.fingerprint()));
        rejectOrdinary(owner);
        assertThat(jdbc.queryForObject("select stopped_at from stripe_billing_clock_intents where id=?", java.sql.Timestamp.class, owner.intent()).toInstant())
            .isBetween(owner.admittedAt(), Instant.now());
    }

    @ParameterizedTest @EnumSource(value = StopReason.class, names = "BINDING_FAILED", mode = EnumSource.Mode.EXCLUDE)
    void everyOtherStopReasonRefusesKnownAckBinding(StopReason reason) {
        var owner = admit(); var ready = createReady(owner);
        var ack = ack(operations.reserveOwnedBillingCustomer(billing, ready), "cus_owned");
        clocks.stop(owner, reason, Instant.now());
        denied("PROVIDER_OPERATION_CONFLICT", () -> customers.ensureOwnedCustomer(billing, owner));
        assertThat(customerOperation(owner)).isEqualTo(ack);
        assertThat(stopped(owner)).isEqualTo(reason.name());
        assertThat(count("stripe_billing_customers")).isZero();
        assertThat(http.calls).hasSize(2);
    }

    @ParameterizedTest @ValueSource(strings = {"malformed", "disconnect"})
    void invalidClockPostPersistsUnknownAndTerminalStopWithoutAnotherAttempt(String failure) {
        var owner = admit();
        if (failure.equals("malformed")) http.reply("{"); else http.disconnect();
        denied("PROVIDER_EVIDENCE_MISMATCH", () -> clockAdapter.createFresh(billing, owner));
        assertThat(clockOperation(owner).status()).isEqualTo(ProviderOperation.Status.UNKNOWN);
        assertThat(stopped(owner)).isEqualTo("CLOCK_CREATE_FAILED");
        denied("PROVIDER_OPERATION_CONFLICT", () -> clockAdapter.createFresh(billing, owner));
        rejectOrdinary(owner);
        assertThat(clocks.ready(owner.intent())).isEmpty();
        assertThat(count("stripe_billing_customers")).isZero(); assertThat(http.calls).hasSize(1);
    }

    @Test void wrongExactReadyResponsePreservesOriginalAckButNeverPollsOrCreatesCustomer() {
        var owner = admit(); http.reply(clockJson(owner)); http.reply(clockJson(owner).replace("\"ready\"", "\"advancing\""));
        denied("PROVIDER_EVIDENCE_MISMATCH", () -> clockAdapter.createFresh(billing, owner));
        assertThat(clockOperation(owner).status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(stopped(owner)).isEqualTo("CLOCK_READY_FAILED");
        denied("PROVIDER_OPERATION_CONFLICT", () -> clockAdapter.createFresh(billing, owner));
        denied("PROVIDER_OPERATION_CONFLICT", () -> customers.ensureOwnedCustomer(billing, owner));
        assertThat(clocks.ready(owner.intent())).isEmpty(); assertThat(http.calls).hasSize(2);
    }

    @ParameterizedTest @ValueSource(strings = {"malformed", "disconnect", "wrong_clock"})
    void invalidOwnedCustomerPersistsUnknownWithoutReplayOrOrdinaryRecovery(String failure) {
        var owner = admit(); createReady(owner);
        switch (failure) { case "malformed" -> http.reply("{"); case "disconnect" -> http.disconnect(); default -> http.reply(customerJson("clock_foreign")); }
        denied("PROVIDER_RESULT_UNKNOWN", () -> customers.ensureOwnedCustomer(billing, owner));
        var unknown = customerOperation(owner);
        assertThat(unknown.status()).isEqualTo(ProviderOperation.Status.UNKNOWN);
        assertThat(stopped(owner)).isEqualTo("CUSTOMER_CREATE_FAILED");
        denied("PROVIDER_OPERATION_CONFLICT", () -> customers.ensureOwnedCustomer(billing, owner));
        denied("PROVIDER_OPERATION_CONFLICT", () -> operations.bindRecovered(unknown, "cus_owned", unknown.fingerprint()));
        rejectOrdinary(owner);
        assertThat(count("stripe_billing_customers")).isZero(); assertThat(http.calls).hasSize(3);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void authorityDriftDuringClockOrCustomerPostCannotAcknowledge(boolean customerPost) {
        var owner = admit(); if (customerPost) createReady(owner);
        http.afterResponse = () -> jdbc.update("update payment_accounts set external_account_id='acct_changed' where id=?", billing.id().value());
        if (customerPost) assertThatThrownBy(() -> createCustomer(owner)).isInstanceOf(PaymentApplicationException.class);
        else assertThatThrownBy(() -> createReady(owner)).isInstanceOf(PaymentApplicationException.class);
        var operation = customerPost ? customerOperation(owner) : clockOperation(owner);
        assertThat(operation.status()).isEqualTo(ProviderOperation.Status.DISPATCHING);
        assertThat(operation.providerReference()).isNull();
        assertThat(count("stripe_billing_customers")).isZero();
        if (!customerPost) assertThat(clocks.ready(owner.intent())).isEmpty();
        assertThat(http.calls).hasSize(customerPost ? 3 : 1);
        assertThatThrownBy(() -> customers.ensureOwnedCustomer(billing, owner)).isInstanceOf(PaymentApplicationException.class);
        if (customerPost) assertThat(clockAdapter.createFresh(billing, owner)).isEqualTo(clocks.ready(owner.intent()).orElseThrow());
        else assertThatThrownBy(() -> clockAdapter.createFresh(billing, owner)).isInstanceOf(PaymentApplicationException.class);
        assertThat(http.calls).hasSize(customerPost ? 3 : 1);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void expiredOriginalLeaseRefusesLateSdkAckAndConsumesAttempt(boolean customerPost) {
        var owner = admit(); if (customerPost) createReady(owner);
        http.afterResponse = () -> {
            var op = customerPost ? customerOperation(owner) : clockOperation(owner);
            // Simulate elapsed UTC without sleeping or altering any application Clock/frozen time.
            jdbc.update("update provider_operations set first_dispatched_at=now()-interval '120 seconds',retry_before=now()-interval '120 seconds'+interval '23 hours',lease_expires_at=now()-interval '60 seconds' where id=?", op.id());
        };
        if (customerPost) denied("PROVIDER_OPERATION_CONFLICT", () -> createCustomer(owner));
        else denied("PROVIDER_OPERATION_CONFLICT", () -> createReady(owner));
        var op = customerPost ? customerOperation(owner) : clockOperation(owner);
        assertThat(op.status()).isEqualTo(ProviderOperation.Status.DISPATCHING); assertThat(op.providerReference()).isNull();
        assertThat(stopped(owner)).isEqualTo(customerPost ? "CUSTOMER_CREATE_FAILED" : "CLOCK_CREATE_FAILED");
        denied("PROVIDER_OPERATION_CONFLICT", () -> operations.claim(op.id(), "later", Instant.now(), Duration.ofSeconds(60)));
        denied("PROVIDER_OPERATION_CONFLICT", () -> operations.acknowledge(op.id(), op.leaseOwner(), op.version(), customerPost ? "cus_owned" : "clock_owned"));
        assertThat(count("stripe_billing_customers")).isZero(); assertThat(http.calls).hasSize(customerPost ? 3 : 1);
    }

    @ParameterizedTest @ValueSource(strings = {"INTENT", "READY", "BOUND", "STOPPED"})
    void productionInjectedCommonPrepareDirectActionAndMappedInboxCannotDispatch(String state) {
        var owner = admit();
        if (!state.equals("INTENT")) createReady(owner);
        if (state.equals("BOUND")) createCustomer(owner);
        if (state.equals("STOPPED")) clocks.stop(owner, StopReason.AUTHORITY_REVOKED, Instant.now());
        var command = lifecycle(true); var id = command.operation().subscriptionId();
        var s = subscriptions.findOwned(billing.id(), id).orElseThrow();
        transaction.required(() -> { objects.bind(billing.provider(), s, SubscriptionObjectPort.Kind.SUBSCRIPTION, "sub_owned", null, Instant.now()); return null; });
        int requests = http.calls.size(), receipts = count("provider_operations"), checks = verification.accounts.size();
        denied("PROVIDER_CAPABILITY_UNAVAILABLE", () -> ownership.owned(billing, id));
        denied("PROVIDER_CAPABILITY_UNAVAILABLE", () -> provider.prepareCreate(command));
        var ordinaryPreparation = new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Command(
            billing, command.operation(), command.current(), null, null, null, null, true, false);
        denied("PROVIDER_CAPABILITY_UNAVAILABLE", () -> provider.prepareCreate(ordinaryPreparation));
        denied("PROVIDER_CAPABILITY_UNAVAILABLE", () -> provider.create(command));
        denied("PROVIDER_CAPABILITY_UNAVAILABLE", () -> provider.retrieve(new Lookup(billing, id, "sub_owned", null)));
        denied("PROVIDER_CAPABILITY_UNAVAILABLE", () -> actions.confirmAction(command, "bga1_" + "A".repeat(43)));
        denied("PROVIDER_CAPABILITY_UNAVAILABLE", () -> actions.confirmPreparedSetup(command, "bga1_" + "A".repeat(43)));
        denied("PROVIDER_CAPABILITY_UNAVAILABLE", () -> inbox.resolve(envelope("SUBSCRIPTION_CHANGED", "sub_owned")));
        for (var kind : List.of(ProviderOperation.Kind.SUBSCRIPTION_CREATE, ProviderOperation.Kind.SETUP_INTENT_CREATE))
            denied("PROVIDER_OPERATION_CONFLICT", () -> operations.reserve(billing, kind, command.operation().id(), command.operation().providerOperationKey() + (kind == ProviderOperation.Kind.SETUP_INTENT_CREATE ? ":SETUP" : ""), "facts"));
        assertThat(http.calls).hasSize(requests); assertThat(verification.accounts).hasSize(checks);
        assertThat(count("provider_operations")).isEqualTo(receipts);
        assertThat(count("stripe_subscription_setup_proofs")).isZero();
        assertThat(count("subscription_billing_records")).isZero();
        assertThat(count("payments")).isZero();
    }

    @Test void actualInboxExactSetupReadCannotAdoptOrRepairClockOwner() {
        var owner = admit(); createReady(owner); createCustomer(owner); var command = lifecycle(true);
        http.reply("{\"id\":\"seti_owned\",\"object\":\"setup_intent\",\"metadata\":{\"subscription_id\":\"" + command.operation().subscriptionId() + "\",\"operation_id\":\"" + command.operation().id() + "\"}}");
        denied("PROVIDER_CAPABILITY_UNAVAILABLE", () -> inbox.resolve(envelope("SUBSCRIPTION_SETUP_CHANGED", "seti_owned")));
        assertThat(http.calls).hasSize(4);
        assertThat(http.calls.getLast().method().name()).isEqualTo("GET");
        assertThat(http.calls.getLast().url().getPath()).isEqualTo("/v1/setup_intents/seti_owned");
        assertThat(count("provider_operations")).isEqualTo(2);
        assertThat(count("stripe_subscription_setup_proofs")).isZero();
    }

    @Test void ordinaryCustomerStillUsesHistoricalKeyFingerprintAndLocalAckReplay() {
        http.reply(customerJson(null)); var binding = customers.ensureCustomer(billing, site, customer);
        var op = operations.findById(binding.creationOperationId()).orElseThrow();
        assertThat(op.operationKey()).isEqualTo("billing_customer_" + billing.id().value() + "_" + site.value() + "_" + customer.value());
        assertThat(op.fingerprint()).isEqualTo(StripeRequestFingerprint.of(digest, billing, "CUSTOMER_CREATE", site.value().toString(), customer.value().toString()));
        assertThat(http.calls.getFirst().params()).containsOnlyKeys("metadata");
        assertThat(customers.ensureCustomer(billing, site, customer)).isEqualTo(binding);
        assertThat(http.calls).hasSize(1); assertThat(count("stripe_billing_clock_intents")).isZero();
        var command = lifecycle(false);
        assertThat(ownership.owned(billing, command.operation().subscriptionId()).customerId()).isEqualTo(customer);
        assertThat(provider.prepareCreate(command)).isInstanceOf(dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.ReadyForCreate.class);
    }

    void rejectOrdinary(ClockOwner owner) {
        int checks = verification.accounts.size(), calls = http.calls.size();
        denied("PROVIDER_OPERATION_CONFLICT", () -> customers.ensureCustomer(billing, site, customer));
        denied("PROVIDER_OPERATION_CONFLICT", () -> customers.recoverCustomer(billing, site, customer, UUID.randomUUID(), "evt_owned"));
        denied("PROVIDER_OPERATION_CONFLICT", () -> operations.reserve(billing, ProviderOperation.Kind.CUSTOMER_CREATE, customer.value(), owner.customerKey(), "ordinary"));
        assertThat(http.calls).hasSize(calls); assertThat(verification.accounts).hasSize(checks);
    }
    void assertDispatchUtc(ProviderOperation op, Instant before) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(op.status()).isEqualTo(ProviderOperation.Status.DISPATCHING);
        assertThat(op.firstDispatchedAt()).isBetween(before, Instant.now());
        assertThat(op.leaseExpiresAt()).isEqualTo(op.firstDispatchedAt().plusSeconds(60));
        assertThat(op.retryBefore()).isEqualTo(op.firstDispatchedAt().plus(Duration.ofHours(23)));
        assertThat(op.firstDispatchedAt().getEpochSecond()).isNotIn(FROZEN, PROVIDER_CREATED, PROVIDER_DELETES);
    }
    SubscriptionProviderEnvelope envelope(String type, String reference) {
        return new SubscriptionProviderEnvelope(billing.provider(), billing.id(), "evt_owned", reference, type, Instant.now(), Instant.now());
    }
    static void denied(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
