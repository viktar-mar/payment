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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    static final String REQUIRED_TRIAL_PROOF_SITE_KEY = "s5a-00000000000040008000000000000001";
    private static final UUID REQUIRED_TRIAL_PROOF_RUN = UUID.fromString("00000000-0000-4000-8000-000000000001");
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
    private TrialPublication originalTrial;
    private SubscriptionOperationResult trialSetup, trialResume, requiredTrial;
    private String requiredTrialCreateDigest;
    private ProductId requiredTrialProduct;
    private PriceId requiredTrialPrice;
    private JsonObject requiredTrialSetup, requiredTrialRemote;
    private SubscriptionBillingProofStore.Setup pendingRequiredSetup;
    private Subscription siblingTrial;
    private TrialPublication siblingPublication;
    private Subscription.Period requiredTrialPeriod;
    private static final BillingRefs BILLING = new BillingRefs("sub_billing", "si_billing", "price_billing", "prod_billing");
    private static final BillingRefs REQUIRED = new BillingRefs("sub_required", "si_required", "price_required", "prod_required");
    private Subscription.Period resumedPeriod;
    private int beforeDenied, beforeFake;
    private UUID fakeSubscription;
    private PriceId upgradePrice;
    private SubscriptionQuote upgradeQuote;
    private ChangeSubscriptionPlanUseCase.Command upgradeCommand;
    private SubscriptionOperationResult upgraded;
    private JsonObject changeInvoice,creditLine,debitLine;
    private InvoiceGraph changeCash;
    private Instant planAnchor;
    private static final String PLAN_SCHEDULE = "sub_sched_billing";
    private PriceId downgradePrice;
    private SubscriptionQuote downgradeQuote;
    private SubscriptionOperationResult scheduledChange, clearedChange;
    private PublishedCash originalPlanCash;
    private JsonObject releasedPlanSchedule;
    private int beforePlan;
    private UUID portalOperation;
    private String portalReturn,portalNavigation;
    private PortalInputs portalInputs;

    private record PortalInputs(PublishedCash cash,long policyVersion,String keyDigest,Instant expiresAt,
                                long revision,Subscription.Period period) {}

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
    private Subscription subscription() { return subscription(created.subscriptionId()); }
    private Subscription subscription(UUID id) { return bean(SubscriptionStore.class).find(site,id).orElseThrow(); }
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
        if(attached) {
            StripeAcceptanceConfiguration.providerTime(null);
            factory.detach();
            attached=false;
        }
        if(http!=null) { http.close(); http=null; }
    }
    private void identity() {
        var provider=new JsonObject();provider.addProperty("id",account.externalAccountId());provider.addProperty("object","account");
        respond("GET","/v1/account",provider);
    }
    private void respond(String method,String path,JsonObject body) { http.enqueue(method,path,200,body.toString()); }
    private static JsonObject json(String body) { return JsonParser.parseString(body).getAsJsonObject(); }
    private static String page(Collection<JsonObject> objects) {
        var page=new JsonObject();page.addProperty("object","list");page.addProperty("has_more",false);
        var data=new JsonArray();objects.forEach(data::add);page.add("data",data);return page.toString();
    }
    private void catalogFixture(int trialDays,boolean portalAcceptance,String siteKey) {
        site=new SiteId(UUID.randomUUID());var accountId=UUID.randomUUID();product=new ProductId(UUID.randomUUID());price=new PriceId(UUID.randomUUID());
        jdbc.update("insert into payment_accounts values (?,?,'STRIPE','SANDBOX','ACTIVE',?,'stripe-sandbox-default',now(),0)",
            accountId,portalAcceptance||REQUIRED_TRIAL_PROOF_SITE_KEY.equals(siteKey)?"stripe-sandbox-default":"stripe-billing-acceptance",
            portalAcceptance||REQUIRED_TRIAL_PROOF_SITE_KEY.equals(siteKey)?"acct_1UPiMpELswAd8tpR":"acct_billing");
        jdbc.update("insert into sites values (?,?,'Stripe Billing',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site.value(),siteKey,accountId);
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
    public void create(int trialDays) throws Exception { create(trialDays,false); }

    @Given("an owned Stripe Billing subscription for Portal acceptance")
    public void createPortalFixture() throws Exception { create(0,true); }

    @Given("an owned Stripe Billing subscription for a required trial proof run")
    public void createRequiredTrialProofFixture() throws Exception {
        create(7,false,REQUIRED_TRIAL_PROOF_SITE_KEY);
    }

    private void create(int trialDays,boolean portalAcceptance) throws Exception { create(trialDays,portalAcceptance,"customer-one"); }
    private void create(int trialDays,boolean portalAcceptance,String siteKey) throws Exception {
        assertThat(trialDays).isIn(0,7);
        // Align this synthetic business fixture with ordinary provider UTC; security clocks stay untouched.
        TestClock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        http=new StripeHttpFixture();factory.attach(http);attached=true;catalogFixture(trialDays,portalAcceptance,siteKey);
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
    private JsonObject subscriptionJson() { return subscriptionJson(BILLING, price); }
    private JsonObject subscriptionJson(BillingRefs refs, PriceId localPrice) {
        return json("""
            {"id":"%s","object":"subscription","livemode":false,"customer":"cus_billing","status":"incomplete","currency":"eur",
             "collection_method":"charge_automatically","billing_mode":{"type":"classic"},"cancel_at_period_end":false,
             "payment_settings":{"payment_method_types":["card"],"save_default_payment_method":"on_subscription"},
             "items":{"object":"list","has_more":false,"data":[{"id":"%s","object":"subscription_item","subscription":"%s","quantity":1,
              "current_period_start":%d,"current_period_end":%d,"price":{"id":"%s","object":"price","livemode":false,"product":"%s","currency":"eur","unit_amount":2000,
              "tax_behavior":"inclusive","type":"recurring","recurring":{"interval":"month","interval_count":1,"usage_type":"licensed"},"metadata":{"local_price_id":"%s","local_price_version":"1"}}}]}}
            """.formatted(refs.subscription(),refs.item(),refs.subscription(),start.getEpochSecond(),end.getEpochSecond(),refs.price(),refs.product(),localPrice.value()));
    }
    private void setPeriod(Instant from,Instant until) { setPeriod(remote, from, until); }
    private static void setPeriod(JsonObject subscription, Instant from, Instant until) {
        var item=subscription.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject();
        item.addProperty("current_period_start",from.getEpochSecond());item.addProperty("current_period_end",until.getEpochSecond());
    }
    @Then("its Stripe create acknowledgement has no cash or paid access") public void acknowledgement() {
        assertThat(cash()).isZero();assertThat(subscription().paidThrough()).isNull();assertThat(access().allowed()).isFalse();
        assertThat(count("provider_operations","resource_id=? and kind='SUBSCRIPTION_CREATE' and status='ACKNOWLEDGED'",created.operationId())).isEqualTo(1);
        assertThat(count("payment_events","site_id=? and event_type='PAYMENT_SUCCEEDED'",site.value())).isZero();
    }
    private InvoiceGraph invoice(String suffix,String reason,Instant from,Instant until,String settlement) {
        return invoice(suffix, reason, from, until, settlement, BILLING);
    }
    private InvoiceGraph invoice(String suffix,String reason,Instant from,Instant until,String settlement,BillingRefs refs) {
        String id="in_"+suffix;long amount=settlement.equals("ZERO")?0:2000;
        var invoice=json("""
            {"id":"%s","object":"invoice","livemode":false,"customer":"cus_billing","currency":"eur","status":"%s","billing_reason":"%s","created":%d,
             "collection_method":"charge_automatically","amount_due":%d,"amount_paid":%d,"amount_remaining":%d,"amount_paid_off_stripe":0,"amount_overpaid":0,"total":%d,"subtotal":%d,
             "starting_balance":0,"ending_balance":0,"pre_payment_credit_notes_amount":0,"post_payment_credit_notes_amount":0,"discounts":[],"total_discount_amounts":[],"total_pretax_credit_amounts":[],"total_taxes":[],
             "attempted":true,"attempt_count":1,"auto_advance":false,"next_payment_attempt":null,
             "parent":{"type":"subscription_details","subscription_details":{"subscription":"%s"}}}
            """.formatted(id,settlement.equals("OPEN")?"open":"paid",reason,from.getEpochSecond(),amount,settlement.equals("OPEN")?0:amount,settlement.equals("OPEN")?amount:0,amount,amount,refs.subscription()));
        var line=json("""
            {"id":"il_%s","object":"line_item","livemode":false,"invoice":"%s","subscription":"%s","currency":"eur","quantity":1,"amount":%d,"discount_amounts":[],"pretax_credit_amounts":[],
             "period":{"start":%d,"end":%d},"parent":{"type":"subscription_item_details","subscription_item_details":{"subscription":"%s","subscription_item":"%s","proration":false}},
             "pricing":{"type":"price_details","price_details":{"price":"%s","product":"%s"}}}
            """.formatted(suffix,id,refs.subscription(),amount,from.getEpochSecond(),until.getEpochSecond(),refs.subscription(),refs.item(),refs.price(),refs.product()));
        var allocation=json("{\"id\":\"inpay_"+suffix+"\",\"object\":\"invoice_payment\",\"livemode\":false,\"invoice\":\""+id+"\",\"currency\":\"eur\",\"status\":\"paid\",\"amount_paid\":2000,\"amount_requested\":2000,\"payment\":{\"type\":\"payment_intent\",\"payment_intent\":\"pi_"+suffix+"\"},\"status_transitions\":{\"paid_at\":"+from.getEpochSecond()+"}}");
        var intent=json("{\"id\":\"pi_"+suffix+"\",\"object\":\"payment_intent\",\"livemode\":false,\"customer\":\"cus_billing\",\"currency\":\"eur\",\"amount\":2000,\"amount_received\":2000,\"status\":\"succeeded\",\"capture_method\":\"automatic\",\"payment_method_types\":[\"card\"],\"latest_charge\":\"ch_"+suffix+"\"}");
        var charge=json("{\"id\":\"ch_"+suffix+"\",\"object\":\"charge\",\"livemode\":false,\"customer\":\"cus_billing\",\"payment_intent\":\"pi_"+suffix+"\",\"currency\":\"eur\",\"amount\":2000,\"amount_captured\":2000,\"paid\":true,\"captured\":true,\"status\":\"succeeded\",\"created\":"+from.getEpochSecond()+",\"payment_method_details\":{\"type\":\"card\"},\"amount_refunded\":0,\"refunded\":false}");
        return new InvoiceGraph(suffix,settlement,invoice,line,allocation,intent,charge);
    }
    /** Exact native transport order: complete invoice/line/allocation pages plus reverse allocation join. */
    private void readGraph() { readGraph(remote, invoices); }
    private void readGraph(JsonObject subscription, List<InvoiceGraph> billing) {
        identity();respond("GET","/v1/customers/cus_billing",remoteCustomer);
        respond("GET","/v1/subscriptions/"+subscription.get("id").getAsString(),subscription);
        http.enqueue("GET","/v1/invoices",200,page(billing.stream().map(InvoiceGraph::invoice).toList()));
        for(var graph:billing) {
            respondPage("/v1/invoices/in_"+graph.suffix()+"/lines",List.of(graph.line()));
            respondPage("/v1/invoice_payments",graph.settlement().equals("CASH")?List.of(graph.allocation()):List.of());
            if(graph.settlement().equals("CASH")) {
                respond("GET","/v1/payment_intents/pi_"+graph.suffix(),graph.intent());respond("GET","/v1/charges/ch_"+graph.suffix(),graph.charge());
                respondPage("/v1/invoice_payments",List.of(graph.allocation()));
            }
        }
        if (subscription.has("default_payment_method")) {
            String method=subscription.get("default_payment_method").getAsString();
            respond("GET", "/v1/payment_methods/"+method, trialPaymentMethod(method));
        }
    }
    private void respondPage(String path,List<JsonObject> items) { http.enqueue("GET",path,200,page(items)); }
    private void reconcile() { readGraph();bean(ReconcileSubscriptionUseCase.class).reconcile(new ReconcileSubscriptionUseCase.Command(context(key()),created.subscriptionId())); }
    private void signed(String event) throws Exception {
        signed(event, "invoice.paid", now(), "paid");
    }
    private void signed(String event, String type, Instant eventTime, String invoiceStatus) throws Exception {
        long at=Instant.now().getEpochSecond();
        var envelope=json("""
            {"object":"event","api_version":"2026-09-30.endive","livemode":false,
             "data":{"object":{"id":"in_initial","object":"invoice","livemode":false}}}
            """);
        envelope.addProperty("id",event);
        envelope.addProperty("type",type);
        envelope.addProperty("created",eventTime.getEpochSecond());
        envelope.getAsJsonObject("data").getAsJsonObject("object").addProperty("status", invoiceStatus);
        String body=envelope.toString();
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(StripeAcceptanceConfiguration.SIGNING.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature=HexFormat.of().formatHex(mac.doFinal((at+"."+body).getBytes(StandardCharsets.UTF_8)));
        var response=world.api().mvc.perform(post("/api/v1/provider-webhooks/stripe/"+account.key()).header("Stripe-Signature","t="+at+",v1="+signature).contentType("application/json").content(body)).andReturn().getResponse();
        assertThat(response.getStatus()).as("Billing signed receipt HTTP status; payload withheld").isEqualTo(204);
    }
    private void processReceipt(String event) throws Exception {
        signed(event);
        processReceivedInvoice(event);
    }
    private void processReceivedInvoice(String event) {
        TestClock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));identity();respond("GET","/v1/invoices/in_initial",invoices.getFirst().invoice());readGraph();
        bean(SubscriptionInboxWorker.class).runOnce();
        assertThat(count("provider_inbox","payment_account_id=? and provider_event_id=? and status='PROCESSED'",account.id().value(),event)).isEqualTo(1);
    }
    @When("its initial Stripe invoice is verified through a signed receipt") public void initialPaid() throws Exception {
        remote.addProperty("status","active");invoices.add(invoice("initial","subscription_create",start,end,"CASH"));processReceipt("evt_initial");
    }
    @When("its paid Stripe invoice is reconciled before any webhook arrives")
    public void recoverMissingInvoiceNotification() {
        remote.addProperty("status","active");
        invoices.add(invoice("initial","subscription_create",start,end,"CASH"));
        int before=http.calls().size();
        TestClock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        reconcile();
        assertThat(count("provider_inbox","payment_account_id=?",account.id().value())).isZero();
        assertFreshInvoiceEvidence(before, false);
    }

    private void assertFreshInvoiceEvidence(int before, boolean receivedWebhook) {
        var calls=http.calls().stream().skip(before).toList();
        assertThat(calls).allSatisfy(call -> assertThat(call.method()).isEqualTo("GET"));
        assertThat(calls).extracting(StripeHttpFixture.Call::path).contains("/v1/invoices/in_initial/lines",
            "/v1/payment_intents/pi_initial","/v1/charges/ch_initial");
        assertThat(calls).filteredOn(call -> call.path().equals("/v1/invoices/in_initial"))
            .hasSize(receivedWebhook ? 1 : 0);
        assertThat(calls).filteredOn(call -> call.path().equals("/v1/invoices")).singleElement()
            .satisfies(call -> assertThat(call.query()).containsEntry("subscription","sub_billing"));
        assertThat(calls).filteredOn(call -> call.path().equals("/v1/invoice_payments")).satisfiesExactly(
            forward -> assertThat(forward.query()).containsEntry("invoice","in_initial"),
            reverse -> assertThat(reverse.query()).containsEntry("payment[type]","payment_intent")
                .containsEntry("payment[payment_intent]","pi_initial").doesNotContainKey("invoice"));
    }

    private record PublishedCash(UUID bill, UUID payment, UUID cashEvent, UUID periodEvent) {}

    private PublishedCash originalPublishedCash() {
        assertThat(count("subscription_billing_records","site_id=? and subscription_id=?",
            site.value(),created.subscriptionId())).isOne();
        assertThat(count("subscription_events","site_id=? and subscription_id=? and event_type='PERIOD_PAID'",
            site.value(),created.subscriptionId())).isOne();
        return publishedCash("in_initial", "pi_initial", BillingRecord.Kind.INITIAL);
    }

    private PublishedCash publishedCash(String invoiceReference, String paymentReference, BillingRecord.Kind kind) {
        return publishedCash(invoiceReference,paymentReference,kind,SubscriptionEvent.Source.RECONCILIATION);
    }

    private PublishedCash publishedCash(String invoiceReference,String paymentReference,BillingRecord.Kind kind,SubscriptionEvent.Source source) {
        return jdbc.queryForObject("""
            select b.id, p.id, cash.id, period.id from subscription_billing_records b
            join payments p on p.billing_record_id=b.id and p.subscription_id=b.subscription_id and p.site_id=b.site_id
              and p.payment_account_id=b.payment_account_id and p.provider_payment_reference=?
              and p.subscription_invoice_reference=b.provider_invoice_reference and p.status='SUCCEEDED'
              and p.source='SUBSCRIPTION' and p.amount_minor=2000 and p.currency='EUR'
            join payment_events cash on cash.payment_id=p.id and cash.event_type='PAYMENT_SUCCEEDED'
              and cash.site_id=p.site_id and cash.payment_account_id=p.payment_account_id
            join subscription_events period on period.billing_record_id=b.id and period.subscription_id=b.subscription_id
              and period.site_id=b.site_id and period.payment_account_id=b.payment_account_id
              and period.event_type='PERIOD_PAID' and period.source=?
            join outbox_events pay_callback on pay_callback.id=cash.id and pay_callback.site_id=p.site_id
              and pay_callback.payment_id=p.id and pay_callback.callback_type='payment.succeeded'
            join outbox_events period_callback on period_callback.id=period.id and period_callback.site_id=b.site_id
              and period_callback.subscription_id=b.subscription_id and period_callback.callback_type='subscription.updated'
            where b.site_id=? and b.subscription_id=? and b.payment_account_id=? and p.customer_id=?
              and b.provider_invoice_reference=? and b.kind=? and b.settlement='CONFIRMED_CASH'
              and b.currency='EUR' and b.amount_due_minor=2000
            """,(row,index) -> new PublishedCash(row.getObject(1,UUID.class),row.getObject(2,UUID.class),
                row.getObject(3,UUID.class),row.getObject(4,UUID.class)),
            paymentReference,source.name(),site.value(),created.subscriptionId(),account.id().value(),customer.id().value(),
            invoiceReference,kind.name());
    }

    @When("its delayed paid and earlier finalized Stripe notifications arrive in reverse order")
    public void reversedInvoiceNotifications() throws Exception {
        var original=originalPublishedCash();
        var paidTime=Instant.now().minusSeconds(1);
        signed("evt_delayedpaid", "invoice.paid", paidTime, "paid");
        int before=http.calls().size();
        processReceivedInvoice("evt_delayedpaid");
        assertFreshInvoiceEvidence(before, true);
        assertThat(originalPublishedCash()).isEqualTo(original);
        unchanged();
        signed("evt_earlierfinalized", "invoice.finalized", paidTime.minusSeconds(1), "open");
        before=http.calls().size();
        processReceivedInvoice("evt_earlierfinalized");
        assertFreshInvoiceEvidence(before, true);
        assertThat(originalPublishedCash()).isEqualTo(original);
        assertThat(count("provider_inbox","payment_account_id=? and status='PROCESSED'",account.id().value())).isEqualTo(2);
        assertThat(jdbc.queryForList("""
            select provider_event_id from provider_inbox where payment_account_id=? order by occurred_at
            """,String.class,account.id().value())).containsExactly("evt_earlierfinalized","evt_delayedpaid");
        assertThat(access().reason()).isEqualTo(AccessDecision.Reason.PAID);
        assertThat(subscription().paidThrough()).isEqualTo(end);
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

    private record TrialPublication(String invoice, String eligibility, String proof, String started) {}

    private TrialPublication trialPublication() {
        return jdbc.queryForObject("""
            select to_jsonb(b)::text, to_jsonb(e)::text, to_jsonb(p)::text, to_jsonb(j)::text
            from subscription_billing_records b
            join stripe_subscription_trial_proofs p on p.site_id=b.site_id and p.subscription_id=b.subscription_id
              and p.payment_account_id=b.payment_account_id and p.invoice_reference=b.provider_invoice_reference
            join subscription_trial_eligibility e on e.site_id=b.site_id and e.customer_id=p.customer_id
              and e.product_id=b.product_id and e.reserved_by_operation=p.operation_id and e.consumed_at is not null
            join subscription_events j on j.site_id=b.site_id and j.subscription_id=b.subscription_id
              and j.payment_account_id=b.payment_account_id and j.event_type='TRIAL_STARTED'
              and j.source='RECONCILIATION'
            where b.site_id=? and b.subscription_id=? and b.payment_account_id=? and p.operation_id=?
              and p.trial_start=? and p.requested_end=? and b.provider_invoice_reference='in_trial'
              and b.kind='INITIAL' and b.settlement='ZERO_AMOUNT' and b.currency='EUR' and b.amount_due_minor=0
            """, (row,index) -> new TrialPublication(row.getString(1),row.getString(2),row.getString(3),row.getString(4)),
            site.value(),created.subscriptionId(),account.id().value(),created.operationId(),start.getEpochSecond(),end.getEpochSecond());
    }

    @When("the same customer requests a required-method Stripe trial for a second product")
    public void startRequiredTrialSetup() throws NoSuchAlgorithmException {
        siblingTrial=subscription();
        siblingPublication=trialPublication();
        requiredTrialProduct=new ProductId(UUID.randomUUID());
        requiredTrialPrice=new PriceId(UUID.randomUUID());
        jdbc.update("""
            insert into products(id,site_id,product_key,name,status,created_at,updated_at)
            select ?,site_id,'billing-required','Required trial','ACTIVE',created_at,updated_at from products where id=?
            """,requiredTrialProduct.value(),product.value());
        jdbc.update("""
            insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,
              trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at)
            select ?,site_id,?,'required-monthly',price_version,price_type,amount_minor,currency,billing_interval,
              trial_days,'REQUIRED_BEFORE_TRIAL',tax_mode,status,published_at,created_at,updated_at from prices where id=?
            """,requiredTrialPrice.value(),requiredTrialProduct.value(),price.value());
        bean(ProviderCatalogMappingPort.class).save(new ProviderCatalogMapping(account.id(),requiredTrialProduct,
            new ProviderProductRef(REQUIRED.product()),requiredTrialPrice,new ProviderPriceRef(REQUIRED.price()),now()));
        requiredTrialSetup=json("""
            {"id":"seti_required","object":"setup_intent","livemode":false,"customer":"cus_billing",
             "usage":"off_session","payment_method_types":["card"],"status":"requires_payment_method"}
            """);
        identity();
        http.enqueue("POST","/v1/setup_intents",200,call -> {
            requiredTrialSetup.add("metadata",fixtureMetadata(call,List.of("operation_id","subscription_id")));
            requiredTrialSetup.addProperty("created",firstDispatch(call));
            return requiredTrialSetup.toString();
        });
        var createKey=key();
        requiredTrialCreateDigest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(createKey.getBytes(StandardCharsets.UTF_8)));
        requiredTrial=bean(CreateSubscriptionUseCase.class).create(
            new CreateSubscriptionUseCase.Command(context(createKey),requiredTrialPrice,true));
    }

    @Then("the second trial has only its original setup receipt without cash or access")
    public void requiredSetupRemainsPending() {
        var original=operation(requiredTrial.operationId());
        var local=subscription(requiredTrial.subscriptionId());
        var steps=bean(ProviderOperationStore.class);
        var proofs=bean(SubscriptionBillingProofStore.class);
        var setupStep=steps.find(account.id(),original.providerOperationKey()+":SETUP").orElseThrow();
        pendingRequiredSetup=proofs.setup(account.id(),original.id()).orElseThrow();
        assertThat(requiredTrial.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        assertThat(original.kind()).isEqualTo(SubscriptionOperation.Kind.CREATE);
        assertThat(original.providerReference()).isNull();
        assertThat(setupStep.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(setupStep.resourceId()).isEqualTo(original.id());
        assertThat(pendingRequiredSetup).isEqualTo(new SubscriptionBillingProofStore.Setup(original.id(),local.id(),account.id(),setupStep.id(),"seti_required",null));
        assertThat(steps.find(account.id(),original.providerOperationKey())).isEmpty();
        assertThat(proofs.trial(account.id(),local.id())).isEmpty();
        assertThat(local.billingStatus()).isEqualTo(Subscription.Status.INCOMPLETE);
        assertThat(local.providerReference()).isNull();
        assertThat(local.trial()).isNull();
        assertThat(bean(QuerySubscriptionsUseCase.class).get(actor,site,local.id()).access().allowed()).isFalse();
        assertThat(bean(TrialEligibilityStore.class).find(site,customer.id(),requiredTrialProduct)).hasValueSatisfying(eligibility -> {
            assertThat(eligibility.reservedByOperation()).isEqualTo(original.id());
            assertThat(eligibility.consumedAt()).isNull();
        });
        assertThat(count("subscription_billing_records","subscription_id=?",local.id())).isZero();
        assertThat(http.calls().stream().filter(call -> !call.method().equals("GET")))
            .extracting(call -> call.method()+" "+call.path())
            .containsExactly("POST /v1/customers","POST /v1/subscriptions","POST /v1/setup_intents");
        assertThat(cash()).isZero();
    }

    @When("that exact setup succeeds and its original Stripe zero invoice is reconciled")
    public void verifyRequiredTrial() {
        var providerNow=Instant.now().truncatedTo(ChronoUnit.MICROS);
        StripeAcceptanceConfiguration.providerTime(providerNow);
        requiredTrialSetup.addProperty("status","succeeded");
        requiredTrialSetup.addProperty("payment_method","pm_required");
        identity();
        respond("GET","/v1/setup_intents/seti_required",requiredTrialSetup);
        respond("GET","/v1/payment_methods/pm_required",trialPaymentMethod("pm_required"));
        identity();
        respond("GET","/v1/customers/cus_billing",remoteCustomer);
        identity();
        requiredTrialRemote=subscriptionJson(REQUIRED,requiredTrialPrice);
        http.enqueue("POST","/v1/subscriptions",200,call -> {
            long dispatched=firstDispatch(call);
            var from=Instant.ofEpochSecond(dispatched);
            var until=Instant.ofEpochSecond(Long.parseLong(call.form().get("trial_end")));
            requiredTrialPeriod=new Subscription.Period(from,until);
            requiredTrialRemote.addProperty("created",dispatched);
            requiredTrialRemote.addProperty("status","trialing");
            requiredTrialRemote.addProperty("trial_start",dispatched);
            requiredTrialRemote.addProperty("trial_end",until.getEpochSecond());
            requiredTrialRemote.add("trial_settings",json("{\"end_behavior\":{\"missing_payment_method\":\"pause\"}}"));
            requiredTrialRemote.addProperty("default_payment_method","pm_required");
            requiredTrialRemote.add("metadata",fixtureMetadata(call,List.of("account_id","site_id","customer_id","subscription_id","operation_id")));
            setPeriod(requiredTrialRemote,from,until);
            return requiredTrialRemote.toString();
        });
        var continued=bean(ContinueOriginalSubscriptionSetupUseCase.class).api(context(key()),requiredTrial.subscriptionId(),requiredTrial.operationId());
        assertThat(continued.operationId()).isEqualTo(requiredTrial.operationId());
        assertThat(continued.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        var providerSecond=providerNow.truncatedTo(ChronoUnit.SECONDS);
        assertThat(requiredTrialPeriod).isEqualTo(new Subscription.Period(providerSecond,providerSecond.plus(Duration.ofDays(7))));
        assertThat(bean(QuerySubscriptionsUseCase.class).get(actor,site,requiredTrial.subscriptionId()).access().allowed()).isFalse();
        TestClock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        var zero=invoice("requiredtrial","subscription_create",requiredTrialPeriod.start(),requiredTrialPeriod.end(),"ZERO",REQUIRED);
        readGraph(requiredTrialRemote,List.of(zero));
        bean(ReconcileSubscriptionUseCase.class).reconcile(new ReconcileSubscriptionUseCase.Command(context(key()),requiredTrial.subscriptionId()));
    }

    @Then("only the second trial owns the verified card and the sibling trial is unchanged")
    public void requiredTrialMethodIsolation() {
        var original=operation(requiredTrial.operationId());
        var local=subscription(requiredTrial.subscriptionId());
        var proofs=bean(SubscriptionBillingProofStore.class);
        var setup=new SubscriptionBillingProofStore.Setup(original.id(),local.id(),account.id(),pendingRequiredSetup.step(),"seti_required","pm_required");
        var createStep=bean(ProviderOperationStore.class).find(account.id(),original.providerOperationKey()).orElseThrow();
        assertThat(original.status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(original.providerReference()).isEqualTo(REQUIRED.subscription());
        assertThat(createStep.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(createStep.resourceId()).isEqualTo(original.id());
        assertThat(createStep.providerReference()).isEqualTo(REQUIRED.subscription());
        assertThat(proofs.setup(account.id(),original.id())).contains(setup);
        assertThat(proofs.method(account.id(),local.id(),"pm_required")).contains(setup);
        assertThat(proofs.method(account.id(),siblingTrial.id(),"pm_required")).isEmpty();
        assertThat(proofs.trial(account.id(),local.id())).contains(new SubscriptionBillingProofStore.Trial(original.id(),local.id(),account.id(),
            requiredTrialPeriod.end().getEpochSecond(),createStep.id(),REQUIRED.subscription(),REQUIRED.item(),"in_requiredtrial",requiredTrialPeriod.start().getEpochSecond()));
        assertThat(bean(TrialEligibilityStore.class).find(site,customer.id(),requiredTrialProduct)).hasValueSatisfying(eligibility -> {
            assertThat(eligibility.reservedByOperation()).isEqualTo(original.id());
            assertThat(eligibility.consumedAt()).isNotNull();
        });
        var decision=bean(QuerySubscriptionsUseCase.class).get(actor,site,local.id()).access();
        assertThat(decision.reason()).isEqualTo(AccessDecision.Reason.TRIAL);
        assertThat(decision.entitledPriceId()).isEqualTo(requiredTrialPrice);
        assertThat(decision.validUntil()).isEqualTo(SubscriptionAccessPolicy.validUntil(now(),requiredTrialPeriod.end()));
        assertThat(local.trial()).isEqualTo(requiredTrialPeriod);
        assertThat(local.paidThrough()).isNull();
        assertThat(count("subscription_billing_records","subscription_id=? and provider_invoice_reference='in_requiredtrial' and settlement='ZERO_AMOUNT' and amount_due_minor=0",local.id())).isOne();
        assertThat(cash()).isZero();
        assertThat(count("payment_events","site_id=?",site.value())).isZero();

        // A Customer-level default is shared in the provider object, while each Subscription still owns its own method.
        remoteCustomer.add("invoice_settings",json("{\"default_payment_method\":\"pm_required\"}"));
        int before=http.calls().size();
        readGraph();
        var siblingRead=bean(SubscriptionProviderRegistry.class).forAccount(account).retrieve(
            new SubscriptionProviderModels.Lookup(account,siblingTrial.id(),siblingTrial.providerReference(),operation(created.operationId()).providerOperationKey()));
        assertThat(siblingRead.paymentMethodReady()).isFalse();
        assertThat(http.calls().subList(before,http.calls().size())).allSatisfy(call -> {
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.path()).doesNotStartWith("/v1/payment_methods");
        });
        assertThat(subscription()).isEqualTo(siblingTrial);
        assertThat(trialPublication()).isEqualTo(siblingPublication);
        assertThat(proofs.method(account.id(),siblingTrial.id(),"pm_required")).isEmpty();
        assertThat(proofs.method(account.id(),local.id(),"pm_required")).contains(setup);
        var writes=http.calls().stream().filter(call -> !call.method().equals("GET")).toList();
        assertThat(writes).extracting(call -> call.method()+" "+call.path())
            .containsExactly("POST /v1/customers","POST /v1/subscriptions","POST /v1/setup_intents","POST /v1/subscriptions");
        assertThat(writes.get(1).form()).doesNotContainKey("default_payment_method");
        assertThat(writes.get(2).header("Idempotency-Key")).isEqualTo(original.providerOperationKey()+":SETUP");
        assertThat(writes.get(2).form()).containsEntry("metadata[subscription_id]",local.id().toString()).containsEntry("metadata[operation_id]",original.id().toString());
        assertThat(writes.get(3).header("Idempotency-Key")).isEqualTo(original.providerOperationKey());
        assertThat(writes.get(3).form()).containsEntry("default_payment_method","pm_required")
            .containsEntry("metadata[subscription_id]",local.id().toString()).containsEntry("metadata[operation_id]",original.id().toString());
        assertTrialStartCallback(local,original.id(),"in_requiredtrial");
        assertTrialStartCallback(siblingTrial,created.operationId(),"in_trial");
        assertThat(count("outbox_events","site_id=?",site.value())).isEqualTo(2);
        deliverCallbacks(Set.of(local.id(),siblingTrial.id()));
    }

    @Then("the PostgreSQL proof verifies only the exact required trial and no cash")
    public void requiredTrialSqlProof() throws Exception {
        var id=requiredTrial.subscriptionId();
        var bill=jdbc.queryForObject("select id from subscription_billing_records where site_id=? and subscription_id=? and provider_invoice_reference='in_requiredtrial'",
            UUID.class,site.value(),id);
        var event=jdbc.queryForObject("select id from subscription_events where site_id=? and subscription_id=? and event_type='TRIAL_STARTED'",
            UUID.class,site.value(),id);
        StripeBillingRequiredTrialSqlAssertions.verify(bean(javax.sql.DataSource.class),List.of(
            REQUIRED_TRIAL_PROOF_RUN,account.id().value(),site.value(),customer.id().value(),requiredTrialProduct.value(),requiredTrialPrice.value(),
            id,requiredTrial.operationId(),requiredTrialCreateDigest,"cus_billing","seti_required","pm_required",REQUIRED.subscription(),REQUIRED.item(),
            "in_requiredtrial",Timestamp.from(requiredTrialPeriod.start()),Timestamp.from(requiredTrialPeriod.end())),
            bill,event,subscription(id).revision(),REQUIRED.price(),REQUIRED.product());
    }

    private void assertTrialStartCallback(Subscription trial, UUID operationId, String invoice) {
        var payloads=jdbc.queryForList("""
            select o.payload::text from subscription_events e
            join outbox_events o on o.id=e.id and o.subscription_event_id=e.id
              and o.site_id=e.site_id and o.subscription_id=e.subscription_id and o.payment_account_id=e.payment_account_id
            join stripe_subscription_trial_proofs p on p.site_id=e.site_id and p.subscription_id=e.subscription_id
              and p.payment_account_id=e.payment_account_id and p.operation_id=? and p.invoice_reference=?
            join subscription_billing_records b on b.site_id=p.site_id and b.subscription_id=p.subscription_id
              and b.payment_account_id=p.payment_account_id and b.provider_invoice_reference=p.invoice_reference
            where e.site_id=? and e.subscription_id=? and e.payment_account_id=? and e.event_type='TRIAL_STARTED'
              and e.source='RECONCILIATION' and e.revision=?
              and b.kind='INITIAL' and b.settlement='ZERO_AMOUNT' and b.amount_due_minor=0
              and (e.billing_record_id is null or e.billing_record_id=b.id)
              and o.source_kind='SUBSCRIPTION' and o.callback_type='subscription.updated'
              and o.payload->'schema_version'='4'::jsonb and o.payload->'revision'=to_jsonb(e.revision)
              and o.payload->>'event_id'=e.id::text and o.payload->>'event_type'=o.callback_type
              and o.payload->>'site_id'=e.site_id::text and o.payload->>'subscription_id'=e.subscription_id::text
              and o.payload->>'customer_id'=? and o.payload->>'product_id'=?
              and o.payload->>'state'='TRIALING' and o.payload->'access_allowed'='true'::jsonb
              and (o.payload->>'occurred_at')::timestamptz=e.occurred_at
              and o.payload->'billing_record_id'=coalesce(to_jsonb(e.billing_record_id),'null'::jsonb)
              and e.payment_id is null and o.payload->'payment_id'='null'::jsonb
              and (o.payload->>'access_valid_until')::timestamptz=least(e.occurred_at+interval '5 minutes',?::timestamptz)
            """,String.class,operationId,invoice,site.value(),trial.id(),account.id().value(),trial.revision(),
            customer.id().value().toString(),trial.productId().value().toString(),Timestamp.from(trial.trial().end()));
        assertThat(payloads).singleElement().satisfies(payload -> assertThat(json(payload).keySet()).containsExactlyInAnyOrder(
            "schema_version","event_id","event_type","site_id","subscription_id","customer_id","product_id","revision",
            "state","access_allowed","access_valid_until","occurred_at","billing_record_id","payment_id"));
    }

    private static JsonObject fixtureMetadata(StripeHttpFixture.Call call, List<String> fields) {
        var metadata=new JsonObject();
        for (String field:fields) metadata.addProperty(field,call.form().get("metadata["+field+"]"));
        return metadata;
    }

    private long firstDispatch(StripeHttpFixture.Call call) {
        return jdbc.queryForObject("select floor(extract(epoch from first_dispatched_at))::bigint from provider_operations where operation_key=?",
            Long.class,call.header("Idempotency-Key"));
    }

    @When("the trial reaches its exact end without a payment method")
    public void reachTrialEnd() {
        originalTrial=trialPublication();
        int callsBeforeAccess=http.calls().size();
        TestClock.set(end.minusSeconds(1));
        authenticate();
        assertAccess(Subscription.Status.TRIALING,AccessDecision.Reason.TRIAL,end);
        TestClock.set(end);
        assertAccess(Subscription.Status.TRIALING,AccessDecision.Reason.EXPIRED,null);
        assertThat(http.calls()).hasSize(callsBeforeAccess);
    }

    private void assertAccess(Subscription.Status state, AccessDecision.Reason reason, Instant validUntil) {
        var queries=bean(QuerySubscriptionsUseCase.class);
        var view=queries.get(actor,site,created.subscriptionId());
        assertThat(view.subscription().billingStatus()).isEqualTo(state);
        var decision=queries.access(actor,site,customer.id(),product);
        assertThat(decision).isEqualTo(view.access());
        assertThat(decision.reason()).isEqualTo(reason);
        assertThat(decision.allowed()).isEqualTo(validUntil!=null);
        assertThat(decision.evaluatedAt()).isEqualTo(now());
        assertThat(decision.validUntil()).isEqualTo(validUntil);
    }

    @When("Stripe reports the original trial subscription paused")
    public void reconcilePausedTrial() {
        int before=http.calls().size();
        remote.addProperty("status","paused");
        reconcile();
        var calls=http.calls().stream().skip(before).toList();
        assertThat(calls).extracting(StripeHttpFixture.Call::path).contains("/v1/subscriptions/sub_billing",
            "/v1/invoices","/v1/invoices/in_trial/lines","/v1/invoice_payments");
    }

    @Then("the same subscription is paused without debt or a new invoice or payment")
    public void pausedTrial() {
        assertAccess(Subscription.Status.PAUSED,AccessDecision.Reason.PAUSED,null);
        var paused=subscription();
        assertThat(paused.providerReference()).isEqualTo("sub_billing");
        assertThat(paused.trial()).isEqualTo(new Subscription.Period(start,end));
        assertThat(paused.paidThrough()).isNull();
        assertThat(paused.debt()).isNull();
        assertThat(trialPublication()).isEqualTo(originalTrial);
        assertThat(count("subscription_billing_records","subscription_id=?",created.subscriptionId())).isOne();
        assertThat(jdbc.queryForList("""
            select event_type from subscription_events where subscription_id=? order by sequence_number
            """,String.class,created.subscriptionId())).containsExactly("TRIAL_STARTED","PAUSED");
        assertThat(count("payments","site_id=?",site.value())).isZero();
        assertThat(count("payment_events","site_id=?",site.value())).isZero();
        assertThat(count("outbox_events","site_id=? and callback_type='payment.succeeded'",site.value())).isZero();
        var calls=http.calls();
        var creates=calls.stream().filter(call -> call.method().equals("POST") && call.path().equals("/v1/subscriptions")).toList();
        assertThat(creates).hasSize(1);
        var trialCreate=creates.getFirst();
        assertThat(trialCreate.form().get("trial_settings[end_behavior][missing_payment_method]")).isEqualTo("pause");
        assertThat(trialCreate.form().containsKey("default_payment_method")).isFalse();
        assertThat(calls.subList(calls.indexOf(trialCreate)+1,calls.size()))
            .allSatisfy(call -> assertThat(call.method()).isEqualTo("GET"));
    }

    private JsonObject trialPaymentMethod() { return trialPaymentMethod("pm_trial"); }
    private JsonObject trialPaymentMethod(String method) {
        return json("""
            {"id":"%s","object":"payment_method","livemode":false,"customer":"cus_billing",
             "type":"card","card":{"brand":"visa","last4":"4242"}}
            """.formatted(method));
    }

    @When("the customer sets up a card for that Stripe subscription")
    public void setUpTrialCard() {
        StripeAcceptanceConfiguration.providerTime(end);
        var intent = json("""
            {"id":"seti_trial","object":"setup_intent","livemode":false,"customer":"cus_billing",
             "usage":"off_session","payment_method_types":["card"],"status":"requires_payment_method"}
            """);
        identity();
        http.enqueue("POST", "/v1/setup_intents", 200, call -> {
            var metadata = new JsonObject();
            metadata.addProperty("operation_id", call.form().get("metadata[operation_id]"));
            metadata.addProperty("subscription_id", call.form().get("metadata[subscription_id]"));
            intent.add("metadata", metadata);
            intent.addProperty("created", end.getEpochSecond());
            return intent.toString();
        });
        trialSetup = bean(SetupSubscriptionMethodUseCase.class).setup(
            new SetupSubscriptionMethodUseCase.Command(context(key()), created.subscriptionId(), true));
        assertThat(trialSetup.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        assertThat(http.calls().stream().filter(call -> call.path().equals("/v1/setup_intents")))
            .singleElement().satisfies(call -> {
                assertThat(call.method()).isEqualTo("POST");
                assertThat(call.header("Idempotency-Key")).isEqualTo(operation(trialSetup.operationId()).providerOperationKey() + ":SETUP");
                assertThat(call.form()).containsEntry("customer", "cus_billing").containsEntry("usage", "off_session")
                    .containsEntry("metadata[subscription_id]", created.subscriptionId().toString());
            });
        assertThat(access().reason()).isEqualTo(AccessDecision.Reason.PAUSED);
        assertThat(cash()).isZero();

        intent.addProperty("status", "succeeded");
        intent.addProperty("payment_method", "pm_trial");
        identity();
        respond("GET", "/v1/setup_intents/seti_trial", intent);
        respond("GET", "/v1/payment_methods/pm_trial", trialPaymentMethod());
        identity();
        remote.addProperty("default_payment_method", "pm_trial");
        respond("POST", "/v1/subscriptions/sub_billing", remote);
        var continued = bean(ContinueOriginalSubscriptionSetupUseCase.class).api(
            context(key()), created.subscriptionId(), trialSetup.operationId());
        assertThat(continued.operationId()).isEqualTo(trialSetup.operationId());
        assertThat(continued.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        readGraph();
        respond("GET", "/v1/subscriptions/sub_billing", remote);
        bean(ReconcileSubscriptionUseCase.class).reconcile(
            new ReconcileSubscriptionUseCase.Command(context(key()), created.subscriptionId()));
    }

    @Then("the card is saved without resuming the subscription or collecting cash")
    public void trialSetupDoesNotResume() {
        assertThat(operation(trialSetup.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        var proof = bean(SubscriptionBillingProofStore.class).setup(account.id(), trialSetup.operationId()).orElseThrow();
        assertThat(proof.reference()).isEqualTo("seti_trial");
        assertThat(proof.method()).isEqualTo("pm_trial");
        assertThat(proof.subscription()).isEqualTo(created.subscriptionId());
        assertThat(http.calls().stream().filter(call -> call.method().equals("POST") && call.path().equals("/v1/subscriptions/sub_billing")))
            .singleElement().satisfies(call -> {
                assertThat(call.header("Idempotency-Key")).isEqualTo(operation(trialSetup.operationId()).providerOperationKey() + ":METHOD");
                assertThat(call.form()).containsExactlyEntriesOf(Map.of("default_payment_method", "pm_trial"));
            });
        assertThat(http.calls()).noneMatch(call -> call.path().endsWith("/resume") || call.path().endsWith("/pay"));
        assertUnpaidTrialPause();
        assertThat(count("subscription_billing_records", "subscription_id=?", created.subscriptionId())).isOne();
    }

    private void assertUnpaidTrialPause() {
        assertAccess(Subscription.Status.PAUSED, AccessDecision.Reason.PAUSED, null);
        assertThat(subscription().paidThrough()).isNull();
        assertThat(subscription().debt()).isNull();
        assertThat(trialPublication()).isEqualTo(originalTrial);
        assertThat(cash()).isZero();
        assertThat(count("payment_events", "site_id=?", site.value())).isZero();
        assertThat(count("outbox_events", "site_id=? and callback_type='payment.succeeded'", site.value())).isZero();
    }

    @When("the customer explicitly resumes and Stripe leaves its new invoice open")
    public void resumeTrialWithOpenInvoice() {
        Instant anchor = end.plusSeconds(1);
        TestClock.set(anchor);
        StripeAcceptanceConfiguration.providerTime(anchor);
        resumedPeriod = new Subscription.Period(anchor, anchor.atOffset(ZoneOffset.UTC).plusMonths(1).toInstant());
        var open = invoice("resume", "subscription_cycle", resumedPeriod.start(), resumedPeriod.end(), "OPEN");
        readGraph();
        identity();
        respond("GET", "/v1/subscriptions/sub_billing", remote);
        respond("GET", "/v1/payment_methods/pm_trial", trialPaymentMethod());
        var resumeResponse = remote.deepCopy();
        resumeResponse.addProperty("latest_invoice", "in_resume");
        respond("POST", "/v1/subscriptions/sub_billing/resume", resumeResponse);
        respond("GET", "/v1/invoices/in_resume", open.invoice());
        respondPage("/v1/invoices/in_resume/lines", List.of(open.line()));
        trialResume = bean(ResumeSubscriptionUseCase.class).resume(
            new ResumeSubscriptionUseCase.Command(context(key()), created.subscriptionId(), true));
        assertThat(trialResume.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        assertThat(http.calls().stream().filter(call -> call.path().equals("/v1/subscriptions/sub_billing/resume")))
            .singleElement().satisfies(call -> {
                assertThat(call.method()).isEqualTo("POST");
                assertThat(call.header("Idempotency-Key")).isEqualTo(operation(trialResume.operationId()).providerOperationKey());
                assertThat(call.form()).containsExactlyInAnyOrderEntriesOf(Map.of("billing_cycle_anchor[type]", "now",
                    "payment_behavior", "resume_on_payment_success", "proration_behavior", "none"));
            });
        invoices.add(open);
        reconcile();
    }

    @Then("the resumption remains pending without paid access")
    public void trialResumeStillPending() {
        assertUnpaidTrialPause();
        assertThat(operation(trialResume.operationId()).status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        var proof = bean(SubscriptionBillingProofStore.class).resume(account.id(), trialResume.operationId()).orElseThrow();
        assertThat(proof.subscription()).isEqualTo(created.subscriptionId());
        assertThat(proof.reference()).isEqualTo("sub_billing");
        assertThat(proof.invoice()).isEqualTo("in_resume");
        assertThat(proof.period()).isEqualTo(resumedPeriod);
    }

    @When("the original resumption invoice has verified cash")
    public void reconcilePaidResumption() {
        remote.addProperty("status", "active");
        remote.addProperty("latest_invoice", "in_resume");
        setPeriod(resumedPeriod.start(), resumedPeriod.end());
        invoices.set(1, invoice("resume", "subscription_cycle", resumedPeriod.start(), resumedPeriod.end(), "CASH"));
        reconcile();
    }

    @Then("that payment restores access once and preserves the original zero trial invoice")
    public void trialResumed() {
        assertAccess(Subscription.Status.ACTIVE, AccessDecision.Reason.PAID, now().plus(Duration.ofMinutes(5)));
        assertThat(subscription().currentPeriod()).isEqualTo(resumedPeriod);
        assertThat(subscription().paidThrough()).isEqualTo(resumedPeriod.end());
        assertThat(subscription().debt()).isNull();
        assertThat(subscription().trial()).isEqualTo(new Subscription.Period(start, end));
        assertThat(trialPublication()).isEqualTo(originalTrial);
        assertThat(operation(trialResume.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(cash()).isOne();
        assertThat(count("subscription_billing_records", "subscription_id=?", created.subscriptionId())).isEqualTo(2);
        assertThat(count("payment_events", "site_id=? and event_type='PAYMENT_SUCCEEDED'", site.value())).isOne();
        assertThat(count("subscription_events", "subscription_id=? and event_type='PERIOD_PAID'", created.subscriptionId())).isOne();
        assertThat(count("outbox_events", "site_id=? and callback_type='payment.succeeded'", site.value())).isOne();

        var publication = publishedCash("in_resume", "pi_resume", BillingRecord.Kind.RENEWAL);
        var paid = financialEvidence();
        reconcile();
        assertThat(financialEvidence()).isEqualTo(paid);
        assertThat(publishedCash("in_resume", "pi_resume", BillingRecord.Kind.RENEWAL)).isEqualTo(publication);
        assertThat(trialPublication()).isEqualTo(originalTrial);
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
        readGraph();
        // The observed cancellation event selects the withdrawal form before the claimed recheck.
        for(int read=0;read<(withdraw?2:1);read++) {
            identity();respond("GET","/v1/customers/cus_billing",remoteCustomer);respond("GET","/v1/subscriptions/sub_billing",remote);
        }
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
        assertThat(subscription().cancellation()).isNull();assertThat(subscription().paidThrough()).isEqualTo(end);assertThat(cash()).isEqualTo(1);
        var withdrawn=operation(withdrawal.operationId());
        assertThat(withdrawn.status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(withdrawn.fingerprint()).startsWith("v2.cancel.withdraw.");
        assertThat(count("stripe_subscription_cancellation_proofs","subscription_id=?",created.subscriptionId())).isEqualTo(2);
        var writes=http.calls().stream().filter(c->c.method().equals("POST")&&c.path().equals("/v1/subscriptions/sub_billing")).toList();assertThat(writes).hasSize(2);
        assertThat(writes.get(0).header("Idempotency-Key")).isEqualTo(operation(cancel.operationId()).providerOperationKey());assertThat(writes.get(1).header("Idempotency-Key")).isEqualTo(withdrawn.providerOperationKey());
        assertThat(writes.get(0).form()).containsEntry("cancel_at",Long.toString(end.getEpochSecond())).containsEntry("proration_behavior","none");
        assertThat(writes.get(1).form()).containsExactlyInAnyOrderEntriesOf(Map.of("cancel_at","","proration_behavior","none"));
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
    private int graphRequests() {
        return 4+(remote.has("default_payment_method")?1:0)+invoices.stream().mapToInt(i->i.settlement().equals("CASH")?5:2).sum();
    }

    private PriceId targetPrice(String key) { return targetPrice(key,4000,"price_upgrade"); }

    private PriceId targetPrice(String key,long amount,String providerPrice) {
        var target=new PriceId(UUID.randomUUID());
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,?,1,'SUBSCRIPTION',?,'EUR','MONTH',0,'OPTIONAL_DURING_TRIAL','INCLUSIVE','PUBLISHED',?,?,?)",target.value(),site.value(),product.value(),key,amount,Timestamp.from(now()),Timestamp.from(now()),Timestamp.from(now()));
        bean(ProviderCatalogMappingPort.class).save(new ProviderCatalogMapping(account.id(),product,new ProviderProductRef("prod_billing"),target,new ProviderPriceRef(providerPrice),now()));
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
    private JsonObject planPhase(Instant from,Instant until,String nativePrice) {
        return json("""
            {"start_date":%d,"end_date":%d,"proration_behavior":"none","items":[{"price":"%s","quantity":1}]}
            """.formatted(from.getEpochSecond(),until.getEpochSecond(),nativePrice));
    }

    private JsonObject planSchedule(boolean configured) {
        var schedule=json("""
            {"id":"%s","object":"subscription_schedule","livemode":false,"customer":"cus_billing",
             "subscription":"sub_billing","status":"active","end_behavior":"release",
             "current_phase":{"start_date":%d,"end_date":%d},
             "default_settings":{"collection_method":"charge_automatically"}}
            """.formatted(PLAN_SCHEDULE,start.getEpochSecond(),end.getEpochSecond()));
        var phases=new JsonArray();
        phases.add(planPhase(start,end,"price_billing"));
        if(configured)phases.add(planPhase(end,end.atOffset(ZoneOffset.UTC).plusMonths(1).toInstant(),"price_downgrade"));
        schedule.add("phases",phases);
        return schedule;
    }

    @When("the customer schedules an allowed Stripe downgrade for the next period")
    public void scheduleDowngrade() {
        beforePlan=http.calls().size();
        originalPlanCash=publishedCash("in_initial","pi_initial",BillingRecord.Kind.INITIAL,SubscriptionEvent.Source.PROVIDER_WEBHOOK);
        downgradePrice=targetPrice("billing-downgrade",1500,"price_downgrade");
        var policies=bean(SubscriptionPolicyPort.class);
        policies.saveTransition(new PlanTransition(site,product,price,downgradePrice,PlanTransition.Direction.DOWNGRADE,false,policies.load(site).version()));
        readHead();
        // The fixture business clock is fixed after invoice reconciliation. Keep this preview on that sample.
        StripeAcceptanceConfiguration.providerTime(now());
        try {
            downgradeQuote=bean(PreviewSubscriptionChangeUseCase.class).preview(
                new PreviewSubscriptionChangeUseCase.Query(context(null),created.subscriptionId(),downgradePrice,ChangeTiming.NEXT_PERIOD));
        } finally {
            StripeAcceptanceConfiguration.providerTime(null);
        }
        assertThat(downgradeQuote.timing()).isEqualTo(ChangeTiming.NEXT_PERIOD);
        assertThat(downgradeQuote.dueNowMinor()).isZero();
        assertThat(downgradeQuote.nextAmountMinor()).isEqualTo(1500);
        assertThat(downgradeQuote.effectiveAt()).isEqualTo(end);

        readHead(); // Fresh quote before the application claims the mutation.
        readHead(); // Fresh subscription before the provider schedule create.
        http.enqueue("POST","/v1/subscription_schedules",200,call -> {
            remote.addProperty("schedule",PLAN_SCHEDULE);
            return planSchedule(false).toString();
        });
        var schedulePath="/v1/subscription_schedules/"+PLAN_SCHEDULE;
        respond("GET",schedulePath,planSchedule(false));
        respond("POST",schedulePath,planSchedule(true));
        respond("GET",schedulePath,planSchedule(true));
        scheduledChange=bean(ChangeSubscriptionPlanUseCase.class).change(
            new ChangeSubscriptionPlanUseCase.Command(context(key()),created.subscriptionId(),downgradeQuote.id()));
        assertThat(scheduledChange.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        assertThat(subscription().pendingChange().state()).isNotEqualTo(Subscription.ChangeState.SCHEDULED);
    }

    private void reconcilePlan(JsonObject providerSchedule) {
        readGraph();
        respond("GET","/v1/subscription_schedules/"+PLAN_SCHEDULE,providerSchedule);
        bean(ReconcileSubscriptionUseCase.class).reconcile(new ReconcileSubscriptionUseCase.Command(context(key()),created.subscriptionId()));
    }

    private void assertOriginalPlanSettlement() {
        var subscription=subscription();
        assertThat(subscription.providerReference()).isEqualTo("sub_billing");
        assertThat(subscription.currentPeriod()).isEqualTo(new Subscription.Period(start,end));
        assertThat(subscription.paidThrough()).isEqualTo(end);
        assertThat(subscription.billingPrice().priceId()).isEqualTo(price);
        assertThat(subscription.entitledPrice().priceId()).isEqualTo(price);
        assertThat(access().reason()).isEqualTo(AccessDecision.Reason.PAID);
        assertThat(cash()).isEqualTo(1);
        assertThat(publishedCash("in_initial","pi_initial",BillingRecord.Kind.INITIAL,SubscriptionEvent.Source.PROVIDER_WEBHOOK)).isEqualTo(originalPlanCash);
        assertThat(count("subscription_billing_records","subscription_id=?",created.subscriptionId())).isEqualTo(1);
        assertThat(count("subscription_events","subscription_id=? and event_type='CHANGE_APPLIED'",created.subscriptionId())).isZero();
    }

    @Then("the exact Stripe schedule is projected without changing paid terms")
    public void scheduledDowngrade() {
        reconcilePlan(planSchedule(true));
        assertOriginalPlanSettlement();
        var pending=subscription().pendingChange();
        assertThat(pending.state()).isEqualTo(Subscription.ChangeState.SCHEDULED);
        assertThat(pending.effectiveAt()).isEqualTo(end);
        assertThat(pending.operationId()).isEqualTo(scheduledChange.operationId());
        assertThat(pending.target().priceId()).isEqualTo(downgradePrice);
        assertThat(operation(scheduledChange.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(count("subscription_events","subscription_id=? and event_type='CHANGE_SCHEDULED'",created.subscriptionId())).isEqualTo(1);
        assertPlanTransitionCallback(SubscriptionEvent.Type.CHANGE_SCHEDULED);
    }

    @When("the customer clears the original Stripe schedule before the paid boundary")
    public void clearScheduledDowngrade() {
        readHead();
        var schedulePath="/v1/subscription_schedules/"+PLAN_SCHEDULE;
        respond("GET",schedulePath,planSchedule(true));
        http.enqueue("POST",schedulePath+"/release",200,call -> {
            releasedPlanSchedule=json("""
                {"id":"%s","object":"subscription_schedule","livemode":false,"customer":"cus_billing",
                 "status":"released","released_at":%d,"subscription":null,"released_subscription":"sub_billing"}
                """.formatted(PLAN_SCHEDULE,dispatchSecond(call)));
            remote.remove("schedule");
            return releasedPlanSchedule.toString();
        });
        clearedChange=bean(ClearSubscriptionChangeUseCase.class).clear(
            new ClearSubscriptionChangeUseCase.Command(context(key()),created.subscriptionId()));
        assertThat(clearedChange.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        assertThat(subscription().pendingChange().operationId()).isEqualTo(scheduledChange.operationId());
        assertThat(releasedPlanSchedule.get("released_at").getAsLong()).isLessThan(end.getEpochSecond());
    }

    @Then("the release preserves the original Stripe cash and paid terms")
    public void clearedDowngrade() {
        reconcilePlan(releasedPlanSchedule);
        assertOriginalPlanSettlement();
        assertThat(subscription().pendingChange()).isNull();
        var change=operation(scheduledChange.operationId());
        var clear=operation(clearedChange.operationId());
        assertThat(clear.status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(count("subscription_events","subscription_id=? and event_type='CHANGE_CLEARED'",created.subscriptionId())).isEqualTo(1);
        assertPlanTransitionCallback(SubscriptionEvent.Type.CHANGE_CLEARED);

        var proof=bean(SubscriptionPlanProofStore.class).schedule(account.id(),change.id()).orElseThrow();
        assertThat(proof.reference()).isEqualTo(PLAN_SCHEDULE);
        assertThat(proof.subscription()).isEqualTo(created.subscriptionId());
        var nativeQuote=bean(SubscriptionPlanProofStore.class).quote(account.id(),proof.quote()).orElseThrow();
        assertThat(nativeQuote.reference()).isEqualTo(downgradeQuote.providerQuoteReference());
        assertThat(proof.clearOperation()).isEqualTo(clear.id());
        assertPlanStep(proof.creationStep(),change,ProviderOperation.Kind.SCHEDULE_CREATE);
        assertPlanStep(proof.updateStep(),change,ProviderOperation.Kind.SCHEDULE_UPDATE);
        assertPlanStep(proof.releaseStep(),clear,ProviderOperation.Kind.SCHEDULE_RELEASE);

        var nativePaths="/v1/subscription_schedules";
        var writes=http.calls().subList(beforePlan,http.calls().size()).stream().filter(call->call.method().equals("POST")).toList();
        assertThat(writes).extracting(StripeHttpFixture.Call::path).containsExactly(nativePaths,nativePaths+"/"+PLAN_SCHEDULE,nativePaths+"/"+PLAN_SCHEDULE+"/release");
        assertThat(writes).extracting(call->call.header("Idempotency-Key")).containsExactly(
            change.providerOperationKey()+":SCHEDULE_CREATE",change.providerOperationKey()+":SCHEDULE_UPDATE",clear.providerOperationKey()+":SCHEDULE_RELEASE");
        assertThat(writes.getFirst().form()).containsExactlyEntriesOf(Map.of("from_subscription","sub_billing"));
        assertThat(writes.get(1).form()).containsEntry("end_behavior","release").containsEntry("proration_behavior","none")
            .containsEntry("phases[0][items][0][price]","price_billing").containsEntry("phases[0][end_date]",Long.toString(end.getEpochSecond()))
            .containsEntry("phases[1][items][0][price]","price_downgrade").containsEntry("phases[1][start_date]",Long.toString(end.getEpochSecond()))
            .containsEntry("phases[1][proration_behavior]","none");
        assertThat(writes.getLast().form()).isEmpty();

        var afterClear=financialEvidence();
        reconcile();
        assertThat(financialEvidence()).isEqualTo(afterClear);
    }

    private void assertPlanTransitionCallback(SubscriptionEvent.Type type) {
        var matches=jdbc.queryForObject("""
            select count(*) from subscription_events e
            join outbox_events o on o.id=e.id and o.subscription_event_id=e.id
              and o.site_id=e.site_id and o.subscription_id=e.subscription_id and o.payment_account_id=e.payment_account_id
            where e.site_id=? and e.subscription_id=? and e.payment_account_id=? and e.event_type=?
              and e.source='RECONCILIATION' and e.revision=?
              and o.source_kind='SUBSCRIPTION' and o.callback_type='subscription.updated'
              and o.payload->>'event_id'=e.id::text and o.payload->>'event_type'=o.callback_type
              and o.payload->>'revision'=e.revision::text and o.payload->>'site_id'=e.site_id::text
              and o.payload->>'subscription_id'=e.subscription_id::text and o.payload->>'customer_id'=?
              and o.payload->>'state'='ACTIVE' and o.payload->>'access_allowed'='true'
              and (o.payload->>'access_valid_until')::timestamptz=least(e.occurred_at+interval '5 minutes',?::timestamptz)
            """,Long.class,site.value(),created.subscriptionId(),account.id().value(),type.name(),subscription().revision(),
            customer.id().value().toString(),Timestamp.from(end));
        assertThat(matches).as(type+" exact callback").isOne();
    }

    private void assertPlanStep(UUID id,SubscriptionOperation owner,ProviderOperation.Kind kind) {
        var step=bean(ProviderOperationStore.class).findById(id).orElseThrow();
        assertThat(step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(step.kind()).isEqualTo(kind);
        assertThat(step.resourceId()).isEqualTo(owner.id());
        assertThat(step.operationKey()).isEqualTo(owner.providerOperationKey()+":"+kind);
        assertThat(step.providerReference()).isEqualTo(PLAN_SCHEDULE);
    }

    @When("the customer opens the allowed narrow Stripe Billing Portal") public void portal() throws NoSuchAlgorithmException {
        var policies=bean(SubscriptionPolicyPort.class);var old=policies.load(site);portalReturn=ORIGIN+"/account";
        var policy=policies.save(new SubscriptionPolicy(site,old.version(),true,3,false,true,false,Set.of(portalReturn)),old.version());
        var original=subscription();
        var paid=publishedCash("in_initial","pi_initial",BillingRecord.Kind.INITIAL,SubscriptionEvent.Source.PROVIDER_WEBHOOK);
        var portalKey=key();
        var keyDigest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(portalKey.getBytes(StandardCharsets.UTF_8)));
        evidence=financialEvidence();portalNavigation="https://billing.stripe.com/p/session?secret=test_"+UUID.randomUUID().toString().replace("-","");
        identity();respond("GET","/v1/customers/cus_billing",remoteCustomer);respondPage("/v1/subscriptions",List.of(remote));respondPage("/v1/invoices",List.of(invoices.getFirst().invoice()));
        var configuration=json("""
            {"id":"bpc_billing","object":"billing_portal.configuration","active":true,"livemode":false,"default_return_url":"%s",
             "login_page":{"enabled":false,"url":null},"features":{"invoice_history":{"enabled":true},"customer_update":{"enabled":false,"allowed_updates":[]},"payment_method_update":{"enabled":false},"subscription_update":{"enabled":false,"default_allowed_updates":[],"products":[]},"subscription_cancel":{"enabled":true,"mode":"at_period_end","proration_behavior":"none","cancellation_reason":{"enabled":false,"options":[]}}}}
            """.formatted(portalReturn));
        http.enqueue("POST","/v1/billing_portal/configurations",200,call->{long at=dispatchSecond(call);configuration.addProperty("created",at);configuration.addProperty("updated",at);return configuration.toString();});
        http.enqueue("GET","/v1/billing_portal/configurations/bpc_billing",200,ignored->configuration.toString());
        http.enqueue("POST","/v1/billing_portal/sessions",200,call->{
            long dispatched=dispatchSecond(call);
            // Keep the fixture's fixed application time after the real SDK dispatch and before publication.
            TestClock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
            return """
            {"id":"bps_billing","object":"billing_portal.session","livemode":false,"created":%d,"customer":"cus_billing","configuration":"bpc_billing","url":"%s","return_url":"%s","flow":{"type":"subscription_cancel","subscription_cancel":{"subscription":"sub_billing"},"after_completion":{"type":"redirect","redirect":{"return_url":"%s"}}}}
            """.formatted(dispatched,portalNavigation,portalReturn,portalReturn);
        });
        var navigation=bean(CreateSubscriptionPortalUseCase.class).create(new CreateSubscriptionPortalUseCase.Command(context(portalKey),created.subscriptionId(),portalReturn));
        assertThat(navigation.simulated()).isFalse();assertThat(navigation.navigationUrl().equals(portalNavigation)).as("exact ephemeral owned navigation; value withheld").isTrue();
        portalInputs=new PortalInputs(paid,policy.version(),keyDigest,navigation.expiresAt(),original.revision(),original.currentPeriod());
        portalOperation=jdbc.queryForObject("select id from subscription_operations where subscription_id=? and kind='PORTAL'",UUID.class,created.subscriptionId());
    }
    @Then("its Portal configuration and session have original persisted provider proofs") public void portalProofs() throws IOException {
        var sql=Files.readString(Path.of("../infrastructure/stripe-billing-portal-proof.sql")).replaceAll("\\$[0-9]+","?");
        assertThat(queryPortalProof(sql,portalInputs.expiresAt())).containsExactly(true);
        assertThat(queryPortalProof(sql,portalInputs.expiresAt().plusNanos(1000))).isEmpty();
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
    private List<Boolean> queryPortalProof(String sql,Instant expiry) {
        var inputs=portalInputs;
        return jdbc.queryForList(sql,Boolean.class,account.id().value(),site.value(),customer.id().value(),created.subscriptionId(),
            created.operationId(),inputs.cash().bill(),inputs.cash().payment(),price.value(),inputs.policyVersion(),inputs.keyDigest(),
            portalReturn,Timestamp.from(expiry),inputs.revision(),Timestamp.from(inputs.period().start()),Timestamp.from(inputs.period().end()));
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
        deliverCallbacks(Set.of(created.subscriptionId()));
    }
    private void deliverCallbacks(Set<UUID> subscriptions) {
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
                    var canonical=json(new String(body,StandardCharsets.UTF_8));assertThat(headers.get("Payment-Event-Id")).isEqualTo(canonical.get("event_id").getAsString());assertThat(canonical.get("site_id").getAsString()).isEqualTo(site.value().toString());assertThat(subscriptions).contains(UUID.fromString(canonical.get("subscription_id").getAsString()));assertThat(delivered.add(claim.eventId())).isTrue();return CallbackHttpPort.Result.success(204);
                },deliveryClock).deliver(claim);
            } finally { Arrays.fill(secret,(byte)0); }
        }
        assertThat(count("outbox_events","site_id=? and status<>'DELIVERED'",site.value())).isZero();
        assertThat(count("outbox_deliveries","outbox_event_id in (select id from outbox_events where site_id=?)",site.value())).isEqualTo(claims.size());
        assertThat(outbox.claimAutomatic("stripe-billing-callback-replay",20,now(),Duration.ofSeconds(30))).isEmpty();
    }
    private record BillingRefs(String subscription,String item,String price,String product) {}
    private record InvoiceGraph(String suffix,String settlement,JsonObject invoice,JsonObject line,JsonObject allocation,JsonObject intent,JsonObject charge) {}
}
