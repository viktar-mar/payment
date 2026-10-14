package dev.genericpayments.platform.admin.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.genericpayments.platform.support.PostgresIntegrationTest;
import dev.genericpayments.platform.admin.application.port.out.AdminPasswordPort;
import dev.genericpayments.platform.admin.application.port.out.AdminUserPort;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@TestPropertySource(properties = {
    "payment.admin.bootstrap.username=admin",
    "payment.admin.bootstrap.password=local-password"
})
class AdminAuthenticationIntegrationTest extends PostgresIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminBootstrap bootstrap;
    @Autowired AdminUserPort users;
    @Autowired AdminPasswordPort passwords;
    @Autowired Clock clock;
    private MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        clearDatabase();
        bootstrap.initialize();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(RequestIdFilter.class))
            .apply(springSecurity()).build();
    }

    @Test
    void openApiNamesAuthOperationsAndMatchesLoginLogoutNoContentResponses() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/admin/v1/auth/sessions'].post.operationId").value("loginAdmin"))
            .andExpect(jsonPath("$.paths['/api/admin/v1/auth/session'].get.operationId").value("getAdminSession"))
            .andExpect(jsonPath("$.paths['/api/admin/v1/auth/session'].delete.operationId").value("logoutAdmin"))
            .andExpect(jsonPath("$.paths['/api/admin/v1/auth/sessions'].post.responses['204']").exists())
            .andExpect(jsonPath("$.paths['/api/admin/v1/auth/sessions'].post.responses['204'].content").doesNotExist())
            .andExpect(jsonPath("$.paths['/api/admin/v1/auth/sessions'].post.responses['200']").doesNotExist())
            .andExpect(jsonPath("$.paths['/api/admin/v1/auth/session'].delete.responses['204']").exists())
            .andExpect(jsonPath("$.paths['/api/admin/v1/auth/session'].delete.responses['204'].content").doesNotExist())
            .andExpect(jsonPath("$.paths['/api/admin/v1/auth/session'].delete.responses['200']").doesNotExist())
            .andExpect(jsonPath("$.paths['/api/admin/v1/auth/session'].get.responses['200'].content['application/json'].schema['$ref']")
                .value("#/components/schemas/SessionResponse"));
        var response = login().getResponse();
        assertThat(response.getContentAsString()).isEmpty();
        var csrf = response.getCookie("XSRF-TOKEN");
        mvc.perform(delete("/api/admin/v1/auth/session")
                .cookie(response.getCookie("PAYMENT_ADMIN_SESSION"), csrf).header("X-XSRF-TOKEN", csrf.getValue()))
            .andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test
    void openApiSessionExpiryUsesTheRuntimeSnakeCaseWireName() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.components.schemas.SessionResponse.properties.expires_at.type").value("string"))
            .andExpect(jsonPath("$.components.schemas.SessionResponse.properties.expires_at.format").value("date-time"))
            .andExpect(jsonPath("$.components.schemas.SessionResponse.properties.expiresAt").doesNotExist());
        var response = login().getResponse();
        mvc.perform(get("/api/admin/v1/auth/session").cookie(response.getCookie("PAYMENT_ADMIN_SESSION")))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.expires_at").value("2026-09-13T18:15:30Z"))
            .andExpect(jsonPath("$.expiresAt").doesNotExist());
    }

    @Test
    void protectedEndpointRejectsAnonymousAndLoginSetsSecureCookie() throws Exception {
        mvc.perform(get("/api/admin/v1/auth/session")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/v1/auth/sessions")
                .contentType("application/json")
                .content("""
                    {"username":"admin","password":"local-password"}
                    """))
            .andExpect(status().isNoContent())
            .andExpect(cookie().httpOnly("PAYMENT_ADMIN_SESSION", true))
            .andExpect(cookie().path("PAYMENT_ADMIN_SESSION", "/"))
            .andExpect(cookie().secure("PAYMENT_ADMIN_SESSION", true))
            .andExpect(cookie().sameSite("PAYMENT_ADMIN_SESSION", "Strict"))
            .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    void loginPersistsOnlyHashAndDurableSuccessAndFailureAudits() throws Exception {
        var result = login();
        String token = result.getResponse().getCookie("PAYMENT_ADMIN_SESSION").getValue();
        assertThat(jdbc.queryForObject("select token_hash from admin_sessions", String.class))
            .matches("[0-9a-f]{64}").doesNotContain(token);
        assertThat(jdbc.queryForObject("select password_hash from admin_users", String.class)).startsWith("$argon2id$");
        for (String username : new String[]{"admin", "unknown"}) {
            mvc.perform(post("/api/admin/v1/auth/sessions").header("X-Request-Id", "failed-login")
                    .contentType("application/json").content("{\"username\":\"" + username + "\",\"password\":\"wrong-secret\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:payment:problem:invalid_credentials"))
                .andExpect(jsonPath("$.title").value("Invalid credentials"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("invalid_credentials"))
                .andExpect(jsonPath("$.request_id").value("failed-login"))
                .andExpect(cookie().doesNotExist("PAYMENT_ADMIN_SESSION"));
        }
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='ADMIN_LOGIN_SUCCEEDED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='ADMIN_LOGIN_FAILED'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("select * from audit_log").toString())
            .doesNotContain("wrong-secret", "local-password", token, "unknown");
        assertThat(jdbc.queryForObject("select occurred_at from audit_log where action='ADMIN_LOGIN_SUCCEEDED'", java.sql.Timestamp.class).toInstant())
            .isEqualTo(INITIAL_TIME);
    }

    @Test
    void cookieAuthenticatesAndOnlyMatchingCsrfCookieAndHeaderAllowLogout() throws Exception {
        var response = login().getResponse();
        var session = response.getCookie("PAYMENT_ADMIN_SESSION");
        var csrf = response.getCookie("XSRF-TOKEN");
        assertThat(csrf.isHttpOnly()).isFalse();
        mvc.perform(get("/api/admin/v1/auth/session").cookie(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("admin"))
            .andExpect(jsonPath("$.expires_at").exists());
        mvc.perform(delete("/api/admin/v1/auth/session").cookie(session))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
        mvc.perform(delete("/api/admin/v1/auth/session").cookie(session, csrf).header("X-XSRF-TOKEN", "invalid"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
        mvc.perform(delete("/api/admin/v1/auth/session").cookie(session, csrf).header("X-XSRF-TOKEN", csrf.getValue()))
            .andExpect(status().isNoContent()).andExpect(cookie().maxAge("PAYMENT_ADMIN_SESSION", 0))
            .andExpect(cookie().path("PAYMENT_ADMIN_SESSION", "/"))
            .andExpect(cookie().secure("PAYMENT_ADMIN_SESSION", true))
            .andExpect(cookie().httpOnly("PAYMENT_ADMIN_SESSION", true))
            .andExpect(cookie().sameSite("PAYMENT_ADMIN_SESSION", "Strict"));
        mvc.perform(get("/api/admin/v1/auth/session").cookie(session)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select revoked_at from admin_sessions", java.sql.Timestamp.class)).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='ADMIN_LOGOUT'", Integer.class)).isEqualTo(1);
    }

    @Test
    void authenticatedReadsPreserveCsrfCookieForFollowingBrowserMutation() throws Exception {
        var response = login().getResponse();
        var session = response.getCookie("PAYMENT_ADMIN_SESSION");
        var csrf = response.getCookie("XSRF-TOKEN");
        for (String path : new String[]{"/api/admin/v1/auth/session", "/api/admin/v1/sites"}) {
            var read = mvc.perform(get(path).cookie(session, csrf)).andExpect(status().isOk()).andReturn();
            for (Cookie cookie : read.getResponse().getCookies()) {
                if (cookie.getName().equals("XSRF-TOKEN")) {
                    assertThat(cookie.getMaxAge()).as("authenticated read must not delete browser CSRF cookie").isNotZero();
                    assertThat(cookie.getValue()).isNotBlank();
                    csrf = cookie;
                }
            }
        }
        mvc.perform(delete("/api/admin/v1/auth/session").cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()))
            .andExpect(status().isNoContent());
    }

    @Test
    void anonymousDeleteReturnsUnauthorizedProblemEvenWithoutCsrf() throws Exception {
        mvc.perform(delete("/api/admin/v1/auth/session").header("X-Request-Id", "anonymous-delete"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("urn:payment:problem:unauthorized"))
            .andExpect(jsonPath("$.title").value("Authentication required"))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.code").value("unauthorized"))
            .andExpect(jsonPath("$.request_id").value("anonymous-delete"));
    }

    @Test
    void expiredSessionDeleteReturnsUnauthorizedProblemBeforeCsrfDenial() throws Exception {
        var response = login().getResponse();
        var session = response.getCookie("PAYMENT_ADMIN_SESSION");
        var csrf = response.getCookie("XSRF-TOKEN");
        TestClock.set(INITIAL_TIME.plus(Duration.ofHours(8)));
        mvc.perform(delete("/api/admin/v1/auth/session").cookie(session).header("X-Request-Id", "expired-delete"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("urn:payment:problem:unauthorized"))
            .andExpect(jsonPath("$.title").value("Authentication required"))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.code").value("unauthorized"))
            .andExpect(jsonPath("$.request_id").value("expired-delete"));
        mvc.perform(delete("/api/admin/v1/auth/session").cookie(session, csrf).header("X-XSRF-TOKEN", "invalid"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"));
        mvc.perform(delete("/api/admin/v1/auth/session").cookie(session, csrf).header("X-XSRF-TOKEN", csrf.getValue()))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='ADMIN_LOGOUT'", Integer.class)).isZero();
    }

    @Test
    void expiredRevokedDisabledAndMalformedSessionsRejectAccess() throws Exception {
        var session = login().getResponse().getCookie("PAYMENT_ADMIN_SESSION");
        TestClock.set(INITIAL_TIME.plus(Duration.ofHours(8)));
        mvc.perform(get("/api/admin/v1/auth/session").cookie(session)).andExpect(status().isUnauthorized());
        TestClock.set(INITIAL_TIME);
        jdbc.update("update admin_sessions set revoked_at = created_at");
        mvc.perform(get("/api/admin/v1/auth/session").cookie(session)).andExpect(status().isUnauthorized());
        var other = login().getResponse().getCookie("PAYMENT_ADMIN_SESSION");
        jdbc.update("update admin_users set status='DISABLED'");
        mvc.perform(get("/api/admin/v1/auth/session").cookie(other)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/v1/auth/session").cookie(new Cookie("PAYMENT_ADMIN_SESSION", "bad")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void httpSessionSlidingCannotCrossAbsoluteExpiry() throws Exception {
        var session = login().getResponse().getCookie("PAYMENT_ADMIN_SESSION");
        for (int hours : new int[]{7, 14, 21}) {
            TestClock.set(INITIAL_TIME.plus(Duration.ofHours(hours)));
            mvc.perform(get("/api/admin/v1/auth/session").cookie(session)).andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("select expires_at from admin_sessions", java.sql.Timestamp.class).toInstant())
            .isEqualTo(INITIAL_TIME.plus(Duration.ofHours(24)));
        TestClock.set(INITIAL_TIME.plus(Duration.ofHours(24)));
        mvc.perform(get("/api/admin/v1/auth/session").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void problemsAlwaysIncludeValidatedRequestId() throws Exception {
        mvc.perform(get("/api/admin/v1/auth/session").header("X-Request-Id", "a:b.c-1_2"))
            .andExpect(status().isUnauthorized()).andExpect(header().string("X-Request-Id", "a:b.c-1_2"))
            .andExpect(jsonPath("$.request_id").value("a:b.c-1_2"))
            .andExpect(jsonPath("$.type").exists()).andExpect(jsonPath("$.title").exists())
            .andExpect(jsonPath("$.status").value(401)).andExpect(jsonPath("$.code").value("unauthorized"));
        for (var invalid : new String[]{"", "x".repeat(65), "bad id", "bad\nid"}) {
            var result = mvc.perform(get("/api/admin/v1/auth/session").header("X-Request-Id", invalid))
                .andExpect(status().isUnauthorized()).andReturn();
            assertThatCode(() -> UUID.fromString(result.getResponse().getHeader("X-Request-Id"))).doesNotThrowAnyException();
        }
        var result = mvc.perform(get("/api/admin/v1/auth/session")).andReturn();
        assertThatCode(() -> UUID.fromString(result.getResponse().getHeader("X-Request-Id"))).doesNotThrowAnyException();
    }

    @Test
    void invalidLoginBodiesHaveSafeProblemDetails() throws Exception {
        for (String body : new String[]{"{}", "{\"username\":\"admin\"}", "{", "{\"username\":\"admin\",\"password\":\"\"}"}) {
            mvc.perform(post("/api/admin/v1/auth/sessions").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(jsonPath("$.request_id").exists()).andExpect(jsonPath("$.status").value(400));
        }
        assertThat(jdbc.queryForObject("select count(*) from admin_sessions", Integer.class)).isZero();
    }

    @Test
    void securityRulesExposeHealthAndDocsAndDenyOtherPaths() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/v1/other")).andExpect(status().isUnauthorized());
        var session = login().getResponse().getCookie("PAYMENT_ADMIN_SESSION");
        mvc.perform(get("/unlisted").cookie(session)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("forbidden"));
        mvc.perform(post("/v3/api-docs").cookie(session)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("csrf_invalid"));
        mvc.perform(post("/api/admin/v1/auth/sessions/extra").cookie(session)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("csrf_invalid"));
    }

    @Test
    void bootstrapNeverReplacesExistingAdminOrClearsPassword() {
        var original = jdbc.queryForMap("select * from admin_users");
        new AdminBootstrap(new AdminBootstrapProperties("replacement", "other-password"), users, passwords, clock).initialize();
        new AdminBootstrap(new AdminBootstrapProperties("", ""), users, passwords, clock).initialize();
        assertThat(jdbc.queryForList("select * from admin_users")).containsExactly(original);
        assertThat(((java.sql.Timestamp) original.get("created_at")).toInstant()).isEqualTo(INITIAL_TIME);
    }

    @Test
    void bootstrapRequiresBothNonBlankValuesAndEmptyDatabase() {
        clearDatabase();
        for (var properties : new AdminBootstrapProperties[]{new AdminBootstrapProperties(null, null),
            new AdminBootstrapProperties("admin", ""), new AdminBootstrapProperties("", "password"),
            new AdminBootstrapProperties(" ", "password")}) {
            new AdminBootstrap(properties, users, passwords, clock).initialize();
        }
        assertThat(jdbc.queryForObject("select count(*) from admin_users", Integer.class)).isZero();
        bootstrap.initialize();
        assertThat(jdbc.queryForObject("select count(*) from admin_users", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentBootstrapCreatesOnlyOneInitialAdmin() throws Exception {
        clearDatabase();
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var racingUsers = (AdminUserPort) java.lang.reflect.Proxy.newProxyInstance(
            AdminUserPort.class.getClassLoader(), new Class<?>[]{AdminUserPort.class}, (proxy, method, arguments) -> {
                var result = method.invoke(users, arguments);
                if (method.getName().equals("anyUserExists")) barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return result;
            });
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new AdminBootstrap(new AdminBootstrapProperties("first", "password-one"),
                racingUsers, passwords, clock).initialize());
            var second = executor.submit(() -> new AdminBootstrap(new AdminBootstrapProperties("second", "password-two"),
                racingUsers, passwords, clock).initialize());
            first.get(15, java.util.concurrent.TimeUnit.SECONDS);
            second.get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select count(*) from admin_users", Integer.class)).isEqualTo(1);
    }

    private MvcResult login() throws Exception {
        return mvc.perform(post("/api/admin/v1/auth/sessions").header("X-Request-Id", "successful-login")
            .contentType("application/json").content("""
                {"username":"admin","password":"local-password"}
                """)).andExpect(status().isNoContent()).andReturn();
    }
}
