package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.webhooks.application.port.out.SubscriptionInboxPort;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.domain.Customer;
import dev.genericpayments.platform.sites.domain.*;
import java.time.*;import java.util.*;import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class StripeSubscriptionMethodIsolationTest {
    final StripeSubscriptionConfirmationTest f=new StripeSubscriptionConfirmationTest();
    @Test void explicitSetupContinuesTheSelectedOriginalCreateRatherThanAdmittingAnother(){
        var phases=mock(SubscriptionInteractionTransaction.class);var actions=mock(GetSubscriptionActionUseCase.class);var continuation=mock(ContinueOriginalSubscriptionSetupUseCase.class);var provider=mock(SubscriptionProviderPort.class);
        var terms=f.s.billingPrice();var required=new SubscriptionPriceTerms(terms.priceId(),terms.priceKey(),terms.version(),terms.productId(),terms.regularAmount(),terms.interval(),terms.trialDays(),dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL,terms.taxMode());
        var pending=new Subscription(f.s.id(),f.s.siteId(),f.s.customerId(),f.s.productId(),f.s.paymentAccountId(),required,null,Subscription.Status.INCOMPLETE,null,null,null,null,null,null,null,null,0,f.now,f.now,null);
        var old=f.op;var original=new SubscriptionOperation(old.id(),old.siteId(),old.customerId(),old.subscriptionId(),old.kind(),old.status(),old.authorityScope(),old.keyDigest(),"v1.trial."+"a".repeat(64),old.providerOperationKey(),old.expectedRevision(),old.policyVersion(),null,null,old.createdAt(),old.updatedAt(),old.version());
        var state=new SubscriptionInteractionTransaction.State(pending,f.account,null,original,false);when(phases.originalCreate(f.context,f.s.id())).thenReturn(state);
        var expected=new SubscriptionOperationResult(f.op.id(),f.s.id(),SubscriptionOperation.Status.UNKNOWN,null);
        when(continuation.api(f.context,f.s.id(),f.op.id())).thenReturn(expected);
        var service=new SubscriptionMethodService(phases,a->provider,actions);org.springframework.test.util.ReflectionTestUtils.setField(service,"continuation",continuation);
        assertThat(service.setup(new SetupSubscriptionMethodUseCase.Command(f.context,f.s.id(),true))).isEqualTo(expected);
        verify(phases,never()).reserve(any(),any(),any(),any(),any());verifyNoInteractions(provider);
    }
    @Test void ordinaryPaidOriginalDoesNotEnterRequiredSetupContinuation(){
        var phases=mock(SubscriptionInteractionTransaction.class);var actions=mock(GetSubscriptionActionUseCase.class);var continuation=mock(ContinueOriginalSubscriptionSetupUseCase.class);
        when(phases.originalCreate(f.context,f.s.id())).thenReturn(new SubscriptionInteractionTransaction.State(f.s,f.account,null,f.op,false));
        var service=new SubscriptionMethodService(phases,a->mock(SubscriptionProviderPort.class),actions);org.springframework.test.util.ReflectionTestUtils.setField(service,"continuation",continuation);
        assertThat(service.setup(new SetupSubscriptionMethodUseCase.Command(f.context,f.s.id(),true)).status()).isEqualTo(f.op.status());verifyNoInteractions(continuation);
    }
    @Test void onceCreateHasASubscriptionSweepDoesNotRecheckSetupOrTheCurrentMethod()throws Exception{
        var dependencies=new HashMap<Class<?>,Object>();SubscriptionTransactionPort tx=new SubscriptionTransactionPort(){public <T>T required(Supplier<T>s){return s.get();}public <T>T requiresNew(Supplier<T>s){return s.get();}public boolean active(){return false;}};
        var ctor=SubscriptionSetupContinuationService.class.getConstructors()[0];for(var type:ctor.getParameterTypes())dependencies.put(type,type==Clock.class?Clock.fixed(f.now,ZoneOffset.UTC):type==SubscriptionTransactionPort.class?tx:mock(type));var service=(SubscriptionSetupContinuationService)ctor.newInstance(Arrays.stream(ctor.getParameterTypes()).map(dependencies::get).toArray());
        var s=new Subscription(f.s.id(),f.s.siteId(),f.s.customerId(),f.s.productId(),f.s.paymentAccountId(),f.s.billingPrice(),f.s.entitledPrice(),Subscription.Status.TRIALING,"sub_owned",f.s.currentPeriod(),null,f.s.currentPeriod(),null,null,null,null,1,f.s.createdAt(),f.s.updatedAt(),null);
        var old=f.op;var o=new SubscriptionOperation(old.id(),old.siteId(),old.customerId(),old.subscriptionId(),old.kind(),old.status(),old.authorityScope(),old.keyDigest(),"v1.trial."+"a".repeat(64),old.providerOperationKey(),old.expectedRevision(),old.policyVersion(),"sub_owned",null,old.createdAt(),old.updatedAt(),old.version());
        when(((SubscriptionStore)dependencies.get(SubscriptionStore.class)).findOwned(f.account.id(),s.id())).thenReturn(Optional.of(s));when(((SubscriptionReadPreparation)dependencies.get(SubscriptionReadPreparation.class)).financial(s)).thenReturn(o);when(((SubscriptionBillingProofStore)dependencies.get(SubscriptionBillingProofStore.class)).setup(any(),any())).thenReturn(Optional.of(new SubscriptionBillingProofStore.Setup(o.id(),s.id(),f.account.id(),UUID.randomUUID(),"seti_original","pm_removed")));
        var claim=new SubscriptionSweepPort.Claim(s.siteId(),s.id(),"owned",UUID.randomUUID(),s.revision());assertThatCode(()->assertThat(service.sweep(f.account,s.id(),claim)).isEqualTo(claim)).doesNotThrowAnyException();verifyNoInteractions((SubscriptionProviderRegistry)dependencies.get(SubscriptionProviderRegistry.class));
    }
    @Test void exactLiveSetupInboxClaimIsAcceptedButAnotherSetupReceiptIsRefused()throws Exception{
        var nativeSub=new Subscription(f.s.id(),f.s.siteId(),f.s.customerId(),f.s.productId(),f.s.paymentAccountId(),f.s.billingPrice(),f.s.entitledPrice(),f.s.billingStatus(),"sub_owned",f.s.currentPeriod(),f.s.paidThrough(),f.s.trial(),f.s.cancellation(),f.s.pendingChange(),f.s.debt(),f.s.terminalReason(),f.s.revision(),f.s.createdAt(),f.s.updatedAt(),f.s.lastReconciledAt());
        var original=f.op;var operation=new SubscriptionOperation(original.id(),original.siteId(),original.customerId(),original.subscriptionId(),SubscriptionOperation.Kind.SETUP,original.status(),original.authorityScope(),original.keyDigest(),"b".repeat(64),"subscription:"+f.account.id().value()+":"+original.id()+":SETUP",original.expectedRevision(),original.policyVersion(),"sub_owned",null,original.createdAt(),original.updatedAt(),original.version());
        var dependencies=new HashMap<Class<?>,Object>();
        SubscriptionTransactionPort tx=new SubscriptionTransactionPort(){public <T>T required(Supplier<T>s){return s.get();}public <T>T requiresNew(Supplier<T>s){return s.get();}public boolean active(){return false;}};
        var ctor=SubscriptionSetupContinuationService.class.getConstructors()[0];for(var type:ctor.getParameterTypes())dependencies.put(type,type==Clock.class?Clock.fixed(f.now,ZoneOffset.UTC):type==SubscriptionTransactionPort.class?tx:mock(type));
        var service=(SubscriptionSetupContinuationService)ctor.newInstance(Arrays.stream(ctor.getParameterTypes()).map(dependencies::get).toArray());
        var ops=(SubscriptionOperationStore)dependencies.get(SubscriptionOperationStore.class);var subs=(SubscriptionStore)dependencies.get(SubscriptionStore.class);var proofs=(SubscriptionBillingProofStore)dependencies.get(SubscriptionBillingProofStore.class);var inbox=(SubscriptionInboxPort)dependencies.get(SubscriptionInboxPort.class);
        when(ops.lock(f.op.id())).thenReturn(operation);when(subs.findOwned(f.account.id(),f.s.id())).thenReturn(Optional.of(nativeSub));when(subs.find(f.s.siteId(),f.s.id())).thenReturn(Optional.of(nativeSub));when(subs.lock(f.s.siteId(),f.s.id())).thenReturn(nativeSub);
        when(((LockSitePort)dependencies.get(LockSitePort.class)).findByIdForUpdate(f.s.siteId())).thenReturn(Optional.of(Site.create(f.s.siteId(),new SiteKey("owned"),"Owned",f.account.id(),Set.of(java.net.URI.create("https://shop.test")),Set.of(AuthenticationMode.SITE_TOKEN),PaymentMethodPolicy.automatic())));
        when(((CustomerStore)dependencies.get(CustomerStore.class)).findByIdForUpdate(f.s.siteId(),f.s.customerId())).thenReturn(Optional.of(Customer.create(f.s.customerId(),f.s.siteId(),null,null,f.now)));
        when(((LockPaymentAccountPort)dependencies.get(LockPaymentAccountPort.class)).findByIdForUpdate(f.account.id())).thenReturn(Optional.of(f.account));when(((SubscriptionPolicyPort)dependencies.get(SubscriptionPolicyPort.class)).load(f.s.siteId())).thenReturn(new SubscriptionPolicy(f.s.siteId(),1,true,3,false,false,false,Set.of()));
        when(proofs.setup(f.account.id(),f.op.id())).thenReturn(Optional.of(new SubscriptionBillingProofStore.Setup(f.op.id(),f.s.id(),f.account.id(),UUID.randomUUID(),"seti_owned",null)));
        var claim=new SubscriptionInboxPort.Claim(UUID.randomUUID(),"owned");when(inbox.lock(eq(claim),any())).thenReturn(new SubscriptionProviderEnvelope(f.account.provider(),f.account.id(),"evt_owned","seti_owned","SUBSCRIPTION_SETUP_CHANGED",f.now,f.now));
        var command=new Command(f.account,operation,f.s.billingPrice(),null,"sub_owned",null,null,true,false);var fence=new InboxSetupFence(claim);
        assertThatCode(()->service.requireCurrent(command,fence)).doesNotThrowAnyException();
        when(inbox.lock(eq(claim),any())).thenReturn(new SubscriptionProviderEnvelope(f.account.provider(),f.account.id(),"evt_other","seti_other","SUBSCRIPTION_SETUP_CHANGED",f.now,f.now));
        assertThatThrownBy(()->service.requireCurrent(command,fence)).isInstanceOf(RuntimeException.class);
    }
}
