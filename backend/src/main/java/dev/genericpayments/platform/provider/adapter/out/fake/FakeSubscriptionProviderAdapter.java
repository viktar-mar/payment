package dev.genericpayments.platform.provider.adapter.out.fake;

import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderPort;

/** Transport facade for the durable simulator; it owns no in-memory provider truth. */
public final class FakeSubscriptionProviderAdapter implements SubscriptionProviderPort {
    private final FakeSubscriptionStateAdapter state;
    public FakeSubscriptionProviderAdapter(FakeSubscriptionStateAdapter state){this.state=state;}
    @Override public Capabilities capabilities(PaymentAccount account){FakeSubscriptionStateAdapter.requireFake(account);return new Capabilities(true,true,true,true);}
    @Override public Reply create(Command command){return state.execute("CREATE",command,false,null);}
    @Override public ProviderQuote preview(QuoteRequest request){return state.preview(request);}
    @Override public Reply change(Command command){return state.execute("CHANGE",command,false,null);}
    @Override public Reply clearChange(Command command){return state.execute("CLEAR_CHANGE",command,false,null);}
    @Override public Reply cancel(Command command,boolean immediate){return state.execute("CANCEL",command,immediate,null);}
    @Override public Reply withdrawCancel(Command command){return state.execute("WITHDRAW_CANCEL",command,false,null);}
    @Override public Reply setup(Command command){return state.execute("SETUP",command,false,null);}
    @Override public Reply recover(Command command){return state.execute("RECOVER",command,false,null);}
    @Override public Reply resume(Command command){return state.execute("RESUME",command,false,null);}
    @Override public Snapshot retrieve(Lookup lookup){return state.retrieve(lookup);}
    @Override public NextAction nextAction(Lookup lookup){return state.nextAction(lookup);}
    @Override public Reply confirmAction(Command command,String actionToken){return state.execute("CONFIRM_ACTION",command,false,actionToken);}
    @Override public PortalNavigation portal(Command command,String allowedReturnUrl){return state.portal(command,allowedReturnUrl);}
}
