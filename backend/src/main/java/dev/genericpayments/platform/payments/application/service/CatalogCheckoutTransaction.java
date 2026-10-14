package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.catalog.application.port.out.LoadPricePort;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord.*;
import dev.genericpayments.platform.payments.application.port.in.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.*;
import dev.genericpayments.platform.sites.domain.Site;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

/** The claim, local money facts, session and completion share one transaction. */
public class CatalogCheckoutTransaction {
    private final IdempotencyService idempotency;
    private final LoadPricePort prices;
    private final CheckoutSessionPort sessions;
    private final PaymentCreationTransaction creation;
    private final PaymentAttemptTransaction attempts;
    private final RandomTokenPort tokens;
    private final DigestPort digest;
    private final Clock clock;
    public CatalogCheckoutTransaction(IdempotencyService idempotency, LoadPricePort prices, CheckoutSessionPort sessions,
        PaymentCreationTransaction creation, PaymentAttemptTransaction attempts, RandomTokenPort tokens, DigestPort digest, Clock clock) {
        this.idempotency = idempotency; this.prices = prices; this.sessions = sessions; this.creation = creation;
        this.attempts = attempts; this.tokens = tokens; this.digest = digest; this.clock = clock;
    }
    public record CreatedPhase(IdempotencyService.Outcome outcome, CheckoutSession session, Payment payment, String token) {
        @Override public String toString() { return "CreatedPhase[token=redacted]"; }
    }
    @Transactional
    public CreatedPhase create(CreateCatalogCheckoutCommand command, Site site, PaymentAccount account) {
        var scope = Scope.publicOperation(site.id().value(), Stage2TelemetryPort.Operation.CHECKOUT_CREATE, null);
        var legacyGuestFingerprint = idempotency.fingerprint(Map.of("site_id", site.id().value(), "price_key", command.priceKey(),
            "origin", command.origin().toString()));
        var fingerprint = idempotency.fingerprint(Map.of("site_id", site.id().value(), "price_key", command.priceKey(),
            "origin", command.origin().toString(), "customer_ownership", ownership(command.customerId())));
        var compatible = command.customerId() == null ? Set.of(legacyGuestFingerprint) : Set.<String>of();
        var outcome = idempotency.beginCompatibleReplay(scope, command.idempotencyKey(), fingerprint, compatible);
        if (!(outcome instanceof IdempotencyService.Owner owner)) return new CreatedPhase(outcome, null, null, null);
        Price price;
        try { price = prices.findCurrentlyPublished(site.id(), PriceKey.of(command.priceKey())).orElseThrow(() -> priceUnavailable()); }
        catch (dev.genericpayments.platform.shared.domain.DomainValidationException invalid) { throw priceUnavailable(); }
        if (!price.siteId().equals(site.id()) || price.status() != PriceStatus.PUBLISHED || price.type() != PriceType.ONE_TIME) throw priceUnavailable();
        if (price.taxMode() != TaxMode.NONE && price.taxMode() != TaxMode.INCLUSIVE) throw capability();
        var snapshot = new PriceSnapshot(price.id(), price.key().value(), price.version(), price.money(), price.taxMode());
        // PostgreSQL timestamps retain microseconds; publish the same immutable value before and after persistence.
        var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var payment = Payment.catalog(new PaymentId(UUID.randomUUID()), site.id(), command.customerId(), account.id(), snapshot, Map.of(), now);
        var local = creation.createLocal(new PreparePaymentCommand(payment, account, site.key().value(), command.requestId(), command.idempotencyKey()));
        var token = tokens.issue(32);
        var session = new CheckoutSession(owner.id(), local.payment().id(), site.id(), digest.sha256Hex(token), command.origin(),
            Set.of("read", "confirm"), now.plus(Duration.ofHours(24)), now);
        sessions.save(session);
        idempotency.complete(owner, new Resource(ResourceType.CHECKOUT, session.id()), 201, new IdempotencyService.SecretFragment(token, session.expiresAt()));
        return new CreatedPhase(outcome, session, local.payment(), token);
    }
    @Transactional
    public IdempotencyService.Outcome confirm(ConfirmCheckoutCommand command, CheckoutAuthorizationService.Authorized authorized,
        ConfirmPaymentCommand operation) {
        var scope = Scope.publicOperation(authorized.site().id().value(), Stage2TelemetryPort.Operation.CHECKOUT_CONFIRM, command.checkoutId());
        var fingerprint = idempotency.fingerprint(Map.of("checkout_id", command.checkoutId(), "payment_id", authorized.payment().id().value(),
            "payment_method_family", command.method().name()));
        var outcome = idempotency.begin(scope, command.idempotencyKey(), fingerprint);
        if (outcome instanceof IdempotencyService.Owner owner) {
            attempts.startAttempt(operation);
            idempotency.complete(owner, new Resource(ResourceType.CHECKOUT, command.checkoutId()), 200, null);
        }
        return outcome;
    }
    static PaymentApplicationException priceUnavailable() { return new PaymentApplicationException("PRICE_NOT_AVAILABLE", 404, "Price not available"); }
    static PaymentApplicationException capability() { return new PaymentApplicationException("PAYMENT_CAPABILITY_UNAVAILABLE", 422, "Payment capability unavailable"); }
    private static String ownership(UUID customerId) { return customerId == null ? "guest" : "customer:" + customerId; }
}
