package dev.genericpayments.platform.subscriptions.adapter.out.integration;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.sites.domain.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionLifecycleLockPort;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class SubscriptionLifecycleLockAdapter implements SubscriptionLifecycleLockPort {
    private final LockSitePort sites;private final CustomerStore customers;
    public SubscriptionLifecycleLockAdapter(LockSitePort sites,CustomerStore customers){this.sites=sites;this.customers=customers;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void lockActive(SiteId site,CustomerId customer){
        var lockedSite=sites.findByIdForUpdate(site).orElseThrow(SubscriptionLifecycleLockAdapter::unavailable);
        var lockedCustomer=customers.findByIdForUpdate(site,customer).orElseThrow(SubscriptionLifecycleLockAdapter::unavailable);
        if(lockedSite.status()!=SiteStatus.ACTIVE||lockedCustomer.status()!=CustomerStatus.ACTIVE)throw unavailable();
    }
    private static PaymentApplicationException unavailable(){return new PaymentApplicationException("CUSTOMER_NOT_AVAILABLE",404,"Customer unavailable");}
}
