package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.PrepareSubscriptionReadUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;

/** Explicit internal publication consumer; deliberately absent from ordinary registration. */
public final class OwnedFirstRenewalPublicationService {
    private final OwnedFirstRenewalStore store;
    private final SubscriptionTransactionPort transaction;
    private final SubscriptionOperationStore operations;
    private final SubscriptionLifecycleLockPort lifecycle;
    private final LockPaymentAccountPort accounts;
    private final SubscriptionStore subscriptions;
    private final PrepareSubscriptionReadUseCase reads;
    private final SubscriptionObservationPort observations;
    private final ApplySubscriptionSnapshotUseCase facts;
    private final TrialEligibilityStore trials;
    private final Clock clock;
    public OwnedFirstRenewalPublicationService(OwnedFirstRenewalStore store,SubscriptionTransactionPort transaction,
            SubscriptionOperationStore operations,SubscriptionLifecycleLockPort lifecycle,LockPaymentAccountPort accounts,
            SubscriptionStore subscriptions,PrepareSubscriptionReadUseCase reads,SubscriptionObservationPort observations,
            ApplySubscriptionSnapshotUseCase facts,TrialEligibilityStore trials,Clock clock) {
        this.store=store;this.transaction=transaction;this.operations=operations;this.lifecycle=lifecycle;this.accounts=accounts;
        this.subscriptions=subscriptions;this.reads=reads;this.observations=observations;this.facts=facts;this.trials=trials;this.clock=clock;
    }

    public Publication publish(Admission admission,LaterReady ready,Paid paid) {
        if(admission==null||ready==null||paid==null)throw unavailable();
        return transaction.required(()->{
            var state=lock(admission);
            if(!admission.equals(paid.admission())||!ready.equals(paid.ready())||ready.ack().advance().step()!=Step.FINALIZATION
                    ||state.subscription().revision()!=admission.subscriptionRevision()
                    ||!ready.equals(store.requireReadyInTransaction(admission,ready))
                    ||!store.readClaim(admission,ready).filter(paid.claim()::equals).isPresent()
                    ||!paid.boundary().equals(store.boundaryInTransaction(admission))
                    ||!state.fence().equals(paid.fence())||!paid.claim().fence().equals(paid.fence())
                    ||paid.graphAcceptedAt().isBefore(paid.claim().startedAt())||!paid.graphAcceptedAt().isBefore(paid.claim().deadlineAt())
                    ||paid.graphAcceptedAt().isAfter(clock.instant()))throw unavailable();
            requireCashShape(admission,paid);
            facts.apply(new ApplySubscriptionSnapshotUseCase.Command(state.account(),state.subscription().id(),paid.snapshot(),
                SubscriptionEvent.Source.RECONCILIATION,"owned-renewal:"+admission.id(),"owned-renewal:"+admission.id(),paid.fence()));
            return store.recordPublicationInTransaction(admission,ready,paid);
        });
    }

    public Publication replayPublished(Admission admission) {
        if(admission==null)throw unavailable();
        return transaction.required(()->{
            var state=lock(admission);
            var receipt=store.publicationInTransaction(admission).orElseThrow(OwnedFirstRenewalPublicationService::unavailable);
            if(state.fence().revision()!=receipt.projectedRevision())throw unavailable();
            return receipt;
        });
    }

    private record Locked(PaymentAccount account,Subscription subscription,ApplySubscriptionSnapshotUseCase.Fence fence) {}
    private Locked lock(Admission admission) {
        var i=admission.receipt().intent();var o=i.clock().owner();
        var candidate=subscriptions.findOwned(o.account(),i.subscription()).orElseThrow(OwnedFirstRenewalPublicationService::unavailable);
        if(!candidate.siteId().equals(o.site())||!candidate.customerId().equals(o.customer()))throw unavailable();
        var ids=new TreeSet<UUID>();ids.add(i.createOperation());operations.unresolved(candidate.siteId(),candidate.id()).forEach(operation->ids.add(operation.id()));
        trials.find(candidate.siteId(),candidate.customerId(),candidate.productId()).filter(t->t.consumedAt()==null&&t.reservedByOperation()!=null)
            .ifPresent(t->ids.add(t.reservedByOperation()));
        for(var id:ids)operations.lock(id);
        lifecycle.lockActive(o.site(),o.customer());var account=accounts.findByIdForUpdate(o.account()).orElseThrow();
        var subscription=subscriptions.lock(o.site(),i.subscription());
        if(!admission.equals(store.requirePaidInTransaction(admission))||reads.financial(subscription)!=null
                ||trials.find(o.site(),o.customer(),subscription.productId()).filter(t->t.consumedAt()==null&&t.reservedByOperation()!=null).isPresent())throw unavailable();
        return new Locked(account,subscription,new ApplySubscriptionSnapshotUseCase.Fence(subscription.revision(),null,null,null,null,observations.captureFence(subscription)));
    }

    private static void requireCashShape(Admission admission,Paid paid) {
        var s=paid.snapshot();var receipt=admission.receipt();var terms=receipt.intent().terms();
        if(!receipt.reference().equals(s.subscriptionReference())||s.status()!=Subscription.Status.ACTIVE||!terms.equals(s.billingPrice())
                ||!paid.boundary().period().equals(s.period())||s.providerRevision()!=0||s.operationKey()!=null||s.operationObservation()!=null
                ||s.trial()!=null||s.trialEvidence()!=null||s.cancellationAt()!=null||s.externalWithdrawalEvidence()!=null
                ||!s.recoveryObservations().isEmpty()||s.invoices().size()!=2||s.observedAt().isBefore(paid.claim().startedAt())
                ||s.observedAt().isAfter(paid.graphAcceptedAt()))throw unavailable();
        var initial=s.invoices().stream().filter(i->i.kind()==BillingRecord.Kind.INITIAL).toList();
        var renewal=s.invoices().stream().filter(i->i.kind()==BillingRecord.Kind.RENEWAL).toList();
        if(initial.size()!=1||renewal.size()!=1)throw unavailable();
        var old=initial.getFirst();var next=renewal.getFirst();
        if(!old.reference().equals(admission.invoice())||!old.period().equals(new Subscription.Period(Instant.ofEpochSecond(receipt.periodStart()),Instant.ofEpochSecond(receipt.periodEnd())))
                ||!next.reference().equals(paid.boundary().renewalInvoice())||next.reference().equals(old.reference())||!next.period().equals(paid.boundary().period()))throw unavailable();
        for(var invoice:s.invoices()) {
            if(!terms.equals(invoice.price())||invoice.previousPrice()!=null||invoice.settlement()!=SettlementKind.CASH
                    ||invoice.amountDueMinor()!=terms.regularAmount().minorUnits()||!invoice.currency().equals(terms.regularAmount().currency())
                    ||invoice.cashObjects().size()!=1)throw unavailable();
            var cash=invoice.cashObjects().getFirst();
            if(cash.status()!=PaymentStatus.SUCCEEDED||cash.amountMinor()!=invoice.amountDueMinor()||!cash.currency().equals(invoice.currency())
                    ||!cash.reference().matches("pi_[A-Za-z0-9]{1,252}"))throw unavailable();
        }
        if(!old.cashObjects().getFirst().reference().equals(admission.paymentIntent())
                ||next.cashObjects().getFirst().reference().equals(admission.paymentIntent()))throw unavailable();
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("OWNED_FIRST_RENEWAL_PUBLICATION_UNAVAILABLE");}
}
