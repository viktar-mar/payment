package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import dev.genericpayments.platform.shared.application.port.out.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import java.time.*;
import java.util.*;

/** Single normalized policy, executed by short adapter-owned transactions. */
public final class PaymentTransitionService {
    public record Lease(Payment payment,PaymentAccount account,String owner,Stage2TelemetryPort.Context context) {}
    public record Result(Payment payment,PaymentStatus previous,Stage2TelemetryPort.Context context,Instant occurredAt,boolean changed,RefundStatus refundStatus) {
        public Result(Payment p,PaymentStatus previous,Stage2TelemetryPort.Context context,Instant at,boolean changed){this(p,previous,context,at,changed,null);}
    }
    private RefundTransitionService refundTransitions;
    public void refunds(RefundTransitionService transitions){this.refundTransitions=Objects.requireNonNull(transitions);}
    private ProviderOperationStore operations;
    private ClientPaymentAttemptPort clientAttempts;
    private ProviderChargeReceiptPort charges;
    private Clock referenceClock;
    public void verifiedReceipts(ProviderOperationStore operations,ClientPaymentAttemptPort clientAttempts,ProviderChargeReceiptPort charges,Clock referenceClock) {
        this.operations=Objects.requireNonNull(operations);this.clientAttempts=Objects.requireNonNull(clientAttempts);
        this.charges=Objects.requireNonNull(charges);this.referenceClock=Objects.requireNonNull(referenceClock);
    }
    private final PaymentTransitionPort tx;private final ProviderInboxPort inbox;private final LoadPaymentPort load;private final SavePaymentPort save;
    private final LoadPaymentAttemptPort attempts;private final SavePaymentAttemptPort saveAttempts;private final AppendPaymentEventPort events;private final PaymentOutboxPort outbox;
    private final LoadSitePort sites;private final LoadPaymentAccountPort accounts;private final Stage2TelemetryPort telemetry;private final DigestPort digest;private final Clock clock;
    public PaymentTransitionService(PaymentTransitionPort tx,ProviderInboxPort inbox,LoadPaymentPort load,SavePaymentPort save,LoadPaymentAttemptPort attempts,SavePaymentAttemptPort saveAttempts,AppendPaymentEventPort events,PaymentOutboxPort outbox,LoadSitePort sites,LoadPaymentAccountPort accounts,Stage2TelemetryPort telemetry,DigestPort digest,Clock clock){this.tx=tx;this.inbox=inbox;this.load=load;this.save=save;this.attempts=attempts;this.saveAttempts=saveAttempts;this.events=events;this.outbox=outbox;this.sites=sites;this.accounts=accounts;this.telemetry=telemetry;this.digest=digest;this.clock=clock;}
    public void requireNoAmbientTransaction(){
        if(tx.hasAmbientTransaction())throw new IllegalStateException("Provider coordinator requires no ambient transaction");
    }
    public Result process(ProviderInboxPort.Claim claim) {
        return tx.transaction(() -> {
            var fact=inbox.lock(claim,clock.instant());var e=fact.envelope();tx.requireProvider(e.accountId(),e.provider());
            if(e.provider()==PaymentProviderType.STRIPE)throw new IllegalStateException("UNSUPPORTED_EVENT");
            if(Set.of(PaymentEventType.REFUND_REQUESTED,PaymentEventType.REFUND_PROCESSING,PaymentEventType.REFUND_SUCCEEDED,PaymentEventType.REFUND_FAILED).contains(e.type())){
                var result=refundTransitions.process(fact);inbox.complete(claim);return new Result(result.payment(),result.payment().status(),result.context(),e.occurredAt(),result.changed(),result.refund().status());
            }
            var identity=tx.resolve(e.accountId(),e.objectId()).orElseThrow(() -> new IllegalStateException("UNRESOLVED_OBJECT"));
            telemetry.requireContext(identity.context());var p=tx.lock(identity);
            if(!p.paymentAccountId().equals(e.accountId())||!e.objectId().equals(p.providerPaymentReference()))throw new IllegalStateException("IDENTITY_MISMATCH");
            var target=switch(e.type()){case PAYMENT_METHOD_REQUIRED -> ProviderState.Status.REQUIRES_PAYMENT_METHOD;case CUSTOMER_ACTION_REQUIRED -> ProviderState.Status.REQUIRES_ACTION;case PAYMENT_PROCESSING -> ProviderState.Status.PROCESSING;case PAYMENT_SUCCEEDED -> ProviderState.Status.SUCCEEDED;case PAYMENT_FAILED -> ProviderState.Status.FAILED;case PAYMENT_CANCELLED -> ProviderState.Status.CANCELLED;default -> throw new IllegalStateException("UNSUPPORTED_EVENT");};
            var result=apply(p,target,identity.context(),fact.id(),PaymentEventSource.PROVIDER_EVENT,e.eventId(),e.objectId(),e.occurredAt(),e.receivedAt(),"inbox_"+fact.id(),fact.payloadHash());
            inbox.complete(claim);return result;
        });
    }
    /** Applies only retrieved proof. All provider I/O has finished before this short transaction. */
    public Result processVerified(ProviderInboxPort.Claim claim,ResolvedProviderReceipt resolved) {
        return tx.transaction(() -> {
            var receipt=inbox.lockReference(claim);
            if(resolved==null||!receipt.equals(resolved.receipt()))throw mismatch();
            if(receipt.objectType()!=ProviderReferenceReceipt.ObjectType.PAYMENT||resolved.refundId()!=null) {
                var result=refundTransitions.processVerified(resolved);
                inbox.completeReference(claim);return result;
            }
            var found=load.findByAccountAndId(receipt.accountId(),resolved.paymentId()).orElseThrow(() -> new IllegalStateException("UNRESOLVED_OBJECT"));
            var locked=load.findForAttemptStart(found.siteId(),found.id()).orElseThrow(() -> new IllegalStateException("UNRESOLVED_OBJECT"));
            var p=locked.value();
            var account=accounts.findById(p.paymentAccountId()).orElseThrow(PaymentTransitionService::mismatch);
            if(account.provider()!=PaymentProviderType.STRIPE||account.status()!=PaymentAccountStatus.ACTIVE
                    ||!account.equals(resolved.verifiedAccount())||!account.id().equals(receipt.accountId())
                    ||!p.id().equals(resolved.paymentId())||!receipt.objectId().equals(resolved.state().reference())
                    ||!sameVerifiedRequest(p,resolved.verifiedPayment()))throw mismatch();
            var operation=operations.find(account.id(),ProviderOperationKeys.prepare(p.id())).orElseThrow(() -> new IllegalStateException("UNRESOLVED_OBJECT"));
            if(operation.status()!=ProviderOperation.Status.ACKNOWLEDGED||operation.kind()!=ProviderOperation.Kind.PAYMENT_PREPARE
                    ||!operation.resourceId().equals(p.id().value())||!operation.accountId().equals(account.id())
                    ||!operation.operationKey().equals(ProviderOperationKeys.prepare(p.id()))
                    ||!operation.externalAccountId().equals(account.externalAccountId())||!operation.credentialsRef().equals(account.credentialsRef())
                    ||!operation.fingerprint().equals(resolved.requestFingerprint())||!receipt.objectId().equals(operation.providerReference()))throw mismatch();
            if(p.providerPaymentReference()==null) {
                // A valid ACK does not authorize stealing an in-flight preparation/reconciliation lease.
                if(p.status()!=PaymentStatus.CREATED||locked.hasLiveLease(referenceClock.instant()))throw new IllegalStateException("UNRESOLVED_OBJECT");
                p=p.withProviderPaymentReference(receipt.objectId(),max(clock.instant(),p.updatedAt()));
                ProviderEvidenceValidator.requirePayment(account,p,resolved.state());
                p=save.save(p);
            } else if(!receipt.objectId().equals(p.providerPaymentReference()))throw mismatch();
            ProviderEvidenceValidator.requirePayment(account,p,resolved.state());
            var site=sites.findById(p.siteId()).orElseThrow(PaymentTransitionService::mismatch);
            var context=new Stage2TelemetryPort.Context(site.key().value(),Stage2TelemetryPort.Provider.STRIPE);
            telemetry.requireContext(context);
            var result=applyVerified(p,account,resolved.state(),context,claim.id(),PaymentEventSource.PROVIDER_EVENT,
                receipt.eventId(),receipt.occurredAt(),receipt.receivedAt(),"inbox_"+claim.id());
            inbox.completeReference(claim);return result;
        });
    }
    private Result applyVerified(Payment p,PaymentAccount account,ProviderState state,Stage2TelemetryPort.Context context,
            UUID eventId,PaymentEventSource source,String providerEvent,Instant occurred,Instant received,String requestId) {
        var previous=p.status();
        if(rank(previous)>=4)return new Result(p,previous,context,occurred,false);
        if(Set.of(ProviderState.Status.UNKNOWN,ProviderState.Status.CREATED,ProviderState.Status.FAILED,ProviderState.Status.PENDING).contains(state.status()))
            throw new IllegalStateException("UNRESOLVED_OBJECT");
        var pending=attempts.findClientAttemptsByPayment(p.siteId(),p.id()).stream()
            .filter(a -> !Set.of(PaymentAttemptStatus.SUCCEEDED,PaymentAttemptStatus.FAILED,PaymentAttemptStatus.CANCELLED).contains(a.value().status())).toList();
        if(pending.size()>1)throw mismatch();
        LoadPaymentAttemptPort.StoredAttempt stored=null;
        ClientPaymentAttemptPort.Baseline baseline=null;
        if(!pending.isEmpty()) {
            stored=attempts.findByIdForUpdate(p.siteId(),pending.getFirst().value().id()).orElseThrow(PaymentTransitionService::mismatch);
            var a=stored.value();baseline=clientAttempts.findByAttempt(a.id().value()).orElseThrow(PaymentTransitionService::mismatch);
            if(!a.paymentId().equals(p.id())||!Objects.equals(a.providerIntentReference(),p.providerPaymentReference())
                    ||!baseline.paymentId().equals(p.id())||!baseline.accountId().equals(account.id())
                    ||!Objects.equals(baseline.customerId(),p.customerId())||!baseline.externalAccountId().equals(account.externalAccountId())
                    ||!baseline.credentialsRef().equals(account.credentialsRef()))throw mismatch();
        }
        var now=max(clock.instant(),p.updatedAt());var target=previous;
        PaymentAttempt next=stored==null?null:stored.value();
        var at=next==null?now:max(now,next.updatedAt());
        switch(state.status()) {
            case SUCCEEDED -> {
                charges.record(account.id(),p.id(),state.evidence().chargeReference(),true,"SUCCEEDED");
                p=p.succeeded(now);target=PaymentStatus.SUCCEEDED;if(next!=null)next=next.succeeded(at);
            }
            case REQUIRES_PAYMENT_METHOD -> {
                var failed=state.evidence().failedChargeReference();
                if(next!=null&&ClientAttemptPolicy.isNewFailure(failed,baseline.initialFailedChargeReference())&&!charges.contains(account.id(),failed)) {
                    if(!charges.record(account.id(),p.id(),failed,false,"FAILED"))throw mismatch();
                    next=next.failed("PROVIDER_FAILED",at);p=p.recoverableDecline(now);target=PaymentStatus.REQUIRES_PAYMENT_METHOD;
                } else if(previous==PaymentStatus.CREATED) {p=p.requiresPaymentMethod(now);target=PaymentStatus.REQUIRES_PAYMENT_METHOD;}
            }
            case REQUIRES_ACTION -> {
                if(rank(previous)<rank(PaymentStatus.REQUIRES_ACTION)){p=p.requiresAction(now);target=PaymentStatus.REQUIRES_ACTION;}
                if(next!=null&&Set.of(PaymentAttemptStatus.CREATED,PaymentAttemptStatus.REQUIRES_PAYMENT_METHOD,PaymentAttemptStatus.UNKNOWN).contains(next.status()))next=next.requiresAction(at);
            }
            case PROCESSING -> {p=p.processing(now);target=PaymentStatus.PROCESSING;if(next!=null)next=next.processing(at);}
            case CANCELLED -> {p=p.cancelled(now);target=PaymentStatus.CANCELLED;if(next!=null)next=next.cancelled(at);}
            default -> throw new IllegalStateException("UNRESOLVED_OBJECT");
        }
        boolean attemptChanged=stored!=null&&!stored.value().equals(next);
        if(attemptChanged)saveAttempts.save(new LoadPaymentAttemptPort.StoredAttempt(next,stored.version()));
        if(target==previous&&!attemptChanged)return new Result(p,previous,context,occurred,false);
        String hash=digest.sha256Hex(SafeFactJson.json(Map.of("state",state.status().name(),"reference",state.reference(),
            "charge",Objects.toString(state.evidence().chargeReference(),""),"failed_charge",Objects.toString(state.evidence().failedChargeReference(),""))));
        return journalTransition(p,previous,context,eventId,source,providerEvent,state.reference(),occurred,received,requestId,hash);
    }
    /** Compare the exact immutable request inputs; the adapter alone owns canonical fingerprint encoding. */
    private static boolean sameVerifiedRequest(Payment current,Payment verified) {
        return current.id().equals(verified.id())&&current.siteId().equals(verified.siteId())
            &&current.paymentAccountId().equals(verified.paymentAccountId())&&current.source()==verified.source()
            &&current.money().equals(verified.money())&&current.taxMode()==verified.taxMode()
            &&Objects.equals(current.customerId(),verified.customerId());
    }
    private static IllegalStateException mismatch(){return new IllegalStateException("IDENTITY_MISMATCH");}
    private static Instant max(Instant a,Instant b){return a.isBefore(b)?b:a;}
    public Lease acquire(SiteId siteId,PaymentId id,String requestId,Duration duration) {
        SafeFactJson.text(requestId,128);return tx.transaction(() -> {
            var p=load.findByIdForUpdate(siteId,id).orElseThrow(PartnerPaymentRules::notFound);
            var site=sites.findById(siteId).filter(s -> s.status()==SiteStatus.ACTIVE&&(p.source()==PaymentSource.SUBSCRIPTION||s.paymentAccountId().equals(p.paymentAccountId()))).orElseThrow(PartnerPaymentRules::state);
            var account=accounts.findById(p.paymentAccountId()).filter(a -> a.status()==PaymentAccountStatus.ACTIVE).orElseThrow(PartnerPaymentRules::state);
            if(p.providerPaymentReference()==null)throw PartnerPaymentRules.state();
            var context=new Stage2TelemetryPort.Context(site.key().value(),Stage2TelemetryPort.Provider.valueOf(account.provider().name()));telemetry.requireContext(context);
            String owner=UUID.randomUUID().toString();if(!load.tryAcquireReconciliationLease(siteId,id,owner,clock.instant(),clock.instant().plus(duration)))throw PartnerPaymentRules.state();
            return new Lease(load.findByIdForUpdate(siteId,id).orElseThrow(),account,owner,context);
        });
    }
    public Result reconcile(Lease lease,ProviderState state,String requestId) {
        if(state==null||!lease.payment().providerPaymentReference().equals(state.reference()))throw PartnerPaymentRules.state();
        return tx.transaction(() -> {
            var p=load.findByIdForUpdate(lease.payment().siteId(),lease.payment().id()).orElseThrow(PartnerPaymentRules::notFound);
            tx.verifyLease(p,lease.owner(),clock.instant());tx.requireProvider(p.paymentAccountId(),lease.account().provider());
            if(!p.paymentAccountId().equals(lease.account().id())||!state.reference().equals(p.providerPaymentReference()))throw PartnerPaymentRules.state();
            var currentAccount=accounts.findById(p.paymentAccountId()).orElseThrow(PartnerPaymentRules::state);
            if(currentAccount.provider()==PaymentProviderType.STRIPE||lease.account().provider()==PaymentProviderType.STRIPE) {
                if(currentAccount.status()!=PaymentAccountStatus.ACTIVE||currentAccount.provider()!=lease.account().provider()
                        ||currentAccount.mode()!=lease.account().mode()||!currentAccount.externalAccountId().equals(lease.account().externalAccountId())
                        ||!currentAccount.credentialsRef().equals(lease.account().credentialsRef())||!sameVerifiedRequest(p,lease.payment()))throw PartnerPaymentRules.state();
                ProviderEvidenceValidator.requirePayment(currentAccount,p,state);
            }
            telemetry.requireContext(lease.context());Instant now=clock.instant();
            var result=currentAccount.provider()==PaymentProviderType.STRIPE
                ?applyVerified(p,currentAccount,state,lease.context(),UUID.randomUUID(),PaymentEventSource.RECONCILIATION,null,now,now,requestId)
                :apply(p,state.status(),lease.context(),UUID.randomUUID(),PaymentEventSource.RECONCILIATION,null,state.reference(),now,now,requestId,digest.sha256Hex(SafeFactJson.json(Map.of("state",state.status().name()))));
            if(currentAccount.provider()!=PaymentProviderType.STRIPE&&!result.changed()&&state.status()!=ProviderState.Status.UNKNOWN&&state.status()!=ProviderState.Status.CREATED)resolveUnknown(p,state.status(),now);
            if(!load.releaseReconciliationLease(p.siteId(),p.id(),lease.owner()))throw PartnerPaymentRules.state();
            return new Result(load.findByIdForUpdate(p.siteId(),p.id()).orElseThrow(),result.previous(),result.context(),now,result.changed());
        });
    }
    /** Caller owns the lifecycle transaction and object lock. Never opens a separate transaction. */
    public Result subscriptionCash(Payment p,PaymentStatus status,Stage2TelemetryPort.Context context,Instant occurred,String factKey) {
        if(!tx.hasAmbientTransaction())throw new IllegalStateException("Cash fact requires an ambient transaction");
        if(p.source()!=PaymentSource.SUBSCRIPTION)throw new IllegalArgumentException("Subscription payment required");
        telemetry.requireContext(context);
        var identity=SafeFactJson.json(Map.of("account",p.paymentAccountId().value().toString(),"object",p.providerPaymentReference(),"fact",factKey));
        var eventId=UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return apply(p,ProviderState.Status.valueOf(status.name()),context,eventId,PaymentEventSource.PROVIDER_EVENT,factKey,p.providerPaymentReference(),occurred,clock.instant(),"cash_"+eventId,
            digest.sha256Hex(SafeFactJson.json(Map.of("state",status.name()))));
    }
    private Result apply(Payment p,ProviderState.Status state,Stage2TelemetryPort.Context context,UUID eventId,PaymentEventSource source,String providerEvent,String reference,Instant occurred,Instant received,String requestId,String hash) {
        var previous=p.status();var target=switch(state){case CREATED,UNKNOWN -> previous;case PENDING,PROCESSING -> PaymentStatus.PROCESSING;case REQUIRES_PAYMENT_METHOD -> PaymentStatus.REQUIRES_PAYMENT_METHOD;case REQUIRES_ACTION -> PaymentStatus.REQUIRES_ACTION;case SUCCEEDED -> PaymentStatus.SUCCEEDED;case FAILED -> PaymentStatus.FAILED;case CANCELLED -> PaymentStatus.CANCELLED;};
        if(previous==target||rank(previous)>=4||rank(target)<=rank(previous))return new Result(p,previous,context,occurred,false);
        Instant now=clock.instant().isBefore(p.updatedAt())?p.updatedAt():clock.instant();
        p=switch(target){case REQUIRES_PAYMENT_METHOD -> p.requiresPaymentMethod(now);case REQUIRES_ACTION -> p.requiresAction(now);case PROCESSING -> p.processing(now);case SUCCEEDED -> p.succeeded(now);case FAILED -> p.terminalDecline(now);case CANCELLED -> p.cancelled(now);default -> p;};
        var candidates=attempts.findByPayment(p.siteId(),p.id());
        var latest=candidates.stream().filter(a -> !Set.of(PaymentAttemptStatus.SUCCEEDED,PaymentAttemptStatus.FAILED,PaymentAttemptStatus.CANCELLED).contains(a.value().status())).max(Comparator.comparingInt(a -> a.value().sequence()));
        if(latest.isPresent()) {
            var stored=attempts.findByIdForUpdate(p.siteId(),latest.get().value().id()).orElseThrow();var a=stored.value();Instant at=now.isBefore(a.updatedAt())?a.updatedAt():now;
            var next=switch(target){case REQUIRES_PAYMENT_METHOD -> a.status()==PaymentAttemptStatus.CREATED||a.status()==PaymentAttemptStatus.UNKNOWN?a.requiresPaymentMethod(at):a;case REQUIRES_ACTION -> Set.of(PaymentAttemptStatus.CREATED,PaymentAttemptStatus.REQUIRES_PAYMENT_METHOD,PaymentAttemptStatus.UNKNOWN).contains(a.status())?a.requiresAction(at):a;case PROCESSING -> a.processing(at);case SUCCEEDED -> a.succeeded(at);case FAILED -> a.failed("PROVIDER_FAILED",at);case CANCELLED -> a.cancelled(at);default -> a;};
            if(!next.equals(a))saveAttempts.save(new LoadPaymentAttemptPort.StoredAttempt(next,stored.version()));
        }
        return journalTransition(p,previous,context,eventId,source,providerEvent,reference,occurred,received,requestId,hash);
    }
    private Result journalTransition(Payment p,PaymentStatus previous,Stage2TelemetryPort.Context context,UUID eventId,PaymentEventSource source,String providerEvent,String reference,Instant occurred,Instant received,String requestId,String hash) {
        var saved=save.save(p);var target=saved.status();var type=type(target);
        var event=events.append(saved.id(),seq -> new PaymentEvent(eventId,saved.id(),seq,saved.siteId(),saved.paymentAccountId(),type,source,saved.money(),providerEvent,reference,occurred,received,requestId,null,hash,Map.of("state",target.name())));
        outbox.snapshot(saved,event,clock.instant());return new Result(saved,previous,context,occurred,true);
    }
    private static int rank(PaymentStatus state){return switch(state){case CREATED -> 0;case REQUIRES_PAYMENT_METHOD -> 1;case REQUIRES_ACTION -> 2;case PROCESSING -> 3;default -> 4;};}
    private void resolveUnknown(Payment p,ProviderState.Status state,Instant now) {
        var latest=attempts.findByPayment(p.siteId(),p.id()).stream().max(Comparator.comparingInt(a -> a.value().sequence()));
        if(latest.isEmpty()||latest.get().value().status()!=PaymentAttemptStatus.UNKNOWN)return;
        var stored=attempts.findByIdForUpdate(p.siteId(),latest.get().value().id()).orElseThrow();var a=stored.value();Instant at=now.isBefore(a.updatedAt())?a.updatedAt():now;
        // An earlier contradictory terminal provider fact must not rewrite the confirmed winner.
        if(rank(p.status())>=4&&!p.status().name().equals(state.name()))return;
        var next=switch(state){case REQUIRES_PAYMENT_METHOD -> a.requiresPaymentMethod(at);case REQUIRES_ACTION -> a.requiresAction(at);case PENDING,PROCESSING -> a.processing(at);case SUCCEEDED -> a.succeeded(at);case FAILED -> a.failed("PROVIDER_FAILED",at);case CANCELLED -> a.cancelled(at);default -> a;};
        if(!next.equals(a))saveAttempts.save(new LoadPaymentAttemptPort.StoredAttempt(next,stored.version()));
    }
    private static PaymentEventType type(PaymentStatus s){return switch(s){case REQUIRES_PAYMENT_METHOD -> PaymentEventType.PAYMENT_METHOD_REQUIRED;case REQUIRES_ACTION -> PaymentEventType.CUSTOMER_ACTION_REQUIRED;case PROCESSING -> PaymentEventType.PAYMENT_PROCESSING;case SUCCEEDED -> PaymentEventType.PAYMENT_SUCCEEDED;case FAILED -> PaymentEventType.PAYMENT_FAILED;case CANCELLED -> PaymentEventType.PAYMENT_CANCELLED;default -> throw SafeFactJson.invalid();};}
    public void emit(Result result){if(result.changed()){if(result.refundStatus()!=null)telemetry.refundResult(result.context(),Stage2TelemetryPort.RefundResult.valueOf(result.refundStatus().name()));else telemetry.paymentTransition(result.context(),Stage2TelemetryPort.PaymentState.valueOf(result.previous().name()),Stage2TelemetryPort.PaymentState.valueOf(result.payment().status().name()));}}
}
