package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.port.in.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.*;
import java.time.*;
import java.util.*;

/** Commit reservation before provider IO; replay resumes the exact durable operation. */
public final class RefundCoordinator implements CreateRefundUseCase {
    private record Reservation(Payment payment,Refund refund,PaymentAccount account,Stage2TelemetryPort.Context context,boolean created) {}
    private final PaymentTransitionPort tx;private final LoadPaymentPort payments;private final LoadRefundPort refunds;private final SaveRefundPort save;
    private final LoadSitePort sites;private final LoadPaymentAccountPort accounts;private final PaymentProviderRegistry providers;
    private final IdempotencyService idem;private final AppendPaymentEventPort events;private final AdminPaymentAuditPort audit;
    private final RefundTransitionService transitions;private final Stage2TelemetryPort telemetry;private final Clock clock;
    public RefundCoordinator(PaymentTransitionPort tx,LoadPaymentPort payments,LoadRefundPort refunds,SaveRefundPort save,LoadSitePort sites,LoadPaymentAccountPort accounts,PaymentProviderRegistry providers,IdempotencyService idem,AppendPaymentEventPort events,AdminPaymentAuditPort audit,RefundTransitionService transitions,Stage2TelemetryPort telemetry,Clock clock){this.tx=tx;this.payments=payments;this.refunds=refunds;this.save=save;this.sites=sites;this.accounts=accounts;this.providers=providers;this.idem=idem;this.events=events;this.audit=audit;this.transitions=transitions;this.telemetry=telemetry;this.clock=clock;}
    @Override public Refund create(CreateRefundCommand c){
        if(tx.hasAmbientTransaction())throw new IllegalStateException("Provider coordinator requires no ambient transaction");
        var reservation=tx.transaction(()->reserve(c));if(reservation.created())telemetry.refundRequested(reservation.context());
        var r=reservation.refund();
        if(r.status()!=RefundStatus.PENDING&&!(r.status()==RefundStatus.UNKNOWN&&r.providerRefundReference()==null))return r;
        return request(reservation.payment(),r,reservation.account(),reservation.context(),c.requestId());
    }
    public Refund resume(Payment p,Refund r,PaymentAccount account,Stage2TelemetryPort.Context context,String requestId){
        if(tx.hasAmbientTransaction())throw new IllegalStateException("Provider coordinator requires no ambient transaction");
        return request(p,r,account,context,requestId);
    }
    private Refund request(Payment p,Refund r,PaymentAccount account,Stage2TelemetryPort.Context context,String requestId){
        var provider=providers.select(account);long start=System.nanoTime();ProviderResult result;
        try{result=provider.requestRefund(new ProviderRefundRequest(account,p,r,ProviderOperationKeys.refund(r.id())));if(result==null)result=new ProviderResult.Unknown(null);}
        catch(RuntimeException failure){result=new ProviderResult.Unknown(null);}
        telemetry.providerCall(context,Stage2TelemetryPort.ProviderOperation.REFUND,Stage2TelemetryPort.ProviderResult.valueOf(result.kind().name()),Duration.ofNanos(Math.max(0,System.nanoTime()-start)));
        var acknowledged=result;
        var committed=tx.transaction(()->{var locked=payments.findByIdForUpdate(p.siteId(),p.id()).orElseThrow(PartnerPaymentRules::notFound);var stored=refunds.findByIdForUpdate(p.siteId(),r.id()).orElseThrow(PartnerPaymentRules::notFound);if(!locked.paymentAccountId().equals(account.id())||!stored.value().paymentId().equals(locked.id()))throw PartnerPaymentRules.state();return transitions.acknowledge(locked,stored,acknowledged,context,requestId);});
        transitions.emit(committed);return committed.refund();
    }
    private Reservation reserve(CreateRefundCommand c){
        var site=sites.findById(c.siteId()).filter(s->s.status()==SiteStatus.ACTIVE).orElseThrow(PartnerPaymentRules::state);
        var p=payments.findByIdForUpdate(c.siteId(),c.paymentId()).orElseThrow(PartnerPaymentRules::notFound);
        var account=accounts.findById(p.source()==PaymentSource.SUBSCRIPTION?p.paymentAccountId():site.paymentAccountId()).filter(a->a.status()==PaymentAccountStatus.ACTIVE).orElseThrow(PartnerPaymentRules::state);
        try{providers.select(account);}catch(IllegalStateException unavailable){throw PartnerPaymentRules.state();}
        PartnerPaymentRules.preflight(telemetry,site,account);var context=new Stage2TelemetryPort.Context(site.key().value(),Stage2TelemetryPort.Provider.valueOf(account.provider().name()));
        String hash=idem.fingerprint(Map.of("payment_id",c.paymentId().value(),"amount",c.amountMinor(),"currency",c.currency(),"reason",c.reasonCode(),"note",Objects.toString(c.technicalNote(),"")));
        var outcome=idem.begin(IdempotencyRecord.Scope.admin(c.siteId().value(),c.adminId(),Stage2TelemetryPort.Operation.REFUND_CREATE),c.idempotencyKey(),hash);
        if(outcome instanceof IdempotencyService.Conflict)throw conflict();
        if(!p.paymentAccountId().equals(account.id()))throw PartnerPaymentRules.state();
        if(outcome instanceof IdempotencyService.Completed completed){if(completed.resource().type()!=IdempotencyRecord.ResourceType.REFUND)throw PartnerPaymentRules.state();var r=refunds.findById(c.siteId(),new RefundId(completed.resource().id())).orElseThrow(PartnerPaymentRules::notFound).value();if(!r.paymentId().equals(p.id()))throw conflict();return new Reservation(p,r,account,context,false);}
        if(!(outcome instanceof IdempotencyService.Owner owner))throw PartnerPaymentRules.state();
        if(!p.canRefund()||p.providerPaymentReference()==null)throw PartnerPaymentRules.state();
        if(!p.money().currency().equals(c.currency()))throw PartnerPaymentRules.state();
        long reserved=refunds.findReservedByPaymentForUpdate(c.siteId(),p.id()).stream().mapToLong(s->s.value().money().minorUnits()).reduce(0,Math::addExact);
        if(c.amountMinor()>p.money().minorUnits()-reserved)throw new PaymentApplicationException("REFUND_EXCEEDS_AVAILABLE",409,"Refund exceeds available amount");
        var id=new RefundId(UUID.randomUUID());var now=clock.instant().isBefore(p.updatedAt())?p.updatedAt():clock.instant();
        var r=save.insert(Refund.request(id,p,Money.of(c.amountMinor(),c.currency()),ProviderOperationKeys.refund(id),c.reasonCode(),c.technicalNote(),now)).value();
        events.append(p.id(),seq->new PaymentEvent(UUID.randomUUID(),p.id(),seq,p.siteId(),p.paymentAccountId(),PaymentEventType.REFUND_REQUESTED,PaymentEventSource.ADMIN,r.money(),null,null,now,now,c.requestId(),null,hash,Map.of("refund_id",id.value().toString(),"state","PENDING")));
        idem.complete(owner,new IdempotencyRecord.Resource(IdempotencyRecord.ResourceType.REFUND,id.value()),202,null);
        audit.record(AdminPaymentAuditPort.Action.PAYMENT_REFUND_REQUESTED,c.adminId(),p.id().value(),id.value(),c.requestId(),Map.of("status","PENDING","site_id",p.siteId().value().toString()));
        return new Reservation(p,r,account,context,true);
    }
    static PaymentApplicationException conflict(){return new PaymentApplicationException("IDEMPOTENCY_CONFLICT",409,"Idempotency conflict");}
}
