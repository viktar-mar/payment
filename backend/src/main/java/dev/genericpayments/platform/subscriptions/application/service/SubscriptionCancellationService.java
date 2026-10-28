package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import org.springframework.stereotype.Service;
@Service
public class SubscriptionCancellationService implements CancelSubscriptionUseCase,WithdrawSubscriptionCancellationUseCase {
    private final SubscriptionCancellationTransaction transactions;
    private final SubscriptionProviderRegistry providers;
    public SubscriptionCancellationService(SubscriptionCancellationTransaction transactions,SubscriptionProviderRegistry providers){this.transactions=transactions;this.providers=providers;}
    public SubscriptionOperationResult cancel(CancelSubscriptionUseCase.Command command){return execute(command.context(),command.subscriptionId(),command.mode(),command.confirmation(),false);}
    public SubscriptionOperationResult withdraw(WithdrawSubscriptionCancellationUseCase.Command command){return execute(command.context(),command.subscriptionId(),null,null,true);}
    private SubscriptionOperationResult execute(dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext c,java.util.UUID id,CancelSubscriptionUseCase.Mode mode,java.util.UUID confirmation,boolean withdraw){
        var candidate=transactions.candidate(c,id);
        if(!withdraw&&candidate.subscription().providerReference()==null&&candidate.account().provider()==dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.STRIPE)
            return transactions.abandon(c,id,mode,candidate);
        var provider=providers.forAccount(candidate.account());
        var snapshot=provider.retrieve(new Lookup(candidate.account(),id,candidate.subscription().providerReference(),null));
        var r=transactions.reserve(c,id,mode,confirmation,candidate,snapshot,provider.capabilities(candidate.account()).safeImmediateCancel(),withdraw);
        var request=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Command(r.account(),r.operation(),r.subscription().billingPrice(),null,r.subscription().providerReference(),null,null,false,false);
        if(!r.owner()){
            if(r.account().provider()!=dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.STRIPE
                ||!java.util.Set.of(dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Status.PROCESSING,dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Status.UNKNOWN).contains(r.operation().status()))return transactions.result(r);
            transactions.beforeOriginalReplay(c,r,snapshot);
            Reply replay;try{replay=provider.continueOriginalCancellation(request);}catch(RuntimeException uncertain){replay=new Reply(Outcome.UNKNOWN,null,null);}
            return replay==null?transactions.result(r):transactions.reply(r,replay);
        }
        Reply reply;try{reply=withdraw?provider.withdrawCancel(request):provider.cancel(request,mode==CancelSubscriptionUseCase.Mode.IMMEDIATE);}
        catch(RuntimeException unknown){reply=new Reply(Outcome.UNKNOWN,null,null);}
        return transactions.reply(r,reply);
    }
}
