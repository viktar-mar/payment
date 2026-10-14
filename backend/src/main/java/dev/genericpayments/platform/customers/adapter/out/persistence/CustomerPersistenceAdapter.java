package dev.genericpayments.platform.customers.adapter.out.persistence;

import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue;
import dev.genericpayments.platform.sites.domain.SiteId;
import jakarta.persistence.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import static dev.genericpayments.platform.customers.adapter.out.persistence.CustomerPersistenceMapper.Digest;
import static dev.genericpayments.platform.customers.adapter.out.persistence.PostgresTimestamps.canonical;

@Repository
@Transactional
public class CustomerPersistenceAdapter implements CustomerStore, CustomerIdentityStore, IdentityProviderConfigurationStore {
    private final EntityManager entities;
    private final CustomerPersistenceMapper mapper;
    private final Clock clock;
    CustomerPersistenceAdapter(EntityManager entities,CustomerPersistenceMapper mapper,Clock clock) {
        this.entities=entities; this.mapper=mapper; this.clock=clock;
    }
    private TypedQuery<CustomerJpaEntity> customerQuery(SiteId site,CustomerId id) {
        return entities.createQuery("select c from CustomerJpaEntity c where c.siteId=:site and c.id=:id",CustomerJpaEntity.class)
            .setParameter("site",site.value()).setParameter("id",id.value());
    }
    @Override @Transactional(readOnly=true)
    public Optional<Customer> findById(SiteId site,CustomerId id) {
        return customerQuery(site,id).getResultStream().findFirst().map(mapper::customer);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<Customer> findByIdForUpdate(SiteId site,CustomerId id) {
        return customerQuery(site,id).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst().map(mapper::customer);
    }
    @Override @Transactional(readOnly=true)
    public List<Customer> list(SiteId site,int limit,int offset) {
        if(limit<1 || limit>50 || offset<0) throw new IllegalArgumentException("Invalid customer page");
        return entities.createQuery("select c from CustomerJpaEntity c where c.siteId=:site order by c.createdAt desc,c.id desc",CustomerJpaEntity.class)
            .setParameter("site",site.value()).setMaxResults(limit).setFirstResult(offset).getResultList().stream().map(mapper::customer).toList();
    }
    @Override @Transactional(readOnly=true)
    public List<Customer> findByEmailDigest(SiteId site,String reference) {
        Digest digest=Digest.parse(reference);
        return entities.createQuery("select c from CustomerJpaEntity c where c.siteId=:site and c.emailDigest=:digest and c.emailDigestKeyVersion=:version order by c.createdAt desc,c.id desc",CustomerJpaEntity.class)
            .setParameter("site",site.value()).setParameter("digest",digest.value()).setParameter("version",digest.keyVersion())
            .getResultList().stream().map(mapper::customer).toList();
    }
    @Override @Transactional(readOnly=true)
    public List<Customer> query(SiteId site,CustomerStatus status,CustomerId id,Boolean hasEmail,int limit,int offset) {
        if(limit<1||limit>50||offset<0)throw new IllegalArgumentException("Invalid customer page");
        String jpql="select c from CustomerJpaEntity c where c.siteId=:site"
            +(status==null?"":" and c.status=:status")+(id==null?"":" and c.id=:id")
            +(hasEmail==null?"":hasEmail?" and c.emailCiphertext is not null":" and c.emailCiphertext is null")
            +" order by c.createdAt desc,c.id desc";
        var query=entities.createQuery(jpql,CustomerJpaEntity.class).setParameter("site",site.value());
        if(status!=null)query.setParameter("status",status.name());
        if(id!=null)query.setParameter("id",id.value());
        return query.setMaxResults(limit).setFirstResult(offset).getResultList().stream().map(mapper::customer).toList();
    }
    @Override @Transactional(readOnly=true)
    public Optional<EncryptedValue> findEncryptedEmail(SiteId site,CustomerId id) {
        return customerQuery(site,id).getResultStream().findFirst().filter(e->e.emailCiphertext!=null)
            .map(e->new EncryptedValue(e.emailCiphertext,e.emailNonce,e.emailKeyVersion));
    }
    @Override public Customer save(Customer customer) { return saveCustomer(customer,null); }
    @Override public Customer save(Customer customer,EncryptedValue email) {
        Objects.requireNonNull(email,"encrypted email");
        return saveCustomer(customer,email);
    }
    private Customer saveCustomer(Customer customer,EncryptedValue email) {
        var createdAt=canonical(customer.createdAt());
        var updatedAt=canonical(customer.updatedAt());
        var anonymizedAt=canonical(customer.anonymizedAt().orElse(null));
        CustomerJpaEntity e=customerQuery(customer.siteId(),customer.id()).getResultStream().findFirst().orElse(null);
        boolean insert=e==null;
        if(insert) {
            if(customer.version()!=0) throw new OptimisticLockingFailureException("Customer no longer exists");
            e=new CustomerJpaEntity(); e.id=customer.id().value(); e.siteId=customer.siteId().value(); e.createdAt=createdAt;
        } else {
            if(customer.version()!=e.version) throw new OptimisticLockingFailureException("Stale Customer version");
            if(!createdAt.equals(canonical(e.createdAt)) || updatedAt.isBefore(canonical(e.updatedAt))
                || ("ANONYMIZED".equals(e.status) && customer.status()!=CustomerStatus.ANONYMIZED)
                || (e.anonymizedAt!=null && !Objects.equals(anonymizedAt,canonical(e.anonymizedAt))))
                throw new IllegalArgumentException("Customer lifecycle is immutable");
        }
        if(customer.encryptedEmailRef().isEmpty()) {
            if(email!=null) throw new IllegalArgumentException("Email material requires a profile reference");
            e.emailCiphertext=null; e.emailNonce=null; e.emailKeyVersion=null; e.emailDigest=null; e.emailDigestKeyVersion=null;
        } else {
            if(!customer.encryptedEmailRef().orElseThrow().equals(CustomerStore.emailReference(customer.id())))
                throw new IllegalArgumentException("Unknown encrypted email reference");
            Digest digest=Digest.parse(customer.emailDigest().orElseThrow());
            if(email==null && (e.emailCiphertext==null || !digest.value().equals(e.emailDigest) || !digest.keyVersion().equals(e.emailDigestKeyVersion)))
                throw new IllegalArgumentException("Changed profile email requires encrypted material");
            if(email!=null) { e.emailCiphertext=email.ciphertext(); e.emailNonce=email.nonce(); e.emailKeyVersion=email.keyVersion(); }
            e.emailDigest=digest.value(); e.emailDigestKeyVersion=digest.keyVersion();
        }
        e.status=customer.status().name(); e.updatedAt=updatedAt; e.anonymizedAt=anonymizedAt;
        if(insert) entities.persist(e);
        entities.flush(); return mapper.customer(e);
    }
    private TypedQuery<CustomerIdentityJpaEntity> identityQuery(SiteId site,String issuer,String reference) {
        Digest digest=Digest.parse(reference);
        return entities.createQuery("select i from CustomerIdentityJpaEntity i where i.siteId=:site and i.issuerKey=:issuer and i.subjectDigest=:digest and i.subjectDigestKeyVersion=:version",CustomerIdentityJpaEntity.class)
            .setParameter("site",site.value()).setParameter("issuer",issuer).setParameter("digest",digest.value()).setParameter("version",digest.keyVersion());
    }
    private TypedQuery<CustomerIdentityJpaEntity> identityIdQuery(SiteId site,UUID id) {
        return entities.createQuery("select i from CustomerIdentityJpaEntity i where i.siteId=:site and i.id=:id",CustomerIdentityJpaEntity.class)
            .setParameter("site",site.value()).setParameter("id",id);
    }
    @Override @Transactional(readOnly=true)
    public Optional<CustomerIdentity> find(SiteId site,String issuer,String digest) {
        return identityQuery(site,issuer,digest).getResultStream().findFirst().map(mapper::identity);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<CustomerIdentity> findForUpdate(SiteId site,String issuer,String digest) {
        return identityQuery(site,issuer,digest).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst().map(mapper::identity);
    }
    private record ReservedAlias(Digest digest,long lockKey) {}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public List<SubjectAlias> reserveSubjectAliases(SiteId site,String issuer,List<String> references) {
        var aliases=orderedAliases(site,issuer,references);
        aliases.stream().mapToLong(ReservedAlias::lockKey).distinct().forEach(lockKey -> entities
            .createNativeQuery("select pg_advisory_xact_lock(?)").setParameter(1,lockKey).getSingleResult());
        return findSubjectAliasesForUpdate(site,issuer,aliases);
    }
    private List<ReservedAlias> orderedAliases(SiteId site,String issuer,List<String> references) {
        Objects.requireNonNull(site,"site"); Objects.requireNonNull(issuer,"issuer"); Objects.requireNonNull(references,"subject aliases");
        return references.stream().map(Digest::parse).distinct()
            .map(digest -> new ReservedAlias(digest,aliasLockKey(site,issuer,digest)))
            .sorted(Comparator.comparingLong(ReservedAlias::lockKey).thenComparing(alias -> alias.digest().reference()))
            .toList();
    }
    private static long aliasLockKey(SiteId site,String issuer,Digest digest) {
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            updateHash(hash,"customer-identity-alias-v1");
            hash.update(ByteBuffer.allocate(16).putLong(site.value().getMostSignificantBits()).putLong(site.value().getLeastSignificantBits()).array());
            updateHash(hash,issuer); updateHash(hash,digest.keyVersion()); updateHash(hash,digest.value());
            return ByteBuffer.wrap(hash.digest()).getLong();
        } catch(NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required digest algorithm is unavailable",impossible);
        }
    }
    private static void updateHash(MessageDigest hash,String value) {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); hash.update(bytes);
    }
    private List<SubjectAlias> findSubjectAliasesForUpdate(SiteId site,String issuer,List<ReservedAlias> aliases) {
        if(aliases.isEmpty()) return List.of();
        var digests=aliases.stream().map(ReservedAlias::digest).toList();
        String predicates=java.util.stream.IntStream.range(0,digests.size())
            .mapToObj(index -> "(subject_digest=:digest"+index+" and subject_digest_key_version=:version"+index+")")
            .collect(java.util.stream.Collectors.joining(" or "));
        Query query=entities.createNativeQuery("select identity_id,subject_digest,subject_digest_key_version from customer_identity_subject_aliases "
            +"where site_id=:site and issuer_key=:issuer and ("+predicates+") order by subject_digest_key_version,subject_digest for update")
            .setParameter("site",site.value()).setParameter("issuer",issuer);
        for(int index=0;index<digests.size();index++) {
            query.setParameter("digest"+index,digests.get(index).value());
            query.setParameter("version"+index,digests.get(index).keyVersion());
        }
        @SuppressWarnings("unchecked") List<Object[]> rows=query.getResultList();
        Map<UUID,CustomerIdentity> owners=new HashMap<>();
        rows.stream().map(row -> (UUID)row[0]).distinct().sorted().forEach(identityId -> owners.put(identityId,identityIdQuery(site,identityId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst().map(mapper::identity)
            .orElseThrow(() -> new IllegalStateException("Identity alias owner is missing"))));
        List<SubjectAlias> result=new ArrayList<>();
        for(Object[] row:rows) {
            UUID identityId=(UUID)row[0];
            result.add(new SubjectAlias(row[2]+":"+row[1],owners.get(identityId)));
        }
        return List.copyOf(result);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void saveSubjectAliases(CustomerIdentity identity,List<String> references) {
        var aliases=orderedAliases(identity.siteId(),identity.issuerKey(),references);
        if(aliases.isEmpty()) return;
        aliases.stream().mapToLong(ReservedAlias::lockKey).distinct().forEach(lockKey -> entities
            .createNativeQuery("select pg_advisory_xact_lock(?)").setParameter(1,lockKey).getSingleResult());
        assertNoOtherOwner(identity,findSubjectAliasesForUpdate(identity.siteId(),identity.issuerKey(),aliases));
        insertAliasesAndVerify(identity,aliases);
    }
    private void insertSubjectAlias(CustomerIdentity identity,Digest digest) {
        entities.createNativeQuery("insert into customer_identity_subject_aliases(site_id,issuer_key,subject_digest,subject_digest_key_version,identity_id) values (?,?,?,?,?) on conflict do nothing")
            .setParameter(1,identity.siteId().value()).setParameter(2,identity.issuerKey()).setParameter(3,digest.value())
            .setParameter(4,digest.keyVersion()).setParameter(5,identity.id().value()).executeUpdate();
    }
    private void insertAliasesAndVerify(CustomerIdentity identity,List<ReservedAlias> aliases) {
        aliases.forEach(alias -> insertSubjectAlias(identity,alias.digest()));
        entities.flush();
        var stored=findSubjectAliasesForUpdate(identity.siteId(),identity.issuerKey(),aliases);
        if(stored.size()!=aliases.size()) throw new SubjectAliasOwnershipConflictException();
        assertNoOtherOwner(identity,stored);
    }
    private static void assertNoOtherOwner(CustomerIdentity identity,List<SubjectAlias> aliases) {
        if(aliases.stream().anyMatch(alias -> !alias.identity().id().equals(identity.id())))
            throw new SubjectAliasOwnershipConflictException();
    }
    @Override @Transactional(readOnly=true)
    public List<CustomerIdentity> list(SiteId site,CustomerId customer) {
        return entities.createQuery("select i from CustomerIdentityJpaEntity i where i.siteId=:site and i.customerId=:customer order by i.createdAt,i.id",CustomerIdentityJpaEntity.class)
            .setParameter("site",site.value()).setParameter("customer",customer.value()).getResultList().stream().map(mapper::identity).toList();
    }
    @Override public CustomerIdentity create(CustomerIdentity identity,List<String> references) {
        var aliases=orderedAliases(identity.siteId(),identity.issuerKey(),references);
        if(aliases.stream().noneMatch(alias -> alias.digest().reference().equals(identity.subjectDigest())))
            throw new IllegalArgumentException("Semantic identity digest must be included in its alias set");
        aliases.stream().mapToLong(ReservedAlias::lockKey).distinct().forEach(lockKey -> entities
            .createNativeQuery("select pg_advisory_xact_lock(?)").setParameter(1,lockKey).getSingleResult());
        if(!findSubjectAliasesForUpdate(identity.siteId(),identity.issuerKey(),aliases).isEmpty())
            throw new SubjectAliasOwnershipConflictException();
        CustomerIdentity saved=saveIdentity(identity,true);
        insertAliasesAndVerify(saved,aliases);
        return saved;
    }
    @Override public CustomerIdentity update(CustomerIdentity identity) { return saveIdentity(identity,false); }
    private CustomerIdentity saveIdentity(CustomerIdentity identity,boolean create) {
        var createdAt=canonical(identity.createdAt());
        var verifiedAt=canonical(identity.verifiedAt());
        var lastUsedAt=canonical(identity.lastUsedAt());
        var existing=entities.createQuery("select i from CustomerIdentityJpaEntity i where i.siteId=:site and i.id=:id",CustomerIdentityJpaEntity.class)
            .setParameter("site",identity.siteId().value()).setParameter("id",identity.id().value()).getResultStream().findFirst();
        CustomerIdentityJpaEntity e;
        if(existing.isPresent()) {
            if(create) throw new IllegalArgumentException("Identity already exists");
            e=existing.orElseThrow();
            var stored=mapper.identity(e);
            identity.assertOwnedBy(stored.siteId(),stored.customerId());
            if(identity.mode()!=stored.mode() || !identity.issuerKey().equals(stored.issuerKey()) || !identity.subjectDigest().equals(stored.subjectDigest())
                || !createdAt.equals(stored.createdAt()) || !verifiedAt.equals(stored.verifiedAt()) || lastUsedAt.isBefore(stored.lastUsedAt()))
                throw new IllegalArgumentException("Identity facts are immutable");
        } else {
            if(!create) throw new IllegalArgumentException("Identity does not exist");
            e=new CustomerIdentityJpaEntity(); e.id=identity.id().value(); e.siteId=identity.siteId().value(); e.customerId=identity.customerId().value();
            e.mode=identity.mode().name(); e.issuerKey=identity.issuerKey();
            Digest digest=Digest.parse(identity.subjectDigest()); e.subjectDigest=digest.value(); e.subjectDigestKeyVersion=digest.keyVersion();
            e.verifiedAt=verifiedAt; e.createdAt=createdAt;
            e.lastUsedAt=lastUsedAt;
            entities.persist(e);
        }
        e.lastUsedAt=lastUsedAt; entities.flush();
        return mapper.identity(e);
    }
    @Override public void deleteByCustomer(SiteId site,CustomerId customer) {
        // Linking locks aliases before their owner identities; cascade deletion must use the same order.
        entities.createNativeQuery("select a.identity_id from customer_identity_subject_aliases a "
            +"join customer_identities i on i.site_id=a.site_id and i.id=a.identity_id "
            +"where i.site_id=:site and i.customer_id=:customer "
            +"order by a.issuer_key,a.subject_digest_key_version,a.subject_digest for update of a")
            .setParameter("site",site.value()).setParameter("customer",customer.value()).getResultList();
        entities.createQuery("select i from CustomerIdentityJpaEntity i where i.siteId=:site and i.customerId=:customer order by i.id",CustomerIdentityJpaEntity.class)
            .setParameter("site",site.value()).setParameter("customer",customer.value()).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        entities.createQuery("delete from CustomerIdentityJpaEntity i where i.siteId=:site and i.customerId=:customer")
            .setParameter("site",site.value()).setParameter("customer",customer.value()).executeUpdate();
    }
    private TypedQuery<IdentityProviderConfigurationJpaEntity> providerQuery(SiteId site,String provider) {
        return entities.createQuery("select p from IdentityProviderConfigurationJpaEntity p where p.siteId=:site and p.providerKey=:provider",IdentityProviderConfigurationJpaEntity.class)
            .setParameter("site",site.value()).setParameter("provider",provider);
    }
    private TypedQuery<IdentityProviderConfigurationJpaEntity> providerIdQuery(SiteId site,UUID provider) {
        return entities.createQuery("select p from IdentityProviderConfigurationJpaEntity p where p.siteId=:site and p.id=:provider",IdentityProviderConfigurationJpaEntity.class)
            .setParameter("site",site.value()).setParameter("provider",provider);
    }
    @Override @Transactional(readOnly=true)
    public Optional<IdentityProviderConfiguration> find(SiteId site,String provider) {
        return providerQuery(site,provider).getResultStream().findFirst().map(mapper::provider);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Optional<IdentityProviderConfiguration> findForUpdate(SiteId site,String provider) {
        return providerQuery(site,provider).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst().map(mapper::provider);
    }
    @Override @Transactional(readOnly=true)
    public List<IdentityProviderConfiguration> list(SiteId site) {
        return entities.createQuery("select p from IdentityProviderConfigurationJpaEntity p where p.siteId=:site order by p.providerKey",IdentityProviderConfigurationJpaEntity.class)
            .setParameter("site",site.value()).getResultList().stream().map(mapper::provider).toList();
    }
    @Override @Transactional(readOnly=true)
    public Optional<StoredConfiguration> findById(SiteId site,UUID provider) {
        return providerIdQuery(site,provider).getResultStream().findFirst().map(e->new StoredConfiguration(e.id,mapper.provider(e)));
    }
    @Override @Transactional(readOnly=true)
    public List<StoredConfiguration> listStored(SiteId site) {
        return entities.createQuery("select p from IdentityProviderConfigurationJpaEntity p where p.siteId=:site order by p.providerKey",IdentityProviderConfigurationJpaEntity.class)
            .setParameter("site",site.value()).getResultList().stream().map(e->new StoredConfiguration(e.id,mapper.provider(e))).toList();
    }
    @Override @Transactional(readOnly=true)
    public Optional<PublicJwkSet> findPublicJwkSet(SiteId site,String provider) {
        return providerQuery(site,provider).getResultStream().findFirst().filter(e->e.publicJwkSet!=null)
            .map(e->new PublicJwkSet(e.publicJwkSet));
    }
    @Override public IdentityProviderConfiguration save(IdentityProviderConfiguration value) { return saveProvider(value,null).configuration(); }
    @Override public IdentityProviderConfiguration save(IdentityProviderConfiguration value,PublicJwkSet publicKeys) {
        Objects.requireNonNull(publicKeys,"public JWK set");
        return saveProvider(value,publicKeys).configuration();
    }
    @Override public StoredConfiguration saveStored(IdentityProviderConfiguration value) { return saveProvider(value,null); }
    @Override public StoredConfiguration saveStored(IdentityProviderConfiguration value,PublicJwkSet publicKeys) {
        Objects.requireNonNull(publicKeys,"public JWK set"); return saveProvider(value,publicKeys);
    }
    private StoredConfiguration saveProvider(IdentityProviderConfiguration value,PublicJwkSet publicKeys) {
        var now=canonical(clock.instant());
        var existing=providerQuery(value.siteId(),value.providerKey()).getResultStream().findFirst();
        boolean insert=existing.isEmpty();
        var e=existing.orElseGet(IdentityProviderConfigurationJpaEntity::new);
        if(insert) {
            if(value.version()!=0) throw new OptimisticLockingFailureException("Provider no longer exists");
            e.id=UUID.randomUUID(); e.siteId=value.siteId().value(); e.providerKey=value.providerKey(); e.createdAt=now;
        } else {
            if(e.version!=value.version()) throw new OptimisticLockingFailureException("Stale provider version");
            if(("DISABLED".equals(e.status) && value.status()!=IdentityProviderStatus.DISABLED) || !e.mode.equals(value.mode().name()))
                throw new IllegalArgumentException("Provider mode and disabled state are immutable");
        }
        String previousRef=e.publicJwkSetRef;
        mapper.copyProvider(value,e);
        if("STATIC_PUBLIC".equals(e.jwksSourceType)) {
            if(publicKeys==null && (e.publicJwkSet==null || !Objects.equals(previousRef,e.publicJwkSetRef)))
                throw new IllegalArgumentException("Changed static key reference requires public JWK set");
            if(publicKeys!=null) e.publicJwkSet=publicKeys.canonicalJson();
        } else {
            if(publicKeys!=null) throw new IllegalArgumentException("Public JWK set requires static source");
            e.publicJwkSet=null;
        }
        e.updatedAt=now;
        if(insert) entities.persist(e);
        entities.flush(); return new StoredConfiguration(e.id,mapper.provider(e));
    }
}
