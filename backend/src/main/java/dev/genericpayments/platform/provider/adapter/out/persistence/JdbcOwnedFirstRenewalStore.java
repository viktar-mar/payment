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
        // Boundary and later READY producers belong to the later consumer tasks. Until their exact
        // persisted chain is implemented FINALIZATION stays fail-closed.
        if(step==Step.FINALIZATION)throw unavailable();
        if(!existing.isEmpty())return requireBoundaryAdvance(admission,advanceRow(existing.getFirst(),admission.id()));
        long target=admission.receipt().periodEnd();
        if(target<=admission.receipt().intent().clock().frozenTime())throw unavailable();
        var id=UUID.randomUUID();var key="billing-renewal-advance-v1:"+admission.id()+":"+step.number();
        var at=utcAt.truncatedTo(ChronoUnit.MICROS);
        var fp=boundaryFingerprint(id,admission,target,key,at);
        jdbc.update("insert into stripe_billing_first_renewal_steps(id,admission_id,ordinal,target,operation_key,fingerprint,prepared_at) values (?,?,?,?,?,?,?)",id,admission.id(),step.number(),target,key,fp,Timestamp.from(at));
        return requireBoundaryAdvance(admission,advanceRow(id,admission.id()));
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
        requireTransaction();if(admission==null)throw unavailable();lockProviderScope(admission);return requirePaidInTransaction(admission);
    }
    public LaterReady recordReady(Ack ack,TestClockReady observation,Instant utcAt){throw unavailable();}
    public Boundary recordBoundary(Admission admission,LaterReady ready,Boundary boundary){throw unavailable();}
    public Boundary boundary(Admission admission){throw unavailable();}
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
    private Advance requireBoundaryAdvance(Admission admission,Advance advance){
        if(advance.step()!=Step.BOUNDARY||advance.target()!=admission.receipt().periodEnd()
                ||!advance.fingerprint().equals(boundaryFingerprint(advance.id(),admission,advance.target(),advance.operationKey(),advance.preparedAt())))throw unavailable();
        return advance;
    }
    private static String boundaryFingerprint(UUID id,Admission admission,long target,String key,Instant at){
        var c=admission.receipt().intent().clock();
        return OwnedFirstRenewal.fingerprint("gp:owned-first-renewal-advance:v1",id,admission.id(),admission.fingerprint(),Step.BOUNDARY.number(),target,
            c.creationOperation(),c.reference(),c.frozenTime(),null,null,key,at);
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
