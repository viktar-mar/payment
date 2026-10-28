package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
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
        requireResource(account.id(), kind, resourceId);
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
        requirePinnedAccount(current);
        requireResource(current.accountId(), current.kind(), current.resourceId());
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
        if ("ACKNOWLEDGED".equals(status) && !supportedResource(current.kind())) throw conflict();
        if (current.kind() == Kind.CUSTOMER_CREATE && "ACKNOWLEDGED".equals(status)) {
            requirePinnedAccount(current);
            requireResource(current.accountId(), current.kind(), current.resourceId());
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
        requirePinnedAccount(current);requireResource(current.accountId(),current.kind(),current.resourceId());
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
        if (!current.equals(expected) || !current.fingerprint().equals(fingerprint)
            || current.firstDispatchedAt() == null) throw conflict();
        if (!supportedResource(current.kind())) throw conflict();
        if (current.kind() == Kind.CUSTOMER_CREATE) {
            requirePinnedAccount(current);
            requireResource(current.accountId(), current.kind(), current.resourceId());
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

    private void requireResource(PaymentAccountId account, Kind kind, UUID resourceId) {
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

    private static boolean supportedResource(Kind kind) {
        return switch (kind) {
            case PRODUCT_CREATE, PRICE_CREATE, PAYMENT_PREPARE, REFUND_CREATE, CUSTOMER_CREATE -> true;
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
