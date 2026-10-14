package dev.genericpayments.platform.customers.adapter.out.persistence;

import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional
public class CustomerSessionPersistenceAdapter implements CustomerSessionStore {
    private final EntityManager entities;
    public CustomerSessionPersistenceAdapter(EntityManager entities){this.entities=entities;}

    @Override @Transactional(readOnly=true)
    public List<CustomerSession> findCandidates(List<KeyedDigestPort.DigestValue> digests) {
        if(digests==null || digests.isEmpty()) return List.of();
        String predicates=java.util.stream.IntStream.range(0,digests.size())
            .mapToObj(index -> "(s.tokenDigest=:digest"+index+" and s.tokenDigestKeyVersion=:version"+index+")")
            .collect(java.util.stream.Collectors.joining(" or "));
        TypedQuery<CustomerSessionJpaEntity> query=entities.createQuery(
            "select s from CustomerSessionJpaEntity s where "+predicates,CustomerSessionJpaEntity.class);
        for(int index=0;index<digests.size();index++) {
            query.setParameter("digest"+index,digests.get(index).value());
            query.setParameter("version"+index,digests.get(index).keyVersion());
        }
        return query.getResultList().stream().map(this::domain).toList();
    }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<CustomerSession> findByIdForUpdate(CustomerSessionId id) {
        return entities.createQuery("select s from CustomerSessionJpaEntity s where s.id=:id",CustomerSessionJpaEntity.class)
            .setParameter("id",id.value()).setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultStream().findFirst().map(entity -> {
                // The candidate query may have managed this entity before a concurrent logout committed.
                entities.refresh(entity);
                return domain(entity);
            });
    }

    @Override public CustomerSession save(CustomerSession value) {
        CustomerSessionJpaEntity entity=entities.find(CustomerSessionJpaEntity.class,value.id().value());
        boolean insert=entity==null;
        if(insert) {
            if(value.version()!=0) throw new OptimisticLockingFailureException("Customer session no longer exists");
            entity=new CustomerSessionJpaEntity(); entity.id=value.id().value(); entity.siteId=value.siteId().value();
            entity.customerId=value.customerId().value(); entity.origin=value.origin(); entity.tokenDigest=value.tokenDigest();
            entity.tokenDigestKeyVersion=value.tokenDigestKeyVersion(); entity.scopes=scopes(value.scopes());
            entity.issuedAt=canonical(value.issuedAt()); entity.expiresAt=canonical(value.expiresAt());
        } else {
            CustomerSession stored=domain(entity);
            if(value.version()!=stored.version()) throw new OptimisticLockingFailureException("Stale Customer session version");
            if(!value.siteId().equals(stored.siteId()) || !value.customerId().equals(stored.customerId())
                || !value.origin().equals(stored.origin()) || !value.tokenDigest().equals(stored.tokenDigest())
                || !value.tokenDigestKeyVersion().equals(stored.tokenDigestKeyVersion()) || !value.scopes().equals(stored.scopes())
                || !canonical(value.issuedAt()).equals(stored.issuedAt()) || !canonical(value.expiresAt()).equals(stored.expiresAt()))
                throw new IllegalArgumentException("Customer session binding is immutable");
        }
        entity.lastUsedAt=canonical(value.lastUsedAt());
        entity.revokedAt=value.revokedAt().map(CustomerSessionPersistenceAdapter::canonical).orElse(null);
        entity.revokedReason=value.revokedReason().map(Enum::name).orElse(null);
        if(insert) entities.persist(entity);
        entities.flush();
        return domain(entity);
    }

    private CustomerSession domain(CustomerSessionJpaEntity entity) {
        return CustomerSession.reconstitute(new CustomerSessionId(entity.id),new SiteId(entity.siteId),new CustomerId(entity.customerId),
            entity.origin,entity.tokenDigest,entity.tokenDigestKeyVersion,parseScopes(entity.scopes),canonical(entity.issuedAt),
            canonical(entity.lastUsedAt),canonical(entity.expiresAt),canonical(entity.revokedAt),
            entity.revokedReason==null?null:CustomerSession.RevocationReason.valueOf(entity.revokedReason),entity.version);
    }
    private static String scopes(Set<CustomerSessionScope> scopes) {
        return scopes.stream().map(Enum::name).sorted().map(value -> "\""+value+"\"")
            .collect(java.util.stream.Collectors.joining(",","[","]"));
    }
    private static Set<CustomerSessionScope> parseScopes(String json) {
        EnumSet<CustomerSessionScope> result=EnumSet.noneOf(CustomerSessionScope.class);
        for(CustomerSessionScope scope:CustomerSessionScope.values()) if(json.contains("\""+scope.name()+"\"")) result.add(scope);
        return result;
    }
    private static Instant canonical(Instant value){return value==null?null:value.truncatedTo(ChronoUnit.MICROS);}
}
