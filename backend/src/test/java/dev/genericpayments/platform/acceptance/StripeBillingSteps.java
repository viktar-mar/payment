package dev.genericpayments.platform.acceptance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.google.gson.*;
import dev.genericpayments.platform.catalog.application.model.*;
import dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.application.service.CustomerSessionService;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock;
import dev.genericpayments.platform.support.StripeHttpFixture;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackPayloadPort;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackHttpPort;
import dev.genericpayments.platform.webhooks.application.port.out.OutboxPort;
import dev.genericpayments.platform.webhooks.application.service.CallbackDeliveryService;
import dev.genericpayments.platform.webhooks.application.service.CallbackSigner;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionPortalService;
import dev.genericpayments.platform.webhooks.application.service.SubscriptionInboxWorker;
import io.cucumber.java.After;
import io.cucumber.java.en.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** HOSTED ONLY: existing Cucumber application/SQL ports, official SDK and an owned loopback peer.
 * SQL provisions catalog fixtures and reads evidence; no normalized provider snapshots bypass the mapper. */
public class StripeBillingSteps {
    private static final String ORIGIN = "https://subscription-acceptance.test";
    private final ApplicationContext app;
    private final AcceptanceWorld world;
    private final JdbcTemplate jdbc;
    private final StripeAcceptanceConfiguration.FixtureFactory factory;
    private StripeHttpFixture http;
    private boolean attached;
    private SiteId site;
    private PaymentAccount account;
    private Customer customer;
    private ProductId product;
    private PriceId price;
    private SubscriptionActor actor;
    private SubscriptionOperationResult created, recovery, cancel, withdrawal;
    private CreateSubscriptionUseCase.Command createCommand;
    private RecoverSubscriptionUseCase.Command recoverCommand;
    private Instant start, end, grace;
    private JsonObject remote, remoteCustomer;
    private final List<InvoiceGraph> invoices = new ArrayList<>();
    private List<Long> evidence;
    private int beforeDenied, beforeFake;
    private UUID fakeSubscription;
    private PriceId upgradePrice;
    private SubscriptionQuote upgradeQuote;
    private ChangeSubscriptionPlanUseCase.Command upgradeCommand;
    private SubscriptionOperationResult upgraded;
    private JsonObject changeInvoice,creditLine,debitLine;
    private InvoiceGraph changeCash;
    private Instant planAnchor;
    private UUID portalOperation;
    private String portalReturn,portalNavigation;

    public StripeBillingSteps(ApplicationContext app, AcceptanceWorld world, JdbcTemplate jdbc,
            StripeAcceptanceConfiguration.FixtureFactory factory) {
        this.app=app; this.world=world; this.jdbc=jdbc; this.factory=factory;
    }
    private <T> T bean(Class<T> type) { return app.getBean(type); }
    private Instant now() { return bean(Clock.class).instant(); }
    private String key() { return UUID.randomUUID().toString(); }
    private SubscriptionCommandContext context(String key) {
        return new SubscriptionCommandContext(actor,site,customer.id(),ORIGIN,key,"stripe-billing-acceptance");
    }
    private Subscription subscription() { return bean(SubscriptionStore.class).find(site,created.subscriptionId()).orElseThrow(); }
    private SubscriptionOperation operation(UUID id) {
        return bean(SubscriptionTransactionPort.class).required(() -> bean(SubscriptionOperationStore.class).lock(id));
    }
    private long count(String table,String predicate,Object... args) {
        return jdbc.queryForObject("select count(*) from "+table+" where "+predicate,Long.class,args);
    }
    private long cash() { return count("payments","site_id=? and source='SUBSCRIPTION'",site.value()); }
    private AccessDecision access() { return new SubscriptionAccessPolicy().evaluate(subscription(),now()); }
    private void authenticate() {
        var sessions=bean(CustomerSessionService.class);
        actor=new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site,customer.id(),ORIGIN).rawToken(),ORIGIN));
    }
    @After("@stripe-billing") public void closeOwnedFixture() {
        if(attached) { factory.detach(); attached=false; }
        if(http!=null) { http.close(); http=null; }
    }
    private void identity() { http.enqueue("GET","/v1/account",200,"{\"id\":\"acct_billing\",\"object\":\"account\"}"); }
    private void respond(String method,String path,JsonObject body) { http.enqueue(method,path,200,body.toString()); }
    private static JsonObject json(String body) { return JsonParser.parseString(body).getAsJsonObject(); }
    private static String page(Collection<JsonObject> objects) {
        var page=new JsonObject();page.addProperty("object","list");page.addProperty("has_more",false);
        var data=new JsonArray();objects.forEach(data::add);page.add("data",data);return page.toString();
    }
    private void catalogFixture(int trialDays) {
        site=new SiteId(UUID.randomUUID());var accountId=UUID.randomUUID();product=new ProductId(UUID.randomUUID());price=new PriceId(UUID.randomUUID());
        jdbc.update("insert into payment_accounts values (?,'stripe-billing-acceptance','STRIPE','SANDBOX','ACTIVE','acct_billing','stripe-sandbox-default',now(),0)",accountId);
        jdbc.update("insert into sites values (?,'customer-one','Stripe Billing',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site.value(),accountId);
        jdbc.update("insert into site_allowed_origins values (?,?)",site.value(),ORIGIN);
        jdbc.update("insert into site_authentication_modes values (?,'SITE_TOKEN')",site.value());
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,'billing','Billing','ACTIVE',?,?)",product.value(),site.value(),Timestamp.from(now()),Timestamp.from(now()));
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,'billing-monthly',1,'SUBSCRIPTION',2000,'EUR','MONTH',?,'OPTIONAL_DURING_TRIAL','INCLUSIVE','PUBLISHED',?,?,?)",price.value(),site.value(),product.value(),trialDays,Timestamp.from(now()),Timestamp.from(now()),Timestamp.from(now()));
        account=bean(LoadPaymentAccountPort.class).findById(new PaymentAccountId(accountId)).orElseThrow();
        customer=bean(CustomerStore.class).save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,now()));
        bean(SubscriptionPolicyPort.class).save(new SubscriptionPolicy(site,0,true,3,true,false,false,Set.of()),0);
        bean(ProviderCatalogMappingPort.class).save(new ProviderCatalogMapping(account.id(),product,new ProviderProductRef("prod_billing"),price,new ProviderPriceRef("price_billing"),now()));
        bean(CallbackConfigurationPort.class).configure(site.value(),"http://127.0.0.1:8081/callback",true);
        authenticate();
    }
    @Given("an owned Stripe Billing subscription with {int} trial days")
    public void create(int trialDays) throws Exception {
        assertThat(trialDays).isIn(0,7);
        // Align this synthetic business fixture with ordinary provider UTC; security clocks stay untouched.
        TestClock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        http=new StripeHttpFixture();factory.attach(http);attached=true;catalogFixture(trialDays);
        start=now();end=start.plusSeconds(30L*86400);
        remoteCustomer=json("{\"id\":\"cus_billing\",\"object\":\"customer\",\"livemode\":false}");
        remote=subscriptionJson();
        identity();http.enqueue("POST","/v1/customers",200,call -> {
            var metadata=new JsonObject();for(String field:List.of("account_id","site_id","customer_id"))metadata.addProperty(field,call.form().get("metadata["+field+"]"));
            remoteCustomer.add("metadata",metadata);return remoteCustomer.toString();
        });
        if(trialDays>0) { identity();http.enqueue("GET","/v1/customers/cus_billing",200,ignored -> remoteCustomer.toString()); }
        identity();http.enqueue("POST","/v1/subscriptions",200,call -> {
            var metadata=new JsonObject();for(String field:List.of("account_id","site_id","customer_id","subscription_id","operation_id"))metadata.addProperty(field,call.form().get("metadata["+field+"]"));
            remote.add("metadata",metadata);
            long dispatched=jdbc.queryForObject("select floor(extract(epoch from first_dispatched_at))::bigint from provider_operations where operation_key=?",Long.class,call.header("Idempotency-Key"));
            remote.addProperty("created",dispatched);
            if(trialDays>0) {
                start=Instant.ofEpochSecond(dispatched);end=Instant.ofEpochSecond(Long.parseLong(call.form().get("trial_end")));
                remote.addProperty("status","trialing");remote.addProperty("trial_start",start.getEpochSecond());remote.addProperty("trial_end",end.getEpochSecond());
                remote.add("trial_settings",json("{\"end_behavior\":{\"missing_payment_method\":\"pause\"}}"));setPeriod(start,end);
            }
            return remote.toString();
        });
        createCommand=new CreateSubscriptionUseCase.Command(context(key()),price,true);
        created=bean(CreateSubscriptionUseCase.class).create(createCommand);
        assertThat(created.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        assertThat(subscription().providerReference()).isEqualTo("sub_billing");
    }
    private JsonObject subscriptionJson() {
        return json("""
            {"id":"sub_billing","object":"subscription","livemode":false,"customer":"cus_billing","status":"incomplete","currency":"eur",
             "collection_method":"charge_automatically","billing_mode":{"type":"classic"},"cancel_at_period_end":false,
             "payment_settings":{"payment_method_types":["card"],"save_default_payment_method":"on_subscription"},
             "items":{"object":"list","has_more":false,"data":[{"id":"si_billing","object":"subscription_item","subscription":"sub_billing","quantity":1,
              "current_period_start":%d,"current_period_end":%d,"price":{"id":"price_billing","object":"price","livemode":false,"product":"prod_billing","currency":"eur","unit_amount":2000,
              "tax_behavior":"inclusive","type":"recurring","recurring":{"interval":"month","interval_count":1,"usage_type":"licensed"},"metadata":{"local_price_id":"%s","local_price_version":"1"}}}]}}
            """.formatted(start.getEpochSecond(),end.getEpochSecond(),price.value()));
    }
    private void setPeriod(Instant from,Instant until) {
        var item=remote.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject();
        item.addProperty("current_period_start",from.getEpochSecond());item.addProperty("current_period_end",until.getEpochSecond());
    }
    @Then("its Stripe create acknowledgement has no cash or paid access") public void acknowledgement() {
        assertThat(cash()).isZero();assertThat(subscription().paidThrough()).isNull();assertThat(access().allowed()).isFalse();
        assertThat(count("provider_operations","resource_id=? and kind='SUBSCRIPTION_CREATE' and status='ACKNOWLEDGED'",created.operationId())).isEqualTo(1);
        assertThat(count("payment_events","site_id=? and event_type='PAYMENT_SUCCEEDED'",site.value())).isZero();
    }
    private InvoiceGraph invoice(String suffix,String reason,Instant from,Instant until,String settlement) {
        String id="in_"+suffix;long amount=settlement.equals("ZERO")?0:2000;
        var invoice=json("""
            {"id":"%s","object":"invoice","livemode":false,"customer":"cus_billing","currency":"eur","status":"%s","billing_reason":"%s","created":%d,
             "collection_method":"charge_automatically","amount_due":%d,"amount_paid":%d,"amount_remaining":%d,"amount_paid_off_stripe":0,"amount_overpaid":0,"total":%d,"subtotal":%d,
             "starting_balance":0,"ending_balance":0,"pre_payment_credit_notes_amount":0,"post_payment_credit_notes_amount":0,"discounts":[],"total_discount_amounts":[],"total_pretax_credit_amounts":[],"total_taxes":[],
             "attempted":true,"attempt_count":1,"auto_advance":false,"next_payment_attempt":null,
             "parent":{"type":"subscription_details","subscription_details":{"subscription":"sub_billing"}}}
            """.formatted(id,settlement.equals("OPEN")?"open":"paid",reason,from.getEpochSecond(),amount,settlement.equals("OPEN")?0:amount,settlement.equals("OPEN")?amount:0,amount,amount));
        var line=json("""
            {"id":"il_%s","object":"line_item","livemode":false,"invoice":"%s","subscription":"sub_billing","currency":"eur","quantity":1,"amount":%d,"discount_amounts":[],"pretax_credit_amounts":[],
             "period":{"start":%d,"end":%d},"parent":{"type":"subscription_item_details","subscription_item_details":{"subscription":"sub_billing","subscription_item":"si_billing","proration":false}},
             "pricing":{"type":"price_details","price_details":{"price":"price_billing","product":"prod_billing"}}}
            """.formatted(suffix,id,amount,from.getEpochSecond(),until.getEpochSecond()));
        var allocation=json("{\"id\":\"inpay_"+suffix+"\",\"object\":\"invoice_payment\",\"livemode\":false,\"invoice\":\""+id+"\",\"currency\":\"eur\",\"status\":\"paid\",\"amount_paid\":2000,\"amount_requested\":2000,\"payment\":{\"type\":\"payment_intent\",\"payment_intent\":\"pi_"+suffix+"\"},\"status_transitions\":{\"paid_at\":"+from.getEpochSecond()+"}}");
        var intent=json("{\"id\":\"pi_"+suffix+"\",\"object\":\"payment_intent\",\"livemode\":false,\"customer\":\"cus_billing\",\"currency\":\"eur\",\"amount\":2000,\"amount_received\":2000,\"status\":\"succeeded\",\"capture_method\":\"automatic\",\"payment_method_types\":[\"card\"],\"latest_charge\":\"ch_"+suffix+"\"}");
        var charge=json("{\"id\":\"ch_"+suffix+"\",\"object\":\"charge\",\"livemode\":false,\"customer\":\"cus_billing\",\"payment_intent\":\"pi_"+suffix+"\",\"currency\":\"eur\",\"amount\":2000,\"amount_captured\":2000,\"paid\":true,\"captured\":true,\"status\":\"succeeded\",\"created\":"+from.getEpochSecond()+",\"payment_method_details\":{\"type\":\"card\"},\"amount_refunded\":0,\"refunded\":false}");
        return new InvoiceGraph(suffix,settlement,invoice,line,allocation,intent,charge);
    }
    /** Exact native transport order: complete invoice/line/allocation pages plus reverse allocation join. */
    private void readGraph() {
        identity();respond("GET","/v1/customers/cus_billing",remoteCustomer);respond("GET","/v1/subscriptions/sub_billing",remote);
        http.enqueue("GET","/v1/invoices",200,page(invoices.stream().map(InvoiceGraph::invoice).toList()));
        for(var graph:invoices) {
            respondPage("/v1/invoices/in_"+graph.suffix()+"/lines",List.of(graph.line()));
            respondPage("/v1/invoice_payments",graph.settlement().equals("CASH")?List.of(graph.allocation()):List.of());
            if(graph.settlement().equals("CASH")) {
                respond("GET","/v1/payment_intents/pi_"+graph.suffix(),graph.intent());respond("GET","/v1/charges/ch_"+graph.suffix(),graph.charge());
                respondPage("/v1/invoice_payments",List.of(graph.allocation()));
            }
        }
    }
    private void respondPage(String path,List<JsonObject> items) { http.enqueue("GET",path,200,page(items)); }
    private void reconcile() { readGraph();bean(ReconcileSubscriptionUseCase.class).reconcile(new ReconcileSubscriptionUseCase.Command(context(key()),created.subscriptionId())); }
    private void signed(String event) throws Exception {
        long at=Instant.now().getEpochSecond();String body="{\"id\":\""+event+"\",\"object\":\"event\",\"api_version\":\"2026-09-30.endive\",\"livemode\":false,\"created\":"+at+",\"type\":\"invoice.paid\",\"data\":{\"object\":{\"id\":\"in_initial\",\"object\":\"invoice\",\"livemode\":false}}}";
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(StripeAcceptanceConfiguration.SIGNING.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature=HexFormat.of().formatHex(mac.doFinal((at+"."+body).getBytes(StandardCharsets.UTF_8)));
        var response=world.api().mvc.perform(post("/api/v1/provider-webhooks/stripe/stripe-billing-acceptance").header("Stripe-Signature","t="+at+",v1="+signature).contentType("application/json").content(body)).andReturn().getResponse();
        assertThat(response.getStatus()).as("Billing signed receipt HTTP status; payload withheld").isEqualTo(204);
    }
    private void processReceipt(String event) throws Exception {
        signed(event);TestClock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));identity();respond("GET","/v1/invoices/in_initial",invoices.getFirst().invoice());readGraph();
        bean(SubscriptionInboxWorker.class).runOnce();
        assertThat(count("provider_inbox","payment_account_id=? and provider_event_id=? and status='PROCESSED'",account.id().value(),event)).isEqualTo(1);
    }
    @When("its initial Stripe invoice is verified through a signed receipt") public void initialPaid() throws Exception {
        remote.addProperty("status","active");invoices.add(invoice("initial","subscription_create",start,end,"CASH"));processReceipt("evt_initial");
    }
    @Then("one owned subscription cash payment and canonical callbacks exist") public void initialCash() {
        assertThat(cash()).isEqualTo(1);assertThat(subscription().paidThrough()).isEqualTo(end);assertThat(access().reason()).isEqualTo(AccessDecision.Reason.PAID);
        assertThat(operation(created.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(count("payments","site_id=? and payment_account_id=? and customer_id=? and source='SUBSCRIPTION' and provider_payment_reference='pi_initial' and amount_minor=2000 and currency='EUR' and status='SUCCEEDED'",site.value(),account.id().value(),customer.id().value())).isEqualTo(1);
        assertThat(count("payment_events","site_id=? and event_type='PAYMENT_SUCCEEDED'",site.value())).isEqualTo(1);
        assertThat(count("outbox_events","site_id=? and callback_type='payment.succeeded'",site.value())).isEqualTo(1);
        assertThat(count("outbox_events","site_id=? and callback_type like 'subscription.%'",site.value())).isPositive();
        assertThat(count("provider_operations","payment_account_id=? and kind='PAYMENT_PREPARE'",account.id().value())).isZero();
        for(String stored:jdbc.queryForList("select payload::text from outbox_events where site_id=?",String.class,site.value())) {
            var payload=json(stored);assertThat(payload.get("site_id").getAsString()).isEqualTo(site.value().toString());
            assertThat(payload.get("subscription_id").getAsString()).isEqualTo(created.subscriptionId().toString());
            assertThat(payload.get("customer_id").getAsString()).isEqualTo(customer.id().value().toString());
            assertThat(payload.get("product_id").getAsString()).isEqualTo(product.value().toString());
            if(payload.get("schema_version").getAsInt()==4) {
                assertThat(payload.keySet()).containsExactlyInAnyOrder("schema_version","event_id","event_type","site_id","subscription_id","customer_id","product_id","revision","state","access_allowed","access_valid_until","occurred_at","billing_record_id","payment_id");
                assertThat(count("subscription_events","id=? and subscription_id=? and payment_account_id=?",UUID.fromString(payload.get("event_id").getAsString()),created.subscriptionId(),account.id().value())).isEqualTo(1);
            } else {
                assertThat(payload.get("schema_version").getAsInt()).isEqualTo(3);
                assertThat(payload.keySet()).containsExactlyInAnyOrder("schema_version","event_id","event_type","site_id","payment_id","external_order_id","payment_status","amount_minor","currency","occurred_at","subscription_id","billing_record_id","customer_id","product_id","billing_kind","price_id","price_version","period_start","period_end");
            }
        }
        evidence=financialEvidence();
    }
    private List<Long> financialEvidence() {
        return List.of(cash(),count("payment_events","site_id=?",site.value()),count("subscription_events","subscription_id=?",created.subscriptionId()),count("outbox_events","site_id=?",site.value()),subscription().revision());
    }
    @When("Stripe repeats the receipt and sends another hint for the same invoice") public void repeated() throws Exception {
        int before=http.calls().size();signed("evt_initial");bean(SubscriptionInboxWorker.class).runOnce();assertThat(http.calls()).hasSize(before);
        processReceipt("evt_initialagain");
    }
    @Then("its subscription cash, lifecycle facts and callbacks are unchanged") public void unchanged() { assertThat(financialEvidence()).isEqualTo(evidence); }
    @Then("its original Stripe create key and Account ownership remain intact") public void originalOwnership() {
        int before=http.calls().size();assertThat(bean(CreateSubscriptionUseCase.class).create(createCommand).operationId()).isEqualTo(created.operationId());assertThat(http.calls()).hasSize(before);
        var writes=http.calls().stream().filter(c->c.method().equals("POST")&&c.path().equals("/v1/subscriptions")).toList();
        assertThat(writes).singleElement().satisfies(c->{assertThat(c.header("Idempotency-Key")).isEqualTo(operation(created.operationId()).providerOperationKey());assertThat(c.form()).containsEntry("customer","cus_billing").containsEntry("metadata[account_id]",account.id().value().toString()).containsEntry("metadata[site_id]",site.value().toString()).containsEntry("metadata[customer_id]",customer.id().value().toString()).containsEntry("metadata[subscription_id]",created.subscriptionId().toString());});
        assertThat(http.calls()).allSatisfy(c->{assertThat(c.header("Stripe-Version")).isEqualTo("2026-09-30.endive");assertThat(c.header("Stripe-Account")).isNull();});
    }
    @When("its original zero Stripe trial invoice is reconciled") public void trialZero() {
        TestClock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));invoices.add(invoice("trial","subscription_create",start,end,"ZERO"));reconcile();
    }
    @Then("trial access exists without a cash payment or payment succeeded fact") public void trialAccess() {
        assertThat(access().allowed()).isTrue();assertThat(access().reason()).isEqualTo(AccessDecision.Reason.TRIAL);assertThat(subscription().trial()).isEqualTo(new Subscription.Period(start,end));
        assertThat(cash()).isZero();assertThat(subscription().paidThrough()).isNull();assertThat(count("payment_events","site_id=?",site.value())).isZero();
        assertThat(count("subscription_billing_records","subscription_id=? and settlement='ZERO_AMOUNT'",created.subscriptionId())).isEqualTo(1);
        assertThat(operation(created.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
    }
    private void next(String settlement) {
        start=end;end=end.plusSeconds(30L*86400);TestClock.set(start);authenticate();setPeriod(start,end);
        remote.addProperty("status",settlement.equals("CASH")?"active":"past_due");invoices.add(invoice("renewal","subscription_cycle",start,end,settlement));reconcile();
    }
    @When("its next Stripe renewal has fully captured cash") public void renewal() { next("CASH"); }
    @Then("two distinct subscription payments cover the renewed period") public void renewed() {
        assertThat(cash()).isEqualTo(2);assertThat(subscription().paidThrough()).isEqualTo(end);assertThat(subscription().currentPeriod()).isEqualTo(new Subscription.Period(start,end));assertThat(access().reason()).isEqualTo(AccessDecision.Reason.PAID);
        assertThat(jdbc.queryForList("select provider_payment_reference from payments where site_id=? order by provider_payment_reference",String.class,site.value())).containsExactly("pi_initial","pi_renewal");
    }
    @When("its next Stripe renewal remains open without a supported payment method") public void failedRenewal() { next("OPEN"); }
    @Then("the renewal has fixed grace without another cash payment") public void grace() {
        grace=subscription().debt().graceDeadline();assertThat(grace).isEqualTo(start.plusSeconds(3L*86400));assertThat(subscription().paidThrough()).isEqualTo(start);assertThat(cash()).isEqualTo(1);assertThat(access().reason()).isEqualTo(AccessDecision.Reason.GRACE);
    }
    @When("the future Site grace policy is extended and the open invoice is observed again") public void extendGrace() {
        var policies=bean(SubscriptionPolicyPort.class);var policy=policies.load(site);policies.save(new SubscriptionPolicy(site,policy.version(),true,30,true,false,false,Set.of()),policy.version());reconcile();
    }
    @Then("the original Stripe renewal grace deadline is unchanged") public void fixedGrace() { assertThat(subscription().debt().graceDeadline()).isEqualTo(grace);assertThat(cash()).isEqualTo(1); }
    @When("the customer requests recovery of that same open Stripe invoice twice") public void recover() {
        recoverCommand=new RecoverSubscriptionUseCase.Command(context(key()),created.subscriptionId(),subscription().debt().billingRecordId());
        int before=http.calls().size();readGraph();readGraph();recovery=bean(RecoverSubscriptionUseCase.class).recover(recoverCommand);
        assertThat(http.calls()).hasSize(before+2*graphRequests());
        var original=operation(recovery.operationId());var steps=bean(ProviderOperationStore.class);
        assertThat(steps.find(account.id(),original.providerOperationKey()+":INVOICE_PAY")).isEmpty();
        before=http.calls().size();readGraph();
        assertThat(bean(RecoverSubscriptionUseCase.class).recover(recoverCommand)).isEqualTo(recovery);
        assertThat(http.calls()).hasSize(before+graphRequests());assertThat(operation(original.id())).isEqualTo(original);
        assertThat(steps.find(account.id(),original.providerOperationKey()+":INVOICE_PAY")).isEmpty();
    }
    @Then("recovery remains unknown without an invoice pay or browser retry authority") public void recoveryClosed() {
        assertThat(operation(recovery.operationId()).status()).isEqualTo(SubscriptionOperation.Status.UNKNOWN);assertThat(recovery.nextAction()).isNull();assertThat(recovery.browserConfirmAllowed()).isFalse();
        assertThat(http.calls().stream().filter(c->c.path().endsWith("/pay")&&c.method().equals("POST"))).isEmpty();assertThat(cash()).isEqualTo(1);assertThat(subscription().debt().graceDeadline()).isEqualTo(grace);
    }
    private void cancellationWrite(boolean withdraw) {
        readGraph();identity();respond("GET","/v1/customers/cus_billing",remoteCustomer);respond("GET","/v1/subscriptions/sub_billing",remote);
        http.enqueue("POST","/v1/subscriptions/sub_billing",200,call->{
            if(withdraw){remote.remove("cancel_at");remote.addProperty("cancel_at_period_end",false);}
            else remote.addProperty("cancel_at",end.getEpochSecond());
            return remote.toString();
        });
        if(withdraw)withdrawal=bean(WithdrawSubscriptionCancellationUseCase.class).withdraw(new WithdrawSubscriptionCancellationUseCase.Command(context(key()),created.subscriptionId()));
        else cancel=bean(CancelSubscriptionUseCase.class).cancel(new CancelSubscriptionUseCase.Command(context(key()),created.subscriptionId(),CancelSubscriptionUseCase.Mode.PERIOD_END,created.subscriptionId()));
        assertThat((withdraw?withdrawal:cancel).status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);reconcile();
    }
    @When("the customer schedules and withdraws Stripe period end cancellation") public void cancellation() {
        cancellationWrite(false);assertThat(subscription().cancellation().effectiveAt()).isEqualTo(end);assertThat(operation(cancel.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        cancellationWrite(true);
    }
    @Then("the Stripe cancellation proofs preserve the paid period and cash") public void cancelledAndWithdrawn() {
        assertThat(subscription().cancellation()).isNull();assertThat(subscription().paidThrough()).isEqualTo(end);assertThat(cash()).isEqualTo(1);assertThat(operation(withdrawal.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(count("stripe_subscription_cancellation_proofs","subscription_id=?",created.subscriptionId())).isEqualTo(2);
        var writes=http.calls().stream().filter(c->c.method().equals("POST")&&c.path().equals("/v1/subscriptions/sub_billing")).toList();assertThat(writes).hasSize(2);
        assertThat(writes.get(0).header("Idempotency-Key")).isEqualTo(operation(cancel.operationId()).providerOperationKey());assertThat(writes.get(1).header("Idempotency-Key")).isEqualTo(operation(withdrawal.operationId()).providerOperationKey());
        assertThat(writes.get(0).form()).containsEntry("cancel_at",Long.toString(end.getEpochSecond())).containsEntry("proration_behavior","none");assertThat(writes.get(1).form()).containsEntry("cancel_at","").containsEntry("cancel_at_period_end","false");
    }
    @When("the customer requests a disallowed Stripe plan change and Portal return") public void deniedPolicy() {
        var target=new PriceId(UUID.randomUUID());
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,'billing-other',1,'SUBSCRIPTION',4000,'EUR','MONTH',0,'OPTIONAL_DURING_TRIAL','INCLUSIVE','PUBLISHED',?,?,?)",target.value(),site.value(),product.value(),Timestamp.from(now()),Timestamp.from(now()),Timestamp.from(now()));
        bean(ProviderCatalogMappingPort.class).save(new ProviderCatalogMapping(account.id(),product,new ProviderProductRef("prod_billing"),target,new ProviderPriceRef("price_other"),now()));
        beforeDenied=http.calls().size();
        assertThatThrownBy(()->bean(PreviewSubscriptionChangeUseCase.class).preview(new PreviewSubscriptionChangeUseCase.Query(context(null),created.subscriptionId(),target,ChangeTiming.IMMEDIATE)))
            .isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("SUBSCRIPTION_STATE_CONFLICT"));
        assertThatThrownBy(()->bean(CreateSubscriptionPortalUseCase.class).create(new CreateSubscriptionPortalUseCase.Command(context(key()),created.subscriptionId(),ORIGIN+"/foreign-return")))
            .isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("SUBSCRIPTION_PORTAL_UNAVAILABLE"));
    }
    @Then("neither request dispatches a Stripe mutation or grants new cash") public void deniedNoMutation() {
        assertThat(http.calls()).hasSize(beforeDenied);assertThat(cash()).isEqualTo(1);assertThat(count("subscription_portal_sessions","subscription_id=?",created.subscriptionId())).isZero();
        assertThat(count("subscription_operations","subscription_id=? and kind in ('CHANGE','PORTAL')",created.subscriptionId())).isZero();
    }
    @When("an independent Fake subscription is bought in the same application") public void fake() {
        beforeFake=http.calls().size();var fake=bean(SubscriptionAcceptanceWorld.class);fake.initialize();fake.createPaid("mixed-fake","MONTH");fakeSubscription=fake.result().subscriptionId();assertThat(fake.access().reason()).isEqualTo(AccessDecision.Reason.PAID);
    }
    @Then("Fake grants its paid access without using the Stripe transport") public void fakeIsolated() {
        assertThat(http.calls()).hasSize(beforeFake);assertThat(count("payments","subscription_id=? and source='SUBSCRIPTION' and status='SUCCEEDED'",fakeSubscription)).isEqualTo(1);assertThat(cash()).isEqualTo(1);assertThat(subscription().paymentAccountId()).isEqualTo(account.id());
    }
    private int graphRequests() { return 4+invoices.stream().mapToInt(i->i.settlement().equals("CASH")?5:2).sum(); }

    private PriceId targetPrice(String key) {
        var target=new PriceId(UUID.randomUUID());
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,?,1,'SUBSCRIPTION',4000,'EUR','MONTH',0,'OPTIONAL_DURING_TRIAL','INCLUSIVE','PUBLISHED',?,?,?)",target.value(),site.value(),product.value(),key,Timestamp.from(now()),Timestamp.from(now()),Timestamp.from(now()));
        bean(ProviderCatalogMappingPort.class).save(new ProviderCatalogMapping(account.id(),product,new ProviderProductRef("prod_billing"),target,new ProviderPriceRef("price_upgrade"),now()));
        return target;
    }
    private void readHead() {
        identity();respond("GET","/v1/customers/cus_billing",remoteCustomer);respond("GET","/v1/subscriptions/sub_billing",remote);
    }
    private void previewResponse() {
        readHead();http.enqueue("POST","/v1/invoices/create_preview",200,call -> {
            planAnchor=Instant.ofEpochSecond(Long.parseLong(call.form().get("subscription_details[proration_date]")));
            creditLine.getAsJsonObject("period").addProperty("start",planAnchor.getEpochSecond());
            debitLine.getAsJsonObject("period").addProperty("start",planAnchor.getEpochSecond());
            var preview=changeInvoice.deepCopy();preview.addProperty("id","upcoming_billing");preview.addProperty("status","draft");preview.addProperty("amount_paid",0);preview.addProperty("amount_remaining",1000);
            var credit=creditLine.deepCopy();credit.addProperty("id","il_previewcredit");credit.addProperty("invoice","upcoming_billing");
            var debit=debitLine.deepCopy();debit.addProperty("id","il_previewdebit");debit.addProperty("invoice","upcoming_billing");
            preview.add("lines",JsonParser.parseString(page(List.of(credit,debit))));
            // Real provider quote anchors use UTC; align only the existing synthetic business fixture
            // before its common quote publication validates effectiveAt/TTL against business time.
            TestClock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));return preview.toString();
        });
    }
    @When("the customer previews and confirms an allowed immediate Stripe upgrade") public void upgrade() {
        upgradePrice=targetPrice("billing-upgrade");var policies=bean(SubscriptionPolicyPort.class);
        policies.saveTransition(new PlanTransition(site,product,price,upgradePrice,PlanTransition.Direction.UPGRADE,true,policies.load(site).version()));
        changeCash=invoice("change","subscription_update",now().truncatedTo(ChronoUnit.SECONDS),end,"CASH");changeInvoice=changeCash.invoice();
        for(String field:List.of("amount_due","amount_paid","total","subtotal"))changeInvoice.addProperty(field,1000);
        creditLine=changeCash.line().deepCopy();creditLine.addProperty("id","il_changecredit");creditLine.addProperty("amount",-1000);creditLine.getAsJsonObject("parent").getAsJsonObject("subscription_item_details").addProperty("proration",true);
        debitLine=creditLine.deepCopy();debitLine.addProperty("id","il_changedebit");debitLine.addProperty("amount",2000);debitLine.getAsJsonObject("pricing").getAsJsonObject("price_details").addProperty("price","price_upgrade");
        changeCash.allocation().addProperty("amount_paid",1000);changeCash.allocation().addProperty("amount_requested",1000);changeCash.allocation().addProperty("is_default",true);
        for(String field:List.of("amount","amount_received"))changeCash.intent().addProperty(field,1000);
        for(String field:List.of("amount","amount_captured"))changeCash.charge().addProperty(field,1000);
        previewResponse();upgradeQuote=bean(PreviewSubscriptionChangeUseCase.class).preview(new PreviewSubscriptionChangeUseCase.Query(context(null),created.subscriptionId(),upgradePrice,ChangeTiming.IMMEDIATE));
        assertThat(upgradeQuote.dueNowMinor()).isEqualTo(1000);assertThat(upgradeQuote.target().priceId()).isEqualTo(upgradePrice);
        previewResponse();readHead();
        http.enqueue("POST","/v1/subscriptions/sub_billing",200,call -> {
            long dispatched=dispatchSecond(call);
            changeInvoice.addProperty("created",dispatched);changeCash.charge().addProperty("created",dispatched);changeCash.allocation().getAsJsonObject("status_transitions").addProperty("paid_at",dispatched);
            var target=remote.deepCopy();var nativePrice=target.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject().getAsJsonObject("price");
            nativePrice.addProperty("id","price_upgrade");nativePrice.addProperty("unit_amount",4000);nativePrice.getAsJsonObject("metadata").addProperty("local_price_id",upgradePrice.value().toString());
            target.addProperty("latest_invoice","in_change");remote=target;return remote.toString();
        });
        http.enqueue("GET","/v1/invoices/in_change",200,ignored -> changeInvoice.toString());
        http.enqueue("GET","/v1/invoices/in_change/lines",200,ignored -> page(List.of(creditLine,debitLine)));
        upgradeCommand=new ChangeSubscriptionPlanUseCase.Command(context(key()),created.subscriptionId(),upgradeQuote.id());
        upgraded=bean(ChangeSubscriptionPlanUseCase.class).change(upgradeCommand);
        assertThat(upgraded.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
    }
    private long dispatchSecond(StripeHttpFixture.Call call) {
        return jdbc.queryForObject("select floor(extract(epoch from first_dispatched_at))::bigint from provider_operations where operation_key=?",Long.class,call.header("Idempotency-Key"));
    }
    @Then("its Stripe change acknowledgement preserves the original entitlement and cash") public void upgradeAcknowledgement() {
        assertThat(subscription().billingPrice().priceId()).isEqualTo(price);assertThat(subscription().entitledPrice().priceId()).isEqualTo(price);assertThat(subscription().paidThrough()).isEqualTo(end);assertThat(cash()).isEqualTo(1);
        assertThat(subscription().pendingChange().target().priceId()).isEqualTo(upgradePrice);
        assertThat(count("subscription_events","subscription_id=? and event_type='CHANGE_APPLIED'",created.subscriptionId())).isZero();
        var receipt=bean(SubscriptionPlanProofStore.class).result(account.id(),upgraded.operationId()).orElseThrow();var step=bean(ProviderOperationStore.class).findById(receipt.step()).orElseThrow();
        assertThat(step.kind()).isEqualTo(ProviderOperation.Kind.SUBSCRIPTION_UPDATE);assertThat(step.resourceId()).isEqualTo(upgraded.operationId());assertThat(step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
    }
    private void readUpgradeGraph() {
        readHead();respondPage("/v1/invoices",List.of(invoices.getFirst().invoice(),changeInvoice));
        var first=invoices.getFirst();respondPage("/v1/invoices/in_initial/lines",List.of(first.line()));respondPage("/v1/invoice_payments",List.of(first.allocation()));respond("GET","/v1/payment_intents/pi_initial",first.intent());respond("GET","/v1/charges/ch_initial",first.charge());respondPage("/v1/invoice_payments",List.of(first.allocation()));
        respondPage("/v1/invoices/in_change/lines",List.of(creditLine,debitLine));respondPage("/v1/invoice_payments",List.of(changeCash.allocation()));respond("GET","/v1/payment_intents/pi_change",changeCash.intent());respond("GET","/v1/charges/ch_change",changeCash.charge());respondPage("/v1/invoice_payments",List.of(changeCash.allocation()));
    }
    @When("its exact Stripe proration invoice and captured charge are reconciled") public void settleUpgrade() {
        readUpgradeGraph();bean(ReconcileSubscriptionUseCase.class).reconcile(new ReconcileSubscriptionUseCase.Command(context(key()),created.subscriptionId()));
    }
    @Then("the upgraded entitlement has one additional cash payment and immutable change proof") public void upgraded() {
        var settled=subscription();assertThat(settled.billingPrice().priceId()).isEqualTo(upgradePrice);assertThat(settled.entitledPrice().priceId()).isEqualTo(upgradePrice);assertThat(settled.pendingChange()).isNull();assertThat(settled.currentPeriod()).isEqualTo(new Subscription.Period(start,end));assertThat(settled.paidThrough()).isEqualTo(end);assertThat(cash()).isEqualTo(2);
        assertThat(operation(upgraded.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(count("payments","site_id=? and provider_payment_reference='pi_change' and amount_minor=1000 and status='SUCCEEDED'",site.value())).isEqualTo(1);
        assertThat(count("subscription_billing_records","subscription_id=? and provider_invoice_reference='in_change' and kind='PRORATION' and settlement='CONFIRMED_CASH' and billed_price_id=? and previous_price_id=? and period_start=? and period_end=?",created.subscriptionId(),upgradePrice.value(),price.value(),Timestamp.from(planAnchor),Timestamp.from(end))).isEqualTo(1);
        var proofs=bean(SubscriptionPlanProofStore.class);var receipt=proofs.result(account.id(),upgraded.operationId()).orElseThrow();var quote=proofs.quote(account.id(),receipt.quote()).orElseThrow();
        assertThat(receipt.invoice()).isEqualTo("in_change");assertThat(receipt.creditLine()).isEqualTo("il_changecredit");assertThat(receipt.debitLine()).isEqualTo("il_changedebit");assertThat(quote.anchor()).isEqualTo(planAnchor);assertThat(quote.lines()).extracting(SubscriptionPlanProofStore.Line::amount).containsExactly(-1000L,2000L);
        var stable=financialEvidence();int before=http.calls().size();assertThat(bean(ChangeSubscriptionPlanUseCase.class).change(upgradeCommand).operationId()).isEqualTo(upgraded.operationId());assertThat(http.calls()).hasSize(before);
        settleUpgrade();assertThat(financialEvidence()).isEqualTo(stable);assertThat(proofs.result(account.id(),upgraded.operationId())).contains(receipt);assertThat(proofs.quote(account.id(),quote.id())).contains(quote);
        var writes=http.calls().stream().filter(c->c.method().equals("POST")&&c.path().equals("/v1/subscriptions/sub_billing")).toList();
        assertThat(writes).singleElement().satisfies(c->{assertThat(c.header("Idempotency-Key")).isEqualTo(operation(upgraded.operationId()).providerOperationKey());assertThat(c.form()).containsEntry("payment_behavior","pending_if_incomplete").containsEntry("proration_behavior","always_invoice").containsEntry("proration_date",Long.toString(planAnchor.getEpochSecond())).containsEntry("items[0][price]","price_upgrade").containsEntry("items[0][id]","si_billing");});
    }
    @When("the customer opens the allowed narrow Stripe Billing Portal") public void portal() {
        var policies=bean(SubscriptionPolicyPort.class);var old=policies.load(site);portalReturn=ORIGIN+"/account";
        policies.save(new SubscriptionPolicy(site,old.version(),true,3,true,true,false,Set.of(portalReturn)),old.version());
        evidence=financialEvidence();portalNavigation="https://billing.stripe.com/p/session?secret=test_"+UUID.randomUUID().toString().replace("-","");
        identity();respond("GET","/v1/customers/cus_billing",remoteCustomer);respondPage("/v1/subscriptions",List.of(remote));respondPage("/v1/invoices",List.of(invoices.getFirst().invoice()));
        var configuration=json("""
            {"id":"bpc_billing","object":"billing_portal.configuration","active":true,"livemode":false,"default_return_url":"%s",
             "login_page":{"enabled":false,"url":null},"features":{"invoice_history":{"enabled":true},"customer_update":{"enabled":false,"allowed_updates":[]},"payment_method_update":{"enabled":false},"subscription_update":{"enabled":false,"default_allowed_updates":[],"products":[]},"subscription_cancel":{"enabled":true,"mode":"at_period_end","proration_behavior":"none","cancellation_reason":{"enabled":false,"options":[]}}}}
            """.formatted(portalReturn));
        http.enqueue("POST","/v1/billing_portal/configurations",200,call->{long at=dispatchSecond(call);configuration.addProperty("created",at);configuration.addProperty("updated",at);return configuration.toString();});
        http.enqueue("GET","/v1/billing_portal/configurations/bpc_billing",200,ignored->configuration.toString());
        http.enqueue("POST","/v1/billing_portal/sessions",200,call->"""
            {"id":"bps_billing","object":"billing_portal.session","livemode":false,"created":%d,"customer":"cus_billing","configuration":"bpc_billing","url":"%s","return_url":"%s","flow":{"type":"subscription_cancel","subscription_cancel":{"subscription":"sub_billing"},"after_completion":{"type":"redirect","redirect":{"return_url":"%s"}}}}
            """.formatted(dispatchSecond(call),portalNavigation,portalReturn,portalReturn));
        var navigation=bean(CreateSubscriptionPortalUseCase.class).create(new CreateSubscriptionPortalUseCase.Command(context(key()),created.subscriptionId(),portalReturn));
        assertThat(navigation.simulated()).isFalse();assertThat(navigation.navigationUrl().equals(portalNavigation)).as("exact ephemeral owned navigation; value withheld").isTrue();
        portalOperation=jdbc.queryForObject("select id from subscription_operations where subscription_id=? and kind='PORTAL'",UUID.class,created.subscriptionId());
    }
    @Then("its Portal configuration and session have original persisted provider proofs") public void portalProofs() {
        assertThat(operation(portalOperation).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(count("stripe_portal_configurations","payment_account_id=? and site_id=? and configuration_reference='bpc_billing'",account.id().value(),site.value())).isEqualTo(1);
        assertThat(count("stripe_portal_session_receipts","operation_id=? and session_reference='bps_billing'",portalOperation)).isEqualTo(1);
        assertThat(count("subscription_portal_sessions","public_operation_id=? and site_id=? and customer_id=? and subscription_id=?",portalOperation,site.value(),customer.id().value(),created.subscriptionId())).isEqualTo(1);
        var session=bean(ProviderOperationStore.class).find(account.id(),operation(portalOperation).providerOperationKey()+":SESSION").orElseThrow();assertThat(session.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(session.resourceId()).isEqualTo(portalOperation);
        var writes=http.calls().stream().filter(c->c.method().equals("POST")&&c.path().startsWith("/v1/billing_portal/")).toList();assertThat(writes).hasSize(2);
        var configuration=jdbc.queryForMap("select intent_id,creation_step from stripe_portal_configurations where payment_account_id=? and configuration_reference='bpc_billing'",account.id().value());
        var configurationStep=bean(ProviderOperationStore.class).findById((UUID)configuration.get("creation_step")).orElseThrow();
        assertThat(configurationStep.resourceId()).isEqualTo(configuration.get("intent_id"));assertThat(configurationStep.accountId()).isEqualTo(account.id());assertThat(configurationStep.kind()).isEqualTo(ProviderOperation.Kind.PORTAL_CONFIGURATION_CREATE);assertThat(configurationStep.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(writes.getFirst().header("Idempotency-Key")).isEqualTo(configurationStep.operationKey());
        assertThat(writes.getFirst().form()).containsEntry("features[invoice_history][enabled]","true").containsEntry("features[subscription_cancel][mode]","at_period_end").containsEntry("features[subscription_cancel][proration_behavior]","none").containsEntry("features[payment_method_update][enabled]","false").containsEntry("features[customer_update][enabled]","false").containsEntry("features[subscription_update][enabled]","false");
        assertThat(writes.getLast().header("Idempotency-Key")).isEqualTo(session.operationKey());assertThat(writes.getLast().form()).containsEntry("customer","cus_billing").containsEntry("configuration","bpc_billing").containsEntry("return_url",portalReturn).containsEntry("flow_data[type]","subscription_cancel").containsEntry("flow_data[subscription_cancel][subscription]","sub_billing");
        for(String table:List.of("stripe_portal_configuration_intents","stripe_portal_configurations","stripe_portal_session_intents","stripe_portal_session_receipts","subscription_portal_sessions","subscription_operations","outbox_events"))assertThat(jdbc.queryForList("select row_to_json(t)::text from "+table+" t").toString().contains(portalNavigation)).as("ephemeral Portal URL persistence scan; value withheld").isFalse();
    }
    @Then("only its original customer and public operation can read the Portal return") public void scopedPortalReturn() {
        int before=http.calls().size();var service=bean(SubscriptionPortalService.class);assertThat(service.returned(context(null),created.subscriptionId(),portalOperation)).isEqualTo(subscription());
        assertThatThrownBy(()->service.returned(context(null),created.subscriptionId(),UUID.randomUUID())).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
        var foreign=bean(CustomerStore.class).save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,now()));var sessions=bean(CustomerSessionService.class);
        var foreignActor=new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site,foreign.id(),ORIGIN).rawToken(),ORIGIN));
        var foreignContext=new SubscriptionCommandContext(foreignActor,site,foreign.id(),ORIGIN,null,"stripe-billing-foreign");
        assertThatThrownBy(()->service.returned(foreignContext,created.subscriptionId(),portalOperation)).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);assertThat(http.calls()).hasSize(before);
    }
    @Then("the Portal return leaves subscription cash and lifecycle facts unchanged") public void portalNoMoney() { assertThat(financialEvidence()).isEqualTo(evidence); }
    @Then("its canonical Site callbacks are signed and delivered exactly once") public void deliverCallbacks() {
        var outbox=bean(OutboxPort.class);var encryption=bean(EncryptedSecretPort.class);var payloads=bean(CallbackPayloadPort.class);
        // Existing in-memory CallbackHttpPort seam, actual signer/canonical bytes/outbox completion.
        // These delivery scenarios use ordinary initial/change periods, never a future business advance.
        Clock deliveryClock=Clock.systemUTC();
        var claims=outbox.claimAutomatic("stripe-billing-callbacks",20,now(),Duration.ofSeconds(30));
        assertThat(claims).hasSize((int)count("outbox_events","site_id=?",site.value()));var delivered=new HashSet<UUID>();
        for(var claim:claims) {
            long before=deliveryClock.instant().getEpochSecond();
            byte[] secret=Base64.getUrlDecoder().decode(encryption.decrypt(claim.secret()));
            try {
                new CallbackDeliveryService(outbox,encryption,payloads,(endpoint,headers,body)->{
                    assertThat(bean(SubscriptionTransactionPort.class).active()).isFalse();assertThat(endpoint.toString()).isEqualTo("http://127.0.0.1:8081/callback");
                    long at=Long.parseLong(headers.get("Payment-Timestamp"));assertThat(at).isBetween(before,deliveryClock.instant().getEpochSecond());assertThat(headers.get("Payment-Signature")).isEqualTo("v1="+new CallbackSigner().sign(secret,at,body));
                    var canonical=json(new String(body,StandardCharsets.UTF_8));assertThat(headers.get("Payment-Event-Id")).isEqualTo(canonical.get("event_id").getAsString());assertThat(canonical.get("site_id").getAsString()).isEqualTo(site.value().toString());assertThat(canonical.get("subscription_id").getAsString()).isEqualTo(created.subscriptionId().toString());assertThat(delivered.add(claim.eventId())).isTrue();return CallbackHttpPort.Result.success(204);
                },deliveryClock).deliver(claim);
            } finally { Arrays.fill(secret,(byte)0); }
        }
        assertThat(count("outbox_events","site_id=? and status<>'DELIVERED'",site.value())).isZero();
        assertThat(count("outbox_deliveries","outbox_event_id in (select id from outbox_events where site_id=?)",site.value())).isEqualTo(claims.size());
        assertThat(outbox.claimAutomatic("stripe-billing-callback-replay",20,now(),Duration.ofSeconds(30))).isEmpty();
    }
    private record InvoiceGraph(String suffix,String settlement,JsonObject invoice,JsonObject line,JsonObject allocation,JsonObject intent,JsonObject charge) {}
}
