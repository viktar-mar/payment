package dev.genericpayments.platform.payments.adapter.out.persistence;

import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional
public class RefundPersistenceAdapter implements LoadRefundPort, SaveRefundPort, LoadRefundOwnershipPort {
    private final EntityManager entities; private final JdbcTemplate jdbc; private final PaymentPersistenceMapper mapper;
    RefundPersistenceAdapter(EntityManager entities,JdbcTemplate jdbc,PaymentPersistenceMapper mapper) {
        this.entities=entities; this.jdbc=jdbc; this.mapper=mapper;
    }
    @Override public StoredRefund insert(Refund r) {
        RefundJpaEntity e=new RefundJpaEntity(); mapper.copy(r,e); entities.persist(e); entities.flush(); return stored(e);
    }
    @Override public StoredRefund save(StoredRefund r) {
        RefundJpaEntity e=entities.find(RefundJpaEntity.class,r.value().id().value());
        if(e==null || e.version!=r.version()) throw new OptimisticLockingFailureException("stale refund version");
        mapper.copy(r.value(),e); entities.flush(); return stored(e);
    }
    @Override @Transactional(readOnly=true) public Optional<StoredRefund> findById(SiteId siteId,RefundId id) {
        return entities.createQuery("select r from RefundJpaEntity r, PaymentJpaEntity p where r.paymentId=p.id and p.siteId=:site and r.id=:id",RefundJpaEntity.class)
            .setParameter("site",siteId.value()).setParameter("id",id.value()).getResultStream().findFirst().map(this::stored);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY) public Optional<StoredRefund> findByIdForUpdate(SiteId siteId,RefundId id){
        var ids=jdbc.query("select r.id from refunds r join payments p on p.id=r.payment_id where p.site_id=? and r.id=? for update of r",(r,n)->r.getObject("id",UUID.class),siteId.value(),id.value());
        return ids.stream().findFirst().map(key->{var e=entities.find(RefundJpaEntity.class,key);entities.refresh(e);return stored(e);});
    }
    @Override @Transactional(readOnly=true) public Optional<RefundOwnership> findOwnership(RefundId id) {
        Objects.requireNonNull(id);
        return jdbc.query("select p.site_id,p.id,p.payment_account_id from refunds r join payments p on p.id=r.payment_id where r.id=?",
            (rs,n)->new RefundOwnership(new SiteId(rs.getObject("site_id",UUID.class)),
                new PaymentId(rs.getObject("id",UUID.class)),
                new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(rs.getObject("payment_account_id",UUID.class))),
            id.value()).stream().findFirst();
    }
    @Override @Transactional(readOnly=true) public List<StoredRefund> findByPayment(SiteId siteId,PaymentId id) {
        return entities.createQuery("select r from RefundJpaEntity r, PaymentJpaEntity p where r.paymentId=p.id and p.siteId=:site and p.id=:id order by r.createdAt,r.id",RefundJpaEntity.class)
            .setParameter("site",siteId.value()).setParameter("id",id.value()).getResultList().stream().map(this::stored).toList();
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public List<StoredRefund> findReservedByPaymentForUpdate(SiteId siteId,PaymentId id) {
        List<UUID> ids=jdbc.query("""
            select r.id from refunds r join payments p on p.id=r.payment_id
            where p.id=? and p.site_id=? and r.status in ('PENDING','PROCESSING','UNKNOWN','SUCCEEDED')
            order by r.created_at,r.id FOR UPDATE
            """,(rs,n)->rs.getObject("id",UUID.class),id.value(),siteId.value());
        return ids.stream().map(key -> {
            RefundJpaEntity e=entities.find(RefundJpaEntity.class,key); entities.refresh(e); return stored(e);
        }).toList();
    }
    private StoredRefund stored(RefundJpaEntity e) { return new StoredRefund(mapper.refund(e),e.version); }
}
