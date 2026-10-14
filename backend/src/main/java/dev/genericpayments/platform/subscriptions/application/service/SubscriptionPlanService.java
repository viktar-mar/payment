package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import org.springframework.stereotype.Service;
@Service
public class SubscriptionPlanService implements PreviewSubscriptionChangeUseCase,ChangeSubscriptionPlanUseCase,ClearSubscriptionChangeUseCase {
    private final SubscriptionPlanTransaction transactions;private final SubscriptionProviderRegistry providers;
    public SubscriptionPlanService(SubscriptionPlanTransaction transactions,SubscriptionProviderRegistry providers){this.transactions=transactions;this.providers=providers;}
    public SubscriptionQuote preview(PreviewSubscriptionChangeUseCase.Query query){
        var c=transactions.candidate(query.context(),query.subscriptionId());var provider=providers.forAccount(c.account());
        boolean conditional=provider.capabilities(c.account()).conditionalUpdate();var p=transactions.preview(query,c,conditional);
        var request=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Command(c.account(),p.request(),p.candidate().subscription().billingPrice(),p.target(),p.candidate().subscription().providerReference(),null,null,false,false);
        var timing=p.timing();var quote=provider.preview(new QuoteRequest(request,p.target(),timing));
        if(timing==ChangeTiming.IMMEDIATE&&quote.dueNowMinor()<0){timing=ChangeTiming.NEXT_PERIOD;quote=provider.preview(new QuoteRequest(request,p.target(),timing));}
        return transactions.savePreview(query,p,quote,timing,conditional);
    }
    public SubscriptionOperationResult change(ChangeSubscriptionPlanUseCase.Command command){return execute(command.context(),command.subscriptionId(),command.quoteId(),false);}
    public SubscriptionOperationResult clear(ClearSubscriptionChangeUseCase.Command command){return execute(command.context(),command.subscriptionId(),null,true);}
    private SubscriptionOperationResult execute(SubscriptionCommandContext context,java.util.UUID id,java.util.UUID quoteId,boolean clear){
        var c=transactions.candidate(context,id);var provider=providers.forAccount(c.account());boolean conditional=provider.capabilities(c.account()).conditionalUpdate();
        var r=transactions.reserve(context,id,quoteId,c,conditional,clear);if(!r.owner())return transactions.result(r);
        var request=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Command(r.account(),r.operation(),r.subscription().billingPrice(),clear?null:r.quote().target(),r.subscription().providerReference(),null,clear?null:r.quote().providerQuoteReference(),false,false);
        try{
            if(!clear){var read=provider.preview(new QuoteRequest(request,r.quote().target(),r.quote().timing()));var q=r.quote();
                if(!new ProviderQuote(q.providerQuoteReference(),q.dueNowMinor(),q.nextAmountMinor(),q.currency(),q.effectiveAt(),q.expiresAt(),q.taxFinal()).equals(read))
                    throw SubscriptionRuleErrors.translate(new SubscriptionRuleViolation(SubscriptionRuleViolation.Reason.QUOTE_STALE,"Preview again"));}
            transactions.beforeMutation(context,r,conditional);
        }catch(RuntimeException invalid){transactions.rejectBeforeMutation(r);throw invalid;}
        Reply reply;try{reply=clear?provider.clearChange(request):provider.change(request);}catch(RuntimeException unknown){reply=new Reply(Outcome.UNKNOWN,null,null);}
        return transactions.reply(r,reply);
    }
}
