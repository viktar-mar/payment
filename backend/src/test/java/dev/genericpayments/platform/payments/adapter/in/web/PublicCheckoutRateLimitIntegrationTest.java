package dev.genericpayments.platform.payments.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.support.AdminApiClient;
import com.jayway.jsonpath.JsonPath;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;

@TestPropertySource(properties = {"payment.public-api.rate-limit.create-per-minute=1", "payment.public-api.rate-limit.confirm-per-minute=1", "payment.public-api.rate-limit.read-per-minute=1"})
class PublicCheckoutRateLimitIntegrationTest extends CheckoutTestFixture {
    @Autowired WebApplicationContext context;
    @Test void throttlesByRealIpAndOperationWithIntegerRetryAndCorsOnErrors() throws Exception {
        var mvc = new AdminApiClient(context).mvc;
        var ip = "client-" + UUID.randomUUID();
        var path = "/api/v1/public/sites/test-site/checkouts";
        var response = mvc.perform(post(path).header("Origin", "https://shop.test").header("Idempotency-Key", "one")
            .contentType("application/json").content("{\"price_key\":\"lifetime\"}").with(r -> { r.setRemoteAddr(ip); return r; }))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Map<String, Object> c = JsonPath.read(response, "$");
        mvc.perform(post(path).header("Origin", "https://shop.test").header("Idempotency-Key", "two")
            .header("X-Forwarded-For", "different-client").contentType("application/json").content("{\"price_key\":\"lifetime\"}")
            .with(r -> { r.setRemoteAddr(ip); return r; }))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"))
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Access-Control-Allow-Origin", "https://shop.test"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        var read = "/api/v1/public/checkouts/" + c.get("checkout_id");
        mvc.perform(get(read).header("Origin", "https://shop.test").header("Authorization", "Bearer " + c.get("checkout_token"))
            .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk());
        mvc.perform(get(read).header("Origin", "https://shop.test").header("Authorization", "Bearer " + c.get("checkout_token"))
            .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isTooManyRequests());
        mvc.perform(post(read + "/confirm").header("Origin", "https://shop.test").header("Authorization", "Bearer " + c.get("checkout_token"))
            .header("Idempotency-Key", "confirm").contentType("application/json").content("{\"payment_method_family\":\"CARD\"}")
            .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk());
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("payment_attempts")).isEqualTo(1);
    }
}
