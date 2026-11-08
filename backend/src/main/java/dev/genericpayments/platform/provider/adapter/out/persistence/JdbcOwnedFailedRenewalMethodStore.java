package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.*;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedRenewalMethodStore;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.adapter.out.persistence.SubscriptionOperationPersistenceAdapter;
import dev.genericpayments.platform.subscriptions.adapter.out.persistence.SubscriptionPersistenceAdapter;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Command;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore.Setup;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit constructor only; caller owns the transaction and lifecycle locks. V35 guards every insert. */
public final class JdbcOwnedFailedRenewalMethodStore implements OwnedFailedRenewalMethodStore {
    private final JdbcTemplate jdbc;
    public JdbcOwnedFailedRenewalMethodStore(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }

    static boolean ownedStepResource(JdbcTemplate jdbc,PaymentAccountId account,UUID resource) {
        return !jdbc.queryForList("""
            select r.id from stripe_billing_failed_method_reservations r join stripe_billing_failed_renewals d on d.id=r.admission_id
            where r.setup_operation=? and d.payment_account_id=?
            """,UUID.class,resource,account.value()).isEmpty();
    }
    static Reservation stepReservation(JdbcTemplate jdbc,PaymentAccountId account,UUID resource) {
        var ids=jdbc.queryForList("""
            select r.id from stripe_billing_failed_method_reservations r join stripe_billing_failed_renewals d on d.id=r.admission_id
            where r.setup_operation=? and d.payment_account_id=?
            """,UUID.class,resource,account.value());
        if(ids.size()!=1)throw unavailable();
        return new JdbcOwnedFailedRenewalMethodStore(jdbc).reservation(ids.getFirst());
    }
    /** Provider reserve/claim/dispatch/ACK phase. No own ACK or not-yet-created setup proof is required. */
    static void requireStepResource(JdbcTemplate jdbc,PaymentAccountId account,ProviderOperation.Kind kind,UUID resource,String key,String fingerprint) {
        if(kind!=ProviderOperation.Kind.SETUP_INTENT_CREATE&&kind!=ProviderOperation.Kind.SUBSCRIPTION_UPDATE)throw unavailable();
        jdbc.queryForList("select id from subscription_operations where id=? for update",UUID.class,resource);
        var store=new JdbcOwnedFailedRenewalMethodStore(jdbc);var r=stepReservation(jdbc,account,resource);
        store.requireReservationInTransaction(r);
        store.require("select owned_method_debt(?,0,?) and owned_method_initial_operation(?)",r.id(),resource,r.id());
        var o=store.operation(r,resource);
        if(o.status()!=SubscriptionOperation.Status.RESERVED||o.version()!=r.initialOperationVersion()||o.safeFailureCode()!=null||o.providerReference()!=null)throw unavailable();
        boolean method=kind==ProviderOperation.Kind.SUBSCRIPTION_UPDATE;
        String expected=jdbc.queryForObject("select owned_method_step_fingerprint(?,?)",String.class,r.id(),method);
        if(!Objects.equals(fingerprint,expected)||!key.equals(o.providerOperationKey()+(method?":METHOD":":SETUP")))throw unavailable();
        if(method) {
            store.require("select owned_method_browser_permit(?)",resource);
            var p=store.setup(r);var setup=JdbcProviderOperationStore.row(jdbc,p.step()).orElseThrow(JdbcOwnedFailedRenewalMethodStore::unavailable);
            var owner=r.admission().firstRenewalAdmission().receipt().intent().clock().owner();
            String setupFingerprint=jdbc.queryForObject("select owned_method_step_fingerprint(?,false)",String.class,r.id());
            if(p.method()==null||!p.method().matches("pm_[A-Za-z0-9]{1,252}")||!p.operation().equals(resource)
                    ||!p.subscription().equals(o.subscriptionId())||!p.account().equals(account)
                    ||setup.kind()!=ProviderOperation.Kind.SETUP_INTENT_CREATE||setup.status()!=ProviderOperation.Status.ACKNOWLEDGED
                    ||!setup.accountId().equals(account)||!setup.resourceId().equals(resource)||!setup.providerReference().equals(p.reference())
                    ||!setup.operationKey().equals(o.providerOperationKey()+":SETUP")||!setup.fingerprint().equals(setupFingerprint)
                    ||setup.firstDispatchedAt()==null||setup.leaseOwner()!=null||setup.leaseExpiresAt()!=null
                    ||!setup.externalAccountId().equals(owner.externalAccount())||!setup.credentialsRef().equals(owner.credentialsRef()))throw unavailable();
        }
    }

    @Override public Reservation reserveInTransaction(OwnedFailedRenewal.Admission a,OwnedFailedRenewal.Failure f,
            SubscriptionOperation setup,CustomerBinding binding,Sample sample,Instant now) {
        return reserve(a,f,setup,binding,sample,now,null);
    }
    @Override public Reservation reserveBrowserInTransaction(OwnedFailedRenewal.Admission a,OwnedFailedRenewal.Failure f,
            SubscriptionOperation setup,CustomerBinding binding,Sample sample,Instant now,Enrollment enrollment) {
        transaction();if(enrollment==null)throw unavailable();return reserve(a,f,setup,binding,sample,now,enrollment);
    }
    private Reservation reserve(OwnedFailedRenewal.Admission a,OwnedFailedRenewal.Failure f,
            SubscriptionOperation setup,CustomerBinding binding,Sample sample,Instant now,Enrollment enrollment) {
        transaction();if(a==null||f==null||setup==null)throw unavailable();
        JdbcOwnedFirstRenewalStore.requireStoredFailedPredecessor(jdbc,a.firstRenewalAdmission(),a.ready(),a.boundary());
        if(!JdbcOwnedFirstRenewalStore.storedFailedAdmission(jdbc,a.id()).equals(a)
                ||!new JdbcOwnedFailedRenewalStore(jdbc).failureRow(a.id()).equals(f)
                ||setup.kind()!=SubscriptionOperation.Kind.SETUP||setup.status()!=SubscriptionOperation.Status.RESERVED||setup.version()!=0)throw unavailable();
        jdbc.queryForList("select lock_owned_renewal_branch(?)",a.firstRenewalAdmission().id());
        var r=new Reservation(UUID.randomUUID(),a,f,setup.id(),setup.fingerprint(),setup.version(),binding,sample,utc(now));
        if(!operation(r,setup.id()).equals(setup))throw unavailable();
        int count=jdbc.update("""
            insert into stripe_billing_failed_method_reservations(id,admission_id,setup_operation,request_fingerprint,initial_operation_version,
             operation_snapshot,binding_id,binding_version,binding_reference,binding_creation_operation,billing_at,billing_mode,reserved_at,
             browser_session_id,browser_origin,browser_session_expires_at)
            select ?,?,?,?, ?,to_jsonb(o),?,?,?,?,?,'ISOLATED',?,?,?,? from subscription_operations o where o.id=?
            """,r.id(),a.id(),setup.id(),r.requestFingerprint(),r.initialOperationVersion(),binding.id(),binding.version(),binding.providerReference(),
            binding.creationOperationId(),time(sample.billingAt()),time(r.reservedAt()),enrollment==null?null:enrollment.sessionId(),
            enrollment==null?null:enrollment.origin(),enrollment==null?null:time(enrollment.sessionExpiresAt()),setup.id());
        if(count!=1)throw unavailable();
        return requireReservationInTransaction(r);
    }
    private JdbcOwnedMethodBrowserStore browser(){return new JdbcOwnedMethodBrowserStore(jdbc,this);}
    @Override public Enrollment requireBrowserSetupEnrollmentInTransaction(Reservation r,CustomerSessionContext s,Instant now){return browser().setupEnrollment(r,s,now);}
    @Override public Enrollment requireBrowserRecoveryIdentityInTransaction(Reservation r,CustomerSessionContext s){return browser().recoveryIdentity(r,s);}
    @Override public Enrollment requireBrowserRecoveryEnrollmentInTransaction(Reservation r,CustomerSessionContext s,Instant now){return browser().recoveryEnrollment(r,s,now);}
    @Override public Optional<Enrollment> requireReplyEnrollmentInTransaction(Reservation r,CustomerSessionContext s,Instant now){return browser().replyEnrollment(r,s,now);}
    @Override public BoundSetup requireBrowserBoundSetupInTransaction(Reservation r,CustomerSessionContext s,Instant now){
        return requireBrowserSetup(r,s,now,true);
    }
    @Override public BoundSetup requireBrowserUnboundSetupInTransaction(Reservation r,CustomerSessionContext s,Instant now){
        return requireBrowserSetup(r,s,now,false);
    }
    private BoundSetup requireBrowserSetup(Reservation r,CustomerSessionContext s,Instant now,boolean methodRequired){
        transaction();if(r==null||s==null||now==null)throw unavailable();
        var owner=r.admission().firstRenewalAdmission().receipt().intent().clock().owner();
        var o=operation(r,r.setupOperationId());
        if(methodRequired){
            var fingerprint=jdbc.queryForObject("select owned_method_step_fingerprint(?,true)",String.class,r.id());
            requireStepResource(jdbc,owner.account(),ProviderOperation.Kind.SUBSCRIPTION_UPDATE,o.id(),o.providerOperationKey()+":METHOD",fingerprint);
        }else{
            // V36 pending is specifically unbound and refuses any existing METHOD row. It joins caller locks.
            browser().pending(r,s,now);
            if(jdbc.queryForList("select operation_id from stripe_subscription_setup_proofs where operation_id=? and payment_account_id=? for update",
                    UUID.class,o.id(),owner.account().value()).size()!=1)throw unavailable();
            require("select owned_method_browser_permit(?)",o.id());
        }
        var proof=setup(r);var step=JdbcProviderOperationStore.row(jdbc,proof.step()).orElseThrow(JdbcOwnedFailedRenewalMethodStore::unavailable);
        var setupFingerprint=jdbc.queryForObject("select owned_method_step_fingerprint(?,false)",String.class,r.id());
        if(!r.setupOperationId().equals(proof.operation())||!o.subscriptionId().equals(proof.subscription())||!owner.account().equals(proof.account())
            ||proof.reference()==null||!proof.reference().matches("seti_[A-Za-z0-9]{1,250}")
            ||(methodRequired?(proof.method()==null||!proof.method().matches("pm_[A-Za-z0-9]{1,252}")):proof.method()!=null)
            ||step.kind()!=ProviderOperation.Kind.SETUP_INTENT_CREATE||step.status()!=ProviderOperation.Status.ACKNOWLEDGED||step.version()!=2
            ||!owner.account().equals(step.accountId())||!owner.externalAccount().equals(step.externalAccountId())||!owner.credentialsRef().equals(step.credentialsRef())
            ||!o.id().equals(step.resourceId())||!Objects.equals(o.providerOperationKey()+":SETUP",step.operationKey())||!Objects.equals(setupFingerprint,step.fingerprint())
            ||!proof.reference().equals(step.providerReference())||step.firstDispatchedAt()==null||step.firstDispatchedAt().isBefore(r.reservedAt())||step.firstDispatchedAt().isAfter(utc(now))
            ||!step.firstDispatchedAt().plusSeconds(23*3600).equals(step.retryBefore())||step.leaseOwner()!=null||step.leaseExpiresAt()!=null)throw unavailable();
        requireBoundSetupRootChronology(browser().root(r.id()).orElse(null),step);
        // Rechecks exact original session/scopes and fresh DB UTC after every potentially blocking read.
        var enrollment=browser().setupEnrollment(r,s,now);
        return new BoundSetup(enrollment,proof,step);
    }
    @Override public BoundSetup bindBrowserSetupInTransaction(BindingPreparationContext expected,CustomerSessionContext s,String method,Instant now){
        transaction();if(expected==null||s==null||now==null||method==null||!method.matches("pm_[A-Za-z0-9]{1,252}"))throw unavailable();
        var original=expected.original();var r=original.reservation();
        var unbound=requireBrowserUnboundSetupInTransaction(r,s,now);
        if(!original.enrollment().equals(unbound.enrollment())||!expected.setupProof().equals(unbound.setupProof())
            ||!expected.setupStep().equals(unbound.setupStep()))throw unavailable();
        var proof=unbound.setupProof();var owner=r.admission().firstRenewalAdmission().receipt().intent().clock().owner();
        // Match every immutable proof identity column; an already-bound proof is never adopted or replaced.
        int changed=jdbc.update("""
            update stripe_subscription_setup_proofs set method_reference=?
            where operation_id=? and site_id=? and customer_id=? and subscription_id=? and payment_account_id=?
            and operation_kind='SETUP' and step_id=? and setup_reference=? and method_reference is null
            """,method,proof.operation(),owner.site().value(),owner.customer().value(),proof.subscription(),proof.account().value(),proof.step(),proof.reference());
        if(changed!=1)throw unavailable();
        var actual=setup(r);
        if(!actual.equals(new Setup(proof.operation(),proof.subscription(),proof.account(),proof.step(),proof.reference(),method)))throw unavailable();
        // No independently committed ordinary proof-store call. Fresh DBUTC after the update's locks.
        var enrollment=browser().setupEnrollment(r,s,now);
        if(!original.enrollment().equals(enrollment))throw unavailable();
        return new BoundSetup(enrollment,actual,unbound.setupStep());
    }
    static void requireBoundSetupRootChronology(Root root,ProviderOperation setupStep){
        if(root==null||setupStep==null||setupStep.firstDispatchedAt()==null||root.issuedAt().isBefore(setupStep.firstDispatchedAt()))throw unavailable();
    }
    @Override public Pending requireBrowserPendingInTransaction(Reservation r,CustomerSessionContext s,Instant now){return browser().pending(r,s,now);}
    @Override public Optional<Root> browserRootInTransaction(UUID r){return browser().root(r);}
    @Override public Root insertBrowserRootInTransaction(Pending p,Root r){return browser().insert(p,r);}
    @Override public void requireBrowserActionInTransaction(Pending p,Root r,Instant now){browser().action(p,r,now);}
    @Override public Selection startBrowserSelectionInTransaction(Pending p,Root r,String key,Instant now){return browser().start(p,r,key,now);}
    @Override public void finishBrowserSelectionInTransaction(Pending p,Root r,Selection s,Instant now){browser().finish(p,r,s,now);}
    @Override public boolean consumeBrowserFirstInTransaction(Pending p,Root r,String key,String fingerprint,Instant now){return browser().consume(p,r,key,fingerprint,now);}
    @Override public Reservation requireReservationInTransaction(Reservation expected) {
        transaction();if(expected==null)throw unavailable();
        var a=expected.admission();
        JdbcOwnedFirstRenewalStore.requireStoredFailedIdentity(jdbc,a.firstRenewalAdmission(),a.ready(),a.boundary());
        if(!reservation(expected.id()).equals(expected))throw unavailable();
        require("select owned_method_identity(?)",expected.id());
        // Immutable identity only: live operation status, billing sequence and stop are checked by phase predicates.
        return expected;
    }
    @Override public MethodReadContext requirePreparedMethodInTransaction(Reservation r) {
        requireReservationInTransaction(r);
        require("select owned_method_debt(?,0,?) and owned_method_steps(?) and owned_method_initial_operation(?)",r.id(),r.setupOperationId(),r.id(),r.id());
        var o=operation(r,r.setupOperationId());
        if(o.status()!=SubscriptionOperation.Status.RESERVED||o.version()!=r.initialOperationVersion()||o.safeFailureCode()!=null)throw unavailable();
        return methodContext(r,o);
    }
    @Override public ReadClaim claimReadInTransaction(Reservation r,Phase phase,UUID invocation,Fence fence,Sample sample,Instant now) {
        requireReservationInTransaction(r);
        var at=utc(now);var q=new ReadClaim(UUID.randomUUID(),r.id(),phase,invocation,at,at.plusSeconds(30),fence,sample);
        if(!sample.equals(r.billingSample()))throw unavailable();
        int count=jdbc.update("""
            insert into stripe_billing_failed_method_reads(id,reservation_id,phase,invocation,started_at,deadline_at,captured_revision,
             observation_sequence,selected_operation,selected_version,operation_snapshot,setup_step_snapshot,method_step_snapshot,billing_at,billing_mode)
            select ?,?,?,?,?,?,?,?,?,?,to_jsonb(o),to_jsonb(setup),to_jsonb(method),?,'ISOLATED'
            from stripe_subscription_setup_proofs p join provider_operations setup on setup.id=p.step_id
            join provider_operations method on method.resource_id=p.operation_id and method.payment_account_id=p.payment_account_id and method.kind='SUBSCRIPTION_UPDATE'
            join subscription_operations o on o.id=? where p.operation_id=?
            """,q.id(),r.id(),phase.name(),invocation,time(at),time(q.deadlineAt()),fence.revision(),fence.observationSequence(),
            fence.operationId(),fence.operationVersion(),time(sample.billingAt()),fence.operationId(),r.setupOperationId());
        if(count!=1)throw unavailable();
        return requireReadInTransaction(r,q);
    }
    @Override public ReadClaim requireReadInTransaction(Reservation r,ReadClaim q) {
        requireReservationInTransaction(r);storedRead(r,q);liveWindow(q);
        if(!q.billingSample().equals(r.billingSample()))throw unavailable();
        require("""
            select exists(select 1 from stripe_billing_failed_method_reservations r join stripe_billing_failed_renewals d on d.id=r.admission_id
            join subscriptions s on s.id=d.subscription_id join subscription_operations o on o.id=?
            join stripe_billing_failed_method_reads q on q.id=?
            join stripe_subscription_setup_proofs p on p.operation_id=r.setup_operation
            join provider_operations setup on setup.id=p.step_id
            join provider_operations method on method.resource_id=r.setup_operation and method.payment_account_id=d.payment_account_id and method.kind='SUBSCRIPTION_UPDATE'
            where r.id=? and s.revision=? and s.observation_sequence=? and o.version=? and to_jsonb(o)=q.operation_snapshot
             and to_jsonb(setup)=q.setup_step_snapshot and to_jsonb(method)=q.method_step_snapshot)
            """,q.fence().operationId(),q.id(),r.id(),q.fence().revision(),q.fence().observationSequence(),q.fence().operationVersion());
        if(q.phase()==Phase.METHOD) {
            if(!q.fence().operationId().equals(r.setupOperationId()))throw unavailable();
            require("select owned_method_pre(?)",r.id());
        } else {
            require("select owned_method_recovery_pre(?)",r.id());
            var x=recovery(r);
            if(!q.fence().operationId().equals(x.association().operationId())||q.fence().operationVersion()!=x.association().operationVersion())throw unavailable();
        }
        return q;
    }
    @Override public MethodReadContext requireMethodContextInTransaction(Reservation r,ReadClaim q) {
        requireReadInTransaction(r,q);if(q.phase()!=Phase.METHOD)throw unavailable();
        return methodContext(r,operation(r,q.fence().operationId()));
    }
    @Override public Completion recordCompletionInTransaction(Reservation r,ReadClaim q,MethodEvidence e,Instant now) {
        // Effects already happened. Authenticate the saved claim directly, never call requireRead/prestate here.
        requireReservationInTransaction(r);storedRead(r,q);liveWindow(q);
        if(q.phase()!=Phase.METHOD||e==null)throw unavailable();
        q.requireAccepted(e.snapshot().observedAt(),e.acceptedAt());q.requireAccepted(e.acceptedAt(),now);
        var p=setup(r);
        if(!e.setupIntent().equals(p.reference())||!e.method().equals(p.method())||!e.snapshot().paymentMethodReady())throw unavailable();
        JdbcOwnedFailedRenewalStore.requireSnapshot(r.admission(),e.snapshot(),false,r.failure().paymentIntent(),null);
        int count=jdbc.update("""
            insert into stripe_billing_failed_method_completions(reservation_id,claim_id,setup_operation,setup_final_version,
             setup_step,setup_step_version,method_step,method_step_version,setup_reference,method_reference,projected_revision,observation_sequence,completed_at)
            select ?,?,o.id,o.version,setup.id,setup.version,method.id,method.version,p.setup_reference,p.method_reference,s.revision,s.observation_sequence,?
            from stripe_billing_failed_method_reservations r join stripe_billing_failed_renewals d on d.id=r.admission_id
            join subscription_operations o on o.id=r.setup_operation join subscriptions s on s.id=d.subscription_id
            join stripe_subscription_setup_proofs p on p.operation_id=o.id join provider_operations setup on setup.id=p.step_id
            join provider_operations method on method.resource_id=o.id and method.payment_account_id=d.payment_account_id and method.kind='SUBSCRIPTION_UPDATE' where r.id=?
            """,r.id(),q.id(),time(now),r.id());
        if(count!=1)throw unavailable();
        return requireCompletionInTransaction(r,completion(r));
    }
    @Override public Completion requireCompletionInTransaction(Reservation r,Completion expected) {
        requireReservationInTransaction(r);
        if(expected==null||!completion(r).equals(expected))throw unavailable();
        require("select owned_method_lineage(?) and owned_method_completed(?)",r.id(),r.id());
        return expected;
    }
    @Override public Recovery associateRecoveryInTransaction(Reservation r,Completion c,SubscriptionOperation recover,Instant now) {
        requireCompletionInTransaction(r,c);
        if(recover==null||recover.kind()!=SubscriptionOperation.Kind.RECOVER||recover.status()!=SubscriptionOperation.Status.RESERVED
                ||recover.version()!=0||!operation(r,recover.id()).equals(recover))throw unavailable();
        require("select owned_method_debt(?,1,?)",r.id(),recover.id());
        var f=r.failure();var original=r.admission().firstRenewalAdmission().receipt();var owner=original.intent().clock().owner();
        jdbc.update("""
            insert into stripe_subscription_recovery_proofs(operation_id,site_id,customer_id,subscription_id,payment_account_id,operation_kind,
             billing_record_id,subscription_reference,invoice_reference,origin_operation,origin_step,origin_kind,pay_step,pay_kind,allocation_reference,intent_reference)
            values(?,?,?,?,?,'RECOVER',?,?,?,?,?,'SUBSCRIPTION_CREATE',null,null,?,?)
            """,recover.id(),owner.site().value(),owner.customer().value(),original.intent().subscription(),owner.account().value(),f.billId(),
            original.reference(),f.invoice(),original.intent().createOperation(),original.providerOperation(),f.allocation(),f.paymentIntent());
        jdbc.update("""
            insert into stripe_billing_failed_method_recoveries(reservation_id,operation_id,observation_sequence,operation_version,associated_at)
            values(?,?,?,?,?)
            """,r.id(),recover.id(),c.observationSequence(),recover.version(),time(now));
        return requireRecoveryInTransaction(r,recovery(r));
    }
    @Override public Recovery requireRecoveryInTransaction(Reservation r,Recovery expected) {
        requireReservationInTransaction(r);
        if(expected==null||!recovery(r).equals(expected))throw unavailable();
        require("select owned_method_recovery_pre(?)",r.id());return expected;
    }
    @Override public RecoveredReadContext requireRecoveredContextInTransaction(Reservation r,ReadClaim q) {
        requireReadInTransaction(r,q);if(q.phase()!=Phase.RECOVERED)throw unavailable();
        return new RecoveredReadContext(account(r),subscription(r),r.binding(),operation(r,q.fence().operationId()),completion(r),recovery(r),q.billingSample());
    }
    @Override public void requireUnobservedRecoveryInTransaction(Reservation r,Recovery expected){
        requireRecoveryInTransaction(r,expected);
        require("select not exists(select 1 from stripe_billing_failed_method_reads where reservation_id=? and phase='RECOVERED') and not exists(select 1 from stripe_billing_failed_method_publications where reservation_id=?)",r.id(),r.id());
    }
    @Override public void requireRecoveryConfirmationIdentityInTransaction(RecoveryPreparationContext expected){
        if(expected==null)throw unavailable();var r=expected.reservation();requireReservationInTransaction(r);
        if(!completion(r).equals(expected.completion())||!recovery(r).equals(expected.recovery())||!r.binding().equals(expected.binding()))throw unavailable();
        require("""
            select exists(select 1 from stripe_billing_failed_method_reservations r
            join stripe_billing_failed_renewals d on d.id=r.admission_id
            where r.id=? and d.payment_account_id=? and d.site_id=? and d.customer_id=? and d.subscription_id=?
              and r.browser_session_id=? and r.browser_origin=? and r.browser_session_expires_at=?
              and owned_recovery_confirm_identity(r.id))
            """,r.id(),expected.account().id().value(),expected.subscription().siteId().value(),expected.subscription().customerId().value(),expected.subscription().id(),
            expected.enrollment().sessionId(),expected.enrollment().origin(),time(expected.enrollment().sessionExpiresAt()));
        var owner=r.admission().firstRenewalAdmission().receipt().intent().clock().owner();
        if(!expected.account().id().equals(owner.account())||!expected.account().externalAccountId().equals(owner.externalAccount())
                ||!expected.account().credentialsRef().equals(owner.credentialsRef()))throw unavailable();
    }
    @Override public Publication recordPublicationInTransaction(Reservation r,ReadClaim q,Instant now) {
        requireReservationInTransaction(r);storedRead(r,q);liveWindow(q);
        if(q.phase()!=Phase.RECOVERED)throw unavailable();q.requireAccepted(now,now);
        int count=jdbc.update("""
            insert into stripe_billing_failed_method_publications(reservation_id,claim_id,operation_id,renewal_payment,projected_revision,observation_sequence,published_at)
            select ?,?,x.operation_id,state.renewal_payment,state.projected_revision,state.observation_sequence,?
            from stripe_billing_failed_method_recoveries x join owned_method_projected(x.reservation_id) state on true where x.reservation_id=?
            """,r.id(),q.id(),time(now),r.id());
        if(count!=1)throw unavailable();return publicationInTransaction(r).orElseThrow(JdbcOwnedFailedRenewalMethodStore::unavailable);
    }
    @Override public Optional<Publication> publicationInTransaction(Reservation r) {
        requireReservationInTransaction(r);
        var rows=jdbc.query("select * from stripe_billing_failed_method_publications where reservation_id=?",(row,n)->new Publication(r.id(),
            new OwnedFailedRenewal.Publication(r.admission().id(),uuid(row,"claim_id"),uuid(row,"operation_id"),r.failure().billId(),uuid(row,"renewal_payment"),
                row.getLong("projected_revision"),instant(row,"published_at")),row.getLong("observation_sequence")),r.id());
        if(!rows.isEmpty())require("select exists(select 1 from stripe_billing_failed_method_publications p join owned_method_projected(p.reservation_id) s on row(p.renewal_payment,p.projected_revision,p.observation_sequence)=row(s.renewal_payment,s.projected_revision,s.observation_sequence) where p.reservation_id=?)",r.id());
        return rows.stream().findFirst();
    }
    @Override public void requireMethodFactInTransaction(Command command,Subscription locked,List<SubscriptionOperation> operations,Instant billingAt,Instant utcAt) {
        fact(command,locked,operations,billingAt,utcAt,Phase.METHOD);
    }
    @Override public void requireRecoveryFactInTransaction(Command command,Subscription locked,List<SubscriptionOperation> operations,Instant billingAt,Instant utcAt) {
        fact(command,locked,operations,billingAt,utcAt,Phase.RECOVERED);
    }
    private void fact(Command command,Subscription locked,List<SubscriptionOperation> operations,Instant billingAt,Instant utcAt,Phase phase) {
        transaction();
        if(command==null||locked==null||operations==null||billingAt==null||utcAt==null||command.fence()==null
                ||command.account()==null||command.snapshot()==null||command.source()!=SubscriptionEvent.Source.RECONCILIATION)throw unavailable();
        var rows=jdbc.query("""
            select q.* from stripe_billing_failed_method_reads q join stripe_billing_failed_method_reservations r on r.id=q.reservation_id
            join stripe_billing_failed_renewals d on d.id=r.admission_id where d.subscription_id=? and d.payment_account_id=? and q.phase=?
            and ('owned-failed-renewal-method:'||r.id::text||':'||q.phase||':'||q.invocation::text)=?
            """,this::readRow,command.subscriptionId(),command.account().id().value(),phase.name(),command.factKey());
        if(rows.size()!=1)throw unavailable();
        var q=rows.getFirst();var r=reservation(q.reservationId());requireReadInTransaction(r,q);
        if(!command.fence().equals(q.fence())||!command.account().equals(account(r))||!locked.equals(subscription(r))
                ||!command.subscriptionId().equals(locked.id())||!billingAt.equals(q.billingSample().billingAt())
                ||!q.billingSample().equals(command.expectedBillingSample()))throw unavailable();
        q.requireAccepted(command.snapshot().observedAt(),utcAt);
        var selected=operation(r,q.fence().operationId());
        if(operations.stream().filter(selected::equals).count()!=1||operations.stream().anyMatch(o->
                !o.subscriptionId().equals(locked.id())||!operation(r,o.id()).equals(o)))throw unavailable();
        var s=command.snapshot();
        requireOperationObservation(s,selected,phase);
        if(phase==Phase.METHOD) {
            var raw=new Snapshot(s.subscriptionReference(),s.status(),s.billingPrice(),s.period(),s.trial(),s.cancellationAt(),s.invoices(),null,true,
                s.providerRevision(),s.observedAt(),null,s.recoveryObservations(),s.trialEvidence(),s.externalWithdrawalEvidence());
            JdbcOwnedFailedRenewalStore.requireSnapshot(r.admission(),raw,false,r.failure().paymentIntent(),null);
        } else JdbcOwnedFailedRenewalStore.requireSnapshot(r.admission(),s,true,r.failure().paymentIntent(),selected.providerOperationKey());
    }

    static void requireOperationObservation(Snapshot s,SubscriptionOperation selected,Phase phase) {
        if((phase==Phase.METHOD&&!s.paymentMethodReady())||!Objects.equals(s.operationKey(),selected.providerOperationKey())||s.operationObservation()==null
                ||s.operationObservation().state()!=OperationState.APPLIED||!s.operationObservation().operationKey().equals(selected.providerOperationKey()))throw unavailable();
    }

    private Reservation reservation(UUID id) {
        return jdbc.query("select * from stripe_billing_failed_method_reservations where id=?",(row,n)-> {
            var a=JdbcOwnedFirstRenewalStore.storedFailedAdmission(jdbc,uuid(row,"admission_id"));
            var owner=a.firstRenewalAdmission().receipt().intent().clock().owner();
            var b=new CustomerBinding(uuid(row,"binding_id"),owner.account(),owner.site(),owner.customer(),row.getString("binding_reference"),uuid(row,"binding_creation_operation"),row.getLong("binding_version"));
            return new Reservation(uuid(row,"id"),a,new JdbcOwnedFailedRenewalStore(jdbc).failureRow(a.id()),uuid(row,"setup_operation"),row.getString("request_fingerprint"),
                row.getLong("initial_operation_version"),b,new Sample(instant(row,"billing_at"),Mode.valueOf(row.getString("billing_mode"))),instant(row,"reserved_at"));
        },id).stream().findFirst().orElseThrow(JdbcOwnedFailedRenewalMethodStore::unavailable);
    }
    private ReadClaim storedRead(Reservation r,ReadClaim q) {
        if(q==null||!r.id().equals(q.reservationId())||!jdbc.query("select * from stripe_billing_failed_method_reads where id=? and reservation_id=?",this::readRow,q.id(),r.id()).stream().anyMatch(q::equals))throw unavailable();
        return q;
    }
    private ReadClaim readRow(ResultSet row,int n)throws SQLException {
        return new ReadClaim(uuid(row,"id"),uuid(row,"reservation_id"),Phase.valueOf(row.getString("phase")),uuid(row,"invocation"),instant(row,"started_at"),instant(row,"deadline_at"),
            new Fence(row.getLong("captured_revision"),uuid(row,"selected_operation"),row.getLong("selected_version"),null,null,row.getLong("observation_sequence")),
            new Sample(instant(row,"billing_at"),Mode.valueOf(row.getString("billing_mode"))));
    }
    private Completion completion(Reservation r) {
        return jdbc.query("select * from stripe_billing_failed_method_completions where reservation_id=?",(row,n)->new Completion(r.id(),uuid(row,"claim_id"),uuid(row,"setup_operation"),
            row.getLong("setup_final_version"),uuid(row,"setup_step"),row.getLong("setup_step_version"),uuid(row,"method_step"),row.getLong("method_step_version"),row.getString("setup_reference"),
            row.getString("method_reference"),row.getLong("projected_revision"),row.getLong("observation_sequence"),instant(row,"completed_at")),r.id()).stream().findFirst().orElseThrow(JdbcOwnedFailedRenewalMethodStore::unavailable);
    }
    private Recovery recovery(Reservation r) {
        return jdbc.query("select * from stripe_billing_failed_method_recoveries where reservation_id=?",(row,n)->new Recovery(r.id(),new OwnedFailedRenewal.Recovery(r.admission().id(),
            uuid(row,"operation_id"),r.failure().billId(),r.failure().allocation(),r.failure().paymentIntent(),r.failure().revision(),row.getLong("operation_version"),instant(row,"associated_at")),row.getLong("observation_sequence")),r.id())
            .stream().findFirst().orElseThrow(JdbcOwnedFailedRenewalMethodStore::unavailable);
    }
    private MethodReadContext methodContext(Reservation r,SubscriptionOperation o) {
        var p=setup(r);var setup=JdbcProviderOperationStore.row(jdbc,p.step()).orElseThrow(JdbcOwnedFailedRenewalMethodStore::unavailable);
        var ids=jdbc.queryForList("select id from provider_operations where payment_account_id=? and resource_id=? and kind='SUBSCRIPTION_UPDATE'",UUID.class,r.binding().accountId().value(),r.setupOperationId());
        if(ids.size()!=1)throw unavailable();
        return new MethodReadContext(account(r),subscription(r),r.binding(),o,p,setup,JdbcProviderOperationStore.row(jdbc,ids.getFirst()).orElseThrow(JdbcOwnedFailedRenewalMethodStore::unavailable),r.billingSample());
    }
    Setup setup(Reservation r) {
        return jdbc.query("select * from stripe_subscription_setup_proofs where operation_id=? and payment_account_id=?",(row,n)->new Setup(uuid(row,"operation_id"),uuid(row,"subscription_id"),r.binding().accountId(),uuid(row,"step_id"),row.getString("setup_reference"),row.getString("method_reference")),r.setupOperationId(),r.binding().accountId().value())
            .stream().findFirst().orElseThrow(JdbcOwnedFailedRenewalMethodStore::unavailable);
    }
    SubscriptionOperation operation(Reservation r,UUID id) {
        return new SubscriptionOperationPersistenceAdapter(jdbc).find(r.binding().siteId(),r.admission().firstRenewalAdmission().receipt().intent().subscription(),id).orElseThrow(JdbcOwnedFailedRenewalMethodStore::unavailable);
    }
    Subscription subscription(Reservation r) {
        return new SubscriptionPersistenceAdapter(jdbc).find(r.binding().siteId(),r.admission().firstRenewalAdmission().receipt().intent().subscription()).orElseThrow(JdbcOwnedFailedRenewalMethodStore::unavailable);
    }
    PaymentAccount account(Reservation r) {
        return jdbc.query("select * from payment_accounts where id=?",(row,n)->new PaymentAccount(r.binding().accountId(),row.getString("account_key"),PaymentProviderType.valueOf(row.getString("provider")),
            PaymentAccountMode.valueOf(row.getString("account_mode")),PaymentAccountStatus.valueOf(row.getString("account_status")),row.getString("external_account_id"),row.getString("credentials_ref")),r.binding().accountId().value()).stream().findFirst().orElseThrow(JdbcOwnedFailedRenewalMethodStore::unavailable);
    }
    private void liveWindow(ReadClaim q) { require("select clock_timestamp()>=? and clock_timestamp()<?",time(q.startedAt()),time(q.deadlineAt())); }
    private void require(String sql,Object... args) { if(!Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)))throw unavailable(); }
    private static UUID uuid(ResultSet r,String field)throws SQLException { return r.getObject(field,UUID.class); }
    private static Instant instant(ResultSet r,String field)throws SQLException { return r.getTimestamp(field).toInstant(); }
    private static Instant utc(Instant at) { if(at==null)throw unavailable();return at.truncatedTo(ChronoUnit.MICROS); }
    private static Timestamp time(Instant at) { return Timestamp.from(utc(at)); }
    private static void transaction() { if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Owned method transition requires caller transaction"); }
    private static IllegalStateException unavailable() { return new IllegalStateException("Owned method transition authority unavailable"); }
}
