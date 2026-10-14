package dev.genericpayments.platform.subscriptions.application.service;

import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.application.service.CustomerSessionService;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@org.springframework.test.context.TestPropertySource(properties="payment.telemetry.site-keys[0]=facts")
public abstract class SubscriptionFactFixture extends PostgresIntegrationTest {
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected CustomerStore customers;
    @Autowired protected CustomerSessionService sessions;
    @Autowired protected SubscriptionPolicyPort policies;
    @Autowired protected SubscriptionStore subscriptions;
    @Autowired protected SubscriptionOperationStore operations;
    @Autowired protected TrialEligibilityStore trials;
    @Autowired protected SubscriptionTransactionPort tx;
    @Autowired protected SubscriptionProviderRegistry providers;
    @Autowired protected CreateSubscriptionUseCase create;
    @Autowired protected SubscriptionCommandTransaction commands;
    @Autowired protected ApplySubscriptionSnapshotUseCase facts;
    @Autowired protected LoadPaymentAccountPort accounts;
    protected SiteId site;protected Customer customer;protected PaymentAccount account;protected PriceId price;protected SubscriptionActor actor;
    @BeforeEach void factFixture() {
        clearDatabase();site=new SiteId(UUID.randomUUID());UUID aid=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'facts','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",aid);
        jdbc.update("insert into sites values (?,'facts','Facts',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site.value(),aid);
        jdbc.update("insert into site_allowed_origins values (?,'https://facts.test')",site.value());
        jdbc.update("insert into site_authentication_modes values (?,'SITE_TOKEN')",site.value());
        customer=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        policies.save(new SubscriptionPolicy(site,0,true,3,false,false,false,Set.of()),0);
        actor=new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site,customer.id(),"https://facts.test").rawToken(),"https://facts.test"));
        account=accounts.findById(new PaymentAccountId(aid)).orElseThrow();price=price(0);
    }
    protected PriceId price(int days) {
        UUID product=UUID.randomUUID(),id=UUID.randomUUID();
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,?,'Plan','ACTIVE',?,?)",product,site.value(),"p-"+product,Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,?,1,'SUBSCRIPTION',2000,'EUR','MONTH',?,'OPTIONAL_DURING_TRIAL','NONE','PUBLISHED',?,?,?)",id,site.value(),product,"p-"+id,days,Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME));return new PriceId(id);
    }
    protected CreateSubscriptionUseCase.Command command(String key){return new CreateSubscriptionUseCase.Command(new SubscriptionCommandContext(actor,site,customer.id(),"https://facts.test",key,"task7"),price,true);}
    protected SubscriptionOperationResult start(){return create.create(command(UUID.randomUUID().toString()));}
    protected Subscription sub(UUID id){return subscriptions.find(site,id).orElseThrow();}
    protected SubscriptionOperation op(UUID id){return tx.required(()->operations.lock(id));}
    protected Snapshot retrieve(SubscriptionOperationResult r){var o=op(r.operationId());return providers.forAccount(account).retrieve(new Lookup(account,r.subscriptionId(),o.providerReference(),o.providerOperationKey()));}
    protected void apply(UUID id,Snapshot snapshot,String key){tx.required(()->{facts.apply(new ApplySubscriptionSnapshotUseCase.Command(account,id,snapshot,SubscriptionEvent.Source.PROVIDER_WEBHOOK,key,"task7"));return null;});}
    protected int count(String table){return jdbc.queryForObject("select count(*) from "+table,Integer.class);}
}
