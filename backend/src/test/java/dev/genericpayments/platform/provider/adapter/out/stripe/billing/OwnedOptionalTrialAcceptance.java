package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.port.in.ResumeSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionAuthorityPort;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionOperationStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Test-classpath entry for one freshly admitted optional trial. */
public final class OwnedOptionalTrialAcceptance {
    public enum Status { TRIALING, PAUSED, WAITING_FOR_SETUP, SETUP_INSTALLED, OPEN, PAID, STOPPED }

    public record Snapshot(OwnedOptionalTrialCreate.Scope scope, Status status, UUID setupOperation, UUID resumeOperation) {}

    private final OwnedOptionalTrialAdmission admission;
    private final OwnedOptionalTrialCreate create;
    private final OwnedOptionalTrialPublication publication;
    private final OwnedOptionalTrialClockAdvance clock;
    private final OwnedOptionalTrialSetup setup;
    private final OwnedOptionalTrialResume resume;
    private final OwnedOptionalTrialPaymentConfirmation confirmation;

    OwnedOptionalTrialAcceptance(OwnedOptionalTrialAdmission admission, OwnedOptionalTrialCreate create,
            OwnedOptionalTrialPublication publication, OwnedOptionalTrialClockAdvance clock, OwnedOptionalTrialSetup setup,
            OwnedOptionalTrialResume resume, OwnedOptionalTrialPaymentConfirmation confirmation) {
        this.admission = admission;
        this.create = create;
        this.publication = publication;
        this.clock = clock;
        this.setup = setup;
        this.resume = resume;
        this.confirmation = confirmation;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({OwnedOptionalTrialAdmission.Configuration.class, OwnedOptionalTrialPublication.Configuration.class,
        OwnedOptionalTrialResume.Configuration.class, OwnedOptionalTrialPaymentConfirmation.Configuration.class})
    public static class Configuration {
        @Bean OwnedOptionalTrialAcceptance ownedOptionalTrialAcceptance(JdbcTemplate jdbc, StripeCredentialResolver credentials,
                StripeClientFactory clients, PaymentAccountVerificationPort verification, SubscriptionTransactionPort transactions,
                SubscriptionAuthorityPort authority, SubscriptionOperationStore operations, SubscriptionStore subscriptions,
                DigestPort digest, Clock operationalClock, OwnedOptionalTrialAdmission admission, OwnedOptionalTrialPublication publication,
                OwnedOptionalTrialResume resume, OwnedOptionalTrialPaymentConfirmation confirmation) {
            var create = OwnedOptionalTrialCreate.withDatabase(jdbc, credentials, clients, verification);
            var clock = OwnedOptionalTrialClockAdvance.withDatabase(jdbc, credentials, clients, verification);
            var setup = new OwnedOptionalTrialSetup(jdbc, transactions, authority, operations, subscriptions, digest,
                credentials, clients, verification, publication, operationalClock);
            return new OwnedOptionalTrialAcceptance(admission, create, publication, clock, setup, resume, confirmation);
        }
    }

    public Session open(OwnedOptionalTrialAdmission.Request request) {
        var admitted = checked("OPTIONAL_TRIAL_ADMISSION", () -> admission.admit(request));
        checked("OPTIONAL_TRIAL_CREATE", () -> {
            create.create(admitted.account(), admitted.scope());
            return null;
        });
        checked("OPTIONAL_TRIAL_TRIALING", () -> {
            publication.publish(admitted.account(), admitted.scope());
            return null;
        });
        return new Session(admitted);
    }

    public final class Session {
        private final PaymentAccount account;
        private final OwnedOptionalTrialCreate.Scope scope;
        private final long frozen;
        private final long trialEnd;
        private Status status = Status.TRIALING;
        private UUID setupOperation;
        private UUID resumeOperation;
        private SubscriptionCommandContext setupContext;
        private OwnedOptionalTrialSetup.Prepared prepared;

        private Session(OwnedOptionalTrialAdmission.Admitted admitted) {
            this.account = admitted.account();
            this.scope = admitted.scope();
            this.frozen = admitted.frozen();
            this.trialEnd = admitted.trialEnd();
        }

        public long frozen() { return frozen; }
        public long trialEnd() { return trialEnd; }

        public synchronized Snapshot snapshot() {
            return new Snapshot(scope, status, setupOperation, resumeOperation);
        }

        public synchronized void advance() {
            step(Status.TRIALING, Status.PAUSED, "OPTIONAL_TRIAL_ADVANCE_AND_PAUSE", () -> {
                publication.advanceAndPause(account, scope, clock);
                return null;
            });
        }

        public synchronized OwnedOptionalTrialSetup.SetupPermit prepareSetup(SubscriptionCommandContext context, boolean recurringConsent) {
            return step(Status.PAUSED, Status.WAITING_FOR_SETUP, "OPTIONAL_TRIAL_SETUP_PERMIT", () -> {
                if (!recurringConsent) throw new IllegalStateException();
                prepared = setup.prepare(account, scope, context, recurringConsent);
                setupOperation = prepared.operation();
                setupContext = context;
                return setup.claimBrowserPermit(account, scope, context, prepared);
            });
        }

        public synchronized void completeSetup() {
            step(Status.WAITING_FOR_SETUP, Status.SETUP_INSTALLED, "OPTIONAL_TRIAL_SETUP_INSTALL", () -> {
                setup.complete(account, scope, setupContext, prepared);
                clearSetup();
                return null;
            });
        }

        public synchronized void resume(SubscriptionCommandContext context, boolean recurringConsent) {
            step(Status.SETUP_INSTALLED, Status.OPEN, "OPTIONAL_TRIAL_RESUME_OPEN", () -> {
                if (!recurringConsent) throw new IllegalStateException();
                var command = new ResumeSubscriptionUseCase.Command(context, scope.subscription(), recurringConsent);
                var result = publication.startResume(account, scope, setupOperation, command, resume);
                if (!scope.subscription().equals(result.subscriptionId()) || result.operationId() == null) {
                    throw new IllegalStateException();
                }
                resumeOperation = result.operationId();
                if (result.status() != SubscriptionOperation.Status.PROCESSING
                        || result.nextAction() != null || result.browserConfirmAllowed()) {
                    throw new IllegalStateException();
                }
                publication.observeResume(account, scope, setupOperation, resumeOperation, resume, false);
                return null;
            });
        }

        public synchronized void pay() {
            step(Status.OPEN, Status.PAID, "OPTIONAL_TRIAL_PAYMENT_CONFIRMATION", () -> {
                publication.confirmResumePayment(account, scope, setupOperation, resumeOperation, confirmation);
                checked("OPTIONAL_TRIAL_PAYMENT_OBSERVATION", () -> {
                    publication.observeResume(account, scope, setupOperation, resumeOperation, resume, true);
                    return null;
                });
                return null;
            });
        }

        public synchronized void stop() {
            if (status != Status.PAID) status = Status.STOPPED;
            clearSetup();
        }

        private <T> T step(Status expected, Status next, String code, Supplier<T> action) {
            if (status != expected) throw new IllegalStateException("OPTIONAL_TRIAL_PHASE");
            status = Status.STOPPED;
            try {
                T result = checked(code, action);
                status = next;
                return result;
            } finally {
                if (status == Status.STOPPED) clearSetup();
            }
        }

        private void clearSetup() {
            setupContext = null;
            prepared = null;
        }
    }

    private static <T> T checked(String code, Supplier<T> action) {
        try {
            return action.get();
        } catch (PhaseFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new PhaseFailure(code);
        }
    }

    private static final class PhaseFailure extends IllegalStateException {
        private PhaseFailure(String code) {
            super(code);
        }
    }
}
