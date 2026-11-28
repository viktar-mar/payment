package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeBillingClockIdentity;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingClockStore;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation=Propagation.REQUIRES_NEW)
public class JdbcStripeBillingClockStore implements StripeBillingClockStore {
    private final JdbcTemplate jdbc;
    private final DigestPort digest;
    public JdbcStripeBillingClockStore(JdbcTemplate jdbc, DigestPort digest) { this.jdbc=jdbc; this.digest=digest; }

    public ClockOwner admitFresh(PaymentAccount account, SiteId site, CustomerId customer, UUID run,
            String apiVersion, long frozen, Instant admittedAt) {
        if(account==null || site==null || customer==null || run==null || admittedAt==null
            || apiVersion==null || apiVersion.isBlank() || frozen<=0
            || account.provider()!=PaymentProviderType.STRIPE || account.mode()!=PaymentAccountMode.SANDBOX) throw conflict();
        lockScope(jdbc,account.id(),site,customer,account.externalAccountId(),account.credentialsRef(),true,false);
        var prior=ownerRow(jdbc,account.id(),site,customer);
        if(prior.isPresent()) {
            var o=prior.get();
            if(!o.run().equals(run) || !o.apiVersion().equals(apiVersion) || o.initialFrozenTime()!=frozen
                || !o.externalAccount().equals(account.externalAccountId()) || !o.credentialsRef().equals(account.credentialsRef())) throw conflict();
            requireActiveRow(jdbc,o); return o;
        }
        if(!jdbc.queryForList("select id from stripe_billing_customers where site_id=? and customer_id=?",UUID.class,site.value(),customer.value()).isEmpty()
            || !jdbc.queryForList("select id from provider_operations where (kind='CUSTOMER_CREATE' and resource_id=?) or (payment_account_id=? and operation_key=?)",UUID.class,
                customer.value(),account.id().value(),"billing_customer_"+account.id().value()+"_"+site.value()+"_"+customer.value()).isEmpty()) throw conflict();
        // A local lifecycle reservation is already an owner even before provider Customer creation.
        if(!jdbc.queryForList("select id from subscriptions where site_id=? and customer_id=?",UUID.class,site.value(),customer.value()).isEmpty()
            || !jdbc.queryForList("select id from subscription_operations where site_id=? and customer_id=?",UUID.class,site.value(),customer.value()).isEmpty()) throw conflict();
        var id=UUID.randomUUID();

        var o=new ClockOwner(id,run,account.id(),site,customer,account.externalAccountId(),account.credentialsRef(),apiVersion,
            frozen,StripeBillingClockIdentity.name(id),StripeBillingClockIdentity.createKey(account.id(),id),
            StripeBillingClockIdentity.createFingerprint(digest,account,id,run,site.value(),customer.value(),apiVersion,frozen),
            admittedAt.truncatedTo(ChronoUnit.MICROS));
        jdbc.update("""
            insert into stripe_billing_clock_intents(id,run_id,payment_account_id,site_id,customer_id,external_account_id,
                credentials_ref,api_version,initial_frozen_time,name,create_key,create_fingerprint,admitted_at)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict do nothing
            """,o.intent(),o.run(),o.account().value(),o.site().value(),o.customer().value(),o.externalAccount(),o.credentialsRef(),
            o.apiVersion(),o.initialFrozenTime(),o.name(),o.createKey(),o.createFingerprint(),Timestamp.from(o.admittedAt()));
        return ownerRow(jdbc,account.id(),site,customer).filter(o::equals).orElseThrow(JdbcStripeBillingClockStore::conflict);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
    public Optional<ClockOwner> owner(PaymentAccountId account, SiteId site, CustomerId customer) {
        return ownerRow(jdbc,account,site,customer);
    }
    public void requireActive(ClockOwner owner) {
        lockOwner(jdbc,owner); requireActiveRow(jdbc,owner);
    }
    public ReadyClock recordReady(ClockOwner owner, ProviderOperation ack, ReadyObservation observation) {
        lockOwner(jdbc,owner); requireActiveRow(jdbc,owner);
        if(ack==null || observation==null || observation.state()!=ReadyState.READY || observation.observedAt()==null
            || observation.observedAt().isBefore(owner.admittedAt()) || ack.kind()!=ProviderOperation.Kind.TEST_CLOCK_CREATE
            || ack.status()!=ProviderOperation.Status.ACKNOWLEDGED || !owner.intent().equals(ack.resourceId())
            || !owner.account().equals(ack.accountId()) || !owner.createKey().equals(ack.operationKey())
            || !owner.createFingerprint().equals(ack.fingerprint()) || !owner.externalAccount().equals(ack.externalAccountId())
            || !owner.credentialsRef().equals(ack.credentialsRef()) || !Objects.equals(ack.providerReference(),observation.reference())) throw conflict();
        if(jdbc.queryForList("""
            select id from provider_operations where id=? and payment_account_id=? and resource_id=? and kind='TEST_CLOCK_CREATE'
             and status='ACKNOWLEDGED' and provider_reference=? and operation_key=? and fingerprint=? and version=?
            """,UUID.class,ack.id(),owner.account().value(),owner.intent(),ack.providerReference(),owner.createKey(),owner.createFingerprint(),ack.version()).size()!=1) throw conflict();
        var receipt=new ReadyClock(owner,ack.id(),observation.reference(),observation.frozenTime(),observation.providerCreated(),
            observation.providerDeletesAfter(),observation.observedAt().truncatedTo(ChronoUnit.MICROS),
            StripeBillingClockIdentity.customerFingerprint(digest,owner,ack.id(),observation.reference()));
        jdbc.update("""
            insert into stripe_billing_clock_ready(intent_id,payment_account_id,site_id,customer_id,creation_operation_id,
                reference,frozen_time,provider_created,provider_deletes_after,observed_at,customer_fingerprint)
            values (?,?,?,?,?,?,?,?,?,?,?) on conflict(intent_id) do nothing
            """,owner.intent(),owner.account().value(),owner.site().value(),owner.customer().value(),ack.id(),receipt.reference(),
            receipt.frozenTime(),receipt.providerCreated(),receipt.providerDeletesAfter(),Timestamp.from(receipt.observedAt()),receipt.customerFingerprint());
        return readyRow(jdbc,owner.intent()).filter(receipt::equals).orElseThrow(JdbcStripeBillingClockStore::conflict);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
    public Optional<ReadyClock> ready(UUID intent) { return readyRow(jdbc,intent); }
    public void stop(ClockOwner owner, StopReason reason, Instant stoppedAt) {
        if(owner==null || reason==null || stoppedAt==null || stoppedAt.isBefore(owner.admittedAt())) throw conflict();
        // Same Account fence as all dispatch/ACK/bind paths, exclusive to serialize stop with them.
        jdbc.queryForList("select id from payment_accounts where id=? for update",UUID.class,owner.account().value());
        if(!ownerRow(jdbc,owner.account(),owner.site(),owner.customer()).filter(owner::equals).isPresent()) throw conflict();
        jdbc.update("update stripe_billing_clock_intents set stopped_at=?,stop_reason=? where id=? and stopped_at is null",
            Timestamp.from(stoppedAt),reason.name(),owner.intent());
    }
    static void lockOwner(JdbcTemplate jdbc, ClockOwner owner) {
        if(owner==null) throw conflict();
        lockScope(jdbc,owner.account(),owner.site(),owner.customer(),owner.externalAccount(),owner.credentialsRef(),false,false);
    }
    static void lockScope(JdbcTemplate jdbc,PaymentAccountId account,SiteId site,CustomerId customer,
            String external,String credentials,boolean exclusiveCustomer,boolean accountAlreadyLocked) {
        if(!accountAlreadyLocked && jdbc.queryForList("""
            select id from payment_accounts where id=? and provider='STRIPE' and account_mode='SANDBOX'
             and account_status='ACTIVE' and external_account_id=? and credentials_ref=? for share
            """,UUID.class,account.value(),external,credentials).size()!=1) throw conflict();
        if(jdbc.queryForList("select id from sites where id=? and payment_account_id=? and status='ACTIVE' for share",
            UUID.class,site.value(),account.value()).size()!=1) throw conflict();
        if(jdbc.queryForList("select id from customers where id=? and site_id=? and status='ACTIVE' for "+(exclusiveCustomer?"update":"share"),
            UUID.class,customer.value(),site.value()).size()!=1) throw conflict();
    }
    /** A known Customer ACK may repair only its immutable local binding after BINDING_FAILED. */
    static void requireBindingRow(JdbcTemplate jdbc,ClockOwner owner) {
        if(!ownerRow(jdbc,owner.account(),owner.site(),owner.customer()).filter(owner::equals).isPresent()
            || jdbc.queryForList("select id from stripe_billing_clock_intents where id=? and (stopped_at is null or stop_reason='BINDING_FAILED')",UUID.class,owner.intent()).size()!=1) throw conflict();
    }
    static void requireActiveRow(JdbcTemplate jdbc,ClockOwner owner) {
        if(!ownerRow(jdbc,owner.account(),owner.site(),owner.customer()).filter(owner::equals).isPresent()
            || jdbc.queryForList("select id from stripe_billing_clock_intents where id=? and stopped_at is null",UUID.class,owner.intent()).size()!=1) throw conflict();
    }
    static Optional<ClockOwner> ownerRow(JdbcTemplate jdbc,PaymentAccountId a,SiteId s,CustomerId c) {
        return jdbc.query("select * from stripe_billing_clock_intents where payment_account_id=? and site_id=? and customer_id=?",
            JdbcStripeBillingClockStore::owner,a.value(),s.value(),c.value()).stream().findFirst();
    }
    static Optional<ClockOwner> intentRow(JdbcTemplate jdbc,UUID id) {
        return jdbc.query("select * from stripe_billing_clock_intents where id=?",JdbcStripeBillingClockStore::owner,id).stream().findFirst();
    }
    static Optional<ReadyClock> readyRow(JdbcTemplate jdbc,UUID intent) {
        var owner=intentRow(jdbc,intent);
        if(owner.isEmpty()) return Optional.empty();
        return jdbc.query("select * from stripe_billing_clock_ready where intent_id=?",(r,n)->new ReadyClock(owner.get(),
            r.getObject("creation_operation_id",UUID.class),r.getString("reference"),r.getLong("frozen_time"),r.getLong("provider_created"),
            r.getLong("provider_deletes_after"),r.getTimestamp("observed_at").toInstant(),r.getString("customer_fingerprint")),intent).stream().findFirst();
    }
    private static ClockOwner owner(ResultSet r,int n)throws SQLException {
        return new ClockOwner(r.getObject("id",UUID.class),r.getObject("run_id",UUID.class),new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),
            new SiteId(r.getObject("site_id",UUID.class)),new CustomerId(r.getObject("customer_id",UUID.class)),r.getString("external_account_id"),
            r.getString("credentials_ref"),r.getString("api_version"),r.getLong("initial_frozen_time"),r.getString("name"),r.getString("create_key"),
            r.getString("create_fingerprint"),r.getTimestamp("admitted_at").toInstant());
    }
    static PaymentApplicationException conflict() { return new PaymentApplicationException("PROVIDER_OPERATION_CONFLICT",409,"Provider operation conflicts with the request"); }
}
