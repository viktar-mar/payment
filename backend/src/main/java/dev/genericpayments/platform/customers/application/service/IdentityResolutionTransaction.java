package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
class IdentityResolutionTransaction {
    private final CustomerStore customers; private final CustomerIdentityStore identities;
    IdentityResolutionTransaction(CustomerStore customers,CustomerIdentityStore identities) { this.customers=customers; this.identities=identities; }
    @Transactional
    public Customer createOrLoad(SiteId site,CustomerAuthenticationMode mode,String issuer,List<String> subjects,String activeSubject,
            String emailDigest,EncryptedValue email,Instant now) {
        var aliases=identities.reserveSubjectAliases(site,issuer,subjects);
        var existing=owner(aliases);
        if(existing.isPresent()) return activateAndBackfill(existing.orElseThrow(),aliases,subjects,now);
        var id=new CustomerId(UUID.randomUUID());
        Customer candidate=Customer.create(id,site,email==null?null:CustomerStore.emailReference(id),emailDigest,now);
        Customer saved=email==null?customers.save(candidate):customers.save(candidate,email);
        identities.create(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,saved.id(),mode,issuer,activeSubject,now),subjects);
        return saved;
    }
    private Customer activateAndBackfill(CustomerIdentity identity,List<CustomerIdentityStore.SubjectAlias> aliases,List<String> subjects,Instant now) {
        Set<String> present=aliases.stream().map(CustomerIdentityStore.SubjectAlias::versionedSubjectDigest).collect(java.util.stream.Collectors.toSet());
        identities.saveSubjectAliases(identity,subjects.stream().filter(subject -> !present.contains(subject)).toList());
        return active(identity,now);
    }
    private static Optional<CustomerIdentity> owner(List<CustomerIdentityStore.SubjectAlias> aliases) {
        Map<CustomerIdentityId,CustomerIdentity> owners=new LinkedHashMap<>();
        aliases.forEach(alias -> owners.put(alias.identity().id(),alias.identity()));
        if(owners.size()>1) throw CustomerAuthenticationSupport.failed();
        return owners.values().stream().findFirst();
    }
    private Customer active(CustomerIdentity identity,Instant now) {
        Customer customer=customers.findByIdForUpdate(identity.siteId(),identity.customerId()).orElseThrow(CustomerAuthenticationSupport::failed);
        if(customer.status()!=CustomerStatus.ACTIVE) throw CustomerAuthenticationSupport.failed();
        identities.update(identity.touch(now));
        return customer;
    }
}
