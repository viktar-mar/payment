package dev.genericpayments.platform.payments.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.jayway.jsonpath.JsonPath;
import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.support.AdminApiClient;
import java.util.*;
import java.time.Duration;
import dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.*;

class PublicCheckoutApiIntegrationTest extends CheckoutTestFixture {
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    String ip;
    final String create = "/api/v1/public/sites/test-site/checkouts";
    @BeforeEach void http() { mvc = new AdminApiClient(context).mvc; ip = UUID.randomUUID().toString(); }
    ResultActions create(String key, String body) throws Exception {
        return mvc.perform(post(create).header("Origin", "https://shop.test").header("Idempotency-Key", key)
            .header("X-Request-Id", "public-test").contentType("application/json").content(body).with(r -> { r.setRemoteAddr(ip); return r; }));
    }
    Map<String, Object> checkout() throws Exception {
        return JsonPath.read(create("create-" + UUID.randomUUID(), "{\"price_key\":\"lifetime\"}")
            .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Access-Control-Allow-Origin", "https://shop.test"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
            .andExpect(jsonPath("$.amount_minor").value(4999)).andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.provider_client_payload").isEmpty()).andExpect(jsonPath("$.confirmation_mode").value("SERVER"))
            .andReturn().getResponse().getContentAsString(), "$");
    }
    @Test void publicCreateReadConfirmAreStrictAndNeverReturnTokenAgain() throws Exception {
        var c = checkout(); var path = "/api/v1/public/checkouts/" + c.get("checkout_id");
        mvc.perform(get(path).header("Origin", "https://shop.test").header("Authorization", "Bearer " + c.get("checkout_token")))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.checkout_token").doesNotExist()).andExpect(jsonPath("$.status").value("REQUIRES_PAYMENT_METHOD"));
        mvc.perform(post(path + "/confirm").header("Origin", "https://shop.test").header("Authorization", "Bearer " + c.get("checkout_token"))
            .header("Idempotency-Key", "confirm").contentType("application/json").content("{\"payment_method_family\":\"CARD\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSING"))
            .andExpect(jsonPath("$.checkout_token").doesNotExist());
        for (var field : List.of("amount_minor", "currency", "misspelled")) {
            create("strict-" + field, "{\"price_key\":\"lifetime\",\"" + field + "\":\"1\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Access-Control-Allow-Origin", "https://shop.test"));
        }
        assertThat(count("payments")).isEqualTo(1);
    }
    @Test void expiryRemainsExactAcrossPostgresCreateReplayReadAndConfirm() throws Exception {
        TestClock.set(java.time.Instant.parse("2026-09-13T10:15:30.123456111Z"));
        var key = "precision-" + UUID.randomUUID();
        Map<String, Object> created = JsonPath.read(create(key, "{\"price_key\":\"lifetime\"}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$");
        var expiry = created.get("expires_at");
        var path = "/api/v1/public/checkouts/" + created.get("checkout_id");
        var bearer = "Bearer " + created.get("checkout_token");
        var replay = create(key, "{\"price_key\":\"lifetime\"}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var read = mvc.perform(get(path).header("Origin", "https://shop.test").header("Authorization", bearer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.checkout_token").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        var confirm = mvc.perform(post(path + "/confirm").header("Origin", "https://shop.test").header("Authorization", bearer)
            .header("Idempotency-Key", "precision-confirm").contentType("application/json").content("{\"payment_method_family\":\"CARD\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSING"))
            .andExpect(jsonPath("$.checkout_token").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertThat(List.of(JsonPath.<String>read(replay, "$.expires_at"), JsonPath.<String>read(read, "$.expires_at"),
            JsonPath.<String>read(confirm, "$.expires_at"))).containsOnly((String) expiry);
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("payment_attempts")).isEqualTo(1);
    }
    @Test void originIsMandatoryExactAndPreflightHasNoCredentials() throws Exception {
        mvc.perform(post(create).contentType("application/json").content("{\"price_key\":\"lifetime\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"))
            .andExpect(header().string("Cache-Control", "no-store"));
        for (var origin : List.of("https://evil.test", "null", "https://shop.test/", "https://shop.test.evil")) {
            mvc.perform(post(create).header("Origin", origin).header("Idempotency-Key", "origin")
                .contentType("application/json").content("{\"price_key\":\"lifetime\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
        mvc.perform(options(create).header("Origin", "https://shop.test").header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://shop.test"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        assertThat(count("payments")).isZero();
    }
    @Test void bearerHeaderMustBeSingleAndWellFormed() throws Exception {
        var c = checkout(); var path = "/api/v1/public/checkouts/" + c.get("checkout_id");
        for (var auth : List.of("", "Basic xyz", "Bearer invalid", "Bearer " + c.get("checkout_token") + ", Bearer invalid", "Bearer  " + c.get("checkout_token"))) {
            mvc.perform(get(path).header("Origin", "https://shop.test").header("Authorization", auth))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("CHECKOUT_TOKEN_INVALID"))
                .andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(get(path).header("Origin", "https://shop.test").header("Authorization", "Bearer " + c.get("checkout_token"), "Bearer invalid"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("CHECKOUT_TOKEN_INVALID"));
    }
    @Test void missingIdempotencyAndInvalidPriceUseSafeProblemsAndOpenApiIds() throws Exception {
        mvc.perform(post(create).header("Origin", "https://shop.test").contentType("application/json").content("{\"price_key\":\"lifetime\"}"))
            .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        create("bad-price", "{\"price_key\":\"not-found\"}").andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PRICE_NOT_AVAILABLE")).andExpect(jsonPath("$.request_id").value("public-test"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/public/sites/{siteKey}/checkouts'].post.operationId").value("createCatalogCheckout"))
            .andExpect(jsonPath("$.paths['/api/v1/public/checkouts/{checkoutId}/confirm'].post.operationId").value("confirmCheckout"))
            .andExpect(jsonPath("$.paths['/api/v1/public/checkouts/{checkoutId}'].get.operationId").value("getCheckout"));
    }
    @Test void expiredAndInvalidTokensHaveSafeProblemsAndNoStoreOnEveryRoute() throws Exception {
        var c = checkout(); var path = "/api/v1/public/checkouts/" + c.get("checkout_id");
        mvc.perform(get(path).header("Origin", "https://shop.test").header("Authorization", "Bearer " + "A".repeat(43)))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("CHECKOUT_TOKEN_INVALID"))
            .andExpect(header().string("Access-Control-Allow-Origin", "https://shop.test"));
        mvc.perform(get(path).header("Origin", "https://shop.test", "https://evil.test"))
            .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"));
        TestClock.set(INITIAL_TIME.plus(Duration.ofHours(24)));
        mvc.perform(get(path).header("Origin", "https://shop.test").header("Authorization", "Bearer " + c.get("checkout_token")))
            .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("CHECKOUT_EXPIRED"))
            .andExpect(jsonPath("$.payment_id").value(c.get("payment_id"))).andExpect(jsonPath("$.checkout_token").doesNotExist())
            .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post(path + "/confirm").header("Origin", "https://shop.test").header("Authorization", "Bearer " + c.get("checkout_token"))
            .header("Idempotency-Key", "expired").contentType("application/json").content("{\"payment_method_family\":\"CARD\"}"))
            .andExpect(status().isGone()).andExpect(header().string("Cache-Control", "no-store"));
        assertThat(count("payment_attempts")).isZero();
    }
    @Test void validationErrorsAreSnakeCaseSafeAndPreflightRejectsExtraAuthority() throws Exception {
        create("invalid", "{\"price_key\":\"\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.field_errors[0].field").value("price_key"))
            .andExpect(jsonPath("$.field_errors[0].code").value("INVALID_VALUE"));
        mvc.perform(options(create).header("Origin", "https://shop.test").header("Access-Control-Request-Method", "DELETE"))
            .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options(create).header("Origin", "https://shop.test").header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "x-admin-secret"))
            .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mvc.perform(get("/api/v1/public/not-found").header("Origin", "https://shop.test"))
            .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
    }
}
