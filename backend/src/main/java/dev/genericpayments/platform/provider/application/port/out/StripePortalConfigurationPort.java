package dev.genericpayments.platform.provider.application.port.out;

import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Command;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Instant;
import java.util.*;

/** Task-specific safe Portal intent and publication proof. Never stores a navigation URL. */
public interface StripePortalConfigurationPort {
    record Scope(PaymentAccount account,StripeBillingBindingPort.CustomerBinding customer,SubscriptionOperation operation,
        SubscriptionPolicy policy,List<Subscription> members,String returnUrl){public Scope{members=List.copyOf(members);}}
    record Configuration(UUID id,PaymentAccountId account,SiteId site,long policy,String digest,UUID owner,
        String key,String fingerprint,String reference,UUID step){}
    record Session(UUID operation,Configuration configuration,Scope scope,String fingerprint,String reference,UUID step,Instant expiresAt){}
    Scope capture(Command command,String returnUrl);
    /** Short transaction: locks owner/Site/Customer/Account and compares complete membership/revisions. */
    void authorize(Scope scope);
    Configuration intent(Configuration proposed);
    Configuration bind(Configuration intent,UUID acknowledgedStep,String reference);
    Session session(Session proposed);
    Session record(Session intent,UUID acknowledgedStep,String reference,Instant expiresAt);
    /** Joins the interaction's final publication transaction and independently validates real proof. */
    void requirePublication(PaymentAccount account,Subscription subscription,SubscriptionOperation operation,String returnUrl,Instant expiresAt);
}
