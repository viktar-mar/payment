package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.param.*;
import com.stripe.net.RequestOptions;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeRequestFingerprint;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore;
import dev.genericpayments.platform.subscriptions.domain.BillingRecord;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms;
import dev.genericpayments.platform.subscriptions.domain.ChangeTiming;
import java.time.Instant;
import java.util.*;

/** Read-only verification of an already bound subscription. The caller supplies the original
 * account's verified client and persisted catalog mappings, capturing its local fence before IO.
 * Creation/Setup/CHANGE proof is deliberately not inferred from status or metadata. */
public final class StripeBillingSnapshotMapper {
    private StripeBillingSnapshotMapper() {}
    private static final com.fasterxml.jackson.databind.ObjectMapper NATIVE_JSON=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public record PublishedPrice(SubscriptionPriceTerms terms,String providerPriceReference,String providerProductReference) {}
    public record TrialContext(SubscriptionBillingProofStore.Trial request,dev.genericpayments.platform.provider.application.model.ProviderOperation step,PublishedPrice original,SubscriptionBillingProofStore.Setup requiredSetup){}
    /** Descriptive proof only; the owned adapter reloads this immutable receipt before and after IO. */
    public record OwnedInitial(dev.genericpayments.platform.provider.application.model.OwnedClockSubscriptionReceipt receipt){
        public OwnedInitial {Objects.requireNonNull(receipt);}
    }
    public record Ownership(PaymentAccount account,Subscription subscription,CustomerBinding customer,List<PublishedPrice> prices,TrialContext trial,SubscriptionBillingProofStore proofs,List<dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Proration> prorations,List<dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.ScheduleProof> schedules) {
        public Ownership(PaymentAccount a,Subscription s,CustomerBinding b,List<PublishedPrice> prices){this(a,s,b,prices,null,null,List.of());}
        public Ownership(PaymentAccount a,Subscription s,CustomerBinding b,List<PublishedPrice> prices,TrialContext trial,SubscriptionBillingProofStore proofs){this(a,s,b,prices,trial,proofs,List.of());}
        public Ownership(PaymentAccount a,Subscription s,CustomerBinding b,List<PublishedPrice> prices,TrialContext trial,SubscriptionBillingProofStore proofs,List<dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Proration> prorations){this(a,s,b,prices,trial,proofs,prorations,List.of());}
        public Ownership { prices=List.copyOf(prices);prorations=List.copyOf(prorations);schedules=List.copyOf(schedules); }
    }
    private static final int PAGE_SIZE=10, MAX_PAGES=10, MAX_READS=100;
    public static Snapshot retrieve(StripeClient client,Ownership owner,Instant observedAt) {
        try {return new Read(client,owner,observedAt).snapshot();}
        catch(PaymentApplicationException safe){throw safe;}
        catch(StripeException | RuntimeException malformed){throw mismatch();}
    }
    public static Snapshot retrieveOwned(StripeClient client,Ownership owner,OwnedInitial original,Instant observedAt){
        try {return new Read(client,owner,observedAt,Objects.requireNonNull(original)).snapshot();}
        catch(PaymentApplicationException safe){throw safe;}
        catch(StripeException | RuntimeException malformed){throw mismatch();}
    }
    /** Private first-renewal evidence only; publication authority belongs to the separate consumer. */
    public static MappedObservation retrieveOwnedFirstRenewal(StripeClient client,Ownership owner,
            Admission admission,LaterReady laterReady,Instant observedAt){
        require(laterReady!=null&&laterReady.ack().advance().step()==Step.BOUNDARY);
        return retrieveOwnedFirstRenewal(client,owner,admission,laterReady,observedAt,null);
    }
    static MappedObservation retrieveOwnedFirstRenewal(StripeClient client,Ownership owner,Admission admission,
            LaterReady laterReady,Instant observedAt,Boundary boundary){
        return retrieveOwnedFirstRenewal(client,owner,admission,laterReady,observedAt,boundary,null);
    }
    /** READY2 requires the authenticated persisted READY1 boundary, never reconstructed from UUIDs. */
    static MappedObservation retrieveOwnedFirstRenewal(StripeClient client,Ownership owner,Admission admission,
            LaterReady laterReady,Instant observedAt,Boundary boundary,ReadExecutor executor){
        try{
            var read=new Read(client,owner,observedAt,new OwnedInitial(admission.receipt()));
            read.renewalAdmission=admission;read.executor=executor;
            return read.firstRenewal(laterReady,boundary,RenewalLane.SUCCESS).success();
        }catch(PaymentApplicationException safe){throw safe;}
        catch(StripeException malformed){throw mismatch();}
    }
    record FailedRenewalRead(Snapshot snapshot,String allocation,String paymentIntent) {}
    /** Descriptive cash graph only; supplied Recovery consistency authenticates no durable authority. */
    record OwnedRecoveredRead(Snapshot snapshot,String allocation,String paymentIntent) {}
    static OwnedRecoveredRead retrieveOwnedRecoveredRenewal(StripeClient client,Ownership owner,
            OwnedFailedRenewal.Admission admission,OwnedFailedRenewal.Failure failure,OwnedFailedRenewal.Recovery recovery,
            SubscriptionOperation operation,Instant observedAt,ReadExecutor executor){
        try{
            require(admission!=null&&executor!=null);
            var read=new Read(client,owner,observedAt,new OwnedInitial(admission.firstRenewalAdmission().receipt()));
            read.renewalAdmission=admission.firstRenewalAdmission();read.executor=executor;
            read.requireDebtContext(admission,failure);
            requireOwnedRecovery(owner,admission,failure,recovery,operation,observedAt);
            var mapped=read.ownedDebtGraph(admission,failure,RenewalLane.RECOVERED).success();
            require(mapped instanceof MappedPaid);
            return new OwnedRecoveredRead(((MappedPaid)mapped).snapshot(),recovery.allocation(),recovery.paymentIntent());
        }catch(StripeException|RuntimeException malformed){throw mismatch();}
    }
    private static void requireOwnedRecovery(Ownership owner,OwnedFailedRenewal.Admission admission,OwnedFailedRenewal.Failure failure,
            OwnedFailedRenewal.Recovery recovery,SubscriptionOperation operation,Instant at){
        require(recovery!=null&&operation!=null);var s=owner.subscription();var a=owner.account();
        require(recovery.admissionId().equals(admission.id())&&recovery.billId().equals(failure.billId())
            &&recovery.allocation().equals(failure.allocation())&&recovery.paymentIntent().equals(failure.paymentIntent())
            &&recovery.expectedRevision()==failure.revision()&&recovery.operationId().equals(operation.id())&&recovery.operationVersion()==operation.version()
            &&!recovery.associatedAt().isBefore(failure.recordedAt())&&!at.isBefore(recovery.associatedAt())
            &&operation.kind()==SubscriptionOperation.Kind.RECOVER&&operation.status()==SubscriptionOperation.Status.RESERVED
            &&operation.siteId().equals(s.siteId())&&operation.customerId().equals(s.customerId())&&operation.subscriptionId().equals(s.id())
            &&operation.expectedRevision()==failure.revision()&&operation.policyVersion()==admission.firstRenewalAdmission().policyVersion()
            &&s.providerReference().equals(operation.providerReference())&&operation.safeFailureCode()==null
            &&!operation.createdAt().isBefore(failure.recordedAt())&&!operation.updatedAt().isAfter(recovery.associatedAt())
            &&operation.providerOperationKey().equals("subscription:"+a.id().value()+":"+operation.id()+":RECOVER")
            &&operation.authorityScope().equals(OwnedFailedRenewal.recoveryAuthority(admission))
            &&operation.keyDigest().equals(OwnedFirstRenewal.fingerprint("owned-failed-renewal-recover-key-v1",admission.id()))
            &&operation.fingerprint().equals("v1.recover."+failure.billId()+"."+OwnedFirstRenewal.fingerprint(
                "owned-failed-renewal-recover-v1",admission.id(),failure.billId(),failure.billVersion(),failure.invoice(),failure.allocation(),failure.paymentIntent(),failure.revision())));
    }
    /** Supplied descriptive records only: persistence, readiness and completion authority are external. */
    record OwnedMethodCandidate(SubscriptionOperation operation,SubscriptionBillingProofStore.Setup setup,
            ProviderOperation setupStep,ProviderOperation methodStep) {}
    record OwnedMethodRead(Snapshot snapshot,String setupIntent,String method) {}
    /** Completed original card plus unchanged OPEN debt. This description grants no dispatch authority. */
    static Snapshot retrieveOwnedRecoveryPreparation(StripeClient client,Ownership owner,
            dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.RecoveryPreparationContext original,
            OwnedMethodCandidate candidate,DigestPort digest,Instant observedAt,ReadExecutor executor){
        try{
            require(executor!=null);
            requireOwnedRecoveryPreparation(owner,original,candidate,digest,observedAt);
            var r=original.reservation();var admission=r.admission();
            var read=new Read(client,owner,observedAt,new OwnedInitial(admission.firstRenewalAdmission().receipt()));
            read.renewalAdmission=admission.firstRenewalAdmission();read.executor=executor;
            var failed=read.ownedDebtGraph(admission,r.failure(),RenewalLane.FAILED).failed();
            require(r.failure().allocation().equals(failed.allocation())&&r.failure().paymentIntent().equals(failed.paymentIntent()));
            return readOwnedMethodCard(client,owner,candidate,observedAt,read,failed.snapshot()).snapshot();
        }catch(StripeException|RuntimeException malformed){throw mismatch();}
    }
    /** Supplied-record joins only; caller must independently authenticate/reload every original. */
    static void requireOwnedRecoveryPreparation(Ownership owner,
            dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.RecoveryPreparationContext original,
            OwnedMethodCandidate candidate,DigestPort digest,Instant observedAt){
        require(owner!=null&&original!=null&&candidate!=null&&observedAt!=null);
        var r=original.reservation();var c=original.completion();var recovery=original.recovery();var o=original.setupOperation();
        require(owner.account().equals(original.account())&&owner.subscription().equals(original.subscription())&&owner.customer().equals(original.binding())
            &&r.binding().equals(original.binding())&&r.billingSample().equals(original.billingSample())
            &&candidate.operation().equals(o)&&r.setupOperationId().equals(o.id())&&r.requestFingerprint().equals(o.fingerprint())
            &&o.authorityScope().equals("owned-failed-renewal-method:"+r.admission().id())
            &&r.initialOperationVersion()==0&&c.setupFinalVersion()==2&&o.version()==2
            &&r.id().equals(c.reservationId())&&o.id().equals(c.setupOperationId())&&r.id().equals(recovery.reservationId())
            &&c.revision()==r.failure().revision()&&c.observationSequence()==r.failure().observationSequence()+1
            &&recovery.observationSequence()==c.observationSequence()&&recovery.association().operationVersion()==0&&original.recoveryOperation().version()==0
            &&!c.completedAt().isBefore(r.reservedAt())&&!c.completedAt().isAfter(recovery.association().associatedAt())
            &&!o.updatedAt().isBefore(o.createdAt())&&!o.updatedAt().isAfter(c.completedAt())&&!c.completedAt().isAfter(observedAt)
            &&c.setupStepVersion()==2&&c.methodStepVersion()==2&&candidate.setupStep().version()==2&&candidate.methodStep().version()==2
            &&c.setupStepId().equals(candidate.setupStep().id())&&c.methodStepId().equals(candidate.methodStep().id())
            &&c.setupIntent().equals(candidate.setup().reference())&&c.method().equals(candidate.setup().method())
            &&candidate.setupStep().firstDispatchedAt()!=null&&candidate.methodStep().firstDispatchedAt()!=null
            &&!candidate.setupStep().firstDispatchedAt().isAfter(c.completedAt())&&!candidate.methodStep().firstDispatchedAt().isAfter(c.completedAt()));
        requireOwnedRecovery(owner,r.admission(),r.failure(),recovery.association(),original.recoveryOperation(),observedAt);
        requireOwnedMethodCandidate(owner,r.failure(),candidate,digest,observedAt,r.admission().firstRenewalAdmission().policyVersion(),SubscriptionOperation.Status.SUCCEEDED);
    }
    static OwnedMethodRead retrieveOwnedMethodReady(StripeClient client,Ownership owner,
            OwnedFailedRenewal.Admission admission,OwnedFailedRenewal.Failure failure,OwnedMethodCandidate candidate,
            DigestPort digest,Instant observedAt,ReadExecutor executor){
        try{
            require(admission!=null&&executor!=null);
            var read=new Read(client,owner,observedAt,new OwnedInitial(admission.firstRenewalAdmission().receipt()));
            read.renewalAdmission=admission.firstRenewalAdmission();read.executor=executor;
            read.requireDebtContext(admission,failure);
            requireOwnedMethodCandidate(owner,failure,candidate,digest,observedAt,admission.firstRenewalAdmission().policyVersion(),SubscriptionOperation.Status.PROCESSING);
            var failed=read.ownedDebtGraph(admission,failure,RenewalLane.FAILED).failed();
            require(failure.allocation().equals(failed.allocation())&&failure.paymentIntent().equals(failed.paymentIntent()));
            return readOwnedMethodCard(client,owner,candidate,observedAt,read,failed.snapshot());
        }catch(StripeException|RuntimeException malformed){throw mismatch();}
    }
    private static OwnedMethodRead readOwnedMethodCard(StripeClient client,Ownership owner,OwnedMethodCandidate candidate,
            Instant observedAt,Read read,Snapshot s)throws StripeException{
            var setup=candidate.setup();var operation=candidate.operation();
            require(setup.method().equals(read.remote.getDefaultPaymentMethod()));
            var remote=read.get(()->client.v1().setupIntents().retrieve(setup.reference(),read.options()));
            StripeSubscriptionProviderAdapter.requireSetupRemote(remote,owner.subscription(),operation,owner.customer(),candidate.setupStep());
            require("succeeded".equals(remote.getStatus())&&setup.method().equals(remote.getPaymentMethod())
                &&remote.getCreated()<=observedAt.getEpochSecond()&&remote.getCreated()<=candidate.methodStep().firstDispatchedAt().getEpochSecond());
            var method=read.get(()->client.v1().paymentMethods().retrieve(setup.method(),read.options()));
            require(setup.method().equals(method.getId())&&"payment_method".equals(method.getObject())&&Boolean.FALSE.equals(method.getLivemode())
                &&owner.customer().providerReference().equals(method.getCustomer())&&"card".equals(method.getType())&&method.getCard()!=null);
            return new OwnedMethodRead(new Snapshot(s.subscriptionReference(),s.status(),s.billingPrice(),s.period(),s.trial(),s.cancellationAt(),
                s.invoices(),null,true,0,observedAt,null,List.of(),null,null),setup.reference(),setup.method());
    }
    private static void requireOwnedMethodCandidate(Ownership owner,OwnedFailedRenewal.Failure failure,
            OwnedMethodCandidate candidate,DigestPort digest,Instant at,long policyVersion,SubscriptionOperation.Status status){
        require(candidate!=null&&digest!=null);var o=candidate.operation();var setup=candidate.setup();var a=owner.account();var s=owner.subscription();var b=owner.customer();
        require(o!=null&&setup!=null&&o.kind()==SubscriptionOperation.Kind.SETUP&&o.status()==status
            &&o.siteId().equals(s.siteId())&&o.customerId().equals(s.customerId())&&o.subscriptionId().equals(s.id())
            &&o.expectedRevision()==failure.revision()&&o.policyVersion()==policyVersion&&s.providerReference().equals(o.providerReference())
            &&o.safeFailureCode()==null&&!o.createdAt().isBefore(failure.recordedAt())&&!o.updatedAt().isAfter(at)
            &&o.providerOperationKey().equals("subscription:"+a.id().value()+":"+o.id()+":SETUP")
            &&setup.operation().equals(o.id())&&setup.subscription().equals(s.id())&&setup.account().equals(a.id()));
        ref(setup.reference(),"seti_");ref(setup.method(),"pm_");
        String setupFingerprint=StripeRequestFingerprint.of(digest,a,"SETUP_INTENT_CREATE_V1",o.id().toString(),o.providerOperationKey()+":SETUP",
            o.fingerprint(),Long.toString(o.policyVersion()),s.id().toString(),s.siteId().value().toString(),s.customerId().value().toString(),b.providerReference(),"off_session","card","consent:true");
        String methodFingerprint=StripeRequestFingerprint.of(digest,a,"SUBSCRIPTION_METHOD_V1",o.id().toString(),o.providerOperationKey()+":METHOD",o.fingerprint(),
            s.providerReference(),b.providerReference(),setup.step().toString(),setup.reference(),setup.method(),"consent:true");
        requireMethodStep(a,o,candidate.setupStep(),ProviderOperation.Kind.SETUP_INTENT_CREATE,":SETUP",setupFingerprint,setup.reference(),at);
        requireMethodStep(a,o,candidate.methodStep(),ProviderOperation.Kind.SUBSCRIPTION_UPDATE,":METHOD",methodFingerprint,s.providerReference(),at);
        require(setup.step().equals(candidate.setupStep().id())&&!candidate.setupStep().id().equals(candidate.methodStep().id())
            &&!candidate.methodStep().firstDispatchedAt().isBefore(candidate.setupStep().firstDispatchedAt()));
    }
    private static void requireMethodStep(PaymentAccount a,SubscriptionOperation operation,ProviderOperation step,ProviderOperation.Kind kind,
            String suffix,String fingerprint,String reference,Instant at){
        require(step!=null&&step.accountId().equals(a.id())&&step.externalAccountId().equals(a.externalAccountId())&&step.credentialsRef().equals(a.credentialsRef())
            &&step.kind()==kind&&step.resourceId().equals(operation.id())&&step.operationKey().equals(operation.providerOperationKey()+suffix)
            &&step.fingerprint().equals(fingerprint)&&step.status()==ProviderOperation.Status.ACKNOWLEDGED&&reference.equals(step.providerReference())
            &&step.firstDispatchedAt()!=null&&!step.firstDispatchedAt().isBefore(operation.createdAt())&&!step.firstDispatchedAt().isAfter(at)
            &&step.retryBefore()!=null&&step.retryBefore().isAfter(step.firstDispatchedAt())&&step.leaseOwner()==null&&step.leaseExpiresAt()==null);
    }
    private enum RenewalLane { SUCCESS, FAILED, RECOVERED }
    private record RenewalResult(MappedObservation success,FailedRenewalRead failed) {}
    static FailedRenewalRead retrieveOwnedFailedRenewal(StripeClient client,Ownership owner,
            dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.Admission failed,Instant observedAt,ReadExecutor executor){
        try {
            require(failed!=null&&executor!=null&&failed.ready().ack().advance().step()==Step.FINALIZATION);
            var read=new Read(client,owner,observedAt,new OwnedInitial(failed.firstRenewalAdmission().receipt()));
            read.renewalAdmission=failed.firstRenewalAdmission();read.executor=executor;
            return read.firstRenewal(failed.ready(),failed.boundary(),RenewalLane.FAILED).failed();
        }catch(PaymentApplicationException safe){throw safe;}
        catch(StripeException | RuntimeException malformed){throw mismatch();}
    }
    /** Exact credited native line, funded using the same complete normalization as ordinary reads. */
    static void requireCreditedFunding(StripeClient client,Ownership owner,com.stripe.model.Subscription remote,
            dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Quote quote,Instant at){
        var linked=quote.lines().stream().filter(l->l.creditedInvoice()!=null).toList();if(linked.isEmpty())return;
        require(linked.size()==1);var credit=linked.getFirst();require(credit.creditedLines().size()==1);
        try{
            var read=new Read(client,owner,at);for(var p:owner.prices())require(read.prices.put(p.providerPriceReference(),p)==null);
            ref(credit.creditedInvoice(),"in_");ref(credit.creditedLines().getFirst(),"il_");
            var invoice=read.get(()->client.v1().invoices().retrieve(credit.creditedInvoice()));require(credit.creditedInvoice().equals(invoice.getId())&&StripeBillingMutationAdapter.noAdjustments(invoice));
            var normalized=read.invoice(invoice,remote,remote.getItems().getData().getFirst(),null);
            require(normalized.price().equals(quote.source())&&!normalized.period().start().isAfter(quote.anchor())&&!normalized.period().end().isBefore(quote.paidThrough())
                &&(normalized.settlement()==SettlementKind.CASH||normalized.settlement()==SettlementKind.ZERO&&normalized.kind()==BillingRecord.Kind.PRORATION));
            var lines=read.sourceLines.get(invoice.getId());require(lines!=null);var literal=lines.stream().filter(l->credit.creditedLines().getFirst().equals(l.getId())).toList();require(literal.size()==1);var line=literal.getFirst();
            require(invoice.getId().equals(line.getInvoice())&&quote.subscriptionReference().equals(line.getSubscription())&&line.getAmount()!=null&&line.getAmount()>0
                &&(line.getTaxes()==null||line.getTaxes().isEmpty())&&(line.getDiscounts()==null||line.getDiscounts().isEmpty())
                &&line.getParent()!=null&&line.getParent().getSubscriptionItemDetails()!=null&&quote.subscriptionReference().equals(line.getParent().getSubscriptionItemDetails().getSubscription())
                &&quote.item().equals(line.getParent().getSubscriptionItemDetails().getSubscriptionItem())&&line.getPricing()!=null&&line.getPricing().getPriceDetails()!=null
                &&quote.sourcePrice().equals(line.getPricing().getPriceDetails().getPrice())&&quote.product().equals(line.getPricing().getPriceDetails().getProduct())
                &&line.getPeriod()!=null&&line.getPeriod().getStart()!=null&&line.getPeriod().getEnd()!=null&&line.getPeriod().getStart()<=quote.anchor().getEpochSecond()&&line.getPeriod().getEnd()>=quote.paidThrough().getEpochSecond());
        }catch(PaymentApplicationException safe){throw safe;}catch(StripeException|RuntimeException malformed){throw mismatch();}
    }
    /** Ephemeral, verified original Billing action; never serialize as a normal snapshot. */
    public record PaymentAction(dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.Binding binding,String clientSecret,Long amount,String currency){
        public PaymentAction(dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.Binding binding,String clientSecret){this(binding,clientSecret,null,null);}
        @Override public String toString(){return "PaymentAction[redacted]";}
    }
    public static PaymentAction retrievePaymentAction(StripeClient client,StripeBillingOwnership.PaymentProof proof,Instant at){
        try{
            var r=new Read(client,new Ownership(proof.account(),proof.subscription(),proof.customer(),proof.prices(),null,null,proof.history()),at);
            var snapshot=r.snapshot();
            List<SubscriptionProviderModels.Invoice> selected;
            if(proof instanceof StripeBillingOwnership.OriginalChange change){
                var q=change.result().quote();var original=change.result().result();
                require(q.source().equals(snapshot.billingPrice())&&q.period().equals(snapshot.period())&&snapshot.status()==Subscription.Status.ACTIVE&&q.entitled().equals(proof.subscription().entitledPrice()));
                var item=r.remote.getItems().getData().getFirst();var pending=r.remote.getPendingUpdate();
                require(q.item().equals(item.getId())&&pending!=null&&original.pendingExpiry()!=null&&Objects.equals(pending.getExpiresAt(),original.pendingExpiry())&&at.getEpochSecond()<original.pendingExpiry()&&pending.getSubscriptionItems()!=null&&pending.getSubscriptionItems().size()==1);
                var target=pending.getSubscriptionItems().getFirst();require(q.item().equals(target.getId())&&Objects.equals(1L,target.getQuantity())&&target.getPrice()!=null&&q.targetPrice().equals(target.getPrice().getId()));
                selected=snapshot.invoices().stream().filter(i->i.reference().equals(original.invoice())&&i.kind()==BillingRecord.Kind.PRORATION&&i.price().equals(q.target())&&Objects.equals(i.previousPrice(),q.source())&&i.period().equals(new Subscription.Period(q.anchor(),q.paidThrough()))).toList();
            }else if(proof instanceof StripeBillingOwnership.OriginalResume resume){
                StripeBillingOwnership.requireCurrentRemote(r.remote,proof.subscription(),proof.customer(),proof.price());
                require(Set.of("active","paused","incomplete","past_due").contains(r.remote.getStatus()));
                selected=snapshot.invoices().stream().filter(i->i.reference().equals(resume.result().invoice())&&i.kind()==BillingRecord.Kind.RENEWAL&&i.period().equals(resume.result().period())).toList();
            }else if(proof instanceof StripeBillingOwnership.OriginalRecovery recovery){
                StripeBillingOwnership.requireCurrentRemote(r.remote,proof.subscription(),proof.customer(),proof.price());
                selected=snapshot.invoices().stream().filter(i->exactRecoveryInvoice(i,recovery.bill())).toList();
            }else{
                StripeBillingOwnership.requireRemote(r.remote,proof.account(),proof.subscription(),proof.operation(),proof.customer(),proof.price(),proof.step());
                require("incomplete".equals(r.remote.getStatus()));
                selected=snapshot.invoices().stream().filter(i->i.kind()==BillingRecord.Kind.INITIAL).toList();
            }
            require(selected.size()==1);
            var i=selected.getFirst();require(i.settlement()==SettlementKind.OPEN&&i.price().equals(proof.price().terms())&&(proof instanceof StripeBillingOwnership.OriginalChange||proof instanceof StripeBillingOwnership.OriginalRecovery||i.period().equals(snapshot.period())));
            return paymentAction(r,proof,i);
        }catch(PaymentApplicationException safe){throw safe;}catch(StripeException|RuntimeException unsafe){throw mismatch();}
    }
    private static PaymentAction paymentAction(Read r,StripeBillingOwnership.PaymentProof proof,SubscriptionProviderModels.Invoice i) throws StripeException {
            if(proof instanceof StripeBillingOwnership.OriginalResume||proof instanceof StripeBillingOwnership.OriginalChange){
                var invoice=r.sourceInvoices.stream().filter(v->v.getId().equals(i.reference())).findFirst().orElseThrow(StripeBillingSnapshotMapper::mismatch);
                require(StripeBillingOwnership.inWindow(invoice.getCreated(),proof.step()));
            }
            var graph=paymentGraph(r,i,proof.customer().providerReference());var a=graph.allocation();var pi=graph.intent();var secret=graph.secret();
            String canonical=dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.canonical(proof.step().fingerprint(),proof.operation().fingerprint(),i.period().start(),i.period().end());
            if(proof instanceof StripeBillingOwnership.OriginalChange change)canonical=dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.canonical("CHANGE_V1",canonical,change.result().quote().toString(),change.result().result().creditLine(),change.result().result().debitLine());
            if(proof instanceof StripeBillingOwnership.OriginalRecovery recovery){
                canonical=dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.canonical("RECOVERY_V1",canonical,recovery.bill().id(),recovery.origin().operation(),recovery.origin().step());
                var saved=recovery.result();if(saved!=null)require(Objects.equals(saved.allocation(),a.getId())&&Objects.equals(saved.intent(),pi.getId()));
            }
            var binding=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.Binding(proof.step().id(),proof.operation().providerOperationKey(),StripeBillingGrantTokens.hash(canonical),proof.subscription().providerReference(),proof.customer().providerReference(),i.reference(),a.getId(),pi.getId(),proof instanceof StripeBillingOwnership.OriginalRecovery?"recovery-v1":proof instanceof StripeBillingOwnership.OriginalChange?"change-v1":proof instanceof StripeBillingOwnership.OriginalResume?"resume-v1":"payment-v1",null);
            return new PaymentAction(binding,secret,pi.getAmount(),pi.getCurrency());
    }
    /** Closed guard provenance for one owned action read; never inferred from exceptions. */
    enum OwnedActionGuard { LINE_ASSOCIATION, INVOICE_SETTLEMENT, PAYMENT_ALLOCATION }
    /** A reached typed null is unavailable; this says nothing about provider wire optionality. */
    enum AllocationReason {
        STATUS_UNAVAILABLE, STATUS_NOT_OPEN, DEFAULT_UNAVAILABLE, DEFAULT_NOT_TRUE,
        AMOUNT_REQUESTED_UNAVAILABLE, AMOUNT_REQUESTED_MISMATCH,
        AMOUNT_PAID_UNAVAILABLE, AMOUNT_PAID_NONZERO, PAYMENT_UNAVAILABLE,
        TYPE_UNAVAILABLE, TYPE_UNEXPECTED
    }
    enum SettlementReason {
        PRORATION_FLAG, PRORATION_KIND, COLLECTION_METHOD, PAID_OFF_STRIPE,
        OVERPAID, STARTING_BALANCE, ENDING_BALANCE, PRE_CREDIT_NOTES,
        POST_CREDIT_NOTES, INVOICE_DISCOUNTS, INVOICE_DISCOUNT_AMOUNTS, INVOICE_PRETAX_CREDITS,
        LINE_DISCOUNT_AMOUNTS, LINE_PRETAX_CREDITS, TOTAL, SUBTOTAL,
        LINE_AMOUNT, OPEN_DUE_POSITIVE, OPEN_DUE_CATALOG, OPEN_PAID,
        OPEN_REMAINING, OPEN_ALLOCATION_STATUS, OPEN_ALLOCATION_PAYMENT, OPEN_ALLOCATION_TYPE,
        OPEN_ALLOCATION_INTENT, SNAPSHOT_UNSUPPORTED, ACTION_OPEN_CASH, ACTION_OPEN
    }
    /** Records only the first false expression, without throwing or retaining provider values. */
    private static final class SettlementTrace {
        private final boolean enabled;
        private SettlementReason first;
        SettlementTrace(boolean enabled){this.enabled=enabled;}
        boolean check(boolean valid,SettlementReason reason){if(enabled&&!valid&&first==null)first=reason;return valid;}
        // All six getter arguments are evaluated before this helper, as with the original zeros call.
        boolean plainAmounts(Long paidOffStripe,Long overpaid,Long starting,Long ending,Long preCredit,Long postCredit,boolean acceptsUnavailableOffStripe,boolean acceptsUnfinalizedEnding){
            // Application evidence policy: unavailable is unknown, not zero or proof of no outside funding.
            return check(Objects.equals(paidOffStripe,0L)||acceptsUnavailableOffStripe&&paidOffStripe==null,SettlementReason.PAID_OFF_STRIPE)
                &&check(Objects.equals(overpaid,0L),SettlementReason.OVERPAID)
                &&check(Objects.equals(starting,0L),SettlementReason.STARTING_BALANCE)
                &&check(Objects.equals(ending,0L)||acceptsUnfinalizedEnding&&ending==null,SettlementReason.ENDING_BALANCE)
                &&check(Objects.equals(preCredit,0L),SettlementReason.PRE_CREDIT_NOTES)
                &&check(Objects.equals(postCredit,0L),SettlementReason.POST_CREDIT_NOTES);
        }
    }
    static final class OwnedActionFailure {
        private OwnedActionGuard guard;
        private SettlementReason settlement;
        private AllocationReason allocation;
        SettlementReason settlement(){return settlement;}
        AllocationReason allocation(){return allocation;}
        OwnedActionGuard guard(){return guard;}
        private void require(boolean valid,OwnedActionGuard boundary){
            require(valid,boundary,null);
        }
        private void require(boolean valid,OwnedActionGuard boundary,SettlementReason reason){
            if(!valid){if(guard==null){guard=boundary;settlement=reason;}throw mismatch();}
        }
    }
    /** Typed original-only entry. This descriptive receipt must be independently authorized by the caller. */
    public static PaymentAction retrieveOwnedPaymentAction(StripeClient client,Ownership owner,OwnedInitial original,Instant at){
        return retrieveOwnedPaymentAction(client,owner,original,at,null);
    }
    static PaymentAction retrieveOwnedPaymentAction(StripeClient client,Ownership owner,OwnedInitial original,Instant at,OwnedActionFailure failure){
        try{
            var r=new Read(client,owner,at,Objects.requireNonNull(original),failure);var snapshot=r.snapshot();
            var receipt=original.receipt();var intent=receipt.intent();
            require(snapshot.status()==Subscription.Status.INCOMPLETE&&snapshot.invoices().size()==1);
            var invoice=snapshot.invoices().getFirst();
            require(invoice.kind()==BillingRecord.Kind.INITIAL);
            r.requireSettlement(invoice.settlement()==SettlementKind.OPEN,invoice.settlement()==SettlementKind.CASH?SettlementReason.ACTION_OPEN_CASH:SettlementReason.ACTION_OPEN);
            require(invoice.price().equals(intent.terms())
                &&invoice.period().equals(snapshot.period())&&invoice.period().start().getEpochSecond()==receipt.periodStart()&&invoice.period().end().getEpochSecond()==receipt.periodEnd());
            var graph=paymentGraph(r,invoice,intent.customerReference());var pi=graph.intent();
            require("requires_payment_method".equals(pi.getStatus())&&"automatic".equals(pi.getConfirmationMethod())&&pi.getLatestCharge()==null&&graph.secret().length()<=512);
            var binding=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.Binding(receipt.providerOperation(),intent.operationKey(),
                dev.genericpayments.platform.provider.application.model.OwnedInitialConfirmationProof.hash(receipt,invoice.reference(),graph.allocation().getId(),pi.getId()),
                receipt.reference(),intent.customerReference(),invoice.reference(),graph.allocation().getId(),pi.getId(),"owned-initial-v1",null,intent.id());
            return new PaymentAction(binding,graph.secret(),pi.getAmount(),pi.getCurrency());
        }catch(PaymentApplicationException safe){throw safe;}catch(StripeException|RuntimeException unsafe){throw mismatch();}
    }
    private record PaymentGraph(InvoicePayment allocation,PaymentIntent intent,String secret){
        @Override public String toString(){return "PaymentGraph[redacted]";}
    }
    private static PaymentGraph paymentGraph(Read r,SubscriptionProviderModels.Invoice i,String customer)throws StripeException{
            var client=r.client;
            var invoice=r.sourceInvoices.stream().filter(v->v.getId().equals(i.reference())).findFirst().orElseThrow(StripeBillingSnapshotMapper::mismatch);
            require("open".equals(invoice.getStatus()));

            var allocations=r.sourceAllocations.get(i.reference());require(allocations!=null&&allocations.size()==1);
            var a=allocations.getFirst();long due=i.amountDueMinor();
            // Preserve the original short circuit order, including the second payment getter.
            var status=a.getStatus();r.requireAllocation("open".equals(status),status==null?AllocationReason.STATUS_UNAVAILABLE:AllocationReason.STATUS_NOT_OPEN);
            var isDefault=a.getIsDefault();r.requireAllocation(Boolean.TRUE.equals(isDefault),isDefault==null?AllocationReason.DEFAULT_UNAVAILABLE:AllocationReason.DEFAULT_NOT_TRUE);
            var requested=a.getAmountRequested();r.requireAllocation(Objects.equals(due,requested),requested==null?AllocationReason.AMOUNT_REQUESTED_UNAVAILABLE:AllocationReason.AMOUNT_REQUESTED_MISMATCH);
            // Stripe leaves allocation paid unfinalized until paid; only the proved owned initial graph admits null.
            // Null remains unknown, never numeric zero or the full paid amount required by CASH.
            var paid=a.getAmountPaid();r.requireAllocation(zeros(paid)||paid==null&&r.original!=null&&i.kind()==BillingRecord.Kind.INITIAL&&due>0,paid==null?AllocationReason.AMOUNT_PAID_UNAVAILABLE:AllocationReason.AMOUNT_PAID_NONZERO);
            var payment=a.getPayment();r.requireAllocation(payment!=null,AllocationReason.PAYMENT_UNAVAILABLE);
            var type=a.getPayment().getType();r.requireAllocation("payment_intent".equals(type),type==null?AllocationReason.TYPE_UNAVAILABLE:AllocationReason.TYPE_UNEXPECTED);
            ref(a.getPayment().getPaymentIntent(),"pi_");var pi=r.get(()->client.v1().paymentIntents().retrieve(a.getPayment().getPaymentIntent()));
            require(a.getPayment().getPaymentIntent().equals(pi.getId())&&"payment_intent".equals(pi.getObject())&&Boolean.FALSE.equals(pi.getLivemode())&&customer.equals(pi.getCustomer())
                &&invoice.getCurrency().equals(pi.getCurrency())&&Objects.equals(due,pi.getAmount())&&zeros(pi.getAmountReceived())&&"automatic".equals(pi.getCaptureMethod())&&List.of("card").equals(pi.getPaymentMethodTypes())
                &&Set.of("requires_payment_method","requires_action").contains(pi.getStatus()));
            // Owned initial proof never enters the ordinary causal-decline Charge branch.
            if(r.original!=null)require("requires_payment_method".equals(pi.getStatus())&&"automatic".equals(pi.getConfirmationMethod())&&pi.getLatestCharge()==null);
            if(pi.getLatestCharge()!=null){
                ref(pi.getLatestCharge(),"ch_");var ch=r.get(()->client.v1().charges().retrieve(pi.getLatestCharge()));
                require(pi.getLatestCharge().equals(ch.getId())&&"charge".equals(ch.getObject())&&Boolean.FALSE.equals(ch.getLivemode())&&pi.getId().equals(ch.getPaymentIntent())&&customer.equals(ch.getCustomer())&&pi.getCurrency().equals(ch.getCurrency())
                    &&Objects.equals(due,ch.getAmount())&&zeros(ch.getAmountCaptured())&&Boolean.FALSE.equals(ch.getPaid())&&Boolean.FALSE.equals(ch.getCaptured())&&"failed".equals(ch.getStatus())&&ch.getPaymentMethodDetails()!=null&&"card".equals(ch.getPaymentMethodDetails().getType()));
            }
            var reverse=r.pages(after->client.v1().invoicePayments().list(InvoicePaymentListParams.builder().setPayment(InvoicePaymentListParams.Payment.builder().setType(InvoicePaymentListParams.Payment.Type.PAYMENT_INTENT).setPaymentIntent(pi.getId()).build()).setLimit((long)PAGE_SIZE).setStartingAfter(after).build()));
            require(reverse.size()==1&&a.equals(reverse.getFirst()));
            String secret=pi.getClientSecret();require(secret!=null&&secret.startsWith(pi.getId()+"_secret_")&&secret.length()<=1024&&secret.matches("[A-Za-z0-9_-]+"));
            if(invoice.getConfirmationSecret()!=null)require("payment_intent".equals(invoice.getConfirmationSecret().getType())&&secret.equals(invoice.getConfirmationSecret().getClientSecret()));
            return new PaymentGraph(a,pi,secret);
    }
    public record RecoveryRead(Snapshot snapshot,String allocation,String intent,String method,boolean firstPay) {}
    public static RecoveryRead retrieveRecovery(StripeClient client,StripeBillingOwnership.OriginalRecovery proof,Instant at){
        try{
            var r=new Read(client,new Ownership(proof.account(),proof.subscription(),proof.customer(),proof.prices(),null,null,proof.history()),at);var snapshot=r.snapshot();
            StripeBillingOwnership.requireCurrentRemote(r.remote,proof.subscription(),proof.customer(),proof.price());
            require(r.remote.getSchedule()==null&&r.remote.getPendingUpdate()==null&&Set.of("active","past_due","unpaid","incomplete").contains(r.remote.getStatus()));
            var selected=snapshot.invoices().stream().filter(i->exactRecoveryInvoice(i,proof.bill())).toList();require(selected.size()==1);var i=selected.getFirst();
            if(i.settlement()==SettlementKind.CASH)return new RecoveryRead(snapshot,null,null,null,false);
            require(i.settlement()==SettlementKind.OPEN);var allocations=r.sourceAllocations.get(i.reference());require(allocations!=null);
            if(allocations.size()==1){var action=paymentAction(r,proof,i);return new RecoveryRead(snapshot,action.binding().allocationReference(),action.binding().intentReference(),null,false);}
            require(allocations.isEmpty());var invoice=r.sourceInvoices.stream().filter(v->v.getId().equals(i.reference())).findFirst().orElseThrow(StripeBillingSnapshotMapper::mismatch);
            boolean first=Boolean.FALSE.equals(invoice.getAttempted())&&Objects.equals(0L,invoice.getAttemptCount())&&Boolean.FALSE.equals(invoice.getAutoAdvance())&&invoice.getNextPaymentAttempt()==null
                &&r.explicitNoNextAttempt.contains(invoice.getId())
                &&invoice.getConfirmationSecret()==null&&StripeBillingMutationAdapter.noAdjustments(invoice)&&snapshot.paymentMethodReady()
                &&Objects.equals(invoice.getAmountPaid(),0L)&&Objects.equals(invoice.getAmountRemaining(),proof.bill().amountDueMinor());
            return new RecoveryRead(snapshot,null,null,first?r.remote.getDefaultPaymentMethod():null,first);
        }catch(PaymentApplicationException safe){throw safe;}catch(StripeException|RuntimeException unsafe){throw mismatch();}
    }
    private static boolean exactRecoveryInvoice(SubscriptionProviderModels.Invoice invoice,BillingRecord bill){return invoice.reference().equals(bill.providerInvoiceReference())&&invoice.kind()==bill.kind()&&invoice.price().equals(bill.billedPrice())&&Objects.equals(invoice.previousPrice(),bill.previousPrice())&&invoice.period().equals(bill.period())&&invoice.amountDueMinor()==bill.amountDueMinor();}
    @FunctionalInterface interface Call<T>{T run() throws StripeException;}
    enum RenewalCheckpoint { CUSTOMER,SUBSCRIPTION,INVOICES,INITIAL_INVOICE,RENEWAL_INVOICE }
    interface ReadExecutor {
        <T>T execute(Call<T> call);
        RequestOptions options();
        default void checkpoint(RenewalCheckpoint checkpoint){}
    }
    @FunctionalInterface private interface Page<T extends HasId>{StripeCollection<T> read(String after) throws StripeException;}
    private static final class Read {
        final StripeClient client;final Ownership owner;final Instant at;final OwnedInitial original;final OwnedActionFailure actionFailure;
        final Map<String,PublishedPrice> prices=new HashMap<>();final Set<String> cashIds=new HashSet<>();
        com.stripe.model.Subscription remote;List<com.stripe.model.Invoice> sourceInvoices;
        final Map<String,List<InvoicePayment>> sourceAllocations=new HashMap<>();
        final Map<String,List<InvoiceLineItem>> sourceLines=new HashMap<>();
        final Map<String,String> renewalFlatItems=new HashMap<>();
        final Map<SubscriptionProviderModels.Invoice,SettlementReason> settlementReasons=new IdentityHashMap<>();
        final Set<String> explicitNoNextAttempt=new HashSet<>();
        int requests;
        Admission renewalAdmission;
        OwnedFailedRenewal.Failure debtFailure;
        boolean recoveredDebt;
        ReadExecutor executor;
        TrialEvidence trialEvidence;
        Read(StripeClient client,Ownership owner,Instant at){this(client,owner,at,null);}
        Read(StripeClient client,Ownership owner,Instant at,OwnedInitial original){this(client,owner,at,original,null);}
        Read(StripeClient client,Ownership owner,Instant at,OwnedInitial original,OwnedActionFailure actionFailure){this.client=Objects.requireNonNull(client);this.owner=Objects.requireNonNull(owner);this.at=Objects.requireNonNull(at);this.original=original;this.actionFailure=actionFailure;}
        void checkpoint(RenewalCheckpoint value){if(executor!=null)executor.checkpoint(value);}
        void requireOwned(boolean valid,OwnedActionGuard boundary){if(actionFailure==null)require(valid);else actionFailure.require(valid,boundary);}
        private void requireAllocation(boolean valid,AllocationReason reason){
            if(!valid){
                if(actionFailure!=null&&actionFailure.guard==null)actionFailure.allocation=reason;
                requireOwned(false,OwnedActionGuard.PAYMENT_ALLOCATION);
            }
        }
        void requireSettlement(boolean valid,SettlementReason reason){if(actionFailure==null)require(valid);else actionFailure.require(valid,OwnedActionGuard.INVOICE_SETTLEMENT,reason);}
        boolean clockMatches(String clock){return Objects.equals(clock,original==null?null:original.receipt().intent().clock().reference());}

        <T> T get(Call<T> call) throws StripeException {require(++requests<=MAX_READS);return Objects.requireNonNull(executor==null?call.run():executor.execute(call));}
        RequestOptions options(){return executor==null?null:executor.options();}
        <T extends HasId> List<T> pages(Page<T> call) throws StripeException {
            return pages(call,page->{});
        }
        <T extends HasId> List<T> pages(Page<T> call,java.util.function.Consumer<StripeCollection<T>> acceptedPage) throws StripeException {
            var all=new ArrayList<T>();var ids=new HashSet<String>();String cursor=null;
            for(int p=0;p<MAX_PAGES;p++){
                String after=cursor;var page=get(()->call.read(after));
                require("list".equals(page.getObject())&&page.getData()!=null&&page.getHasMore()!=null&&page.getData().size()<=PAGE_SIZE);
                acceptedPage.accept(page);
                for(var item:page.getData()){require(item!=null&&item.getId()!=null&&ids.add(item.getId()));all.add(item);}
                if(!page.getHasMore())return List.copyOf(all);
                require(!page.getData().isEmpty());cursor=page.getData().getLast().getId();
            }
            throw mismatch();
        }
        /** Validates only supplied joins against the actual debt projection; this authenticates no durable row or fence. */
        void requireDebtContext(OwnedFailedRenewal.Admission admission,OwnedFailedRenewal.Failure failure){
            require(admission!=null&&failure!=null&&executor!=null&&failure.admissionId().equals(admission.id())
                &&failure.invoice().equals(admission.boundary().renewalInvoice())&&failure.period().equals(admission.boundary().period())
                &&!failure.recordedAt().isBefore(admission.admittedAt())&&!at.isBefore(failure.recordedAt()));
            var s=owner.subscription();var debt=s.debt();var receipt=admission.firstRenewalAdmission().receipt();
            require(s.billingStatus()==Subscription.Status.PAST_DUE&&s.revision()==failure.revision()&&debt!=null
                &&debt.billingRecordId().equals(failure.billId())&&debt.unpaidPeriod().equals(failure.period())&&Objects.equals(debt.graceDeadline(),failure.graceDeadline())
                &&s.currentPeriod().equals(failure.period())&&s.paidThrough().equals(Instant.ofEpochSecond(receipt.periodEnd()))&&s.terminalReason()==null);
        }
        /** Shared complete invoice/cash normalization, without fabricating an ACTIVE local owner. */
        RenewalResult ownedDebtGraph(OwnedFailedRenewal.Admission admission,OwnedFailedRenewal.Failure failure,RenewalLane lane)throws StripeException{
            requireDebtContext(admission,failure);debtFailure=failure;recoveredDebt=lane==RenewalLane.RECOVERED;
            return firstRenewal(admission.ready(),admission.boundary(),lane);
        }
        RenewalResult firstRenewal(LaterReady ready,Boundary saved,RenewalLane lane) throws StripeException {
            var admission=renewalAdmission;var receipt=admission.receipt();var intent=receipt.intent();var clock=intent.clock();var scope=clock.owner();
            var a=owner.account();var s=owner.subscription();var b=owner.customer();
            require(ready!=null&&ready.admissionId().equals(admission.id())&&at!=null&&!at.isBefore(ready.observedAt()));
            long target=ready.ack().advance().step()==Step.BOUNDARY?receipt.periodEnd():Math.addExact(receipt.periodEnd(),3600);
            require(ready.ack().advance().target()==target&&ready.observation().frozenTime()==target
                &&ready.observation().reference().equals(clock.reference())&&ready.observation().name().equals(scope.name())
                &&ready.observation().created()==clock.providerCreated()&&ready.observation().deletesAfter()==clock.providerDeletesAfter());
            require(a!=null&&s!=null&&b!=null&&a.provider()==PaymentProviderType.STRIPE&&a.mode()==PaymentAccountMode.SANDBOX&&a.status()==PaymentAccountStatus.ACTIVE
                &&a.id().equals(scope.account())&&a.externalAccountId().equals(scope.externalAccount())&&a.credentialsRef().equals(scope.credentialsRef())
                &&s.paymentAccountId().equals(a.id())&&s.id().equals(intent.subscription())&&s.siteId().equals(scope.site())&&s.customerId().equals(scope.customer())
                &&s.billingPrice().equals(intent.terms())&&intent.terms().equals(s.entitledPrice())
                &&s.providerReference().equals(receipt.reference())
                &&(debtFailure!=null||s.billingStatus()==Subscription.Status.ACTIVE&&s.revision()==admission.subscriptionRevision()
                    &&s.currentPeriod().equals(period(receipt.periodStart(),receipt.periodEnd()))&&s.paidThrough().equals(Instant.ofEpochSecond(receipt.periodEnd()))&&s.debt()==null)
                &&s.trial()==null&&s.cancellation()==null&&s.pendingChange()==null
                &&b.accountId().equals(a.id())&&b.siteId().equals(scope.site())&&b.customerId().equals(scope.customer())&&b.id().equals(intent.customerBinding())
                &&b.creationOperationId().equals(intent.customerCreationOperation())&&b.providerReference().equals(intent.customerReference())
                &&owner.trial()==null&&owner.proofs()==null&&owner.prorations().isEmpty()&&owner.schedules().isEmpty()
                &&owner.prices().equals(List.of(new PublishedPrice(intent.terms(),intent.priceReference(),intent.productReference()))));
            var mapped=owner.prices().getFirst();prices.put(mapped.providerPriceReference(),mapped);
            checkpoint(RenewalCheckpoint.CUSTOMER);var customer=get(()->client.v1().customers().retrieve(b.providerReference(),options()));
            require(b.providerReference().equals(customer.getId())&&"customer".equals(customer.getObject())&&Boolean.FALSE.equals(customer.getLivemode())
                &&!Boolean.TRUE.equals(customer.getDeleted())&&clockMatches(customer.getTestClock()));
            checkpoint(RenewalCheckpoint.SUBSCRIPTION);remote=get(()->client.v1().subscriptions().retrieve(receipt.reference(),options()));
            require(receipt.reference().equals(remote.getId())&&"subscription".equals(remote.getObject())&&Boolean.FALSE.equals(remote.getLivemode())
                &&Objects.equals(receipt.providerCreated(),remote.getCreated())&&b.providerReference().equals(remote.getCustomer())&&clockMatches(remote.getTestClock())
                &&(lane==RenewalLane.FAILED?"past_due":"active").equals(remote.getStatus())&&remote.getPauseCollection()==null&&"charge_automatically".equals(remote.getCollectionMethod())
                &&remote.getBillingMode()!=null&&"classic".equals(remote.getBillingMode().getType())
                &&remote.getPaymentSettings()!=null&&List.of("card").equals(remote.getPaymentSettings().getPaymentMethodTypes())
                &&"on_subscription".equals(remote.getPaymentSettings().getSaveDefaultPaymentMethod())
                &&(remote.getAutomaticTax()==null||!Boolean.TRUE.equals(remote.getAutomaticTax().getEnabled()))
                &&(remote.getDefaultTaxRates()==null||remote.getDefaultTaxRates().isEmpty())
                &&remote.getMetadata()!=null&&remote.getMetadata().entrySet().containsAll(StripeBillingOwnership.metadata(intent).entrySet())
                &&remote.getTrialStart()==null&&remote.getTrialEnd()==null&&remote.getSchedule()==null&&remote.getPendingUpdate()==null
                &&remote.getCancelAt()==null&&Boolean.FALSE.equals(remote.getCancelAtPeriodEnd())&&remote.getCanceledAt()==null&&remote.getEndedAt()==null);
            var items=remote.getItems();require(items!=null&&Boolean.FALSE.equals(items.getHasMore())&&items.getData()!=null&&items.getData().size()==1);
            var item=items.getData().getFirst();require(receipt.itemReference().equals(item.getId())&&"subscription_item".equals(item.getObject())
                &&receipt.reference().equals(item.getSubscription())&&Objects.equals(1L,item.getQuantity())&&!Boolean.TRUE.equals(item.getDeleted())
                &&(item.getTaxRates()==null||item.getTaxRates().isEmpty()));
            require(item.getPrice()!=null&&intent.priceReference().equals(item.getPrice().getId()));validatePrice(item.getPrice(),mapped);currency(remote.getCurrency(),intent.terms());
            var period=period(item.getCurrentPeriodStart(),item.getCurrentPeriodEnd());
            require(period.start().equals(Instant.ofEpochSecond(receipt.periodEnd()))&&!Instant.ofEpochSecond(target).isBefore(period.start())
                &&Instant.ofEpochSecond(target).isBefore(period.end())&&Instant.ofEpochSecond(Math.addExact(receipt.periodEnd(),3600)).isBefore(period.end()));
            if(ready.ack().advance().step()==Step.FINALIZATION){
                require(saved!=null&&saved.admissionId().equals(admission.id())&&saved.id().equals(ready.ack().advance().boundary())
                    &&saved.ready().id().equals(ready.ack().advance().predecessorReady())&&saved.item().equals(item.getId())&&saved.period().equals(period));
            }else require(saved==null);
            checkpoint(RenewalCheckpoint.INVOICES);sourceInvoices=pages(after->client.v1().invoices().list(InvoiceListParams.builder().setSubscription(remote.getId()).setLimit((long)PAGE_SIZE).setStartingAfter(after).addExpand("data.amount_paid_off_stripe").build(),options()));
            require(sourceInvoices.size()==2);
            var historical=sourceInvoices.stream().filter(i->admission.invoice().equals(i.getId())&&"subscription_create".equals(i.getBillingReason())).toList();
            var renewals=sourceInvoices.stream().filter(i->!admission.invoice().equals(i.getId())&&"subscription_cycle".equals(i.getBillingReason())).toList();
            require(historical.size()==1&&renewals.size()==1);var renewal=renewals.getFirst();
            if(lane==RenewalLane.RECOVERED)require(saved!=null&&saved.renewalInvoice().equals(renewal.getId()));
            require(Set.of("paid","draft","open").contains(renewal.getStatus()));
            checkpoint(RenewalCheckpoint.INITIAL_INVOICE);var initial=invoice(historical.getFirst(),remote,item,null);
            require(initial.kind()==BillingRecord.Kind.INITIAL&&initial.period().equals(period(receipt.periodStart(),receipt.periodEnd()))
                &&initial.price().equals(intent.terms())&&initial.settlement()==SettlementKind.CASH&&initial.cashObjects().size()==1
                &&initial.cashObjects().getFirst().reference().equals(admission.paymentIntent()));
            var initialAllocations=sourceAllocations.get(initial.reference());
            require(initialAllocations.size()==1&&initialAllocations.getFirst().getId().equals(admission.allocation()));
            checkpoint(RenewalCheckpoint.RENEWAL_INVOICE);var next=invoice(renewal,remote,item,null);
            require(next.kind()==BillingRecord.Kind.RENEWAL&&next.period().equals(period)&&next.price().equals(intent.terms())
                &&(next.settlement()==SettlementKind.CASH||next.settlement()==SettlementKind.OPEN));
            if(lane==RenewalLane.FAILED){
                require(saved!=null&&saved.renewalInvoice().equals(next.reference())&&"open".equals(renewal.getStatus())
                    &&next.settlement()==SettlementKind.OPEN&&next.cashObjects().isEmpty());
                var failed=failedRenewal(renewal,admission);
                return new RenewalResult(null,new FailedRenewalRead(new Snapshot(remote.getId(),Subscription.Status.PAST_DUE,intent.terms(),period,null,null,
                    List.of(initial,next),null,false,0,at,null,List.of(),null,null),failed.getFirst(),failed.getLast()));
            }
            if(lane==RenewalLane.RECOVERED){
                require(saved!=null&&saved.renewalInvoice().equals(next.reference())&&next.settlement()==SettlementKind.CASH&&next.cashObjects().size()==1
                    &&debtFailure.paymentIntent().equals(next.cashObjects().getFirst().reference()));
                var allocations=sourceAllocations.get(next.reference());
                require(allocations.size()==1&&debtFailure.allocation().equals(allocations.getFirst().getId())&&Boolean.TRUE.equals(allocations.getFirst().getIsDefault()));
                return new RenewalResult(new MappedPaid(saved,new Snapshot(remote.getId(),Subscription.Status.ACTIVE,intent.terms(),period,null,null,
                    List.of(initial,next),null,false,0,at,null,List.of(),null,null)),null);
            }
            if(next.settlement()==SettlementKind.OPEN)requirePendingRenewal(renewal);
            if(saved!=null)require(saved.renewalInvoice().equals(next.reference()));
            var boundary=saved;
            if(boundary==null){
                var id=UUID.randomUUID();
                boundary=new Boundary(id,admission.id(),ready,item.getId(),next.reference(),period,
                    OwnedFirstRenewal.fingerprint("gp:owned-first-renewal-boundary:v1",id,admission.id(),ready.id(),item.getId(),next.reference(),period.start(),period.end(),at),at);
            }
            if(ready.ack().advance().step()==Step.BOUNDARY||next.settlement()==SettlementKind.OPEN)return new RenewalResult(new MappedPending(boundary),null);
            boolean methodReady=false;
            if(remote.getDefaultPaymentMethod()!=null){
                String method=remote.getDefaultPaymentMethod();ref(method,"pm_");var pm=get(()->client.v1().paymentMethods().retrieve(method,options()));
                require(method.equals(pm.getId())&&"payment_method".equals(pm.getObject())&&Boolean.FALSE.equals(pm.getLivemode())
                    &&b.providerReference().equals(pm.getCustomer())&&"card".equals(pm.getType())&&pm.getCard()!=null);methodReady=true;
            }
            return new RenewalResult(new MappedPaid(boundary,new Snapshot(remote.getId(),Subscription.Status.ACTIVE,intent.terms(),period,null,null,
                List.of(initial,next),null,methodReady,0,at,null,List.of(),null,null)),null);
        }
        private List<String> failedRenewal(com.stripe.model.Invoice invoice,Admission admission)throws StripeException {
            var allocations=sourceAllocations.get(invoice.getId());require(allocations.size()==1);
            var allocation=allocations.getFirst();long due=invoice.getAmountDue();
            require(!admission.allocation().equals(allocation.getId())&&Boolean.TRUE.equals(allocation.getIsDefault())
                &&"open".equals(allocation.getStatus())&&Objects.equals(due,allocation.getAmountRequested())&&zeros(allocation.getAmountPaid())
                &&allocation.getPayment()!=null&&"payment_intent".equals(allocation.getPayment().getType()));
            var id=allocation.getPayment().getPaymentIntent();ref(id,"pi_");require(!id.equals(admission.paymentIntent()));
            var pi=get(()->client.v1().paymentIntents().retrieve(id,options()));
            require(id.equals(pi.getId())&&"payment_intent".equals(pi.getObject())&&Boolean.FALSE.equals(pi.getLivemode())
                &&owner.customer().providerReference().equals(pi.getCustomer())&&invoice.getCurrency().equals(pi.getCurrency())
                &&Objects.equals(due,pi.getAmount())&&zeros(pi.getAmountReceived())&&"automatic".equals(pi.getCaptureMethod())
                &&List.of("card").equals(pi.getPaymentMethodTypes())&&"requires_payment_method".equals(pi.getStatus()));
            var error=pi.getLastPaymentError();require(error!=null&&"card_error".equals(error.getType())&&"card_declined".equals(error.getCode())
                &&Set.of("generic_decline","insufficient_funds","do_not_honor").contains(error.getDeclineCode()));
            var chargeId=pi.getLatestCharge();ref(chargeId,"ch_");require(chargeId.equals(error.getCharge()));
            var charge=get(()->client.v1().charges().retrieve(chargeId,options()));
            require(chargeId.equals(charge.getId())&&"charge".equals(charge.getObject())&&Boolean.FALSE.equals(charge.getLivemode())
                &&owner.customer().providerReference().equals(charge.getCustomer())&&id.equals(charge.getPaymentIntent())&&invoice.getCurrency().equals(charge.getCurrency())
                &&Objects.equals(due,charge.getAmount())&&"failed".equals(charge.getStatus())&&Boolean.FALSE.equals(charge.getPaid())
                &&Boolean.FALSE.equals(charge.getCaptured())&&zeros(charge.getAmountCaptured(),charge.getAmountRefunded())&&Boolean.FALSE.equals(charge.getRefunded())
                &&charge.getPaymentMethodDetails()!=null&&"card".equals(charge.getPaymentMethodDetails().getType())&&charge.getPaymentMethodDetails().getCard()!=null
                &&"card_declined".equals(charge.getFailureCode())&&charge.getOutcome()!=null&&"issuer_declined".equals(charge.getOutcome().getType())
                &&error.getDeclineCode().equals(charge.getOutcome().getReason()));
            var reverse=pages(after->client.v1().invoicePayments().list(InvoicePaymentListParams.builder().setPayment(InvoicePaymentListParams.Payment.builder()
                .setType(InvoicePaymentListParams.Payment.Type.PAYMENT_INTENT).setPaymentIntent(id).build()).setLimit((long)PAGE_SIZE).setStartingAfter(after).build(),options()));
            require(reverse.size()==1&&allocation.equals(reverse.getFirst()));
            return List.of(allocation.getId(),id);
        }
        private void requirePendingRenewal(com.stripe.model.Invoice invoice)throws StripeException {
            var allocations=sourceAllocations.get(invoice.getId());require(allocations.size()<=1);
            if(allocations.isEmpty())return;
            var allocation=allocations.getFirst();long due=invoice.getAmountDue();
            require("open".equals(allocation.getStatus())&&Objects.equals(due,allocation.getAmountRequested())&&zeros(allocation.getAmountPaid())
                &&allocation.getPayment()!=null&&"payment_intent".equals(allocation.getPayment().getType()));
            var id=allocation.getPayment().getPaymentIntent();ref(id,"pi_");var pi=get(()->client.v1().paymentIntents().retrieve(id,options()));
            require(id.equals(pi.getId())&&"payment_intent".equals(pi.getObject())&&Boolean.FALSE.equals(pi.getLivemode())
                &&owner.customer().providerReference().equals(pi.getCustomer())&&invoice.getCurrency().equals(pi.getCurrency())
                &&Objects.equals(due,pi.getAmount())&&zeros(pi.getAmountReceived())&&"automatic".equals(pi.getCaptureMethod())
                &&List.of("card").equals(pi.getPaymentMethodTypes())&&"processing".equals(pi.getStatus())&&pi.getLastPaymentError()==null&&pi.getLatestCharge()==null);
            var reverse=pages(after->client.v1().invoicePayments().list(InvoicePaymentListParams.builder().setPayment(InvoicePaymentListParams.Payment.builder()
                .setType(InvoicePaymentListParams.Payment.Type.PAYMENT_INTENT).setPaymentIntent(id).build()).setLimit((long)PAGE_SIZE).setStartingAfter(after).build(),options()));
            require(reverse.size()==1&&allocation.equals(reverse.getFirst()));
        }
        Snapshot snapshot() throws StripeException {
            var a=owner.account();var s=owner.subscription();var b=owner.customer();
            require(a!=null&&s!=null&&b!=null&&a.provider()==PaymentProviderType.STRIPE&&a.mode()==PaymentAccountMode.SANDBOX
                &&a.id().equals(s.paymentAccountId())&&a.id().equals(b.accountId())&&s.siteId().equals(b.siteId())&&s.customerId().equals(b.customerId()));
            ref(s.providerReference(),"sub_");ref(b.providerReference(),"cus_");
            if(original!=null){
                var r=original.receipt();var i=r.intent();var o=i.clock().owner();
                require(a.id().equals(o.account())&&a.externalAccountId().equals(o.externalAccount())&&a.credentialsRef().equals(o.credentialsRef())
                    &&s.id().equals(i.subscription())&&s.siteId().equals(o.site())&&s.customerId().equals(o.customer())
                    &&s.billingPrice().equals(i.terms())&&s.providerReference().equals(r.reference())&&b.id().equals(i.customerBinding())
                    &&b.creationOperationId().equals(i.customerCreationOperation())&&b.providerReference().equals(i.customerReference())
                    &&owner.trial()==null&&owner.proofs()==null&&owner.prorations().isEmpty()&&owner.schedules().isEmpty()
                    &&owner.prices().equals(List.of(new PublishedPrice(i.terms(),i.priceReference(),i.productReference()))));
            }

            for(var p:owner.prices()){
                require(p!=null&&p.terms()!=null&&s.productId().equals(p.terms().productId())&&p.terms().taxMode()!=dev.genericpayments.platform.catalog.domain.TaxMode.EXCLUSIVE);
                ref(p.providerPriceReference(),"price_");ref(p.providerProductReference(),"prod_");require(prices.put(p.providerPriceReference(),p)==null);
            }
            var customer=get(()->client.v1().customers().retrieve(b.providerReference()));
            require(clockMatches(customer.getTestClock())&&b.providerReference().equals(customer.getId())&&"customer".equals(customer.getObject())&&Boolean.FALSE.equals(customer.getLivemode())&&!Boolean.TRUE.equals(customer.getDeleted()));
            remote=get(()->client.v1().subscriptions().retrieve(s.providerReference()));
            require(clockMatches(remote.getTestClock())&&s.providerReference().equals(remote.getId())&&"subscription".equals(remote.getObject())&&Boolean.FALSE.equals(remote.getLivemode())&&b.providerReference().equals(remote.getCustomer())
                &&remote.getPauseCollection()==null&&"charge_automatically".equals(remote.getCollectionMethod())&&remote.getBillingMode()!=null&&"classic".equals(remote.getBillingMode().getType()));
            if(original!=null){
                var r=original.receipt();StripeBillingOwnership.requireOwnedInitialRemote(remote,r.intent());
                var item=remote.getItems().getData().getFirst();
                require(Objects.equals(remote.getCreated(),r.providerCreated())&&r.itemReference().equals(item.getId())
                    &&Objects.equals(item.getCurrentPeriodStart(),r.periodStart())&&Objects.equals(item.getCurrentPeriodEnd(),r.periodEnd()));
            }
            var items=remote.getItems();require(items!=null&&Boolean.FALSE.equals(items.getHasMore())&&items.getData()!=null&&items.getData().size()==1);
            var item=items.getData().getFirst();ref(item.getId(),"si_");require("subscription_item".equals(item.getObject())&&s.providerReference().equals(item.getSubscription())&&Objects.equals(1L,item.getQuantity())&&!Boolean.TRUE.equals(item.getDeleted()));
            var price=item.getPrice();require(price!=null);var mapped=prices.get(price.getId());require(mapped!=null);validatePrice(price,mapped);
            var period=period(item.getCurrentPeriodStart(),item.getCurrentPeriodEnd());currency(remote.getCurrency(),mapped.terms());
            var status=switch(remote.getStatus()){
                case "active"->Subscription.Status.ACTIVE;case "trialing"->Subscription.Status.TRIALING;case "incomplete"->Subscription.Status.INCOMPLETE;
                case "incomplete_expired","canceled"->Subscription.Status.CANCELLED;case "past_due"->Subscription.Status.PAST_DUE;case "unpaid"->Subscription.Status.UNPAID;case "paused"->Subscription.Status.PAUSED;
                default->throw mismatch();};
            Subscription.Period trial=null;
            if(owner.trial()!=null){
                var context=owner.trial();var request=context.request();var step=context.step();
                require(request.account().equals(a.id())&&request.subscription().equals(s.id())&&step.resourceId().equals(request.operation())&&step.accountId().equals(a.id())
                    &&step.kind()==dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SUBSCRIPTION_CREATE&&step.status()==dev.genericpayments.platform.provider.application.model.ProviderOperation.Status.ACKNOWLEDGED&&remote.getId().equals(step.providerReference()));
                if(context.original().terms().trialPolicy()==dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL){var setup=context.requiredSetup();require(setup!=null&&setup.operation().equals(request.operation())&&setup.account().equals(a.id())&&setup.subscription().equals(s.id())&&setup.method()!=null);}
                if(request.verified()){
                    require(step.id().equals(request.step())&&remote.getId().equals(request.subscriptionReference()));trial=request.period();
                    if(remote.getTrialStart()!=null||remote.getTrialEnd()!=null)require(trial.equals(period(remote.getTrialStart(),remote.getTrialEnd())));
                }else{
                    if(remote.getTrialStart()!=null||remote.getTrialEnd()!=null){
                        trial=period(remote.getTrialStart(),remote.getTrialEnd());
                        require(trial.end().getEpochSecond()==request.requestedEnd()&&StripeBillingOwnership.inWindow(remote.getTrialStart(),step));
                    }
                }
            }
            var invoices=pages(after->{var page=client.v1().invoices().list(InvoiceListParams.builder().setSubscription(remote.getId()).setLimit((long)PAGE_SIZE).setStartingAfter(after).build());rememberExplicitNullAttempt(page);return page;});
            sourceInvoices=invoices;
            var normalized=new ArrayList<SubscriptionProviderModels.Invoice>();
            for(var invoice:invoices)normalized.add(invoice(invoice,remote,item,trial));
            if(owner.trial()!=null)require(trialEvidence!=null);
            if(original!=null){
                require(normalized.size()==1);var initial=normalized.getFirst();
                require(initial.kind()==BillingRecord.Kind.INITIAL&&initial.price().equals(original.receipt().intent().terms())&&initial.period().equals(period));
                requireSettlement(initial.settlement()==SettlementKind.OPEN||initial.settlement()==SettlementKind.CASH,settlementReasons.getOrDefault(initial,SettlementReason.SNAPSHOT_UNSUPPORTED));
            }

            Instant cancel=remote.getCancelAt()==null?null:Instant.ofEpochSecond(remote.getCancelAt());
            if(Boolean.TRUE.equals(remote.getCancelAtPeriodEnd())){if(cancel==null)cancel=period.end();else require(cancel.equals(period.end()));}
            boolean methodReady=false;
            if(remote.getDefaultPaymentMethod()!=null){
                String method=remote.getDefaultPaymentMethod();
                    require(method.matches("pm_[A-Za-z0-9]{1,252}"));
                    var pm=get(()->client.v1().paymentMethods().retrieve(method));
                    require(method.equals(pm.getId())&&"payment_method".equals(pm.getObject())&&Boolean.FALSE.equals(pm.getLivemode())&&b.providerReference().equals(pm.getCustomer())&&"card".equals(pm.getType())&&pm.getCard()!=null);methodReady=true;
            }
            var withdrawal=externalWithdrawal(status,mapped.terms(),period);
            return new Snapshot(remote.getId(),status,mapped.terms(),period,trialEvidence==null?trial:trialEvidence.period(),cancel,normalized,null,methodReady,0,at,null,List.of(),trialEvidence,withdrawal);
        }
        ExternalWithdrawalEvidence externalWithdrawal(Subscription.Status status,SubscriptionPriceTerms price,Subscription.Period period){
            if(original!=null||status!=Subscription.Status.ACTIVE||owner.trial()!=null||!price.equals(owner.subscription().billingPrice())
                ||!period.equals(owner.subscription().currentPeriod())||remote.getLastResponse()==null)return null;
            try{
                var raw=NATIVE_JSON.readTree(remote.getLastResponse().body());
                if(!remote.getId().equals(raw.path("id").asText())||!raw.path("cancel_at_period_end").isBoolean()||raw.path("cancel_at_period_end").booleanValue())return null;
                for(var field:List.of("cancel_at","schedule","pending_update","trial_start","trial_end"))if(!raw.has(field)||!raw.get(field).isNull())return null;
                return new ExternalWithdrawalEvidence(remote.getId(),price,period,at);
            }catch(java.io.IOException|RuntimeException malformed){throw mismatch();}
        }
        void rememberExplicitNullAttempt(StripeCollection<com.stripe.model.Invoice> page){
            // A nullable SDK getter loses the distinction between absent and explicit null.
            // Retain that one native field from this exact already-read collection response.
            if(page.getLastResponse()==null)return;
            try{
                var root=NATIVE_JSON.readTree(page.getLastResponse().body());var data=root.get("data");if(data==null||!data.isArray()||page.getData()==null||data.size()!=page.getData().size())return;
                for(int i=0;i<data.size();i++){var raw=data.get(i);var id=raw.get("id");if(id!=null&&id.isTextual()&&id.textValue().equals(page.getData().get(i).getId())&&raw.has("next_payment_attempt")&&raw.get("next_payment_attempt").isNull())explicitNoNextAttempt.add(id.textValue());}
            }catch(java.io.IOException|RuntimeException malformed){throw mismatch();}
        }
        void rememberRenewalFlatItems(StripeCollection<InvoiceLineItem> page){
            // Pinned wire uses the mandatory nested association. Validate any optional flat duplicate in this same response.
            require(page.getLastResponse()!=null&&page.getData()!=null);
            try{
                var raw=NATIVE_JSON.readTree(page.getLastResponse().body());var data=raw.get("data");
                require(data!=null&&data.isArray()&&data.size()==page.getData().size());
                for(int i=0;i<data.size();i++){
                    var line=data.get(i);var id=line.get("id");var item=line.get("subscription_item");var subscription=line.get("subscription");
                    require(id!=null&&id.isTextual()&&id.textValue().equals(page.getData().get(i).getId())&&(item==null||item.isTextual())&&(subscription==null||subscription.isTextual()));
                    if(item!=null)require(renewalFlatItems.put(id.textValue(),item.textValue())==null);
                }
            }catch(java.io.IOException malformed){throw mismatch();}
        }
        void validatePrice(Price p,PublishedPrice mapped){
            var t=mapped.terms();var recurring=p.getRecurring();
            require("price".equals(p.getObject())&&Boolean.FALSE.equals(p.getLivemode())&&mapped.providerProductReference().equals(p.getProduct())&&Objects.equals(p.getUnitAmount(),t.regularAmount().minorUnits())
                &&"inclusive".equals(p.getTaxBehavior())&&"recurring".equals(p.getType())&&recurring!=null&&Objects.equals(recurring.getIntervalCount(),1L)
                &&"licensed".equals(recurring.getUsageType())&&t.interval().name().toLowerCase(Locale.ROOT).equals(recurring.getInterval())
                &&p.getMetadata()!=null&&t.priceId().value().toString().equals(p.getMetadata().get("local_price_id"))&&Integer.toString(t.version()).equals(p.getMetadata().get("local_price_version")));
            currency(p.getCurrency(),t);
        }
        SubscriptionProviderModels.Invoice invoice(com.stripe.model.Invoice invoice,com.stripe.model.Subscription remote,SubscriptionItem item,Subscription.Period trial) throws StripeException {
            ref(invoice.getId(),"in_");require(clockMatches(invoice.getTestClock())&&"invoice".equals(invoice.getObject())&&Boolean.FALSE.equals(invoice.getLivemode())&&owner.customer().providerReference().equals(invoice.getCustomer())
                &&invoice.getParent()!=null&&"subscription_details".equals(invoice.getParent().getType())&&invoice.getParent().getSubscriptionDetails()!=null&&remote.getId().equals(invoice.getParent().getSubscriptionDetails().getSubscription()));
            var lines=pages(after->client.v1().invoices().lineItems().list(invoice.getId(),InvoiceLineItemListParams.builder().setLimit((long)PAGE_SIZE).setStartingAfter(after).build(),options()),
                page->{if(renewalAdmission!=null&&!renewalAdmission.invoice().equals(invoice.getId()))rememberRenewalFlatItems(page);});
            sourceLines.put(invoice.getId(),lines);
            var matching=owner.prorations().stream().filter(p->invoice.getId().equals(p.result().invoice())).toList();
            if(!matching.isEmpty()){require(matching.size()==1);return proration(invoice,lines,matching.getFirst());}
            // Multi-line proration needs the original quote's exact debit/credit proof.
            require(lines.size()==1);var line=lines.getFirst();ref(line.getId(),"il_");
            // Only a typed original receipt permits an absent duplicate flat reference; nested ownership remains mandatory.
            boolean originalHistory=original!=null&&(renewalAdmission==null||renewalAdmission.invoice().equals(invoice.getId())&&"subscription_create".equals(invoice.getBillingReason()));
            boolean ownedRenewal=renewalAdmission!=null&&!originalHistory;
            if(recoveredDebt)require(StripeBillingMutationAdapter.noNonAmountAdjustments(invoice)&&(line.getTaxes()==null||line.getTaxes().isEmpty()));
            requireOwned("line_item".equals(line.getObject())&&Boolean.FALSE.equals(line.getLivemode())&&invoice.getId().equals(line.getInvoice())&&(remote.getId().equals(line.getSubscription())||(originalHistory||ownedRenewal)&&line.getSubscription()==null)&&Objects.equals(line.getQuantity(),1L)
                &&line.getParent()!=null&&"subscription_item_details".equals(line.getParent().getType())&&line.getParent().getSubscriptionItemDetails()!=null,OwnedActionGuard.LINE_ASSOCIATION);
            var detail=line.getParent().getSubscriptionItemDetails();
            if(ownedRenewal)require((!renewalFlatItems.containsKey(line.getId())||item.getId().equals(renewalFlatItems.get(line.getId())))
                &&ownedRenewalNoAdjustments(invoice)&&(line.getTaxes()==null||line.getTaxes().isEmpty()));
            var trialContext=owner.trial();boolean historicalTrial=trialContext!=null&&trialContext.request().verified()&&invoice.getId().equals(trialContext.request().invoice());
            requireOwned(remote.getId().equals(detail.getSubscription())&&detail.getProration()!=null,OwnedActionGuard.LINE_ASSOCIATION);
            require(line.getPricing()!=null&&"price_details".equals(line.getPricing().getType())&&line.getPricing().getPriceDetails()!=null);
            var pricing=line.getPricing().getPriceDetails();var mapped=prices.get(pricing.getPrice());require(mapped!=null&&mapped.providerProductReference().equals(pricing.getProduct()));
            currency(invoice.getCurrency(),mapped.terms());currency(line.getCurrency(),mapped.terms());
            require(line.getPeriod()!=null);var period=period(line.getPeriod().getStart(),line.getPeriod().getEnd());require(invoice.getAmountDue()!=null&&invoice.getAmountDue()>=0&&line.getAmount()!=null);
            var kind=switch(invoice.getBillingReason()){case "subscription_create"->BillingRecord.Kind.INITIAL;case "subscription_cycle"->BillingRecord.Kind.RENEWAL;case "subscription_update"->BillingRecord.Kind.PRORATION;default->throw mismatch();};
            boolean originalTrial=trialContext!=null&&!trialContext.request().verified()&&kind==BillingRecord.Kind.INITIAL&&invoice.getAmountDue()==0
                &&mapped.equals(trialContext.original())&&period.end().getEpochSecond()==trialContext.request().requestedEnd()
                &&StripeBillingOwnership.inWindow(period.start().getEpochSecond(),trialContext.step())&&StripeBillingOwnership.inWindow(invoice.getCreated(),trialContext.step())
                &&(trial==null||trial.equals(period));
            var scheduleHistory=owner.schedules().stream().filter(p->{var q=p.quote();var binding=p.schedule();return binding.releaseStep()==null&&binding.updateStep()!=null&&binding.targetEnd()!=null
                &&q.customerReference().equals(invoice.getCustomer())&&q.subscriptionReference().equals(remote.getId())&&q.targetPrice().equals(pricing.getPrice())&&q.product().equals(pricing.getProduct())
                &&line.getPeriod()!=null&&Objects.equals(line.getPeriod().getStart(),q.period().end().getEpochSecond())&&Objects.equals(line.getPeriod().getEnd(),binding.targetEnd());}).toList();
            require(scheduleHistory.size()<=1);
            if(!scheduleHistory.isEmpty()){require("subscription_cycle".equals(invoice.getBillingReason())&&Boolean.FALSE.equals(detail.getProration())&&detail.getSubscriptionItem()!=null&&detail.getSubscriptionItem().matches("si_[A-Za-z0-9]{1,252}")
                &&StripeBillingMutationAdapter.noAdjustments(invoice)&&(line.getTaxes()==null||line.getTaxes().isEmpty())&&(line.getDiscounts()==null||line.getDiscounts().isEmpty()));}
            else if(originalTrial)require(detail.getSubscriptionItem()!=null&&detail.getSubscriptionItem().matches("si_[A-Za-z0-9]{1,252}"));
            else require((historicalTrial?trialContext.request().item():item.getId()).equals(detail.getSubscriptionItem()));
            var allocations=pages(after->client.v1().invoicePayments().list(InvoicePaymentListParams.builder().setInvoice(invoice.getId()).setLimit((long)PAGE_SIZE).setStartingAfter(after).build(),options()));
            sourceAllocations.put(invoice.getId(),allocations);
            for(var allocation:allocations){ref(allocation.getId(),"inpay_");require("invoice_payment".equals(allocation.getObject())&&Boolean.FALSE.equals(allocation.getLivemode())&&invoice.getId().equals(allocation.getInvoice()));currency(allocation.getCurrency(),mapped.terms());}
            SettlementKind settlement=SettlementKind.UNSUPPORTED;List<CashObject> cash=List.of();
            var trace=new SettlementTrace(actionFailure!=null);
            SettlementReason unsupportedReason=SettlementReason.SNAPSHOT_UNSUPPORTED;
            // Cache the pure typed status before the six eager amount getters; reuse it below.
            var status=invoice.getStatus();
            boolean acceptsUnavailableOffStripe=!recoveredDebt&&originalHistory&&kind==BillingRecord.Kind.INITIAL&&invoice.getAmountDue()>0
                &&("open".equals(status)||"paid".equals(status));
            boolean acceptsUnfinalizedEnding=ownedRenewal&&kind==BillingRecord.Kind.RENEWAL&&"draft".equals(status);
            boolean plain=trace.check(!detail.getProration(),SettlementReason.PRORATION_FLAG)&&trace.check(kind!=BillingRecord.Kind.PRORATION,SettlementReason.PRORATION_KIND)&&trace.check("charge_automatically".equals(invoice.getCollectionMethod()),SettlementReason.COLLECTION_METHOD)
                &&trace.plainAmounts(invoice.getAmountPaidOffStripe(),invoice.getAmountOverpaid(),invoice.getStartingBalance(),invoice.getEndingBalance(),invoice.getPrePaymentCreditNotesAmount(),invoice.getPostPaymentCreditNotesAmount(),acceptsUnavailableOffStripe,acceptsUnfinalizedEnding)
                &&trace.check(empty(invoice.getDiscounts()),SettlementReason.INVOICE_DISCOUNTS)&&trace.check(empty(invoice.getTotalDiscountAmounts()),SettlementReason.INVOICE_DISCOUNT_AMOUNTS)&&trace.check(empty(invoice.getTotalPretaxCreditAmounts()),SettlementReason.INVOICE_PRETAX_CREDITS)&&trace.check(empty(line.getDiscountAmounts()),SettlementReason.LINE_DISCOUNT_AMOUNTS)&&trace.check(empty(line.getPretaxCreditAmounts()),SettlementReason.LINE_PRETAX_CREDITS)
                &&trace.check(Objects.equals(invoice.getTotal(),invoice.getAmountDue()),SettlementReason.TOTAL)&&trace.check(Objects.equals(invoice.getSubtotal(),invoice.getAmountDue()),SettlementReason.SUBTOTAL)&&trace.check(Objects.equals(line.getAmount(),invoice.getAmountDue()),SettlementReason.LINE_AMOUNT);
            if(trace.first!=null)unsupportedReason=trace.first;
            switch(status){
                case "paid"->{
                    if(plain&&invoice.getAmountDue()==0&&zeros(invoice.getAmountPaid(),invoice.getAmountRemaining())&&allocations.isEmpty()
                        &&kind==BillingRecord.Kind.INITIAL&&trialContext!=null&&mapped.equals(trialContext.original())
                        &&(originalTrial||historicalTrial&&trial!=null&&trial.equals(period))){
                        require(trialEvidence==null);
                        trialEvidence=new TrialEvidence(trialContext.request().operation(),trialContext.step().id(),invoice.getId(),detail.getSubscriptionItem(),period,mapped.terms(),trialContext.requiredSetup()==null?null:trialContext.requiredSetup().method());
                        settlement=SettlementKind.ZERO;
                    }
                    else if(plain&&invoice.getAmountDue()>0&&invoice.getAmountDue()==mapped.terms().regularAmount().minorUnits()&&Objects.equals(invoice.getAmountPaid(),invoice.getAmountDue())&&zeros(invoice.getAmountRemaining())&&allocations.size()==1){
                        var confirmed=capture(invoice,allocations.getFirst());if(confirmed!=null){require(cashIds.add(confirmed.reference()));cash=List.of(confirmed);settlement=SettlementKind.CASH;}
                    }
                }
                case "open","draft","uncollectible"->{
                    if(plain&&trace.check(invoice.getAmountDue()>0,SettlementReason.OPEN_DUE_POSITIVE)&&trace.check(invoice.getAmountDue()==mapped.terms().regularAmount().minorUnits(),SettlementReason.OPEN_DUE_CATALOG)&&trace.check(zeros(invoice.getAmountPaid()),SettlementReason.OPEN_PAID)&&trace.check(Objects.equals(invoice.getAmountRemaining(),invoice.getAmountDue()),SettlementReason.OPEN_REMAINING)
                        &&allocations.stream().allMatch(p->trace.check("open".equals(p.getStatus())||"canceled".equals(p.getStatus()),SettlementReason.OPEN_ALLOCATION_STATUS)&&trace.check(p.getPayment()!=null,SettlementReason.OPEN_ALLOCATION_PAYMENT)&&trace.check("payment_intent".equals(p.getPayment().getType()),SettlementReason.OPEN_ALLOCATION_TYPE)&&trace.check(p.getPayment().getPaymentIntent()!=null,SettlementReason.OPEN_ALLOCATION_INTENT)))settlement=SettlementKind.OPEN;
                    if(trace.first!=null)unsupportedReason=trace.first;
                }
                case "void"->{if(plain&&allocations.isEmpty())settlement=SettlementKind.VOID;}
                default->throw mismatch();
            }
            if(!scheduleHistory.isEmpty())require(settlement==SettlementKind.CASH||item.getId().equals(detail.getSubscriptionItem())&&settlement==SettlementKind.OPEN);
            var result=new SubscriptionProviderModels.Invoice(invoice.getId(),kind,mapped.terms(),null,period,invoice.getAmountDue(),mapped.terms().regularAmount().currency(),settlement,cash);
            if(actionFailure!=null&&settlement==SettlementKind.UNSUPPORTED)settlementReasons.put(result,unsupportedReason);
            return result;
        }
        private boolean ownedRenewalNoAdjustments(com.stripe.model.Invoice invoice){
            // Request off-stripe explicitly; only the unfinalized draft ending balance may remain unknown.
            return "subscription_cycle".equals(invoice.getBillingReason())&&invoice.getAmountDue()!=null&&invoice.getAmountDue()>0
                &&zeros(invoice.getAmountPaidOffStripe(),invoice.getAmountOverpaid(),invoice.getStartingBalance(),invoice.getPrePaymentCreditNotesAmount(),invoice.getPostPaymentCreditNotesAmount())
                &&(zeros(invoice.getEndingBalance())||"draft".equals(invoice.getStatus())&&invoice.getEndingBalance()==null)
                &&StripeBillingMutationAdapter.noNonAmountAdjustments(invoice);
        }
        SubscriptionProviderModels.Invoice proration(com.stripe.model.Invoice invoice,List<InvoiceLineItem> lines,dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Proration proof)throws StripeException {
            var q=proof.quote();var result=proof.result();var s=owner.subscription();
            require(q.account().equals(owner.account().id())&&q.site().equals(s.siteId())&&q.customer().equals(s.customerId())&&q.customerReference().equals(owner.customer().providerReference())&&q.subscription().equals(s.id())&&q.subscriptionReference().equals(s.providerReference())&&q.id().equals(result.quote())&&result.account().equals(q.account())&&result.subscription().equals(s.id())&&result.reference().equals(q.subscriptionReference())&&result.invoice().equals(invoice.getId())&&q.timing()==ChangeTiming.IMMEDIATE);
            StripeBillingMutationAdapter.requireInvoice(invoice,q,result.invoice());
            require(lines.stream().allMatch(l->invoice.getId().equals(l.getInvoice())&&q.subscriptionReference().equals(l.getSubscription())));
            require(lines.stream().allMatch(l->l.getPricing()!=null&&l.getPricing().getPriceDetails()!=null&&Objects.equals(l.getId(),q.sourcePrice().equals(l.getPricing().getPriceDetails().getPrice())?result.creditLine():result.debitLine())));
            var period=new Subscription.Period(q.anchor(),q.paidThrough());
            require(q.lines().equals(StripeBillingMutationAdapter.prorationLines(lines,q.subscriptionReference(),q.item(),q.sourcePrice(),q.targetPrice(),q.product(),period,q.target().regularAmount().currency(),true)));
            var source=prices.get(q.sourcePrice());var target=prices.get(q.targetPrice());require(source!=null&&source.terms().equals(q.source())&&target!=null&&target.terms().equals(q.target()));
            var allocations=pages(after->client.v1().invoicePayments().list(InvoicePaymentListParams.builder().setInvoice(invoice.getId()).setLimit((long)PAGE_SIZE).setStartingAfter(after).build()));sourceAllocations.put(invoice.getId(),allocations);
            for(var allocation:allocations){ref(allocation.getId(),"inpay_");require("invoice_payment".equals(allocation.getObject())&&Boolean.FALSE.equals(allocation.getLivemode())&&invoice.getId().equals(allocation.getInvoice()));currency(allocation.getCurrency(),q.target());}
            SettlementKind settlement=SettlementKind.UNSUPPORTED;List<CashObject> cash=List.of();
            if("paid".equals(invoice.getStatus())&&zeros(invoice.getAmountRemaining())){
                if(q.dueNow()==0&&zeros(invoice.getAmountPaid())&&allocations.isEmpty())settlement=SettlementKind.ZERO;
                else if(q.dueNow()>0&&Objects.equals(invoice.getAmountPaid(),q.dueNow())&&allocations.size()==1&&Boolean.TRUE.equals(allocations.getFirst().getIsDefault())){
                    var captured=capture(invoice,allocations.getFirst());if(captured!=null){require(cashIds.add(captured.reference()));cash=List.of(captured);settlement=SettlementKind.CASH;}
                }
            }else if(Set.of("open","draft","uncollectible").contains(invoice.getStatus())&&q.dueNow()>0&&zeros(invoice.getAmountPaid())&&Objects.equals(invoice.getAmountRemaining(),q.dueNow())&&allocations.stream().allMatch(a->Set.of("open","canceled").contains(a.getStatus())&&a.getPayment()!=null&&"payment_intent".equals(a.getPayment().getType())&&a.getPayment().getPaymentIntent()!=null))settlement=SettlementKind.OPEN;
            else if("void".equals(invoice.getStatus())&&zeros(invoice.getAmountPaid())&&allocations.isEmpty())settlement=SettlementKind.VOID;
            return new SubscriptionProviderModels.Invoice(invoice.getId(),BillingRecord.Kind.PRORATION,q.target(),q.source(),period,q.dueNow(),q.target().regularAmount().currency(),settlement,cash);
        }
        CashObject capture(com.stripe.model.Invoice invoice,InvoicePayment allocation) throws StripeException {
            var payment=allocation.getPayment();long due=invoice.getAmountDue();
            if(!"paid".equals(allocation.getStatus())||!Objects.equals(allocation.getAmountPaid(),due)||!Objects.equals(allocation.getAmountRequested(),due)||payment==null||!"payment_intent".equals(payment.getType()))return null;
            ref(payment.getPaymentIntent(),"pi_");var intent=get(()->client.v1().paymentIntents().retrieve(payment.getPaymentIntent(),options()));
            require(payment.getPaymentIntent().equals(intent.getId())&&"payment_intent".equals(intent.getObject())&&Boolean.FALSE.equals(intent.getLivemode())&&owner.customer().providerReference().equals(intent.getCustomer())&&invoice.getCurrency().equals(intent.getCurrency()));
            if(!"succeeded".equals(intent.getStatus())||!"automatic".equals(intent.getCaptureMethod())||!List.of("card").equals(intent.getPaymentMethodTypes())||!Objects.equals(intent.getAmount(),due)||!Objects.equals(intent.getAmountReceived(),due))return null;
            ref(intent.getLatestCharge(),"ch_");var charge=get(()->client.v1().charges().retrieve(intent.getLatestCharge(),options()));
            require(intent.getLatestCharge().equals(charge.getId())&&"charge".equals(charge.getObject())&&Boolean.FALSE.equals(charge.getLivemode())&&owner.customer().providerReference().equals(charge.getCustomer())&&intent.getId().equals(charge.getPaymentIntent())&&invoice.getCurrency().equals(charge.getCurrency()));
            // This descriptive recovered-debt lane rejects refunded/unknown cash; legacy facts retain gross capture semantics below.
            if(recoveredDebt)require(zeros(charge.getAmountRefunded())&&Boolean.FALSE.equals(charge.getRefunded()));
            if(!Boolean.TRUE.equals(charge.getCaptured())||!Boolean.TRUE.equals(charge.getPaid())||!"succeeded".equals(charge.getStatus())||!Objects.equals(charge.getAmount(),due)||!Objects.equals(charge.getAmountCaptured(),due)||charge.getPaymentMethodDetails()==null||!"card".equals(charge.getPaymentMethodDetails().getType()))return null;
            var reverse=pages(after->client.v1().invoicePayments().list(InvoicePaymentListParams.builder().setPayment(InvoicePaymentListParams.Payment.builder().setType(InvoicePaymentListParams.Payment.Type.PAYMENT_INTENT).setPaymentIntent(intent.getId()).build()).setLimit((long)PAGE_SIZE).setStartingAfter(after).build(),options()));
            if(reverse.size()!=1)return null;var sole=reverse.getFirst();require(sole.equals(allocation));
            require(allocation.getStatusTransitions()!=null&&allocation.getStatusTransitions().getPaidAt()!=null&&allocation.getStatusTransitions().getPaidAt()>0);
            // Refunds are separate later facts; retain original captured gross PI identity.
            return new CashObject(intent.getId(),due,invoice.getCurrency().toUpperCase(Locale.ROOT),PaymentStatus.SUCCEEDED,Instant.ofEpochSecond(allocation.getStatusTransitions().getPaidAt()));
        }
    }
    private static Subscription.Period period(Long start,Long end){require(start!=null&&end!=null&&start>0&&end>start);return new Subscription.Period(Instant.ofEpochSecond(start),Instant.ofEpochSecond(end));}
    private static boolean zeros(Long... values){return Arrays.stream(values).allMatch(v->Objects.equals(v,0L));}
    private static boolean empty(List<?> list){return list!=null&&list.isEmpty();}
    private static void currency(String value,SubscriptionPriceTerms terms){require(terms.regularAmount().currency().toLowerCase(Locale.ROOT).equals(value));}
    private static void ref(String value,String prefix){require(value!=null&&value.matches(prefix+"[A-Za-z0-9_]{1,200}"));}
    private static void require(boolean value){if(!value)throw mismatch();}
    private static PaymentApplicationException mismatch(){return new PaymentApplicationException("PROVIDER_EVIDENCE_MISMATCH",409,"Provider billing evidence does not match");}
}
