package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry;
import org.springframework.stereotype.Service;
@Service
public class SubscriptionRecoveryService implements RecoverSubscriptionUseCase,ResumeSubscriptionUseCase {
    private final SubscriptionRecoveryTransaction transactions;private final SubscriptionProviderRegistry providers;
    public SubscriptionRecoveryService(SubscriptionRecoveryTransaction transactions,SubscriptionProviderRegistry providers){this.transactions=transactions;this.providers=providers;}
    public SubscriptionOperationResult recover(RecoverSubscriptionUseCase.Command command){return execute(transactions.reserve(command),false,command.context());}
    public SubscriptionOperationResult resume(ResumeSubscriptionUseCase.Command command){return execute(transactions.reserve(command),true,command.context());}
    private SubscriptionOperationResult execute(SubscriptionRecoveryTransaction.Reserved reserved,boolean resume,dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext context){
        boolean replay=!reserved.owner()&&!resume&&reserved.account().provider()==dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.STRIPE
            &&java.util.Set.of(dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Status.PROCESSING,dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation.Status.UNKNOWN).contains(reserved.operation().status());
        if(!reserved.owner()&&!replay)return transactions.readResult(reserved.operation().id());
        transactions.requireOutsideTransaction();
        var request=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Command(reserved.account(),reserved.operation(),reserved.subscription().billingPrice(),null,reserved.subscription().providerReference(),reserved.invoice(),null,resume,false);
        Reply reply;
        try{var provider=providers.forAccount(reserved.account());if(replay){transactions.beforeOriginalReplay(context,reserved);reply=provider.continueOriginalRecovery(request);if(reply==null)return transactions.readResult(reserved.operation().id());}else reply=resume?provider.resume(request):provider.recover(request);}
        catch(RuntimeException unknown){reply=new Reply(Outcome.UNKNOWN,null,null);}
        return transactions.recordReply(reserved.operation().id(),reply);
    }
}
