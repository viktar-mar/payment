package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeRequestFingerprint;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.service.ProviderOperationGuard;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ResumeSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionOperationStore;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionRecoveryTransaction;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialCreate.*;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialResumeGraph.require;

/** Explicit isolated RESUME. Dispatch and read authority are limited to the admitted original graph. */
final class OwnedOptionalTrialResume {
    enum Stage {
        OBSERVE_CLOCK, OBSERVE_SUBSCRIPTION, OBSERVE_CASH, CLAIM, DISPATCH_CLOCK, DISPATCH_RESPONSE,
        STRIPE_INVALID_REQUEST, STRIPE_BILLING_CYCLE_ANCHOR, STRIPE_PAYMENT_BEHAVIOR, STRIPE_PRORATION_BEHAVIOR,
        READ_INVOICE, FINISH, RESULT;

        String code() { return "OPTIONAL_TRIAL_RESUME_" + name(); }
    }

    record Attempt(SubscriptionOperationResult result, Stage failure) {}

    static final class Failure extends IllegalStateException {
        Failure(Stage stage) { super(stage.code()); }
    }

    private final JdbcTemplate jdbc;
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final PaymentAccountVerificationPort verification;
    private final SubscriptionRecoveryTransaction recovery;
    private final SubscriptionOperationStore operations;
    private final ProviderOperationStore steps;
    private final SubscriptionBillingProofStore billingProofs;
    private final DigestPort digest;
    private final Clock utc;

    OwnedOptionalTrialResume(JdbcTemplate jdbc, StripeCredentialResolver credentials, StripeClientFactory clients,
            PaymentAccountVerificationPort verification, SubscriptionRecoveryTransaction recovery,
            SubscriptionOperationStore operations, ProviderOperationStore steps, SubscriptionBillingProofStore billingProofs,
            DigestPort digest, Clock utc) {
        this.jdbc = jdbc;
        this.credentials = credentials;
        this.clients = clients;
        this.verification = verification;
        this.recovery = recovery;
        this.operations = operations;
        this.steps = steps;
        this.billingProofs = billingProofs;
        this.digest = digest;
        this.utc = utc;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean OwnedOptionalTrialResume ownedOptionalTrialResume(JdbcTemplate jdbc, StripeCredentialResolver credentials,
                StripeClientFactory clients, PaymentAccountVerificationPort verification, SubscriptionRecoveryTransaction recovery,
                SubscriptionOperationStore operations, ProviderOperationStore steps, SubscriptionBillingProofStore billingProofs,
                DigestPort digest, @Qualifier("providerReferenceClock") Clock utc) {
            return new OwnedOptionalTrialResume(jdbc, credentials, clients, verification, recovery, operations, steps, billingProofs, digest, utc);
        }
    }

    Attempt resume(PaymentAccount account, Authority authority, Created original, long frozen,
            UUID setupOperation, ResumeSubscriptionUseCase.Command command, Runnable requireCurrent) {
        recovery.requireOutsideTransaction();
        requireAccount(account, authority);
        requireAdmission(authority, original, frozen);
        var setup = setup(account, authority, original, setupOperation);
        Runnable current = () -> {
            requireAdmission(authority, original, frozen);
            requireCurrent.run();
            require(setup.equals(setup(account, authority, original, setupOperation)));
        };
        var time = new SubscriptionBillingTimePort.Sample(Instant.ofEpochSecond(frozen), SubscriptionBillingTimePort.Mode.ISOLATED);
        var reserved = recovery.reserveObservedResume(command, time, lookup -> {
            require(account.equals(lookup.account()) && authority.subscription().equals(lookup.subscriptionId())
                && original.subscription().equals(lookup.reference()));
            require(Boolean.TRUE.equals(jdbc.queryForObject("""
                select not exists(select 1 from subscription_operations where subscription_id=? and kind='RESUME')
                   and not exists(select 1 from stripe_billing_owned_optional_trial_resumes where clock_intent=?)
                """, Boolean.class, authority.subscription(), authority.clockIntent())));
            var graph = new OwnedOptionalTrialResumeGraph(client(account, current), authority, original, frozen, current);
            Stage observation = Stage.OBSERVE_CLOCK;
            try {
                graph.verifyReadyClock();
                observation = Stage.OBSERVE_SUBSCRIPTION;
                graph.verifySubscriptionAndSetup(setupOperation, setup.reference(), setup.method(), original.invoice(), false);
                observation = Stage.OBSERVE_CASH;
                graph.verifyNoPayment();
                observation = Stage.OBSERVE_CLOCK;
                graph.verifyReadyClock();
                return new Snapshot(original.subscription(), Subscription.Status.PAUSED, authority.published().terms(),
                    trialPeriod(authority), trialPeriod(authority), null, List.of(), null, true, 0, utc.instant());
            } catch (StripeException | RuntimeException failure) {
                throw new Failure(observation);
            }
        });
        var operation = reserved.operation();
        if (!reserved.owner()) return new Attempt(recovery.readResult(operation.id()), null);
        require(account.equals(reserved.account()) && authority.subscription().equals(reserved.subscription().id()));
        var claim = UUID.randomUUID();
        var fingerprint = fingerprint(account, authority, original, operation, setup, OwnedOptionalTrialResumeGraph.resumedPeriod(frozen));
        Reply reply = new Reply(Outcome.UNKNOWN, null, null);
        UUID stepId = null;
        Stage stage = Stage.CLAIM;
        try {
            current.run();
            stepId = jdbc.queryForObject("select claim_owned_optional_trial_resume(?,?,?,?,?,?,?,?,?)", UUID.class,
                authority.run(), authority.clockIntent(), authority.subscription(), authority.createOperation(),
                setupOperation, operation.id(), frozen, fingerprint, claim);
            if (stepId != null) {
                var step = steps.findById(stepId).orElseThrow(OwnedOptionalTrialResume::unavailable);
                Runnable dispatchCurrent = () -> {
                    current.run();
                    require(Boolean.TRUE.equals(jdbc.queryForObject("select owned_optional_trial_resume_dispatchable(?)",
                        Boolean.class, claim)) && steps.findById(step.id()).filter(step::equals).isPresent());
                    new ProviderOperationGuard().requireDispatch(step, account, fingerprint, utc.instant());
                };
                var dispatch = new OwnedOptionalTrialResumeGraph(client(account, dispatchCurrent), authority, original, frozen, dispatchCurrent);
                stage = Stage.DISPATCH_CLOCK;
                dispatch.verifyReadyClock();
                stage = Stage.DISPATCH_RESPONSE;
                var invoice = dispatch.dispatch(setup.method(), step.operationKey());
                stage = Stage.READ_INVOICE;
                var open = dispatch.readInvoice(invoice, setup.method(), false, null);
                stage = Stage.DISPATCH_CLOCK;
                dispatch.verifyReadyClock();
                var identity = open.identity();
                stage = Stage.FINISH;
                require(Boolean.TRUE.equals(jdbc.queryForObject("select finish_owned_optional_trial_resume(?,?,?,?,?)",
                    Boolean.class, claim, identity.invoice(), identity.allocation(), identity.intent(),
                    open.invoice().period().end().getEpochSecond())));
                reply = new Reply(Outcome.ACCEPTED, original.subscription(), null);
                stage = null;
            }
        } catch (StripeException | RuntimeException failure) {
            if (stepId != null) finishUnknown(claim);
            if (stage == Stage.DISPATCH_RESPONSE && failure instanceof InvalidRequestException invalid) {
                stage = rejectedResumeParameter(invalid);
            }
        }
        try {
            return new Attempt(recovery.recordReply(operation.id(), reply), stage);
        } catch (RuntimeException failure) {
            throw new Failure(Stage.RESULT);
        }
    }

    static Stage rejectedResumeParameter(InvalidRequestException failure) {
        String parameter = failure.getParam();
        if (parameter == null) return Stage.STRIPE_INVALID_REQUEST;
        return switch (parameter) {
            case "billing_cycle_anchor", "billing_cycle_anchor[type]" -> Stage.STRIPE_BILLING_CYCLE_ANCHOR;
            case "payment_behavior" -> Stage.STRIPE_PAYMENT_BEHAVIOR;
            case "proration_behavior" -> Stage.STRIPE_PRORATION_BEHAVIOR;
            default -> Stage.STRIPE_INVALID_REQUEST;
        };
    }

    Snapshot observe(PaymentAccount account, Authority authority, Created original, long frozen, UUID setupOperation,
            UUID resumeOperation, TrialEvidence trial, boolean paid, Runnable requireCurrent) {
        require(!paid);
        return observe(account, authority, original, frozen, setupOperation, resumeOperation, trial, null, requireCurrent);
    }

    Snapshot observe(PaymentAccount account, Authority authority, Created original, long frozen, UUID setupOperation,
            UUID resumeOperation, TrialEvidence trial, OwnedOptionalTrialPaymentConfirmation.PaymentReceipt acknowledgement,
            Runnable requireCurrent) {
        recovery.requireOutsideTransaction();
        requireAccount(account, authority);
        requireAdmission(authority, original, frozen);
        var setup = setup(account, authority, original, setupOperation);
        var receipt = receipt(authority, original, setupOperation, resumeOperation, frozen);
        boolean paid = acknowledgement != null;
        require(!paid || acknowledgement.charge() != null && receipt.identity().invoice().equals(acknowledgement.invoice())
            && receipt.identity().intent().equals(acknowledgement.intent()));
        var trialPeriod = trialPeriod(authority);
        require(trial != null && authority.createOperation().equals(trial.operation()) && original.item().equals(trial.item())
            && original.invoice().equals(trial.invoice()) && trialPeriod.equals(trial.period()) && trial.originalMethod() == null
            && authority.published().terms().equals(trial.price()));
        Runnable current = () -> {
            requireAdmission(authority, original, frozen);
            requireCurrent.run();
            require(setup.equals(setup(account, authority, original, setupOperation))
                && receipt.equals(receipt(authority, original, setupOperation, resumeOperation, frozen)));
        };
        var graph = new OwnedOptionalTrialResumeGraph(client(account, current), authority, original, frozen, current);
        try {
            graph.verifyReadyClock();
            graph.verifySubscriptionAndSetup(setupOperation, setup.reference(), setup.method(), receipt.identity().invoice(), paid);
            var renewal = graph.readInvoice(receipt.identity().invoice(), setup.method(), paid, receipt.identity(),
                paid ? acknowledgement.charge() : null).invoice();
            graph.verifyReadyClock();
            require(receipt.period().equals(renewal.period()));
            var zero = new Invoice(original.invoice(), BillingRecord.Kind.INITIAL, trial.price(), null,
                trialPeriod, 0, "EUR", SettlementKind.ZERO, List.of());
            return new Snapshot(original.subscription(), paid ? Subscription.Status.ACTIVE : Subscription.Status.PAUSED,
                trial.price(), paid ? renewal.period() : trialPeriod, trialPeriod, null, List.of(zero, renewal), receipt.operationKey(),
                true, 0, utc.instant(), new OperationObservation(receipt.operationKey(), paid ? OperationState.APPLIED : OperationState.PENDING),
                List.of(), trial);
        } catch (StripeException failure) {
            throw unavailable();
        }
    }

    private record Receipt(String operationKey, OwnedOptionalTrialResumeGraph.Identity identity, Subscription.Period period) {}
    private record Admission(Authority authority, Created original) {}

    private void requireAdmission(Authority authority, Created original, long frozen) {
        var rows = jdbc.query("""
            with admitted as materialized (
                select * from stripe_billing_optional_trial_acknowledgements
                where run_id=? and clock_intent=? and subscription_id=? and create_operation=?
            )
            select a.* from admitted a
            join stripe_billing_optional_trial_pauses p on p.clock_intent=a.clock_intent
              and p.subscription_id=a.subscription_id and p.create_operation=a.create_operation and p.frozen_time=a.trial_end
            where p.frozen_time=?
            """, (r,n) -> new Admission(JdbcOwnedOptionalTrialCreateStore.authority(r),
                new Created(r.getString("subscription_reference"), r.getString("item_reference"), r.getString("invoice_reference"),
                    r.getLong("trial_start"))), authority.run(), authority.clockIntent(), authority.subscription(), authority.createOperation(), frozen);
        require(rows.size() == 1 && rows.getFirst().equals(new Admission(authority, original)));
    }

    private Receipt receipt(Authority a, Created original, UUID setupOperation, UUID resumeOperation, long frozen) {
        var rows = jdbc.query("""
            select o.provider_operation_key,r.invoice_reference,r.allocation_reference,r.intent_reference,r.frozen_time,r.period_end
            from stripe_billing_owned_optional_trial_resumes r
            join lateral (
                select admitted.* from stripe_billing_optional_trial_acknowledgements admitted
                where admitted.clock_intent=r.clock_intent and admitted.subscription_id=r.subscription_id
                  and admitted.trial_end=r.frozen_time and admitted.invoice_reference<>r.invoice_reference offset 0
            ) a on true
            join subscription_operations o on o.id=r.resume_operation and o.subscription_id=r.subscription_id and o.kind='RESUME'
              and o.status in ('PROCESSING','SUCCEEDED') and o.provider_reference=a.subscription_reference
            join provider_operations p on p.id=r.step_id and p.status='ACKNOWLEDGED' and p.version=2 and p.kind='SUBSCRIPTION_UPDATE'
              and p.operation_key=o.provider_operation_key and p.resource_id=o.id and p.payment_account_id=a.account_id
              and p.external_account_id=a.external_account and p.credentials_ref=a.credentials_ref and p.fingerprint=r.fingerprint
              and p.lease_owner is null and p.provider_reference=a.subscription_reference and p.first_dispatched_at=r.claimed_at
              and p.recovery_attempts=0 and not p.manual_review_required and p.safe_error_code is null
            join stripe_subscription_resume_proofs proof on proof.operation_id=o.id and proof.step_id=p.id
              and proof.subscription_id=r.subscription_id and proof.payment_account_id=a.account_id
              and proof.subscription_reference=a.subscription_reference and proof.invoice_reference=r.invoice_reference
              and proof.period_start=r.frozen_time and proof.period_end=r.period_end
            where a.run_id=? and r.clock_intent=? and r.subscription_id=? and a.create_operation=? and a.subscription_reference=?
              and r.setup_operation=? and r.resume_operation=? and r.frozen_time=? and r.status='ACKNOWLEDGED'
            """, (r,n) -> new Receipt(r.getString("provider_operation_key"),
                new OwnedOptionalTrialResumeGraph.Identity(r.getString("invoice_reference"), r.getString("allocation_reference"),
                    r.getString("intent_reference")), new Subscription.Period(Instant.ofEpochSecond(r.getLong("frozen_time")),
                    Instant.ofEpochSecond(r.getLong("period_end")))), a.run(), a.clockIntent(), a.subscription(), a.createOperation(),
            original.subscription(), setupOperation, resumeOperation, frozen);
        require(rows.size() == 1);
        return rows.getFirst();
    }

    private SubscriptionBillingProofStore.Setup setup(PaymentAccount account, Authority a, Created original, UUID id) {
        var o = operations.find(new SiteId(a.site()), a.subscription(), id).orElseThrow(OwnedOptionalTrialResume::unavailable);
        require(o.kind() == SubscriptionOperation.Kind.SETUP && o.status() == SubscriptionOperation.Status.SUCCEEDED
            && a.customer().equals(o.customerId().value()) && original.subscription().equals(o.providerReference())
            && o.authorityScope().equals("customer:" + a.customer()));
        var proof = billingProofs.setup(account.id(), id).orElseThrow(OwnedOptionalTrialResume::unavailable);
        var setup = steps.find(account.id(), o.providerOperationKey() + ":SETUP").orElseThrow(OwnedOptionalTrialResume::unavailable);
        var method = steps.find(account.id(), o.providerOperationKey() + ":METHOD").orElseThrow(OwnedOptionalTrialResume::unavailable);
        require(id.equals(proof.operation()) && proof.subscription().equals(a.subscription()) && proof.account().equals(account.id())
            && proof.reference().matches("seti_[A-Za-z0-9]{1,250}") && proof.method() != null && proof.method().matches("pm_[A-Za-z0-9]{1,252}")
            && proof.step().equals(setup.id()) && acknowledged(setup, account, id, ProviderOperation.Kind.SETUP_INTENT_CREATE, proof.reference())
            && acknowledged(method, account, id, ProviderOperation.Kind.SUBSCRIPTION_UPDATE, original.subscription()));
        require(Integer.valueOf(2).equals(jdbc.queryForObject("""
            select count(*) from stripe_billing_optional_trial_setup_steps s join provider_operations p on p.id=s.step_id
            where s.clock_intent=? and s.setup_operation=? and p.version=2
              and p.recovery_attempts=0 and not p.manual_review_required and p.safe_error_code is null
              and ((s.step_kind='SETUP_INTENT_CREATE' and s.step_id=?) or (s.step_kind='SUBSCRIPTION_UPDATE' and s.step_id=?))
            """, Integer.class, a.clockIntent(), id, setup.id(), method.id())));
        return proof;
    }

    private static boolean acknowledged(ProviderOperation step, PaymentAccount account, UUID operation, ProviderOperation.Kind kind, String ref) {
        return step.status() == ProviderOperation.Status.ACKNOWLEDGED && step.kind() == kind && step.accountId().equals(account.id())
            && step.externalAccountId().equals(account.externalAccountId()) && step.credentialsRef().equals(account.credentialsRef())
            && step.resourceId().equals(operation) && ref.equals(step.providerReference()) && step.leaseOwner() == null;
    }

    private String fingerprint(PaymentAccount account, Authority a, Created original, SubscriptionOperation o,
            SubscriptionBillingProofStore.Setup setup, Subscription.Period period) {
        return StripeRequestFingerprint.of(digest, account, "OWNED_OPTIONAL_TRIAL_RESUME_V1", a.run().toString(), a.clockIntent().toString(),
            a.subscription().toString(), a.createOperation().toString(), a.fingerprint(), o.id().toString(), o.providerOperationKey(),
            o.fingerprint(), original.subscription(), original.item(), original.invoice(), a.clockReference(), a.customerReference(),
            a.priceReference(), a.productReference(), setup.operation().toString(), setup.step().toString(), setup.reference(), setup.method(),
            period.start().toString(), period.end().toString(), "EUR:2000", "anchor:now", "payment:success", "proration:none", "consent:true");
    }

    private void finishUnknown(UUID claim) {
        try {
            jdbc.queryForObject("select finish_owned_optional_trial_resume(?,null,null,null,null)", Boolean.class, claim);
        } catch (RuntimeException ignored) {
            // The consumed claim still fences any alternative dispatch.
        }
    }

    private StripeClient client(PaymentAccount account, Runnable current) {
        current.run();
        verification.verify(account);
        current.run();
        return clients.create(credentials.resolve(account));
    }

    static void requireAccount(PaymentAccount account, Authority a) {
        require(a.account().equals(account.id().value()) && account.provider() == PaymentProviderType.STRIPE
            && account.mode() == PaymentAccountMode.SANDBOX && account.status() == PaymentAccountStatus.ACTIVE
            && "stripe-sandbox-default".equals(account.key()) && APPROVED_ACCOUNT.equals(account.externalAccountId())
            && a.externalAccount().equals(account.externalAccountId()) && a.credentialsRef().equals(account.credentialsRef()));
    }

    private static Subscription.Period trialPeriod(Authority authority) {
        return new Subscription.Period(Instant.ofEpochSecond(authority.frozen()), Instant.ofEpochSecond(authority.trialEnd()));
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Owned optional trial resumption unavailable");
    }
}
