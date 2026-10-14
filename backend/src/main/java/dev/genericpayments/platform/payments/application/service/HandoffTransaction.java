package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.shared.application.RateLimitExceededException;
import dev.genericpayments.platform.shared.application.port.out.*;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
public class HandoffTransaction {
    private final HandoffSessionPort handoffs; private final CheckoutSessionPort checkouts; private final LoadPaymentPort payments;
    private final LoadSitePort sites; private final LoadPaymentAccountPort accounts; private final PaymentProviderRegistry providers;
    private final IdempotencyService idempotency; private final RandomTokenPort tokens; private final DigestPort digest;
    private final Stage2TelemetryPort telemetry; private final RateLimitPort limits; private final Clock clock;
    public HandoffTransaction(HandoffSessionPort handoffs,CheckoutSessionPort checkouts,LoadPaymentPort payments,LoadSitePort sites,LoadPaymentAccountPort accounts,
        PaymentProviderRegistry providers,IdempotencyService idempotency,RandomTokenPort tokens,DigestPort digest,Stage2TelemetryPort telemetry,RateLimitPort limits,Clock clock) {
        this.handoffs=handoffs; this.checkouts=checkouts; this.payments=payments; this.sites=sites; this.accounts=accounts; this.providers=providers;
        this.idempotency=idempotency; this.tokens=tokens; this.digest=digest; this.telemetry=telemetry; this.limits=limits; this.clock=clock;
    }
    @Transactional public DynamicPaymentTransaction.Phase issue(Site site,PaymentId paymentId,URI origin,String key) {
        var scope=Scope.partner(site.id().value(),Stage2TelemetryPort.Operation.HANDOFF_ISSUE,paymentId.value());
        var outcome=idempotency.begin(scope,key,idempotency.fingerprint(Map.of("payment_id",paymentId.value(),"checkout_origin",origin.toString())));
        if (!(outcome instanceof IdempotencyService.Owner owner)) return new DynamicPaymentTransaction.Phase(outcome,null,null,null);
        var payment=payments.findByIdForUpdate(site.id(),paymentId).orElseThrow(PartnerPaymentRules::notFound);
        if (!Set.of(PaymentStatus.CREATED,PaymentStatus.REQUIRES_PAYMENT_METHOD,PaymentStatus.REQUIRES_ACTION,PaymentStatus.PROCESSING).contains(payment.status())
            || payment.refundedAmountMinor()!=0) throw PartnerPaymentRules.state();
        var now=clock.instant(); var token=tokens.issue(32);
        var handoff=new HandoffSession(owner.id(),paymentId,site.id(),digest.sha256Hex(token),origin,now.plusSeconds(600),now,null);
        handoffs.save(handoff);
        idempotency.complete(owner,new Resource(ResourceType.HANDOFF,handoff.id()),201,new IdempotencyService.SecretFragment(token,handoff.expiresAt()));
        return new DynamicPaymentTransaction.Phase(outcome,payment,handoff,token);
    }
    @Transactional public CheckoutCreation exchange(String token,URI origin,String ip) {
        if (token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw CheckoutAuthorizationService.invalidToken();
        var hash=digest.sha256Hex(token);
        var handoff=handoffs.lockByHash(hash).orElseThrow(CheckoutAuthorizationService::invalidToken);
        var now=clock.instant();
        if (!MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),handoff.tokenHash().getBytes(StandardCharsets.US_ASCII))
            || origin==null || !handoff.origin().toString().equals(origin.toString()) || handoff.consumedAt()!=null || !now.isBefore(handoff.expiresAt())) throw CheckoutAuthorizationService.invalidToken();
        var site=sites.findById(handoff.siteId()).filter(s -> s.status()==SiteStatus.ACTIVE).orElseThrow(CheckoutAuthorizationService::invalidToken);
        try { if (!PartnerPaymentRules.origin(site,origin).toString().equals(origin.toString())) throw CheckoutAuthorizationService.invalidToken(); }
        catch (dev.genericpayments.platform.shared.application.PaymentApplicationException denied) { throw CheckoutAuthorizationService.invalidToken(); }
        var payment=payments.findById(site.id(),handoff.paymentId()).orElseThrow(CheckoutAuthorizationService::invalidToken);
        var account=PartnerPaymentRules.account(accounts,providers,payment.paymentAccountId());
        PartnerPaymentRules.preflight(telemetry,site,account);
        var decision=limits.acquire(new RateLimitPort.Key(site.id().value(),ip,RateLimitPort.Operation.HANDOFF));
        if (!decision.allowed()) throw new RateLimitExceededException(decision.retryAfterSeconds());
        handoffs.consume(handoff.id(),now);
        var checkoutToken=tokens.issue(32);
        var checkout=new CheckoutSession(UUID.randomUUID(),payment.id(),site.id(),digest.sha256Hex(checkoutToken),origin,Set.of("read","confirm"),now.plus(Duration.ofHours(24)),now);
        checkouts.save(checkout);
        return new CheckoutCreation(new CheckoutView(checkout.id(),payment.id().value(),payment.money().minorUnits(),payment.money().currency(),payment.priceSnapshot(),
            account.provider().name(),providers.select(account).capabilities(account).confirmationMode().name(),Map.of(),payment.status().name(),checkout.expiresAt()),checkoutToken);
    }
}
