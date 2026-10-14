package dev.genericpayments.platform.partners.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.genericpayments.platform.partners.PartnerTestFixture;
import dev.genericpayments.platform.partners.domain.*;
import dev.genericpayments.platform.support.AdminApiClient;
import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

@TestPropertySource(properties = {"payment.partner-api.rate-limit.create-per-minute=1", "payment.partner-api.rate-limit.handoff-per-minute=1", "payment.partner-api.rate-limit.read-per-minute=1"})
class PartnerAuthenticationIntegrationTest extends PartnerTestFixture {
    @Autowired WebApplicationContext context; @Autowired AdminBootstrap bootstrap;
    MockMvc mvc;
    final String path = "/api/v1/partner/payments/" + UUID.randomUUID();
    @BeforeEach void http() { mvc = new AdminApiClient(context).mvc; }
    @Test void bearerCardinalityAndGrossShapeUseOneStableProblem() throws Exception {
        for (var auth : List.of("", "Basic xyz", "Bearer invalid", "Bearer  gpk_" + "A".repeat(12) + "_" + "B".repeat(43), "Bearer gpk_" + "A".repeat(12) + "_" + "B".repeat(43)))
            mvc.perform(get(path).header("Authorization", auth).header("X-Request-Id", "auth-6"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SITE_API_KEY_INVALID"))
                .andExpect(jsonPath("$.request_id").value("auth-6")).andExpect(header().string("Cache-Control", "no-store"));
        var key = key(PartnerScope.PAYMENTS_READ);
        mvc.perform(get(path).header("Authorization", "Bearer " + key, "Bearer " + key)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SITE_API_KEY_INVALID"));
    }
    @Test void scopesAreRequiredAfterAuthenticationAndRateAdmission() throws Exception {
        var key = key(PartnerScope.PAYMENTS_CREATE);
        mvc.perform(get(path).header("Authorization", "Bearer " + key)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SITE_API_SCOPE_REQUIRED"));
        assertThat(jdbc.queryForObject("select last_used_at from site_api_keys", java.sql.Timestamp.class).toInstant()).isEqualTo(INITIAL_TIME);
    }
    @Test void adminCookieCannotReplacePartnerCredentialPrincipal() throws Exception {
        bootstrap.initialize(); var client=new AdminApiClient(context); client.login();
        mvc.perform(get(path).cookie(client.session).header("Authorization","Bearer "+key(PartnerScope.PAYMENTS_CREATE)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SITE_API_SCOPE_REQUIRED"));
        mvc.perform(get(path).cookie(client.session)).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("SITE_API_KEY_INVALID"));
    }
    @Test void rateLimitsIsolateCredentialsAndRejectBeforeLastUsedTouch() throws Exception {
        var first = key(PartnerScope.PAYMENTS_READ); var second = key(PartnerScope.PAYMENTS_READ);
        mvc.perform(get(path).header("Authorization", "Bearer " + first)).andExpect(status().isNotFound());
        jdbc.update("update site_api_keys set last_used_at=null where prefix=?", first.substring(4,16));
        mvc.perform(get(path).header("Authorization", "Bearer " + first).header("X-Forwarded-For", "1.2.3.4"))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60")).andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
        assertThat(jdbc.queryForObject("select last_used_at from site_api_keys where prefix=?", java.sql.Timestamp.class, first.substring(4,16))).isNull();
        mvc.perform(get(path).header("Authorization", "Bearer " + second)).andExpect(status().isNotFound());
    }
    @Test void lastUsedThrottlesAdmittedRequests() throws Exception {
        var key = key(PartnerScope.PAYMENTS_READ);
        mvc.perform(get(path).header("Authorization", "Bearer " + key)).andExpect(status().isNotFound());
        TestClock.set(INITIAL_TIME.plusSeconds(60)); mvc.perform(get(path).header("Authorization", "Bearer " + key)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select last_used_at from site_api_keys", java.sql.Timestamp.class).toInstant()).isEqualTo(INITIAL_TIME);
        TestClock.set(INITIAL_TIME.plus(Duration.ofMinutes(5))); mvc.perform(get(path).header("Authorization", "Bearer " + key)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select last_used_at from site_api_keys", java.sql.Timestamp.class).toInstant()).isEqualTo(INITIAL_TIME.plus(Duration.ofMinutes(5)));
    }
    @Test void stageTwoAdminRequiresExistingSessionAndCsrf() throws Exception {
        bootstrap.initialize(); var client = new AdminApiClient(context); client.login();
        var adminPath = "/api/v1/admin/sites/" + site.value() + "/api-keys";
        mvc.perform(post(adminPath).contentType("application/json").content("{\"scopes\":[\"payments:read\"]}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(adminPath).cookie(client.session).contentType("application/json").content("{\"scopes\":[\"payments:read\"]}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
        client.postJson(adminPath, "{\"scopes\":[\"payments:read\"]}").andExpect(status().isCreated()).andExpect(jsonPath("$.api_key").isString()).andExpect(jsonPath("$.key_hash").doesNotExist());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='SITE_API_KEY_CREATED'", Integer.class)).isEqualTo(1);
    }
    @Test void lifecycleAuditFailureRollsBackMutation() throws Exception {
        bootstrap.initialize(); var client = new AdminApiClient(context); client.login();
        var base = "/api/v1/admin/sites/" + site.value() + "/api-keys";
        rejectWrite("audit_log", "insert", "new.action='SITE_API_KEY_CREATED'");
        client.postJson(base, "{\"scopes\":[\"payments:read\"]}").andExpect(status().isConflict());
        assertThat(count("site_api_keys")).isZero(); clearWriteFailure();
        var original = keys.create(site, Set.of(PartnerScope.PAYMENTS_READ), null);
        for (var operation : List.of("rotate", "revoke")) {
            rejectWrite("audit_log", "insert", "new.action='SITE_API_KEY_" + (operation.equals("rotate") ? "ROTATED" : "REVOKED") + "'");
            client.postJson(base + "/" + original.key().id().value() + "/" + operation, "{}").andExpect(status().isConflict());
            assertThat(count("site_api_keys")).isEqualTo(1);
            assertThat(keys.authenticate(original.apiKey()).keyId()).isEqualTo(original.key().id());
            assertThat(jdbc.queryForObject("select expires_at from site_api_keys", java.sql.Timestamp.class)).isNull(); clearWriteFailure();
        }
    }
    @Test void lifecycleAuditsContainOnlySafeIdsAndScopeStatus() throws Exception {
        bootstrap.initialize(); var client = new AdminApiClient(context); client.login();
        var original = keys.create(site, Set.of(PartnerScope.PAYMENTS_READ), null);
        var base = "/api/v1/admin/sites/" + site.value() + "/api-keys/" + original.key().id().value();
        client.postJson(base + "/rotate", "{}").andExpect(status().isCreated());
        client.postJson(base + "/revoke", "{}").andExpect(status().isNoContent());
        assertThat(jdbc.queryForList("select * from audit_log").toString()).doesNotContain(original.apiKey(), original.key().prefix(), original.key().keyHash());
    }
}
