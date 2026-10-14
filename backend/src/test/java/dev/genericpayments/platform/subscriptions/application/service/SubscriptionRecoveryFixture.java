package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.provider.adapter.out.fake.FakeSubscriptionQueueRelay;
import dev.genericpayments.platform.webhooks.application.service.SubscriptionInboxWorker;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
abstract class SubscriptionRecoveryFixture extends SubscriptionFactFixture {
    @Autowired protected RecoverSubscriptionUseCase recovery;
    @Autowired protected ResumeSubscriptionUseCase resuming;
    @Autowired protected FakeSubscriptionControlPort controls;
    @Autowired protected FakeSubscriptionQueueRelay relay;
    @Autowired protected SubscriptionInboxWorker worker;
    @Autowired protected org.springframework.context.ApplicationContext app;
    protected <T> T bean(Class<T> type){return app.getBean(type);}
    /** Decorate real provider retrieval to exercise the no-lock gap deterministically. */
    protected SubscriptionRecoveryService afterRetrieve(java.util.function.UnaryOperator<Snapshot> hook){
        SubscriptionProviderRegistry registry=a->{var actual=providers.forAccount(a);return (SubscriptionProviderPort)java.lang.reflect.Proxy.newProxyInstance(SubscriptionProviderPort.class.getClassLoader(),new Class<?>[]{SubscriptionProviderPort.class},(proxy,method,args)->{
            assertThat(tx.active()).as("Provider calls cannot hold platform transactions").isFalse();
            Object result;try{result=method.invoke(actual,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            return method.getName().equals("retrieve")?hook.apply((Snapshot)result):result;
        });};
        return new SubscriptionRecoveryService(new SubscriptionRecoveryTransaction(tx,operations,subscriptions,bean(BillingRecordStore.class),bean(SubscriptionAuthorityPort.class),policies,
            bean(dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort.class),bean(dev.genericpayments.platform.sites.application.port.out.LockSitePort.class),customers,registry,bean(java.time.Clock.class)),registry);
    }
    protected SubscriptionProviderPort restartedProvider(){return new dev.genericpayments.platform.provider.adapter.out.fake.FakeSubscriptionProviderAdapter(new dev.genericpayments.platform.provider.adapter.out.fake.FakeSubscriptionStateAdapter(jdbc,bean(java.time.Clock.class),bean(tools.jackson.databind.ObjectMapper.class),bean(org.springframework.transaction.PlatformTransactionManager.class)));}
    protected void drain(){relay.runOnce();worker.runOnce();assertThat(jdbc.queryForObject("select count(*) from provider_inbox where event_family='SUBSCRIPTION' and status<>'PROCESSED'",Integer.class)).isZero();}
    protected void advance(Instant at){TestClock.set(at);controls.advance(account.id(),at);drain();freshActor();}
    protected void freshActor(){actor=new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site,customer.id(),"https://facts.test").rawToken(),"https://facts.test"));}
    protected SubscriptionCommandContext context(String key){return new SubscriptionCommandContext(actor,site,customer.id(),"https://facts.test",key,"task8");}
    protected SubscriptionOperationResult paid(){var r=start();drain();return r;}
    protected Snapshot view(UUID id){return providers.forAccount(account).retrieve(new Lookup(account,id,sub(id).providerReference(),null));}
    protected RecoverSubscriptionUseCase.Command recoverCommand(UUID id,String key){assertThat(sub(id).debt()).isNotNull();return new RecoverSubscriptionUseCase.Command(context(key),id,sub(id).debt().billingRecordId());}
    protected Command providerCommand(UUID id,SubscriptionOperation operation,String invoice){return new Command(account,operation,sub(id).billingPrice(),null,sub(id).providerReference(),invoice,null,operation.kind()!=SubscriptionOperation.Kind.RECOVER,false);}
    protected SubscriptionOperation setupMethod(UUID id){
        var s=sub(id);var now=view(id).observedAt();
        var o=new SubscriptionOperation(UUID.randomUUID(),site,customer.id(),id,SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Status.RESERVED,"internal","setup-"+UUID.randomUUID(),"setup","setup-"+UUID.randomUUID(),s.revision(),policies.load(site).version(),s.providerReference(),null,now,now,0);
        tx.required(()->operations.claim(o));
        var request=providerCommand(id,o,null);var reply=providers.forAccount(account).setup(request);
        providers.forAccount(account).confirmAction(request,reply.action().secret());drain();return o;
    }
}
