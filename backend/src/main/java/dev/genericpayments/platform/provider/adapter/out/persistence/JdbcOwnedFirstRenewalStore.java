package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.model.OwnedClockSubscriptionReceipt;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Internal evidence store. Caller lifecycle locks own admission; provider phases use short transactions. */
@Repository
public class JdbcOwnedFirstRenewalStore implements OwnedFirstRenewalStore {
    private final JdbcTemplate jdbc;
    public JdbcOwnedFirstRenewalStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static IllegalStateException unavailable(){return new IllegalStateException("Owned first renewal authority unavailable");}
    @Transactional(propagation=Propagation.MANDATORY)
    public Admission admitPaid(OwnedClockSubscriptionReceipt receipt,String sourceSha,Instant utcAt){
        requireTransaction();
        if(receipt==null||sourceSha==null||!sourceSha.matches("[a-f0-9]{40}")||utcAt==null)throw unavailable();
        var actual=requireOriginal(receipt);
        var saved=admissionRow(actual);
        if(saved.isPresent()) {
            var admission=saved.get();
            if(!admission.sourceSha().equals(sourceSha))throw unavailable();
            return requirePaidInTransaction(admission);
        }
        var proofs=jdbc.query("select * from owned_first_renewal_paid(?)",(r,n)->{
            var invoice=r.getString("invoice_reference");var allocation=r.getString("allocation_reference");var pi=r.getString("payment_intent_reference");
            if(!OwnedInitialConfirmationProof.hash(actual,invoice,allocation,pi).equals(r.getString("root_proof")))throw unavailable();
            var at=utcAt.truncatedTo(ChronoUnit.MICROS);
            var id=UUID.randomUUID();var root=r.getObject("root_id",UUID.class);var attempt=r.getObject("attempt_id",UUID.class);
            var bill=r.getObject("bill_id",UUID.class);var payment=r.getObject("payment_id",UUID.class);
            long av=r.getLong("attempt_version"),bv=r.getLong("bill_version"),pv=r.getLong("payment_version"),revision=r.getLong("subscription_revision"),policy=r.getLong("policy_version");
            var fp=admissionFingerprint(id,actual,sourceSha,root,attempt,av,bill,bv,payment,pv,invoice,allocation,pi,revision,policy,at);
            return new Admission(id,actual,sourceSha,root,attempt,av,bill,bv,payment,pv,invoice,allocation,pi,revision,policy,fp,at);
        },actual.intent().id());
        if(proofs.size()!=1)throw unavailable();
        var a=proofs.getFirst();var o=actual.intent().clock().owner();
        jdbc.update("""
            insert into stripe_billing_first_renewals(id,owned_intent,payment_account_id,site_id,customer_id,subscription_id,source_sha,
             original_root,original_attempt,attempt_version,initial_bill,bill_version,initial_payment,payment_version,invoice_reference,allocation_reference,
             payment_intent_reference,subscription_revision,policy_version,fingerprint,admitted_at)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,a.id(),actual.intent().id(),o.account().value(),o.site().value(),o.customer().value(),actual.intent().subscription(),sourceSha,
            a.root(),a.attempt(),a.attemptVersion(),a.bill(),a.billVersion(),a.payment(),a.paymentVersion(),a.invoice(),a.allocation(),a.paymentIntent(),a.subscriptionRevision(),
            a.policyVersion(),a.fingerprint(),Timestamp.from(a.admittedAt()));
        return requirePaidInTransaction(a);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Admission requirePaidInTransaction(Admission admission){
        requireTransaction();if(admission==null)throw unavailable();
        var actual=requireOriginal(admission.receipt());
        var saved=admissionRow(actual).filter(admission::equals).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable);
        if(jdbc.queryForList("select id from stripe_billing_first_renewals where id=? and stopped_at is null",UUID.class,saved.id()).size()!=1)throw unavailable();
        var valid=jdbc.queryForObject("""
            select exists(select 1 from owned_first_renewal_paid(?) p
             where p.root_id=? and p.attempt_id=? and p.attempt_version=? and p.bill_id=? and p.bill_version=? and p.payment_id=? and p.payment_version=?
             and p.invoice_reference=? and p.allocation_reference=? and p.payment_intent_reference=? and p.subscription_revision=? and p.policy_version=?
             and p.root_proof=?)
            """,Boolean.class,actual.intent().id(),saved.root(),saved.attempt(),saved.attemptVersion(),saved.bill(),saved.billVersion(),saved.payment(),saved.paymentVersion(),saved.invoice(),
            saved.allocation(),saved.paymentIntent(),saved.subscriptionRevision(),saved.policyVersion(),OwnedInitialConfirmationProof.hash(actual,saved.invoice(),saved.allocation(),saved.paymentIntent()));
        if(!Boolean.TRUE.equals(valid)||!saved.fingerprint().equals(admissionFingerprint(saved.id(),actual,saved.sourceSha(),saved.root(),saved.attempt(),saved.attemptVersion(),saved.bill(),saved.billVersion(),
                saved.payment(),saved.paymentVersion(),saved.invoice(),saved.allocation(),saved.paymentIntent(),saved.subscriptionRevision(),saved.policyVersion(),saved.admittedAt())))throw unavailable();
        return saved;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Advance prepare(Admission admission,Step step,Boundary boundary,Instant utcAt){
        requireTransaction();
        if(admission==null||step==null||utcAt==null)throw unavailable();
        // Existing provider-step first, then Site/Customer/Account; lifecycle reads below are nonlocking.
        var existing=jdbc.queryForList("select id from stripe_billing_first_renewal_steps where admission_id=? and ordinal=? for update",UUID.class,admission.id(),step.number());
        lockProviderScope(admission);
        requirePaidInTransaction(admission);
        if(step==Step.BOUNDARY&&boundary!=null)throw unavailable();
        Boundary predecessor=step==Step.FINALIZATION?boundaryRow(admission).filter(b->b.equals(boundary)).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable):null;
        if(!existing.isEmpty())return requireAdvance(admission,advanceRow(existing.getFirst(),admission.id()));
        long target=step==Step.BOUNDARY?admission.receipt().periodEnd():Math.addExact(admission.receipt().periodEnd(),3600);
        OwnedFirstRenewal.requireAdvanceWindow(admission,step,target);
        if(target<=admission.receipt().intent().clock().frozenTime())throw unavailable();
        if(predecessor!=null&&(!predecessor.period().start().equals(Instant.ofEpochSecond(admission.receipt().periodEnd()))
                ||!Instant.ofEpochSecond(target).isBefore(predecessor.period().end())))throw unavailable();
        var id=UUID.randomUUID();var key="billing-renewal-advance-v1:"+admission.id()+":"+step.number();
        var at=utcAt.truncatedTo(ChronoUnit.MICROS);
        var fp=advanceFingerprint(id,admission,step,target,predecessor==null?null:predecessor.ready().id(),predecessor==null?null:predecessor.id(),key,at);
        jdbc.update("insert into stripe_billing_first_renewal_steps(id,admission_id,ordinal,target,predecessor_ready,boundary_id,operation_key,fingerprint,prepared_at) values (?,?,?,?,?,?,?,?,?)",id,admission.id(),step.number(),target,
            predecessor==null?null:predecessor.ready().id(),predecessor==null?null:predecessor.id(),key,fp,Timestamp.from(at));
        return requireAdvance(admission,advanceRow(id,admission.id()));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void stop(Admission admission,String reason,Instant utcAt){
        requireTransaction();
        if(admission==null||utcAt==null||reason==null||!java.util.Set.of("AUTHORITY_DRIFT","ADVANCE_UNKNOWN","READY_UNAVAILABLE","GRAPH_UNSUPPORTED","PAYMENT_NOT_OBSERVED").contains(reason))throw unavailable();
        requireStoredOriginal(admission.receipt());
        var o=admission.receipt().intent().clock().owner();
        if(jdbc.queryForList("select id from payment_accounts where id=? for update",UUID.class,o.account().value()).size()!=1)throw unavailable();
        if(!admissionRow(admission.receipt()).filter(admission::equals).isPresent())throw unavailable();
        jdbc.update("update stripe_billing_first_renewals set stopped_at=?,stop_reason=? where id=? and stopped_at is null",Timestamp.from(utcAt),reason,admission.id());
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Admission requireCurrent(Admission admission){
        requireTransaction();if(admission==null)throw unavailable();
        // Authenticate immutable stored scope before classifying a predicate failure as drift.
        // Forged records and infrastructure/lock errors must never gain authority to stop an owner.
        requireStoredOriginal(admission.receipt());
        if(admissionRow(admission.receipt()).filter(admission::equals).isEmpty())throw unavailable();
        try{lockProviderScope(admission);return requirePaidInTransaction(admission);}
        catch(IllegalStateException|IllegalArgumentException|dev.genericpayments.platform.shared.application.PaymentApplicationException changed){throw new AuthorityDrift();}
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Optional<LaterReady> ready(Admission admission,Step step){
        requireTransaction();if(admission==null||step==null)throw unavailable();lockProviderScope(admission);requirePaidInTransaction(admission);
        requireHighest(admission,step);
        return jdbc.queryForList("select id from stripe_billing_first_renewal_steps where admission_id=? and ordinal=?",UUID.class,admission.id(),step.number())
            .stream().findFirst().flatMap(id->{requireAdvance(admission,advanceRow(id,admission.id()));return readyRow(admission,id);});
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public LaterReady recordReady(Ack ack,TestClockReady observation,Instant utcAt){
        requireTransaction();if(ack==null||observation==null||utcAt==null)throw unavailable();
        if(jdbc.queryForList("select id from provider_operations where id=? for update",UUID.class,ack.operation().id()).size()!=1)throw unavailable();
        var admission=admissionForAdvance(jdbc,ack.operation().accountId(),ack.advance().id());
        lockProviderScope(admission);requirePaidInTransaction(admission);requireAdvance(admission,ack.advance());
        if(!ackRow(admission,ack.advance()).filter(ack::equals).isPresent()||!utcAt.isBefore(ack.deadline())
                ||!Boolean.TRUE.equals(jdbc.queryForObject("select clock_timestamp() < ?",Boolean.class,Timestamp.from(ack.deadline()))))throw unavailable();
        var ready=new LaterReady(UUID.randomUUID(),admission.id(),ack,observation,utcAt.truncatedTo(ChronoUnit.MICROS));
        jdbc.update("""
            insert into stripe_billing_first_renewal_ready(id,admission_id,step_id,reference,object_type,livemode,name,frozen_time,status,
             provider_created,provider_deletes_after,advancing_target,observed_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,ready.id(),admission.id(),ack.advance().id(),observation.reference(),observation.object(),observation.livemode(),observation.name(),observation.frozenTime(),observation.status(),
            observation.created(),observation.deletesAfter(),observation.advancingTarget(),Timestamp.from(ready.observedAt()));
        return readyRow(admission,ack.advance().id()).filter(ready::equals).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public LaterReady requireReadyInTransaction(Admission admission,LaterReady ready){
        requireTransaction();requirePaidInTransaction(admission);
        if(ready==null||!ready.admissionId().equals(admission.id()))throw unavailable();
        requireAdvance(admission,ready.ack().advance());
        return readyRow(admission,ready.ack().advance().id()).filter(ready::equals).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public ReadClaim beginReadInTransaction(Admission admission,LaterReady ready,
            dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence fence,UUID invocation,Instant startedAt){
        requireTransaction();requireReadyInTransaction(admission,ready);requireReadFence(admission,fence);
        var at=startedAt.truncatedTo(ChronoUnit.MICROS);var claim=new ReadClaim(ready,invocation,at,at.plusSeconds(30),fence);
        int inserted=jdbc.update("""
            insert into stripe_billing_first_renewal_read_claims(ready_id,step_id,admission_id,invocation,started_at,deadline_at,
              captured_revision,selected_operation,selected_version,observation_sequence) values (?,?,?,?,?,?,?,?,?,?)
            on conflict do nothing
            """,ready.id(),ready.ack().advance().id(),admission.id(),invocation,Timestamp.from(claim.startedAt()),Timestamp.from(claim.deadlineAt()),
            fence.revision(),fence.operationId(),fence.operationVersion(),fence.observationSequence());
        // The returned value is authority only on an actual INSERT in this stack. Never reload on conflict.
        if(inserted!=1)throw unavailable();return claim;
    }
    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public Optional<ReadClaim> readClaim(Admission admission,LaterReady ready){
        requireTransaction();requireReadyInTransaction(admission,ready);
        var claim=readClaimRow(admission,ready);claim.ifPresent(c->requireReadFence(admission,c.fence()));return claim;
    }
    private Optional<ReadClaim> readClaimRow(Admission admission,LaterReady ready){
        return jdbc.query("select * from stripe_billing_first_renewal_read_claims where ready_id=? and step_id=? and admission_id=?",(r,n)->
            new ReadClaim(ready,r.getObject("invocation",UUID.class),r.getTimestamp("started_at").toInstant(),r.getTimestamp("deadline_at").toInstant(),
                new dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence(
                    r.getLong("captured_revision"),r.getObject("selected_operation",UUID.class),r.getObject("selected_version",Long.class),null,null,r.getLong("observation_sequence"))),
            ready.id(),ready.ack().advance().id(),admission.id()).stream().findFirst();
    }
    private void requireReadFence(Admission admission,dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence fence){
        // Exact paid admission excludes every unresolved financial operation. A retained trial reservation is also not this paid read.
        if(fence==null||fence.revision()!=admission.subscriptionRevision()||fence.operationId()!=null||fence.operationVersion()!=null
                ||fence.lease()!=null||fence.authority()!=null||fence.observationSequence()==null||!Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from subscriptions s where s.id=? and s.revision=? and s.observation_sequence=?
             and not exists(select 1 from subscription_trial_eligibility t where t.site_id=s.site_id and t.customer_id=s.customer_id
              and t.product_id=s.product_id and t.consumed_at is null and t.reserved_by_operation is not null))
            """,Boolean.class,admission.receipt().intent().subscription(),fence.revision(),fence.observationSequence())))throw unavailable();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Boundary recordBoundary(Admission admission,LaterReady ready,Boundary boundary){
        requireTransaction();requireReadyInTransaction(admission,ready);
        if(ready.ack().advance().step()!=Step.BOUNDARY||boundary==null||!boundary.ready().equals(ready))throw unavailable();
        var claim=readClaim(admission,ready).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable);requireBoundary(admission,boundary,claim);
        var saved=boundaryRow(admission);if(saved.isPresent())return saved.filter(boundary::equals).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable);
        jdbc.update("""
            insert into stripe_billing_first_renewal_boundary(id,admission_id,ready_id,item_reference,invoice_reference,period_start,period_end,fingerprint,observed_at)
             values(?,?,?,?,?,?,?,?,?)
            """,boundary.id(),admission.id(),ready.id(),boundary.item(),boundary.renewalInvoice(),Timestamp.from(boundary.period().start()),
            Timestamp.from(boundary.period().end()),boundary.fingerprint(),Timestamp.from(boundary.observedAt()));
        return boundaryRow(admission).filter(boundary::equals).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable);
    }
    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public Boundary boundaryInTransaction(Admission admission){
        requireTransaction();requirePaidInTransaction(admission);
        var boundary=boundaryRow(admission).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable);
        var exact=readyRow(admission,boundary.ready().ack().advance().id()).filter(boundary.ready()::equals).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable);
        requireBoundary(admission,boundary,readClaimRow(admission,exact).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable));return boundary;
    }
    private static void requireBoundary(Admission admission,Boundary boundary,ReadClaim claim){
        if(!boundary.admissionId().equals(admission.id())||!boundary.ready().equals(claim.ready())||!boundary.item().equals(admission.receipt().itemReference())
            ||boundary.renewalInvoice().equals(admission.invoice())||!boundary.period().start().equals(Instant.ofEpochSecond(admission.receipt().periodEnd()))
            ||!Instant.ofEpochSecond(Math.addExact(admission.receipt().periodEnd(),3600)).isBefore(boundary.period().end())
            ||boundary.observedAt().isBefore(claim.startedAt())||!boundary.observedAt().isBefore(claim.deadlineAt())
            ||!boundary.fingerprint().equals(OwnedFirstRenewal.fingerprint("gp:owned-first-renewal-boundary:v1",boundary.id(),admission.id(),boundary.ready().id(),
                boundary.item(),boundary.renewalInvoice(),boundary.period().start(),boundary.period().end(),boundary.observedAt())))throw unavailable();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Boundary boundary(Admission admission){
        requireTransaction();lockProviderScope(admission);requirePaidInTransaction(admission);
        return boundaryInTransaction(admission);
    }
    public Publication recordPublicationInTransaction(Admission admission,LaterReady ready,Paid paid){throw unavailable();}
    public Optional<Publication> publicationInTransaction(Admission admission){throw unavailable();}

    private OwnedClockSubscriptionReceipt requireOriginal(OwnedClockSubscriptionReceipt expected){
        requireStoredOriginal(expected);
        var i=expected.intent();var o=i.clock().owner();
        JdbcStripeBillingClockStore.requireActiveRow(jdbc,o);
        var actual=JdbcStripeBillingClockSubscriptionStore.intentRow(jdbc,o.account(),i.subscription()).filter(i::equals).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable);
        JdbcStripeBillingClockSubscriptionStore.requireLineage(jdbc,actual);
        return JdbcStripeBillingClockSubscriptionStore.receiptRow(jdbc,actual).filter(expected::equals).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable);
    }
    /** Immutable identity reload only: stop must remain possible after authority revocation/drift. */
    private void requireStoredOriginal(OwnedClockSubscriptionReceipt expected){
        var i=expected.intent();var o=i.clock().owner();
        var actual=JdbcStripeBillingClockSubscriptionStore.intentRow(jdbc,o.account(),i.subscription()).filter(i::equals).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable);
        var exact=jdbc.query("select * from stripe_billing_owned_subscription_receipts where intent_id=?",(r,n)->new OwnedClockSubscriptionReceipt(actual,
            r.getObject("provider_operation",UUID.class),r.getString("reference"),r.getLong("provider_created"),r.getString("item_reference"),r.getLong("period_start"),r.getLong("period_end"),r.getTimestamp("acknowledged_at").toInstant()),actual.id());
        if(exact.size()!=1||!exact.getFirst().equals(expected))throw unavailable();
    }
    private Optional<Admission> admissionRow(OwnedClockSubscriptionReceipt receipt){
        return jdbc.query("select * from stripe_billing_first_renewals where owned_intent=?",(r,n)->new Admission(r.getObject("id",UUID.class),receipt,r.getString("source_sha"),
            r.getObject("original_root",UUID.class),r.getObject("original_attempt",UUID.class),r.getLong("attempt_version"),r.getObject("initial_bill",UUID.class),r.getLong("bill_version"),r.getObject("initial_payment",UUID.class),r.getLong("payment_version"),
            r.getString("invoice_reference"),r.getString("allocation_reference"),r.getString("payment_intent_reference"),r.getLong("subscription_revision"),r.getLong("policy_version"),r.getString("fingerprint"),r.getTimestamp("admitted_at").toInstant()),receipt.intent().id()).stream().findFirst();
    }
    private Advance advanceRow(UUID id,UUID admission){
        return jdbc.query("select * from stripe_billing_first_renewal_steps where id=? and admission_id=?",(r,n)->new Advance(r.getObject("id",UUID.class),admission,
            r.getInt("ordinal")==1?Step.BOUNDARY:Step.FINALIZATION,r.getLong("target"),r.getObject("predecessor_ready",UUID.class),r.getObject("boundary_id",UUID.class),r.getString("operation_key"),r.getString("fingerprint"),r.getTimestamp("prepared_at").toInstant()),id,admission).stream().findFirst().orElseThrow(JdbcOwnedFirstRenewalStore::unavailable);
    }
    private Advance requireAdvance(Admission admission,Advance advance){
        if(!advance.equals(advanceRow(advance.id(),admission.id())))throw unavailable();
        requireHighest(admission,advance.step());
        long target=admission.receipt().periodEnd();
        if(advance.step()==Step.FINALIZATION){
            target=Math.addExact(target,3600);
            var boundary=boundaryRow(admission).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable);
            if(!boundary.id().equals(advance.boundary())||!boundary.ready().id().equals(advance.predecessorReady())
                    ||!boundary.period().start().equals(Instant.ofEpochSecond(admission.receipt().periodEnd()))
                    ||!Instant.ofEpochSecond(target).isBefore(boundary.period().end()))throw unavailable();
        }
        if(advance.target()!=target||!advance.fingerprint().equals(advanceFingerprint(advance.id(),admission,advance.step(),advance.target(),
                advance.predecessorReady(),advance.boundary(),advance.operationKey(),advance.preparedAt())))throw unavailable();
        OwnedFirstRenewal.requireAdvanceWindow(admission,advance.step(),advance.target());
        return advance;
    }
    private void requireHighest(Admission admission,Step step){
        if(!jdbc.queryForList("select id from stripe_billing_first_renewal_steps where admission_id=? and ordinal>?",UUID.class,admission.id(),step.number()).isEmpty())throw unavailable();
    }
    private static String advanceFingerprint(UUID id,Admission admission,Step step,long target,UUID predecessor,UUID boundary,String key,Instant at){
        var c=admission.receipt().intent().clock();
        return OwnedFirstRenewal.fingerprint("gp:owned-first-renewal-advance:v1",id,admission.id(),admission.fingerprint(),step.number(),target,
            c.creationOperation(),c.reference(),c.frozenTime(),predecessor,boundary,key,at);
    }
    /** No proxy/REQUIRES_NEW call: provider store owns its existing short transaction and row lock. */
    static Admission requireAdvanceScope(JdbcTemplate jdbc,PaymentAccountId account,Advance expected){
        var store=new JdbcOwnedFirstRenewalStore(jdbc);var a=admissionForAdvance(jdbc,account,expected.id());
        store.lockProviderScope(a);store.requirePaidInTransaction(a);store.requireAdvance(a,expected);return a;
    }
    static Advance requireOperationAdvance(JdbcTemplate jdbc,ProviderOperation operation){
        var store=new JdbcOwnedFirstRenewalStore(jdbc);var a=admissionForAdvance(jdbc,operation.accountId(),operation.resourceId());
        var advance=store.advanceRow(operation.resourceId(),a.id());requireAdvanceScope(jdbc,operation.accountId(),advance);
        var owner=a.receipt().intent().clock().owner();
        if(!operation.operationKey().equals(advance.operationKey())||!operation.fingerprint().equals(advance.fingerprint())
                ||!operation.externalAccountId().equals(owner.externalAccount())||!operation.credentialsRef().equals(owner.credentialsRef()))throw unavailable();
        return advance;
    }
    static void requireAdvanceResource(JdbcTemplate jdbc,PaymentAccountId account,UUID resource,String key,String fingerprint){
        var store=new JdbcOwnedFirstRenewalStore(jdbc);var a=admissionForAdvance(jdbc,account,resource);
        var advance=store.advanceRow(resource,a.id());
        if(!advance.operationKey().equals(key)||!advance.fingerprint().equals(fingerprint))throw unavailable();
        requireAdvanceScope(jdbc,account,advance);
    }
    static Admission admissionForAdvance(JdbcTemplate jdbc,PaymentAccountId account,UUID step){
        var ids=jdbc.queryForList("select a.subscription_id from stripe_billing_first_renewals a join stripe_billing_first_renewal_steps s on s.admission_id=a.id where s.id=? and a.payment_account_id=?",UUID.class,step,account.value());
        if(ids.size()!=1)throw unavailable();
        var intent=JdbcStripeBillingClockSubscriptionStore.intentRow(jdbc,account,ids.getFirst()).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable);
        var receipt=JdbcStripeBillingClockSubscriptionStore.receiptRow(jdbc,intent).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable);
        return new JdbcOwnedFirstRenewalStore(jdbc).admissionRow(receipt).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable);
    }
    private Optional<Ack> ackRow(Admission admission,Advance advance){
        return jdbc.query("select * from stripe_billing_first_renewal_acks where step_id=? and admission_id=?",(r,n)->new Ack(advance,
            JdbcProviderOperationStore.row(jdbc,r.getObject("provider_operation",UUID.class)).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable),
            r.getString("claimant"),r.getTimestamp("deadline").toInstant(),new TestClockAck(r.getString("reference"),r.getString("object_type"),r.getBoolean("livemode"),r.getString("name"),
            r.getLong("frozen_time"),r.getString("status"),r.getLong("provider_created"),r.getLong("provider_deletes_after"),r.getObject("advancing_target",Long.class)),
            r.getTimestamp("acknowledged_at").toInstant()),advance.id(),admission.id()).stream().findFirst();
    }
    private Optional<LaterReady> readyRow(Admission admission,UUID step){
        var advance=advanceRow(step,admission.id());
        return jdbc.query("select * from stripe_billing_first_renewal_ready where step_id=? and admission_id=?",(r,n)->new LaterReady(r.getObject("id",UUID.class),admission.id(),
            ackRow(admission,advance).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable),new TestClockReady(r.getString("reference"),r.getString("object_type"),r.getBoolean("livemode"),r.getString("name"),
            r.getLong("frozen_time"),r.getString("status"),r.getLong("provider_created"),r.getLong("provider_deletes_after"),r.getObject("advancing_target",Long.class)),r.getTimestamp("observed_at").toInstant()),
            step,admission.id()).stream().findFirst();
    }
    private Optional<Boundary> boundaryRow(Admission admission){
        return jdbc.query("select b.*,r.step_id from stripe_billing_first_renewal_boundary b join stripe_billing_first_renewal_ready r on r.id=b.ready_id and r.admission_id=b.admission_id where b.admission_id=?",(r,n)->
            new Boundary(r.getObject("id",UUID.class),admission.id(),readyRow(admission,r.getObject("step_id",UUID.class)).orElseThrow(JdbcOwnedFirstRenewalStore::unavailable),
            r.getString("item_reference"),r.getString("invoice_reference"),new Subscription.Period(r.getTimestamp("period_start").toInstant(),r.getTimestamp("period_end").toInstant()),
            r.getString("fingerprint"),r.getTimestamp("observed_at").toInstant()),admission.id()).stream().findFirst();
    }
    private void lockProviderScope(Admission admission){
        var o=admission.receipt().intent().clock().owner();
        if(jdbc.queryForList("select id from sites where id=? and payment_account_id=? and status='ACTIVE' for share",UUID.class,o.site().value(),o.account().value()).size()!=1)throw unavailable();
        if(jdbc.queryForList("select id from customers where id=? and site_id=? and status='ACTIVE' for share",UUID.class,o.customer().value(),o.site().value()).size()!=1)throw unavailable();
        if(jdbc.queryForList("select id from payment_accounts where id=? and provider='STRIPE' and account_mode='SANDBOX' and account_status='ACTIVE' and external_account_id=? and credentials_ref=? for share",UUID.class,o.account().value(),o.externalAccount(),o.credentialsRef()).size()!=1)throw unavailable();
    }
    private static String admissionFingerprint(UUID id,OwnedClockSubscriptionReceipt r,String source,UUID root,UUID attempt,long av,UUID bill,long bv,UUID payment,long pv,String invoice,String allocation,String pi,long revision,long policy,Instant at){
        return OwnedFirstRenewal.fingerprint("gp:owned-first-renewal-admission:v1",id,OwnedInitialConfirmationProof.descriptor(r,invoice,allocation,pi),source,root,attempt,av,bill,bv,payment,pv,revision,policy,at);
    }
    private static void requireTransaction(){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Owned renewal requires caller transaction");
    }
}
