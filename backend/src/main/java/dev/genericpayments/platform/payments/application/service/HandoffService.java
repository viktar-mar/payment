package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.in.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import java.net.URI;
import java.time.*;
import java.util.*;
public final class HandoffService implements IssueHandoffUseCase,ExchangeHandoffUseCase {
    private final HandoffTransaction phases; private final HandoffSessionPort handoffs; private final LoadPaymentPort payments;
    private final LoadSitePort sites; private final LoadPaymentAccountPort accounts; private final PaymentProviderRegistry providers;
    private final Stage2TelemetryPort telemetry; private final Clock clock;
    public HandoffService(HandoffTransaction phases,HandoffSessionPort handoffs,LoadPaymentPort payments,LoadSitePort sites,LoadPaymentAccountPort accounts,
        PaymentProviderRegistry providers,Stage2TelemetryPort telemetry,Clock clock) {
        this.phases=phases; this.handoffs=handoffs; this.payments=payments; this.sites=sites; this.accounts=accounts; this.providers=providers; this.telemetry=telemetry; this.clock=clock;
    }
    public Site site(SiteId id) { return sites.findById(id).filter(s -> s.status()==SiteStatus.ACTIVE).orElseThrow(CheckoutAuthorizationService::unavailable); }
    public Instant now() { return clock.instant(); }
    public record Replay(HandoffSession handoff,String token) { @Override public String toString() { return "Replay[secret=redacted]"; } }
    public Replay replay(SiteId site,IdempotencyService.Outcome outcome) {
        if (outcome instanceof IdempotencyService.Conflict) throw new PaymentApplicationException("IDEMPOTENCY_CONFLICT",409,"Idempotency conflict");
        if (outcome instanceof IdempotencyService.InProgress progress) throw new PaymentApplicationException("PAYMENT_STATE_CONFLICT",409,"Payment operation in progress",Map.of("operation_id",progress.operationId()));
        var resource=outcome instanceof IdempotencyService.Completed complete ? complete.resource() : ((IdempotencyService.ExpiredSecret) outcome).resource();
        if (resource.type()!=IdempotencyRecord.ResourceType.HANDOFF) throw PartnerPaymentRules.state();
        var handoff=handoffs.findById(resource.id()).filter(h -> h.siteId().equals(site)).orElseThrow(PartnerPaymentRules::notFound);
        if (outcome instanceof IdempotencyService.ExpiredSecret || !clock.instant().isBefore(handoff.expiresAt())) throw CheckoutAuthorizationService.expired(handoff.paymentId().value());
        return new Replay(handoff,((IdempotencyService.Completed) outcome).secret().orElseThrow(PartnerPaymentRules::state));
    }
    @Override public HandoffCreation issue(SiteId id,PaymentId payment,URI origin,String key,String requestId) {
        var site=site(id); var normalized=PartnerPaymentRules.origin(site,origin);
        var owned=payments.findById(site.id(),payment).orElseThrow(PartnerPaymentRules::notFound);
        var account=PartnerPaymentRules.account(accounts,providers,owned.paymentAccountId()); PartnerPaymentRules.preflight(telemetry,site,account);
        var result=phases.issue(site,payment,normalized,key);
        if (result.outcome() instanceof IdempotencyService.Owner) return HandoffCreation.of(result.payment(),result.token(),result.handoff().expiresAt());
        var replay=replay(site.id(),result.outcome());
        return HandoffCreation.of(payments.findById(site.id(),payment).orElseThrow(PartnerPaymentRules::notFound),replay.token(),replay.handoff().expiresAt());
    }
    @Override public CheckoutCreation exchange(String token,URI origin,String ip) { return phases.exchange(token,origin,ip); }
}
