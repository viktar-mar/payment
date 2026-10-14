package dev.genericpayments.platform.acceptance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import dev.genericpayments.platform.customers.application.service.CustomerMailDeliveryService;
import dev.genericpayments.platform.support.CustomerMailpitIntegrationTest;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import io.cucumber.java.en.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Authentication, catalog and Admin operations all cross the real Spring HTTP boundary. */
public class CustomerAccessSteps {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String ORIGIN="http://localhost:3000",ROOT="/api/v1/public/customer-auth/";
    private final AcceptanceWorld world;
    private final CustomerMailDeliveryService delivery;
    private final JdbcTemplate jdbc;
    private RSAKey key;
    public CustomerAccessSteps(AcceptanceWorld world,CustomerMailDeliveryService delivery,JdbcTemplate jdbc) {
        this.world=world;this.delivery=delivery;this.jdbc=jdbc;
    }
    private Map<String,String> flow(){return world.customerFlow();}
    private JsonNode http(MockHttpServletRequestBuilder request,int expected) throws Exception {
        var response=world.api().mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as("Customer journey HTTP status").isEqualTo(expected);
        return response.getContentAsString().isBlank()?JSON.nullNode():JSON.readTree(response.getContentAsString());
    }
    private MockHttpServletRequestBuilder publicPost(String path,Object body) {
        return post(path).header("Origin",ORIGIN).contentType("application/json").content(JSON.writeValueAsString(body));
    }
    private JsonNode adminPost(String path,Object body,int expected) throws Exception {
        return http(post(path).cookie(world.api().session,world.api().xsrf).header("X-XSRF-TOKEN",world.api().xsrf.getValue())
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(JSON.writeValueAsString(body)),expected);
    }
    private String site(String name){return world.siteIds().get(name);}
    @Given("two Customer-enabled Sites with published one-time prices")
    public void setup() throws Exception {
        CustomerMailpitIntegrationTest.clearMailpit();world.api().login();
        world.accountId(world.api().account("customer-account"));key=new RSAKeyGenerator(2048).keyID("acceptance-rs256").generate();
        for(String name:List.of("customer-one","customer-two")) {
            String id=adminPost("/api/admin/v1/sites",Map.of("key",name,"display_name",name,"payment_account_id",world.accountId(),
                "allowed_origins",List.of(ORIGIN),"authentication_modes",List.of("SITE_TOKEN","OIDC_TOKEN","EMAIL_OTP"),
                "payment_method_policy",Map.of("mode","AUTOMATIC","methods",List.of())),201).get("id").asString();
            world.siteIds().put(name,id);
            for(String mode:List.of("SITE_TOKEN","OIDC_TOKEN"))adminPost("/api/v1/admin/sites/"+id+"/identity-providers",Map.of(
                "provider_key",mode.equals("SITE_TOKEN")?"signed":"oidc","mode",mode,"issuer","https://acceptance-issuer.example.test",
                "audiences",List.of("web"),"allowed_algorithms",List.of("RS256"),"jwks_source_type","STATIC_PUBLIC",
                "public_jwk_set",JSON.readTree(new JWKSet(key.toPublicJWK()).toString())),201);
            adminPost("/api/v1/admin/sites/"+id+"/identity-providers",Map.of("provider_key","email","mode","EMAIL_OTP",
                "sender_ref","local-test","template_key","otp-default"),201);
            String product=world.api().product(id,"customer-product");
            String price=adminPost("/api/admin/v1/products/"+product+"/prices",Map.of("price_key","customer-price","type","ONE_TIME",
                "amount_minor",4999,"currency","EUR","trial_days",0,"tax_mode","INCLUSIVE"),201).get("id").asString();
            adminPost("/api/admin/v1/prices/"+price+"/publish",Map.of(),200);
        }
    }
    private String signed(String subject,String nonce) throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer("https://acceptance-issuer.example.test").audience("web").subject(subject)
            .issueTime(Date.from(PostgresIntegrationTest.INITIAL_TIME)).expirationTime(Date.from(PostgresIntegrationTest.INITIAL_TIME.plusSeconds(120)))
            .jwtID(UUID.randomUUID().toString());
        if(nonce!=null)claims.claim("nonce",nonce);
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims.build());
        jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
    private JsonNode exchange(String subject,String endpoint) throws Exception {
        return http(publicPost(ROOT+"site-token/"+endpoint,Map.of("site_key","customer-one","provider_key","signed","token",signed(subject,null))),200);
    }
    private void remember(JsonNode auth) {flow().put("session",auth.get("session_token").asString());flow().put("customer",auth.get("customer").get("id").asString());}
    @When("a signed Site token is exchanged") public void siteToken() throws Exception {remember(exchange("first-owner","exchanges"));}
    @When("an OIDC attempt and signed token are exchanged") public void oidc() throws Exception {
        var attempt=http(publicPost(ROOT+"oidc/attempts",Map.of("site_key","customer-one","provider_key","oidc","intent","AUTHENTICATE")),201);
        remember(http(publicPost(ROOT+"oidc/exchanges",Map.of("site_key","customer-one","provider_key","oidc","intent","AUTHENTICATE",
            "attempt_id",attempt.get("attempt_id").asString(),"nonce",attempt.get("nonce").asString(),"token",signed("oidc-owner",attempt.get("nonce").asString()))),200));
    }
    private JsonNode email(String siteKey) throws Exception {
        String recipient="acceptance-customer@example.test";
        var challenge=http(publicPost(ROOT+"email-otp/challenges",Map.of("site_key",siteKey,"email",recipient,"intent","AUTHENTICATE")),202);
        assertThat(delivery.deliverReady("cucumber-mailpit")).isPositive();
        String code=CustomerMailpitIntegrationTest.mailpitCode(recipient);
        var result=http(publicPost(ROOT+"email-otp/challenges/"+challenge.get("challenge_id").asString()+"/verify",Map.of("site_key",siteKey,"code",code)),200);
        CustomerMailpitIntegrationTest.clearMailpit();return result;
    }
    @When("a Customer verifies an OTP delivered by Mailpit") public void otp() throws Exception {remember(email("customer-one"));}
    @Then("the current Customer is active and only safe fields are returned") public void safeCustomer() throws Exception {
        var response=http(get("/api/v1/customer/me").header("Origin",ORIGIN).header("Authorization","Bearer "+flow().get("session")),200);
        assertThat(response.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(response.toString().contains(flow().get("session"))).isFalse();
        assertThat(response.has("email")).isFalse();assertThat(response.has("subject")).isFalse();
    }
    @When("the same email authenticates on both Sites") public void sameEmail() throws Exception {
        remember(email("customer-one"));var second=email("customer-two");flow().put("second_customer",second.get("customer").get("id").asString());flow().put("second_session",second.get("session_token").asString());
    }
    @Then("the two Customers and sessions are Site isolated") public void isolated() throws Exception {
        assertThat(flow().get("customer").equals(flow().get("second_customer"))).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isEqualTo(2);scope();
    }
    @Then("cross-Site checkout and cross-Origin Customer reads are rejected") public void scope() throws Exception {
        http(publicPost("/api/v1/public/sites/customer-two/checkouts",Map.of("price_key","customer-price"))
            .header("Idempotency-Key",UUID.randomUUID().toString()).header("Authorization","Bearer "+flow().get("session")),401);
        http(get("/api/v1/customer/me").header("Origin","http://localhost:3001").header("Authorization","Bearer "+flow().get("session")),401);
        assertThat(jdbc.queryForObject("select count(*) from payments",Integer.class)).isZero();
    }
    private void link(String subject,int expected) throws Exception {
        String proof=exchange(subject,"link-proofs").get("proof_token").asString();
        http(publicPost("/api/v1/customer/identities",Map.of("proof_token",proof)).header("Authorization","Bearer "+flow().get("session")),expected);
    }
    @When("another verified identity is explicitly linked") public void link() throws Exception {link("additional-identity",201);}
    @Then("one Customer owns two identities") public void linked() {
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities where customer_id=?",Integer.class,UUID.fromString(flow().get("customer")))).isEqualTo(2);
    }
    @When("two Customers authenticate and one tries to link the other's identity") public void conflict() throws Exception {
        siteToken();exchange("other-owner","exchanges");link("other-owner",409);
    }
    @Then("linking conflicts and both Customer graphs remain separate") public void noMerge() {
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(distinct customer_id) from customer_identities",Integer.class)).isEqualTo(2);
    }
    private JsonNode checkout(String session,int expected) throws Exception {
        var request=publicPost("/api/v1/public/sites/customer-one/checkouts",Map.of("price_key","customer-price")).header("Idempotency-Key",UUID.randomUUID().toString());
        if(session!=null)request.header("Authorization","Bearer "+session);
        return http(request,expected);
    }
    @When("authenticated and guest catalog checkouts are created") public void catalog() throws Exception {siteToken();checkout(flow().get("session"),201);checkout(null,201);}
    @Then("owned and guest Payments coexist and an invalid session creates no Payment") public void catalogOwnership() throws Exception {
        checkout("x".repeat(43),401);
        assertThat(jdbc.queryForObject("select count(*) from payments",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from payments where customer_id is not null",Integer.class)).isOne();
    }
    @When("two Customers create their own catalog Payments") public void histories() throws Exception {
        siteToken();for(int i=0;i<3;i++)checkout(flow().get("session"),201);
        String other=exchange("history-other","exchanges").get("session_token").asString();checkout(other,201);flow().put("second_session",other);
    }
    private JsonNode history(String session,String suffix) throws Exception {return http(get("/api/v1/customer/payments"+suffix).header("Origin",ORIGIN).header("Authorization","Bearer "+session),200);}
    @Then("history pages contain only the requesting Customer's Payments") public void pages() throws Exception {
        var first=history(flow().get("session"),"?limit=2");assertThat(first.get("items").size()).isEqualTo(2);
        var next=history(flow().get("session"),"?limit=2&cursor="+first.get("next_cursor").asString());assertThat(next.get("items").size()).isOne();
        var other=history(flow().get("second_session"),"");assertThat(other.get("items").size()).isOne();
        Set<String> ids=new HashSet<>();for(var item:first.get("items"))ids.add(item.get("payment_id").asString());
        for(var item:next.get("items"))assertThat(ids.add(item.get("payment_id").asString())).isTrue();
        assertThat(ids.contains(other.get("items").get(0).get("payment_id").asString())).isFalse();
    }
    @When("a Customer with an owned Payment is anonymized by the administrator") public void anonymize() throws Exception {
        siteToken();var payment=checkout(flow().get("session"),201);flow().put("payment",payment.get("payment_id").asString());
        flow().put("financial",JSON.writeValueAsString(jdbc.queryForList("select id,site_id,customer_id,amount_minor,currency,status from payments")));
        adminPost("/api/v1/admin/sites/"+site("customer-one")+"/customers/"+flow().get("customer")+"/anonymization",Map.of("confirmation",flow().get("customer")),200);
    }
    @Then("the old session is rejected and Payment facts remain unchanged") public void retained() throws Exception {
        rejected();assertThat(JSON.writeValueAsString(jdbc.queryForList("select id,site_id,customer_id,amount_minor,currency,status from payments"))).isEqualTo(flow().get("financial"));
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select status from customers",String.class)).isEqualTo("ANONYMIZED");
    }
    @When("the Customer logs out") public void logout() throws Exception {http(delete("/api/v1/customer/session").header("Origin",ORIGIN).header("Authorization","Bearer "+flow().get("session")),204);}
    @Then("the old session is rejected") public void rejected() throws Exception {http(get("/api/v1/customer/me").header("Origin",ORIGIN).header("Authorization","Bearer "+flow().get("session")),401);}
    @When("a signed Site token is exchanged twice") public void replay() throws Exception {
        String token=signed("replay-owner",null);var request=Map.of("site_key","customer-one","provider_key","signed","token",token);
        remember(http(publicPost(ROOT+"site-token/exchanges",request),200));flow().put("replay_code",http(publicPost(ROOT+"site-token/exchanges",request),401).get("code").asString());
    }
    @Then("the replay has the uniform authentication failure") public void replayRejected(){assertThat(flow().get("replay_code")).isEqualTo("AUTHENTICATION_FAILED");}
}
