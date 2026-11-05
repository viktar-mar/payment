package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalStore;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Explicit internal consumer. No registration, provider IO, command dispatch, or time mutation.
 * Entry points require no ambient transaction so terminal stop can follow an actual rollback. */
public final class OwnedFailedRenewalService {
    private final OwnedFailedRenewalStore store;
    private final SubscriptionTransactionPort transaction;
    private final SubscriptionOperationStore operations;
    private final SubscriptionLifecycleLockPort lifecycle;
    private final LockPaymentAccountPort accounts;
    private final SubscriptionStore subscriptions;
    private final BillingRecordStore bills;
    private final PrepareSubscriptionReadUseCase reads;
    private final SubscriptionObservationPort observations;
    private final ApplySubscriptionSnapshotUseCase facts;
    private final TrialEligibilityStore trials;
    private final SubscriptionBillingTimePort billingTime;
    private final Clock clock;

    public OwnedFailedRenewalService(OwnedFailedRenewalStore store,SubscriptionTransactionPort transaction,SubscriptionOperationStore operations,
            SubscriptionLifecycleLockPort lifecycle,LockPaymentAccountPort accounts,SubscriptionStore subscriptions,BillingRecordStore bills,
            PrepareSubscriptionReadUseCase reads,SubscriptionObservationPort observations,ApplySubscriptionSnapshotUseCase facts,
            TrialEligibilityStore trials,SubscriptionBillingTimePort billingTime,Clock clock) {
        this.store=Objects.requireNonNull(store);this.transaction=Objects.requireNonNull(transaction);this.operations=Objects.requireNonNull(operations);
        this.lifecycle=Objects.requireNonNull(lifecycle);this.accounts=Objects.requireNonNull(accounts);this.subscriptions=Objects.requireNonNull(subscriptions);
        this.bills=Objects.requireNonNull(bills);this.reads=Objects.requireNonNull(reads);this.observations=Objects.requireNonNull(observations);
        this.facts=Objects.requireNonNull(facts);this.trials=Objects.requireNonNull(trials);this.billingTime=Objects.requireNonNull(billingTime);this.clock=Objects.requireNonNull(clock);
    }
    public Admission admit(OwnedFirstRenewal.Admission upstream,OwnedFirstRenewal.LaterReady ready,OwnedFirstRenewal.Boundary boundary) {
        fresh();Objects.requireNonNull(upstream);Objects.requireNonNull(ready);Objects.requireNonNull(boundary);
        return transaction.requiresNew(()->{
            var state=lock(upstream,boundary,null);
            requireNoFinancial(state);
            if(state.subscription().revision()!=upstream.subscriptionRevision())throw unavailable();
            return store.admitInTransaction(upstream,ready,boundary,state.utcAt());
        });
    }
    public ReadClaim beginFailedRead(Admission admission,UUID invocation) {
        return guarded(admission,()->transaction.requiresNew(()->{
            var state=current(admission);requireNoFinancial(state);
            return store.claimReadInTransaction(admission,Phase.FAILED,Objects.requireNonNull(invocation),state.fence(),state.utcAt());
        }));
    }
    public Failure publishFailure(Admission admission,ReadClaim claim,FailedEvidence evidence) {
        return guarded(admission,()->transaction.requiresNew(()->{
            var state=current(admission);requireNoFinancial(state);
            requireRead(admission,claim,Phase.FAILED,state,evidence.snapshot(),evidence.acceptedAt());
            if(evidence.paymentIntent().equals(admission.firstRenewalAdmission().paymentIntent())
                    ||evidence.allocation().equals(admission.firstRenewalAdmission().allocation()))throw unavailable();
            requireSnapshot(admission,evidence.snapshot(),false,evidence.paymentIntent(),null);
            apply(state,claim,evidence.snapshot());
            // Recorder authenticates the transient post-fact debt; current/read predicates are prestate only.
            return store.recordFailureInTransaction(admission,claim,evidence);
        }));
    }
    public Recovery reserveExistingRecovery(Admission admission) {
        return guarded(admission,()->{
            // Immutable candidate before speculative insert. No lifecycle lock may surround the fresh claim.
            var failure=transaction.requiresNew(()->store.requireFailureInTransaction(admission));
            return transaction.requiresNew(()->{
                var upstream=admission.firstRenewalAdmission();var intent=upstream.receipt().intent();var owner=intent.clock().owner();
                var id=UUID.randomUUID();var at=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                var fingerprint="v1.recover."+failure.billId()+"."+OwnedFirstRenewal.fingerprint(
                    "owned-failed-renewal-recover-v1",admission.id(),failure.billId(),failure.billVersion(),failure.invoice(),failure.allocation(),failure.paymentIntent(),failure.revision());
                var candidate=new SubscriptionOperation(id,owner.site(),owner.customer(),intent.subscription(),SubscriptionOperation.Kind.RECOVER,
                    SubscriptionOperation.Status.RESERVED,OwnedFailedRenewal.recoveryAuthority(admission),
                    OwnedFirstRenewal.fingerprint("owned-failed-renewal-recover-key-v1",admission.id()),fingerprint,
                    "subscription:"+owner.account().value()+":"+id+":RECOVER",failure.revision(),upstream.policyVersion(),upstream.receipt().reference(),null,at,at,0);
                var claimed=operations.claim(candidate);
                if(!claimed.owner()||!candidate.equals(claimed.operation()))throw unavailable();
                var state=lock(upstream,admission.boundary(),id);
                requireDebt(state,failure);requireReserved(state,candidate.id(),candidate.version());
                // The fresh operation is deliberately unassociated here: do not call current/requireFailure.
                return store.associateInTransaction(admission,candidate,failure.allocation(),failure.paymentIntent(),state.utcAt());
            });
        });
    }
    public ReadClaim beginRecoveredRead(Admission admission,UUID invocation) {
        return guarded(admission,()->transaction.requiresNew(()->{
            var state=current(admission);var recovery=store.requireRecoveryInTransaction(admission);
            requireReserved(state,recovery.operationId(),recovery.operationVersion());
            return store.claimReadInTransaction(admission,Phase.RECOVERED,Objects.requireNonNull(invocation),state.fence(),state.utcAt());
        }));
    }
    public Publication publishRecovery(Admission admission,ReadClaim claim,RecoveredEvidence evidence) {
        return guarded(admission,()->transaction.requiresNew(()->{
            var state=current(admission);var recovery=store.requireRecoveryInTransaction(admission);
            var operation=requireReserved(state,recovery.operationId(),recovery.operationVersion());
            requireRead(admission,claim,Phase.RECOVERED,state,evidence.snapshot(),evidence.acceptedAt());
            requireSnapshot(admission,evidence.snapshot(),true,recovery.paymentIntent(),operation.providerOperationKey());
            apply(state,claim,evidence.snapshot());
            return store.recordPublicationInTransaction(admission,claim,clock.instant());
        }));
    }
    /** Durable replay performs no fact application and no terminal write on a failed lookup. */
    public Publication replayPublication(Admission admission) {
        fresh();Objects.requireNonNull(admission);
        return transaction.requiresNew(()->{
            var state=current(admission);
            var publication=store.publicationInTransaction(admission).orElseThrow(OwnedFailedRenewalService::unavailable);
            if(state.subscription().revision()!=publication.revision())throw unavailable();
            return publication;
        });
    }
    public void stop(Admission admission,Reason reason) {
        fresh();Objects.requireNonNull(admission);Objects.requireNonNull(reason);
        transaction.requiresNew(()->{store.stopInTransaction(admission,reason,clock.instant());return null;});
    }
    private <T>T guarded(Admission admission,Supplier<T> work) {
        fresh();Objects.requireNonNull(admission);
        try{return work.get();}
        catch(RuntimeException failure){
            // requiresNew has unwound before this separate transaction begins. Keep the original failure.
            try{stop(admission,failure instanceof IllegalArgumentException||failure instanceof IllegalStateException?Reason.AUTHORITY_DRIFT:Reason.UNCERTAIN);}
            catch(RuntimeException stopFailure){failure.addSuppressed(stopFailure);}
            throw failure;
        }
    }
    private void fresh(){if(transaction.active())throw new IllegalStateException("Owned failed renewal requires an outer transaction boundary");}
    private record Locked(PaymentAccount account,Subscription subscription,List<SubscriptionOperation> operations,
            List<BillingRecord> bills,SubscriptionOperation selected,Fence fence,Instant utcAt) {}
    private Locked current(Admission admission) {
        var state=lock(admission.firstRenewalAdmission(),admission.boundary(),null);
        if(!admission.equals(store.requireCurrentInTransaction(admission)))throw unavailable();
        return state;
    }
    private Locked lock(OwnedFirstRenewal.Admission upstream,OwnedFirstRenewal.Boundary boundary,UUID freshOperation) {
        var intent=upstream.receipt().intent();var owner=intent.clock().owner();
        var candidate=subscriptions.findOwned(owner.account(),intent.subscription()).orElseThrow(OwnedFailedRenewalService::unavailable);
        if(!candidate.siteId().equals(owner.site())||!candidate.customerId().equals(owner.customer())
                ||!candidate.id().equals(intent.subscription())||!candidate.paymentAccountId().equals(owner.account()))throw unavailable();
        var ids=new TreeSet<UUID>();ids.add(intent.createOperation());if(freshOperation!=null)ids.add(freshOperation);
        operations.unresolved(candidate.siteId(),candidate.id()).forEach(o->ids.add(o.id()));
        trials.find(candidate.siteId(),candidate.customerId(),candidate.productId()).filter(t->t.consumedAt()==null&&t.reservedByOperation()!=null).ifPresent(t->ids.add(t.reservedByOperation()));
        var locked=new ArrayList<SubscriptionOperation>();for(var id:ids)locked.add(operations.lock(id));
        lifecycle.lockActive(owner.site(),owner.customer());
        var account=accounts.findByIdForUpdate(owner.account()).orElseThrow(OwnedFailedRenewalService::unavailable);
        var subscription=subscriptions.lock(owner.site(),intent.subscription());
        if(!subscription.id().equals(intent.subscription())||!subscription.siteId().equals(owner.site())||!subscription.customerId().equals(owner.customer())
                ||!subscription.paymentAccountId().equals(owner.account())||!subscription.billingPrice().equals(intent.terms())
                ||!Objects.equals(subscription.providerReference(),upstream.receipt().reference())
                ||!account.id().equals(owner.account())||account.provider()!=PaymentProviderType.STRIPE||account.mode()!=PaymentAccountMode.SANDBOX
                ||account.status()!=PaymentAccountStatus.ACTIVE||!account.externalAccountId().equals(owner.externalAccount())||!account.credentialsRef().equals(owner.credentialsRef())
                ||trials.find(owner.site(),owner.customer(),subscription.productId()).filter(t->t.consumedAt()==null&&t.reservedByOperation()!=null).isPresent())throw unavailable();
        for(var operation:locked)if(operation==null||!operation.siteId().equals(owner.site())||!operation.customerId().equals(owner.customer())||!intent.subscription().equals(operation.subscriptionId()))throw unavailable();
        var billIds=new TreeSet<UUID>();billIds.add(upstream.bill());
        bills.find(owner.site(),intent.subscription(),owner.account(),boundary.renewalInvoice()).ifPresent(b->billIds.add(b.id()));
        var lockedBills=new ArrayList<BillingRecord>();for(var id:billIds)lockedBills.add(bills.lock(owner.site(),id));
        var at=clock.instant();var sample=billingTime.sample(owner.account(),owner.site(),owner.customer(),at);
        if(sample==null||!sample.isolated()||sample.billingAt().isBefore(boundary.period().start())||!sample.billingAt().isBefore(boundary.period().end()))throw unavailable();
        var selected=reads.financial(subscription);
        var fence=new Fence(subscription.revision(),selected==null?null:selected.id(),selected==null?null:selected.version(),null,null,observations.captureFence(subscription));
        return new Locked(account,subscription,List.copyOf(locked),List.copyOf(lockedBills),selected,fence,at);
    }
    private static void requireNoFinancial(Locked state) {
        if(state.selected()!=null)throw unavailable();
        for(var operation:state.operations())if(operation.kind()!=SubscriptionOperation.Kind.RECONCILE
                &&!(operation.kind()==SubscriptionOperation.Kind.CREATE&&operation.status()==SubscriptionOperation.Status.SUCCEEDED))throw unavailable();
    }
    private static SubscriptionOperation requireReserved(Locked state,UUID operationId,long version) {
        var selected=state.selected();
        if(selected==null||!selected.id().equals(operationId)||selected.version()!=version||selected.kind()!=SubscriptionOperation.Kind.RECOVER
                ||selected.status()!=SubscriptionOperation.Status.RESERVED||selected.expectedRevision()!=state.subscription().revision())throw unavailable();
        long count=state.operations().stream().filter(o->o.kind()==SubscriptionOperation.Kind.RECOVER).count();
        if(count!=1||state.operations().stream().anyMatch(o->!o.id().equals(selected.id())&&o.kind()!=SubscriptionOperation.Kind.RECONCILE
                &&!(o.kind()==SubscriptionOperation.Kind.CREATE&&o.status()==SubscriptionOperation.Status.SUCCEEDED)))throw unavailable();
        return selected;
    }
    private static void requireDebt(Locked state,Failure failure) {
        var s=state.subscription();var debt=s.debt();
        if(debt==null||s.revision()!=failure.revision()||!Objects.equals(state.fence().observationSequence(),failure.observationSequence())
                ||!debt.billingRecordId().equals(failure.billId())||!debt.unpaidPeriod().equals(failure.period())||!debt.graceDeadline().equals(failure.graceDeadline()))throw unavailable();
        var bill=state.bills().stream().filter(b->b.id().equals(failure.billId())).findFirst().orElseThrow(OwnedFailedRenewalService::unavailable);
        if(bill.version()!=failure.billVersion()||bill.settlement()!=BillingRecord.Settlement.OPEN||!bill.providerInvoiceReference().equals(failure.invoice())
                ||!bill.subscriptionId().equals(s.id())||!bill.accountId().equals(s.paymentAccountId())||!bill.siteId().equals(s.siteId())
                ||!bill.period().equals(failure.period())||!bill.billedPrice().equals(s.billingPrice()))throw unavailable();
    }
    private void requireRead(Admission admission,ReadClaim claim,Phase phase,Locked state,Snapshot snapshot,Instant acceptedAt) {
        if(claim==null||claim.phase()!=phase||!claim.admissionId().equals(admission.id())||!claim.fence().equals(state.fence())
                ||!claim.equals(store.requireReadInTransaction(admission,claim))||acceptedAt.isAfter(state.utcAt())||!state.utcAt().isBefore(claim.deadlineAt()))throw unavailable();
        claim.requireAccepted(snapshot.observedAt(),acceptedAt);
    }
    private void apply(Locked state,ReadClaim claim,Snapshot snapshot) {
        var key=OwnedFailedRenewal.factKey(claim);
        facts.apply(new ApplySubscriptionSnapshotUseCase.Command(state.account(),state.subscription().id(),snapshot,SubscriptionEvent.Source.RECONCILIATION,key,key,claim.fence()));
    }
    private static void requireSnapshot(Admission admission,Snapshot snapshot,boolean recovered,String paymentIntent,String operationKey) {
        var original=admission.firstRenewalAdmission();var receipt=original.receipt();var terms=receipt.intent().terms();
        if(!receipt.reference().equals(snapshot.subscriptionReference())||!terms.equals(snapshot.billingPrice())||!admission.boundary().period().equals(snapshot.period())
                ||snapshot.status()!=(recovered?Subscription.Status.ACTIVE:Subscription.Status.PAST_DUE)||snapshot.trial()!=null||snapshot.cancellationAt()!=null
                ||snapshot.providerRevision()!=0||snapshot.trialEvidence()!=null||snapshot.externalWithdrawalEvidence()!=null||!snapshot.recoveryObservations().isEmpty()
                ||snapshot.invoices().size()!=2||!Objects.equals(snapshot.operationKey(),operationKey))throw unavailable();
        if(recovered){if(snapshot.operationObservation()==null||snapshot.operationObservation().state()!=OperationState.APPLIED
                ||!operationKey.equals(snapshot.operationObservation().operationKey())||paymentIntent.equals(original.paymentIntent()))throw unavailable();}
        else if(snapshot.operationObservation()!=null)throw unavailable();
        var initial=snapshot.invoices().stream().filter(i->i.kind()==BillingRecord.Kind.INITIAL).toList();
        var renewal=snapshot.invoices().stream().filter(i->i.kind()==BillingRecord.Kind.RENEWAL).toList();
        if(initial.size()!=1||renewal.size()!=1)throw unavailable();
        var first=initial.getFirst();var second=renewal.getFirst();
        if(!first.reference().equals(original.invoice())||!first.period().equals(new Subscription.Period(Instant.ofEpochSecond(receipt.periodStart()),Instant.ofEpochSecond(receipt.periodEnd())))
                ||first.settlement()!=SettlementKind.CASH||!second.reference().equals(admission.boundary().renewalInvoice())||!second.period().equals(admission.boundary().period())
                ||second.settlement()!=(recovered?SettlementKind.CASH:SettlementKind.OPEN))throw unavailable();
        for(var invoice:snapshot.invoices())if(!terms.equals(invoice.price())||invoice.previousPrice()!=null
                ||invoice.amountDueMinor()!=terms.regularAmount().minorUnits()||!invoice.currency().equals(terms.regularAmount().currency()))throw unavailable();
        requireCash(first,original.paymentIntent(),snapshot.observedAt());
        if(recovered)requireCash(second,paymentIntent,snapshot.observedAt());else if(!second.cashObjects().isEmpty())throw unavailable();
    }
    private static void requireCash(Invoice invoice,String paymentIntent,Instant observedAt) {
        if(invoice.cashObjects().size()!=1)throw unavailable();var cash=invoice.cashObjects().getFirst();
        if(!cash.reference().equals(paymentIntent)||cash.status()!=PaymentStatus.SUCCEEDED||cash.amountMinor()!=invoice.amountDueMinor()
                ||!cash.currency().equals(invoice.currency())||cash.occurredAt().isAfter(observedAt))throw unavailable();
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("OWNED_FAILED_RENEWAL_UNAVAILABLE");}
}
