package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.payments.application.port.in.RecordSubscriptionCashUseCase;
import dev.genericpayments.platform.payments.domain.SubscriptionBillingContext;
import dev.genericpayments.platform.catalog.domain.Money;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class SubscriptionFactService implements ApplySubscriptionSnapshotUseCase {
    private final SubscriptionStore subscriptions;
    private final SubscriptionOperationStore operations;
    private final TrialEligibilityStore trials;
    private final LockSitePort sites;
    private final CustomerStore customers;
    private final LockPaymentAccountPort accounts;
    private final BillingRecordStore bills;
    private final SubscriptionCashPort cash;
    private final SubscriptionEventStore events;
    private final SubscriptionCatalogPort catalog;
    private final Clock clock;
    private final SubscriptionCallbackPort callbacks;
    private final SubscriptionObjectPort objects;
    private final BillingMetadataPort metadata;private final SubscriptionPolicyPort policies;
    private final SubscriptionObservationPort observations;
    private final dev.genericpayments.platform.shared.application.port.out.DigestPort digest;
    private final SubscriptionQuoteStore quotes;
    private final SubscriptionSweepPort sweep;
    private final SubscriptionReadPreparation reads;
    private final SubscriptionAuthorityPort authority;
    public SubscriptionFactService(SubscriptionStore subscriptions,SubscriptionOperationStore operations,TrialEligibilityStore trials,
        LockSitePort sites,CustomerStore customers,LockPaymentAccountPort accounts,BillingRecordStore bills,
        SubscriptionCashPort cash,SubscriptionEventStore events,SubscriptionCatalogPort catalog,Clock clock,SubscriptionCallbackPort callbacks,SubscriptionObjectPort objects,BillingMetadataPort metadata,SubscriptionPolicyPort policies,SubscriptionObservationPort observations,dev.genericpayments.platform.shared.application.port.out.DigestPort digest,SubscriptionQuoteStore quotes,SubscriptionSweepPort sweep,SubscriptionReadPreparation reads,SubscriptionAuthorityPort authority){
        this.subscriptions=subscriptions;this.operations=operations;this.trials=trials;this.sites=sites;this.customers=customers;
        this.accounts=accounts;this.bills=bills;this.cash=cash;this.events=events;this.catalog=catalog;this.clock=clock;this.callbacks=callbacks;this.objects=objects;
        this.metadata=metadata;this.policies=policies;
        this.observations=observations;
        this.digest=digest;
        this.quotes=quotes;
        this.sweep=sweep;this.reads=reads;this.authority=authority;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void apply(Command command) {
        var candidate=subscriptions.findOwned(command.account().id(),command.subscriptionId()).orElseThrow();
        var eligibility=trials.find(candidate.siteId(),candidate.customerId(),candidate.productId());
        var operationIds=new TreeSet<UUID>();
        operations.unresolved(candidate.siteId(),candidate.id()).forEach(o->operationIds.add(o.id()));
        // A failed CREATE may still own a reservation. Status is not ownership or release proof.
        eligibility.filter(e->e.consumedAt()==null&&e.reservedByOperation()!=null)
            .flatMap(e->operations.find(candidate.siteId(),candidate.id(),e.reservedByOperation()))
            .ifPresent(o->operationIds.add(o.id()));
        var locked=new ArrayList<SubscriptionOperation>();
        for(var id:operationIds)locked.add(operations.lock(id));
        if(command.fence()!=null&&command.fence().authority()!=null)authority.revalidate(command.fence().authority(),true);
        sites.findByIdForUpdate(candidate.siteId()).orElseThrow();
        customers.findByIdForUpdate(candidate.siteId(),candidate.customerId()).orElseThrow();
        var account=accounts.findByIdForUpdate(candidate.paymentAccountId()).orElseThrow();
        if(account.provider()!=command.account().provider())throw new IllegalArgumentException("Account identity mismatch");
        var original=subscriptions.lock(candidate.siteId(),candidate.id());
        var selected=reads.financial(original);
        if(command.fence()!=null){
            var fence=command.fence();
            if(original.revision()!=fence.revision()||!Objects.equals(fence.operationId(),selected==null?null:selected.id())
                ||!Objects.equals(fence.operationVersion(),selected==null?null:selected.version()))throw new IllegalStateException("OBSERVATION_STALE");
            if(fence.lease()!=null)sweep.requireCurrent(fence.lease(),clock.instant());
        }
        var snapshot=command.snapshot();var now=clock.instant();
        boolean unversioned=account.provider()==dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.STRIPE;
        if(unversioned){
            var fence=command.fence();
            if(snapshot.providerRevision()!=0||fence==null||fence.observationSequence()==null||fence.observationSequence()<0
                ||(fence.operationId()==null)!=(fence.operationVersion()==null))throw new IllegalStateException("OBSERVATION_STALE");
            observations.consumeFence(original,fence.observationSequence());
        }
        boolean current=unversioned||observations.acceptRevision(original,snapshot.providerRevision());
        if(original.providerReference()!=null&&!original.providerReference().equals(snapshot.subscriptionReference()))throw new IllegalArgumentException("Subscription identity mismatch");
        validateTerms(original,snapshot.billingPrice());
        objects.bind(account.provider(),original,SubscriptionObjectPort.Kind.SUBSCRIPTION,snapshot.subscriptionReference(),null,now);
        for(var operation:locked)if(!operation.siteId().equals(original.siteId())||!operation.customerId().equals(original.customerId())||!original.id().equals(operation.subscriptionId()))throw new IllegalArgumentException("Operation ownership mismatch");
        // The first read only selected lock candidates. Customer/lifecycle locks now protect
        // this authoritative ownership decision; never grant using a stale reservation.
        var currentEligibility=trials.find(original.siteId(),original.customerId(),original.productId());
        SubscriptionOperation trialOwner=currentEligibility.filter(e->e.consumedAt()==null).map(e->locked.stream().filter(o->o.id().equals(e.reservedByOperation())).findFirst().orElse(null)).orElse(null);
        if(trialOwner!=null&&(trialOwner.kind()!=SubscriptionOperation.Kind.CREATE||!CreateSubscriptionFingerprint.startsTrial(trialOwner.fingerprint())))throw new IllegalArgumentException("Invalid trial owner");
        var projected=original;
        if(current&&original.providerReference()==null)projected=copy(original,snapshot.subscriptionReference(),original.revision()+1,now);
        SubscriptionEvent.Type change=null;UUID changedBill=null;
        var confirmedPlanOperations=new HashSet<UUID>();
        if(current&&projected.nonTerminal())for(var operation:locked){
            if(operation.kind()!=SubscriptionOperation.Kind.CHANGE&&operation.kind()!=SubscriptionOperation.Kind.CLEAR_CHANGE)continue;
            var observation=snapshot.operationObservation();var pending=projected.pendingChange();
            var intent=quotes.forOperation(original.siteId(),original.id(),operation.id()).orElse(null);
            if(intent==null||pending==null||observation==null||!observation.operationKey().equals(operation.providerOperationKey()))continue;
            if(!pending.target().equals(intent.target())||!pending.effectiveAt().equals(intent.effectiveAt()))continue;
            if(operation.kind()==SubscriptionOperation.Kind.CHANGE&&pending.operationId().equals(operation.id())){
                if(observation.state()==OperationState.DECLINED){projected=SubscriptionPlanTransaction.withChange(projected,null,now);change=SubscriptionEvent.Type.CHANGE_CLEARED;}
                else if(observation.state()==OperationState.APPLIED&&operation.providerOperationKey().equals(snapshot.operationKey())&&intent.timing()==ChangeTiming.NEXT_PERIOD){
                    if(pending.state()!=Subscription.ChangeState.SCHEDULED){projected=SubscriptionPlanTransaction.withChange(projected,new Subscription.Change(operation.id(),intent.target(),intent.effectiveAt(),Subscription.ChangeState.SCHEDULED),now);change=SubscriptionEvent.Type.CHANGE_SCHEDULED;}
                    confirmedPlanOperations.add(operation.id());
                }
            }else if(operation.kind()==SubscriptionOperation.Kind.CLEAR_CHANGE&&observation.state()==OperationState.APPLIED&&operation.providerOperationKey().equals(snapshot.operationKey())){
                projected=SubscriptionPlanTransaction.withChange(projected,null,now);change=SubscriptionEvent.Type.CHANGE_CLEARED;confirmedPlanOperations.add(operation.id());
            }
        }
        // Cash belongs to history even after terminal lifecycle. Never synthesize cash from invoice totals.
        for(var invoice:snapshot.invoices()) {
            validateTerms(original,invoice.price());if(invoice.previousPrice()!=null)validateTerms(original,invoice.previousPrice());
            var settlement=switch(invoice.settlement()){case OPEN->BillingRecord.Settlement.OPEN;case CASH->BillingRecord.Settlement.CONFIRMED_CASH;case ZERO->BillingRecord.Settlement.ZERO_AMOUNT;case UNSUPPORTED->BillingRecord.Settlement.UNSUPPORTED;case VOID->BillingRecord.Settlement.VOID;};
            var previousBill=bills.find(original.siteId(),original.id(),account.id(),invoice.reference());
            var bill=bills.upsert(new BillingRecord(previousBill.map(BillingRecord::id).orElseGet(UUID::randomUUID),original.siteId(),original.id(),account.id(),invoice.reference(),invoice.kind(),invoice.price(),invoice.previousPrice(),invoice.period(),invoice.amountDueMinor(),invoice.currency(),settlement,previousBill.map(BillingRecord::version).orElse(0L),previousBill.map(BillingRecord::createdAt).orElse(now),now));
            objects.bind(account.provider(),original,SubscriptionObjectPort.Kind.INVOICE,invoice.reference(),bill.id(),now);
            for(var attempt:invoice.cashObjects()) {
                var terms=bill.billedPrice();var previous=bill.previousPrice();
                var context=new SubscriptionBillingContext(original.id(),bill.id(),SubscriptionBillingContext.Kind.valueOf(bill.kind().name()),original.productId(),terms.priceId(),terms.version(),previous==null?null:previous.priceId(),previous==null?null:previous.version(),bill.period().start(),bill.period().end(),bill.providerInvoiceReference());
                cash.record(new RecordSubscriptionCashUseCase.Command(original.siteId(),original.customerId().value(),account.id(),account.provider(),context,attempt.reference(),new Money(attempt.amountMinor(),attempt.currency()),terms.taxMode(),attempt.status(),attempt.occurredAt(),"cash:"+digest.sha256Hex(attempt.reference()+"\n"+attempt.status())));
            }
            if(!current)continue;
            boolean freeTrial=invoice.settlement()==SettlementKind.ZERO&&invoice.kind()==BillingRecord.Kind.INITIAL&&snapshot.trial()!=null&&invoice.period().equals(snapshot.trial())&&invoice.price().equals(snapshot.billingPrice())&&(trialOwner!=null||original.trial()!=null);
            boolean zeroChange=invoice.settlement()==SettlementKind.ZERO&&(confirmedProration(original,invoice,snapshot,locked,now)
                ||events.hasBillingFact(original.siteId(),original.id(),bill.id(),SubscriptionEvent.Type.CHANGE_APPLIED));
            if(invoice.settlement()==SettlementKind.ZERO&&!freeTrial&&!zeroChange||invoice.settlement()==SettlementKind.UNSUPPORTED) {
                append(projected,command,SubscriptionEvent.Type.RECONCILIATION_REQUIRED,bill.id(),"billing:"+bill.id()+":unsupported",now);
            } else if((invoice.settlement()==SettlementKind.CASH||zeroChange)&&snapshot.status()==Subscription.Status.ACTIVE&&invoice.price().equals(snapshot.billingPrice())&&invoice.period().equals(snapshot.period())
                &&(invoice.kind()!=BillingRecord.Kind.PRORATION||confirmedProration(original,invoice,snapshot,locked,now)||invoice.price().equals(original.entitledPrice())&&invoice.price().equals(original.billingPrice()))) {
                var paid=projected.confirmPaid(invoice.price(),invoice.period(),now);
                if(paid!=projected){projected=paid;change=zeroChange?SubscriptionEvent.Type.CHANGE_APPLIED:SubscriptionEvent.Type.PERIOD_PAID;changedBill=bill.id();}
            } else if(invoice.settlement()==SettlementKind.OPEN&&projected.nonTerminal()
                &&(snapshot.status()==Subscription.Status.PAST_DUE||snapshot.status()==Subscription.Status.UNPAID||snapshot.status()==Subscription.Status.INCOMPLETE)
                &&invoice.period().equals(snapshot.period())&&invoice.price().equals(snapshot.billingPrice())) {
                var policy=policies.load(original.siteId());
                var deadline=projected.paidThrough()!=null&&projected.entitledPrice()!=null?projected.paidThrough().plus(Duration.ofDays(policy.graceDays())):null;
                var captured=metadata.captureOnce(bill,policy.version(),deadline);
                var debt=projected.renewalFailed(bill.id(),bill.period(),policy.graceDays(),now);
                if(debt!=projected){projected=withDebt(debt,invoice.price(),new Subscription.Debt(bill.id(),bill.period(),captured.graceDeadline()));change=SubscriptionEvent.Type.RENEWAL_FAILED;changedBill=bill.id();}
            }
        }
        if(!current)return;
        if(snapshot.trial()!=null&&original.trial()==null&&original.paidThrough()==null&&!now.isBefore(snapshot.trial().start())
            &&original.billingPrice().trialPolicy()==dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL
            &&!snapshot.paymentMethodReady())throw new IllegalArgumentException("Required trial lacks confirmed payment method");
        if(projected.nonTerminal()&&snapshot.status()==Subscription.Status.UNPAID&&projected.debt()!=null
            &&projected.debt().unpaidPeriod().equals(snapshot.period())&&projected.billingStatus()!=Subscription.Status.UNPAID){
            projected=unpaid(projected);change=SubscriptionEvent.Type.RENEWAL_FAILED;changedBill=projected.debt().billingRecordId();
        }
        if(snapshot.trial()!=null&&trialOwner!=null&&!now.isBefore(snapshot.trial().start())&&original.trial()==null&&original.paidThrough()==null) {
            projected=projected.confirmTrial(snapshot.trial(),now);trials.consume(trialOwner.id(),now);change=SubscriptionEvent.Type.TRIAL_STARTED;
        }
        if(snapshot.status()==Subscription.Status.CANCELLED&&projected.nonTerminal()) {
            projected=projected.cancel("PROVIDER_CANCELLED",now);change=SubscriptionEvent.Type.CANCELLED;
            if(trialOwner!=null&&original.trial()==null&&snapshot.trial()==null)trials.releaseConfirmedFailure(trialOwner.id());
        } else if(snapshot.status()==Subscription.Status.TRIALING&&snapshot.trial()!=null&&projected.nonTerminal()&&!now.isBefore(snapshot.trial().start())&&projected.trial()==null&&projected.paidThrough()==null) {
            if(trialOwner==null)throw new IllegalArgumentException("Trial has no owned reservation");
            projected=projected.confirmTrial(snapshot.trial(),now);trials.consume(trialOwner.id(),now);change=SubscriptionEvent.Type.TRIAL_STARTED;
        }
        if(projected.nonTerminal()&&snapshot.status()==Subscription.Status.PAUSED){var paused=projected.pause(now);if(paused!=projected){projected=paused;change=SubscriptionEvent.Type.PAUSED;}}
        var confirmedCancellationOperations=new HashSet<UUID>();
        for(var operation:locked){
            var observation=snapshot.operationObservation();
            if(observation==null||observation.state()!=OperationState.APPLIED||!operation.providerOperationKey().equals(observation.operationKey())
                ||!operation.providerOperationKey().equals(snapshot.operationKey()))continue;
            if(operation.kind()==SubscriptionOperation.Kind.CANCEL){
                if(SubscriptionCancellationTransaction.immediate(operation)&&snapshot.status()==Subscription.Status.CANCELLED)confirmedCancellationOperations.add(operation.id());
                else if(SubscriptionCancellationTransaction.boundary(operation).filter(b->b.equals(snapshot.cancellationAt())).isPresent()
                    &&snapshot.status()!=Subscription.Status.CANCELLED&&projected.nonTerminal()&&projected.pendingChange()==null){
                    if(projected.cancellation()==null&&now.isBefore(snapshot.cancellationAt())){projected=projected.scheduleCancellation(snapshot.cancellationAt(),now);change=SubscriptionEvent.Type.CANCEL_SCHEDULED;}
                    if(projected.cancellation()!=null&&projected.cancellation().effectiveAt().equals(snapshot.cancellationAt()))confirmedCancellationOperations.add(operation.id());
                }else if(SubscriptionCancellationTransaction.boundary(operation).filter(b->!now.isBefore(b)).isPresent()&&snapshot.status()==Subscription.Status.CANCELLED)confirmedCancellationOperations.add(operation.id());
            }else if(operation.kind()==SubscriptionOperation.Kind.WITHDRAW_CANCEL&&snapshot.cancellationAt()==null&&snapshot.status()!=Subscription.Status.CANCELLED&&projected.nonTerminal()
                &&SubscriptionCancellationTransaction.boundary(operation).filter(b->original.cancellation()!=null&&b.equals(original.cancellation().effectiveAt())).isPresent()){
                // Provider proves this original withdrawal was applied before its deadline; delivery may be delayed.
                projected=projected.withdrawCancellation(operation.createdAt());change=SubscriptionEvent.Type.CANCEL_WITHDRAWN;confirmedCancellationOperations.add(operation.id());
            }
        }
        // Preserve externally confirmed scheduling, but never let it substitute for an unresolved
        // owned command's exact proof, nor extend to an unpaid provider period or grace deadline.
        if(projected.nonTerminal()&&projected.pendingChange()==null&&projected.cancellation()==null&&snapshot.cancellationAt()!=null
            &&snapshot.cancellationAt().equals(SubscriptionCancellationTransaction.liveBoundary(projected,now))
            &&locked.stream().noneMatch(o->o.kind()==SubscriptionOperation.Kind.CANCEL||o.kind()==SubscriptionOperation.Kind.WITHDRAW_CANCEL)){
            projected=projected.scheduleCancellation(snapshot.cancellationAt(),now);change=SubscriptionEvent.Type.CANCEL_SCHEDULED;
        }
        if(projected!=original) {
            projected=subscriptions.save(copy(projected,projected.providerReference(),original.revision()+1,now),original.revision());
            if(change!=null)append(projected,command,change,changedBill,"projection:"+projected.revision(),now);
        }
        for(var operation:locked) {
            if(operation.status()==SubscriptionOperation.Status.FAILED||operation.status()==SubscriptionOperation.Status.SUCCEEDED)continue;
            var observation=snapshot.operationObservation();
            if(observation==null||!operation.providerOperationKey().equals(observation.operationKey()))continue;
            // An APPLIED marker is not generic proof from an unrelated historical paid period.
            // Later command tasks add their own exact, owned confirmation predicates.
            boolean proof=switch(operation.kind()) {
                case CREATE -> projected.trial()!=null||projected.paidThrough()!=null;
                case SETUP -> snapshot.paymentMethodReady();
                case CHANGE -> confirmedPlanOperations.contains(operation.id())||original.pendingChange()!=null&&original.pendingChange().operationId().equals(operation.id())
                    &&projected.pendingChange()==null&&original.pendingChange().target().equals(projected.entitledPrice())
                    &&operation.providerOperationKey().equals(snapshot.operationKey());
                case CLEAR_CHANGE -> confirmedPlanOperations.contains(operation.id());
                case CANCEL,WITHDRAW_CANCEL -> confirmedCancellationOperations.contains(operation.id());
                case RECOVER -> operation.providerOperationKey().equals(snapshot.operationKey())
                    &&SubscriptionRecoveryTransaction.recoveryBill(operation).map(id->snapshot.invoices().stream().anyMatch(i->
                        i.settlement()==SettlementKind.CASH&&bills.find(original.siteId(),original.id(),account.id(),i.reference()).map(b->b.id().equals(id)).orElse(false))).orElse(false);
                case RESUME -> SubscriptionRecoveryTransaction.resumeFingerprint(operation)&&operation.providerOperationKey().equals(snapshot.operationKey())
                    &&snapshot.status()==Subscription.Status.ACTIVE&&snapshot.invoices().stream().anyMatch(i->i.kind()==BillingRecord.Kind.INITIAL
                        &&i.settlement()==SettlementKind.CASH&&i.period().equals(snapshot.period())&&!i.period().start().isBefore(operation.createdAt())&&i.price().equals(snapshot.billingPrice()));
                default -> false;
            };
            var status=observation.state()==OperationState.DECLINED?SubscriptionOperation.Status.FAILED:
                observation.state()==OperationState.APPLIED&&proof?SubscriptionOperation.Status.SUCCEEDED:null;
            if(status!=null)operations.save(new SubscriptionOperation(operation.id(),operation.siteId(),operation.customerId(),operation.subscriptionId(),operation.kind(),status,operation.authorityScope(),operation.keyDigest(),operation.fingerprint(),operation.providerOperationKey(),operation.expectedRevision(),operation.policyVersion(),snapshot.subscriptionReference(),status==SubscriptionOperation.Status.FAILED?"PROVIDER_DECLINED":null,operation.createdAt(),now,operation.version()+1),operation.version());
        }
    }
    private boolean confirmedProration(Subscription s,Invoice invoice,Snapshot snapshot,List<SubscriptionOperation> locked,Instant now){
        var change=s.pendingChange();var observation=snapshot.operationObservation();
        if(invoice.kind()!=BillingRecord.Kind.PRORATION||change==null||s.paidThrough()==null||observation==null
            ||observation.state()!=OperationState.APPLIED||!invoice.price().equals(change.target())||!Objects.equals(invoice.previousPrice(),s.entitledPrice())
            ||!invoice.period().end().equals(s.paidThrough())||!invoice.period().start().equals(change.effectiveAt())||now.isBefore(change.effectiveAt()))return false;
        return locked.stream().anyMatch(o->o.id().equals(change.operationId())&&o.kind()==SubscriptionOperation.Kind.CHANGE
            &&o.providerOperationKey().equals(observation.operationKey())&&o.providerOperationKey().equals(snapshot.operationKey())
            &&quotes.forOperation(s.siteId(),s.id(),o.id()).map(q->q.timing()==ChangeTiming.IMMEDIATE&&q.target().equals(invoice.price())
                &&q.fromPriceId().equals(invoice.previousPrice().priceId())&&q.fromPriceVersion()==invoice.previousPrice().version()
                &&q.dueNowMinor()==invoice.amountDueMinor()&&q.currency().equals(invoice.currency())&&q.effectiveAt().equals(invoice.period().start()))
                .orElse(false));
    }
    private void validateTerms(Subscription s,SubscriptionPriceTerms terms){if(!s.productId().equals(terms.productId())||!catalog.terms(s.siteId(),terms.priceId()).equals(terms))throw new IllegalArgumentException("Invoice terms mismatch");}
    private void append(Subscription s,Command c,SubscriptionEvent.Type type,UUID bill,String key,Instant now){
        var event=new SubscriptionEvent(UUID.randomUUID(),s.siteId(),s.id(),s.paymentAccountId(),0,s.revision(),type,c.source(),now,now,null,bill,null,s.providerReference(),key,c.requestId());
        if(events.append(event))callbacks.snapshot(s,event,new SubscriptionAccessPolicy().evaluate(s,now),now);
    }
    private static Subscription copy(Subscription s,String reference,long revision,Instant now){return new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),reference,s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),s.debt(),s.terminalReason(),revision,s.createdAt(),now,s.lastReconciledAt());}
    private static Subscription withDebt(Subscription s,SubscriptionPriceTerms billing,Subscription.Debt debt){return new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),billing,s.entitledPrice(),s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),debt,s.terminalReason(),s.revision(),s.createdAt(),s.updatedAt(),s.lastReconciledAt());}
    private static Subscription unpaid(Subscription s){return new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),Subscription.Status.UNPAID,s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),s.debt(),s.terminalReason(),s.revision(),s.createdAt(),s.updatedAt(),s.lastReconciledAt());}
}
