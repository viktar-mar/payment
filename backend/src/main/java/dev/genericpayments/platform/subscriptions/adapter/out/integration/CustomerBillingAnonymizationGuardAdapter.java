package dev.genericpayments.platform.subscriptions.adapter.out.integration;
import dev.genericpayments.platform.customers.application.port.out.CustomerBillingAnonymizationGuardPort;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionStore;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class CustomerBillingAnonymizationGuardAdapter implements CustomerBillingAnonymizationGuardPort {
    private final SubscriptionStore subscriptions;
    public CustomerBillingAnonymizationGuardAdapter(SubscriptionStore subscriptions){this.subscriptions=subscriptions;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireAnonymizable(SiteId site,CustomerId customer){
        if(subscriptions.hasBlockingCustomerSubscriptions(site,customer))throw new PaymentApplicationException("CUSTOMER_BILLING_ACTIVE",409,"Finish subscriptions before anonymization");
    }
}
