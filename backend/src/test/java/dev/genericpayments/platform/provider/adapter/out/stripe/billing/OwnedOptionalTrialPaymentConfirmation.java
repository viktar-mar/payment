package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentConfirmParams;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialAcceptance.PhaseFailure;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialCreate.Authority;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialCreate.Created;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialResumeGraph.Identity;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingTestClockAdapter.options;

/** The first and only confirmation of the immutable original invoice-created PaymentIntent. */
final class OwnedOptionalTrialPaymentConfirmation {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    record PaymentReceipt(String invoice, String intent, String charge) {}

    private record Candidate(Authority authority, Created original, long frozen, Identity identity,
                             String setup, String method, String fingerprint, String key) {}

    private final JdbcTemplate jdbc;
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final PaymentAccountVerificationPort verification;
    private final DataSource dataSource;
    private final TransactionTemplate transactions;

    private OwnedOptionalTrialPaymentConfirmation(JdbcTemplate jdbc, StripeCredentialResolver credentials,
            StripeClientFactory clients, PaymentAccountVerificationPort verification) {
        this.jdbc = jdbc;
        this.credentials = credentials;
        this.clients = clients;
        this.verification = verification;
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource());
        this.transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(5);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean OwnedOptionalTrialPaymentConfirmation ownedOptionalTrialPaymentConfirmation(JdbcTemplate jdbc,
                StripeCredentialResolver credentials, StripeClientFactory clients, PaymentAccountVerificationPort verification) {
            return new OwnedOptionalTrialPaymentConfirmation(jdbc, credentials, clients, verification);
        }
    }

    void confirm(PaymentAccount account, Authority a, Created original, long frozen, UUID setupOperation, UUID resumeOperation,
            Runnable requireCurrent) {
        requireIndependent();
        OwnedOptionalTrialResume.requireAccount(account, a);
        var candidate = candidate(a, setupOperation, resumeOperation);
        require(a.equals(candidate.authority()) && original.equals(candidate.original()) && frozen == candidate.frozen());

        Runnable current = () -> {
            requireIndependent();
            requireCurrent.run();
            require(candidate.equals(candidate(a, setupOperation, resumeOperation)));
        };
        Runnable unclaimed = () -> {
            current.run();
            require(Boolean.TRUE.equals(jdbc.queryForObject("""
                select not exists(select 1 from stripe_billing_owned_optional_trial_payment_confirmations where clock_intent=?)
                """, Boolean.class, a.clockIntent())));
        };

        StripeClient client;
        try {
            unclaimed.run();
            verification.verify(account);
            unclaimed.run();
            client = clients.create(credentials.resolve(account));
        } catch (RuntimeException failure) {
            throw new PhaseFailure("OPTIONAL_TRIAL_PAY_PRECHECK");
        }
        verifyOpen(client, candidate, setupOperation, unclaimed);
        unclaimed.run();

        var claim = UUID.randomUUID();
        try {
            require(committed("select claim_owned_optional_trial_payment_confirmation(?,?,?,?,?,?,?,?)",
                a.run(), a.clockIntent(), a.subscription(), a.createOperation(), setupOperation, resumeOperation,
                candidate.fingerprint(), claim));
        } catch (RuntimeException failure) {
            throw new PhaseFailure("OPTIONAL_TRIAL_PAY_CLAIM");
        }
        Runnable owned = () -> {
            current.run();
            require(Boolean.TRUE.equals(jdbc.queryForObject("select owned_optional_trial_payment_confirmation_dispatchable(?)",
                Boolean.class, claim)));
        };
        String checkpoint = "OPTIONAL_TRIAL_PAY_DISPATCH_PROOF";
        try {
            var graph = new OwnedOptionalTrialResumeGraph(client, a, original, frozen, owned);
            verifyOpenPayment(client, graph, candidate, owned);
            graph.verifyReadyClock();
            owned.run();
            checkpoint = "OPTIONAL_TRIAL_PAY_RESPONSE";
            var charge = postConfirm(client, a.customerReference(), candidate.identity().intent(), candidate.method(), candidate.key());
            checkpoint = "OPTIONAL_TRIAL_PAY_ACK";
            owned.run();
            require(committed("select finish_owned_optional_trial_payment_confirmation(?,?)", claim, charge));
        } catch (StripeException | RuntimeException failure) {
            try {
                committed("select finish_owned_optional_trial_payment_confirmation(?,null)", claim);
            } catch (RuntimeException ignored) {
                // The immutable first claim still consumes the dispatch budget.
            }
            if (failure instanceof PhaseFailure diagnosed) throw diagnosed;
            if (checkpoint.equals("OPTIONAL_TRIAL_PAY_RESPONSE") && failure instanceof StripeException) {
                checkpoint = failure instanceof InvalidRequestException
                    ? "OPTIONAL_TRIAL_PAY_STRIPE_INVALID_REQUEST" : "OPTIONAL_TRIAL_PAY_STRIPE";
            }
            throw new PhaseFailure(checkpoint);
        }
    }

    private static void verifyOpen(StripeClient client, Candidate candidate, UUID setupOperation, Runnable current) {
        var a = candidate.authority();
        var identity = candidate.identity();
        var graph = new OwnedOptionalTrialResumeGraph(client, a, candidate.original(), candidate.frozen(), current);
        try {
            graph.verifyReadyClock();
            graph.verifySubscriptionAndSetup(setupOperation, candidate.setup(), candidate.method(), identity.invoice(), false);
            verifyOpenPayment(client, graph, candidate, current);
            graph.verifyReadyClock();
        } catch (PhaseFailure diagnosed) {
            throw diagnosed;
        } catch (StripeException | RuntimeException failure) {
            throw new PhaseFailure("OPTIONAL_TRIAL_PAY_OPEN_GRAPH");
        }
    }

    private static void verifyOpenPayment(StripeClient client, OwnedOptionalTrialResumeGraph graph,
            Candidate candidate, Runnable current) throws StripeException {
        var identity = candidate.identity();
        graph.readInvoice(identity.invoice(), candidate.method(), false, identity);
        current.run();
        verifyOpenIntent(client, candidate.authority().customerReference(), identity.intent(), candidate.method());
    }

    static void verifyOpenIntent(StripeClient client, String customer, String intentId, String method) {
        try {
            var intent = client.v1().paymentIntents().retrieve(intentId, options(null));
            verifyIntent(intent, customer, intentId, method, false);
        } catch (StripeException | RuntimeException failure) {
            throw new PhaseFailure("OPTIONAL_TRIAL_PAY_OPEN_INTENT");
        }
    }

    private Candidate candidate(Authority a, UUID setup, UUID resume) {
        var rows = jdbc.query("""
            select c.* from stripe_billing_optional_trial_payment_confirmation_candidates c
            where c.run_id=? and c.clock_intent=? and c.subscription_id=? and c.create_operation=?
              and c.setup_operation=? and c.resume_operation=?
            """, (r,n) -> new Candidate(JdbcOwnedOptionalTrialCreateStore.authority(r),
                new Created(r.getString("subscription_reference"), r.getString("item_reference"), r.getString("invoice_reference"),
                    r.getLong("trial_start")), r.getLong("paused_time"),
                new Identity(r.getString("resume_invoice"), r.getString("allocation_reference"), r.getString("intent_reference")),
                r.getString("setup_reference"), r.getString("method_reference"), r.getString("evidence_fingerprint"),
                r.getString("confirm_key")), a.run(), a.clockIntent(), a.subscription(), a.createOperation(), setup, resume);
        require(rows.size() == 1);
        return rows.getFirst();
    }

    static PaymentReceipt acknowledged(JdbcTemplate jdbc, Authority a, UUID setup, UUID resume) {
        var rows = jdbc.query("""
            select c.*, f.charge_reference from stripe_billing_owned_optional_trial_payment_confirmations f
            join stripe_billing_optional_trial_payment_confirmation_candidates c on c.clock_intent=f.clock_intent
              and c.resume_operation=f.resume_operation and c.billing_record_id=f.billing_record_id
              and c.evidence_fingerprint=f.evidence_fingerprint
              and (c.resume_invoice,c.allocation_reference,c.intent_reference)=(f.invoice_reference,f.allocation_reference,f.intent_reference)
            where c.run_id=? and f.clock_intent=? and c.subscription_id=? and c.create_operation=? and c.setup_operation=?
              and f.resume_operation=? and f.status='ACKNOWLEDGED'
            """, (r,n) -> {
                require(a.equals(JdbcOwnedOptionalTrialCreateStore.authority(r)));
                return new PaymentReceipt(r.getString("resume_invoice"), r.getString("intent_reference"), r.getString("charge_reference"));
            }, a.run(), a.clockIntent(), a.subscription(), a.createOperation(), setup, resume);
        require(rows.size() == 1);
        return rows.getFirst();
    }

    static String postConfirm(StripeClient client, String customer, String intentId, String method, String key) throws StripeException {
        var builder = RequestOptions.builder().setIdempotencyKey(key)
            .setConnectTimeout(2000).setReadTimeout(8000).setMaxNetworkRetries(0);
        RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(builder, StripeBillingTestClockAdapter.API_VERSION);
        // Invoice-created intents reject error_on_requires_action. Require succeeded in the reply.
        var parameters = PaymentIntentConfirmParams.builder().setPaymentMethod(method).setOffSession(true).build();
        try {
            var response = client.v1().paymentIntents().confirm(intentId, parameters, builder.build());
            verifyIntent(response, customer, intentId, method, true);
            return response.getLatestCharge();
        } catch (RuntimeException malformedReply) {
            throw unavailable();
        }
    }

    private static void verifyIntent(PaymentIntent remote, String customer, String intentId, String method, boolean succeeded) {
        require(remote != null && remote.getLastResponse() != null);
        JsonNode raw;
        try {
            raw = JSON.readTree(remote.getLastResponse().body());
        } catch (IOException invalid) {
            throw unavailable();
        }
        require(raw != null && raw.isObject());
        for (String field : List.of("id", "object", "customer", "currency", "status", "capture_method")) {
            require(raw.path(field).isTextual());
        }
        for (String field : List.of("amount", "amount_received", "amount_capturable")) {
            require(raw.path(field).isIntegralNumber() && raw.path(field).canConvertToLong());
        }
        require(raw.path("livemode").isBoolean() && raw.path("payment_method_types").isArray());
        for (var type : raw.path("payment_method_types")) require(type.isTextual());
        for (String field : List.of("last_payment_error", "next_action")) require(!raw.hasNonNull(field));

        require(intentId.equals(remote.getId()) && "payment_intent".equals(remote.getObject())
            && Boolean.FALSE.equals(remote.getLivemode()) && customer.equals(remote.getCustomer())
            && "eur".equals(remote.getCurrency()) && Long.valueOf(2000).equals(remote.getAmount())
            && Long.valueOf(succeeded ? 2000 : 0).equals(remote.getAmountReceived()) && Long.valueOf(0).equals(remote.getAmountCapturable())
            && "automatic".equals(remote.getCaptureMethod()) && List.of("card").equals(remote.getPaymentMethodTypes()));
        if (succeeded) {
            require("succeeded".equals(remote.getStatus()) && raw.path("payment_method").isTextual()
                && method.equals(remote.getPaymentMethod()) && raw.path("latest_charge").isTextual()
                && remote.getLatestCharge() != null && remote.getLatestCharge().matches("ch_[A-Za-z0-9]{1,252}"));
        } else {
            require(("requires_payment_method".equals(remote.getStatus()) || "requires_confirmation".equals(remote.getStatus()))
                && !raw.hasNonNull("latest_charge")
                && (!raw.hasNonNull("payment_method") || raw.path("payment_method").isTextual() && method.equals(remote.getPaymentMethod())));
        }
    }

    private boolean committed(String sql, Object... parameters) {
        requireIndependent();
        return Boolean.TRUE.equals(transactions.execute(status -> jdbc.queryForObject(sql, Boolean.class, parameters)));
    }

    private void requireIndependent() {
        require(!TransactionSynchronizationManager.isActualTransactionActive()
            && !TransactionSynchronizationManager.hasResource(dataSource));
    }

    private static void require(boolean allowed) {
        if (!allowed) throw unavailable();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Owned optional trial payment confirmation unavailable");
    }
}
