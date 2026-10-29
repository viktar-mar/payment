package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.ReadyClock;
import dev.genericpayments.platform.provider.application.model.OwnedClockSubscription;
import dev.genericpayments.platform.provider.application.model.OwnedClockSubscriptionReceipt;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.Status;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class JdbcProviderOperationStore implements ProviderOperationStore {
    private final JdbcTemplate jdbc;

    public JdbcProviderOperationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public ProviderOperation reserve(PaymentAccount account, Kind kind, UUID resourceId, String key, String fingerprint) {
        if (account == null || kind == null || resourceId == null) throw conflict();
        text(key, 255);
        text(fingerprint, 255);
        requireAccount(account);
        requireResource(account.id(), kind, resourceId,key,fingerprint,false);
        return reserveRow(account,kind,resourceId,key,fingerprint);
    }

    @Override
    public ProviderOperation reserveOwnedBillingCustomer(PaymentAccount account, ReadyClock ready) {
        if(account==null || ready==null || !account.id().equals(ready.owner().account())
            || !account.externalAccountId().equals(ready.owner().externalAccount())
            || !account.credentialsRef().equals(ready.owner().credentialsRef())) throw conflict();
        requireAccount(account);
        requireResource(account.id(),Kind.CUSTOMER_CREATE,ready.owner().customer().value(),ready.owner().customerKey(),ready.customerFingerprint(),true);
        if(!JdbcStripeBillingClockStore.readyRow(jdbc,ready.owner().intent()).filter(ready::equals).isPresent()) throw conflict();
        return reserveRow(account,Kind.CUSTOMER_CREATE,ready.owner().customer().value(),ready.owner().customerKey(),ready.customerFingerprint());
    }

    @Override
    public ProviderOperation reserveOwnedBillingSubscription(PaymentAccount account,OwnedClockSubscription intent) {
        if(account==null || intent==null || !account.id().equals(intent.clock().owner().account())
            || !account.externalAccountId().equals(intent.clock().owner().externalAccount())
            || !account.credentialsRef().equals(intent.clock().owner().credentialsRef()))throw conflict();
        JdbcStripeBillingClockSubscriptionStore.requireCurrentRow(jdbc,intent);
        requireAccount(account);
        return reserveRow(account,Kind.SUBSCRIPTION_CREATE,intent.createOperation(),intent.operationKey(),intent.fingerprint());
    }
    @Override
    public OwnedClockSubscriptionReceipt acknowledgeOwnedBillingSubscription(OwnedClockSubscription intent,ProviderOperation claim,
            String reference,long created,String item,long start,long end,Instant acknowledgedAt) {
        if(intent==null || claim==null || acknowledgedAt==null || claim.status()!=Status.DISPATCHING || claim.leaseOwner()==null)throw conflict();
        var current=lock(claim.id()).orElseThrow(JdbcProviderOperationStore::conflict);
        if(!current.equals(claim) || current.kind()!=Kind.SUBSCRIPTION_CREATE || !current.accountId().equals(intent.clock().owner().account())
            || !current.resourceId().equals(intent.createOperation()) || !current.operationKey().equals(intent.operationKey())
            || !current.fingerprint().equals(intent.fingerprint()) || current.firstDispatchedAt()==null
            || acknowledgedAt.isBefore(current.firstDispatchedAt()))throw conflict();
        JdbcStripeBillingClockSubscriptionStore.requireCurrentRow(jdbc,intent);
        requirePinnedAccount(current);
        requireFirstSubscriptionDispatch(current);
        JdbcStripeBillingClockSubscriptionStore.requirePristine(jdbc,intent);
        requireSubscriptionReference(current,reference);
        var receipt=new OwnedClockSubscriptionReceipt(intent,current.id(),reference,created,item,start,end,acknowledgedAt.truncatedTo(ChronoUnit.MICROS));
        try {
            // Provider row was acquired before the real DB clock predicate; no second transaction or proxy call.
            one(jdbc.query("""
                update provider_operations set status='ACKNOWLEDGED',provider_reference=?,lease_owner=null,lease_expires_at=null,version=version+1
                where id=? and status='DISPATCHING' and lease_owner=? and version=? and lease_expires_at>clock_timestamp() returning *
                """,this::operation,reference,current.id(),current.leaseOwner(),current.version()));
            jdbc.update("""
                insert into stripe_billing_owned_subscription_receipts(intent_id,payment_account_id,create_operation,provider_operation,
                 reference,provider_created,item_reference,period_start,period_end,acknowledged_at)
                values (?,?,?,?,?,?,?,?,?,?)
                """,intent.id(),current.accountId().value(),intent.createOperation(),current.id(),reference,created,item,start,end,Timestamp.from(receipt.acknowledgedAt()));
            return JdbcStripeBillingClockSubscriptionStore.receiptRow(jdbc,intent).filter(receipt::equals).orElseThrow(JdbcProviderOperationStore::conflict);
        } catch(DataIntegrityViolationException invalid) { throw conflict(); }
    }

    private ProviderOperation reserveRow(PaymentAccount account,Kind kind,UUID resourceId,String key,String fingerprint) {
        jdbc.update("""
            insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,
                kind,resource_id,operation_key,fingerprint)
            values (?,?,?,?,?,?,?,?) on conflict do nothing
            """, UUID.randomUUID(), account.id().value(), account.externalAccountId(), account.credentialsRef(),
            kind.name(), resourceId, key, fingerprint);
        var rows = jdbc.query("""
            select * from provider_operations where payment_account_id=?
                and (operation_key=? or (kind=? and resource_id=?))
            """, this::operation, account.id().value(), key, kind.name(), resourceId);
        if (rows.size() != 1) throw conflict();
        var operation = rows.getFirst();
        if (operation.kind() != kind || !operation.resourceId().equals(resourceId)
            || !operation.operationKey().equals(key) || !operation.fingerprint().equals(fingerprint)
            || !operation.externalAccountId().equals(account.externalAccountId())
            || !operation.credentialsRef().equals(account.credentialsRef())) throw conflict();
        if(kind==Kind.SUBSCRIPTION_CREATE&&operation.firstDispatchedAt()==null)requireFirstSubscriptionDispatch(operation);
        return operation;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<ProviderOperation> find(PaymentAccountId account, String key) {
        if (account == null) throw conflict();
        text(key, 255);
        return jdbc.query("select * from provider_operations where payment_account_id=? and operation_key=?",
            this::operation, account.value(), key).stream().findFirst();
    }

    @Override
    public Optional<ProviderOperation> claim(UUID id, String owner, Instant now, Duration lease) {
        text(owner, 128);
        if (id == null || now == null || !Duration.ofSeconds(60).equals(lease)) throw conflict();
        now = now.truncatedTo(ChronoUnit.MICROS);
        var found = lock(id);
        if (found.isEmpty()) return Optional.empty();
        var current = found.get();
        if (current.status() == Status.ACKNOWLEDGED || current.status() == Status.FAILED
            || (current.leaseExpiresAt() != null && now.isBefore(current.leaseExpiresAt()))
            || (current.firstDispatchedAt() != null && (now.isBefore(current.firstDispatchedAt())
                || !now.isBefore(current.retryBefore())))) return Optional.empty();
        // Owned initial publication uses Site -> Customer -> Account. Detect from persisted
        // intent before the generic Account fence, never after Account has already been held.
        if(current.kind()==Kind.SUBSCRIPTION_CREATE)
            JdbcStripeBillingClockSubscriptionStore.operationIntent(jdbc,current.accountId(),current.resourceId())
                .ifPresent(i->JdbcStripeBillingClockSubscriptionStore.requireCurrentRow(jdbc,i));
        requirePinnedAccount(current);
        requireResource(current.accountId(), current.kind(), current.resourceId(),current.operationKey(),current.fingerprint());
        if(firstDispatchOnly(current) && current.firstDispatchedAt()!=null) return Optional.empty();
        if(current.kind()==Kind.SUBSCRIPTION_CREATE&&current.firstDispatchedAt()==null) {
            requireFirstSubscriptionDispatch(current);
            JdbcStripeBillingClockSubscriptionStore.operationIntent(jdbc,current.accountId(),current.resourceId())
                .ifPresent(i->JdbcStripeBillingClockSubscriptionStore.requirePristine(jdbc,i));
        }
        return jdbc.query("""
            update provider_operations set status='DISPATCHING', lease_owner=?, lease_expires_at=?,
                first_dispatched_at=coalesce(first_dispatched_at,?), retry_before=coalesce(retry_before,?),
                version=version+1
            where id=? and version=? and lease_owner is not distinct from ?
                and (lease_expires_at is null or lease_expires_at<=?) returning *
            """, this::operation, owner, timestamp(now.plus(lease)), timestamp(now),
            timestamp(now.plus(Duration.ofHours(23))), id, current.version(), current.leaseOwner(), timestamp(now))
            .stream().findFirst();
    }

    @Override
    public ProviderOperation acknowledge(UUID id, String owner, long expectedVersion, String reference) {
        text(reference, 255);
        return finishClaim(id, owner, expectedVersion, "ACKNOWLEDGED", reference);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public ProviderOperation lockResumeReceipt(ProviderOperation expected){
        var current=lock(expected.id()).orElseThrow(JdbcProviderOperationStore::conflict);
        if(!current.equals(expected)||current.kind()!=Kind.SUBSCRIPTION_UPDATE||!current.operationKey().equals("subscription:"+current.accountId().value()+":"+current.resourceId()+":RESUME"))throw conflict();
        return current;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public ProviderOperation lockPlanReceipt(ProviderOperation expected){
        var current=lock(expected.id()).orElseThrow(JdbcProviderOperationStore::conflict);
        if(!current.equals(expected)||!java.util.Set.of(Kind.SUBSCRIPTION_UPDATE,Kind.SCHEDULE_CREATE,Kind.SCHEDULE_UPDATE,Kind.SCHEDULE_RELEASE).contains(current.kind()))throw conflict();
        return current;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public ProviderOperation lockSubscriptionMutationReceipt(ProviderOperation expected){
        var current=lock(expected.id()).orElseThrow(JdbcProviderOperationStore::conflict);
        if(!current.equals(expected)||!java.util.Set.of(Kind.SUBSCRIPTION_UPDATE,Kind.SUBSCRIPTION_CANCEL,Kind.INVOICE_PAY).contains(current.kind()))throw conflict();
        requireResource(current.accountId(),current.kind(),current.resourceId(),current.operationKey(),current.fingerprint());
        return current;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public ProviderOperation acknowledgeInTransaction(UUID id,String owner,long version,String reference){text(reference,255);return finishClaim(id,owner,version,"ACKNOWLEDGED",reference);}

    @Override
    public ProviderOperation unknown(UUID id, String owner, long expectedVersion) {
        return finishClaim(id, owner, expectedVersion, "UNKNOWN", null);
    }

    private ProviderOperation finishClaim(UUID id, String owner, long version, String status, String reference) {
        text(owner, 128);
        if (id == null || version < 0) throw conflict();
        // Acquire the row first: a blocked UPDATE could otherwise evaluate its clock predicate
        // before waiting for a lock and finish after the lease has expired.
        var current = lock(id).orElseThrow(JdbcProviderOperationStore::conflict);
        if ("ACKNOWLEDGED".equals(status) && (!supportedResource(current.kind()) || ownedSubscription(current))) throw conflict();
        if ((current.kind() == Kind.TEST_CLOCK_CREATE||current.kind() == Kind.CUSTOMER_CREATE||current.kind()==Kind.SUBSCRIPTION_CREATE||current.kind()==Kind.SETUP_INTENT_CREATE||current.kind()==Kind.SUBSCRIPTION_UPDATE||current.kind()==Kind.SUBSCRIPTION_CANCEL||current.kind()==Kind.INVOICE_PAY||current.kind()==Kind.PORTAL_CONFIGURATION_CREATE||current.kind()==Kind.PORTAL_SESSION_CREATE) && "ACKNOWLEDGED".equals(status)) {
            requirePinnedAccount(current);
            requireResource(current.accountId(), current.kind(), current.resourceId(),current.operationKey(),current.fingerprint());
            requireSubscriptionReference(current,reference);
        }
        try {
            return one(jdbc.query("""
                update provider_operations set status=?, provider_reference=?, lease_owner=null,
                    lease_expires_at=null, version=version+1
                where id=? and status='DISPATCHING' and lease_owner=? and version=?
                    and lease_expires_at>clock_timestamp() returning *
                """, this::operation, status, reference, id, owner, version));
        } catch (DataIntegrityViolationException collision) {
            throw conflict();
        }
    }

    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
    public Optional<ProviderOperation> findById(UUID id) {
        return jdbc.query("select * from provider_operations where id=?",this::operation,id).stream().findFirst();
    }
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public ProviderOperation lockRecovery(ProviderOperation expected) {
        var current=lock(expected.id()).orElseThrow(JdbcProviderOperationStore::conflict);
        if(!current.equals(expected))throw conflict();
        requirePinnedAccount(current);
        // Known ACK local binding repair is distinct from provider/event recovery.
        if(firstDispatchOnly(current) && (current.kind()!=Kind.CUSTOMER_CREATE || current.status()!=Status.ACKNOWLEDGED)) throw conflict();
        requireResource(current.accountId(),current.kind(),current.resourceId(),current.operationKey(),current.fingerprint(),true,
            current.kind()==Kind.CUSTOMER_CREATE && current.status()==Status.ACKNOWLEDGED);
        return current;
    }
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public ProviderOperation bindRecoveredInTransaction(ProviderOperation expected,String reference,String fingerprint) {
        var result=bind(expected,reference,fingerprint);
        if(result.kind()==Kind.PRODUCT_CREATE||result.kind()==Kind.PRICE_CREATE)
            jdbc.update("update provider_operations set manual_review_required=true,safe_error_code='PUBLICATION_REQUIRED' where id=?",result.id());
        return result;
    }
    @Override
    public ProviderOperation bindRecovered(ProviderOperation expected, String reference, String fingerprint) {
        return bind(expected,reference,fingerprint);
    }
    private ProviderOperation bind(ProviderOperation expected, String reference, String fingerprint) {
        text(reference, 255);
        text(fingerprint, 255);
        if (expected == null || expected.id() == null) throw conflict();
        var current = lock(expected.id()).orElseThrow(JdbcProviderOperationStore::conflict);
        // Includes immutable request facts, dispatch bounds, owner and fencing version.
        // Portal has no accepted post-window original-request recovery evidence contract.
        if(current.kind()==Kind.PORTAL_CONFIGURATION_CREATE||current.kind()==Kind.PORTAL_SESSION_CREATE||firstDispatchOnly(current))throw conflict();
        if (!current.equals(expected) || !current.fingerprint().equals(fingerprint)
            || current.firstDispatchedAt() == null) throw conflict();
        if (!supportedResource(current.kind())) throw conflict();
        if (current.kind() == Kind.CUSTOMER_CREATE||current.kind()==Kind.SUBSCRIPTION_CREATE||current.kind()==Kind.SETUP_INTENT_CREATE||current.kind()==Kind.SUBSCRIPTION_UPDATE||current.kind()==Kind.SUBSCRIPTION_CANCEL||current.kind()==Kind.INVOICE_PAY) {
            requirePinnedAccount(current);
            requireResource(current.accountId(), current.kind(), current.resourceId(),current.operationKey(),current.fingerprint());
            requireSubscriptionReference(current,reference);
        }
        if (current.status() == Status.ACKNOWLEDGED) {
            if (!Objects.equals(current.providerReference(), reference)) throw conflict();
            return current;
        }
        try {
            return one(jdbc.query("""
                update provider_operations set status='ACKNOWLEDGED', provider_reference=?,
                    lease_owner=null, lease_expires_at=null, version=version+1
                where id=? and version=? and lease_owner is not distinct from ?
                    and (lease_expires_at is null or lease_expires_at<=clock_timestamp()) returning *
                """, this::operation, reference, current.id(), current.version(), current.leaseOwner()));
        } catch (DataIntegrityViolationException collision) {
            throw conflict();
        }
    }

    private Optional<ProviderOperation> lock(UUID id) {
        return jdbc.query("select * from provider_operations where id=? for update", this::operation, id)
            .stream().findFirst();
    }

    private void requireAccount(PaymentAccount account) {
        if (jdbc.queryForList("""
            select id from payment_accounts where id=? and provider=? and account_mode=?
                and account_status='ACTIVE' and external_account_id=? and credentials_ref=? for share
            """, UUID.class, account.id().value(), account.provider().name(), account.mode().name(),
            account.externalAccountId(), account.credentialsRef()).size() != 1) throw conflict();
    }

    private void requirePinnedAccount(ProviderOperation operation) {
        if (jdbc.queryForList("""
            select id from payment_accounts where id=? and account_status='ACTIVE'
                and external_account_id=? and credentials_ref=? for share
            """, UUID.class, operation.accountId().value(), operation.externalAccountId(), operation.credentialsRef()).size() != 1) throw conflict();
    }

    private void requireResource(PaymentAccountId account, Kind kind, UUID resourceId,String key,String fingerprint) {
        requireResource(account,kind,resourceId,key,fingerprint,true);
    }
    private void requireResource(PaymentAccountId account, Kind kind, UUID resourceId,String key,String fingerprint,boolean ownedCustomer) {
        requireResource(account,kind,resourceId,key,fingerprint,ownedCustomer,false);
    }
    private void requireResource(PaymentAccountId account, Kind kind, UUID resourceId,String key,String fingerprint,boolean ownedCustomer,boolean bindingRepair) {
        if(kind==Kind.TEST_CLOCK_CREATE) {
            var intent=JdbcStripeBillingClockStore.intentRow(jdbc,resourceId).orElseThrow(JdbcProviderOperationStore::conflict);
            if(!account.equals(intent.account()) || !key.equals(intent.createKey()) || !fingerprint.equals(intent.createFingerprint())) throw conflict();
            JdbcStripeBillingClockStore.lockOwner(jdbc,intent);
            JdbcStripeBillingClockStore.requireActiveRow(jdbc,intent);
            return;
        }
        if(kind==Kind.SUBSCRIPTION_CREATE && ownedCustomer) {
            var owned=JdbcStripeBillingClockSubscriptionStore.operationIntent(jdbc,account,resourceId);
            if(owned.isPresent()) {
                var i=owned.get();
                if(!i.operationKey().equals(key)||!i.fingerprint().equals(fingerprint))throw conflict();
                JdbcStripeBillingClockSubscriptionStore.requireCurrentRow(jdbc,i);return;
            }
        }
        if(kind==Kind.SUBSCRIPTION_CREATE || kind==Kind.SETUP_INTENT_CREATE) {
            if(!jdbc.queryForList("""
                select i.id from subscription_operations o join subscriptions s on s.id=o.subscription_id and s.site_id=o.site_id
                join stripe_billing_clock_intents i on i.payment_account_id=s.payment_account_id and i.site_id=s.site_id and i.customer_id=s.customer_id
                where o.id=? and s.payment_account_id=?
                """,UUID.class,resourceId,account.value()).isEmpty()) throw conflict();
        }

        if(kind==Kind.PORTAL_CONFIGURATION_CREATE){
            if(jdbc.queryForList("""
                select i.id from stripe_portal_configuration_intents i
                join subscription_operations o on o.id=i.owner_operation and o.site_id=i.site_id and o.subscription_id=i.owner_subscription and o.kind='PORTAL'
                join subscriptions s on s.id=o.subscription_id and s.site_id=o.site_id and s.customer_id=o.customer_id and s.payment_account_id=i.payment_account_id
                where i.id=? and i.payment_account_id=? and i.operation_key=?
                """,UUID.class,resourceId,account.value(),key).size()!=1)throw conflict();return;
        }
        if(kind==Kind.PORTAL_SESSION_CREATE){
            if(jdbc.queryForList("""
                select i.operation_id from stripe_portal_session_intents i
                join subscription_operations o on o.id=i.operation_id and o.site_id=i.site_id and o.customer_id=i.customer_id and o.subscription_id=i.subscription_id and o.kind='PORTAL'
                join subscriptions s on s.id=i.subscription_id and s.payment_account_id=i.payment_account_id and s.site_id=i.site_id and s.customer_id=i.customer_id
                join stripe_portal_configurations c on c.intent_id=i.configuration_id and c.payment_account_id=i.payment_account_id and c.configuration_reference=i.configuration_reference
                where i.operation_id=? and i.payment_account_id=? and ?=o.provider_operation_key||':SESSION'
                """,UUID.class,resourceId,account.value(),key).size()!=1)throw conflict();return;
        }
        if(kind==Kind.INVOICE_PAY){
            if(jdbc.queryForList("""
                select o.id from subscription_operations o join subscriptions s on s.id=o.subscription_id and s.site_id=o.site_id and s.customer_id=o.customer_id
                join subscription_billing_records b on b.id::text=split_part(o.fingerprint,'.',3) and b.subscription_id=s.id and b.payment_account_id=s.payment_account_id
                join sites site on site.id=s.site_id join customers c on c.site_id=s.site_id and c.id=s.customer_id
                where o.id=? and s.payment_account_id=? and o.kind='RECOVER' and o.fingerprint ~ '^v1\\.recover\\.[0-9a-f-]{36}\\.[a-f0-9]{64}$'
                  and ?=o.provider_operation_key||':INVOICE_PAY' and o.provider_reference=s.provider_reference and s.provider_reference is not null
                  and b.provider_invoice_reference ~ '^in_[A-Za-z0-9]{1,252}$' and site.status='ACTIVE' and c.status='ACTIVE'
                """,UUID.class,resourceId,account.value(),key).size()!=1)throw conflict();return;
        }
        if(kind==Kind.SUBSCRIPTION_CANCEL){
            if(jdbc.queryForList("""
                select o.id from subscription_operations o join subscriptions s on s.id=o.subscription_id and s.site_id=o.site_id and s.customer_id=o.customer_id
                join sites site on site.id=s.site_id join customers c on c.site_id=s.site_id and c.id=s.customer_id
                where o.id=? and s.payment_account_id=? and o.kind='CANCEL' and o.fingerprint ~ '^v1\\.cancel\\.immediate\\.none\\.[a-f0-9]{64}$'
                  and ?=o.provider_operation_key and o.provider_reference=s.provider_reference and s.provider_reference is not null and site.status='ACTIVE' and c.status='ACTIVE'
                """,UUID.class,resourceId,account.value(),key).size()!=1)throw conflict();return;
        }
        if(kind==Kind.SUBSCRIPTION_UPDATE&&jdbc.queryForList("""
            select o.id from subscription_operations o join subscriptions s on s.id=o.subscription_id and s.site_id=o.site_id and s.customer_id=o.customer_id
            join sites site on site.id=s.site_id join customers c on c.site_id=s.site_id and c.id=s.customer_id
            where o.id=? and s.payment_account_id=? and site.status='ACTIVE' and c.status='ACTIVE'
              and s.provider_reference is not null and o.provider_reference=s.provider_reference and ?=o.provider_operation_key
              and ((o.kind='CANCEL' and o.fingerprint ~ '^v1\\.cancel\\.period_end\\.[0-9]+/0\\.[a-f0-9]{64}$')
                or (o.kind='WITHDRAW_CANCEL' and o.fingerprint ~ '^v1\\.cancel\\.withdraw\\.[0-9]+/0\\.[a-f0-9]{64}$'))
            """,UUID.class,resourceId,account.value(),key).size()==1)return;
        if(kind==Kind.SETUP_INTENT_CREATE||kind==Kind.SUBSCRIPTION_UPDATE){
            String suffix=kind==Kind.SETUP_INTENT_CREATE?":SETUP":":METHOD";
            if(jdbc.queryForList("""
                select o.id from subscription_operations o join subscriptions s on s.id=o.subscription_id and s.site_id=o.site_id and s.customer_id=o.customer_id
                join sites site on site.id=s.site_id join customers c on c.site_id=s.site_id and c.id=s.customer_id
                where o.id=? and s.payment_account_id=? and site.status='ACTIVE' and c.status='ACTIVE'
                and ((?='SETUP_INTENT_CREATE' and o.kind in ('CREATE','SETUP') and ?=o.provider_operation_key||':SETUP')
                  or (?='SUBSCRIPTION_UPDATE' and ((o.kind='SETUP' and ?=o.provider_operation_key||':METHOD') or (o.kind='RESUME' and ?=o.provider_operation_key)
                    or (o.kind='CHANGE' and ?=o.provider_operation_key and exists(select 1 from subscription_change_intents i join subscription_quotes q on q.id=i.quote_id join stripe_plan_quotes p on p.reference=q.provider_quote_reference and p.subscription_id=s.id and p.payment_account_id=s.payment_account_id where i.operation_id=o.id and p.timing='IMMEDIATE')))))
                """,UUID.class,resourceId,account.value(),kind.name(),key,kind.name(),key,key,key).size()!=1)throw conflict();
            return;
        }
        if(java.util.Set.of(Kind.SCHEDULE_CREATE,Kind.SCHEDULE_UPDATE,Kind.SCHEDULE_RELEASE).contains(kind)){
            if(jdbc.queryForList("""
                select o.id from subscription_operations o join subscriptions s on s.id=o.subscription_id and s.site_id=o.site_id and s.customer_id=o.customer_id
                join subscription_change_intents i on i.operation_id=o.id and i.site_id=o.site_id and i.subscription_id=o.subscription_id
                join subscription_quotes q on q.id=i.quote_id and q.site_id=i.site_id and q.subscription_id=i.subscription_id
                join stripe_plan_quotes p on p.reference=q.provider_quote_reference and p.subscription_id=s.id and p.payment_account_id=s.payment_account_id
                join sites site on site.id=s.site_id join customers c on c.id=s.customer_id and c.site_id=s.site_id
                where o.id=? and s.payment_account_id=? and site.status='ACTIVE' and c.status='ACTIVE' and p.timing='NEXT_PERIOD'
                and ((o.kind='CHANGE' and ((?='SCHEDULE_CREATE' and ?=o.provider_operation_key||':SCHEDULE_CREATE')
                    or (?='SCHEDULE_UPDATE' and ?=o.provider_operation_key||':SCHEDULE_UPDATE' and exists(select 1 from stripe_plan_schedules x where x.operation_id=o.id and x.quote_id=p.id))))
                  or (o.kind='CLEAR_CHANGE' and ?='SCHEDULE_RELEASE' and ?=o.provider_operation_key||':SCHEDULE_RELEASE'
                    and exists(select 1 from stripe_plan_schedules x where x.operation_id=s.pending_operation_id and x.quote_id=p.id and x.update_step is not null)))
                """,UUID.class,resourceId,account.value(),kind.name(),key,kind.name(),key,kind.name(),key).size()!=1)throw conflict();return;
        }
        if(kind==Kind.SUBSCRIPTION_CREATE){
            // Plain immutable identity proof: never wait on lifecycle Operation/Subscription locks
            // while holding provider-step/Account locks (the lifecycle acquires them in the reverse order).
            if(jdbc.queryForList("""
                select o.id from subscription_operations o
                join subscriptions s on s.id=o.subscription_id and s.site_id=o.site_id and s.customer_id=o.customer_id
                join sites site on site.id=s.site_id
                join customers c on c.id=s.customer_id and c.site_id=s.site_id
                join payment_accounts a on a.id=s.payment_account_id
                where o.id=? and o.kind='CREATE' and o.provider_operation_key=? and s.payment_account_id=?
                  and site.status='ACTIVE' and c.status='ACTIVE' and a.provider='STRIPE' and a.account_mode='SANDBOX'
                """,UUID.class,resourceId,key,account.value()).size()!=1)throw conflict();
            return;
        }
        if (kind == Kind.CUSTOMER_CREATE) {
            // Consistent Billing lock order: operation (if present), Account, Site, Customer.
            // CustomerId is globally keyed; a fresh subscription UUID cannot be a create identity.
            var sites = jdbc.queryForList("select site_id from customers where id=?", UUID.class, resourceId);
            if (sites.size() != 1) throw conflict();
            var site = sites.getFirst();
            if (jdbc.queryForList("select id from sites where id=? and payment_account_id=? and status='ACTIVE' for share",
                    UUID.class, site, account.value()).size() != 1) throw conflict();
            if (jdbc.queryForList("select id from customers where id=? and site_id=? and status='ACTIVE' for share",
                    UUID.class, resourceId, site).size() != 1) throw conflict();
            if (jdbc.queryForList("select id from payment_accounts where id=? and provider='STRIPE' and account_mode='SANDBOX'",
                    UUID.class, account.value()).size() != 1) throw conflict();
            var intent=JdbcStripeBillingClockStore.ownerRow(jdbc,account,new SiteId(site),new CustomerId(resourceId));
            if(intent.isPresent()) {
                if(!ownedCustomer) throw conflict();
                var owner=intent.get();
                JdbcStripeBillingClockStore.lockOwner(jdbc,owner);
                if(bindingRepair) JdbcStripeBillingClockStore.requireBindingRow(jdbc,owner);
                else JdbcStripeBillingClockStore.requireActiveRow(jdbc,owner);
                var ready=JdbcStripeBillingClockStore.readyRow(jdbc,owner.intent()).orElseThrow(JdbcProviderOperationStore::conflict);
                if(!key.equals(owner.customerKey()) || !fingerprint.equals(ready.customerFingerprint())
                    || jdbc.queryForList("""
                        select id from provider_operations where id=? and payment_account_id=? and resource_id=?
                         and kind='TEST_CLOCK_CREATE' and status='ACKNOWLEDGED' and provider_reference=?
                         and external_account_id=? and credentials_ref=? and operation_key=? and fingerprint=?
                        """,UUID.class,ready.creationOperation(),account.value(),owner.intent(),ready.reference(),
                        owner.externalAccount(),owner.credentialsRef(),owner.createKey(),owner.createFingerprint()).size()!=1) throw conflict();
            }
            return;

        }
        String query = switch (kind) {
            case PRODUCT_CREATE -> "select p.id from products p join sites s on s.id=p.site_id where p.id=? and s.payment_account_id=? for share of p,s";
            case PRICE_CREATE -> "select p.id from prices p join products product on product.id=p.product_id and product.site_id=p.site_id join sites s on s.id=p.site_id where p.id=? and s.payment_account_id=? for share of p,product,s";
            case PAYMENT_PREPARE -> "select p.id from payments p where p.id=? and p.payment_account_id=? for share of p";
            case REFUND_CREATE -> "select r.id from refunds r join payments p on p.id=r.payment_id where r.id=? and p.payment_account_id=? for share of r,p";
            // Each later command needs its own durable resource proof. Never reuse payment recovery.
            default -> throw conflict();
        };
        if (jdbc.queryForList(query, UUID.class, resourceId, account.value()).size() != 1) throw conflict();
    }
    /** Account FOR SHARE is already held. Plain reads avoid Step -> lifecycle row-lock inversion. */
    private void requireFirstSubscriptionDispatch(ProviderOperation step){
        if(jdbc.queryForList("""
            select o.id from subscription_operations o join subscriptions s on s.id=o.subscription_id and s.site_id=o.site_id and s.customer_id=o.customer_id
            where o.id=? and s.payment_account_id=? and o.kind='CREATE' and o.provider_operation_key=?
              and o.status in ('RESERVED','PROCESSING','REQUIRES_ACTION','UNKNOWN') and o.provider_reference is null
              and s.provider_reference is null and s.billing_status='INCOMPLETE' and s.entitled_price_id is null
              and s.current_period_start is null and s.paid_through is null and s.trial_start is null
              and s.debt_billing_record_id is null and s.pending_operation_id is null
            """,UUID.class,step.resourceId(),step.accountId().value(),step.operationKey()).size()!=1)throw conflict();
    }

    private boolean firstDispatchOnly(ProviderOperation operation) {
        return ownedSubscription(operation) || operation.kind()==Kind.TEST_CLOCK_CREATE || (operation.kind()==Kind.CUSTOMER_CREATE
            && !jdbc.queryForList("select id from stripe_billing_clock_intents where payment_account_id=? and customer_id=?",
                UUID.class,operation.accountId().value(),operation.resourceId()).isEmpty());
    }
    private boolean ownedSubscription(ProviderOperation operation) {
        return operation.kind()==Kind.SUBSCRIPTION_CREATE && !jdbc.queryForList(
            "select id from stripe_billing_owned_subscriptions where payment_account_id=? and create_operation=?",
            UUID.class,operation.accountId().value(),operation.resourceId()).isEmpty();
    }
    private void requireSubscriptionReference(ProviderOperation operation,String reference){
        if(operation.kind()==Kind.TEST_CLOCK_CREATE){if(reference==null||!reference.matches("clock_[A-Za-z0-9]{1,249}"))throw conflict();return;}
        if(operation.kind()==Kind.CUSTOMER_CREATE && firstDispatchOnly(operation)){if(reference==null||!reference.matches("cus_[A-Za-z0-9]{1,251}"))throw conflict();return;}
        if(operation.kind()==Kind.PORTAL_CONFIGURATION_CREATE){if(reference==null||!reference.matches("bpc_[A-Za-z0-9]{1,251}"))throw conflict();return;}
        if(operation.kind()==Kind.PORTAL_SESSION_CREATE){if(reference==null||!reference.matches("bps_[A-Za-z0-9]{1,251}"))throw conflict();return;}
        if(java.util.Set.of(Kind.SCHEDULE_CREATE,Kind.SCHEDULE_UPDATE,Kind.SCHEDULE_RELEASE).contains(operation.kind())){
            if(reference==null||!reference.matches("sub_sched_[A-Za-z0-9]{1,245}"))throw conflict();
            if(operation.kind()!=Kind.SCHEDULE_CREATE&&jdbc.queryForList("""
                select x.operation_id from stripe_plan_schedules x join subscription_operations o on o.id=?
                join subscription_change_intents i on i.operation_id=o.id join subscription_quotes q on q.id=i.quote_id
                join stripe_plan_quotes p on p.reference=q.provider_quote_reference and p.id=x.quote_id
                where x.payment_account_id=? and x.schedule_reference=? and ((o.kind='CHANGE' and x.operation_id=o.id)
                  or (o.kind='CLEAR_CHANGE' and x.subscription_id=o.subscription_id))
                """,UUID.class,operation.resourceId(),operation.accountId().value(),reference).size()!=1)throw conflict();return;
        }
        if(operation.kind()==Kind.INVOICE_PAY){
            if(reference==null||!reference.matches("in_[A-Za-z0-9]{1,252}")||jdbc.queryForList("select o.id from subscription_operations o join subscription_billing_records b on b.id::text=split_part(o.fingerprint,'.',3) where o.id=? and o.kind='RECOVER' and b.payment_account_id=? and b.subscription_id=o.subscription_id and b.provider_invoice_reference=?",UUID.class,operation.resourceId(),operation.accountId().value(),reference).size()!=1)throw conflict();return;
        }
        if(operation.kind()==Kind.SETUP_INTENT_CREATE){if(reference==null||!reference.matches("seti_[A-Za-z0-9]{1,250}"))throw conflict();return;}
        if(operation.kind()==Kind.SUBSCRIPTION_UPDATE||operation.kind()==Kind.SUBSCRIPTION_CANCEL){if(reference==null||!reference.matches("sub_[A-Za-z0-9]{1,251}")||jdbc.queryForList("select o.id from subscription_operations o join subscriptions s on s.id=o.subscription_id where o.id=? and s.payment_account_id=? and s.provider_reference=?",UUID.class,operation.resourceId(),operation.accountId().value(),reference).size()!=1)throw conflict();return;}
        if(operation.kind()!=Kind.SUBSCRIPTION_CREATE)return;
        if(reference==null||!reference.matches("sub_[A-Za-z0-9]{1,251}")||jdbc.queryForList("""
            select o.id from subscription_operations o join subscriptions s on s.id=o.subscription_id and s.site_id=o.site_id and s.customer_id=o.customer_id
            where o.id=? and s.payment_account_id=? and (o.provider_reference is null or o.provider_reference=?)
              and (s.provider_reference is null or s.provider_reference=?)
            """,UUID.class,operation.resourceId(),operation.accountId().value(),reference,reference).size()!=1)throw conflict();
    }

    private static boolean supportedResource(Kind kind) {
        return switch (kind) {
            case PRODUCT_CREATE, PRICE_CREATE, PAYMENT_PREPARE, REFUND_CREATE, TEST_CLOCK_CREATE, CUSTOMER_CREATE, SUBSCRIPTION_CREATE, SETUP_INTENT_CREATE, SUBSCRIPTION_UPDATE, SUBSCRIPTION_CANCEL, INVOICE_PAY, SCHEDULE_CREATE, SCHEDULE_UPDATE, SCHEDULE_RELEASE, PORTAL_CONFIGURATION_CREATE, PORTAL_SESSION_CREATE -> true;
            default -> false;
        };
    }

    private ProviderOperation operation(ResultSet row, int index) throws SQLException {
        return new ProviderOperation(row.getObject("id", UUID.class),
            new PaymentAccountId(row.getObject("payment_account_id", UUID.class)), row.getString("external_account_id"),
            row.getString("credentials_ref"), Kind.valueOf(row.getString("kind")), row.getObject("resource_id", UUID.class),
            row.getString("operation_key"), row.getString("fingerprint"), Status.valueOf(row.getString("status")),
            row.getString("provider_reference"), instant(row, "first_dispatched_at"), instant(row, "retry_before"),
            row.getString("lease_owner"), instant(row, "lease_expires_at"), row.getLong("version"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }

    private static ProviderOperation one(List<ProviderOperation> rows) {
        if (rows.size() != 1) throw conflict();
        return rows.getFirst();
    }

    private static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
            || value.codePoints().anyMatch(Character::isISOControl)) throw conflict();
    }

    private static PaymentApplicationException conflict() {
        return new PaymentApplicationException("PROVIDER_OPERATION_CONFLICT", 409, "Provider operation conflicts with the request");
    }
}
