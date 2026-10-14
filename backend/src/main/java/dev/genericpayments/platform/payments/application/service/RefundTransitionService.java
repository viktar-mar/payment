package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.shared.application.port.out.*;
import java.time.*;
import java.util.*;

/** Refund facts share the parent-lock, journal and outbox transaction boundary. */
public final class RefundTransitionService {
    public record Result(Payment payment,Refund refund,Stage2TelemetryPort.Context context,boolean changed) {}
    private final PaymentTransitionPort tx;private final LoadRefundPort refunds;private final SaveRefundPort saveRefunds;
    private final SavePaymentPort payments;private final AppendPaymentEventPort events;private final PaymentOutboxPort outbox;
    private final Stage2TelemetryPort telemetry;private final DigestPort digest;private final Clock clock;
    public RefundTransitionService(PaymentTransitionPort tx,LoadRefundPort refunds,SaveRefundPort saveRefunds,SavePaymentPort payments,AppendPaymentEventPort events,PaymentOutboxPort outbox,Stage2TelemetryPort telemetry,DigestPort digest,Clock clock){this.tx=tx;this.refunds=refunds;this.saveRefunds=saveRefunds;this.payments=payments;this.events=events;this.outbox=outbox;this.telemetry=telemetry;this.digest=digest;this.clock=clock;}
    public Result process(ProviderInboxPort.Fact fact){
        var e=fact.envelope();var identity=tx.resolveRefund(e.accountId(),e.objectId()).orElseThrow(()->new IllegalStateException("UNRESOLVED_OBJECT"));
        telemetry.requireContext(identity.payment().context());var p=tx.lock(identity.payment());
        var stored=refunds.findByIdForUpdate(p.siteId(),identity.refundId()).orElseThrow(()->new IllegalStateException("UNRESOLVED_OBJECT"));
        var r=stored.value();
        if(!p.paymentAccountId().equals(e.accountId())||!r.paymentId().equals(p.id())||!e.objectId().equals(r.providerRefundReference())||e.payload().containsKey("payment_id")&&!p.id().value().toString().equals(e.payload().get("payment_id"))||e.payload().containsKey("refund_id")&&!r.id().value().toString().equals(e.payload().get("refund_id")))throw new IllegalStateException("IDENTITY_MISMATCH");
        var target=switch(e.type()){case REFUND_REQUESTED -> r.status();case REFUND_PROCESSING -> RefundStatus.PROCESSING;case REFUND_SUCCEEDED -> RefundStatus.SUCCEEDED;case REFUND_FAILED -> RefundStatus.FAILED;default -> throw new IllegalStateException("UNSUPPORTED_EVENT");};
        return apply(p,stored,target,identity.payment().context(),fact.id(),PaymentEventSource.PROVIDER_EVENT,e.eventId(),e.objectId(),e.occurredAt(),e.receivedAt(),"inbox_"+fact.id(),fact.payloadHash());
    }
    public Result reconcile(PaymentTransitionService.Lease lease,RefundId id,ProviderState state,String requestId){
        return tx.transaction(()->{
            var p=tx.lock(new PaymentTransitionPort.Identity(lease.payment().siteId(),lease.payment().id(),lease.context()));tx.verifyLease(p,lease.owner(),clock.instant());
            var stored=refunds.findByIdForUpdate(p.siteId(),id).orElseThrow(PartnerPaymentRules::notFound);
            if(state==null||!stored.value().paymentId().equals(p.id())||!Objects.equals(stored.value().providerRefundReference(),state.reference()))throw PartnerPaymentRules.state();
            var target=switch(state.status()){case PENDING,CREATED,UNKNOWN -> stored.value().status();case PROCESSING -> RefundStatus.PROCESSING;case SUCCEEDED -> RefundStatus.SUCCEEDED;case FAILED,CANCELLED -> RefundStatus.FAILED;default -> throw PartnerPaymentRules.state();};
            var now=clock.instant();return apply(p,stored,target,lease.context(),UUID.randomUUID(),PaymentEventSource.RECONCILIATION,null,state.reference(),now,now,requestId,digest.sha256Hex(SafeFactJson.json(Map.of("state",state.status().name()))));
        });
    }
    public Result acknowledge(Payment p,LoadRefundPort.StoredRefund stored,ProviderResult result,Stage2TelemetryPort.Context context,String requestId){
        var r=stored.value();if(terminal(r.status()))return new Result(p,r,context,false);
        var now=max(clock.instant(),r.updatedAt());
        if(result.reference()!=null){
            if(r.providerRefundReference()!=null&&!r.providerRefundReference().equals(result.reference()))throw PartnerPaymentRules.state();
            r=r.withProviderRefundReference(result.reference(),now);
        }
        var target=switch(result.kind()){case ACCEPTED -> r.status()==RefundStatus.UNKNOWN?RefundStatus.PROCESSING:r.status();case PROCESSING -> RefundStatus.PROCESSING;case RECOVERABLE_DECLINE,TERMINAL_DECLINE,TEMPORARILY_UNAVAILABLE -> RefundStatus.FAILED;case REQUIRES_ACTION,UNKNOWN -> RefundStatus.UNKNOWN;};
        // Reference-only acknowledgement is durable but does not invent a state event/result metric.
        if(target==r.status()){if(!r.equals(stored.value()))r=saveRefunds.save(new LoadRefundPort.StoredRefund(r,stored.version())).value();return new Result(p,r,context,false);}
        return apply(p,new LoadRefundPort.StoredRefund(r,stored.version()),target,context,UUID.randomUUID(),PaymentEventSource.API,null,result.reference(),now,now,requestId,digest.sha256Hex(SafeFactJson.json(Map.of("state",target.name()))));
    }
    private Result apply(Payment p,LoadRefundPort.StoredRefund stored,RefundStatus target,Stage2TelemetryPort.Context context,UUID eventId,PaymentEventSource source,String providerEvent,String reference,Instant occurred,Instant received,String requestId,String hash){
        var r=stored.value();if(r.status()==target||terminal(r.status()))return new Result(p,r,context,false);
        var now=max(clock.instant(),max(p.updatedAt(),r.updatedAt()));
        r=switch(target){case PROCESSING -> r.processing(now);case SUCCEEDED -> r.succeeded(now);case FAILED -> r.failed(now);case UNKNOWN -> r.unknown(now);default -> throw PartnerPaymentRules.state();};
        r=saveRefunds.save(new LoadRefundPort.StoredRefund(r,stored.version())).value();
        if(target==RefundStatus.SUCCEEDED){long sum=refunds.findByPayment(p.siteId(),p.id()).stream().filter(s->s.value().status()==RefundStatus.SUCCEEDED).mapToLong(s->s.value().money().minorUnits()).reduce(0,Math::addExact);p=payments.save(p.refundSucceeded(sum,now));}
        var type=switch(target){case PROCESSING -> PaymentEventType.REFUND_PROCESSING;case SUCCEEDED -> PaymentEventType.REFUND_SUCCEEDED;case FAILED -> PaymentEventType.REFUND_FAILED;case UNKNOWN -> PaymentEventType.PROVIDER_RESULT_UNKNOWN;default -> throw PartnerPaymentRules.state();};
        var payment=p;var refund=r;
        var event=events.append(p.id(),seq->new PaymentEvent(eventId,payment.id(),seq,payment.siteId(),payment.paymentAccountId(),type,source,refund.money(),providerEvent,reference,occurred,received,requestId,null,hash,Map.of("refund_id",refund.id().value().toString(),"state",target.name())));
        outbox.snapshot(p,event,clock.instant());return new Result(p,r,context,true);
    }
    static boolean terminal(RefundStatus s){return Set.of(RefundStatus.SUCCEEDED,RefundStatus.FAILED,RefundStatus.CANCELLED).contains(s);}
    private static Instant max(Instant a,Instant b){return a.isBefore(b)?b:a;}
    public void emit(Result result){if(result.changed())telemetry.refundResult(result.context(),Stage2TelemetryPort.RefundResult.valueOf(result.refund().status().name()));}
}
