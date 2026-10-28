package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
public interface SubscriptionProviderPort {
    Capabilities capabilities(PaymentAccount account);
    default CreatePreparation prepareCreate(Command command){return new ReadyForCreate();}
    default SetupContinuationResult continueOriginalSetup(Command command,SetupContinuationFence fence){return new SetupContinuationResult(null);}
    default OwnedSetupReceipt confirmPreparedSetup(Command command,String token){throw new UnsupportedOperationException();}
    Reply create(Command command);
    ProviderQuote preview(QuoteRequest request);
    Reply change(Command command);
    Reply clearChange(Command command);
    /** Explicit same-key retry only; providers opt in after proving an original dispatch. */
    default Reply continueOriginalPlan(Command command){return null;}
    Reply cancel(Command command,boolean immediate);
    Reply withdrawCancel(Command command);
    Reply setup(Command command);
    Reply recover(Command command);
    Reply resume(Command command);
    Snapshot retrieve(Lookup lookup);
    /** Exact authorized original key may rotate expired capability only; null-key reads never rotate. */
    NextAction nextAction(Lookup lookup);
    /** Original-operation continuation; exact receipt replay precedes mutable economic state checks. */
    Reply confirmAction(Command command,String actionToken);
    PortalNavigation portal(Command command,String allowedReturnUrl);
}
