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
        boolean stripe=r.account().provider()==dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.STRIPE;
        if(stripe&&!(command.context().actor() instanceof SubscriptionActor.Customer))throw SubscriptionRuleErrors.conflict();
        boolean replay=phases.replay(command);
        if(stripe&&replay)return replayReceipt(command,r);
        var claimed=dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionActionStore.ClaimDisposition.REPLAY;
        if(!replay){
            var action=provider.nextAction(lookup(r));
            // Another request may consume while this request is retrieving the action.
            if(stripe&&action==null&&phases.replay(command))return replayReceipt(command,r);
            if(action==null||!MessageDigest.isEqual(action.secret().getBytes(StandardCharsets.UTF_8),command.actionToken().getBytes(StandardCharsets.UTF_8)))throw SubscriptionRuleErrors.conflict();
            claimed=phases.claim(command,r,action);
            if(stripe&&claimed==dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionActionStore.ClaimDisposition.REPLAY)return replayReceipt(command,r);
        }
        if(stripe&&r.subscription().providerReference()==null){
            try{
                var receipt=provider.confirmPreparedSetup(SubscriptionInteractionTransaction.command(r),command.actionToken());
                var result=phases.replyPreparedSetup(command.context(),r,command.actionToken(),receipt);
                boolean allowed=claimed==dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionActionStore.ClaimDisposition.NEW&&result.status()==dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Status.PROCESSING&&phases.browserConfirmationAllowed(command,r);
                return new SubscriptionOperationResult(result.operationId(),result.subscriptionId(),result.status(),null,allowed);
            }catch(RuntimeException unknown){return phases.reply(command.context(),r,new Reply(Outcome.UNKNOWN,null,null));}
        }
        Reply reply;
        try{reply=provider.confirmAction(SubscriptionInteractionTransaction.command(r),command.actionToken());}
        catch(RuntimeException uncertain){reply=new Reply(Outcome.UNKNOWN,r.subscription().providerReference(),null);}
        var result=phases.reply(command.context(),r,reply);
        boolean allowed=stripe&&claimed==dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionActionStore.ClaimDisposition.NEW
            &&reply.outcome()==Outcome.ACCEPTED&&result.status()==dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Status.PROCESSING
            &&phases.browserConfirmationAllowed(command,r);
        return new SubscriptionOperationResult(result.operationId(),result.subscriptionId(),result.status(),result.nextAction(),allowed);
    }
    private SubscriptionOperationResult replayReceipt(Command c,SubscriptionInteractionTransaction.State r){return r.subscription().providerReference()==null?phases.replyPreparedSetup(c.context(),r,c.actionToken(),null):phases.reply(c.context(),r,new Reply(Outcome.ACCEPTED,r.subscription().providerReference(),null));}
    private static Lookup lookup(SubscriptionInteractionTransaction.State r){return new Lookup(r.account(),r.subscription().id(),r.subscription().providerReference(),r.operation().providerOperationKey());}
}
