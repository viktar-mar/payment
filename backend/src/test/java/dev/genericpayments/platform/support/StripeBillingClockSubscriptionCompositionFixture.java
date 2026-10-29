package dev.genericpayments.platform.support;

import com.google.gson.JsonObject;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.customers.application.service.CustomerSessionService;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingClockSubscriptionStore;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionCommandTransaction;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

/** Hosted application composition; only catalog/owner prerequisites use SQL fixture rows.
 * Subscription and CREATE are exclusively produced by real reserveCreate. */
@Import(StripeBillingClockSubscriptionCompositionFixture.OwnedTimeConfiguration.class)
public abstract class StripeBillingClockSubscriptionCompositionFixture extends StripeBillingClockCustomerFixture {
    @Autowired protected SubscriptionCommandTransaction commands;
    @Autowired protected CustomerSessionService sessions;
    @Autowired protected SubscriptionPolicyPort policies;
    @Autowired protected StripeBillingClockSubscriptionStore owned;
    @Autowired protected SubscriptionObservationPort observations;
    @Autowired protected ApplySubscriptionSnapshotUseCase facts;
    @Autowired protected OwnedSubscriptionBillingTime billingTime;
    @Autowired protected StripeCredentialResolver credentials;
    @Autowired protected StripeClientFactory clients;
    protected StripeBillingClockSubscriptionAdapter ownedAdapter;
    protected CreateSubscriptionUseCase.Command createCommand;
    protected SubscriptionCommandTransaction.ReservedCreate reserved;
    protected ClockOwner owner;
    protected StripeBillingOwnedSubscriptionFixture graph;
    protected Instant began;
    protected int sdkCallbacks;

    @BeforeEach void prepareSubscriptionComposition() {
        began = Instant.now().minusSeconds(1);
        TestClock.set(Instant.now());
        billingTime.lastSample=null; billingTime.sampledTransaction=null; billingTime.sampledConnectionHolder=null;
        policies.save(new SubscriptionPolicy(site,0,true,3,false,false,false,Set.of()),0);
        var session = sessions.issue(site,customer,"https://shop.test");
        var actor = new SubscriptionActor.Customer(sessions.resolveRequired(session.rawToken(),"https://shop.test"));
        UUID product=UUID.randomUUID(), price=UUID.randomUUID();
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,'owned-plan','Owned plan','ACTIVE',now(),now())",product,site.value());
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,'owned-monthly',1,'SUBSCRIPTION',1000,'EUR','MONTH',0,'OPTIONAL_DURING_TRIAL','NONE','PUBLISHED',now(),now(),now())",price,site.value(),product);
        jdbc.update("insert into provider_product_mappings(payment_account_id,product_id,provider_product_id,created_at) values (?,?,'prod_owned',now())",billing.id().value(),product);
        jdbc.update("insert into provider_price_mappings(payment_account_id,price_id,provider_price_id,created_at) values (?,?,'price_owned',now())",billing.id().value(),price);
        createCommand=new CreateSubscriptionUseCase.Command(new SubscriptionCommandContext(actor,site,customer,"https://shop.test","owned-create","owned-request"),new PriceId(price),true);
        ownedAdapter=new StripeBillingClockSubscriptionAdapter(credentials,clients,verification,operations,clocks,owned,bindings,subscriptions,lifecycleOperations,Clock.systemUTC());
        // Transport callback is reinstalled for every official SDK request, including all graph reads.
        http.afterResponse=this::outsideTransaction;
    }
    private void outsideTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        sdkCallbacks++;
        if (reserved!=null && http.calls.getLast().url().getPath().equals("/v1/subscriptions") && http.calls.getLast().method().name().equals("POST")) {
            var claim=operations.find(billing.id(),reserved.operation().providerOperationKey()).orElseThrow();
            assertThat(claim.status()).isEqualTo(ProviderOperation.Status.DISPATCHING);
            assertThat(claim.firstDispatchedAt()).isBetween(began,Instant.now());
            assertThat(claim.leaseExpiresAt()).isEqualTo(claim.firstDispatchedAt().plusSeconds(60));
            assertThat(claim.retryBefore()).isEqualTo(claim.firstDispatchedAt().plus(Duration.ofHours(23)));
            assertThat(claim.firstDispatchedAt().getEpochSecond()).isNotIn(FROZEN,42L,PROVIDER_CREATED);
        }

        http.afterResponse=this::outsideTransaction;
    }
    protected Command reserve() {
        reserved=commands.reserveCreate(createCommand);
        assertThat(reserved.owner()).isTrue();
        assertThat(reserved.operation().status()).isEqualTo(SubscriptionOperation.Status.RESERVED);
        assertThat(reserved.subscription().revision()).isZero();
        graph=new StripeBillingOwnedSubscriptionFixture(); // Only JSON shape is reused; none of its doubles drive this flow.
        var metadata=new JsonObject();
        StripeBillingOwnership.metadata(billing,reserved.subscription(),reserved.operation()).forEach(metadata::addProperty);
        graph.remote.add("metadata",metadata);
        graph.price().getAsJsonObject("metadata").addProperty("local_price_id",reserved.subscription().billingPrice().priceId().value().toString());
        return new Command(billing,reserved.operation(),reserved.subscription().billingPrice(),null,null,null,null,true,false);
    }
    protected Reply createOwned() {
        var command=reserve();
        owner=admit(); createReady(owner); createCustomer(owner);
        http.reply(clockJson(owner)); http.reply(graph.remote.toString());
        var reply=ownedAdapter.createOwned(command,owner);
        assertThat(reply.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(reply.action()).isNull();
        commands.recordReply(reserved.operation().id(),reply);
        return reply;
    }
    protected Subscription current() { return subscriptions.find(site,reserved.subscription().id()).orElseThrow(); }
    protected SubscriptionOperation currentOperation() { return lifecycleOperations.find(site,reserved.subscription().id(),reserved.operation().id()).orElseThrow(); }
    protected Lookup lookup() { return new Lookup(billing,reserved.subscription().id(),"sub_owned",reserved.operation().providerOperationKey()); }
    protected Snapshot retrieve() {
        http.reply(clockJson(owner)); http.reply(customerJson("clock_owned")); http.reply(graph.remote.toString());
        http.reply(StripeBillingOwnedSubscriptionFixture.page(graph.invoice));
        http.reply(StripeBillingOwnedSubscriptionFixture.page(graph.line)); http.reply(StripeBillingOwnedSubscriptionFixture.page());
        return ownedAdapter.retrieveOwned(lookup(),owner);
    }
    protected ApplySubscriptionSnapshotUseCase.Fence fence() {
        var s=current();var o=currentOperation();
        return new ApplySubscriptionSnapshotUseCase.Fence(s.revision(),o.id(),o.version(),null,null,observations.captureFence(s));
    }
    protected void apply(Snapshot snapshot, ApplySubscriptionSnapshotUseCase.Fence fence) {
        TestClock.set(Instant.now());
        transaction.required(()->{
            var id=jdbc.queryForObject("select pg_current_xact_id()::text",String.class);
            var holder=TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            assertThat(holder).isNotNull();
            facts.apply(new ApplySubscriptionSnapshotUseCase.Command(billing,current().id(),snapshot,SubscriptionEvent.Source.RECONCILIATION,"owned-open","owned-request",fence));
            assertThat(billingTime.sampledConnectionHolder).isSameAs(holder);
            assertThat(billingTime.sampledTransaction).isEqualTo(id);
            return null;
        });
    }
    protected void assertNoSuccess() {
        var s=current();
        assertThat(s.billingStatus()).isEqualTo(Subscription.Status.INCOMPLETE);
        assertThat(s.entitledPrice()).isNull(); assertThat(s.paidThrough()).isNull();assertThat(s.trial()).isNull();
        assertThat(currentOperation().status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        assertThat(count("payments")).isZero(); assertThat(count("payment_events")).isZero();
        assertThat(count("subscription_events")).isZero(); assertThat(count("outbox_events")).isZero();
    }
    @TestConfiguration(proxyBeanMethods=false)
    public static class OwnedTimeConfiguration {
        @Bean @Primary OwnedSubscriptionBillingTime ownedSubscriptionTime(JdbcTemplate jdbc) { return new OwnedSubscriptionBillingTime(jdbc); }
    }
}
