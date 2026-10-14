package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.port.in.SimulateFakeEventUseCase;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.SiteStatus;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import java.time.*;
import java.util.*;
public final class SimulateFakeEventService implements SimulateFakeEventUseCase {
    private final AdminPaymentReadPort reads;private final PaymentTransitionPort tx;private final LoadSitePort sites;private final LoadPaymentAccountPort accounts;private final LoadRefundPort refunds;private final FakeEventControlPort control;private final IdempotencyService idem;private final AdminPaymentAuditPort audit;private final Stage2TelemetryPort telemetry;private final Clock clock;
    public SimulateFakeEventService(AdminPaymentReadPort reads,PaymentTransitionPort tx,LoadSitePort sites,LoadPaymentAccountPort accounts,LoadRefundPort refunds,FakeEventControlPort control,IdempotencyService idem,AdminPaymentAuditPort audit,Stage2TelemetryPort telemetry,Clock clock){this.reads=reads;this.tx=tx;this.sites=sites;this.accounts=accounts;this.refunds=refunds;this.control=control;this.idem=idem;this.audit=audit;this.telemetry=telemetry;this.clock=clock;}
    public Accepted simulate(Command c){return tx.transaction(()->{
        var view=reads.details(c.paymentId().value()).orElseThrow(PartnerPaymentRules::notFound).payment();var site=sites.findById(new dev.genericpayments.platform.sites.domain.SiteId(view.siteId())).filter(s->s.status()==SiteStatus.ACTIVE).orElseThrow(PartnerPaymentRules::state);
        var account=accounts.findById(site.paymentAccountId()).filter(a->a.status()==PaymentAccountStatus.ACTIVE&&a.provider()==PaymentProviderType.FAKE&&a.id().value().equals(view.paymentAccountId())).orElseThrow(()->new PaymentApplicationException("PROVIDER_CAPABILITY_UNAVAILABLE",409,"Fake provider capability unavailable"));
        PartnerPaymentRules.preflight(telemetry,site,account);var context=new Stage2TelemetryPort.Context(site.key().value(),Stage2TelemetryPort.Provider.FAKE);
        if(c.occurredAt()!=null&&(c.occurredAt().isAfter(clock.instant())||c.occurredAt().isBefore(view.createdAt())))throw new PaymentApplicationException("FAKE_EVENT_INVALID",400,"Invalid fake event time");
        var fingerprint=idem.fingerprint(Map.of("payment_id",c.paymentId().value(),"target",c.target().name(),"type",c.eventType().name(),"sequence",c.sequence(),"refund",c.refundId()==null?"":c.refundId().value().toString(),"occurred",c.occurredAt()==null?"":c.occurredAt().toString()));
        var outcome=idem.begin(IdempotencyRecord.Scope.admin(site.id().value(),c.adminId(),Stage2TelemetryPort.Operation.FAKE_EVENT),c.key(),fingerprint);
        if(outcome instanceof IdempotencyService.Conflict)throw RefundCoordinator.conflict();
        String eventId="fake_evt_"+(c.refundId()==null?c.paymentId().value():c.refundId().value())+"_"+c.sequence();
        if(outcome instanceof IdempotencyService.Completed)return new Accepted(eventId,"ACCEPTED");
        if(!(outcome instanceof IdempotencyService.Owner owner))throw PartnerPaymentRules.state();
        var p=tx.lock(new PaymentTransitionPort.Identity(site.id(),c.paymentId(),context));
        var r=c.refundId()==null?null:refunds.findByIdForUpdate(p.siteId(),c.refundId()).filter(s->s.value().paymentId().equals(p.id())).orElseThrow(PartnerPaymentRules::notFound).value();
        eventId=control.enqueue(account,p,r,c.target(),c.eventType(),c.sequence(),c.occurredAt()==null?clock.instant():c.occurredAt());
        idem.complete(owner,new IdempotencyRecord.Resource(IdempotencyRecord.ResourceType.PAYMENT,p.id().value()),202,null);
        audit.record(AdminPaymentAuditPort.Action.FAKE_PROVIDER_EVENT_SIMULATED,c.adminId(),p.id().value(),p.id().value(),c.requestId(),Map.of("status","ACCEPTED","target",c.target().name()));
        return new Accepted(eventId,"ACCEPTED");
    });}
}
