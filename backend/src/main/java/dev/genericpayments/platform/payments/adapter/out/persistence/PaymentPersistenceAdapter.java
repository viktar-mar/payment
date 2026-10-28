package dev.genericpayments.platform.payments.adapter.out.persistence;

import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional
public class PaymentPersistenceAdapter implements LoadPaymentPort, SavePaymentPort, SubscriptionCashIdentityPort, LoadSubscriptionCashOwnershipPort {
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final PaymentPersistenceMapper mapper;
    PaymentPersistenceAdapter(EntityManager entities, JdbcTemplate jdbc, PaymentPersistenceMapper mapper) {
        this.entities=entities; this.jdbc=jdbc; this.mapper=mapper;
    }
    @Override public Payment save(Payment p) {
        return mapper.payment(saveEntity(p, null, false));
    }
    @Override @Transactional(readOnly=true)
    public Optional<Payment> findByAccountAndId(dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId accountId,PaymentId id) {
        return entities.createQuery("select p from PaymentJpaEntity p where p.id=:id and p.paymentAccountId=:account",PaymentJpaEntity.class)
            .setParameter("id",id.value()).setParameter("account",accountId.value()).getResultStream().findFirst().map(mapper::payment);
    }
    @Override @Transactional(readOnly=true)
    public Optional<LoadSubscriptionCashOwnershipPort.Ownership> findCashOwnership(dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId accountId,PaymentId paymentId,String reference) {
        return jdbc.query("select subscription_id,subscription_invoice_reference,amount_minor,currency from payments where id=? and payment_account_id=? and provider_payment_reference=? and source='SUBSCRIPTION'",
            (rs,n)->new LoadSubscriptionCashOwnershipPort.Ownership(rs.getObject("subscription_id",UUID.class),rs.getString("subscription_invoice_reference"),dev.genericpayments.platform.catalog.domain.Money.of(rs.getLong("amount_minor"),rs.getString("currency"))),
            paymentId.value(),accountId.value(),reference).stream().findFirst();
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<Payment> lock(dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId accountId,String reference) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))","subscription-cash:"+accountId.value()+":"+reference);
        var rows=jdbc.query("select id,site_id,source from payments where payment_account_id=? and provider_payment_reference=? for update",
            (rs,n)->new Object[]{rs.getObject("id",UUID.class),rs.getObject("site_id",UUID.class),rs.getString("source")},accountId.value(),reference);
        if(rows.isEmpty())return Optional.empty();
        if(rows.size()!=1||!"SUBSCRIPTION".equals(rows.getFirst()[2]))throw new IllegalArgumentException("Cash object identity conflict");
        return findByIdForUpdate(new SiteId((UUID)rows.getFirst()[1]),new PaymentId((UUID)rows.getFirst()[0]));
    }
    @Override public StoredPayment save(StoredPayment state) {
        PaymentJpaEntity entity=saveEntity(state.value(),state.returnUrl(),true);
        return new StoredPayment(mapper.payment(entity),entity.returnUrl);
    }
    private PaymentJpaEntity saveEntity(Payment p,String returnUrl,boolean replaceRouting) {
        PaymentJpaEntity e=entities.find(PaymentJpaEntity.class,p.id().value());
        if (e==null) {
            if(p.version()!=0) throw new OptimisticLockingFailureException("payment no longer exists");
            e=new PaymentJpaEntity(); mapper.copy(p,e);
            if(replaceRouting) e.returnUrl=returnUrl;
            entities.persist(e);
        } else {
            if(e.version!=p.version()) throw new OptimisticLockingFailureException("stale payment version");
            mapper.copy(p,e);
            if(replaceRouting) e.returnUrl=returnUrl;
        }
        entities.flush();
        return e;
    }
    @Override @Transactional(readOnly=true) public Optional<StoredPayment> findStateById(SiteId siteId,PaymentId id) {
        return entities.createQuery("select p from PaymentJpaEntity p where p.id=:id and p.siteId=:site",PaymentJpaEntity.class)
            .setParameter("id",id.value()).setParameter("site",siteId.value()).getResultStream().findFirst()
            .map(e -> new StoredPayment(mapper.payment(e),e.returnUrl));
    }
    @Override @Transactional(readOnly=true) public Optional<Payment> findById(SiteId siteId,PaymentId id) {
        return entities.createQuery("select p from PaymentJpaEntity p where p.id=:id and p.siteId=:site",PaymentJpaEntity.class)
            .setParameter("id",id.value()).setParameter("site",siteId.value()).getResultStream().findFirst().map(mapper::payment);
    }
    @Override @Transactional(readOnly=true) public List<Payment> findBySiteId(SiteId siteId) {
        return entities.createQuery("select p from PaymentJpaEntity p where p.siteId=:site order by p.createdAt,p.id",PaymentJpaEntity.class)
            .setParameter("site",siteId.value()).getResultList().stream().map(mapper::payment).toList();
    }
    @Override @Transactional(readOnly=true)
    public List<Payment> findCustomerHistory(SiteId siteId,UUID customerId,Instant beforeCreatedAt,
                                             PaymentId beforePaymentId,int maxResults) {
        if(siteId==null||customerId==null||(beforeCreatedAt==null)!=(beforePaymentId==null)
                ||maxResults<1||maxResults>101) throw new IllegalArgumentException("Invalid Customer payment history query");
        jakarta.persistence.TypedQuery<PaymentJpaEntity> query;
        if(beforeCreatedAt==null) {
            query=entities.createQuery("""
                select p from PaymentJpaEntity p
                where p.siteId=:site and p.customerId=:customer
                order by p.createdAt desc,p.id desc
                """,PaymentJpaEntity.class);
        } else {
            query=entities.createQuery("""
                select p from PaymentJpaEntity p
                where p.siteId=:site and p.customerId=:customer
                  and (p.createdAt<:createdAt or (p.createdAt=:createdAt and p.id<:id))
                order by p.createdAt desc,p.id desc
                """,PaymentJpaEntity.class)
                .setParameter("createdAt",beforeCreatedAt).setParameter("id",beforePaymentId.value());
        }
        return query.setParameter("site",siteId.value()).setParameter("customer",customerId)
            .setMaxResults(maxResults).getResultList().stream().map(mapper::payment).toList();
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<LockedPayment> findForAttemptStart(SiteId siteId,PaymentId id) {
        var rows=jdbc.query("select reconciliation_lease_expires_at from payments where id=? and site_id=? FOR UPDATE",
            (rs,n)->Optional.ofNullable(rs.getTimestamp(1)).map(Timestamp::toInstant),id.value(),siteId.value());
        if(rows.isEmpty()) return Optional.empty();
        var e=entities.find(PaymentJpaEntity.class,id.value());entities.refresh(e);
        return Optional.of(new LockedPayment(mapper.payment(e),rows.getFirst().orElse(null)));
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<Payment> findByIdForUpdate(SiteId siteId,PaymentId id) {
        List<UUID> locked=jdbc.query("select id from payments where id=? and site_id=? FOR UPDATE",
            (rs,n)->rs.getObject("id",UUID.class),id.value(),siteId.value());
        if(locked.isEmpty()) return Optional.empty();
        PaymentJpaEntity e=entities.find(PaymentJpaEntity.class,id.value());
        entities.refresh(e);
        return Optional.of(mapper.payment(e));
    }
    @Override public boolean tryAcquireReconciliationLease(SiteId siteId,PaymentId id,String owner,Instant now,Instant expiresAt) {
        if(owner==null || owner.isBlank() || owner.length()>128 || now==null || expiresAt==null || !expiresAt.isAfter(now))
            throw new IllegalArgumentException("lease owner and future expiry are required");
        entities.flush();
        List<UUID> claims=jdbc.query("""
            select id from payments where id=? and site_id=?
              and (reconciliation_lease_expires_at is null or reconciliation_lease_expires_at<=?)
            FOR UPDATE SKIP LOCKED
            """,(rs,n)->rs.getObject("id",UUID.class),id.value(),siteId.value(),Timestamp.from(now));
        if(claims.isEmpty()) return false;
        jdbc.update("update payments set reconciliation_lease_owner=?,reconciliation_lease_expires_at=?,version=version+1 where id=?",
            owner,Timestamp.from(expiresAt),id.value());
        refreshIfManaged(id);
        return true;
    }
    @Override public boolean releaseReconciliationLease(SiteId siteId,PaymentId id,String owner) {
        entities.flush();
        int changed=jdbc.update("""
            update payments set reconciliation_lease_owner=null,reconciliation_lease_expires_at=null,version=version+1
            where id=? and site_id=? and reconciliation_lease_owner=?
            """,id.value(),siteId.value(),owner);
        if(changed>0) refreshIfManaged(id);
        return changed>0;
    }
    private void refreshIfManaged(PaymentId id) {
        PaymentJpaEntity entity=entities.find(PaymentJpaEntity.class,id.value());
        if(entity!=null) entities.refresh(entity);
    }
}
