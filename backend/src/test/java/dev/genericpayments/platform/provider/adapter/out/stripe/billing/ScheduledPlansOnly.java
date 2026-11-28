package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderPort;
import dev.genericpayments.platform.subscriptions.domain.ChangeTiming;
import java.time.Clock;

/** Restricts the native clock fixture to the requested NEXT_PERIOD quote and its original write. */
final class ScheduledPlansOnly implements SubscriptionProviderPort {
    private final StripeBillingMutationAdapter mutation;
    private final ProviderOperationStore operations;
    private final PaymentAccount account;

    ScheduledPlansOnly(StripeBillingMutationAdapter mutation,ProviderOperationStore operations,PaymentAccount account) {
        this.mutation=mutation;this.operations=operations;this.account=account;
    }
    public Capabilities capabilities(PaymentAccount requested) {
        if(!account.equals(requested))throw unsupported();
        return new Capabilities(true,false,false,false);
    }
    public ProviderQuote preview(QuoteRequest request) {
        if (!account.equals(request.command().account()) || request.timing()!=ChangeTiming.NEXT_PERIOD) throw unsupported();
        return mutation.preview(request);
    }
    public Reply change(Command command) {
        if(!account.equals(command.account()))throw unsupported();
        return mutation.change(command,operations,Clock.systemUTC());
    }
    private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException("Scheduled plan fixture"); }
    public Reply create(Command c){throw unsupported();}
    public Reply clearChange(Command c){throw unsupported();}
    public Reply cancel(Command c,boolean immediately){throw unsupported();}
    public Reply withdrawCancel(Command c){throw unsupported();}
    public Reply setup(Command c){throw unsupported();}
    public Reply recover(Command c){throw unsupported();}
    public Reply resume(Command c){throw unsupported();}
    public Snapshot retrieve(Lookup lookup){throw unsupported();}
    public NextAction nextAction(Lookup lookup){throw unsupported();}
    public Reply confirmAction(Command c,String token){throw unsupported();}
    public PortalNavigation portal(Command c,String url){throw unsupported();}
}
