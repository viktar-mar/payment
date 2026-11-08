package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalStore;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit-constructor store: deliberately not a Spring bean. The caller owns sorted operation,
 * Site/Customer/Account/Subscription/Bill locks and the transaction. No provider or nested transaction.
 * V34 INSERT guards independently check the exact committed/transient phase represented here.
 */
public final class JdbcOwnedFailedRenewalStore implements OwnedFailedRenewalStore {
    private final JdbcTemplate jdbc;
    private final EntityManagerFactory graceEntityManagerFactory;
    /** Existing entry points retain their constructor; grace requires explicit manager provenance. */
    public JdbcOwnedFailedRenewalStore(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc);this.graceEntityManagerFactory=null; }
    public JdbcOwnedFailedRenewalStore(JdbcTemplate jdbc,EntityManagerFactory entityManagerFactory) {
        this.jdbc=Objects.requireNonNull(jdbc);this.graceEntityManagerFactory=Objects.requireNonNull(entityManagerFactory);
    }

    @Override public Admission admitInTransaction(OwnedFirstRenewal.Admission upstream,
            OwnedFirstRenewal.LaterReady ready, OwnedFirstRenewal.Boundary boundary, Instant utcAt) {
        transaction();
        JdbcOwnedFirstRenewalStore.requireStoredFailedPredecessor(jdbc,upstream,ready,boundary);
        // Shared with the V34 exclusion triggers on both V33 success decision points.
        jdbc.queryForList("select lock_owned_renewal_branch(?)",upstream.id());
        var existing=jdbc.queryForList("select id from stripe_billing_failed_renewals where first_renewal_admission=?",UUID.class,upstream.id());
        if (!existing.isEmpty()) {
            var saved=load(existing.getFirst());
            if (!saved.firstRenewalAdmission().equals(upstream)||!saved.ready().equals(ready)||!saved.boundary().equals(boundary)) throw unavailable();
            return requireCurrentInTransaction(saved);
        }
        var result=new Admission(UUID.randomUUID(),upstream,ready,boundary,upstream.sourceSha(),utc(utcAt));
        var owner=upstream.receipt().intent().clock().owner();
        jdbc.update("""
            insert into stripe_billing_failed_renewals(id,first_renewal_admission,payment_account_id,site_id,customer_id,subscription_id,
             ready_id,boundary_id,source_sha,admitted_at) values(?,?,?,?,?,?,?,?,?,?)
            """,result.id(),upstream.id(),owner.account().value(),owner.site().value(),owner.customer().value(),
            upstream.receipt().intent().subscription(),ready.id(),boundary.id(),result.sourceSha(),time(result.admittedAt()));
        return requireCurrentInTransaction(result);
    }

    @Override public Admission requireCurrentInTransaction(Admission admission) {
        identity(admission);
        require("select owned_failed_renewal_current(?)",admission.id());
        return admission;
    }
    @Override public void lockGraceLastSecondInTransaction(Admission admission) {
        graceTransaction();
        // Authenticate descriptive persisted identity before deriving any lock target from it.
        identity(admission);
        jdbc.queryForList("select lock_owned_failed_grace(?)",admission.id());
        // Separate statement at READ COMMITTED: eligibility must be fresh after every lock.
        require("select owned_failed_grace_current(?)",admission.id());
    }
    @Override public GraceLastSecondPreparation prepareGraceLastSecondInTransaction(Admission admission,Failure failure,Instant utcAt) {
        lockGraceLastSecondInTransaction(admission);
        if(failure==null||!failure.equals(failureRow(admission.id())))throw unavailable();
        var at=utc(utcAt);var id=UUID.randomUUID();var target=OwnedFailedRenewal.graceLastSecondTarget(admission,failure);
        var key="billing-failed-grace-v1:"+admission.id()+":1";
        var p=new GraceLastSecondPreparation(id,admission.id(),failure.claimId(),failure.billId(),admission.ready().id(),failure.graceDeadline(),target,key,
            OwnedFailedRenewal.graceLastSecondFingerprint(id,admission,failure,target,key,at),at);
        int inserted=jdbc.update("""
            insert into stripe_billing_failed_grace_preparations(id,admission_id,failure_claim_id,renewal_bill,predecessor_ready,grace_deadline,target,operation_key,fingerprint,prepared_at)
            values(?,?,?,?,?,?,?,?,?,?)
            """,p.id(),p.admissionId(),p.failureClaimId(),p.billId(),p.predecessorReadyId(),time(p.graceDeadline()),p.target(),p.operationKey(),p.fingerprint(),time(p.preparedAt()));
        if(inserted!=1)throw unavailable();
        return requireGraceLastSecondInTransaction(admission,p);
    }
    @Override public GraceLastSecondPreparation requireGraceLastSecondInTransaction(Admission admission,GraceLastSecondPreparation preparation) {
        lockGraceLastSecondInTransaction(admission);
        if(preparation==null||!admission.id().equals(preparation.admissionId()))throw unavailable();
        var saved=jdbc.query("select * from stripe_billing_failed_grace_preparations where id=? and admission_id=?",(r,n)->new GraceLastSecondPreparation(
            uuid(r,"id"),uuid(r,"admission_id"),uuid(r,"failure_claim_id"),uuid(r,"renewal_bill"),uuid(r,"predecessor_ready"),instant(r,"grace_deadline"),
            r.getLong("target"),r.getString("operation_key"),r.getString("fingerprint"),instant(r,"prepared_at")),preparation.id(),admission.id())
            .stream().filter(preparation::equals).findFirst().orElseThrow(JdbcOwnedFailedRenewalStore::unavailable);
        var f=failureRow(admission.id());
        if(!saved.failureClaimId().equals(f.claimId())||!saved.billId().equals(f.billId())||!saved.predecessorReadyId().equals(admission.ready().id())
                ||!saved.graceDeadline().equals(f.graceDeadline())||saved.target()!=OwnedFailedRenewal.graceLastSecondTarget(admission,f)
                ||!saved.fingerprint().equals(OwnedFailedRenewal.graceLastSecondFingerprint(saved.id(),admission,f,saved.target(),saved.operationKey(),saved.preparedAt())))throw unavailable();
        require("select ?<=clock_timestamp()",time(saved.preparedAt()));
        return saved;
    }

    @Override public ReadClaim claimReadInTransaction(Admission admission, Phase phase, UUID invocation, Fence fence, Instant utcAt) {
        requireCurrentInTransaction(admission);
        var at=utc(utcAt);
        var result=new ReadClaim(UUID.randomUUID(),admission.id(),phase,invocation,at,at.plusSeconds(30),fence);
        int count=jdbc.update("""
            insert into stripe_billing_failed_renewal_reads(id,admission_id,phase,invocation,started_at,deadline_at,captured_revision,
             observation_sequence,selected_operation,selected_version) values(?,?,?,?,?,?,?,?,?,?) on conflict(admission_id,phase) do nothing
            """,result.id(),admission.id(),phase.name(),invocation,time(at),time(result.deadlineAt()),fence.revision(),
            fence.observationSequence(),fence.operationId(),fence.operationVersion());
        // Even the same invocation cannot obtain replacement authority.
        if(count!=1)throw unavailable();
        return requireReadInTransaction(admission,result);
    }

    @Override public ReadClaim requireReadInTransaction(Admission admission, ReadClaim claim) {
        requireCurrentInTransaction(admission);
        storedRead(admission,claim);
        require("""
            select exists(select 1 from subscriptions s join stripe_billing_failed_renewals d on d.subscription_id=s.id
             where d.id=? and s.revision=? and s.observation_sequence=?) and clock_timestamp()>=? and clock_timestamp()<?
            """,admission.id(),claim.fence().revision(),claim.fence().observationSequence(),time(claim.startedAt()),time(claim.deadlineAt()));
        if(claim.phase()==Phase.FAILED) {
            require("select not exists(select 1 from stripe_billing_failed_renewal_failures where admission_id=?)",admission.id());
        } else {
            var recovery=requireRecoveryInTransaction(admission);
            if(!recovery.operationId().equals(claim.fence().operationId())||recovery.operationVersion()!=claim.fence().operationVersion())throw unavailable();
        }
        return claim;
    }

    @Override public Failure recordFailureInTransaction(Admission admission, ReadClaim claim, FailedEvidence evidence) {
        // FactService has already changed revision. Requiring the pre-fact current fence here is incorrect.
        identity(admission);storedRead(admission,claim);
        if(claim.phase()!=Phase.FAILED||evidence==null)throw unavailable();
        claim.requireAccepted(evidence.snapshot().observedAt(),evidence.acceptedAt());
        requireSnapshot(admission,evidence.snapshot(),false,evidence.paymentIntent(),null);
        if(evidence.paymentIntent().equals(admission.firstRenewalAdmission().paymentIntent())
                ||evidence.allocation().equals(admission.firstRenewalAdmission().allocation()))throw unavailable();
        var at=utc(evidence.acceptedAt());
        int count=jdbc.update("""
            insert into stripe_billing_failed_renewal_failures(admission_id,claim_id,renewal_bill,payment_account_id,site_id,customer_id,subscription_id,
             bill_version,invoice_reference,allocation_reference,payment_intent_reference,period_start,period_end,grace_deadline,projected_revision,observation_sequence,recorded_at)
            select d.id,?,state.renewal_bill,d.payment_account_id,d.site_id,d.customer_id,d.subscription_id,state.bill_version,b.invoice_reference,?,?,
             b.period_start,b.period_end,state.grace_deadline,state.projected_revision,state.observation_sequence,?
            from stripe_billing_failed_renewals d join stripe_billing_first_renewal_boundary b on b.id=d.boundary_id
            join owned_failed_renewal_debt(d.id,null) state on true where d.id=?
            """,claim.id(),evidence.allocation(),evidence.paymentIntent(),time(at),admission.id());
        if(count!=1)throw unavailable();
        return requireFailureInTransaction(admission);
    }

    @Override public Failure requireFailureInTransaction(Admission admission) {
        requireCurrentInTransaction(admission);
        return failureRow(admission.id());
    }

    @Override public Recovery associateInTransaction(Admission admission, SubscriptionOperation operation,
            String allocation, String paymentIntent, Instant utcAt) {
        identity(admission);
        if(operation==null||operation.kind()!=SubscriptionOperation.Kind.RECOVER||operation.status()!=SubscriptionOperation.Status.RESERVED)throw unavailable();
        var failure=failureRow(admission.id());
        if(!failure.allocation().equals(allocation)||!failure.paymentIntent().equals(paymentIntent))throw unavailable();
        require("select not exists(select 1 from stripe_billing_failed_renewal_recoveries where admission_id=?)",admission.id());
        // The freshly claimed RECOVER exists before lifecycle locks, but the receipt does not yet exist.
        require("select owned_failed_renewal_failure_current(?,?)",admission.id(),operation.id());
        var original=admission.firstRenewalAdmission().receipt();var owner=original.intent().clock().owner();
        if(!operation.siteId().equals(owner.site())||!operation.customerId().equals(owner.customer())
                ||!operation.subscriptionId().equals(original.intent().subscription())
                ||!operation.authorityScope().equals(OwnedFailedRenewal.recoveryAuthority(admission))
                ||operation.expectedRevision()!=failure.revision()||operation.policyVersion()!=admission.firstRenewalAdmission().policyVersion())throw unavailable();
        requireReservedOperation(operation);
        // The ordinary immutable association is deliberately inserted in THIS transaction; no REQUIRES_NEW adapter call.
        jdbc.update("""
            insert into stripe_subscription_recovery_proofs(operation_id,site_id,customer_id,subscription_id,payment_account_id,operation_kind,
             billing_record_id,subscription_reference,invoice_reference,origin_operation,origin_step,origin_kind,pay_step,pay_kind,allocation_reference,intent_reference)
            values(?,?,?,?,?,'RECOVER',?,?,?,?,?,'SUBSCRIPTION_CREATE',null,null,?,?)
            """,operation.id(),owner.site().value(),owner.customer().value(),original.intent().subscription(),owner.account().value(),failure.billId(),
            original.reference(),failure.invoice(),original.intent().createOperation(),original.providerOperation(),allocation,paymentIntent);
        var at=utc(utcAt);
        jdbc.update("""
            insert into stripe_billing_failed_renewal_recoveries(admission_id,operation_id,renewal_bill,payment_account_id,site_id,customer_id,subscription_id,
             allocation_reference,payment_intent_reference,expected_revision,operation_version,associated_at)
            select id,?,?,payment_account_id,site_id,customer_id,subscription_id,?,?,?,?,? from stripe_billing_failed_renewals where id=?
            """,operation.id(),failure.billId(),allocation,paymentIntent,operation.expectedRevision(),operation.version(),time(at),admission.id());
        return requireRecoveryInTransaction(admission);
    }

    @Override public Recovery requireRecoveryInTransaction(Admission admission) {
        requireCurrentInTransaction(admission);
        return recoveryRow(admission.id());
    }

    @Override public Publication recordPublicationInTransaction(Admission admission, ReadClaim claim, Instant utcAt) {
        identity(admission);storedRead(admission,claim);
        if(claim.phase()!=Phase.RECOVERED)throw unavailable();
        var at=utc(utcAt);
        claim.requireAccepted(at,at);
        int count=jdbc.update("""
            insert into stripe_billing_failed_renewal_publications(admission_id,claim_id,operation_id,renewal_bill,renewal_payment,payment_account_id,
             site_id,customer_id,subscription_id,projected_revision,published_at)
            select d.id,?,r.operation_id,state.renewal_bill,state.renewal_payment,d.payment_account_id,d.site_id,d.customer_id,d.subscription_id,state.projected_revision,?
            from stripe_billing_failed_renewals d join stripe_billing_failed_renewal_recoveries r on r.admission_id=d.id
            join owned_failed_renewal_projected(d.id) state on true where d.id=?
            """,claim.id(),time(at),admission.id());
        if(count!=1)throw unavailable();
        return publicationInTransaction(admission).orElseThrow(JdbcOwnedFailedRenewalStore::unavailable);
    }

    @Override public Optional<Publication> publicationInTransaction(Admission admission) {
        requireCurrentInTransaction(admission);
        return jdbc.query("select * from stripe_billing_failed_renewal_publications where admission_id=?",(r,n)->new Publication(
            admission.id(),uuid(r,"claim_id"),uuid(r,"operation_id"),uuid(r,"renewal_bill"),uuid(r,"renewal_payment"),r.getLong("projected_revision"),instant(r,"published_at")),admission.id()).stream().findFirst();
    }

    @Override public void stopInTransaction(Admission admission, Reason reason, Instant utcAt) {
        transaction();if(admission==null||reason==null)throw unavailable();
        // Exact stored identity, without current-state/clock/policy predicates: drift must be stoppable.
        JdbcOwnedFirstRenewalStore.requireStoredFailedIdentity(jdbc,admission.firstRenewalAdmission(),admission.ready(),admission.boundary());
        if(!storedIdentityForStop(admission).equals(admission))throw unavailable();
        jdbc.update("insert into stripe_billing_failed_renewal_stops(admission_id,reason,stopped_at) values(?,?,?) on conflict(admission_id) do nothing",
            admission.id(),reason.name(),time(utc(utcAt)));
    }

    @Override public void requireRecoveryFactInTransaction(ApplySubscriptionSnapshotUseCase.Command command,
            Subscription lockedSubscription,List<SubscriptionOperation> lockedOperations,Instant billingAt,Instant utcAt) {
        transaction();
        if(command==null||command.account()==null||command.snapshot()==null||command.fence()==null||lockedSubscription==null||lockedOperations==null
                ||billingAt==null||utcAt==null||command.source()!=SubscriptionEvent.Source.RECONCILIATION)throw unavailable();
        var claims=jdbc.query("""
            select q.* from stripe_billing_failed_renewal_reads q join stripe_billing_failed_renewals d on d.id=q.admission_id
            where d.subscription_id=? and d.payment_account_id=? and q.phase='RECOVERED'
             and ('owned-failed-renewal:'||d.id::text||':'||q.phase||':'||q.invocation::text)=?
            """,this::readRow,command.subscriptionId(),command.account().id().value(),command.factKey());
        if(claims.size()!=1)throw unavailable();
        var claim=claims.getFirst();var admission=load(claim.admissionId());
        requireReadInTransaction(admission,claim);
        var failure=failureRow(admission.id());var recovery=recoveryRow(admission.id());
        var owner=admission.firstRenewalAdmission().receipt().intent().clock().owner();
        if(!command.fence().equals(claim.fence())||!command.subscriptionId().equals(lockedSubscription.id())
                ||!lockedSubscription.id().equals(admission.firstRenewalAdmission().receipt().intent().subscription())
                ||!command.account().id().equals(owner.account())||!command.account().externalAccountId().equals(owner.externalAccount())
                ||!command.account().credentialsRef().equals(owner.credentialsRef())
                ||!command.account().provider().name().equals("STRIPE")||!command.account().mode().name().equals("SANDBOX")||!command.account().status().name().equals("ACTIVE")
                ||lockedSubscription.revision()!=claim.fence().revision()||!lockedSubscription.paymentAccountId().equals(owner.account())
                ||!lockedSubscription.siteId().equals(owner.site())||!lockedSubscription.customerId().equals(owner.customer())
                ||lockedSubscription.billingStatus()!=Subscription.Status.PAST_DUE
                ||!lockedSubscription.billingPrice().equals(admission.firstRenewalAdmission().receipt().intent().terms())
                ||!lockedSubscription.billingPrice().equals(lockedSubscription.entitledPrice())
                ||!Objects.equals(lockedSubscription.currentPeriod(),failure.period())
                ||!Objects.equals(lockedSubscription.paidThrough(),failure.period().start())
                ||!Objects.equals(lockedSubscription.providerReference(),admission.firstRenewalAdmission().receipt().reference())
                ||lockedSubscription.pendingChange()!=null||lockedSubscription.cancellation()!=null||lockedSubscription.trial()!=null||lockedSubscription.terminalReason()!=null
                ||lockedSubscription.debt()==null||!lockedSubscription.debt().billingRecordId().equals(failure.billId())
                ||!lockedSubscription.debt().unpaidPeriod().equals(failure.period())||!Objects.equals(lockedSubscription.debt().graceDeadline(),failure.graceDeadline())
                ||billingAt.isBefore(failure.period().start())||!billingAt.isBefore(failure.period().end()))throw unavailable();
        claim.requireAccepted(command.snapshot().observedAt(),utcAt);
        var selected=lockedOperations.stream().filter(o->o.kind()==SubscriptionOperation.Kind.RECOVER).toList();
        if(selected.size()!=1)throw unavailable();
        var operation=selected.getFirst();
        if(!operation.id().equals(recovery.operationId())||operation.status()!=SubscriptionOperation.Status.RESERVED
                ||operation.version()!=recovery.operationVersion()||operation.expectedRevision()!=failure.revision()
                ||!operation.subscriptionId().equals(lockedSubscription.id())||!operation.siteId().equals(owner.site())||!operation.customerId().equals(owner.customer())
                ||!operation.authorityScope().equals(OwnedFailedRenewal.recoveryAuthority(admission))
                ||!operation.fingerprint().matches("v1\\.recover\\."+failure.billId()+"\\.[a-f0-9]{64}")
                ||!operation.providerOperationKey().equals("subscription:"+owner.account().value()+":"+operation.id()+":RECOVER")
                ||lockedOperations.stream().anyMatch(o->!o.id().equals(operation.id())
                    &&o.kind()!=SubscriptionOperation.Kind.RECONCILE
                    &&!(o.kind()==SubscriptionOperation.Kind.CREATE&&o.status()==SubscriptionOperation.Status.SUCCEEDED)))throw unavailable();
        requireReservedOperation(operation);
        requireSnapshot(admission,command.snapshot(),true,failure.paymentIntent(),operation.providerOperationKey());
    }

    private void requireReservedOperation(SubscriptionOperation operation) {
        require("""
            select exists(select 1 from subscription_operations where id=? and site_id=? and customer_id=? and subscription_id=?
             and kind='RECOVER' and status='RESERVED' and authority_scope=? and key_digest=? and fingerprint=? and provider_operation_key=?
             and expected_revision=? and policy_version=? and version=? and created_at=? and updated_at=? and provider_reference is not distinct from ?
             and safe_failure_code is not distinct from ?)
            """,operation.id(),operation.siteId().value(),operation.customerId().value(),operation.subscriptionId(),operation.authorityScope(),
            operation.keyDigest(),operation.fingerprint(),operation.providerOperationKey(),operation.expectedRevision(),operation.policyVersion(),operation.version(),
            time(operation.createdAt()),time(operation.updatedAt()),operation.providerReference(),operation.safeFailureCode());
    }

    private void identity(Admission admission) {
        transaction();if(admission==null)throw unavailable();
        JdbcOwnedFirstRenewalStore.requireStoredFailedPredecessor(jdbc,admission.firstRenewalAdmission(),admission.ready(),admission.boundary());
        if(!load(admission.id()).equals(admission))throw unavailable();
    }
    private Admission load(UUID id) { return JdbcOwnedFirstRenewalStore.storedFailedAdmission(jdbc,id); }
    /** Descriptive reload only, after requireStoredFailedIdentity has authenticated the predecessor.
     * In particular, this must not call the live receipt/advance loader: original CREATE status
     * can drift after admission, and that is a reason to consume the lane, not to prevent a stop.
     */
    Admission storedIdentityForStop(Admission admission) {
        transaction();
        var upstream=admission.firstRenewalAdmission();var owner=upstream.receipt().intent().clock().owner();
        return jdbc.query("select * from stripe_billing_failed_renewals where id=?",(r,n)-> {
            if(!uuid(r,"first_renewal_admission").equals(upstream.id())
                    ||!uuid(r,"payment_account_id").equals(owner.account().value())
                    ||!uuid(r,"site_id").equals(owner.site().value())||!uuid(r,"customer_id").equals(owner.customer().value())
                    ||!uuid(r,"subscription_id").equals(upstream.receipt().intent().subscription())
                    ||!uuid(r,"ready_id").equals(admission.ready().id())||!uuid(r,"boundary_id").equals(admission.boundary().id()))throw unavailable();
            return new Admission(uuid(r,"id"),upstream,admission.ready(),admission.boundary(),r.getString("source_sha"),instant(r,"admitted_at"));
        },admission.id()).stream().findFirst().orElseThrow(JdbcOwnedFailedRenewalStore::unavailable);
    }
    private ReadClaim storedRead(Admission admission,ReadClaim claim) {
        if(claim==null||!claim.admissionId().equals(admission.id()))throw unavailable();
        return jdbc.query("select * from stripe_billing_failed_renewal_reads where id=? and admission_id=?",this::readRow,claim.id(),admission.id())
            .stream().filter(claim::equals).findFirst().orElseThrow(JdbcOwnedFailedRenewalStore::unavailable);
    }
    private ReadClaim readRow(ResultSet r,int n)throws SQLException {
        return new ReadClaim(uuid(r,"id"),uuid(r,"admission_id"),Phase.valueOf(r.getString("phase")),uuid(r,"invocation"),instant(r,"started_at"),instant(r,"deadline_at"),
            new Fence(r.getLong("captured_revision"),uuid(r,"selected_operation"),r.getObject("selected_version",Long.class),null,null,r.getLong("observation_sequence")));
    }
    Failure failureRow(UUID admission) {
        return jdbc.query("select * from stripe_billing_failed_renewal_failures where admission_id=?",(r,n)->new Failure(admission,uuid(r,"claim_id"),uuid(r,"renewal_bill"),r.getLong("bill_version"),
            r.getString("invoice_reference"),r.getString("allocation_reference"),r.getString("payment_intent_reference"),new Subscription.Period(instant(r,"period_start"),instant(r,"period_end")),
            instant(r,"grace_deadline"),r.getLong("projected_revision"),r.getLong("observation_sequence"),instant(r,"recorded_at")),admission).stream().findFirst().orElseThrow(JdbcOwnedFailedRenewalStore::unavailable);
    }
    private Recovery recoveryRow(UUID admission) {
        return jdbc.query("select * from stripe_billing_failed_renewal_recoveries where admission_id=?",(r,n)->new Recovery(admission,uuid(r,"operation_id"),uuid(r,"renewal_bill"),
            r.getString("allocation_reference"),r.getString("payment_intent_reference"),r.getLong("expected_revision"),r.getLong("operation_version"),instant(r,"associated_at")),admission)
            .stream().findFirst().orElseThrow(JdbcOwnedFailedRenewalStore::unavailable);
    }
    static void requireSnapshot(Admission admission,Snapshot snapshot,boolean recovered,String paymentIntent,String operationKey) {
        var original=admission.firstRenewalAdmission();var receipt=original.receipt();var terms=receipt.intent().terms();
        if(!snapshot.subscriptionReference().equals(receipt.reference())||!snapshot.billingPrice().equals(terms)||!Objects.equals(snapshot.period(),admission.boundary().period())
                ||snapshot.status()!=(recovered?Subscription.Status.ACTIVE:Subscription.Status.PAST_DUE)||snapshot.trial()!=null||snapshot.cancellationAt()!=null
                ||snapshot.providerRevision()!=0||snapshot.trialEvidence()!=null||snapshot.externalWithdrawalEvidence()!=null||!snapshot.recoveryObservations().isEmpty()
                ||snapshot.invoices().size()!=2||!Objects.equals(snapshot.operationKey(),operationKey))throw unavailable();
        if(recovered) {
            if(snapshot.operationObservation()==null||snapshot.operationObservation().state()!=OperationState.APPLIED
                    ||!snapshot.operationObservation().operationKey().equals(operationKey))throw unavailable();
        } else if(snapshot.operationObservation()!=null)throw unavailable();
        var initial=snapshot.invoices().stream().filter(i->i.kind()==BillingRecord.Kind.INITIAL).toList();
        var renewal=snapshot.invoices().stream().filter(i->i.kind()==BillingRecord.Kind.RENEWAL).toList();
        if(initial.size()!=1||renewal.size()!=1)throw unavailable();
        var first=initial.getFirst();var second=renewal.getFirst();
        if(!first.reference().equals(original.invoice())||!first.period().equals(new Subscription.Period(Instant.ofEpochSecond(receipt.periodStart()),Instant.ofEpochSecond(receipt.periodEnd())))
                ||first.settlement()!=SettlementKind.CASH||!second.reference().equals(admission.boundary().renewalInvoice())||!second.period().equals(admission.boundary().period())
                ||second.settlement()!=(recovered?SettlementKind.CASH:SettlementKind.OPEN))throw unavailable();
        for(var invoice:snapshot.invoices()) {
            if(!invoice.price().equals(terms)||invoice.previousPrice()!=null||invoice.amountDueMinor()!=terms.regularAmount().minorUnits()
                    ||!invoice.currency().equals(terms.regularAmount().currency()))throw unavailable();
        }
        cash(first,original.paymentIntent(),snapshot.observedAt());
        if(recovered)cash(second,paymentIntent,snapshot.observedAt());
        else if(!second.cashObjects().isEmpty())throw unavailable();
    }
    private static void cash(Invoice invoice,String intent,Instant observedAt) {
        if(invoice.cashObjects().size()!=1)throw unavailable();
        var cash=invoice.cashObjects().getFirst();
        if(!cash.reference().equals(intent)||cash.status()!=PaymentStatus.SUCCEEDED||cash.amountMinor()!=invoice.amountDueMinor()
                ||!cash.currency().equals(invoice.currency())||cash.occurredAt().isAfter(observedAt))throw unavailable();
    }
    private void require(String sql,Object... args) { if(!Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)))throw unavailable(); }
    private static UUID uuid(ResultSet r,String field)throws SQLException { return r.getObject(field,UUID.class); }
    private static Instant instant(ResultSet r,String field)throws SQLException { return r.getTimestamp(field).toInstant(); }
    private static Instant utc(Instant at) { if(at==null)throw unavailable();return at.truncatedTo(ChronoUnit.MICROS); }
    private static Timestamp time(Instant at) { return Timestamp.from(utc(at)); }
    private static void transaction() { if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Owned failed renewal requires caller transaction"); }
    private void graceTransaction() {
        transaction();var dataSource=jdbc.getDataSource();
        if(graceEntityManagerFactory==null||dataSource==null
                ||!(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder holder)
                ||!(TransactionSynchronizationManager.getResource(graceEntityManagerFactory) instanceof EntityManagerHolder owner))throw graceTransactionUnavailable();
        try {
            var em=owner.getEntityManager();
            if(!em.isOpen()||!em.getTransaction().isActive())throw graceTransactionUnavailable();
            // A synchronized JDBC holder may belong to a secondary pool. Even !autoCommit
            // alone does not prove ownership: match the known active JPA transaction's
            // already-exposed physical connection, without opening a connection or transaction.
            var bound=DataSourceUtils.getTargetConnection(holder.getConnection());
            if(bound.getAutoCommit()||!Boolean.TRUE.equals(em.unwrap(Session.class).doReturningWork(connection->
                    DataSourceUtils.getTargetConnection(connection)==bound&&!connection.getAutoCommit())))throw graceTransactionUnavailable();
        }catch(java.sql.SQLException|RuntimeException failure){throw graceTransactionUnavailable();}
    }
    private static IllegalStateException graceTransactionUnavailable(){return new IllegalStateException("Owned failed renewal requires caller DataSource transaction");}
    private static IllegalStateException unavailable() { return new IllegalStateException("Owned failed renewal authority unavailable"); }
}
