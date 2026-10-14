package dev.genericpayments.platform.payments.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.genericpayments.platform.partners.PartnerTestFixture;
import dev.genericpayments.platform.partners.domain.PartnerScope;
import dev.genericpayments.platform.support.AdminApiClient;
import com.jayway.jsonpath.JsonPath;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.*;

class PartnerPaymentApiIntegrationTest extends PartnerTestFixture {
    @Autowired WebApplicationContext context; MockMvc mvc; String credential;
    final String path = "/api/v1/partner/payments";
    @BeforeEach void http() { mvc = new AdminApiClient(context).mvc; credential = key(PartnerScope.PAYMENTS_CREATE, PartnerScope.PAYMENTS_READ); }
    ResultActions create(String idem, String body) throws Exception { return mvc.perform(post(path).header("Authorization", "Bearer " + credential).header("Idempotency-Key", idem).header("X-Request-Id", "partner-6").contentType("application/json").content(body)); }
    String body(String order) { return "{\"external_order_id\":\"" + order + "\",\"amount_minor\":2300,\"currency\":\"EUR\",\"checkout_origin\":\"https://shop.test\",\"return_url\":\"https://shop.test/done?order=1\",\"metadata\":{\"channel\":\"partner\"}}"; }
    @Test void createsDynamicPaymentAndReplaysSameHandoffThenReadsSafeSummary() throws Exception {
        var text = create("same", body("order-1")).andExpect(status().isCreated()).andExpect(jsonPath("$.amount_minor").value(2300)).andExpect(jsonPath("$.status").value("REQUIRES_PAYMENT_METHOD")).andExpect(jsonPath("$.handoff_token").isString()).andExpect(jsonPath("$.checkout_token").doesNotExist()).andReturn().getResponse().getContentAsString();
        var token = (String) JsonPath.read(text, "$.handoff_token"); var id = (String) JsonPath.read(text, "$.payment_id");
        create("same", body("order-1")).andExpect(status().isCreated()).andExpect(jsonPath("$.payment_id").value(id)).andExpect(jsonPath("$.handoff_token").value(token));
        mvc.perform(get(path + "/" + id).header("Authorization", "Bearer " + credential)).andExpect(status().isOk()).andExpect(jsonPath("$.handoff_token").doesNotExist()).andExpect(jsonPath("$.checkout_token").doesNotExist()).andExpect(jsonPath("$.provider_payment_reference").doesNotExist());
        assertThat(count("payments")).isEqualTo(1); assertThat(count("fake_provider_operations")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select return_url from payments", String.class)).isEqualTo("https://shop.test/done?order=1");
    }
    @Test void credentialRotationDoesNotChangeDynamicCommandIdentity() throws Exception {
        var keyId=keys.authenticate(credential).keyId();
        var first=create("rotated",body("order")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        credential=keys.rotate(site,keyId).apiKey();
        create("rotated",body("order")).andExpect(status().isCreated())
            .andExpect(jsonPath("$.payment_id").value((String)JsonPath.read(first,"$.payment_id")))
            .andExpect(jsonPath("$.handoff_token").value((String)JsonPath.read(first,"$.handoff_token")));
        assertThat(count("payments")).isEqualTo(1); assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
    @Test void positiveAmountUppercaseCurrencyAndStrictBodyAreEnforced() throws Exception {
        for (var bad : List.of(body("zero").replace("2300", "0"), body("negative").replace("2300", "-1"), body("lower").replace("EUR", "eur"), body("invalid").replace("EUR", "ZZZ"), body("site").replace("\"metadata\":", "\"site_id\":\"" + site.value() + "\",\"metadata\":")))
            create(UUID.randomUUID().toString(), bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        assertThat(count("payments")).isZero();
    }
    @Test void metadataMustBeAFlatStringMapWithoutScalarCoercion() throws Exception {
        for (String value : List.of("123", "true", "null", "{\"nested\":\"value\"}", "[\"value\"]"))
            create(UUID.randomUUID().toString(),body("order").replace("\"channel\":\"partner\"","\"channel\":"+value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        assertThat(count("payments")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"request_id","headers","cookies","api.key","client.secret","Authorization"})
    void reservedMetadataNamesReturnSafeClientProblemsBeforeAnyBusinessWrite(String name) throws Exception {
        var response=create("reserved",body("order").replace("\"channel\":\"partner\"","\""+name+"\":\"private-marker\""))
            .andReturn().getResponse();
        for (var table:List.of("idempotency_records","payments","checkout_sessions","payment_events","payment_attempts","fake_provider_operations"))
            assertThat(count(table)).as(table).isZero();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat((String)JsonPath.read(response.getContentAsString(),"$.code")).isEqualTo("invalid_request");
        assertThat((String)JsonPath.read(response.getContentAsString(),"$.request_id")).isEqualTo("partner-6");
        assertThat(response.getContentAsString()).doesNotContain("private-marker", "Invalid canonical business fields", credential);
    }
    @Test void minorAmountMustBeAnIntegerWithoutRoundingOrStringCoercion() throws Exception {
        for (String amount:List.of("2300.5","\"2300\""))
            create(UUID.randomUUID().toString(),body("order").replace("2300",amount))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        assertThat(count("payments")).isZero();
    }
    @Test void externalOrderCollisionHasStableErrorAndCrossSiteReadIsNotFound() throws Exception {
        var text = create("first", body("order-1")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        create("second", body("order-1")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXTERNAL_ORDER_ALREADY_EXISTS"));
        create("first", body("order-2")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        var other = new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID());
        jdbc.update("insert into sites(id,site_key,display_name,status,payment_account_id,payment_method_policy,created_at,updated_at,version) select ?,'other-site','Other','ACTIVE',payment_account_id,payment_method_policy,created_at,updated_at,0 from sites where id=?", other.value(), site.value());
        jdbc.update("insert into site_allowed_origins values (?,'https://shop.test')", other.value()); jdbc.update("insert into site_authentication_modes values (?,'SITE_TOKEN')", other.value());
        credential = keys.create(other, Set.of(PartnerScope.PAYMENTS_CREATE, PartnerScope.PAYMENTS_READ), null).apiKey();
        mvc.perform(get(path + "/" + JsonPath.read(text, "$.payment_id")).header("Authorization", "Bearer " + credential)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        mvc.perform(post(path+"/"+JsonPath.read(text,"$.payment_id")+"/handoffs").header("Authorization","Bearer "+credential).header("Idempotency-Key","foreign")
            .contentType("application/json").content("{\"checkout_origin\":\"https://shop.test\"}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        create("other-first", body("order-1")).andExpect(status().isCreated()); assertThat(count("payments")).isEqualTo(2);
    }
    @Test void returnAndCheckoutOriginsRejectAuthorityPathAndMismatch() throws Exception {
        for (var origin : List.of("https://evil.test", "https://shop.test/", "https://user@shop.test", "https://shop.test?q=1", "https://shop.test#x")) create(UUID.randomUUID().toString(), body("order").replace("\"checkout_origin\":\"https://shop.test\"", "\"checkout_origin\":\"" + origin + "\"")).andExpect(status().isForbidden());
        for (var url : List.of("/done", "https://evil.test/done", "https://user@shop.test/done", "https://shop.test/done#x", "https://shop.test/" + "x".repeat(2048))) create(UUID.randomUUID().toString(), body("order").replace("https://shop.test/done?order=1", url)).andExpect(status().isBadRequest());
        create("normal", body("order").replace("\"checkout_origin\":\"https://shop.test\"", "\"checkout_origin\":\"https://SHOP.TEST:443\"").replace("https://shop.test/done?order=1", "https://SHOP.TEST:443/done?q=1")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select origin from checkout_sessions", String.class)).isEqualTo("https://shop.test");
    }
    @Test void paymentCreateAndHandoffRequireCreateScope() throws Exception {
        credential = key(PartnerScope.PAYMENTS_READ);
        create("denied", body("order")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SITE_API_SCOPE_REQUIRED"));
        mvc.perform(post(path + "/" + UUID.randomUUID() + "/handoffs").header("Authorization", "Bearer " + credential).header("Idempotency-Key", "handoff").contentType("application/json").content("{\"checkout_origin\":\"https://shop.test\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SITE_API_SCOPE_REQUIRED"));
        assertThat(count("payments")).isZero();
    }
    @Test void publicExchangeNeedsOriginAndOneTimeTokenButNoIdempotencyOrSiteKey() throws Exception {
        var response = create("created", body("order")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(response, "$.handoff_token"); var exchange = "/api/v1/public/handoffs";
        mvc.perform(post(exchange).contentType("application/json").content("{\"handoff_token\":\"" + token + "\"}")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("CHECKOUT_TOKEN_INVALID"));
        mvc.perform(post(exchange).header("Origin","https://shop.test","https://shop.test").contentType("application/json").content("{\"handoff_token\":\""+token+"\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("CHECKOUT_TOKEN_INVALID"));
        mvc.perform(post(exchange).header("Origin","https://shop.test").contentType("application/json").content("{\"handoff_token\":\""+credential+"\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("CHECKOUT_TOKEN_INVALID"));
        mvc.perform(options(exchange).header("Origin","https://shop.test").header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","Content-Type"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Access-Control-Allow-Origin","https://shop.test"));
        mvc.perform(post(exchange).header("Origin", "https://shop.test").contentType("application/json").content("{\"handoff_token\":\"" + token + "\"}"))
            .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("Access-Control-Allow-Origin", "https://shop.test")).andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
            .andExpect(jsonPath("$.checkout_token").isString()).andExpect(jsonPath("$.provider_client_payload").isEmpty()).andExpect(jsonPath("$.status").value("REQUIRES_PAYMENT_METHOD"));
        mvc.perform(post(exchange).header("Origin", "https://shop.test").contentType("application/json").content("{\"handoff_token\":\"" + token + "\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("CHECKOUT_TOKEN_INVALID"));
    }
    @Test void issueEndpointReplaysSameSubjectHandoffWithoutPreparingProvider() throws Exception {
        var created=create("create",body("order")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var url=path+"/"+JsonPath.read(created,"$.payment_id")+"/handoffs";
        var issued=mvc.perform(post(url).header("Authorization","Bearer "+credential).header("Idempotency-Key","issue")
            .contentType("application/json").content("{\"checkout_origin\":\"https://shop.test\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(post(url).header("Authorization","Bearer "+credential).header("Idempotency-Key","issue")
            .contentType("application/json").content("{\"checkout_origin\":\"https://shop.test\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.handoff_token").value((String)JsonPath.read(issued,"$.handoff_token")));
        assertThat(count("checkout_sessions")).isEqualTo(2); assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void normalizedSiteAllowlistKeepsCreatedAndIssuedHandoffsUsableThroughPublicConfirm(boolean issue) throws Exception {
        jdbc.update("update site_allowed_origins set origin='https://SHOP.TEST:443' where site_id=?",site.value());
        var created=create("create",body("order")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String handoff=JsonPath.read(created,"$.handoff_token");
        if (issue) {
            var issued=mvc.perform(post(path+"/"+JsonPath.read(created,"$.payment_id")+"/handoffs")
                .header("Authorization","Bearer "+credential).header("Idempotency-Key","issue")
                .contentType("application/json").content("{\"checkout_origin\":\"https://shop.test\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            handoff=JsonPath.read(issued,"$.handoff_token");
        }
        var exchanged=mvc.perform(post("/api/v1/public/handoffs").header("Origin","https://shop.test")
            .contentType("application/json").content("{\"handoff_token\":\""+handoff+"\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var checkout="/api/v1/public/checkouts/"+JsonPath.read(exchanged,"$.checkout_id");
        var bearer="Bearer "+JsonPath.read(exchanged,"$.checkout_token");
        assertThat(jdbc.queryForObject("select origin from checkout_sessions where id=?",String.class,
            UUID.fromString(JsonPath.read(exchanged,"$.checkout_id")))).isEqualTo("https://shop.test");
        for (var denied:List.of("https://SHOP.TEST:443","https://shop.test/","https://shop.test?q=1","https://user@shop.test")) {
            mvc.perform(get(checkout).header("Origin",denied).header("Authorization",bearer)).andExpect(status().isForbidden());
            mvc.perform(post(checkout+"/confirm").header("Origin",denied).header("Authorization",bearer)
                .header("Idempotency-Key","denied").contentType("application/json").content("{\"payment_method_family\":\"CARD\"}"))
                .andExpect(status().isForbidden());
        }
        assertThat(count("payment_attempts")).isZero();
        mvc.perform(get(checkout).header("Origin","https://shop.test").header("Authorization",bearer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUIRES_PAYMENT_METHOD"));
        mvc.perform(post(checkout+"/confirm").header("Origin","https://shop.test").header("Authorization",bearer)
            .header("Idempotency-Key","confirm").contentType("application/json").content("{\"payment_method_family\":\"CARD\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSING"));
        assertThat(count("payment_attempts")).isEqualTo(1);
    }
    @Test void openApiPublishesAllTaskSixOperationIds() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/partner/payments'].post.operationId").value("createPartnerPayment"))
            .andExpect(jsonPath("$.paths['/api/v1/partner/payments/{paymentId}'].get.operationId").value("getPartnerPayment"))
            .andExpect(jsonPath("$.paths['/api/v1/partner/payments/{paymentId}/handoffs'].post.operationId").value("issuePartnerPaymentHandoff"))
            .andExpect(jsonPath("$.paths['/api/v1/public/handoffs'].post.operationId").value("exchangePublicHandoff"))
            .andExpect(jsonPath("$.paths['/api/v1/admin/sites/{siteId}/api-keys'].post.operationId").value("createAdminSiteApiKey"));
    }
}
