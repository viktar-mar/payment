package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.*;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.CustomerSessionScope;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionInteractionTransaction;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Function;

/** Explicit internal composition. No provider calls, runtime registration or nested authority transactions. */
public final class OwnedFailedRenewalMethodService {
    private final OwnedFailedRenewalMethodStore store;
    private final OwnedFailedRenewalStore failed;
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
    private final SubscriptionBillingTimePort time;
    private final Clock clock;
    private final StripeBillingBindingPort bindings;
    private final SubscriptionAuthorityPort authority;
    private final DigestPort digest;

    public OwnedFailedRenewalMethodService(OwnedFailedRenewalMethodStore store,OwnedFailedRenewalStore failed,SubscriptionTransactionPort transaction,
        SubscriptionOperationStore operations,SubscriptionLifecycleLockPort lifecycle,LockPaymentAccountPort accounts,SubscriptionStore subscriptions,
        BillingRecordStore bills,PrepareSubscriptionReadUseCase reads,SubscriptionObservationPort observations,ApplySubscriptionSnapshotUseCase facts,
        TrialEligibilityStore trials,SubscriptionBillingTimePort time,Clock clock,StripeBillingBindingPort bindings,SubscriptionAuthorityPort authority,DigestPort digest){
        this.store=Objects.requireNonNull(store);this.failed=Objects.requireNonNull(failed);this.transaction=Objects.requireNonNull(transaction);
        this.operations=Objects.requireNonNull(operations);this.lifecycle=Objects.requireNonNull(lifecycle);this.accounts=Objects.requireNonNull(accounts);
        this.subscriptions=Objects.requireNonNull(subscriptions);this.bills=Objects.requireNonNull(bills);this.reads=Objects.requireNonNull(reads);
        this.observations=Objects.requireNonNull(observations);this.facts=Objects.requireNonNull(facts);this.trials=Objects.requireNonNull(trials);
        this.time=Objects.requireNonNull(time);this.clock=Objects.requireNonNull(clock);this.bindings=Objects.requireNonNull(bindings);
        this.authority=Objects.requireNonNull(authority);this.digest=Objects.requireNonNull(digest);
    }
    public Reservation reserveMethod(OwnedFailedRenewal.Admission admission,SubscriptionCommandContext context,boolean recurringConsent){
        return reserve(admission,context,recurringConsent,false);
    }
    public Reservation reserveBrowserMethod(OwnedFailedRenewal.Admission admission,SubscriptionCommandContext context,boolean recurringConsent){
        return reserve(admission,context,recurringConsent,true);
    }
    private Reservation reserve(OwnedFailedRenewal.Admission admission,SubscriptionCommandContext context,boolean recurringConsent,boolean browser){
        fresh();Objects.requireNonNull(admission);validateContext(context);
        if(!recurringConsent)throw new IllegalArgumentException("Recurring consent required");
        if(browser)customer(context,utc());
        Supplier<Reservation> work=()->{
            var failure=transaction.requiresNew(()->failed.requireFailureInTransaction(admission));
            return transaction.requiresNew(()->{
                var intent=admission.firstRenewalAdmission().receipt().intent();var owner=intent.clock().owner();var id=UUID.randomUUID();var at=utc();
                var candidate=new SubscriptionOperation(id,owner.site(),owner.customer(),intent.subscription(),SubscriptionOperation.Kind.SETUP,
                    SubscriptionOperation.Status.RESERVED,methodAuthority(admission),digest.sha256Hex(context.idempotencyKey()),request(admission,failure,context),
                    "subscription:"+owner.account().value()+":"+id+":SETUP",failure.revision(),admission.firstRenewalAdmission().policyVersion(),null,null,at,at,0);
                var claim=operations.claim(candidate);
                if(!claim.owner()||!candidate.equals(claim.operation()))throw unavailable();
                var state=lock(admission,failure,Set.of(id),context);requireDebt(state,failure,0);
                selected(state,id,SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Status.RESERVED,0,null);
                if(browser){var session=customer(context,utc());return store.reserveBrowserInTransaction(admission,failure,candidate,state.binding(),state.sample(),state.utcAt(),
                    new Enrollment(session.sessionId().value(),session.origin(),session.expiresAt()));}
                return store.reserveInTransaction(admission,failure,candidate,state.binding(),state.sample(),state.utcAt());
            });
        };
        return browser?work.get():guarded(admission,work);
    }
    /** Fresh original management authentication before SETUP exists; no writes and no reusable dispatch authority. */
    public SetupPreparationContext inspectBrowserSetupPreparation(Reservation reservation,SubscriptionCommandContext originalContext){
        fresh();Objects.requireNonNull(reservation);validateContext(originalContext);customer(originalContext,utc());
        return transaction.requiresNew(()->{
            var state=current(reservation,originalContext,null);requireDebt(state,reservation.failure(),0);
            var selected=selected(state,reservation.setupOperationId(),SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Status.RESERVED,0,null);
            if(!selected.authorityScope().equals(methodAuthority(reservation.admission()))
                ||!selected.fingerprint().equals(request(reservation.admission(),reservation.failure(),originalContext))
                ||!selected.keyDigest().equals(digest.sha256Hex(originalContext.idempotencyKey())))throw unavailable();
            // Existing operation/lifecycle/account/subscription/bill waits cannot extend original session authority.
            authority.revalidate(originalContext,true);
            var enrollment=store.requireBrowserSetupEnrollmentInTransaction(reservation,customer(originalContext,utc()),utc());
            return new SetupPreparationContext(reservation,enrollment,state.account(),state.subscription(),selected,state.binding(),state.sample());
        });
    }
    /** Browser requests have their own keys (GET has none) and must never stop another customer's admission. */
    <T>T browserPhase(Reservation reservation,SubscriptionCommandContext context,Function<Pending,T> work){
        fresh();Objects.requireNonNull(reservation);Objects.requireNonNull(work);customer(context,utc());
        return transaction.requiresNew(()->{
            var state=current(reservation,context,null);requireDebt(state,reservation.failure(),0);
            var selected=selected(state,reservation.setupOperationId(),SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Status.RESERVED,0,null);
            if(!selected.authorityScope().equals(methodAuthority(reservation.admission()))||!selected.fingerprint().equals(request(reservation.admission(),reservation.failure(),context)))throw unavailable();
            var pending=store.requireBrowserPendingInTransaction(reservation,customer(context,utc()),utc());
            if(!pending.reservation().equals(reservation)||!pending.account().equals(state.account())||!pending.subscription().equals(state.subscription())
                ||!pending.operation().equals(selected)||!pending.binding().equals(state.binding()))throw unavailable();
            return work.apply(pending);
        });
    }
    /** Both provider ACKs and management authority are authenticated in this exact save transaction. */
    public SubscriptionOperation recordMethodUpdateReply(Reservation reservation,SubscriptionCommandContext context){
        Objects.requireNonNull(reservation);validateContext(context);
        return guarded(reservation.admission(),()->transaction.requiresNew(()->{
            var state=current(reservation,context,null);requireDebt(state,reservation.failure(),0);
            var selected=selected(state,reservation.setupOperationId(),SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Status.RESERVED,reservation.initialOperationVersion(),null);
            if(!selected.authorityScope().equals(methodAuthority(reservation.admission()))||!selected.fingerprint().equals(request(reservation.admission(),reservation.failure(),context))
                ||!selected.keyDigest().equals(digest.sha256Hex(context.idempotencyKey())))throw unavailable();
            var prepared=store.requirePreparedMethodInTransaction(reservation);requireMethodContext(state,prepared);
            if(!prepared.operation().equals(selected))throw unavailable();
            return operations.save(new SubscriptionOperation(selected.id(),selected.siteId(),selected.customerId(),selected.subscriptionId(),selected.kind(),
                SubscriptionOperation.Status.PROCESSING,selected.authorityScope(),selected.keyDigest(),selected.fingerprint(),selected.providerOperationKey(),
                selected.expectedRevision(),selected.policyVersion(),state.subscription().providerReference(),null,selected.createdAt(),state.utcAt(),selected.version()+1),selected.version());
        }));
    }
    public ReadClaim beginMethodRead(Reservation reservation,UUID invocation){
        return guarded(reservation.admission(),()->transaction.requiresNew(()->{
            var state=current(reservation,null,null);requireDebt(state,reservation.failure(),0);
            selected(state,reservation.setupOperationId(),SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Status.PROCESSING,reservation.initialOperationVersion()+1,null);
            return store.claimReadInTransaction(reservation,Phase.METHOD,Objects.requireNonNull(invocation),state.fence(),state.sample(),state.utcAt());
        }));
    }
    public MethodReadContext inspectMethodRead(Reservation reservation,ReadClaim claim){
        fresh();return transaction.requiresNew(()->{
            var state=current(reservation,null,null);requireRead(reservation,claim,Phase.METHOD,state,null,null);
            var context=store.requireMethodContextInTransaction(reservation,claim);requireMethodContext(state,context);return context;
        });
    }
    public Completion publishMethod(Reservation reservation,ReadClaim claim,MethodEvidence evidence,MethodReadContext expected){
        return guarded(reservation.admission(),()->transaction.requiresNew(()->{
            var state=current(reservation,null,null);requireRead(reservation,claim,Phase.METHOD,state,evidence.snapshot(),evidence.acceptedAt());
            var context=store.requireMethodContextInTransaction(reservation,claim);requireMethodContext(state,context);
            if(expected==null||!expected.equals(context)||!evidence.setupIntent().equals(context.setupProof().reference())||!evidence.method().equals(context.setupProof().method()))throw unavailable();
            requireSnapshot(reservation,evidence.snapshot(),false);
            var raw=evidence.snapshot();var key=context.operation().providerOperationKey();
            var applied=new Snapshot(raw.subscriptionReference(),raw.status(),raw.billingPrice(),raw.period(),raw.trial(),raw.cancellationAt(),raw.invoices(),key,raw.paymentMethodReady(),
                raw.providerRevision(),raw.observedAt(),new OperationObservation(key,OperationState.APPLIED),raw.recoveryObservations(),raw.trialEvidence(),raw.externalWithdrawalEvidence());
            apply(state,claim,applied);
            // Receipt authenticates poststate, never re-enters a PROCESSING/debt-sequence precondition.
            return store.recordCompletionInTransaction(reservation,claim,evidence,clock.instant());
        }));
    }
    public Recovery reserveRecovery(Reservation reservation,Completion completion){
        return guarded(reservation.admission(),()->transaction.requiresNew(()->{
            var a=reservation.admission();var f=reservation.failure();var intent=a.firstRenewalAdmission().receipt().intent();var owner=intent.clock().owner();
            var id=UUID.randomUUID();var at=utc();
            var fingerprint="v1.recover."+f.billId()+"."+OwnedFirstRenewal.fingerprint("owned-failed-renewal-recover-v1",a.id(),f.billId(),f.billVersion(),f.invoice(),f.allocation(),f.paymentIntent(),f.revision());
            var candidate=new SubscriptionOperation(id,owner.site(),owner.customer(),intent.subscription(),SubscriptionOperation.Kind.RECOVER,SubscriptionOperation.Status.RESERVED,
                OwnedFailedRenewalMethod.recoveryAuthority(reservation),OwnedFirstRenewal.fingerprint("owned-failed-renewal-recover-key-v1",a.id()),fingerprint,
                "subscription:"+owner.account().value()+":"+id+":RECOVER",f.revision(),a.firstRenewalAdmission().policyVersion(),a.firstRenewalAdmission().receipt().reference(),null,at,at,0);
            var claim=operations.claim(candidate);if(!claim.owner()||!candidate.equals(claim.operation()))throw unavailable();
            var state=current(reservation,null,id);requireDebt(state,f,1);
            selected(state,id,SubscriptionOperation.Kind.RECOVER,SubscriptionOperation.Status.RESERVED,0,reservation.setupOperationId());
            if(!completion.equals(store.requireCompletionInTransaction(reservation,completion)))throw unavailable();
            return store.associateRecoveryInTransaction(reservation,completion,candidate,state.utcAt());
        }));
    }
    public ReadClaim beginRecoveredRead(Reservation reservation,Recovery recovery,UUID invocation){
        return guarded(reservation.admission(),()->transaction.requiresNew(()->{
            var state=current(reservation,null,recovery.association().operationId());requireDebt(state,reservation.failure(),1);
            if(!recovery.equals(store.requireRecoveryInTransaction(reservation,recovery)))throw unavailable();
            selected(state,recovery.association().operationId(),SubscriptionOperation.Kind.RECOVER,SubscriptionOperation.Status.RESERVED,recovery.association().operationVersion(),reservation.setupOperationId());
            return store.claimReadInTransaction(reservation,Phase.RECOVERED,Objects.requireNonNull(invocation),state.fence(),state.sample(),state.utcAt());
        }));
    }
    public RecoveredReadContext inspectRecoveredRead(Reservation reservation,ReadClaim claim){
        fresh();return transaction.requiresNew(()->{
            var state=current(reservation,null,claim.fence().operationId());requireRead(reservation,claim,Phase.RECOVERED,state,null,null);
            var context=store.requireRecoveredContextInTransaction(reservation,claim);requireRecoveredContext(state,context);return context;
        });
    }
    public Publication publishRecovery(Reservation reservation,ReadClaim claim,OwnedFailedRenewal.RecoveredEvidence evidence,RecoveredReadContext expected){
        return guarded(reservation.admission(),()->transaction.requiresNew(()->{
            var state=current(reservation,null,claim.fence().operationId());requireRead(reservation,claim,Phase.RECOVERED,state,evidence.snapshot(),evidence.acceptedAt());
            var context=store.requireRecoveredContextInTransaction(reservation,claim);requireRecoveredContext(state,context);
            if(expected==null||!expected.equals(context))throw unavailable();
            requireSnapshot(reservation,evidence.snapshot(),true);
            var raw=evidence.snapshot();var key=context.operation().providerOperationKey();
            var applied=new Snapshot(raw.subscriptionReference(),raw.status(),raw.billingPrice(),raw.period(),raw.trial(),raw.cancellationAt(),raw.invoices(),key,raw.paymentMethodReady(),
                raw.providerRevision(),raw.observedAt(),new OperationObservation(key,OperationState.APPLIED),raw.recoveryObservations(),raw.trialEvidence(),raw.externalWithdrawalEvidence());
            apply(state,claim,applied);return store.recordPublicationInTransaction(reservation,claim,clock.instant());
        }));
    }
    public void stop(Reservation reservation,OwnedFailedRenewal.Reason reason){fresh();stopAdmission(reservation.admission(),reason);}
    private void stopAdmission(OwnedFailedRenewal.Admission admission,OwnedFailedRenewal.Reason reason){transaction.requiresNew(()->{failed.stopInTransaction(admission,reason,clock.instant());return null;});}
    private <T>T guarded(OwnedFailedRenewal.Admission admission,Supplier<T> work){
        fresh();Objects.requireNonNull(admission);
        try{return work.get();}catch(RuntimeException failure){
            try{stopAdmission(admission,failure instanceof IllegalArgumentException||failure instanceof IllegalStateException?OwnedFailedRenewal.Reason.AUTHORITY_DRIFT:OwnedFailedRenewal.Reason.UNCERTAIN);}
            catch(RuntimeException stopFailure){failure.addSuppressed(stopFailure);}throw failure;
        }
    }
    private void fresh(){if(transaction.active())throw new IllegalStateException("Owned method transition requires an outer transaction boundary");}
    private Instant utc(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
    private record Locked(PaymentAccount account,Subscription subscription,List<SubscriptionOperation> operations,List<BillingRecord> bills,
        StripeBillingBindingPort.CustomerBinding binding,Fence fence,SubscriptionBillingTimePort.Sample sample,Instant utcAt){}
    private Locked current(Reservation reservation,SubscriptionCommandContext context,UUID recovery){
        var ids=new HashSet<UUID>();ids.add(reservation.setupOperationId());if(recovery!=null)ids.add(recovery);
        var state=lock(reservation.admission(),reservation.failure(),ids,context);
        if(!reservation.equals(store.requireReservationInTransaction(reservation))||!reservation.binding().equals(state.binding())||!reservation.billingSample().equals(state.sample()))throw unavailable();return state;
    }
    private Locked lock(OwnedFailedRenewal.Admission admission,OwnedFailedRenewal.Failure failure,Set<UUID> selected,SubscriptionCommandContext context){
        var upstream=admission.firstRenewalAdmission();var intent=upstream.receipt().intent();var owner=intent.clock().owner();
        var candidate=subscriptions.findOwned(owner.account(),intent.subscription()).orElseThrow(OwnedFailedRenewalMethodService::unavailable);
        if(!candidate.siteId().equals(owner.site())||!candidate.customerId().equals(owner.customer())||!candidate.id().equals(intent.subscription())||!candidate.paymentAccountId().equals(owner.account()))throw unavailable();
        var ids=new TreeSet<UUID>(selected);ids.add(intent.createOperation());operations.unresolved(owner.site(),candidate.id()).forEach(o->ids.add(o.id()));
        trials.find(owner.site(),owner.customer(),candidate.productId()).filter(t->t.consumedAt()==null&&t.reservedByOperation()!=null).ifPresent(t->ids.add(t.reservedByOperation()));
        var locked=new ArrayList<SubscriptionOperation>();for(var id:ids)locked.add(operations.lock(id));
        if(context!=null){if(!owner.site().equals(context.siteId())||!owner.customer().equals(context.customerId()))throw unavailable();authority.revalidate(context,true);}
        lifecycle.lockActive(owner.site(),owner.customer());
        var account=accounts.findByIdForUpdate(owner.account()).orElseThrow(OwnedFailedRenewalMethodService::unavailable);var s=subscriptions.lock(owner.site(),intent.subscription());
        if(!s.id().equals(intent.subscription())||!s.siteId().equals(owner.site())||!s.customerId().equals(owner.customer())||!s.paymentAccountId().equals(owner.account())
            ||!s.billingPrice().equals(intent.terms())||!Objects.equals(s.providerReference(),upstream.receipt().reference())||!account.id().equals(owner.account())
            ||account.provider()!=PaymentProviderType.STRIPE||account.mode()!=PaymentAccountMode.SANDBOX||account.status()!=PaymentAccountStatus.ACTIVE
            ||!account.externalAccountId().equals(owner.externalAccount())||!account.credentialsRef().equals(owner.credentialsRef())
            ||trials.find(owner.site(),owner.customer(),s.productId()).filter(t->t.consumedAt()==null&&t.reservedByOperation()!=null).isPresent())throw unavailable();
        for(var o:locked)if(o==null||!o.siteId().equals(owner.site())||!o.customerId().equals(owner.customer())||!intent.subscription().equals(o.subscriptionId()))throw unavailable();
        var billIds=new TreeSet<UUID>();billIds.add(upstream.bill());billIds.add(failure.billId());var lockedBills=new ArrayList<BillingRecord>();for(var id:billIds)lockedBills.add(bills.lock(owner.site(),id));
        var at=clock.instant();var sample=time.sample(owner.account(),owner.site(),owner.customer(),at);
        if(sample==null||!sample.isolated()||!sample.billingAt().equals(Instant.ofEpochSecond(admission.ready().ack().advance().target())))throw unavailable();
        var binding=bindings.findCustomer(owner.account(),owner.site(),owner.customer()).orElseThrow(OwnedFailedRenewalMethodService::unavailable);
        if(!binding.id().equals(intent.customerBinding())||!binding.accountId().equals(owner.account())||!binding.siteId().equals(owner.site())||!binding.customerId().equals(owner.customer())
            ||!binding.creationOperationId().equals(intent.customerCreationOperation())||!binding.providerReference().equals(intent.customerReference()))throw unavailable();
        var financial=reads.financial(s);var fence=new Fence(s.revision(),financial==null?null:financial.id(),financial==null?null:financial.version(),null,null,observations.captureFence(s));
        return new Locked(account,s,List.copyOf(locked),List.copyOf(lockedBills),binding,fence,sample,at);
    }
    private static SubscriptionOperation selected(Locked state,UUID id,SubscriptionOperation.Kind kind,SubscriptionOperation.Status status,long version,UUID completedSetup){
        var o=state.operations().stream().filter(x->x.id().equals(id)).findFirst().orElseThrow(OwnedFailedRenewalMethodService::unavailable);
        if(o.kind()!=kind||o.status()!=status||o.version()!=version||o.expectedRevision()!=state.subscription().revision()||!id.equals(state.fence().operationId())||!Objects.equals(version,state.fence().operationVersion())
            ||state.operations().stream().anyMatch(x->!x.id().equals(id)&&x.kind()!=SubscriptionOperation.Kind.RECONCILE
                &&!(x.kind()==SubscriptionOperation.Kind.CREATE&&x.status()==SubscriptionOperation.Status.SUCCEEDED)
                &&!(x.id().equals(completedSetup)&&x.kind()==SubscriptionOperation.Kind.SETUP&&x.status()==SubscriptionOperation.Status.SUCCEEDED)))throw unavailable();return o;
    }
    private static void requireDebt(Locked state,OwnedFailedRenewal.Failure failure,long sequenceDelta){
        var s=state.subscription();var debt=s.debt();
        if(s.billingStatus()!=Subscription.Status.PAST_DUE||debt==null||s.revision()!=failure.revision()||!Objects.equals(state.fence().observationSequence(),failure.observationSequence()+sequenceDelta)
            ||!debt.billingRecordId().equals(failure.billId())||!debt.unpaidPeriod().equals(failure.period())||!debt.graceDeadline().equals(failure.graceDeadline()))throw unavailable();
        var b=state.bills().stream().filter(x->x.id().equals(failure.billId())).findFirst().orElseThrow(OwnedFailedRenewalMethodService::unavailable);
        if(b.version()!=failure.billVersion()||b.settlement()!=BillingRecord.Settlement.OPEN||!b.providerInvoiceReference().equals(failure.invoice())||!b.period().equals(failure.period())||!b.billedPrice().equals(s.billingPrice())
            ||!b.siteId().equals(s.siteId())||!b.subscriptionId().equals(s.id())||!b.accountId().equals(s.paymentAccountId()))throw unavailable();
    }
    private void requireRead(Reservation r,ReadClaim q,Phase phase,Locked state,Snapshot snapshot,Instant accepted){
        if(q==null||q.phase()!=phase||!q.reservationId().equals(r.id())||!q.fence().equals(state.fence())||!q.billingSample().equals(state.sample())
            ||!q.equals(store.requireReadInTransaction(r,q))||state.utcAt().isBefore(q.startedAt())||!state.utcAt().isBefore(q.deadlineAt()))throw unavailable();
        if(snapshot!=null){q.requireAccepted(snapshot.observedAt(),accepted);if(accepted.isAfter(state.utcAt()))throw unavailable();}
    }
    private static void requireMethodContext(Locked state,MethodReadContext context){
        if(!state.account().equals(context.account())||!state.subscription().equals(context.subscription())||!state.binding().equals(context.binding())||!state.sample().equals(context.billingSample())
            ||!state.operations().contains(context.operation()))throw unavailable();
    }
    private static void requireRecoveredContext(Locked state,RecoveredReadContext context){
        if(!state.account().equals(context.account())||!state.subscription().equals(context.subscription())||!state.binding().equals(context.binding())||!state.sample().equals(context.billingSample())
            ||!state.operations().contains(context.operation()))throw unavailable();
    }
    private void apply(Locked state,ReadClaim q,Snapshot snapshot){var key=OwnedFailedRenewalMethod.factKey(q);facts.apply(new ApplySubscriptionSnapshotUseCase.Command(state.account(),state.subscription().id(),snapshot,SubscriptionEvent.Source.RECONCILIATION,key,key,q.fence(),state.sample()));}
    private static void requireSnapshot(Reservation r,Snapshot snapshot,boolean recovered){
        var original=r.admission().firstRenewalAdmission();var receipt=original.receipt();var terms=receipt.intent().terms();
        if(!receipt.reference().equals(snapshot.subscriptionReference())||!terms.equals(snapshot.billingPrice())||!r.failure().period().equals(snapshot.period())
            ||snapshot.status()!=(recovered?Subscription.Status.ACTIVE:Subscription.Status.PAST_DUE)||snapshot.trial()!=null||snapshot.cancellationAt()!=null||snapshot.providerRevision()!=0
            ||snapshot.trialEvidence()!=null||snapshot.externalWithdrawalEvidence()!=null||!snapshot.recoveryObservations().isEmpty()||snapshot.invoices().size()!=2
            ||snapshot.operationKey()!=null||snapshot.operationObservation()!=null||!recovered&&!snapshot.paymentMethodReady())throw unavailable();
        var initial=snapshot.invoices().stream().filter(i->i.kind()==BillingRecord.Kind.INITIAL).toList();var renewal=snapshot.invoices().stream().filter(i->i.kind()==BillingRecord.Kind.RENEWAL).toList();
        if(initial.size()!=1||renewal.size()!=1)throw unavailable();var first=initial.getFirst();var second=renewal.getFirst();
        if(!first.reference().equals(original.invoice())||!first.period().equals(new Subscription.Period(Instant.ofEpochSecond(receipt.periodStart()),Instant.ofEpochSecond(receipt.periodEnd())))||first.settlement()!=SettlementKind.CASH
            ||!second.reference().equals(r.failure().invoice())||!second.period().equals(r.failure().period())||second.settlement()!=(recovered?SettlementKind.CASH:SettlementKind.OPEN))throw unavailable();
        for(var i:snapshot.invoices())if(!terms.equals(i.price())||i.previousPrice()!=null||i.amountDueMinor()!=terms.regularAmount().minorUnits()||!i.currency().equals(terms.regularAmount().currency()))throw unavailable();
        requireCash(first,original.paymentIntent(),snapshot.observedAt());if(recovered)requireCash(second,r.failure().paymentIntent(),snapshot.observedAt());else if(!second.cashObjects().isEmpty())throw unavailable();
    }
    private static void requireCash(Invoice i,String reference,Instant observedAt){
        if(i.cashObjects().size()!=1)throw unavailable();var c=i.cashObjects().getFirst();
        if(!c.reference().equals(reference)||c.status()!=dev.genericpayments.platform.payments.domain.PaymentStatus.SUCCEEDED||c.amountMinor()!=i.amountDueMinor()||!c.currency().equals(i.currency())||c.occurredAt().isAfter(observedAt))throw unavailable();
    }
    private static String methodAuthority(OwnedFailedRenewal.Admission a){return "owned-failed-renewal-method:"+a.id();}
    private static String request(OwnedFailedRenewal.Admission a,OwnedFailedRenewal.Failure f,SubscriptionCommandContext c){return SubscriptionInteractionTransaction.request(c,"owned-failed-renewal-method-v1",a.id(),f.billId(),f.billVersion(),f.invoice(),f.allocation(),f.paymentIntent(),f.revision(),f.observationSequence(),"SETUP",true);}
    private static void validateContext(SubscriptionCommandContext c){
        if(c==null||c.actor()==null||c.siteId()==null||c.customerId()==null||c.idempotencyKey()==null||c.idempotencyKey().isBlank()||c.idempotencyKey().length()>255||c.idempotencyKey().codePoints().anyMatch(Character::isISOControl))throw unavailable();
    }
    private static CustomerSessionContext customer(SubscriptionCommandContext c,Instant now){
        if(c==null||!(c.actor() instanceof SubscriptionActor.Customer actor)||actor.session()==null)throw unavailable();
        var s=actor.session();
        if(!s.siteId().equals(c.siteId())||!s.customerId().equals(c.customerId())||!s.origin().equals(c.origin())||!now.isBefore(s.expiresAt()))throw unavailable();
        s.requireScope(CustomerSessionScope.SUBSCRIPTION_MANAGE);return s;
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("OWNED_FAILED_RENEWAL_METHOD_UNAVAILABLE");}
}
