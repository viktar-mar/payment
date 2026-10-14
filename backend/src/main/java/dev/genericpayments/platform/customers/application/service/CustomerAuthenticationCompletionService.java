package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.model.*;
import dev.genericpayments.platform.customers.domain.CustomerAuthenticationMode;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically resolves customer ownership and inserts the one returned session. */
@Service
class CustomerAuthenticationCompletionService {
    private final LockSitePort sites;
    private final IdentityResolutionService identities;
    private final CustomerSessionService sessions;

    CustomerAuthenticationCompletionService(LockSitePort sites,IdentityResolutionService identities,CustomerSessionService sessions) {
        this.sites=sites; this.identities=identities; this.sessions=sessions;
    }

    @Transactional
    CustomerAuthenticationResult.AuthenticatedCustomer authenticate(SiteId siteId,CustomerAuthenticationMode mode,
            VerifiedIdentityEvidence evidence,Instant now,String origin) {
        String normalizedOrigin=CustomerAuthenticationSupport.origin(origin);
        var site=sites.findByIdForUpdate(siteId).orElseThrow(CustomerAuthenticationSupport::failed);
        CustomerAuthenticationSupport.active(site,mode,normalizedOrigin);
        var customer=identities.resolve(site.id(),mode,evidence,now);
        var session=sessions.issue(site.id(),customer.id(),normalizedOrigin);
        return new CustomerAuthenticationResult.AuthenticatedCustomer(customer.id(),session);
    }
}
