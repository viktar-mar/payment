package dev.genericpayments.acceptance.runtime;

import com.google.gson.*;
import com.stripe.StripeClient;
import com.stripe.net.*;
import com.stripe.exception.*;
import dev.genericpayments.platform.PaymentPlatformApplication;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import dev.genericpayments.platform.webhooks.application.service.*;
import dev.genericpayments.platform.support.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import static org.assertj.core.api.Assertions.*;

/** Hosted only. The scenario under test creates all financial rows through real durable services. */
@SpringBootTest(classes=PaymentPlatformApplication.class)
class OwnedBillingFirstPaymentScenarioIntegrationTest extends StripeBillingClockConfirmationCompositionFixture {
    @Autowired ApplicationContext application;
    @Autowired EncryptedSecretPort encryption;
    @Autowired OutboxPort outbox;
    @Autowired CallbackPayloadPort payload;
    private OwnedBillingFirstPaymentScenario scenario;
    private JsonObject prepared,prepareRequest;
    private final List<StripeRequest> requests=new ArrayList<>();
    private String outcome="succeeded";
    private boolean driftAfterConfirm,staleDuringRead,consumeDuringRead;
    private UUID run,originalPrice;
    private StripeClientFactory inertClients;

    @BeforeEach void newScenario() {
        run=UUID.randomUUID();
        jdbc.update("update payment_accounts set account_key='stripe-sandbox-default',external_account_id='acct_1UPiMpELswAd8tpR' where id=?",billing.id().value());
        billing=accounts.findById(billing.id()).orElseThrow();
        originalPrice=createCommand.priceId().value();var firstPrice=UUID.randomUUID();
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) select ?,site_id,product_id,'first-monthly',1,'SUBSCRIPTION',2000,'EUR','MONTH',0,'OPTIONAL_DURING_TRIAL','INCLUSIVE','PUBLISHED',now(),now(),now() from prices where id=?",firstPrice,originalPrice);
        jdbc.update("insert into provider_price_mappings(payment_account_id,price_id,provider_price_id,created_at) values (?,?,'price_first',now())",billing.id().value(),firstPrice);
        createCommand=new dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionUseCase.Command(createCommand.context(),new dev.genericpayments.platform.catalog.domain.PriceId(firstPrice),true);
        callbacks.configure(site.value(),"https://example.com/callback",true);
        var session=sessions.issue(site,customer,"https://shop.test");
        var admission=new OwnedBillingFirstPaymentRuntime.Admission(run,"a".repeat(40),FROZEN,"q".repeat(43),8099,"127.0.0.1");
        inertClients=new StripeClientFactory(){@Override public StripeClient create(StripeCredentials ignored){return StripeClient.builder().setApiKey("sk_test_INERT").setApiBase("https://api.stripe.com").setMaxNetworkRetries(0).setHttpClient(new HttpClient(){@Override public StripeResponse request(StripeRequest request)throws StripeException{return respond(request);}}).build();}};
        scenario=new OwnedBillingFirstPaymentScenario(admission,this::runtimeBean,Clock.systemUTC(),Instant.now().plusSeconds(120));
        prepareRequest=new JsonObject();prepareRequest.addProperty("run_id",run.toString());prepareRequest.addProperty("source_sha","a".repeat(40));prepareRequest.addProperty("account_id",billing.id().value().toString());prepareRequest.addProperty("site_id",site.value().toString());prepareRequest.addProperty("customer_id",customer.value().toString());prepareRequest.addProperty("price_id",createCommand.priceId().value().toString());prepareRequest.addProperty("origin","https://shop.test");prepareRequest.addProperty("create_key","original-create");prepareRequest.addProperty("customer_token",session.rawToken());
    }
    private Object runtimeBean(Class<?> type) {
        if(type==StripeClientFactory.class)return inertClients;
        if(type==StripeBillingGrantTokens.class)return tokens;
        if(type==StripeBillingCustomerAdapter.class)return new StripeBillingCustomerAdapter(credentials,inertClients,operations,bindings,digest,verification,accounts,sites,application.getBean(CustomerStore.class),clocks);
        return application.getBean(type);
    }
    private StripeResponse respond(StripeRequest request)throws StripeException {
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        requests.add(request);String path=request.url().getPath();
        owner=clocks.owner(billing.id(),site,customer).orElseThrow();assertThat(owner.run()).isEqualTo(run);
        String body;
        if(path.startsWith("/v1/test_helpers/test_clocks"))body=clockJson(owner);
        else if(path.startsWith("/v1/customers"))body=customerJson("clock_owned");
        else if(path.equals("/v1/subscriptions")) {
            var ids=scenario.status();var sub=subscriptions.find(site,UUID.fromString(ids.get("subscription_id").getAsString())).orElseThrow();
            var op=lifecycleOperations.find(site,sub.id(),UUID.fromString(ids.get("operation_id").getAsString())).orElseThrow();
            graph=new StripeBillingOwnedSubscriptionFixture();var metadata=new JsonObject();StripeBillingOwnership.metadata(billing,sub,op).forEach(metadata::addProperty);graph.remote.add("metadata",metadata);
            graph.price().getAsJsonObject("metadata").addProperty("local_price_id",createCommand.priceId().value().toString());
            graph.price().addProperty("unit_amount",2000);graph.price().addProperty("id","price_first");graph.line.getAsJsonObject("pricing").getAsJsonObject("price_details").addProperty("price","price_first");
            for(var field:List.of("amount_due","amount_remaining","total","subtotal"))graph.invoice.addProperty(field,2000);
            graph.line.addProperty("amount",2000);graph.allocation.addProperty("amount_requested",2000);graph.allocation.addProperty("amount_paid",0);graph.allocation.addProperty("status","open");graph.allocation.addProperty("is_default",true);graph.allocation.remove("status_transitions");
            graph.pi.addProperty("amount",2000);graph.pi.addProperty("amount_received",0);graph.pi.addProperty("status","requires_payment_method");graph.pi.addProperty("confirmation_method","automatic");graph.pi.addProperty("client_secret","pi_owned_secret_INERT");graph.pi.remove("latest_charge");
            graph.charge.addProperty("amount",2000);graph.charge.addProperty("amount_captured",2000);body=graph.remote.toString();
        } else if(path.equals("/v1/subscriptions/sub_owned")) {
            if(staleDuringRead){jdbc.update("update subscriptions set revision=revision+1 where id=?",UUID.fromString(scenario.status().get("subscription_id").getAsString()));staleDuringRead=false;}
            if(consumeDuringRead){var sub=subscriptions.find(site,UUID.fromString(scenario.status().get("subscription_id").getAsString())).orElseThrow();transaction.required(()->{observations.consumeFence(sub,observations.captureFence(sub));return null;});consumeDuringRead=false;}

            body=graph.remote.toString();
        } else if(path.equals("/v1/invoices"))body=StripeBillingOwnedSubscriptionFixture.page(graph.invoice);
        else if(path.equals("/v1/invoices/in_owned/lines"))body=StripeBillingOwnedSubscriptionFixture.page(graph.line);
        else if(path.equals("/v1/invoice_payments"))body=StripeBillingOwnedSubscriptionFixture.page(graph.allocation);
        else if(path.equals("/v1/payment_intents/pi_owned/confirm")) {
            var row=jdbc.queryForMap("select state,confirm_key,provider_key from subscription_confirmation_attempts");
            assertThat(row.get("state")).isEqualTo("DISPATCHING");assertThat(row.get("confirm_key")).isEqualTo("42303eb48a49f8370192a907cf5faf7689075ae490a60635ca77e6dad403ea1c");
            assertThat(request.headers().map().get("Idempotency-Key")).containsExactly((String)row.get("provider_key"));assertThat(request.options().getMaxNetworkRetries()).isZero();
            assertThat(request.params()).containsEntry("confirmation_token","ctoken_INERT");
            if(outcome.equals("disconnect"))throw new ApiConnectionException("synthetic disconnect");
            var response=graph.pi.deepCopy();response.addProperty("status",outcome);response.addProperty("amount_received",outcome.equals("succeeded")?2000:0);
            response.add("next_action",outcome.equals("requires_action")?StripeBillingOwnedSubscriptionFixture.json("{\"type\":\"use_stripe_sdk\",\"use_stripe_sdk\":{\"opaque\":\"inert\"}}"):JsonNull.INSTANCE);
            if(driftAfterConfirm)jdbc.update("update subscriptions set revision=revision+1");body=response.toString();
        } else if(path.equals("/v1/payment_intents/pi_owned"))body=graph.pi.toString();
        else if(path.equals("/v1/charges/ch_owned"))body=graph.charge.toString();
        else throw new AssertionError("Unexpected inert SDK path: "+path);
        return new StripeResponse(200,HttpHeaders.of(Map.of()),body);
    }
    private JsonObject prepare(){prepared=scenario.prepare(prepareRequest);return prepared;}
    private JsonObject confirmRequest(){var result=new JsonObject();var session=prepared.getAsJsonObject("session");result.add("attempt_id",session.get("attempt_id"));result.add("capability",session.get("capability"));result.addProperty("confirmation_token","ctoken_INERT");result.addProperty("idempotency_key","sdk-generated-pay-key");return result;}
    private void paidGraph(){graph.remote.addProperty("status","active");graph.invoice.addProperty("status","paid");graph.invoice.addProperty("amount_paid",2000);graph.invoice.addProperty("amount_remaining",0);graph.allocation.addProperty("status","paid");graph.allocation.addProperty("amount_paid",2000);graph.allocation.add("status_transitions",StripeBillingOwnedSubscriptionFixture.json("{\"paid_at\":1700000011}"));graph.pi.addProperty("status","succeeded");graph.pi.addProperty("amount_received",2000);graph.pi.addProperty("latest_charge","ch_owned");}
    private void noMoney(){assertThat(count("payments")).isZero();assertThat(count("subscription_billing_records")).isZero();assertThat(count("payment_events")).isZero();assertThat(count("subscription_events")).isZero();assertThat(count("outbox_events")).isZero();}
    private long posts(){return requests.stream().filter(r->r.method().name().equals("POST")).count();}

    @Test void actualSdkKeySelectsThenConfirmsOnceAndFreshCashProducesSignedDeliveredCallbacks() throws Exception {
        callbacks.configure(site.value(),"http://callback-receiver:8080/callback",true);
        prepared=privateRequest("POST", "/initial-payment/prepare", prepareRequest);
        assertThat(prepared.getAsJsonObject("action").get("secret").getAsString()).startsWith("bga1_");
        noMoney();
        assertThat(privateRequest("POST", "/initial-payment/confirm", confirmRequest()).get("state").getAsString()).isEqualTo("OBSERVE");
        noMoney();
        paidGraph();
        var proof=privateRequest("POST", "/initial-payment/observe", new JsonObject());assertThat(proof.get("phase").getAsString()).isEqualTo("SETTLED_LOCAL");
        assertThat(proof.get("paid_through").getAsString()).isEqualTo("2023-12-15T22:13:20Z");assertThat(proof.get("access_valid_until").getAsString()).isEqualTo("2023-11-14T22:18:20Z");
        assertThat(count("payments")).isEqualTo(1);assertThat(count("payment_events")).isEqualTo(1);assertThat(count("subscription_billing_records")).isEqualTo(1);assertThat(count("subscription_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select settlement from subscription_billing_records",String.class)).isEqualTo("CONFIRMED_CASH");assertThat(jdbc.queryForObject("select provider_payment_reference from payments",String.class)).isEqualTo("pi_owned");assertThat(jdbc.queryForObject("select event_type from payment_events",String.class)).isEqualTo("PAYMENT_SUCCEEDED");assertThat(jdbc.queryForObject("select event_type from subscription_events",String.class)).isEqualTo("PERIOD_PAID");
        var snapshots=jdbc.queryForList("select id,payload::text from outbox_events order by id");assertThat(snapshots).hasSize(2);
        observe();assertThat(jdbc.queryForList("select id,payload::text from outbox_events order by id")).isEqualTo(snapshots);assertThat(posts()).isEqualTo(4);
        var claims=outbox.claimAutomatic("first-payment",10,OwnedBillingFirstPaymentRuntimeTest.callbackClaimUtc(Instant.now()),Duration.ofSeconds(30));assertThat(claims).hasSize(2);
        for(var claim:claims) {
            new CallbackDeliveryService(outbox,encryption,payload,(endpoint,headers,bytes)->{
                long timestamp=Long.parseLong(headers.get("Payment-Timestamp"));assertThat(Instant.ofEpochSecond(timestamp)).isBetween(began,Instant.now());
                assertThat(headers.get("Payment-Signature")).isEqualTo("v1="+new CallbackSigner().sign(Base64.getUrlDecoder().decode(encryption.decrypt(claim.secret())),timestamp,bytes));
                var json=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();assertThat(json.get("subscription_id").getAsString()).isEqualTo(prepared.get("subscription_id").getAsString());return CallbackHttpPort.Result.success(204);
            },Clock.systemUTC()).deliver(claim);
        }
        assertThat(jdbc.queryForList("select status from outbox_events",String.class)).containsOnly("DELIVERED");
        assertThat(scenario.status().toString()).doesNotContain("ctoken","bca2_","bga1_","secret","customer_token");
        verifyOwnedInitialSql();
    }

    private void refreshObservationClock() {
        // Match the existing composition fixture's apply boundary; only persisted billing F stays frozen.
        TestClock.set(OwnedBillingFirstPaymentRuntimeTest.observationUtc(Instant.now()));
    }
    private JsonObject observe() { refreshObservationClock();return scenario.observe(); }

    /** Actual private HTTP handler with the real scenario and stores; no listener/socket. */

    private JsonObject privateRequest(String method, String path, JsonObject body) throws Exception {
        if(path.equals("/initial-payment/observe"))refreshObservationClock();
        var exchange=new OwnedBillingFirstPaymentRuntimeTest.MemoryExchange(method,path,body.toString(),true);
        new OwnedBillingFirstPaymentRuntime.Handler(scenario,"q".repeat(43)).handle(exchange);
        assertThat(exchange.code).isEqualTo(200);
        assertThat(exchange.responses.getFirst("Cache-Control")).isEqualTo("no-store");
        return JsonParser.parseString(exchange.output.toString(java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** Task2 proves the shipped SQL against the same real service-created settled/delivered rows. */
    private void verifyOwnedInitialSql() throws Exception {
        var sql=java.nio.file.Files.readString(java.nio.file.Path.of("../infrastructure/stripe-billing-first-payment-proof.sql")).replaceAll("\\$[0-9]+","?");
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        var createHash=HexFormat.of().formatHex(digest.digest("original-create".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var confirmHash=HexFormat.of().formatHex(digest.digest("sdk-generated-pay-key".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Object[] args={billing.id().value(),site.value(),customer.value(),UUID.fromString(prepared.get("subscription_id").getAsString()),UUID.fromString(prepared.get("operation_id").getAsString()),UUID.fromString(prepared.getAsJsonObject("session").get("attempt_id").getAsString()),jdbc.queryForObject("select product_id from prices where id=?",UUID.class,createCommand.priceId().value()),createCommand.priceId().value(),createHash,confirmHash,"https://shop.test",run,FROZEN};
        var rows=jdbc.queryForList(sql,args);assertThat(rows).hasSize(1);
        var proof=JsonParser.parseString(rows.getFirst().get("proof").toString()).getAsJsonObject();
        for(var key:List.of("owned","cash","original","journal"))assertThat(proof.get(key).getAsBoolean()).as(key).isTrue();
        var delivered=JsonParser.parseString(rows.getFirst().get("callbacks").toString()).getAsJsonArray();assertThat(delivered.size()).isEqualTo(2);
        for(var callback:delivered)assertThat(callback.getAsJsonObject().get("delivered").getAsBoolean()).isTrue();
        assertThat(count("provider_inbox")).isZero();
        for(int index:new int[]{8,9,11,12}) {
            var bad=args.clone();bad[index]=index==11?UUID.randomUUID():index==12?FROZEN+1:"0".repeat(64);
            var rejected=jdbc.queryForList(sql,bad);assertThat(rejected).hasSize(1);
            var flags=JsonParser.parseString(rejected.getFirst().get("proof").toString()).getAsJsonObject();
            assertThat(flags.get(index>=11?"owned":"original").getAsBoolean()).isFalse();
        }
    }
    @ParameterizedTest @ValueSource(strings={"requires_action","requires_payment_method","processing","disconnect"})
    void anyNonObserveOrLostResponseIsTerminalWithoutMoneyOrRedispatch(String outcome) {
        this.outcome=outcome;prepare();assertThatThrownBy(()->scenario.confirm(confirmRequest())).hasMessage("INITIAL_PAYMENT_STOPPED");
        long posts=posts();assertThat(posts).isEqualTo(4);assertThatThrownBy(()->scenario.confirm(confirmRequest())).isInstanceOf(RuntimeException.class);assertThatThrownBy(this::observe).isInstanceOf(RuntimeException.class);assertThat(posts()).isEqualTo(posts);noMoney();
    }
    @Test void duplicatePrepareAndRestartCannotAdoptExistingOwner() {
        prepare();long posts=posts();assertThatThrownBy(()->scenario.prepare(prepareRequest)).isInstanceOf(RuntimeException.class);assertThat(posts()).isEqualTo(posts);
        var admission=new OwnedBillingFirstPaymentRuntime.Admission(run,"a".repeat(40),FROZEN,"q".repeat(43),8099,"127.0.0.1");
        var restarted=new OwnedBillingFirstPaymentScenario(admission,this::runtimeBean,Clock.systemUTC(),Instant.now().plusSeconds(60));assertThatThrownBy(()->restarted.prepare(prepareRequest)).isInstanceOf(RuntimeException.class);assertThat(posts()).isEqualTo(posts);noMoney();
    }
    @ParameterizedTest @ValueSource(strings={"price","account","session","source","callback","mapping"})
    void preflightRejectsBeforeAnyProviderMutation(String drift) {
        switch(drift){case "mapping"->jdbc.update("delete from provider_price_mappings where price_id=?",createCommand.priceId().value());case "price"->prepareRequest.addProperty("price_id",originalPrice.toString());case "account"->jdbc.update("update payment_accounts set external_account_id='acct_foreign'");case "session"->prepareRequest.addProperty("customer_token","bad");case "source"->prepareRequest.addProperty("source_sha","b".repeat(40));case "callback"->callbacks.configure(site.value(),"https://example.com/callback",false);}
        assertThatThrownBy(()->scenario.prepare(prepareRequest)).hasMessage("INITIAL_PAYMENT_STOPPED");assertThat(requests).isEmpty();assertThat(count("stripe_billing_clock_intents")).isZero();noMoney();
    }
    @Test void revokedSessionBeforeConfirmAndExplicitAbortNeverDispatch() {
        prepare();var context=sessions.resolveRequired(prepareRequest.get("customer_token").getAsString(),"https://shop.test");sessions.logout(context);
        assertThatThrownBy(()->scenario.confirm(confirmRequest())).isInstanceOf(RuntimeException.class);assertThat(posts()).isEqualTo(3);scenario.abort();assertThatThrownBy(this::observe).isInstanceOf(RuntimeException.class);noMoney();
    }
    @Test void driftAfterProviderPostRetainsConsumedAttemptWithoutAnotherPost() {
        prepare();driftAfterConfirm=true;assertThatThrownBy(()->scenario.confirm(confirmRequest())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select state from subscription_confirmation_attempts",String.class)).isEqualTo("DISPATCHING");assertThatThrownBy(()->scenario.confirm(confirmRequest())).isInstanceOf(RuntimeException.class);assertThat(posts()).isEqualTo(4);noMoney();
    }
    @ParameterizedTest @ValueSource(strings={"revision","fence"}) void staleObservationCannotBeRebasedOntoFreshFence(String drift) {
        prepare();scenario.confirm(confirmRequest());paidGraph();staleDuringRead=drift.equals("revision");consumeDuringRead=drift.equals("fence");
        assertThatThrownBy(this::observe).hasMessage("INITIAL_PAYMENT_STOPPED");noMoney();assertThatThrownBy(this::observe).isInstanceOf(RuntimeException.class);assertThat(posts()).isEqualTo(4);
    }
    @Test void lostLocalConfirmationAcknowledgementStaysDispatchingAndCannotRepeat() {
        prepare();jdbc.execute("create function first_payment_reject_ack() returns trigger language plpgsql as $$ begin if new.state='OBSERVE' then raise exception 'synthetic'; end if; return new; end; $$");jdbc.execute("create trigger first_payment_reject_ack before update on subscription_confirmation_attempts for each row execute function first_payment_reject_ack()");
        try{assertThatThrownBy(()->scenario.confirm(confirmRequest())).isInstanceOf(RuntimeException.class);}finally{jdbc.execute("drop trigger first_payment_reject_ack on subscription_confirmation_attempts");jdbc.execute("drop function first_payment_reject_ack()");}
        assertThat(jdbc.queryForObject("select state from subscription_confirmation_attempts",String.class)).isEqualTo("DISPATCHING");assertThatThrownBy(()->scenario.confirm(confirmRequest())).isInstanceOf(RuntimeException.class);assertThat(posts()).isEqualTo(4);noMoney();
    }
}
