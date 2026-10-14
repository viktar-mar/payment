package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord.Scope;
import dev.genericpayments.platform.payments.application.port.in.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.*;
import dev.genericpayments.platform.sites.domain.*;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class CatalogCheckoutService implements CreateCatalogCheckoutUseCase, ConfirmCheckoutUseCase, GetCheckoutUseCase {
    private final CheckoutAuthorizationService authorization;
    private final CatalogCheckoutTransaction phases;
    private final LoadPaymentPort payments;
    private final LoadPaymentAttemptPort attempts;
    private final LoadPaymentAccountPort accounts;
    private final PaymentProviderRegistry providers;
    private final PaymentPreparationCoordinator preparation;
    private final PaymentConfirmationCoordinator confirmation;
    private final DigestPort digest;
    private final Stage2TelemetryPort telemetry;
    private final Clock clock;
    public CatalogCheckoutService(CheckoutAuthorizationService authorization, CatalogCheckoutTransaction phases, LoadPaymentPort payments,
        LoadPaymentAttemptPort attempts, LoadPaymentAccountPort accounts, PaymentProviderRegistry providers, PaymentPreparationCoordinator preparation,
        PaymentConfirmationCoordinator confirmation, DigestPort digest, Stage2TelemetryPort telemetry, Clock clock) {
        this.authorization = authorization; this.phases = phases; this.payments = payments; this.attempts = attempts;
        this.accounts = accounts; this.providers = providers; this.preparation = preparation; this.confirmation = confirmation;
        this.digest = digest; this.telemetry = telemetry; this.clock = clock;
    }
    @Override public CheckoutCreation create(CreateCatalogCheckoutCommand command) {
        noAmbientTransaction();
        var site = authorization.site(command.siteKey(), command.origin());
        var account = account(site.paymentAccountId());
        capabilities(account);
        requireTelemetry(site, account);
        var result = phases.create(command, site, account);
        if (result.outcome() instanceof IdempotencyService.Conflict) throw conflict();
        if (result.outcome() instanceof IdempotencyService.InProgress progress) throw new PaymentApplicationException("PAYMENT_STATE_CONFLICT", 409,
            "Payment operation in progress", Map.of("operation_id", progress.operationId()));
        CheckoutSession session; Payment payment; String token;
        if (result.outcome() instanceof IdempotencyService.Owner) {
            session = result.session(); payment = result.payment(); token = result.token();
            telemetry.paymentCreated(new Stage2TelemetryPort.Context(site.key().value(), Stage2TelemetryPort.Provider.valueOf(account.provider().name())));
        } else {
            var resource = result.outcome() instanceof IdempotencyService.Completed completed ? completed.resource()
                : ((IdempotencyService.ExpiredSecret) result.outcome()).resource();
            session = authorization.session(resource.id());
            payment = payments.findById(session.siteId(), session.paymentId()).orElseThrow(() -> CheckoutAuthorizationService.invalidToken());
            if(!Objects.equals(payment.customerId(),command.customerId())) throw conflict();
            if (result.outcome() instanceof IdempotencyService.ExpiredSecret || !clock.instant().isBefore(session.expiresAt())) throw CheckoutAuthorizationService.expired(payment.id().value());
            token = ((IdempotencyService.Completed) result.outcome()).secret().orElseThrow(() -> CheckoutAuthorizationService.invalidToken());
            account = account(payment.paymentAccountId());
        }
        if (payment.status() == PaymentStatus.CREATED && payment.providerPaymentReference() == null) {
            var original = original(payment);
            try { payment = preparation.prepare(new PreparePaymentCommand(original, account, site.key().value(), command.requestId(), command.idempotencyKey())); }
            catch (IllegalStateException state) { throw stateError(state); }
        }
        if (payment.status() == PaymentStatus.CREATED && payment.providerPaymentReference() == null) throw new PaymentApplicationException("PROVIDER_RESULT_UNKNOWN", 503, "Provider result unknown", Map.of("payment_id", payment.id().value()));
        return new CheckoutCreation(view(session, payment, account), token);
    }
    @Override public CheckoutView confirm(ConfirmCheckoutCommand command) {
        noAmbientTransaction();
        var authorized = authorization.authorize(command.checkoutId(), command.origin(), command.checkoutToken(), "confirm");
        var account = account(authorized.payment().paymentAccountId());
        var capabilities = capabilities(account);
        requireTelemetry(authorized.site(), account);
        if (command.method() == null || capabilities.confirmationMode() != PaymentProviderCapabilities.ConfirmationMode.SERVER
            || !capabilities.methods().contains(command.method()) || authorized.site().paymentMethodPolicy().mode() == PaymentMethodPolicyMode.ALLOWLIST
            && !authorized.site().paymentMethodPolicy().allowedMethods().contains(command.method())) throw CatalogCheckoutTransaction.capability();
        if(authorized.payment().source()==PaymentSource.DONATION && authorized.payment().status()==PaymentStatus.CREATED
            && authorized.payment().providerPaymentReference()==null) {
            try {
                preparation.prepare(new PreparePaymentCommand(authorized.payment(),account,authorized.site().key().value(),command.requestId(),command.idempotencyKey()));
            } catch(IllegalStateException state) {
                // Another authorized confirmation may already have prepared/started this same Payment.
                var refreshed=authorization.authorize(command.checkoutId(),command.origin(),command.checkoutToken(),"confirm");
                if(!"PAYMENT_STATE_CONFLICT".equals(state.getMessage()) || refreshed.payment().providerPaymentReference()==null) throw stateError(state);
            }
            authorized=authorization.authorize(command.checkoutId(),command.origin(),command.checkoutToken(),"confirm");
            account=account(authorized.payment().paymentAccountId());
            if(authorized.payment().status()==PaymentStatus.CREATED && authorized.payment().providerPaymentReference()==null)
                throw new PaymentApplicationException("PROVIDER_RESULT_UNKNOWN",503,"Provider result unknown",Map.of("payment_id",authorized.payment().id().value()));
        }
        var scope = Scope.publicOperation(authorized.site().id().value(), Stage2TelemetryPort.Operation.CHECKOUT_CONFIRM, command.checkoutId());
        var operation = new ConfirmPaymentCommand(authorized.site().id(), authorized.payment().id(), account,
            attemptId(scope, command.idempotencyKey()), command.method(), authorized.site().key().value(), command.requestId(), command.idempotencyKey());
        IdempotencyService.Outcome outcome;
        try { outcome = phases.confirm(command, authorized, operation); }
        catch (IllegalStateException state) { throw stateError(state); }
        if (outcome instanceof IdempotencyService.Conflict) throw conflict();
        if (outcome instanceof IdempotencyService.InProgress) return view(authorized.session(), reload(authorized.session()), account);
        var stored = attempts.findById(authorized.site().id(), operation.attemptId()).orElseThrow();
        if (stored.value().status() == PaymentAttemptStatus.CREATED || stored.value().status() == PaymentAttemptStatus.UNKNOWN) {
            try { confirmation.confirm(operation); }
            catch (IllegalStateException state) { throw stateError(state); }
            stored = attempts.findById(authorized.site().id(), operation.attemptId()).orElseThrow();
        }
        switch (stored.value().status()) {
            case UNKNOWN -> throw new PaymentApplicationException("PROVIDER_RESULT_UNKNOWN", 503, "Provider result unknown");
            case FAILED -> {
                if ("UNAVAILABLE".equals(stored.value().resultCode())) throw new PaymentApplicationException("PROVIDER_TEMPORARILY_UNAVAILABLE", 503, "Provider temporarily unavailable");
                throw new PaymentApplicationException("PROVIDER_DECLINED", 422, "Payment declined");
            }
            default -> { }
        }
        return view(authorized.session(), reload(authorized.session()), account);
    }
    @Override public CheckoutView get(UUID id, URI origin, String token) {
        var authorized = authorization.authorize(id, origin, token, "read");
        return view(authorized.session(), authorized.payment(), account(authorized.payment().paymentAccountId()));
    }
    private Payment reload(CheckoutSession session) { return payments.findById(session.siteId(), session.paymentId()).orElseThrow(); }
    private PaymentAccount account(PaymentAccountId id) {
        var account = accounts.findById(id).orElseThrow(() -> CatalogCheckoutTransaction.capability());
        if (account.status() != PaymentAccountStatus.ACTIVE) throw CatalogCheckoutTransaction.capability();
        return account;
    }
    private PaymentProviderCapabilities capabilities(PaymentAccount account) {
        try { return providers.select(account).capabilities(account); }
        catch (IllegalStateException unsupported) { throw CatalogCheckoutTransaction.capability(); }
    }
    private CheckoutView view(CheckoutSession session, Payment payment, PaymentAccount account) {
        return new CheckoutView(session.id(), payment.id().value(), payment.money().minorUnits(), payment.money().currency(), payment.priceSnapshot(),
            account.provider().name(), capabilities(account).confirmationMode().name(), Map.of(), payment.status().name(), session.expiresAt());
    }
    private void requireTelemetry(Site site, PaymentAccount account) {
        try { telemetry.requireContext(new Stage2TelemetryPort.Context(site.key().value(), Stage2TelemetryPort.Provider.valueOf(account.provider().name()))); }
        catch (IllegalArgumentException misconfigured) { throw new PaymentApplicationException("SITE_NOT_AVAILABLE", 503, "Site not available"); }
    }
    private PaymentAttemptId attemptId(Scope scope, String key) {
        IdempotencyRecord.validateText(key, 255);
        // Length-prefix the key: trusted scope + arbitrary validated key cannot collide by delimiter manipulation.
        var hex = digest.sha256Hex("checkout-confirm-v1:" + scope.value() + ":" + key.length() + ":" + key);
        return new PaymentAttemptId(UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
            + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32)));
    }
    private static Payment original(Payment p) {
        return new Payment(p.id(), p.siteId(), p.customerId(), p.paymentAccountId(), p.source(), p.priceId(), p.priceSnapshot(), p.externalOrderId(),p.donationId(),p.donationSnapshot(), p.money(),
            p.taxMode(), p.taxAmountMinor(), PaymentStatus.CREATED, null, 0, 0, p.createdAt(), p.createdAt(), p.technicalMetadata());
    }
    private static PaymentApplicationException conflict() { return new PaymentApplicationException("IDEMPOTENCY_CONFLICT", 409, "Idempotency conflict"); }
    private static PaymentApplicationException stateError(IllegalStateException error) {
        if ("PAYMENT_CAPABILITY_UNAVAILABLE".equals(error.getMessage())) return CatalogCheckoutTransaction.capability();
        if ("PAYMENT_STATE_CONFLICT".equals(error.getMessage())) return new PaymentApplicationException("PAYMENT_STATE_CONFLICT", 409, "Payment state conflict");
        throw error;
    }
    private static void noAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Checkout coordinator requires no ambient transaction");
    }
}
