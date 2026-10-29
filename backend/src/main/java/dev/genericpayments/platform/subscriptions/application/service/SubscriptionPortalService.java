package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionPortalUseCase;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.PortalNavigation;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPolicy;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.util.UUID;
@org.springframework.stereotype.Service
public class SubscriptionPortalService implements CreateSubscriptionPortalUseCase {
    private final SubscriptionInteractionTransaction phases;private final SubscriptionProviderRegistry providers;
    public SubscriptionPortalService(SubscriptionInteractionTransaction phases,SubscriptionProviderRegistry providers){this.phases=phases;this.providers=providers;}
    public PortalNavigation create(Command command){
        var candidate=phases.candidate(command.context(),command.subscriptionId());
        var provider=providers.forAccount(candidate.account());
        var url=requireAllowedReturn(candidate.policy(),command.returnUrl());
        if(!provider.capabilities(candidate.account()).portal())throw SubscriptionRuleErrors.conflict();
        var r=phases.reserve(command.context(),command.subscriptionId(),SubscriptionOperation.Kind.PORTAL,
            SubscriptionInteractionTransaction.request(command.context(),"PORTAL",command.subscriptionId(),url),candidate);
        phases.beforePortal(command.context(),r,url);
        var navigation=provider.portal(SubscriptionInteractionTransaction.command(r),url);
        return phases.portal(command.context(),r,navigation,url);
    }
    public Subscription returned(SubscriptionCommandContext context,UUID subscriptionId,UUID publicOperationId){return phases.portalReturn(context,subscriptionId,publicOperationId);}
    public static String requireAllowedReturn(SubscriptionPolicy policy,String url){
        if(!policy.enabled()||!policy.portalEnabled()||url==null||!policy.portalReturnUrls().contains(url))throw new PaymentApplicationException("SUBSCRIPTION_PORTAL_UNAVAILABLE",409,"Portal unavailable");
        return url;
    }
}
