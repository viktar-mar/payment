package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.CustomerSessionScope;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.Optional;

public interface CustomerSessionResolver {
    CustomerSessionContext resolveRequired(String rawToken,String origin);
    /** Empty means the header was absent; every supplied invalid value fails closed. */
    Optional<CustomerSessionContext> resolveOptional(String authorizationHeader,SiteId expectedSiteId,String origin);
    /** Required scope is validated before the successful-use timestamp is changed. */
    Optional<CustomerSessionContext> resolveOptional(String authorizationHeader,SiteId expectedSiteId,String origin,
                                                      CustomerSessionScope requiredScope);
}
