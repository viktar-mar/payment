package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
@org.springframework.stereotype.Service
public class SubscriptionActionService implements GetSubscriptionActionUseCase,ConfirmSubscriptionActionUseCase {
    private final SubscriptionInteractionTransaction phases;
    private final SubscriptionProviderRegistry providers;
    public SubscriptionActionService(SubscriptionInteractionTransaction phases,SubscriptionProviderRegistry providers){this.phases=phases;this.providers=providers;}
    /** Transport adapters must return this capability with Cache-Control: no-store. */
    public NextAction get(Query query){
        var r=phases.action(query.context(),query.subscriptionId(),query.operationId());
        var action=providers.forAccount(r.account()).nextAction(lookup(r));
        return phases.issued(query.context(),r,action);
    }
    public SubscriptionOperationResult confirm(Command command){
        var r=phases.action(command.context(),command.subscriptionId(),command.operationId());
        var provider=providers.forAccount(r.account());
        if(!phases.replay(command)){
            var action=provider.nextAction(lookup(r));
            if(action==null||!MessageDigest.isEqual(action.secret().getBytes(StandardCharsets.UTF_8),command.actionToken().getBytes(StandardCharsets.UTF_8)))throw SubscriptionRuleErrors.conflict();
            phases.claim(command,r,action);
        }
        Reply reply;
        try{reply=provider.confirmAction(SubscriptionInteractionTransaction.command(r),command.actionToken());}
        catch(RuntimeException uncertain){reply=new Reply(Outcome.UNKNOWN,r.subscription().providerReference(),null);}
        return phases.reply(command.context(),r,reply);
    }
    private static Lookup lookup(SubscriptionInteractionTransaction.State r){return new Lookup(r.account(),r.subscription().id(),r.subscription().providerReference(),r.operation().providerOperationKey());}
}
