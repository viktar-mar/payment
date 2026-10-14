package dev.genericpayments.platform.payments.adapter.out.persistence;

import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Repository
@Transactional
public class PaymentAttemptPersistenceAdapter implements LoadPaymentAttemptPort, SavePaymentAttemptPort {
    private final EntityManager entities; private final JdbcTemplate jdbc; private final PaymentPersistenceMapper mapper;
    PaymentAttemptPersistenceAdapter(EntityManager entities,JdbcTemplate jdbc,PaymentPersistenceMapper mapper) {
        this.entities=entities; this.jdbc=jdbc; this.mapper=mapper;
    }
    @Override public StoredAttempt insert(PaymentAttempt a,Instant now) {
        Objects.requireNonNull(now);
        var leases=jdbc.query("select reconciliation_lease_expires_at from payments where id=? FOR UPDATE",
            (rs,n)-> { var time=rs.getTimestamp("reconciliation_lease_expires_at"); return time==null?null:time.toInstant(); },a.paymentId().value());
        if(leases.isEmpty()) throw new IllegalArgumentException("payment does not exist");
        if(leases.getFirst()!=null && leases.getFirst().isAfter(now)) throw new IllegalStateException("payment has a live reconciliation lease");
        PaymentAttemptJpaEntity e=new PaymentAttemptJpaEntity(); mapper.copy(a,e); entities.persist(e); entities.flush();
        return stored(e);
    }
    @Override public StoredAttempt save(StoredAttempt a) {
        PaymentAttemptJpaEntity e=entities.find(PaymentAttemptJpaEntity.class,a.value().id().value());
        if(e==null || e.version!=a.version()) throw new OptimisticLockingFailureException("stale attempt version");
        mapper.copy(a.value(),e); entities.flush(); return stored(e);
    }
    @Override @Transactional(readOnly=true) public Optional<StoredAttempt> findById(SiteId siteId,PaymentAttemptId id) {
        return entities.createQuery("select a from PaymentAttemptJpaEntity a, PaymentJpaEntity p where a.paymentId=p.id and p.siteId=:site and a.id=:id",PaymentAttemptJpaEntity.class)
            .setParameter("site",siteId.value()).setParameter("id",id.value()).getResultStream().findFirst().map(this::stored);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<StoredAttempt> findByIdForUpdate(SiteId siteId,PaymentAttemptId id) {
        var locked=jdbc.query("select a.id from payment_attempts a join payments p on p.id=a.payment_id where a.id=? and p.site_id=? FOR UPDATE OF a",
            (rs,n)->rs.getObject(1,UUID.class),id.value(),siteId.value());
        if(locked.isEmpty())return Optional.empty();
        var entity=entities.find(PaymentAttemptJpaEntity.class,id.value());
        entities.refresh(entity);
        return Optional.of(stored(entity));
    }
    @Override @Transactional(readOnly=true) public List<StoredAttempt> findByPayment(SiteId siteId,PaymentId id) {
        return entities.createQuery("select a from PaymentAttemptJpaEntity a, PaymentJpaEntity p where a.paymentId=p.id and p.siteId=:site and p.id=:id order by a.attemptNumber",PaymentAttemptJpaEntity.class)
            .setParameter("site",siteId.value()).setParameter("id",id.value()).getResultList().stream().map(this::stored).toList();
    }
    private StoredAttempt stored(PaymentAttemptJpaEntity e) { return new StoredAttempt(mapper.attempt(e),e.version); }
}
