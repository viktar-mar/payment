package dev.genericpayments.platform.subscriptions.adapter.out.integration;
import dev.genericpayments.platform.customers.application.port.out.CustomerFeatureScopesPort;
import dev.genericpayments.platform.customers.domain.CustomerSessionScope;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPolicyPort;
import java.util.Set;
import org.springframework.stereotype.Component;
@Component
public class CustomerSubscriptionScopesAdapter implements CustomerFeatureScopesPort {
    private final SubscriptionPolicyPort policies;
    public CustomerSubscriptionScopesAdapter(SubscriptionPolicyPort policies) {this.policies=policies;}
    public Set<CustomerSessionScope> additionalScopes(SiteId site) {
        return policies.load(site).enabled()
            ? Set.of(CustomerSessionScope.SUBSCRIPTION_READ,CustomerSessionScope.SUBSCRIPTION_MANAGE) : Set.of();
    }
}
