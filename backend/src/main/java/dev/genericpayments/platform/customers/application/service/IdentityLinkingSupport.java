package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.port.out.CustomerIdentityStore;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

final class IdentityLinkingSupport {
    enum Outcome { LINKED, ALREADY_LINKED, CONFLICT }
    record Result(CustomerIdentityId identityId,Outcome outcome) {}
    private final CustomerIdentityStore identities;
    IdentityLinkingSupport(CustomerIdentityStore identities){this.identities=identities;}

    Result link(SiteId site,CustomerId target,CustomerAuthenticationMode mode,String issuer,List<String> subjects,Instant verifiedAt,Instant now) {
        List<String> unique=subjects.stream().distinct().toList();
        if(unique.isEmpty())throw new IllegalArgumentException("At least one subject digest is required");
        var aliases=identities.reserveSubjectAliases(site,issuer,unique);
        var owners=aliases.stream().map(CustomerIdentityStore.SubjectAlias::identity)
            .collect(java.util.stream.Collectors.toMap(identity -> identity.id().value(),identity -> identity,(left,right)->left,LinkedHashMap::new));
        if(owners.size()>1) return new Result(null,Outcome.CONFLICT);
        if(owners.size()==1) {
            var existing=owners.values().iterator().next();
            if(!existing.customerId().equals(target)) return new Result(existing.id(),Outcome.CONFLICT);
            identities.saveSubjectAliases(existing,unique);
            identities.update(existing.touch(now));
            return new Result(existing.id(),Outcome.ALREADY_LINKED);
        }
        var created=identities.create(CustomerIdentity.create(new CustomerIdentityId(UUID.randomUUID()),site,target,mode,issuer,unique.getFirst(),verifiedAt),unique);
        return new Result(created.id(),Outcome.LINKED);
    }
}
