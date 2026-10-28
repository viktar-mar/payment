package dev.genericpayments.platform.payments.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.genericpayments.platform.support.AdminApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/** Keeps deliberately exhausted default-client buckets out of the shared test context. */
@Import(PublicCheckoutClientIsolationIntegrationTest.IsolatedContext.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublicCheckoutClientIsolationIntegrationTest extends PublicCheckoutApiIntegrationTest {
    @Autowired PublicRateLimitProperties rateLimits;

    @Override
    @Test
    void missingIdempotencyAndInvalidPriceUseSafeProblemsAndOpenApiIds() throws Exception {
        var priorClient = new AdminApiClient(context).mvc;
        for (int admitted = 0; admitted < rateLimits.createPerMinute(); admitted++) {
            priorClient.perform(post(create).header("Origin", "https://shop.test")
                .contentType("application/json").content("{\"price_key\":\"lifetime\"}"))
                .andExpect(status().isBadRequest());
        }
        priorClient.perform(post(create).header("Origin", "https://shop.test")
            .contentType("application/json").content("{\"price_key\":\"lifetime\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
            .andExpect(header().string("Retry-After", "3"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Access-Control-Allow-Origin", "https://shop.test"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));

        for (int client = 0; client < 2; client++) {
            http();
            mvc.perform(post(create).header("Origin", "https://shop.test")
                .contentType("application/json").content("{\"price_key\":\"lifetime\"}")
                .remoteAddress("127.0.0.1"))
                .andExpect(status().isTooManyRequests());
            super.missingIdempotencyAndInvalidPriceUseSafeProblemsAndOpenApiIds();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedContext {}
}
