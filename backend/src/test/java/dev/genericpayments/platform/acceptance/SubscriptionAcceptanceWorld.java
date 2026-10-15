package dev.genericpayments.platform.acceptance;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.application.service.CustomerSessionService;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.fake.FakeSubscriptionQueueRelay;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import dev.genericpayments.platform.webhooks.application.service.SubscriptionInboxWorker;
import io.cucumber.spring.ScenarioScope;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Per-scenario real application ports. SQL only provisions owned catalog fixtures or inspects persisted effects. */
@Component @ScenarioScope
public class SubscriptionAcceptanceWorld {
    static final String ORIGIN = "https://subscription-acceptance.test";
    private final ApplicationContext app;
    private final JdbcTemplate jdbc;
    SiteId site;
    Customer customer;
    PaymentAccount account;
    PriceId price;
    SubscriptionActor actor;
    SubscriptionOperationResult result;
    Subscription.Period originalTrial;
    Subscription.Period originalPeriod;
    SubscriptionPriceTerms originalPrice;
    PriceId target;
    Instant graceDeadline;
    Snapshot captured;
    private final Map<String, ProductId> products = new HashMap<>();
    public SubscriptionAcceptanceWorld(ApplicationContext app, JdbcTemplate jdbc) { this.app=app; this.jdbc=jdbc; }
    // Cucumber injects a scoped proxy. Mutable scenario state must cross a method boundary.
    public void selectPrice(PriceId value) { price=value; }
    public void rememberTrial() { originalTrial=subscription().trial(); }
    public Subscription.Period originalTrial() { return originalTrial; }
    public Subscription.Period originalPeriod() { return originalPeriod; }
    public SubscriptionPriceTerms originalPrice() { return originalPrice; }
    public Instant graceDeadline() { return graceDeadline; }
    public PaymentAccount account() { return account; }
    public SubscriptionOperationResult result() { return result; }
    public void selectResult(SubscriptionOperationResult value) { result=value; }
    public PriceId targetPrice() { return target; }
    public Snapshot captured() { return captured; }
    <T> T bean(Class<T> type) { return app.getBean(type); }
    Instant now() { return bean(Clock.class).instant(); }
    String key() { return UUID.randomUUID().toString(); }
    public void initialize() {
        site=new SiteId(UUID.randomUUID()); var accountId=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'subscription-acceptance','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",accountId);
        jdbc.update("insert into sites values (?,'subscription-acceptance','Subscriptions',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site.value(),accountId);
        jdbc.update("insert into site_allowed_origins values (?,?)",site.value(),ORIGIN);
        jdbc.update("insert into site_authentication_modes values (?,'SITE_TOKEN')",site.value());
        customer=bean(CustomerStore.class).save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,now()));
        bean(SubscriptionPolicyPort.class).save(new SubscriptionPolicy(site,0,true,3,true,true,false,Set.of(ORIGIN+"/")),0);
        account=bean(LoadPaymentAccountPort.class).findById(new PaymentAccountId(accountId)).orElseThrow();
        authenticate();
    }
    void authenticate() {
        var sessions=bean(CustomerSessionService.class);
        actor=new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site,customer.id(),ORIGIN).rawToken(),ORIGIN));
    }
    SubscriptionCommandContext context(String key) { return new SubscriptionCommandContext(actor,site,customer.id(),ORIGIN,key,"subscription-acceptance"); }
    public PriceId price(String product, String interval, int trialDays, String methodPolicy, long amount) {
        var productId=products.computeIfAbsent(product,name->{
            var id=new ProductId(UUID.randomUUID());
            jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,?,?,'ACTIVE',?,?)",id.value(),site.value(),name,name,Timestamp.from(now()),Timestamp.from(now()));
            return id;
        });
        var id=new PriceId(UUID.randomUUID());
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,?,1,'SUBSCRIPTION',?,'EUR',?,?,?,'NONE','PUBLISHED',?,?,?)",id.value(),site.value(),productId.value(),"p-"+id.value(),amount,interval,trialDays,methodPolicy,Timestamp.from(now()),Timestamp.from(now()),Timestamp.from(now()));
        return id;
    }
    public void create() {
        result=bean(CreateSubscriptionUseCase.class).create(new CreateSubscriptionUseCase.Command(context(key()),price,true));
    }
    public void createPaid(String product,String interval) {
        price=price(product,interval,0,"OPTIONAL_DURING_TRIAL",2000);create();drainInbox();
        originalPeriod=subscription().currentPeriod();originalPrice=subscription().entitledPrice();captured=view();
    }
    public void startTrial(String methodPolicy,boolean hasMethod) {
        price=price("trial", "MONTH",7,methodPolicy,2000);create();
        if(hasMethod && result.nextAction()!=null) confirmOriginalAction();
        drainInbox();originalTrial=subscription().trial();
    }
    Subscription subscription() { return bean(SubscriptionStore.class).find(site,result.subscriptionId()).orElseThrow(); }
    Snapshot view() { return bean(SubscriptionProviderRegistry.class).forAccount(account).retrieve(new Lookup(account,result.subscriptionId(),subscription().providerReference(),null)); }
    public void advance(Instant at) {
        PostgresIntegrationTest.TestClock.set(at);bean(FakeSubscriptionControlPort.class).advance(account.id(),at);drainInbox();authenticate();
    }
    public void drainInbox() {
        bean(FakeSubscriptionQueueRelay.class).runOnce();bean(SubscriptionInboxWorker.class).runOnce();
        assertThat(count("provider_inbox", "event_family='SUBSCRIPTION' and status<>'PROCESSED'")).as("all owned subscription facts processed").isZero();
    }
    public AccessDecision access() { return new SubscriptionAccessPolicy().evaluate(subscription(),now()); }
    public long paymentCount() { return count("payments","source='SUBSCRIPTION' and site_id='"+site.value()+"'"); }
    public long eventCount(SubscriptionEvent.Type type) { return count("subscription_events","subscription_id='"+result.subscriptionId()+"' and event_type='"+type.name()+"'"); }
    long count(String table,String where) { return jdbc.queryForObject("select count(*) from "+table+" where "+where,Long.class); }
    public void failRenewal() {
        bean(FakeSubscriptionControlPort.class).scenario(account.id(),result.subscriptionId(),FakeSubscriptionControlPort.Scenario.DECLINE);
        advance(subscription().currentPeriod().end());graceDeadline=subscription().debt().graceDeadline();
    }
    public void recover() {
        bean(FakeSubscriptionControlPort.class).scenario(account.id(),result.subscriptionId(),FakeSubscriptionControlPort.Scenario.SUCCESS);
        var command=new RecoverSubscriptionUseCase.Command(context(key()),result.subscriptionId(),subscription().debt().billingRecordId());
        var first=bean(RecoverSubscriptionUseCase.class).recover(command);drainInbox();
        assertThat(bean(RecoverSubscriptionUseCase.class).recover(command).operationId()).isEqualTo(first.operationId());drainInbox();
    }
    public void cancelNow() {
        bean(CancelSubscriptionUseCase.class).cancel(new CancelSubscriptionUseCase.Command(context(key()),result.subscriptionId(),CancelSubscriptionUseCase.Mode.IMMEDIATE,result.subscriptionId()));drainInbox();
    }
    void confirmOriginalAction() {
        var action=bean(GetSubscriptionActionUseCase.class).get(new GetSubscriptionActionUseCase.Query(context(null),result.subscriptionId(),result.operationId()));
        bean(ConfirmSubscriptionActionUseCase.class).confirm(new ConfirmSubscriptionActionUseCase.Command(context(key()),result.subscriptionId(),result.operationId(),action.secret()));
    }
    void setupMethod() {
        bean(FakeSubscriptionControlPort.class).scenario(account.id(),result.subscriptionId(),FakeSubscriptionControlPort.Scenario.SUCCESS);
        var setup=bean(SetupSubscriptionMethodUseCase.class).setup(new SetupSubscriptionMethodUseCase.Command(context(key()),result.subscriptionId(),true));
        var action=bean(GetSubscriptionActionUseCase.class).get(new GetSubscriptionActionUseCase.Query(context(null),result.subscriptionId(),setup.operationId()));
        bean(ConfirmSubscriptionActionUseCase.class).confirm(new ConfirmSubscriptionActionUseCase.Command(context(key()),result.subscriptionId(),setup.operationId(),action.secret()));drainInbox();
    }
    void resume() { bean(ResumeSubscriptionUseCase.class).resume(new ResumeSubscriptionUseCase.Command(context(key()),result.subscriptionId(),true)); }
    PriceId target(long amount,PlanTransition.Direction direction) {
        target=price("paid","MONTH",0,"OPTIONAL_DURING_TRIAL",amount);
        bean(SubscriptionPolicyPort.class).saveTransition(new PlanTransition(site,subscription().productId(),subscription().billingPrice().priceId(),target,direction,true,bean(SubscriptionPolicyPort.class).load(site).version()));
        return target;
    }
    SubscriptionQuote quote(ChangeTiming timing) { return bean(PreviewSubscriptionChangeUseCase.class).preview(new PreviewSubscriptionChangeUseCase.Query(context(null),result.subscriptionId(),target,timing)); }
    void change(SubscriptionQuote quote) { bean(ChangeSubscriptionPlanUseCase.class).change(new ChangeSubscriptionPlanUseCase.Command(context(key()),result.subscriptionId(),quote.id()));drainInbox(); }
    void extendGrace() {var p=bean(SubscriptionPolicyPort.class).load(site);bean(SubscriptionPolicyPort.class).save(new SubscriptionPolicy(site,p.version(),true,30,true,true,false,Set.of(ORIGIN+"/")),p.version());}
    void portal() { assertThat(bean(CreateSubscriptionPortalUseCase.class).create(new CreateSubscriptionPortalUseCase.Command(context(key()),result.subscriptionId(),ORIGIN+"/"))).isNotNull(); }
    void repeatFacts(Snapshot snapshot) {
        bean(SubscriptionTransactionPort.class).required(()->{
            bean(ApplySubscriptionSnapshotUseCase.class).apply(new ApplySubscriptionSnapshotUseCase.Command(account,result.subscriptionId(),snapshot,SubscriptionEvent.Source.PROVIDER_WEBHOOK,key(),"subscription-acceptance"));return null;
        });
    }

    void initialOutcome(String outcome) {
        price=price("initial","MONTH",0,"OPTIONAL_DURING_TRIAL",2000);
        var commands=bean(dev.genericpayments.platform.subscriptions.application.service.SubscriptionCommandTransaction.class);
        var reserved=commands.reserveCreate(new CreateSubscriptionUseCase.Command(context(key()),price,true));
        bean(FakeSubscriptionControlPort.class).scenario(account.id(),reserved.subscription().id(),FakeSubscriptionControlPort.Scenario.valueOf(outcome));
        var reply=bean(SubscriptionProviderRegistry.class).forAccount(account).create(new Command(account,reserved.operation(),reserved.subscription().billingPrice(),null,null,null,null,true,false));
        result=commands.recordReply(reserved.operation().id(),reply);drainInbox();
    }
    SubscriptionOperation operation() {return bean(SubscriptionTransactionPort.class).required(()->bean(SubscriptionOperationStore.class).lock(result.operationId()));}
    void restartUnknown() throws Exception {
        var original=operation();assertThat(original.status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);
        var receipt=jdbc.queryForObject("select reply::text from fake_subscription_operations where operation_key=?",String.class,original.providerOperationKey());
        long process=dev.genericpayments.platform.subscriptions.application.service.SubscriptionRestartHarness.run(app,now().plusSeconds(1),"resolve-success",original.id());
        assertThat(process).isNotEqualTo(ProcessHandle.current().pid());
        PostgresIntegrationTest.TestClock.set(now().plusSeconds(1));authenticate();drainInbox();
        assertThat(operation().providerOperationKey()).isEqualTo(original.providerOperationKey());
        assertThat(operation().status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(jdbc.queryForObject("select reply::text from fake_subscription_operations where operation_key=?",String.class,original.providerOperationKey())).isEqualTo(receipt);
        assertThat(count("fake_subscription_operations","true")).isEqualTo(1);
    }
    void refund() {
        var id=new dev.genericpayments.platform.payments.domain.PaymentId(jdbc.queryForObject("select id from payments where site_id=?",UUID.class,site.value()));
        var refund=bean(dev.genericpayments.platform.payments.application.service.RefundCoordinator.class).create(new dev.genericpayments.platform.payments.application.port.in.CreateRefundCommand(site,id,UUID.randomUUID(),2000,"EUR","REQUESTED_BY_CUSTOMER",null,key(),"subscription-acceptance"));
        bean(dev.genericpayments.platform.payments.application.service.ProviderInboxService.class).receive(new dev.genericpayments.platform.payments.application.model.ProviderEventEnvelope(account.provider(),account.id(),key(),refund.providerRefundReference(),dev.genericpayments.platform.payments.domain.PaymentEventType.REFUND_SUCCEEDED,now(),now(),Map.of()));
        bean(dev.genericpayments.platform.payments.application.service.ProviderInboxWorker.class).runOnce();
        assertThat(bean(dev.genericpayments.platform.payments.application.port.out.LoadPaymentPort.class).findById(site,id).orElseThrow().status()).isEqualTo(dev.genericpayments.platform.payments.domain.PaymentStatus.REFUNDED);
        assertThat(subscription().cancellation()).isNull();
    }
    void foreignAuthority() {
        var foreign=new SiteId(UUID.randomUUID());
        assertThatThrownBy(()->bean(QuerySubscriptionsUseCase.class).get(actor,foreign,result.subscriptionId())).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
        var context=new SubscriptionCommandContext(actor,foreign,customer.id(),ORIGIN,key(),"subscription-acceptance");
        assertThatThrownBy(()->bean(CancelSubscriptionUseCase.class).cancel(new CancelSubscriptionUseCase.Command(context,result.subscriptionId(),CancelSubscriptionUseCase.Mode.IMMEDIATE,result.subscriptionId()))).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
        assertThat(count("fake_subscription_operations","true")).isEqualTo(1);
    }
    void oldScopes() {
        // Persist a real legacy session rather than trusting a forged context or auto-expanding scopes.
        var legacy=CustomerSession.create(new CustomerSessionId(UUID.randomUUID()),site,customer.id(),ORIGIN,"legacyacceptancesession","v1",Set.of(CustomerSessionScope.CUSTOMER_READ,CustomerSessionScope.CHECKOUT_CREATE,CustomerSessionScope.IDENTITY_LINK),now(),now().plusSeconds(1800));
        bean(dev.genericpayments.platform.customers.application.port.out.CustomerSessionStore.class).save(legacy);
        var old=new SubscriptionActor.Customer(new dev.genericpayments.platform.customers.application.model.CustomerSessionContext(legacy.id(),site,customer.id(),ORIGIN,legacy.scopes(),legacy.expiresAt()));
        assertThatThrownBy(()->bean(QuerySubscriptionsUseCase.class).get(old,site,result.subscriptionId())).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
        assertThatThrownBy(()->bean(CancelSubscriptionUseCase.class).cancel(new CancelSubscriptionUseCase.Command(new SubscriptionCommandContext(old,site,customer.id(),ORIGIN,key(),"old-scope"),result.subscriptionId(),CancelSubscriptionUseCase.Mode.IMMEDIATE,result.subscriptionId()))).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
        assertThat(bean(QuerySubscriptionsUseCase.class).get(actor,site,result.subscriptionId())).isNotNull();
        assertThat(count("fake_subscription_operations","true")).isEqualTo(1);
    }
    dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase.Command anonymizeCommand() {
        return new dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase.Command(site,customer.id(),customer.id().value(),key(),"ADMIN",UUID.randomUUID().toString(),"subscription-acceptance","127.0.0.1");
    }
    void anonymizationRace() throws Exception {
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var started=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(()->bean(SubscriptionTransactionPort.class).required(()->{
                bean(SubscriptionLifecycleLockPort.class).lockActive(site,customer.id());locked.countDown();
                try {if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Lifecycle gate timeout");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                return subscription();
            }));
            assertThat(locked.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->{started.countDown();return bean(dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase.class).anonymize(anonymizeCommand());});
            assertThat(started.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();release.countDown();first.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThatThrownBy(()->second.get(10,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
            assertThat(bean(CustomerStore.class).findById(site,customer.id()).orElseThrow().status()).isEqualTo(CustomerStatus.ACTIVE);
        } finally {release.countDown();}
    }
    void anonymizeTerminal() {
        assertThat(bean(dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase.class).anonymize(anonymizeCommand()).status()).isEqualTo(CustomerStatus.ANONYMIZED);
        assertThat(paymentCount()).isEqualTo(1);assertThat(subscription().billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
    }
    void validateMigration() {
        var before=subscription();var cash=jdbc.queryForList("select * from payments where site_id=?",site.value());
        var flyway=bean(org.flywaydb.core.Flyway.class);flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(Arrays.stream(flyway.info().applied()).map(i->i.getVersion().toString()).toList()).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,23).mapToObj(Integer::toString).toList());
        assertThat(subscription()).isEqualTo(before);assertThat(jdbc.queryForList("select * from payments where site_id=?",site.value())).isEqualTo(cash);
    }
    void callbacks() {bean(dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort.class).configure(site.value(),"https://example.test/callback",true);}
    void signedCallbacks() throws Exception {
        var outbox=bean(dev.genericpayments.platform.webhooks.application.port.out.OutboxPort.class);
        var claims=outbox.claimAutomatic("acceptance-signed",10,now(),Duration.ofSeconds(30));assertThat(claims).hasSize(2);
        var payload=bean(dev.genericpayments.platform.webhooks.application.port.out.CallbackPayloadPort.class);
        var encryption=bean(dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.class);
        var versions=new HashSet<Integer>();var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        for(var claim:claims) {
            byte[] secret=Base64.getUrlDecoder().decode(encryption.decrypt(claim.secret()));
            try {
                var canonical=payload.bytes(claim);var node=mapper.readTree(canonical);int version=node.path("schema_version").intValue();versions.add(version);
                var fields=new HashSet<String>();node.fieldNames().forEachRemaining(fields::add);
                assertThat(fields).isEqualTo(version==3?Set.of("schema_version","event_id","event_type","site_id","payment_id","external_order_id","payment_status","amount_minor","currency","occurred_at","subscription_id","billing_record_id","customer_id","product_id","billing_kind","price_id","price_version","period_start","period_end"):Set.of("schema_version","event_id","event_type","site_id","subscription_id","customer_id","product_id","revision","state","access_allowed","access_valid_until","occurred_at","billing_record_id","payment_id"));
                assertThat(node.path("subscription_id").asText()).isEqualTo(result.subscriptionId().toString());
                var delivery=new dev.genericpayments.platform.webhooks.application.service.CallbackDeliveryService(outbox,encryption,payload,(endpoint,headers,body)->{
                    assertThat(body).isEqualTo(canonical);long timestamp=Long.parseLong(headers.get("Payment-Timestamp"));
                    assertThat(timestamp).isEqualTo(now().getEpochSecond());
                    assertThat(headers.get("Payment-Signature")).isEqualTo("v1="+new dev.genericpayments.platform.webhooks.application.service.CallbackSigner().sign(secret,timestamp,body));
                    return dev.genericpayments.platform.webhooks.application.port.out.CallbackHttpPort.Result.success(204);
                },bean(Clock.class));
                delivery.deliver(claim);
                outbox.enqueue(claim.eventId(),dev.genericpayments.platform.webhooks.application.port.out.OutboxPort.Mode.CURRENT_CONFIG_RETRY,now());
                var replay=outbox.claimManual("acceptance-replay",1,now(),Duration.ofSeconds(30)).getFirst();assertThat(payload.bytes(replay)).isEqualTo(canonical);delivery.deliver(replay);
            } finally {Arrays.fill(secret,(byte)0);}
        }
        assertThat(versions).containsExactlyInAnyOrder(3,4);assertThat(count("outbox_events","status='DELIVERED'")).isEqualTo(2);
    }
}
