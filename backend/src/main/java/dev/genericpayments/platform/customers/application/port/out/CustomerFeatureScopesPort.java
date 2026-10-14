package dev.genericpayments.platform.customers.application.port.out;
import dev.genericpayments.platform.customers.domain.CustomerSessionScope;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.Set;
/** Evaluated only when issuing a new authenticated session. */
public interface CustomerFeatureScopesPort {
    Set<CustomerSessionScope> additionalScopes(SiteId site);
}
