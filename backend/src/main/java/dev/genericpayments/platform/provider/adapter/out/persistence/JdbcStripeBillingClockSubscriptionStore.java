package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeBillingClockIdentity;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingClockSubscriptionStore;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import static dev.genericpayments.platform.provider.adapter.out.persistence.JdbcStripeBillingClockStore.conflict;

@Repository
@Transactional(propagation=Propagation.REQUIRES_NEW)
public class JdbcStripeBillingClockSubscriptionStore implements StripeBillingClockSubscriptionStore {
    private final JdbcTemplate jdbc;
    private final DigestPort digest;
    public JdbcStripeBillingClockSubscriptionStore(JdbcTemplate jdbc,DigestPort digest){this.jdbc=jdbc;this.digest=digest;}

    @Transactional(propagation=Propagation.MANDATORY)
    public void requireOrdinaryConfirmation(PaymentAccountId account,dev.genericpayments.platform.sites.domain.SiteId site,dev.genericpayments.platform.customers.domain.CustomerId customer){
        requireTransaction();
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from stripe_billing_clock_intents where payment_account_id=? and site_id=? and customer_id=?)",Boolean.class,account.value(),site.value(),customer.value())))throw conflict();
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public OwnedClockSubscriptionReceipt requireConfirmationReceipt(OwnedClockSubscriptionReceipt expected,PaymentAccount account,
            dev.genericpayments.platform.subscriptions.domain.Subscription s,
            dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation operation){
        requireTransaction();
        if(expected==null||account==null||s==null||operation==null)throw conflict();
        var i=intentRow(jdbc,account.id(),s.id()).orElseThrow(JdbcStripeBillingClockStore::conflict);
        requireCurrentRow(jdbc,i);
        var r=receiptRow(jdbc,i).filter(expected::equals).orElseThrow(JdbcStripeBillingClockStore::conflict);
        var owner=i.clock().owner();
        if(!account.id().equals(owner.account())||account.provider()!=PaymentProviderType.STRIPE||account.mode()!=PaymentAccountMode.SANDBOX||account.status()!=PaymentAccountStatus.ACTIVE
            ||!account.externalAccountId().equals(owner.externalAccount())||!account.credentialsRef().equals(owner.credentialsRef())
            ||!s.siteId().equals(owner.site())||!s.customerId().equals(owner.customer())||!s.paymentAccountId().equals(owner.account())
            ||!s.billingPrice().equals(i.terms())||s.billingStatus()!=dev.genericpayments.platform.subscriptions.domain.Subscription.Status.INCOMPLETE
            ||!r.reference().equals(s.providerReference())||s.entitledPrice()!=null||s.paidThrough()!=null||s.trial()!=null||s.debt()!=null||s.pendingChange()!=null||s.cancellation()!=null
            ||s.currentPeriod()!=null&&(!s.currentPeriod().start().equals(Instant.ofEpochSecond(r.periodStart()))||!s.currentPeriod().end().equals(Instant.ofEpochSecond(r.periodEnd())))
            ||!operation.id().equals(i.createOperation())||!operation.subscriptionId().equals(i.subscription())||!operation.siteId().equals(owner.site())||!operation.customerId().equals(owner.customer())
            ||operation.kind()!=dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Kind.CREATE
            ||!Set.of(dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Status.PROCESSING,dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Status.REQUIRES_ACTION,dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Status.UNKNOWN).contains(operation.status())
            ||!r.reference().equals(operation.providerReference())||!operation.providerOperationKey().equals(i.operationKey())||!operation.fingerprint().equals(i.requestFingerprint())
            ||operation.expectedRevision()!=i.expectedRevision()||operation.policyVersion()!=i.policyVersion())throw conflict();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from site_subscription_policies where site_id=? and enabled and version=?)",Boolean.class,owner.site().value(),i.policyVersion())))throw conflict();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select owned_initial_confirmation_available(?) and exists(select 1 from subscriptions s join subscription_operations o on o.id=? where s.id=? and s.revision=? and o.version=?)",Boolean.class,i.id(),operation.id(),s.id(),s.revision(),operation.version())))throw conflict();
        return r;
    }
    /** Narrow root writer proof in the caller transaction. Reuses owner Site/Customer/Account locks;
     * never acquires a provider step or lifecycle lock beneath those rows. */
    public static void requireConfirmationGrant(JdbcTemplate jdbc,dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant g,OwnedClockSubscriptionReceipt expected){
        requireTransaction();if(g==null||expected==null)throw conflict();
        var i=intentRow(jdbc,g.account(),g.subscription()).orElseThrow(JdbcStripeBillingClockStore::conflict);
        requireCurrentRow(jdbc,i);var r=receiptRow(jdbc,i).filter(expected::equals).orElseThrow(JdbcStripeBillingClockStore::conflict);
        var b=g.binding();var o=i.clock().owner();
        if(!"owned-initial-v1".equals(b.schema())||!i.id().equals(b.ownedIntent())||!i.createOperation().equals(g.operation())||!o.site().equals(g.site())||!o.customer().equals(g.customer())
            ||!r.providerOperation().equals(b.stepId())||!i.operationKey().equals(b.operationKey())||!r.reference().equals(b.subscriptionReference())||!i.customerReference().equals(b.customerReference())
            ||!OwnedInitialConfirmationProof.hash(r,b.invoiceReference(),b.allocationReference(),b.intentReference()).equals(b.proof())
            ||g.issuedAt().isBefore(r.acknowledgedAt())||g.consumedAt()!=null||g.supersededAt()!=null||g.sessionId()!=null||g.origin()!=null||g.clientExpiresAt()!=null)throw conflict();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select owned_initial_confirmation_available(?)",Boolean.class,i.id())))throw conflict();
    }
    private static void requireTransaction(){if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Confirmation authority requires caller transaction");}

    public OwnedClockSubscription admitFresh(PaymentAccount account,ClockOwner owner,UUID subscription,UUID operation,Instant at) {
        if(account==null || owner==null || subscription==null || operation==null || at==null
            || !account.id().equals(owner.account()) || !account.externalAccountId().equals(owner.externalAccount())
            || !account.credentialsRef().equals(owner.credentialsRef()) || account.provider()!=PaymentProviderType.STRIPE
            || account.mode()!=PaymentAccountMode.SANDBOX)throw conflict();
        lockOwner(jdbc,owner);
        JdbcStripeBillingClockStore.requireActiveRow(jdbc,owner);
        var existing=intentRow(jdbc,account.id(),subscription);
        if(existing.isPresent()) {
            var i=existing.get();
            if(!i.clock().owner().equals(owner)||!i.createOperation().equals(operation))throw conflict();
            return requireCurrentRow(jdbc,i);
        }
        var clock=JdbcStripeBillingClockStore.readyRow(jdbc,owner.intent()).orElseThrow(JdbcStripeBillingClockStore::conflict);
        // No lifecycle locks under Site/Customer/Account. Immutable source relations are compared at every boundary.
        var candidates=jdbc.query("""
            select b.id customer_binding,b.creation_operation_id customer_creation_operation,b.provider_reference customer_reference,
             o.fingerprint request_fingerprint,o.provider_operation_key operation_key,o.expected_revision,o.policy_version,
             s.billing_price_id price_id,s.billing_price_key price_key,s.billing_price_version price_version,s.product_id,
             s.billing_amount_minor amount_minor,s.currency,s.billing_interval,s.billing_trial_days trial_days,
             s.billing_trial_policy trial_policy,s.billing_tax_mode tax_mode,pm.provider_price_id price_reference,gm.provider_product_id product_reference
            from subscription_operations o join subscriptions s on s.id=o.subscription_id and s.site_id=o.site_id and s.customer_id=o.customer_id
            join stripe_billing_customers b on b.payment_account_id=s.payment_account_id and b.site_id=s.site_id and b.customer_id=s.customer_id
            join prices p on p.id=s.billing_price_id and p.site_id=s.site_id and p.product_id=s.product_id
            join products g on g.id=p.product_id and g.site_id=p.site_id
            join provider_price_mappings pm on pm.payment_account_id=s.payment_account_id and pm.price_id=p.id
            join provider_product_mappings gm on gm.payment_account_id=s.payment_account_id and gm.product_id=p.product_id
            where o.id=? and s.id=? and s.payment_account_id=? and s.site_id=? and s.customer_id=?
              and o.kind='CREATE' and o.status in ('RESERVED','PROCESSING') and o.provider_reference is null
              and s.provider_reference is null and s.billing_status='INCOMPLETE' and s.revision=o.expected_revision
              and s.entitled_price_id is null and s.current_period_start is null and s.current_period_end is null
              and s.paid_through is null and s.trial_start is null and s.trial_end is null
              and s.debt_billing_record_id is null and s.pending_operation_id is null and s.cancellation_effective_at is null
              and p.status='PUBLISHED' and p.published_at is not null and p.price_type='SUBSCRIPTION' and g.status='ACTIVE'
              and p.price_key=s.billing_price_key and p.price_version=s.billing_price_version and p.amount_minor=s.billing_amount_minor
              and p.currency=s.currency and p.billing_interval=s.billing_interval and p.trial_days=s.billing_trial_days
              and p.trial_payment_method_policy=s.billing_trial_policy and p.tax_mode=s.billing_tax_mode
              and not exists(select 1 from provider_operations x where x.payment_account_id=s.payment_account_id
                and (x.resource_id=o.id or x.operation_key=o.provider_operation_key))
            """,(r,n)->map(r,UUID.randomUUID(),clock,subscription,operation,"0".repeat(64),at.truncatedTo(ChronoUnit.MICROS)),
            operation,subscription,account.id().value(),owner.site().value(),owner.customer().value());
        if(candidates.size()!=1)throw conflict();
        var draft=candidates.getFirst(); var i=withFingerprint(draft,StripeBillingClockIdentity.subscriptionFingerprint(digest,draft));
        requireLineage(jdbc,i);
        var t=i.terms();
        jdbc.update("""
            insert into stripe_billing_owned_subscriptions(id,clock_intent,payment_account_id,site_id,customer_id,subscription_id,create_operation,
             customer_binding,customer_creation_operation,customer_reference,operation_key,request_fingerprint,expected_revision,policy_version,
             price_id,price_key,price_version,product_id,amount_minor,currency,billing_interval,trial_days,trial_policy,tax_mode,
             price_reference,product_reference,fingerprint,admitted_at)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict do nothing
            """,i.id(),owner.intent(),account.id().value(),owner.site().value(),owner.customer().value(),subscription,operation,
            i.customerBinding(),i.customerCreationOperation(),i.customerReference(),i.operationKey(),i.requestFingerprint(),i.expectedRevision(),i.policyVersion(),
            t.priceId().value(),t.priceKey(),t.version(),t.productId().value(),t.regularAmount().minorUnits(),t.regularAmount().currency(),
            t.interval().name(),t.trialDays(),t.trialPolicy().name(),t.taxMode().name(),i.priceReference(),i.productReference(),i.fingerprint(),Timestamp.from(i.admittedAt()));
        // A concurrent identical admission may have won with its own generated ID/admission UTC.
        var saved=intentRow(jdbc,account.id(),subscription).orElseThrow(JdbcStripeBillingClockStore::conflict);
        if(!saved.createOperation().equals(operation)||!saved.clock().equals(clock))throw conflict();
        return requireCurrentRow(jdbc,saved);
    }
    public Optional<OwnedClockSubscription> find(PaymentAccountId account,UUID subscription) {
        return intentRow(jdbc,account,subscription).map(i->requireCurrentRow(jdbc,i));
    }
    public OwnedClockSubscription requireCurrent(OwnedClockSubscription intent){return requireCurrentRow(jdbc,intent);}
    public Optional<OwnedClockSubscriptionReceipt> receipt(OwnedClockSubscription intent){requireCurrentRow(jdbc,intent);return receiptRow(jdbc,intent);}
    public OwnedClockSubscriptionReceipt requireReceipt(PaymentAccountId account,UUID subscription){
        var i=intentRow(jdbc,account,subscription).orElseThrow(JdbcStripeBillingClockStore::conflict);
        requireCurrentRow(jdbc,i);return receiptRow(jdbc,i).orElseThrow(JdbcStripeBillingClockStore::conflict);
    }
    static OwnedClockSubscription requireCurrentRow(JdbcTemplate jdbc,OwnedClockSubscription i){
        if(i==null)throw conflict();
        lockOwner(jdbc,i.clock().owner());
        JdbcStripeBillingClockStore.requireActiveRow(jdbc,i.clock().owner());
        if(!intentRow(jdbc,i.clock().owner().account(),i.subscription()).filter(i::equals).isPresent())throw conflict();
        requireLineage(jdbc,i);return i;
    }
    /** Initial Subscription publication locks Site, Customer, then Account. Acquire this order
     * before any Account lock; the inherited clock/Customer setup helper is Account-first.
     * Provider steps may already hold their own row, but never acquire lifecycle row locks. */
    private static void lockOwner(JdbcTemplate jdbc,ClockOwner owner){
        if(jdbc.queryForList("select id from sites where id=? and payment_account_id=? and status='ACTIVE' for share",
            UUID.class,owner.site().value(),owner.account().value()).size()!=1)throw conflict();
        if(jdbc.queryForList("select id from customers where id=? and site_id=? and status='ACTIVE' for share",
            UUID.class,owner.customer().value(),owner.site().value()).size()!=1)throw conflict();
        if(jdbc.queryForList("""
            select id from payment_accounts where id=? and provider='STRIPE' and account_mode='SANDBOX'
             and account_status='ACTIVE' and external_account_id=? and credentials_ref=? for share
            """,UUID.class,owner.account().value(),owner.externalAccount(),owner.credentialsRef()).size()!=1)throw conflict();
    }
    static void requirePristine(JdbcTemplate jdbc,OwnedClockSubscription i){
        if(jdbc.queryForList("""
            select s.id from subscriptions s join subscription_operations o on o.subscription_id=s.id
            where s.id=? and o.id=? and s.revision=? and o.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN')
             and o.provider_reference is null and s.provider_reference is null and s.billing_status='INCOMPLETE'
             and s.entitled_price_id is null and s.current_period_start is null and s.current_period_end is null
             and s.paid_through is null and s.trial_start is null and s.trial_end is null and s.debt_billing_record_id is null
             and s.pending_operation_id is null and s.cancellation_effective_at is null and s.terminal_reason is null
            """,UUID.class,i.subscription(),i.createOperation(),i.expectedRevision()).size()!=1)throw conflict();
    }
    static void requireLineage(JdbcTemplate jdbc,OwnedClockSubscription i){
        var c=i.clock(); var o=c.owner(); var t=i.terms();
        if(!JdbcStripeBillingClockStore.readyRow(jdbc,o.intent()).filter(c::equals).isPresent())throw conflict();
        if(jdbc.queryForList("""
            select b.id from stripe_billing_customers b
            join provider_operations p on p.id=b.creation_operation_id and p.payment_account_id=b.payment_account_id and p.resource_id=b.customer_id
            join provider_operations k on k.id=? and k.payment_account_id=b.payment_account_id and k.resource_id=?
            where b.id=? and b.payment_account_id=? and b.site_id=? and b.customer_id=? and b.creation_operation_id=? and b.provider_reference=?
             and p.kind='CUSTOMER_CREATE' and p.status='ACKNOWLEDGED' and p.provider_reference=b.provider_reference
             and p.operation_key=? and p.fingerprint=? and p.external_account_id=? and p.credentials_ref=?
             and k.kind='TEST_CLOCK_CREATE' and k.status='ACKNOWLEDGED' and k.provider_reference=?
             and k.operation_key=? and k.fingerprint=? and k.external_account_id=? and k.credentials_ref=?
            """,UUID.class,c.creationOperation(),o.intent(),i.customerBinding(),o.account().value(),o.site().value(),o.customer().value(),
            i.customerCreationOperation(),i.customerReference(),o.customerKey(),c.customerFingerprint(),o.externalAccount(),o.credentialsRef(),
            c.reference(),o.createKey(),o.createFingerprint(),o.externalAccount(),o.credentialsRef()).size()!=1)throw conflict();
        if(jdbc.queryForList("""
            select s.id from subscriptions s join subscription_operations x on x.subscription_id=s.id and x.site_id=s.site_id and x.customer_id=s.customer_id
            join prices p on p.id=s.billing_price_id and p.site_id=s.site_id and p.product_id=s.product_id
            join provider_price_mappings pm on pm.payment_account_id=s.payment_account_id and pm.price_id=p.id
            join provider_product_mappings gm on gm.payment_account_id=s.payment_account_id and gm.product_id=p.product_id
            where s.id=? and s.payment_account_id=? and s.site_id=? and s.customer_id=? and x.id=? and x.kind='CREATE'
             and x.provider_operation_key=? and x.fingerprint=? and x.expected_revision=? and x.policy_version=?
             and s.billing_price_id=? and s.billing_price_key=? and s.billing_price_version=? and s.product_id=?
             and s.billing_amount_minor=? and s.currency=? and s.billing_interval=? and s.billing_trial_days=? and s.billing_trial_policy=? and s.billing_tax_mode=?
             and p.price_key=s.billing_price_key and p.price_version=s.billing_price_version and p.amount_minor=s.billing_amount_minor
             and p.currency=s.currency and p.billing_interval=s.billing_interval and p.trial_days=s.billing_trial_days
             and p.trial_payment_method_policy=s.billing_trial_policy and p.tax_mode=s.billing_tax_mode
             and p.published_at is not null and p.status in ('PUBLISHED','ARCHIVED') and p.price_type='SUBSCRIPTION'
             and pm.provider_price_id=? and gm.provider_product_id=?
             and (s.provider_reference is null or exists(select 1 from stripe_billing_owned_subscription_receipts r where r.intent_id=? and r.reference=s.provider_reference))
             and (x.provider_reference is null or exists(select 1 from stripe_billing_owned_subscription_receipts r where r.intent_id=? and r.reference=x.provider_reference))
            """,UUID.class,i.subscription(),o.account().value(),o.site().value(),o.customer().value(),i.createOperation(),i.operationKey(),i.requestFingerprint(),
            i.expectedRevision(),i.policyVersion(),t.priceId().value(),t.priceKey(),t.version(),t.productId().value(),t.regularAmount().minorUnits(),
            t.regularAmount().currency(),t.interval().name(),t.trialDays(),t.trialPolicy().name(),t.taxMode().name(),i.priceReference(),i.productReference(),i.id(),i.id()).size()!=1)throw conflict();
    }
    static Optional<OwnedClockSubscription> intentRow(JdbcTemplate jdbc,PaymentAccountId account,UUID subscription){
        return jdbc.query("select * from stripe_billing_owned_subscriptions where payment_account_id=? and subscription_id=?",(r,n)->{
            var c=JdbcStripeBillingClockStore.readyRow(jdbc,r.getObject("clock_intent",UUID.class)).orElseThrow(JdbcStripeBillingClockStore::conflict);
            return map(r,r.getObject("id",UUID.class),c,r.getObject("subscription_id",UUID.class),r.getObject("create_operation",UUID.class),r.getString("fingerprint"),r.getTimestamp("admitted_at").toInstant());
        },account.value(),subscription).stream().findFirst();
    }
    static Optional<OwnedClockSubscription> operationIntent(JdbcTemplate jdbc,PaymentAccountId account,UUID operation){
        var ids=jdbc.queryForList("select subscription_id from stripe_billing_owned_subscriptions where payment_account_id=? and create_operation=?",UUID.class,account.value(),operation);
        return ids.isEmpty()?Optional.empty():intentRow(jdbc,account,ids.getFirst());
    }
    static Optional<OwnedClockSubscriptionReceipt> receiptRow(JdbcTemplate jdbc,OwnedClockSubscription i){
        return jdbc.query("""
            select r.* from stripe_billing_owned_subscription_receipts r join provider_operations p on p.id=r.provider_operation
            where r.intent_id=? and p.status='ACKNOWLEDGED' and p.kind='SUBSCRIPTION_CREATE' and p.payment_account_id=?
             and p.resource_id=? and p.operation_key=? and p.fingerprint=? and p.provider_reference=r.reference
             and p.external_account_id=? and p.credentials_ref=?
            """,(r,n)->new OwnedClockSubscriptionReceipt(i,r.getObject("provider_operation",UUID.class),r.getString("reference"),r.getLong("provider_created"),
            r.getString("item_reference"),r.getLong("period_start"),r.getLong("period_end"),r.getTimestamp("acknowledged_at").toInstant()),
            i.id(),i.clock().owner().account().value(),i.createOperation(),i.operationKey(),i.fingerprint(),i.clock().owner().externalAccount(),i.clock().owner().credentialsRef()).stream().findFirst();
    }
    private static OwnedClockSubscription map(ResultSet r,UUID id,ReadyClock c,UUID subscription,UUID operation,String fp,Instant at)throws SQLException {
        var terms=new SubscriptionPriceTerms(new PriceId(r.getObject("price_id",UUID.class)),r.getString("price_key"),r.getInt("price_version"),
            new ProductId(r.getObject("product_id",UUID.class)),new Money(r.getLong("amount_minor"),r.getString("currency")),BillingInterval.valueOf(r.getString("billing_interval")),
            r.getInt("trial_days"),TrialPaymentMethodPolicy.valueOf(r.getString("trial_policy")),TaxMode.valueOf(r.getString("tax_mode")));
        return new OwnedClockSubscription(id,c,r.getObject("customer_binding",UUID.class),r.getObject("customer_creation_operation",UUID.class),r.getString("customer_reference"),
            subscription,operation,r.getString("operation_key"),r.getString("request_fingerprint"),r.getLong("expected_revision"),r.getLong("policy_version"),terms,
            r.getString("price_reference"),r.getString("product_reference"),fp,at);
    }
    private static OwnedClockSubscription withFingerprint(OwnedClockSubscription i,String fp){return new OwnedClockSubscription(i.id(),i.clock(),i.customerBinding(),i.customerCreationOperation(),i.customerReference(),i.subscription(),i.createOperation(),i.operationKey(),i.requestFingerprint(),i.expectedRevision(),i.policyVersion(),i.terms(),i.priceReference(),i.productReference(),fp,i.admittedAt());}
}
