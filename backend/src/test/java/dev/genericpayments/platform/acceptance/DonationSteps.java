package dev.genericpayments.platform.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import io.cucumber.java.en.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Sensitive fixture values never occur in Gherkin labels or assertion diagnostics. */
public class DonationSteps {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String ORIGIN="http://localhost:3000";
    private final AcceptanceWorld world;
    private final JdbcTemplate jdbc;
    private final String message="task9-private-donor-"+UUID.randomUUID();
    private String campaign,id,key=UUID.randomUUID().toString();
    private int version;
    private JsonNode created;
    public DonationSteps(AcceptanceWorld world,JdbcTemplate jdbc){this.world=world;this.jdbc=jdbc;}
    private JsonNode http(MockHttpServletRequestBuilder request,int expected)throws Exception{
        var response=world.api().mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as("Donation HTTP status").isEqualTo(expected);
        return response.getContentAsString().isBlank()?JSON.nullNode():JSON.readTree(response.getContentAsString());
    }
    private JsonNode admin(String method,String path,Object body,int expected)throws Exception{
        var request=method.equals("PUT")?put(path):post(path);
        return http(request.cookie(world.api().session,world.api().xsrf).header("X-XSRF-TOKEN",world.api().xsrf.getValue())
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(JSON.writeValueAsString(body)),expected);
    }
    private String base(){return "/api/v1/admin/sites/"+world.siteIds().get("customer-one")+"/donation-campaigns";}
    private String publicPath(){return "/api/v1/public/sites/customer-one/donation-campaigns/community-help";}
    private Map<String,Object> config(boolean custom,boolean anonymous){return new LinkedHashMap<>(Map.of("key","community-help","display_name","Community","currency","EUR","suggested_amounts",List.of(500,1000),"minimum_amount",100,"maximum_amount",2000,"custom_amount_allowed",custom,"anonymous_allowed",anonymous));}
    @Given("a Donation Campaign in {string} with custom amounts {word} and anonymous access {word}")
    public void campaign(String state,String custom,String anonymous)throws Exception{
        campaign=admin("POST",base(),config(Boolean.parseBoolean(custom),Boolean.parseBoolean(anonymous)),201).get("id").asString();
        // Reuse the existing provider/refund/callback steps against this exact Site and Payment.
        world.siteIds().put("acceptance",world.siteIds().get("customer-one"));
        world.paymentFlow().put("confirm_key",UUID.randomUUID().toString());
        if(!state.equals("DRAFT"))transition(state);
    }
    @When("the Donation Campaign becomes {string}") public void transition(String status)throws Exception{
        var response=admin("POST",base()+"/"+campaign+"/status",Map.of("status",status,"expected_version",version),200);
        version=response.get("version").asInt();
    }
    @Then("the Donation Campaign public read returns {int}") public void read(int expected)throws Exception{http(get(publicPath()).header("Origin",ORIGIN),expected);}
    private JsonNode create(long amount,String authority,String receipt,int expected)throws Exception{
        var request=post(publicPath()+"/checkouts").header("Origin",ORIGIN).header("Idempotency-Key",receipt).contentType("application/json")
            .content(JSON.writeValueAsString(Map.of("amount_minor",amount,"message",message)));
        if(authority!=null)request.header("Authorization","Bearer "+authority);
        return http(request,expected);
    }
    @When("a Donation for {long} cents with {word} authority returns {int}")
    public void checkout(long amount,String authority,int expected)throws Exception{
        String token=switch(authority){case "anonymous"->null;case "Customer"->world.customerFlow().get("session");case "invalid"->"invalid";default->throw new IllegalArgumentException("Unknown authority fixture");};
        var result=create(amount,token,key,expected);
        if(expected==201){created=result;id=result.get("donation").get("donation_id").asString();
            for(String field:List.of("payment_id","checkout_id","checkout_token"))world.paymentFlow().put(field,result.get(field).asString());}
    }
    @Then("exactly {int} atomic Donation and Payment pair exists") public void pairs(int count){
        assertThat(jdbc.queryForObject("select count(*) from donations",Integer.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("select count(*) from payments where source='DONATION'",Integer.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("select count(*) from donations d join payments p on p.donation_id=d.id and p.site_id=d.site_id",Integer.class)).isEqualTo(count);
    }
    @Then("the Donation message is encrypted and no plaintext enters financial sinks") public void encrypted(){
        assertThat(jdbc.queryForObject("select message_state='PRESENT' and message_ciphertext is not null and octet_length(message_nonce)=12 from donations where id=?",Boolean.class,UUID.fromString(id))).isTrue();
        finite();
    }
    private void finite(){for(String table:List.of("donations","payment_events","outbox_events","outbox_deliveries","audit_log","idempotency_records"))
        assertThat(jdbc.queryForList("select row_to_json(t)::text from "+table+" t").toString().contains(message)).as("finite Donation plaintext sink scan").isFalse();}
    @Then("the Donation belongs only to the authenticated Customer") public void owned(){
        assertThat(jdbc.queryForObject("select customer_id::text from payments where id=?",String.class,UUID.fromString(world.paymentFlow().get("payment_id")))).isEqualTo(world.customerFlow().get("customer"));
    }
    @When("the Customer Donation intent is replayed and changed identity conflicts") public void replay()throws Exception{
        var again=create(500,world.customerFlow().get("session"),key,201);
        assertThat(again.toString().equals(created.toString())).as("identical captured Donation response").isTrue();
        create(500,null,key,409);create(1000,world.customerFlow().get("session"),key,409);pairs(1);
    }
    @When("the Donation checkout is confirmed") public void confirm()throws Exception{
        http(post("/api/v1/public/checkouts/"+world.paymentFlow().get("checkout_id")+"/confirm").header("Origin",ORIGIN)
            .header("Authorization","Bearer "+world.paymentFlow().get("checkout_token")).header("Idempotency-Key",world.paymentFlow().get("confirm_key"))
            .contentType("application/json").content("{\"payment_method_family\":\"CARD\"}"),200);
    }
    @When("a Donation insert fails and the same intent is retried") public void rollback()throws Exception{
        jdbc.execute("create function task9_abort_payment() returns trigger language plpgsql as $$ begin raise exception 'synthetic rollback'; end $$");
        jdbc.execute("create trigger task9_abort_payment before insert on payments for each row execute function task9_abort_payment()");
        try{checkout(500,"anonymous",500);pairs(0);assertThat(jdbc.queryForObject("select count(*) from idempotency_records where idempotency_key=?",Integer.class,key)).isZero();}
        finally{jdbc.execute("drop trigger task9_abort_payment on payments");jdbc.execute("drop function task9_abort_payment()");}
        checkout(500,"anonymous",201);pairs(1);
    }
    @Then("the signed Donation callback has strict message-free version two fields") public void v2(){
        String body=jdbc.queryForObject("select payload::text from outbox_events where callback_type='payment.succeeded'",String.class);
        var parsed=JSON.readTree(body);Set<String> fields=new HashSet<>();parsed.properties().forEach(e->fields.add(e.getKey()));
        assertThat(fields).containsExactlyInAnyOrder("schema_version","event_id","event_type","site_id","payment_id","external_order_id","payment_status","amount_minor","currency","occurred_at","donation_id","campaign_key","customer_id");
        assertThat(parsed.get("schema_version").asInt()).isEqualTo(2);assertThat(parsed.get("donation_id").asString()).isEqualTo(id);finite();
    }
    @When("the administrator explicitly redacts the Donation message") public void redact()throws Exception{
        var before=financial();admin("POST","/api/v1/admin/donations/"+id+"/message-redactions",Map.of("confirmation",id),200);
        assertThat(financial().equals(before)).as("financial facts retained across explicit message redaction").isTrue();removed();
    }
    @When("the Donation Customer is anonymized") public void anonymize()throws Exception{
        var before=financial();admin("POST","/api/v1/admin/sites/"+world.siteIds().get("customer-one")+"/customers/"+world.customerFlow().get("customer")+"/anonymization",Map.of("confirmation",world.customerFlow().get("customer")),200);
        assertThat(financial().equals(before)).as("financial facts retained across Customer anonymization").isTrue();removed();
    }
    private void removed(){assertThat(jdbc.queryForObject("select message_state='REDACTED' and message_ciphertext is null and message_nonce is null and message_key_version is null from donations where id=?",Boolean.class,UUID.fromString(id))).isTrue();finite();}
    private List<Object> financial(){var result=new ArrayList<Object>();for(String table:List.of("payments","payment_events","outbox_events","outbox_deliveries","outbox_retry_requests"))result.add(jdbc.queryForList("select row_to_json(t)::text from "+table+" t order by id"));return result;}
    @Then("Customer Partner and Admin Donation reads enforce ownership without messages") public void isolation()throws Exception{
        var owner=http(get("/api/v1/customer/donations").header("Origin",ORIGIN).header("Authorization","Bearer "+world.customerFlow().get("session")),200);
        var other=http(get("/api/v1/customer/donations").header("Origin",ORIGIN).header("Authorization","Bearer "+world.customerFlow().get("second_session")),200);
        assertThat(owner.get("items").size()).isOne();assertThat(other.get("items").size()).isZero();assertThat(owner.toString().contains(message)).isFalse();
        for(String site:List.of("customer-one","customer-two")){
            var apiKey=admin("POST","/api/v1/admin/sites/"+world.siteIds().get(site)+"/api-keys",Map.of("scopes",List.of("donations:read")),201).get("api_key").asString();
            var result=http(get("/api/v1/partner/donations/"+id).header("Authorization","Bearer "+apiKey),site.equals("customer-one")?200:404);
            assertThat(result.toString().contains(message)).isFalse();
        }
        http(get("/api/v1/admin/donations/"+id),401);
        var detail=http(get("/api/v1/admin/donations/"+id).cookie(world.api().session,world.api().xsrf),200);
        assertThat(detail.toString().contains(message)).as("authorized Admin plaintext visibility").isTrue();
    }
}
