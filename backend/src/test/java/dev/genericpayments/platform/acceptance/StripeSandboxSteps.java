package dev.genericpayments.platform.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.in.*;
import dev.genericpayments.platform.payments.application.service.ProviderReferenceInboxWorker;
import dev.genericpayments.platform.customers.application.service.CustomerMailDeliveryService;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.*;
import io.cucumber.java.After;
import io.cucumber.java.en.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP/application/PostgreSQL and official SDK transport. Only Stripe's network is a fixture. */
public class StripeSandboxSteps {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGIN = "http://localhost:3000";
    private final AcceptanceWorld world;
    private final JdbcTemplate jdbc;
    private final StripeAcceptanceConfiguration.FixtureFactory factory;
    private final ProviderReferenceInboxWorker worker;
    private final CreateDynamicPaymentUseCase dynamic;
    private final ExchangeHandoffUseCase exchange;
    private final CustomerMailDeliveryService mail;
    private StripeHttpFixture http;
    private String account, site, payment, checkout, token, source, customer, refund, refundRef;
    private final String capability = "pi_acceptance_secret_" + UUID.randomUUID();
    private long refundAmount, refundedBefore;
    private CreateDynamicPaymentCommand dynamicCommand;
    private boolean unknownPreparation;

    public StripeSandboxSteps(AcceptanceWorld world, JdbcTemplate jdbc,
            StripeAcceptanceConfiguration.FixtureFactory factory, ProviderReferenceInboxWorker worker,
            CreateDynamicPaymentUseCase dynamic, ExchangeHandoffUseCase exchange, CustomerMailDeliveryService mail) {
        this.world=world; this.jdbc=jdbc; this.factory=factory; this.worker=worker;
        this.dynamic=dynamic; this.exchange=exchange; this.mail=mail;
    }
    @After("@stripe-sandbox") public void close() {
        factory.detach(); if(http!=null)http.close(); token=null;
    }
    private JsonNode call(MockHttpServletRequestBuilder request,int expected) throws Exception {
        var response=world.api().mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as("Stripe acceptance HTTP status (payload withheld)").isEqualTo(expected);
        return response.getContentAsString().isBlank()?JSON.nullNode():JSON.readTree(response.getContentAsString());
    }
    private JsonNode admin(String method,String path,Object body,int expected) throws Exception {
        return call((method.equals("PUT")?put(path):post(path)).cookie(world.api().session,world.api().xsrf)
            .header("X-XSRF-TOKEN",world.api().xsrf.getValue()).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType("application/json").content(JSON.writeValueAsString(body)),expected);
    }
    private MockHttpServletRequestBuilder publicPost(String path,Object body) {
        return post(path).header("Origin",ORIGIN).header("Idempotency-Key",UUID.randomUUID().toString())
            .contentType("application/json").content(JSON.writeValueAsString(body));
    }
    private void identity() { http.enqueue("GET","/v1/account",200,"{\"id\":\"acct_acceptance\",\"object\":\"account\"}"); }
    private void setup(String kind) throws Exception {
        source=kind.contains("DONATION")?"DONATION":kind;
        http=new StripeHttpFixture(); factory.attach(http); world.api().login(); identity();
        account=admin("POST","/api/admin/v1/payment-accounts",Map.of("key","stripe-acceptance","provider","STRIPE","mode","SANDBOX",
            "external_account_id","acct_acceptance","credentials_ref","stripe-sandbox-default"),201).get("id").asString();
        site=admin("POST","/api/admin/v1/sites",Map.of("key","acceptance","display_name","Stripe acceptance","payment_account_id",account,
            "allowed_origins",List.of(ORIGIN),"authentication_modes",List.of("EMAIL_OTP"),
            "payment_method_policy",Map.of("mode","AUTOMATIC","methods",List.of())),201).get("id").asString();
        admin("PUT","/api/v1/admin/sites/"+site+"/checkout-return-urls",Map.of("origin",ORIGIN,"return_url",ORIGIN+"/payment-return","expected_version",-1),204);
        admin("PUT","/api/v1/admin/sites/"+site+"/callback",Map.of("endpoint_url","http://127.0.0.1:8081/callback","enabled",true),200);
        if(kind.equals("CATALOG")) {
            String product=world.api().product(site,"stripe-catalog");
            String price=admin("POST","/api/admin/v1/products/"+product+"/prices",Map.of("price_key","stripe-once","type","ONE_TIME",
                "amount_minor",2000,"currency","EUR","trial_days",0,"tax_mode","INCLUSIVE"),201).get("id").asString();
            identity();http.enqueue("POST","/v1/products",200,"{\"id\":\"prod_acceptance\",\"object\":\"product\",\"livemode\":false}");
            identity();http.enqueue("POST","/v1/prices",200,"{\"id\":\"price_acceptance\",\"object\":\"price\",\"livemode\":false}");
            admin("POST","/api/admin/v1/prices/"+price+"/publish",Map.of(),200);
            prepareResponse(false);
            remember(call(publicPost("/api/v1/public/sites/acceptance/checkouts",Map.of("price_key","stripe-once")),201));
        } else if(kind.equals("DYNAMIC")) {
            prepareResponse(unknownPreparation);
            dynamicCommand=new CreateDynamicPaymentCommand(new SiteId(UUID.fromString(site)),"acceptance-order",2000,"EUR",
                URI.create(ORIGIN),null,Map.of(),UUID.randomUUID().toString(),"acceptance");
            if(unknownPreparation) {
                var failure=org.assertj.core.api.Assertions.catchThrowable(() -> dynamic.create(dynamicCommand));
                assertThat(failure instanceof dev.genericpayments.platform.shared.application.PaymentApplicationException).isTrue();
                payment=jdbc.queryForObject("select id::text from payments where site_id=?",String.class,UUID.fromString(site));return;
            }
            var created=dynamic.create(dynamicCommand);
            var c=exchange.exchange(created.handoffToken(),URI.create(ORIGIN),UUID.randomUUID().toString());
            payment=c.view().paymentId().toString(); checkout=c.view().checkoutId().toString();token=c.checkoutToken();
        } else {
            String bearer=null;
            if(kind.equals("IDENTIFIED_DONATION"))bearer=customer();
            String campaign=admin("POST","/api/v1/admin/sites/"+site+"/donation-campaigns",Map.of("key","stripe-help","display_name","Help",
                "currency","EUR","suggested_amounts",List.of(2000),"minimum_amount",100,"maximum_amount",2000,
                "custom_amount_allowed",true,"anonymous_allowed",true),201).get("id").asString();
            admin("POST","/api/v1/admin/sites/"+site+"/donation-campaigns/"+campaign+"/status",Map.of("status","ACTIVE","expected_version",0),200);
            var request=publicPost("/api/v1/public/sites/acceptance/donation-campaigns/stripe-help/checkouts",Map.of("amount_minor",2000));
            if(bearer!=null)request.header("Authorization","Bearer "+bearer);
            remember(call(request,201));
        }
    }
    private String customer() throws Exception {
        CustomerMailpitIntegrationTest.clearMailpit();
        admin("POST","/api/v1/admin/sites/"+site+"/identity-providers",Map.of("provider_key","email","mode","EMAIL_OTP","sender_ref","local-test","template_key","otp-default"),201);
        String address="stripe-acceptance@example.test";
        var challenge=call(publicPost("/api/v1/public/customer-auth/email-otp/challenges",Map.of("site_key","acceptance","email",address,"intent","AUTHENTICATE")),202);
        assertThat(mail.deliverReady("stripe-acceptance")).isPositive();
        var auth=call(publicPost("/api/v1/public/customer-auth/email-otp/challenges/"+challenge.get("challenge_id").asString()+"/verify",
            Map.of("site_key","acceptance","code",CustomerMailpitIntegrationTest.mailpitCode(address))),200);
        customer=auth.get("customer").get("id").asString();return auth.get("session_token").asString();
    }
    private void remember(JsonNode response) {
        payment=response.get("payment_id").asString();checkout=response.get("checkout_id").asString();token=response.get("checkout_token").asString();
    }
    private String intent(String state,String charge) {
        return JSON.writeValueAsString(new LinkedHashMap<>(Map.ofEntries(Map.entry("id","pi_acceptance"),Map.entry("object","payment_intent"),
            Map.entry("livemode",false),Map.entry("status",state),Map.entry("amount",2000),Map.entry("amount_received",state.equals("succeeded")?2000:0),
            Map.entry("currency","eur"),Map.entry("capture_method","automatic"),Map.entry("payment_method_types",List.of("card")),
            Map.entry("client_secret",capability),Map.entry("metadata",Map.of("payment_id",payment==null?"":payment,"site_id",site,"source",source)))))
            .replace("\"metadata\":",(charge==null?"":"\"latest_charge\":\""+charge+"\",")+"\"metadata\":");
    }
    private void prepareResponse(boolean unknown) {
        identity();
        if(unknown)http.disconnectNext("POST","/v1/payment_intents");
        else http.enqueue("POST","/v1/payment_intents",200,call -> {
            String id=call.form().get("metadata[payment_id]");
            return intent("requires_payment_method",null).replace("\"payment_id\":\"\"","\"payment_id\":\""+id+"\"");
        });
    }
    private void charge(String reference,boolean paid) {
        http.enqueue("GET","/v1/charges/"+reference,200,JSON.writeValueAsString(Map.ofEntries(Map.entry("id",reference),Map.entry("object","charge"),
            Map.entry("payment_intent","pi_acceptance"),Map.entry("livemode",false),Map.entry("amount",2000),Map.entry("currency","eur"),
            Map.entry("amount_captured",paid?2000:0),Map.entry("paid",paid),Map.entry("captured",paid),Map.entry("status",paid?"succeeded":"failed"),
            Map.entry("payment_method_details",Map.of("type","card")))));
    }
    private void snapshot(String status,String charge) {
        identity();http.enqueue("GET","/v1/payment_intents/pi_acceptance",200,intent(status,charge));
        if(charge!=null)charge(charge,status.equals("succeeded"));
    }
    private void client(int expected) throws Exception {
        call(post("/api/v1/public/checkouts/"+checkout+"/client-session").header("Origin",ORIGIN).header("Authorization","Bearer "+token)
            .header("Idempotency-Key",UUID.randomUUID().toString()),expected);
    }
    @Given("an authorized Stripe {string} checkout for {long} EUR minor units")
    public void authorized(String kind,long amount) throws Exception {
        assertThat(amount).isEqualTo(2000);setup(kind);
        if(kind.contains("DONATION"))prepareResponse(false);
        snapshot("requires_payment_method",null);snapshot("requires_payment_method",null);client(200);
    }
    @When("its browser confirmation reports success") public void browserSuccess() throws Exception {
        // A browser has no financial settlement endpoint. Even the existing server confirm rejects Stripe.
        call(publicPost("/api/v1/public/checkouts/"+checkout+"/confirm",Map.of("payment_method_family","CARD"))
            .header("Authorization","Bearer "+token),422);
    }
    @When("Stripe has not yet confirmed the intent on the server") public void unconfirmed() throws Exception {
        var view=call(get("/api/v1/public/checkouts/"+checkout).header("Origin",ORIGIN).header("Authorization","Bearer "+token),200);
        assertThat(view.get("status").asString().equals("SUCCEEDED")).isFalse();assertThat(facts("PAYMENT_SUCCEEDED")).isZero();
    }
    @Then("the platform payment is not succeeded") public void unpaid() {
        assertThat(jdbc.queryForObject("select status='SUCCEEDED' from payments where id=?",Boolean.class,UUID.fromString(payment))).isFalse();
        assertThat(facts("PAYMENT_SUCCEEDED")).isZero();
    }
    private void signed(String event,String type,String object,String objectType) throws Exception {
        String body=JSON.writeValueAsString(Map.of("id",event,"object","event","api_version","2026-09-30.endive","livemode",false,
            "created",Instant.now().getEpochSecond(),"type",type,"data",Map.of("object",Map.of("id",object,"object",objectType,"livemode",false))));
        String timestamp=Long.toString(Instant.now().getEpochSecond());var mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(StripeAcceptanceConfiguration.SIGNING.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature=HexFormat.of().formatHex(mac.doFinal((timestamp+"."+body).getBytes(StandardCharsets.UTF_8)));
        call(post("/api/v1/provider-webhooks/stripe/stripe-acceptance").header("Stripe-Signature","t="+timestamp+",v1="+signature)
            .contentType("application/json").content(body),204);
    }
    private void receipt(String event,String eventType,String status,String charge) throws Exception {
        signed(event,eventType,"pi_acceptance","payment_intent");snapshot(status,charge);worker.runOnce();
    }
    @When("a signed Stripe success receipt is reconciled with the matching captured charge") public void success() throws Exception {
        receipt("evt_success","payment_intent.succeeded","succeeded","ch_paid");
    }
    @Then("exactly one payment succeeded fact and one Site callback exist") public void settled() {
        assertThat(facts("PAYMENT_SUCCEEDED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where payment_id=? and site_id=? and callback_type='payment.succeeded'",Integer.class,UUID.fromString(payment),UUID.fromString(site))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from provider_charge_receipts where payment_id=? and charge_reference='ch_paid'",Integer.class,UUID.fromString(payment))).isEqualTo(1);
    }
    private int facts(String type) { return jdbc.queryForObject("select count(*) from payment_events where payment_id=? and event_type=?",Integer.class,UUID.fromString(payment),type); }
    @Then("the Stripe request and persisted payment retain {string} ownership") public void ownership(String kind) {
        assertThat(jdbc.queryForObject("select count(*) from payments where id=? and site_id=? and payment_account_id=? and source=? and amount_minor=2000 and currency='EUR'",Integer.class,
            UUID.fromString(payment),UUID.fromString(site),UUID.fromString(account),source)).isEqualTo(1);
        assertThat(Objects.equals(jdbc.queryForObject("select customer_id::text from payments where id=?",String.class,UUID.fromString(payment)),customer)).isTrue();
        var writes=http.calls().stream().filter(c -> c.path().equals("/v1/payment_intents")&&c.method().equals("POST")).toList();
        assertThat(writes.size()).isEqualTo(1);var call=writes.getFirst();
        assertThat(call.form().get("allowed_payment_method_types[0]").equals("card")&&call.form().get("amount").equals("2000")
            &&call.form().get("currency").equals("eur")&&call.form().get("metadata[site_id]").equals(site)
            &&call.form().get("metadata[source]").equals(source)&&call.header("Idempotency-Key").equals("prepare_"+payment)).isTrue();
        assertThat(http.calls().stream().allMatch(c -> "2026-09-30.endive".equals(c.header("Stripe-Version"))&&c.header("Stripe-Account")==null)).isTrue();
        for(String table:List.of("provider_operations","payment_events","outbox_events","payment_attempts","idempotency_records"))
            assertThat(jdbc.queryForList("select row_to_json(t)::text from "+table+" t").toString().contains(capability)).as("finite capability persistence scan").isFalse();
    }
    @When("a verified decline is followed by another client attempt") public void decline() throws Exception {
        receipt("evt_decline","payment_intent.payment_failed","requires_payment_method","ch_declined");
        snapshot("requires_payment_method",null);snapshot("requires_payment_method",null);client(200);
    }
    @Then("two client attempts share the original Stripe intent") public void attempts() {
        assertThat(jdbc.queryForObject("select count(*) from payment_client_attempts where payment_id=?",Integer.class,UUID.fromString(payment))).isEqualTo(2);
        assertThat(http.calls().stream().filter(c -> c.path().equals("/v1/payment_intents")&&c.method().equals("POST")).count()).isEqualTo(1);
    }
    @When("Stripe requires action and then reports processing") public void sca() throws Exception {
        receipt("evt_sca","payment_intent.requires_action","requires_action",null);unpaid();
        receipt("evt_processing","payment_intent.processing","processing",null);
    }
    @When("Stripe repeats success and sends an old decline") public void repeats() throws Exception {
        int before=http.calls().size();signed("evt_success","payment_intent.succeeded","pi_acceptance","payment_intent");worker.runOnce();
        assertThat(http.calls().size()).isEqualTo(before);
        receipt("evt_successagain","payment_intent.succeeded","succeeded","ch_paid");
        receipt("evt_olddecline","payment_intent.payment_failed","requires_payment_method","ch_old");
    }
    @When("a missed Stripe success is retrieved by the administrator") public void reconcile() throws Exception {
        snapshot("succeeded","ch_paid");admin("POST","/api/v1/admin/payments/"+payment+"/reconcile",Map.of(),200);
    }
    private String refundJson(String id,String reference,long amount) {
        return JSON.writeValueAsString(Map.of("id",reference,"object","refund","amount",amount,"currency","eur","payment_intent","pi_acceptance",
            "charge","ch_paid","status","succeeded","metadata",Map.of("refund_id",id,"payment_id",payment)));
    }
    @When("the administrator requests a Stripe refund of {long} minor units") public void refund(long amount) throws Exception {
        refundedBefore=jdbc.queryForObject("select refunded_amount_minor from payments where id=?",Long.class,UUID.fromString(payment));
        refundAmount=amount;refundRef="re_"+amount;snapshot("succeeded","ch_paid");
        http.enqueue("POST","/v1/refunds",200,c -> refundJson(c.form().get("metadata[refund_id]"),refundRef,amount));
        refund=admin("POST","/api/v1/admin/payments/"+payment+"/refunds",Map.of("amount_minor",amount,"currency","EUR","reason_code","REQUESTED_BY_CUSTOMER"),202).get("refund_id").asString();
    }
    @Then("the synchronous Stripe refund acknowledgement adds no refunded money") public void noRefund() {
        assertThat(jdbc.queryForObject("select refunded_amount_minor from payments where id=?",Long.class,UUID.fromString(payment))).isEqualTo(refundedBefore);
    }
    @When("the individual Stripe refund is verified") public void verifyRefund() throws Exception {
        signed("evt_refund"+refundAmount,"refund.updated",refundRef,"refund");identity();
        http.enqueue("GET","/v1/refunds/"+refundRef,200,refundJson(refund,refundRef,refundAmount));
        http.enqueue("GET","/v1/payment_intents/pi_acceptance",200,intent("succeeded","ch_paid"));charge("ch_paid",true);worker.runOnce();
    }
    @Then("exactly {long} minor units and two refund facts are confirmed") public void refunded(long amount) {
        assertThat(jdbc.queryForObject("select refunded_amount_minor from payments where id=?",Long.class,UUID.fromString(payment))).isEqualTo(amount);
        assertThat(facts("REFUND_SUCCEEDED")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where payment_id=? and callback_type='refund.succeeded'",Integer.class,UUID.fromString(payment))).isEqualTo(2);
    }
    @Given("a Stripe dynamic payment whose preparation response is unknown") public void unknown() throws Exception {
        unknownPreparation=true;setup("DYNAMIC");
        assertThat(jdbc.queryForObject("select status from provider_operations where resource_id=?",String.class,UUID.fromString(payment))).isEqualTo("UNKNOWN");
    }
    @When("its original provider idempotency window expires") public void expires() {
        jdbc.update("update provider_operations set first_dispatched_at=current_timestamp-interval '25 hours',retry_before=current_timestamp-interval '2 hours' where resource_id=?",UUID.fromString(payment));
    }
    @Then("retrying preparation does not send another Stripe write") public void noNewWrite() throws Exception {
        int before=http.calls().size();identity();
        var failure=org.assertj.core.api.Assertions.catchThrowable(() -> dynamic.create(dynamicCommand));
        assertThat(failure instanceof dev.genericpayments.platform.shared.application.PaymentApplicationException).isTrue();
        assertThat(http.calls().subList(before,http.calls().size()).stream().noneMatch(c -> c.method().equals("POST"))).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from provider_operations where resource_id=?",Integer.class,UUID.fromString(payment))).isEqualTo(1);unpaid();
    }
    @When("an independent Fake checkout is confirmed in the same application") public void fake() throws Exception {
        String fakeAccount=world.api().account("fake-coexists"),fakeSite=world.api().site(fakeAccount,"customer-two"),product=world.api().product(fakeSite,"fake-product");
        String price=admin("POST","/api/admin/v1/products/"+product+"/prices",Map.of("price_key","fake-price","type","ONE_TIME","amount_minor",2000,
            "currency","EUR","trial_days",0,"tax_mode","INCLUSIVE"),201).get("id").asString();
        admin("POST","/api/admin/v1/prices/"+price+"/publish",Map.of(),200);
        int before=http.calls().size();var c=call(publicPost("/api/v1/public/sites/customer-two/checkouts",Map.of("price_key","fake-price")),201);
        assertThat(c.get("provider_type").asString()).isEqualTo("FAKE");assertThat(c.get("confirmation_mode").asString()).isEqualTo("SERVER");
        call(publicPost("/api/v1/public/checkouts/"+c.get("checkout_id").asString()+"/confirm",Map.of("payment_method_family","CARD"))
            .header("Authorization","Bearer "+c.get("checkout_token").asString()),200);
        assertThat(http.calls().size()).isEqualTo(before);
    }
    @Then("the Fake checkout uses server confirmation and Stripe remains unpaid") public void mixed() { unpaid(); }
}
