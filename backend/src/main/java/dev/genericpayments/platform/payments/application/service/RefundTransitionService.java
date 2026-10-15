package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.shared.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.ResolvedProviderRecovery;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import java.time.*;
import java.util.*;

/** Refund facts share the parent-lock, journal and outbox transaction boundary. */
public final class RefundTransitionService {
    public record Result(Payment payment,Refund refund,Stage2TelemetryPort.Context context,boolean changed) {}
    private final PaymentTransitionPort tx;private final LoadRefundPort refunds;private final SaveRefundPort saveRefunds;
    private final SavePaymentPort payments;private final AppendPaymentEventPort events;private final PaymentOutboxPort outbox;
    private final Stage2TelemetryPort telemetry;private final DigestPort digest;private final Clock clock;
    private LoadPaymentPort load;private LoadPaymentAccountPort accounts;private LoadSitePort sites;private ProviderOperationStore operations;private Clock referenceClock;
    private dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort recoveryAccounts;
    public void recoveryAccounts(dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort accounts){this.recoveryAccounts=accounts;}
    public RefundTransitionService(PaymentTransitionPort tx,LoadRefundPort refunds,SaveRefundPort saveRefunds,SavePaymentPort payments,AppendPaymentEventPort events,PaymentOutboxPort outbox,Stage2TelemetryPort telemetry,DigestPort digest,Clock clock){this.tx=tx;this.refunds=refunds;this.saveRefunds=saveRefunds;this.payments=payments;this.events=events;this.outbox=outbox;this.telemetry=telemetry;this.digest=digest;this.clock=clock;}
    public void verifiedReceipts(LoadPaymentPort load,LoadPaymentAccountPort accounts,LoadSitePort sites,ProviderOperationStore operations,Clock referenceClock) {
        this.load=Objects.requireNonNull(load);this.accounts=Objects.requireNonNull(accounts);this.sites=Objects.requireNonNull(sites);
        this.operations=Objects.requireNonNull(operations);this.referenceClock=Objects.requireNonNull(referenceClock);
    }
    /** The caller owns the live inbox claim and the same financial transaction, including completion. */
    public PaymentTransitionService.Result processVerified(ResolvedProviderReceipt resolved) {
        if(!tx.hasAmbientTransaction())throw new IllegalStateException("Verified refund requires financial transaction");
        var receipt=resolved.receipt();var proof=resolved.state().refundProof();
        if(receipt.objectType()!=ProviderReferenceReceipt.ObjectType.REFUND||resolved.refundId()==null||proof==null
                ||!resolved.paymentId().equals(proof.payment().id())||!resolved.refundId().equals(proof.refund().id())
                ||!resolved.verifiedAccount().equals(proof.account())||!resolved.verifiedPayment().equals(proof.payment())
                ||!resolved.requestFingerprint().equals(proof.requestFingerprint())||!receipt.objectId().equals(resolved.state().reference()))throw mismatch();
        var found=load.findByAccountAndId(receipt.accountId(),resolved.paymentId()).orElseThrow(RefundTransitionService::unresolved);
        var locked=load.findForAttemptStart(found.siteId(),found.id()).orElseThrow(RefundTransitionService::unresolved);var p=locked.value();
        var account=accounts.findById(p.paymentAccountId()).orElseThrow(RefundTransitionService::mismatch);
        var stored=refunds.findByIdForUpdate(p.siteId(),resolved.refundId()).orElseThrow(RefundTransitionService::unresolved);var r=stored.value();
        if(account.provider()!=PaymentProviderType.STRIPE||!account.id().equals(receipt.accountId()))throw mismatch();
        requireOperation(account,r,resolved.state());
        if(r.providerRefundReference()==null) {
            if(!Set.of(RefundStatus.PENDING,RefundStatus.UNKNOWN).contains(r.status())||locked.hasLiveLease(referenceClock.instant()))throw unresolved();
            r=r.withProviderRefundReference(receipt.objectId(),max(clock.instant(),r.updatedAt()));
            ProviderEvidenceValidator.requireRefund(account,p,r,resolved.state());
            stored=saveRefunds.save(new LoadRefundPort.StoredRefund(r,stored.version()));
        }
        ProviderEvidenceValidator.requireRefund(account,p,r,resolved.state());
        var site=sites.findById(p.siteId()).orElseThrow(RefundTransitionService::mismatch);
        var context=new Stage2TelemetryPort.Context(site.key().value(),Stage2TelemetryPort.Provider.STRIPE);telemetry.requireContext(context);
        var eventId=UUID.randomUUID();
        var result=applyVerified(p,stored,resolved.state(),context,eventId,PaymentEventSource.PROVIDER_EVENT,receipt.eventId(),receipt.occurredAt(),receipt.receivedAt(),"inbox_"+eventId);
        return new PaymentTransitionService.Result(result.payment(),p.status(),context,receipt.occurredAt(),result.changed(),result.refund().status());
    }
    private void requireOperation(PaymentAccount account,Refund r,ProviderState state) {
        var proof=state==null?null:state.refundProof();if(proof==null)throw mismatch();
        var operation=operations.find(account.id(),ProviderOperationKeys.refund(r.id())).orElseThrow(RefundTransitionService::unresolved);
        if(operation.status()!=ProviderOperation.Status.ACKNOWLEDGED||operation.kind()!=ProviderOperation.Kind.REFUND_CREATE
                ||!operation.accountId().equals(account.id())||!operation.resourceId().equals(r.id().value())
                ||!operation.operationKey().equals(ProviderOperationKeys.refund(r.id()))||!operation.operationKey().equals(r.providerOperationKey())
                ||!operation.externalAccountId().equals(account.externalAccountId())||!operation.credentialsRef().equals(account.credentialsRef())
                ||!operation.fingerprint().equals(proof.requestFingerprint())||!Objects.equals(operation.providerReference(),state.reference()))throw mismatch();
    }
    private Result applyVerified(Payment p,LoadRefundPort.StoredRefund stored,ProviderState state,Stage2TelemetryPort.Context context,UUID eventId,PaymentEventSource source,String providerEvent,Instant occurred,Instant received,String requestId) {
        var target=switch(state.status()) {
            case PROCESSING -> RefundStatus.PROCESSING;
            case SUCCEEDED -> RefundStatus.SUCCEEDED;
            case FAILED,CANCELLED -> RefundStatus.FAILED;
            case UNKNOWN -> stored.value().status();
            default -> throw unresolved();
        };
        return apply(p,stored,target,context,eventId,source,providerEvent,state.reference(),occurred,received,requestId,
            digest.sha256Hex(SafeFactJson.json(Map.of("state",state.status().name(),"reference",state.reference(),"charge",state.evidence().chargeReference()))));
    }
    private static IllegalStateException mismatch(){return new IllegalStateException("IDENTITY_MISMATCH");}
    private static IllegalStateException unresolved(){return new IllegalStateException("UNRESOLVED_OBJECT");}
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
        if(lease.account().provider()==PaymentProviderType.STRIPE)return reconcileVerified(lease,id,state,requestId);
        return tx.transaction(()->{
            var p=tx.lock(new PaymentTransitionPort.Identity(lease.payment().siteId(),lease.payment().id(),lease.context()));tx.verifyLease(p,lease.owner(),clock.instant());
            var stored=refunds.findByIdForUpdate(p.siteId(),id).orElseThrow(PartnerPaymentRules::notFound);
            if(state==null||!stored.value().paymentId().equals(p.id())||!Objects.equals(stored.value().providerRefundReference(),state.reference()))throw PartnerPaymentRules.state();
            var target=switch(state.status()){case PENDING,CREATED,UNKNOWN -> stored.value().status();case PROCESSING -> RefundStatus.PROCESSING;case SUCCEEDED -> RefundStatus.SUCCEEDED;case FAILED,CANCELLED -> RefundStatus.FAILED;default -> throw PartnerPaymentRules.state();};
            var now=clock.instant();return apply(p,stored,target,lease.context(),UUID.randomUUID(),PaymentEventSource.RECONCILIATION,null,state.reference(),now,now,requestId,digest.sha256Hex(SafeFactJson.json(Map.of("state",state.status().name()))));
        });
    }
    private Result reconcileVerified(PaymentTransitionService.Lease lease,RefundId id,ProviderState state,String requestId) {
        return tx.transaction(()->{
            var p=load.findByIdForUpdate(lease.payment().siteId(),lease.payment().id()).orElseThrow(PartnerPaymentRules::notFound);
            tx.verifyLease(p,lease.owner(),referenceClock.instant());
            var account=accounts.findById(p.paymentAccountId()).orElseThrow(RefundTransitionService::mismatch);
            if(!account.equals(lease.account())||!ProviderEvidenceValidator.samePayment(p,lease.payment()))throw mismatch();
            var stored=refunds.findByIdForUpdate(p.siteId(),id).orElseThrow(PartnerPaymentRules::notFound);
            requireOperation(account,stored.value(),state);ProviderEvidenceValidator.requireRefund(account,p,stored.value(),state);
            telemetry.requireContext(lease.context());var now=clock.instant();
            var result=applyVerified(p,stored,state,lease.context(),UUID.randomUUID(),PaymentEventSource.RECONCILIATION,null,now,now,requestId);
            // Expiry while journal/outbox writes wait must roll back every financial fact and the release.
            tx.verifyLease(p,lease.owner(),referenceClock.instant());
            if(!load.releaseReconciliationLease(p.siteId(),p.id(),lease.owner()))throw PartnerPaymentRules.state();
            return result;
        });
    }
    public PaymentTransitionService.Result reconcileRecovered(PaymentTransitionService.Lease lease,ResolvedProviderRecovery proof,String requestId) {
        return tx.transaction(()->{
            // Match manual recovery's Account -> resource lock order before holding Payment.
            var account=recoveryAccounts.findByIdForUpdate(lease.payment().paymentAccountId()).orElseThrow(RefundTransitionService::mismatch);
            var p=load.findByIdForUpdate(lease.payment().siteId(),lease.payment().id()).orElseThrow(PartnerPaymentRules::notFound);
            tx.verifyLease(p,lease.owner(),referenceClock.instant());tx.requireProvider(p.paymentAccountId(),PaymentProviderType.STRIPE);
            if(!account.equals(lease.account())||!account.equals(proof.verifiedAccount())||!ProviderEvidenceValidator.samePayment(p,lease.payment())||!ProviderEvidenceValidator.samePayment(p,proof.verifiedPayment()))throw mismatch();
            var id=new RefundId(proof.operation().resourceId());var stored=refunds.findByIdForUpdate(p.siteId(),id).orElseThrow(RefundTransitionService::unresolved);
            var operation=operations.find(account.id(),ProviderOperationKeys.refund(id)).orElseThrow(RefundTransitionService::unresolved);
            if(!operation.equals(proof.operation()))throw mismatch();
            requireOperation(account,stored.value(),proof.state());
            var r=stored.value();
            if(r.providerRefundReference()==null)stored=saveRefunds.save(new LoadRefundPort.StoredRefund(r.withProviderRefundReference(proof.state().reference(),max(clock.instant(),r.updatedAt())),stored.version()));
            ProviderEvidenceValidator.requireRefund(account,p,stored.value(),proof.state());
            var now=clock.instant();var result=applyVerified(p,stored,proof.state(),lease.context(),UUID.randomUUID(),PaymentEventSource.RECONCILIATION,null,now,now,requestId);
            tx.verifyLease(p,lease.owner(),referenceClock.instant());
            if(!load.releaseReconciliationLease(p.siteId(),p.id(),lease.owner()))throw PartnerPaymentRules.state();
            return new PaymentTransitionService.Result(result.payment(),p.status(),lease.context(),now,result.changed(),result.refund().status());
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
