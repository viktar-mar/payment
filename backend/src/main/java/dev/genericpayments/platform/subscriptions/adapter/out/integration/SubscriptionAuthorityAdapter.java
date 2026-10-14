package dev.genericpayments.platform.subscriptions.adapter.out.integration;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionAuthorityPort;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionLifecycleLockPort;
import dev.genericpayments.platform.customers.application.port.in.RevalidateCustomerSessionUseCase;
import dev.genericpayments.platform.customers.domain.CustomerSessionScope;
import dev.genericpayments.platform.partners.application.port.out.SiteApiKeyPort;
import dev.genericpayments.platform.partners.domain.PartnerScope;
import dev.genericpayments.platform.admin.application.port.in.RevalidateAdminUseCase;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.Clock;
import org.springframework.transaction.annotation.*;
import org.springframework.stereotype.Component;
@Component
public class SubscriptionAuthorityAdapter implements SubscriptionAuthorityPort {
    private final SubscriptionLifecycleLockPort lifecycle;
    private final RevalidateCustomerSessionUseCase customers;
    private final SiteApiKeyPort keys;
    private final RevalidateAdminUseCase admins;
    private final Clock clock;
    private final dev.genericpayments.platform.sites.application.port.out.LockSitePort sites;
    public SubscriptionAuthorityAdapter(SubscriptionLifecycleLockPort lifecycle,RevalidateCustomerSessionUseCase customers,SiteApiKeyPort keys,RevalidateAdminUseCase admins,Clock clock,dev.genericpayments.platform.sites.application.port.out.LockSitePort sites){this.lifecycle=lifecycle;this.customers=customers;this.keys=keys;this.admins=admins;this.clock=clock;this.sites=sites;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void revalidateSite(SubscriptionActor actor,dev.genericpayments.platform.sites.domain.SiteId site,boolean manage) {
        if(actor==null||site==null)throw denied();
        var locked=sites.findByIdForUpdate(site).orElseThrow(SubscriptionAuthorityAdapter::denied);
        if(locked.status()!=dev.genericpayments.platform.sites.domain.SiteStatus.ACTIVE)throw denied();
        switch(actor) {
            case SubscriptionActor.Admin a -> admins.revalidate(a.admin());
            case SubscriptionActor.Partner a -> {
                if(!a.site().siteId().equals(site))throw denied();
                var k=keys.lock(site,a.site().keyId()).orElseThrow(SubscriptionAuthorityAdapter::denied);
                if(!k.active(clock.instant())||!k.scopes().equals(a.site().scopes())||!k.scopes().contains(manage?PartnerScope.SUBSCRIPTIONS_MANAGE:PartnerScope.SUBSCRIPTIONS_READ))throw denied();
            }
            case SubscriptionActor.Customer ignored -> throw denied();
        }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void revalidate(SubscriptionCommandContext context,boolean manage) {
        if(context==null||context.actor()==null||context.siteId()==null||context.customerId()==null)throw denied();
        lifecycle.lockActive(context.siteId(),context.customerId());
        switch(context.actor()) {
            case SubscriptionActor.Customer actor -> {
                var session=actor.session();
                if(session==null||!session.siteId().equals(context.siteId())||!session.customerId().equals(context.customerId())||!session.origin().equals(context.origin()))throw denied();
                customers.revalidate(session,context.siteId(),context.origin(),manage?CustomerSessionScope.SUBSCRIPTION_MANAGE:CustomerSessionScope.SUBSCRIPTION_READ);
            }
            case SubscriptionActor.Partner actor -> {
                var supplied=actor.site();
                if(supplied==null||!supplied.siteId().equals(context.siteId()))throw denied();
                var key=keys.lock(context.siteId(),supplied.keyId()).orElseThrow(SubscriptionAuthorityAdapter::denied);
                if(!key.active(clock.instant())||!key.scopes().equals(supplied.scopes())||!key.scopes().contains(manage?PartnerScope.SUBSCRIPTIONS_MANAGE:PartnerScope.SUBSCRIPTIONS_READ))throw denied();
            }
            case SubscriptionActor.Admin actor -> admins.revalidate(actor.admin());
        }
    }
    private static PaymentApplicationException denied(){return new PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed");}
}
