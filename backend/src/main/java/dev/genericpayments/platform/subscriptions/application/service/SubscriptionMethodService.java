package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.in.SetupSubscriptionMethodUseCase;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.GetSubscriptionActionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
@org.springframework.stereotype.Service
public class SubscriptionMethodService implements SetupSubscriptionMethodUseCase {
    private final SubscriptionInteractionTransaction phases;private final SubscriptionProviderRegistry providers;private final GetSubscriptionActionUseCase actions;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private dev.genericpayments.platform.subscriptions.application.port.in.ContinueOriginalSubscriptionSetupUseCase continuation;
    public SubscriptionMethodService(SubscriptionInteractionTransaction phases,SubscriptionProviderRegistry providers,GetSubscriptionActionUseCase actions){this.phases=phases;this.providers=providers;this.actions=actions;}
    public SubscriptionOperationResult setup(Command command){
        SubscriptionInteractionTransaction.validateKey(command.context());
        if(!command.recurringConsent())throw SubscriptionRuleErrors.conflict();
        var original=phases.originalCreate(command.context(),command.subscriptionId());
        if(original!=null){
            if(stripeContinuation(original)&&original.subscription().providerReference()==null
                &&CreateSubscriptionFingerprint.startsTrial(original.operation().fingerprint())&&original.subscription().billingPrice().trialPolicy()==dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL)
                return continueAndReadAction(command,original);
            var action=actions.get(new GetSubscriptionActionUseCase.Query(command.context(),command.subscriptionId(),original.operation().id()));
            if(action!=null)return SubscriptionInteractionTransaction.result(original.operation(),action);
            if(original.operation().status()!=SubscriptionOperation.Status.FAILED)return SubscriptionInteractionTransaction.result(original.operation(),null);
            return dispatch(command,original);
        }
        var candidate=phases.candidate(command.context(),command.subscriptionId());
        if(!providers.forAccount(candidate.account()).capabilities(candidate.account()).setup())throw SubscriptionRuleErrors.conflict();
        var r=phases.reserve(command.context(),command.subscriptionId(),SubscriptionOperation.Kind.SETUP,
            SubscriptionInteractionTransaction.request(command.context(),"SETUP",command.subscriptionId(),true),candidate);
        if(!r.owner())return stripeContinuation(r)?continueAndReadAction(command,r):SubscriptionInteractionTransaction.result(r.operation(),actions.get(new GetSubscriptionActionUseCase.Query(command.context(),command.subscriptionId(),r.operation().id())));
        return dispatch(command,r);
    }
    private boolean stripeContinuation(SubscriptionInteractionTransaction.State r){return continuation!=null&&r.account().provider()==dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.STRIPE;}
    private SubscriptionOperationResult continueAndReadAction(Command c,SubscriptionInteractionTransaction.State r){
        var result=continuation.api(c.context(),c.subscriptionId(),r.operation().id());
        var action=actions.get(new GetSubscriptionActionUseCase.Query(c.context(),c.subscriptionId(),r.operation().id()));
        return new SubscriptionOperationResult(result.operationId(),result.subscriptionId(),result.status(),action);
    }
    private SubscriptionOperationResult dispatch(Command c,SubscriptionInteractionTransaction.State r){
        Reply reply;try{reply=providers.forAccount(r.account()).setup(SubscriptionInteractionTransaction.command(r));}
        catch(RuntimeException uncertain){reply=new Reply(Outcome.UNKNOWN,r.subscription().providerReference(),null);}
        return phases.reply(c.context(),r,reply);
    }
}
