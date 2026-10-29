package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.util.*;
import java.lang.reflect.Proxy;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class SubscriptionBillingTimeServiceTest {
    private static final Instant UTC=Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant BILLING=UTC.plus(Duration.ofDays(35));
    @Test void exactExpiryUsesBillingEligibilityAndUtcReceiptAndEnqueue() {
        var base=SubscriptionFixtures.active(UTC,UTC.plus(Duration.ofDays(30)));
        var period=new Subscription.Period(base.paidThrough(),base.paidThrough().plus(Duration.ofDays(30)));
        var s=base.renewalFailed(UUID.randomUUID(),period,3,base.paidThrough());
        var bill=new BillingRecord(s.debt().billingRecordId(),s.siteId(),s.id(),s.paymentAccountId(),"invoice",BillingRecord.Kind.RENEWAL,s.billingPrice(),null,period,1000,"EUR",BillingRecord.Settlement.OPEN,0,UTC,UTC);
        var appended=new ArrayList<SubscriptionEvent>();var queued=new ArrayList<Instant>();
        var worker=new SubscriptionDeadlineWorker(inert(SubscriptionSweepPort.class),port(SubscriptionStore.class,(m,a)->{assertThat(m).isEqualTo("lock");return s;}),port(BillingRecordStore.class,(m,a)->bill),transaction(),port(SubscriptionEventStore.class,(m,a)->{appended.add((SubscriptionEvent)a[0]);return true;}),(sub,event,access,enqueued)->{assertThat(access.evaluatedAt()).isEqualTo(BILLING);queued.add(enqueued);},inert(SubscriptionWorkerConfigurationPort.class),port(SubscriptionTelemetryPort.class,(m,a)->null),Clock.fixed(UTC,ZoneOffset.UTC),isolated(s,BILLING));
        worker.expire(new SubscriptionSweepPort.Claim(s.siteId(),s.id(),"exact",UUID.randomUUID(),s.revision()));
        assertThat(appended).hasSize(1);
        assertThat(appended.getFirst().occurredAt()).isEqualTo(BILLING);
        assertThat(appended.getFirst().receivedAt()).isEqualTo(UTC);
        assertThat(queued).containsExactly(UTC);
    }
    @Test void globalDeadlineAndListAndOffersRefuseBeforeSelectionEvenAtEqualTime() {
        var s=SubscriptionFixtures.active(UTC,UTC.plus(Duration.ofDays(30)));
        var port=isolated(s,UTC);
        var worker=new SubscriptionDeadlineWorker(inert(SubscriptionSweepPort.class),inert(SubscriptionStore.class),inert(BillingRecordStore.class),transaction(),inert(SubscriptionEventStore.class),inert(SubscriptionCallbackPort.class),inert(SubscriptionWorkerConfigurationPort.class),inert(SubscriptionTelemetryPort.class),Clock.fixed(UTC,ZoneOffset.UTC),port);
        assertThatThrownBy(worker::runOnce).hasMessage("ISOLATED_BILLING_UNSCOPED");
        var query=query(s,port,inert(SubscriptionReadPort.class));
        for(var customer:Arrays.asList(null,s.customerId()))assertThatThrownBy(()->query.list(new dev.genericpayments.platform.subscriptions.application.port.in.QuerySubscriptionsUseCase.Query(null,s.siteId(),customer,null,null,10,null))).hasMessage("ISOLATED_BILLING_UNSCOPED");
        assertThatThrownBy(()->query.offers(null,s.siteId(),s.customerId(),s.productId())).hasMessage("ISOLATED_BILLING_UNSCOPED");
    }
    @Test void exactQueryUsesIsolatedTimeAndSuppressesActionsEvenAtEqualTime() {
        var s=SubscriptionFixtures.active(UTC,UTC.plus(Duration.ofDays(30)));
        var reads=port(SubscriptionReadPort.class,(m,a)->switch(m){case "current"->Optional.of(s);case "unresolvedOperation"->Optional.empty();default->throw new AssertionError(m);});
        var equal=query(s,isolated(s,UTC),reads).get(null,s.siteId(),s.id());
        assertThat(equal.permittedActions()).isEmpty();assertThat(equal.access().allowed()).isTrue();
        var future=query(s,isolated(s,BILLING),reads);
        assertThat(future.get(null,s.siteId(),s.id()).access().allowed()).isFalse();
        assertThat(future.access(null,s.siteId(),s.customerId(),s.productId()).evaluatedAt()).isEqualTo(BILLING);
        var ordinary=query(s,SubscriptionBillingTimePort.ordinary(),reads).get(null,s.siteId(),s.id());
        assertThat(ordinary.permittedActions()).contains(dev.genericpayments.platform.subscriptions.application.model.SubscriptionView.PermittedAction.SETUP_METHOD);
        assertThat(ordinary.access().evaluatedAt()).isEqualTo(UTC);
    }
    @Test void exactScopeRequiresAccountSiteAndCustomerAndIdentityAdapterPreservesFallback() {
        var s=SubscriptionFixtures.active(UTC,UTC.plus(Duration.ofDays(30)));var time=isolated(s,BILLING);
        assertThat(time.sample(s.paymentAccountId(),s.siteId(),s.customerId(),UTC).billingAt()).isEqualTo(BILLING);
        assertThat(time.sample(new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(UUID.randomUUID()),s.siteId(),s.customerId(),UTC)).isEqualTo(new SubscriptionBillingTimePort.Sample(UTC,SubscriptionBillingTimePort.Mode.ORDINARY));
        assertThat(time.sample(s.paymentAccountId(),new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),s.customerId(),UTC).billingAt()).isEqualTo(UTC);
        assertThat(time.sample(s.paymentAccountId(),s.siteId(),new dev.genericpayments.platform.customers.domain.CustomerId(UUID.randomUUID()),UTC).billingAt()).isEqualTo(UTC);
        var ordinary=new dev.genericpayments.platform.subscriptions.adapter.out.integration.OrdinarySubscriptionBillingTimeAdapter();
        assertThat(ordinary.sample(s.paymentAccountId(),s.siteId(),s.customerId(),BILLING)).isEqualTo(new SubscriptionBillingTimePort.Sample(BILLING,SubscriptionBillingTimePort.Mode.ORDINARY));
        ordinary.requireUnscopedAllowed(null,null);
    }
    private SubscriptionQueryService query(Subscription s,SubscriptionBillingTimePort time,SubscriptionReadPort reads){
        var account=dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount.create(s.paymentAccountId(),"account",dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.FAKE,dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountMode.SANDBOX,"fake","ref");
        var policy=new SubscriptionPolicy(s.siteId(),0,true,3,false,false,false,Set.of());
        // An empty catalog proves ordinary actions are still computed without invoking providers.
        return new SubscriptionQueryService(transaction(),port(SubscriptionAuthorityPort.class,(m,a)->null),port(SubscriptionStore.class,(m,a)->Optional.of(s)),reads,port(SubscriptionPolicyPort.class,(m,a)->policy),port(SubscriptionCatalogPort.class,(m,a)->List.of()),inert(TrialEligibilityStore.class),port(dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort.class,(m,a)->Optional.of(account)),a->port(SubscriptionProviderPort.class,(m,args)->new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Capabilities(false,false,true,false)),Clock.fixed(UTC,ZoneOffset.UTC),time);
    }
    @Test void staleRevisionAndSettledDebtCannotEmitExpiry() {
        var base=SubscriptionFixtures.active(UTC,UTC.plus(Duration.ofDays(30)));
        var period=new Subscription.Period(base.paidThrough(),base.paidThrough().plus(Duration.ofDays(30)));
        var s=base.renewalFailed(UUID.randomUUID(),period,3,base.paidThrough(),UTC);
        var settled=new BillingRecord(s.debt().billingRecordId(),s.siteId(),s.id(),s.paymentAccountId(),"invoice",BillingRecord.Kind.RENEWAL,s.billingPrice(),null,period,1000,"EUR",BillingRecord.Settlement.CONFIRMED_CASH,0,UTC,UTC);
        for(boolean stale:List.of(true,false)){
            var worker=new SubscriptionDeadlineWorker(inert(SubscriptionSweepPort.class),port(SubscriptionStore.class,(m,a)->s),stale?inert(BillingRecordStore.class):port(BillingRecordStore.class,(m,a)->settled),transaction(),inert(SubscriptionEventStore.class),inert(SubscriptionCallbackPort.class),inert(SubscriptionWorkerConfigurationPort.class),inert(SubscriptionTelemetryPort.class),Clock.fixed(UTC,ZoneOffset.UTC),isolated(s,BILLING));
            worker.expire(new SubscriptionSweepPort.Claim(s.siteId(),s.id(),"exact",UUID.randomUUID(),s.revision()-(stale?1:0)));
        }
    }
    static SubscriptionBillingTimePort isolated(Subscription owner,Instant at){return new SubscriptionBillingTimePort(){
        public Sample sample(dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId account,dev.genericpayments.platform.sites.domain.SiteId site,dev.genericpayments.platform.customers.domain.CustomerId customer,Instant now){return owner.paymentAccountId().equals(account)&&owner.siteId().equals(site)&&owner.customerId().equals(customer)?new Sample(at,Mode.ISOLATED):new Sample(now,Mode.ORDINARY);}
        public void requireUnscopedAllowed(dev.genericpayments.platform.sites.domain.SiteId site,dev.genericpayments.platform.customers.domain.CustomerId customer){if(site==null||owner.siteId().equals(site)&&(customer==null||owner.customerId().equals(customer)))throw new IllegalStateException("ISOLATED_BILLING_UNSCOPED");}
    };}
    static SubscriptionTransactionPort transaction(){return new SubscriptionTransactionPort(){public <T>T required(java.util.function.Supplier<T> work){return work.get();}public <T>T requiresNew(java.util.function.Supplier<T> work){return work.get();}public boolean active(){return true;}};}
    static <T>T inert(Class<T> type){return port(type,(m,a)->{throw new AssertionError("Unexpected port call: "+type.getSimpleName()+"."+m);});}
    static <T>T port(Class<T> type,BiFunction<String,Object[],Object> call){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->call.apply(m.getName(),a)));}
}
