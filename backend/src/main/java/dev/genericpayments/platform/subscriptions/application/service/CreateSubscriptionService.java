package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
public class CreateSubscriptionService implements CreateSubscriptionUseCase {
    private final SubscriptionCommandTransaction transactions;
    private final SubscriptionProviderRegistry providers;
    public CreateSubscriptionService(SubscriptionCommandTransaction transactions,SubscriptionProviderRegistry providers){this.transactions=transactions;this.providers=providers;}
    public SubscriptionOperationResult create(CreateSubscriptionUseCase.Command command) {
        transactions.requireOutsideTransaction();
        var reserved=transactions.reserveCreate(command);
        if(!reserved.owner())return transactions.readResult(reserved.operation().id());
        var request=new SubscriptionProviderModels.Command(reserved.account(),reserved.operation(),reserved.subscription().billingPrice(),null,null,null,null,
            command.recurringConsent(),CreateSubscriptionFingerprint.startsTrial(reserved.operation().fingerprint()));
        Reply reply;
        try {var provider=providers.forAccount(reserved.account());var preparation=provider.prepareCreate(request);
            if(preparation instanceof PendingSetup pending)return transactions.recordSetupReceipt(reserved.operation().id(),pending);
            reply=provider.create(request);}
        catch(RuntimeException failure){reply=new Reply(Outcome.UNKNOWN,null,null);}
        return transactions.recordReply(reserved.operation().id(),reply);
    }
}
