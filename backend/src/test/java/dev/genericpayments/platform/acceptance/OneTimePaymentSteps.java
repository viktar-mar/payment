package dev.genericpayments.platform.acceptance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.LoadPaymentPort;
import dev.genericpayments.platform.payments.application.service.ProviderInboxWorker;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.provider.adapter.out.fake.FakeProviderStatePersistenceAdapter;
import dev.genericpayments.platform.provider.adapter.out.fake.FakeProviderStatePersistenceAdapter.Scenario;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock;
import dev.genericpayments.platform.webhooks.application.service.OutboxWorker;
import io.cucumber.java.After;
import io.cucumber.java.en.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/** Business journeys use HTTP and the existing private fake-provider controls, never SQL setup. */
public class OneTimePaymentSteps {
    private final AcceptanceWorld world;
    private final JdbcTemplate jdbc;
    private final LoadPaymentPort payments;
    private final LoadPaymentAccountPort accounts;
    private final FakeProviderStatePersistenceAdapter fake;
    private final ProviderInboxWorker inbox;
    private final OutboxWorker outbox;
    private final java.time.Clock clock;
    private HttpServer receiver;
    private final Set<String> verified = Collections.synchronizedSet(new HashSet<>());
    private String callbackSecret;
    private int callbackVersion;
    public OneTimePaymentSteps(AcceptanceWorld world, JdbcTemplate jdbc, LoadPaymentPort payments,
        LoadPaymentAccountPort accounts, FakeProviderStatePersistenceAdapter fake, ProviderInboxWorker inbox, OutboxWorker outbox, java.time.Clock clock) {
        this.world=world; this.jdbc=jdbc; this.payments=payments; this.accounts=accounts; this.fake=fake; this.inbox=inbox; this.outbox=outbox; this.clock=clock;
    }
    private Map<String,String> flow(){return world.paymentFlow();}
    private String payment(){return flow().get("payment_id");}
    private String site(){return world.siteIds().get("acceptance");}
    private String path(){return "/api/v1/public/checkouts/"+flow().get("checkout_id");}
    private String adminPath(){return "/api/v1/admin/payments/"+payment();}
    private String text(ResultActions result,int expected) throws Exception {
        return result.andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    private String field(String json,String field){return JsonPath.read(json,"$."+field);}
    private ResultActions admin(String suffix,String body) throws Exception {
        return world.api().mvc.perform(post(adminPath()+suffix).cookie(world.api().session,world.api().xsrf)
            .header("X-XSRF-TOKEN",world.api().xsrf.getValue()).header("Idempotency-Key",UUID.randomUUID().toString())
            .header("X-Request-Id","acceptance").contentType("application/json").content(body));
    }
    private ResultActions create(String body) throws Exception {
        return world.api().mvc.perform(post("/api/v1/public/sites/acceptance/checkouts").header("Origin","http://localhost:3000")
            .header("Idempotency-Key",flow().get("create_key")).contentType("application/json").content(body));
    }
    private ResultActions confirm(String key) throws Exception {
        return world.api().mvc.perform(post(path()+"/confirm").header("Origin","http://localhost:3000")
            .header("Authorization","Bearer "+flow().get("checkout_token")).header("Idempotency-Key",key)
            .contentType("application/json").content("{\"payment_method_family\":\"CARD\"}"));
    }
    private ProviderPaymentRequest request(){
        var p=payments.findById(new SiteId(UUID.fromString(site())),new PaymentId(UUID.fromString(payment()))).orElseThrow();
        var a=accounts.findById(new PaymentAccountId(UUID.fromString(world.accountId()))).orElseThrow();
        return new ProviderPaymentRequest(a,p,ProviderOperationKeys.prepare(p.id()));
    }
    @Given("a published one-time price for {long} EUR cents")
    public void published(long amount) throws Exception {
        world.api().login();world.accountId(world.api().account("acceptance-account"));
        world.siteIds().put("acceptance",world.api().site(world.accountId(),"acceptance"));
        world.productIds().put("catalog",world.api().product(site(),"catalog"));publish("lifetime",amount);
        flow().put("create_key",UUID.randomUUID().toString());flow().put("confirm_key",UUID.randomUUID().toString());
    }
    private void publish(String key,long amount) throws Exception {
        String id=field(text(world.api().postJson("/api/admin/v1/products/"+world.productIds().get("catalog")+"/prices",
            "{\"price_key\":\""+key+"\",\"type\":\"ONE_TIME\",\"amount_minor\":"+amount+",\"currency\":\"EUR\",\"trial_days\":0,\"tax_mode\":\"INCLUSIVE\"}"),201),"id");
        text(world.api().postJson("/api/admin/v1/prices/"+id+"/publish",""),200);
    }
    @When("the guest creates a checkout") public void creates() throws Exception {
        var json=text(create("{\"price_key\":\"lifetime\"}"),201);
        for(String key:List.of("payment_id","checkout_id","checkout_token"))flow().put(key,field(json,key));
    }
    @When("the guest creates and confirms a checkout") public void createsAndConfirms() throws Exception {creates();text(confirm(flow().get("confirm_key")),200);}
    @When("the guest confirms the checkout again") public void confirmsAgain() throws Exception {
        fake.configure(request(),Scenario.PROCESSING);text(confirm(UUID.randomUUID().toString()),200);
    }
    @When("the provider declines the next confirmation recoverably") public void declines() throws Exception {
        fake.configure(request(),Scenario.RECOVERABLE_DECLINE);text(confirm(flow().get("confirm_key")),422);
        paymentState("REQUIRES_PAYMENT_METHOD",1);
    }
    @When("confirmation has an unknown provider result") public void unknown() throws Exception {
        fake.configure(request(),Scenario.UNKNOWN);text(confirm(flow().get("confirm_key")),503);unknownAttempt();
    }
    @Then("another confirmation cannot create a second attempt") public void blocksDuplicate() throws Exception {
        text(confirm(UUID.randomUUID().toString()),409);unknownAttempt();
    }
    private void unknownAttempt() throws Exception {
        unknownAttempt("REQUIRES_PAYMENT_METHOD");
    }
    @When("Donation confirmation has an unknown provider result") public void donationUnknown() throws Exception {
        fake.configure(request(),Scenario.UNKNOWN);text(confirm(flow().get("confirm_key")),503);unknownAttempt("CREATED");
    }
    @Then("another Donation confirmation cannot create a second attempt") public void donationBlocksDuplicate() throws Exception {
        text(confirm(UUID.randomUUID().toString()),409);unknownAttempt("CREATED");
    }
    private void unknownAttempt(String expectedState) throws Exception {
        paymentState(expectedState,1);
        world.api().getJson(adminPath()).andExpect(status().isOk()).andExpect(jsonPath("$.attempts[0].status").value("UNKNOWN"));
    }
    @When("the provider truth is reconciled as succeeded") public void reconciles() throws Exception {
        fake.setPaymentState(request(),ProviderState.Status.SUCCEEDED);text(admin("/reconcile","{}"),200);
    }
    private void event(String type,int sequence,String refund) throws Exception {
        text(admin("/fake-events","{\"target\":\""+(refund==null?"PAYMENT":"REFUND")+"\",\"event_type\":\""+type+"\",\"sequence\":"+sequence+
            (refund==null?"":",\"refund_id\":\""+refund+"\"")+"}"),202);inbox.runOnce();
    }
    @When("the provider reports {string}") public void reports(String type) throws Exception {event(type,3,null);flow().put("event_type",type);}
    @When("the provider repeats the same event") public void duplicates() throws Exception {
        event(flow().get("event_type"),3,null);
        assertThat(jdbc.queryForObject("select count(*) from provider_inbox where provider_event_id=?",Long.class,"fake_evt_"+payment()+"_3")).isEqualTo(1);
    }
    @When("the provider reports a delayed processing event") public void delayed() throws Exception {
        flow().put("journal_before",Long.toString(count("payment_events")));flow().put("outbox_before",Long.toString(count("outbox_events")));event("PAYMENT_PROCESSING",2,null);
    }
    @Then("no regressive journal or callback fact is added") public void noRegression(){
        assertThat(count("payment_events")).isEqualTo(Long.parseLong(flow().get("journal_before")));
        assertThat(count("outbox_events")).isEqualTo(Long.parseLong(flow().get("outbox_before")));
    }
    @Then("the payment is {string} with {int} attempt(s)") public void paymentState(String state,int attempts) throws Exception {
        world.api().getJson(adminPath()).andExpect(status().isOk()).andExpect(jsonPath("$.payment.payment_id").value(payment()))
            .andExpect(jsonPath("$.payment.status").value(state)).andExpect(jsonPath("$.attempts.length()").value(attempts));
    }
    @Then("exactly {int} {string} journal fact exists") public void journal(int n,String type){
        assertThat(jdbc.queryForObject("select count(*) from payment_events where payment_id=? and event_type=?",Long.class,UUID.fromString(payment()),type)).isEqualTo(n);
    }
    @Then("only one payment exists for the site") public void onePayment() throws Exception {
        world.api().getJson("/api/v1/admin/payments?site_id="+site()).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].payment_id").value(payment()));
    }
    @Then("the payment and provider operation use the configured account") public void routing() throws Exception {
        world.api().getJson(adminPath()).andExpect(status().isOk()).andExpect(jsonPath("$.payment.payment_account_id").value(world.accountId()));
        assertThat(jdbc.queryForList("select distinct payment_account_id from fake_provider_operations where internal_object_id=?",UUID.class,UUID.fromString(payment())))
            .containsExactly(UUID.fromString(world.accountId()));
    }
    @When("the guest replays both requests with their original keys") public void replay() throws Exception {
        create("{\"price_key\":\"lifetime\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.payment_id").value(payment()))
            .andExpect(jsonPath("$.checkout_id").value(flow().get("checkout_id"))).andExpect(jsonPath("$.checkout_token").value(flow().get("checkout_token")));
        text(confirm(flow().get("confirm_key")),200);
    }
    @When("the guest reuses the create key for another published price") public void conflicting() throws Exception {
        publish("different",100);world.lastResponse(create("{\"price_key\":\"different\"}").andReturn());
    }
    @Then("the response is an idempotency conflict") public void conflict() throws Exception {
        assertThat(world.lastResponse().getResponse().getStatus()).isEqualTo(409);
        assertThat(field(world.lastResponse().getResponse().getContentAsString(),"code")).isEqualTo("IDEMPOTENCY_CONFLICT");
    }
    @When("the trusted partner creates a dynamic payment for {long} EUR cents") public void partner(long amount) throws Exception {
        flow().put("partner_key",field(text(world.api().postJson("/api/v1/admin/sites/"+site()+"/api-keys","{\"scopes\":[\"payments:create\",\"payments:read\"]}"),201),"api_key"));
        var json=text(world.api().mvc.perform(post("/api/v1/partner/payments").header("Authorization","Bearer "+flow().get("partner_key"))
            .header("Idempotency-Key",flow().get("create_key")).contentType("application/json").content("{\"external_order_id\":\"acceptance-order\",\"amount_minor\":"+amount+
                ",\"currency\":\"EUR\",\"checkout_origin\":\"http://localhost:3000\",\"metadata\":{}}")),201);
        flow().put("payment_id",field(json,"payment_id"));
    }
    @Then("the partner reads the original order and {long} EUR cents") public void partnerReads(long amount) throws Exception {
        world.api().mvc.perform(get("/api/v1/partner/payments/"+payment()).header("Authorization","Bearer "+flow().get("partner_key")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.external_order_id").value("acceptance-order"))
            .andExpect(jsonPath("$.amount_minor").value(amount)).andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.handoff_token").doesNotExist());onePayment();
    }
    @Given("a succeeded catalog payment") public void succeeded() throws Exception {createsAndConfirms();reports("PAYMENT_SUCCEEDED");}
    @When("the administrator refunds {long} EUR cents") public void refund(long amount) throws Exception {
        flow().put("refund_id",field(text(admin("/refunds",refundBody(amount)),202),"refund_id"));
    }
    private String refundBody(long amount){return "{\"amount_minor\":"+amount+",\"currency\":\"EUR\",\"reason_code\":\"REQUESTED_BY_CUSTOMER\"}";}
    @When("the provider confirms the refund") public void refundSuccess() throws Exception {event("REFUND_SUCCEEDED",1,flow().get("refund_id"));}
    @When("the administrator attempts to refund {long} EUR cents above the remaining balance") public void excess(long amount) throws Exception {world.lastResponse(admin("/refunds",refundBody(amount)).andReturn());}
    @Then("the refund exceeds the available amount") public void excessRejected() throws Exception {
        assertThat(world.lastResponse().getResponse().getStatus()).isEqualTo(409);
        assertThat(field(world.lastResponse().getResponse().getContentAsString(),"code")).isEqualTo("REFUND_EXCEEDS_AVAILABLE");
        assertThat(count("refunds")).isEqualTo(1);
    }
    private void callback(boolean unavailable) throws Exception {
        receiver=HttpServer.create(new InetSocketAddress("127.0.0.1",8081),0);
        receiver.createContext("/callback",exchange->{
            int response=503;
            try {
                byte[] body=exchange.getRequestBody().readNBytes(16385);var h=exchange.getRequestHeaders();
                var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(Base64.getUrlDecoder().decode(callbackSecret),"HmacSHA256"));
                String timestamp=h.getFirst("Payment-Timestamp");mac.update((timestamp+".").getBytes(StandardCharsets.US_ASCII));
                byte[] expected=mac.doFinal(body);String signature=h.getFirst("Payment-Signature");
                boolean valid=body.length<=16384&&timestamp.equals(Long.toString(clock.instant().getEpochSecond()))
                    &&Integer.toString(callbackVersion).equals(h.getFirst("Payment-Secret-Version"))&&signature!=null&&signature.matches("v1=[0-9a-f]{64}")
                    &&MessageDigest.isEqual(expected,HexFormat.of().parseHex(signature.substring(3)));
                var parsed=JsonPath.parse(new String(body,StandardCharsets.UTF_8));
                if(valid&&!unavailable){if("payment.succeeded".equals(parsed.read("$.event_type",String.class)))verified.add(h.getFirst("Payment-Event-Id"));response=204;}
            } catch(Exception ignored){response=400;}
            exchange.sendResponseHeaders(response,-1);exchange.close();
        });receiver.start();
        var json=text(world.api().mvc.perform(put("/api/v1/admin/sites/"+site()+"/callback").cookie(world.api().session,world.api().xsrf)
            .header("X-XSRF-TOKEN",world.api().xsrf.getValue()).contentType("application/json")
            .content("{\"endpoint_url\":\"http://127.0.0.1:8081/callback\",\"enabled\":true}")),200);
        callbackSecret=field(json,"secret");callbackVersion=JsonPath.read(json,"$.secret_version");
    }
    @Given("a site callback that verifies signatures") public void signedCallback() throws Exception {callback(false);}
    @Given("a site callback that returns unavailable") public void unavailableCallback() throws Exception {callback(true);}
    @When("pending callbacks are delivered") public void deliver(){outbox.runOnce();}
    @Then("the succeeded callback is verified exactly once") public void verified(){
        assertThat(verified).hasSize(1);assertThat(jdbc.queryForObject("select count(*) from outbox_events where callback_type='payment.succeeded' and status='DELIVERED'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select attempt_count from outbox_events where callback_type='payment.succeeded'",Integer.class)).isEqualTo(1);
        outbox.runOnce();assertThat(verified).hasSize(1);
    }
    @When("callback retries exhaust the delivery schedule") public void exhaust(){
        Instant now=clock.instant();for(long delay:new long[]{60,300,1800,7200,43200,0}){outbox.runOnce();now=now.plusSeconds(delay);TestClock.set(now);}
    }
    @Then("the succeeded callback is dead after six attempts") public void dead() throws Exception {
        assertThat(jdbc.queryForObject("select status from outbox_events where callback_type='payment.succeeded'",String.class)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("select attempt_count from outbox_events where callback_type='payment.succeeded'",Integer.class)).isEqualTo(6);
        world.api().mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        // The real six-attempt schedule exceeds the eight-hour admin session lifetime.
        world.api().login();
    }
    private long count(String table){return jdbc.queryForObject("select count(*) from "+table,Long.class);}
    @After public void close(){if(receiver!=null)receiver.stop(0);callbackSecret=null;}
}
