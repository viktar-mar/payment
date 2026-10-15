package dev.genericpayments.platform.payments.adapter.out.persistence;

import dev.genericpayments.platform.payments.application.port.out.PendingProviderRecoveryPort;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class PendingProviderRecoveryPersistenceAdapter implements PendingProviderRecoveryPort {
    // Completion is already-committed publication, never permission to publish or invent an audit
    // actor.
    private static final String COMPLETED_CATALOG = """
        o.status='ACKNOWLEDGED' and o.kind in ('PRODUCT_CREATE','PRICE_CREATE') and exists(
          select 1 from products pr join prices p on p.product_id=pr.id and p.site_id=pr.site_id
          join payment_accounts ca on ca.id=o.payment_account_id and ca.provider='STRIPE'
            and ca.account_mode='SANDBOX' and ca.account_status='ACTIVE'
            and ca.external_account_id=o.external_account_id and ca.credentials_ref=o.credentials_ref
          join provider_product_mappings pm on pm.payment_account_id=ca.id and pm.product_id=pr.id
          join provider_price_mappings rm on rm.payment_account_id=ca.id and rm.price_id=p.id
          join provider_operations po on po.payment_account_id=ca.id and po.kind='PRODUCT_CREATE'
            and po.resource_id=pr.id and po.status='ACKNOWLEDGED' and po.provider_reference=pm.provider_product_id
            and po.external_account_id=o.external_account_id and po.credentials_ref=o.credentials_ref
            and po.operation_key='catalog_product_'||ca.id::text||'_'||pr.id::text
          join provider_operations qo on qo.payment_account_id=ca.id and qo.kind='PRICE_CREATE'
            and qo.resource_id=p.id and qo.status='ACKNOWLEDGED' and qo.provider_reference=rm.provider_price_id
            and qo.external_account_id=o.external_account_id and qo.credentials_ref=o.credentials_ref
            and qo.operation_key='catalog_price_'||ca.id::text||'_'||p.id::text||'_v'||p.price_version::text
          where p.status in ('PUBLISHED','ARCHIVED') and p.published_at is not null
            and (o.kind='PRODUCT_CREATE' and o.id=po.id or o.kind='PRICE_CREATE' and o.id=qo.id))
        """;
    private final JdbcTemplate jdbc;
    private final ProviderOperationStore operations;
    private final Clock clock;

    public PendingProviderRecoveryPersistenceAdapter(
            JdbcTemplate jdbc,
            ProviderOperationStore operations,
            @Qualifier("providerReferenceClock") Clock clock) {
        this.jdbc = jdbc;
        this.operations = operations;
        this.clock = clock;
    }

    public Optional<Claim> claimNext() {
        return acquire(null);
    }

    public Optional<Claim> claim(UUID id) {
        Objects.requireNonNull(id);
        return acquire(id);
    }

    private Optional<Claim> acquire(UUID id) {
        var ids = jdbc.queryForList(
                """
            select o.id from provider_operations o join payment_accounts a on a.id=o.payment_account_id
            where a.provider='STRIPE' and a.account_mode='SANDBOX' and a.account_status='ACTIVE'
              and o.status in ('DISPATCHING','UNKNOWN','ACKNOWLEDGED')
              and o.next_attempt_at<=clock_timestamp()
              and (o.lease_expires_at is null or o.lease_expires_at<=clock_timestamp())
              and (?::uuid is null or o.id=?::uuid)
              and ((not o.manual_review_required and not (%s)
                and (o.status<>'ACKNOWLEDGED' or o.kind in ('PRODUCT_CREATE','PRICE_CREATE')
                or o.kind='PAYMENT_PREPARE' and exists(select 1 from payments p where p.id=o.resource_id
                  and p.payment_account_id=o.payment_account_id and (p.provider_payment_reference is distinct from o.provider_reference or p.status not in ('SUCCEEDED','FAILED','CANCELLED','PARTIALLY_REFUNDED','REFUNDED')))
                or o.kind='REFUND_CREATE' and exists(select 1 from refunds r join payments p on p.id=r.payment_id where r.id=o.resource_id
                  and p.payment_account_id=o.payment_account_id and (r.provider_refund_reference is distinct from o.provider_reference or r.status not in ('SUCCEEDED','FAILED','CANCELLED')))))
                or (o.manual_review_required and o.safe_error_code='PUBLICATION_REQUIRED' and (%s)))
            order by o.next_attempt_at,o.id limit 1 for update of o skip locked
            """.formatted(COMPLETED_CATALOG, COMPLETED_CATALOG), UUID.class, id, id);
        if (ids.isEmpty()) return Optional.empty();
        var chosen = ids.getFirst();
        var now = clock.instant();
        var row = jdbc.queryForMap(
                "select recovery_attempts,next_attempt_at,lease_expires_at,manual_review_required from provider_operations where id=?",
                chosen);
        var due = ((Timestamp) row.get("next_attempt_at")).toInstant();
        if (now.isBefore(due)
                || (row.get("lease_expires_at") instanceof Timestamp expiry
                        && now.isBefore(expiry.toInstant()))) {
            return Optional.empty();
        }
        if (Boolean.TRUE.equals(row.get("manual_review_required"))) {
            jdbc.update(
                    "update provider_operations o set manual_review_required=false,safe_error_code=null where o.id=? and o.manual_review_required and o.safe_error_code='PUBLICATION_REQUIRED' and ("
                            + COMPLETED_CATALOG + ")",
                    chosen);
            return Optional.empty();
        }
        if (((Number) row.get("recovery_attempts")).intValue() >= 12) {
            jdbc.update(
                    "update provider_operations set manual_review_required=true,safe_error_code='RETRY_EXHAUSTED' where id=?",
                    chosen);
            return Optional.empty();
        }
        var expires = now.truncatedTo(ChronoUnit.MICROS).plusSeconds(120);
        int attempt = ((Number) row.get("recovery_attempts")).intValue() + 1;
        jdbc.update(
                "update provider_operations set recovery_attempts=?,next_attempt_at=? where id=?",
                attempt,
                Timestamp.from(expires),
                chosen);
        return Optional.of(new Claim(operations.findById(chosen).orElseThrow(), attempt, expires));
    }

    public void finish(Claim claim, Outcome outcome) {
        var row = jdbc.queryForMap(
                "select recovery_attempts,next_attempt_at from provider_operations where id=? for update",
                claim.operation().id());
        var now = clock.instant();
        if (((Number) row.get("recovery_attempts")).intValue() != claim.attempt()
                || !((Timestamp) row.get("next_attempt_at")).toInstant().equals(claim.expiresAt())
                || !now.isBefore(claim.expiresAt()))
            throw new IllegalStateException("RECOVERY_CLAIM_LOST");
        boolean failed = outcome == Outcome.FAILED;
        boolean manual = outcome == Outcome.MANUAL_REVIEW
                || outcome == Outcome.CATALOG_PUBLICATION_REQUIRED
                || failed && claim.attempt() >= 12;
        int attempts = failed ? claim.attempt() : claim.attempt() - 1;
        String code = switch (outcome) {
            case CATALOG_PUBLICATION_REQUIRED -> "PUBLICATION_REQUIRED";
            case MANUAL_REVIEW -> "MANUAL_PROOF_REQUIRED";
            case FAILED -> manual ? "RETRY_EXHAUSTED" : "PROVIDER_UNAVAILABLE";
            case RETRY_PENDING -> "PROVIDER_PENDING";
            default -> null;
        };
        long delay = failed ? Math.min(300, 5L << Math.min(claim.attempt() - 1, 6)) : 30;
        // Always exceed the old token, even on successful provisional decrement: no ABA.
        jdbc.update(
                "update provider_operations set recovery_attempts=?,next_attempt_at=?,safe_error_code=?,manual_review_required=? where id=?",
                attempts,
                Timestamp.from(claim.expiresAt().plusSeconds(delay)),
                code,
                manual,
                claim.operation().id());
    }

    public void clearResolved(ProviderOperation expected) {
        if (expected.status() != ProviderOperation.Status.ACKNOWLEDGED
                || expected.kind() != ProviderOperation.Kind.PAYMENT_PREPARE
                        && expected.kind() != ProviderOperation.Kind.REFUND_CREATE) return;
        if (jdbc.queryForList(
                                "select id from provider_operations where id=? for update",
                                UUID.class,
                                expected.id())
                        .isEmpty()
                || !operations.findById(expected.id()).filter(expected::equals).isPresent()) return;
        jdbc.update("""
            update provider_operations o set manual_review_required=false,safe_error_code=null
            where o.id=? and exists(select 1 from payment_accounts a where a.id=o.payment_account_id
              and a.provider='STRIPE' and a.account_mode='SANDBOX' and a.external_account_id=o.external_account_id and a.credentials_ref=o.credentials_ref)
            and (o.kind='PAYMENT_PREPARE' and exists(select 1 from payments p where p.id=o.resource_id
              and p.payment_account_id=o.payment_account_id and p.provider_payment_reference=o.provider_reference
              and p.status in ('SUCCEEDED','FAILED','CANCELLED','PARTIALLY_REFUNDED','REFUNDED'))
              or o.kind='REFUND_CREATE' and exists(select 1 from refunds r join payments p on p.id=r.payment_id where r.id=o.resource_id
                and p.payment_account_id=o.payment_account_id and r.provider_refund_reference=o.provider_reference and r.status in ('SUCCEEDED','FAILED','CANCELLED')))
            """, expected.id());
    }
}
