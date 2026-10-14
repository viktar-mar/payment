package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.genericpayments.platform.customers.application.model.CustomerSessionCreation;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.application.service.CustomerSessionService;
import dev.genericpayments.platform.customers.domain.Customer;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.AdminApiClient;
import dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

@ExtendWith(OutputCaptureExtension.class)
class CustomerPaymentLinkageIntegrationTest extends CheckoutTestFixture {
    private static final String ORIGIN = "https://shop.test";
    private static final String CREATE = "/api/v1/public/sites/test-site/checkouts";

    @Autowired WebApplicationContext context;
    @Autowired CustomerStore customers;
    @Autowired CustomerSessionService sessions;
    @Autowired IdempotencyService idempotency;
    private MockMvc mvc;
    private Customer customer;
    private CustomerSessionCreation session;

    @BeforeEach
    void customerFixture() {
        mvc = new AdminApiClient(context).mvc;
        customer = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        session = sessions.issue(site, customer.id(), ORIGIN);
    }

    @Test
    void absentAuthorizationRemainsGuestWhileValidSdkSessionLinksOnlyTheCustomerId(CapturedOutput output) throws Exception {
        create("guest-payment", null, ORIGIN, CREATE).andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"));
        UUID guestPayment = jdbc.queryForObject(
            "select id from payments where customer_id is null", UUID.class);

        String response = create("customer-payment", bearer(session), ORIGIN, CREATE)
            .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsString();
        UUID linkedPayment = UUID.fromString(JsonPath.read(response, "$.payment_id"));

        assertThat(guestPayment).isNotNull();
        assertThat(jdbc.queryForObject("select customer_id from payments where id=?", UUID.class, linkedPayment))
            .isEqualTo(customer.id().value());
        String persisted = jdbc.queryForList("select * from payments").toString()
            + jdbc.queryForList("select * from idempotency_records");
        assertThat(persisted).doesNotContain(session.rawToken());
        assertThat(output.getAll()).doesNotContain(session.rawToken());
    }

    @Test
    void suppliedInvalidSessionVariantsFailUniformlyBeforePaymentOrIdempotencyWrites() throws Exception {
        assertAuthenticationFailure("malformed", "Basic private", ORIGIN, CREATE);
        assertAuthenticationFailure("unknown", "Bearer " + "A".repeat(43), ORIGIN, CREATE);
        assertAuthenticationFailure("malformed-origin", bearer(session), "%%%", CREATE);

        int missingOriginPayments = count("payments");
        int missingOriginIdempotency = count("idempotency_records");
        mvc.perform(post(CREATE).header("Authorization", bearer(session)).header("Idempotency-Key", "missing-origin")
                .contentType("application/json").content("{\"price_key\":\"lifetime\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        assertThat(count("payments")).isEqualTo(missingOriginPayments);
        assertThat(count("idempotency_records")).isEqualTo(missingOriginIdempotency);

        int paymentsBefore = count("payments");
        int idempotencyBefore = count("idempotency_records");
        mvc.perform(post(CREATE).header("Origin", ORIGIN).header("Authorization", bearer(session), bearer(session))
                .header("Idempotency-Key", "duplicate").contentType("application/json")
                .content("{\"price_key\":\"lifetime\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        assertThat(count("payments")).isEqualTo(paymentsBefore);
        assertThat(count("idempotency_records")).isEqualTo(idempotencyBefore);

        CustomerSessionCreation expired = sessions.issue(site, customer.id(), ORIGIN);
        TestClock.set(INITIAL_TIME.plus(Duration.ofMinutes(31)));
        assertAuthenticationFailure("expired", bearer(expired), ORIGIN, CREATE);
        TestClock.set(INITIAL_TIME);

        CustomerSessionCreation revoked = sessions.issue(site, customer.id(), ORIGIN);
        jdbc.update("update customer_sessions set revoked_at=?,revoked_reason='LOGOUT' where id=?",
            Timestamp.from(INITIAL_TIME), revoked.sessionId().value());
        assertAuthenticationFailure("revoked", bearer(revoked), ORIGIN, CREATE);

        CustomerSessionCreation insufficient = sessions.issue(site, customer.id(), ORIGIN);
        jdbc.update("update customer_sessions set scopes='[\"CUSTOMER_READ\"]' where id=?",
            insufficient.sessionId().value());
        assertAuthenticationFailure("scope", bearer(insufficient), ORIGIN, CREATE);

        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,'https://alternate.test')", site.value());
        assertAuthenticationFailure("origin-binding", bearer(session), "https://alternate.test", CREATE);

        SiteId otherSite = otherSite();
        assertAuthenticationFailure("site-binding", bearer(session), ORIGIN,
            "/api/v1/public/sites/other-site/checkouts");
        assertAuthenticationFailure("unknown-site", bearer(session), ORIGIN,
            "/api/v1/public/sites/not-a-site/checkouts");
        jdbc.update("update sites set status='DISABLED' where id=?", otherSite.value());
        assertAuthenticationFailure("disabled-site", bearer(session), ORIGIN,
            "/api/v1/public/sites/other-site/checkouts");

        CustomerSessionCreation anonymized = sessions.issue(site, customer.id(), ORIGIN);
        jdbc.update("update customers set status='ANONYMIZED',anonymized_at=?,updated_at=? where id=?",
            Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME), customer.id().value());
        assertAuthenticationFailure("anonymized", bearer(anonymized), ORIGIN, CREATE);
        assertThat(otherSite).isNotEqualTo(site);
    }

    @Test
    void customerOwnershipParticipatesInCatalogIdempotencyIdentity() throws Exception {
        create("guest-to-customer", null, ORIGIN, CREATE).andExpect(status().isCreated());
        create("guest-to-customer", bearer(session), ORIGIN, CREATE)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        Customer second = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        CustomerSessionCreation secondSession = sessions.issue(site, second.id(), ORIGIN);
        String first = create("customer-owner", bearer(session), ORIGIN, CREATE)
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        create("customer-owner", bearer(session), ORIGIN, CREATE)
            .andExpect(status().isCreated()).andExpect(jsonPath("$.payment_id").value((String)JsonPath.read(first, "$.payment_id")));
        create("customer-owner", bearer(secondSession), ORIGIN, CREATE)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(count("payments")).isEqualTo(2);
        assertThat(count("idempotency_records")).isEqualTo(2);
    }

    @Test
    void completedCatalogReplayRevalidatesThePersistedCurrentOwnerIncludingLegacyGuestRecords() throws Exception {
        String legacy = create("legacy-guest", null, ORIGIN, CREATE).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID legacyPayment = UUID.fromString(JsonPath.read(legacy, "$.payment_id"));
        String legacyToken = JsonPath.read(legacy, "$.checkout_token");
        String legacyFingerprint = idempotency.fingerprint(Map.of(
            "site_id", site.value(), "price_key", "lifetime", "origin", ORIGIN));
        jdbc.update("update idempotency_records set fingerprint=? where idempotency_key='legacy-guest'", legacyFingerprint);
        create("legacy-guest", null, ORIGIN, CREATE).andExpect(status().isCreated())
            .andExpect(jsonPath("$.payment_id").value(legacyPayment.toString()))
            .andExpect(jsonPath("$.checkout_token").value(legacyToken));

        int providerOperations = count("fake_provider_operations");
        jdbc.update("update payments set customer_id=? where id=?", customer.id().value(), legacyPayment);
        assertOwnershipConflict(create("legacy-guest", null, ORIGIN, CREATE), providerOperations);

        Customer second = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        UUID ownerChanged = paymentId(create("owner-changed", bearer(session), ORIGIN, CREATE).andExpect(status().isCreated()));
        jdbc.update("update payments set customer_id=? where id=?", second.id().value(), ownerChanged);
        assertOwnershipConflict(create("owner-changed", bearer(session), ORIGIN, CREATE), count("fake_provider_operations"));

        UUID ownerCleared = paymentId(create("owner-cleared", bearer(session), ORIGIN, CREATE).andExpect(status().isCreated()));
        jdbc.update("update payments set customer_id=null where id=?", ownerCleared);
        assertOwnershipConflict(create("owner-cleared", bearer(session), ORIGIN, CREATE), count("fake_provider_operations"));
        assertThat(count("payments")).isEqualTo(3);
    }

    @Test
    void suppliedAuthorizationIsFullyRejectedBeforeMvcArgumentBindingForExactHandlerAndAliases() throws Exception {
        CustomerSessionCreation expired = sessions.issue(site, customer.id(), ORIGIN);
        TestClock.set(INITIAL_TIME.plus(Duration.ofMinutes(31)));
        expectEarlyAuthenticationFailure(post(CREATE).header("Origin", ORIGIN).header("Authorization", bearer(expired))
            .contentType("application/json").content("{}"));
        TestClock.set(INITIAL_TIME);

        CustomerSessionCreation revoked = sessions.issue(site, customer.id(), ORIGIN);
        jdbc.update("update customer_sessions set revoked_at=?,revoked_reason='LOGOUT' where id=?",
            Timestamp.from(INITIAL_TIME), revoked.sessionId().value());
        expectEarlyAuthenticationFailure(post(CREATE).header("Origin", ORIGIN).header("Authorization", bearer(revoked))
            .header("Idempotency-Key", "one", "two").contentType("application/json").content("{"));

        CustomerSessionCreation insufficient = sessions.issue(site, customer.id(), ORIGIN);
        jdbc.update("update customer_sessions set scopes='[\"CUSTOMER_READ\"]' where id=?", insufficient.sessionId().value());
        expectEarlyAuthenticationFailure(post(CREATE).header("Origin", ORIGIN).header("Authorization", bearer(insufficient))
            .header("Idempotency-Key", "scope").contentType("application/json").content("{}"));

        expectEarlyAuthenticationFailure(post(CREATE).header("Origin", ORIGIN).header("Authorization", "Basic private")
            .header("Idempotency-Key", "malformed").contentType("application/json").content("{"));
        expectEarlyAuthenticationFailure(post(CREATE).header("Origin", ORIGIN)
            .header("Authorization", bearer(session), bearer(session)).contentType("application/json").content("{}"));

        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,'https://alternate.test')", site.value());
        expectEarlyAuthenticationFailure(post(CREATE).header("Origin", "https://alternate.test")
            .header("Authorization", bearer(session)).contentType("application/json").content("{"));

        otherSite();
        expectEarlyAuthenticationFailure(post("/api/v1/public/sites/other-site/checkouts").header("Origin", ORIGIN)
            .header("Authorization", bearer(session)).header("Idempotency-Key", "wrong-site")
            .contentType("application/json").content("{}"));

        expectEarlyAuthenticationFailure(post("/edge" + CREATE).contextPath("/edge").header("Origin", ORIGIN)
            .header("Authorization", "Basic private").header("Idempotency-Key", "context")
            .contentType("application/json").content("{"));
        expectEarlyAuthenticationFailure(post(URI.create("/api/v1/public/sites/test-site/%63heckouts"))
            .header("Origin", ORIGIN).header("Authorization", "Basic private").header("Idempotency-Key", "encoded")
            .contentType("application/json").content("{"));

        Customer anonymized = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        CustomerSessionCreation anonymizedSession = sessions.issue(site, anonymized.id(), ORIGIN);
        jdbc.update("update customers set status='ANONYMIZED',anonymized_at=?,updated_at=? where id=?",
            Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME), anonymized.id().value());
        expectEarlyAuthenticationFailure(post(CREATE).header("Origin", ORIGIN).header("Authorization", bearer(anonymizedSession))
            .header("Idempotency-Key", "anonymized").contentType("application/json").content("{"));
        assertThat(count("payments")).isZero();
        assertThat(count("idempotency_records")).isZero();
    }

    @Test
    void missingCheckoutScopeDoesNotTouchTheSessionOrCreatePaymentState() throws Exception {
        jdbc.update("update customer_sessions set scopes='[\"CUSTOMER_READ\"]' where id=?", session.sessionId().value());
        Map<String, Object> before = jdbc.queryForMap(
            "select last_used_at,version from customer_sessions where id=?", session.sessionId().value());
        TestClock.set(INITIAL_TIME.plusSeconds(60));
        try {
            create("scope-no-touch", bearer(session), ORIGIN, CREATE)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        } finally {
            TestClock.set(INITIAL_TIME);
        }
        assertThat(jdbc.queryForMap("select last_used_at,version from customer_sessions where id=?", session.sessionId().value()))
            .isEqualTo(before);
        assertThat(count("payments")).isZero();
        assertThat(count("idempotency_records")).isZero();
    }

    private void assertAuthenticationFailure(String key, String authorization, String origin, String path) throws Exception {
        int paymentsBefore = count("payments");
        int idempotencyBefore = count("idempotency_records");
        create(key, authorization, origin, path)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
            .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(count("payments")).isEqualTo(paymentsBefore);
        assertThat(count("idempotency_records")).isEqualTo(idempotencyBefore);
    }

    private ResultActions create(String key, String authorization, String origin, String path) throws Exception {
        var request = post(path).header("Origin", origin).header("Idempotency-Key", key)
            .header("X-Request-Id", "task-9-linkage").contentType("application/json")
            .content("{\"price_key\":\"lifetime\"}");
        if (authorization != null) request.header("Authorization", authorization);
        return mvc.perform(request);
    }

    private void expectEarlyAuthenticationFailure(MockHttpServletRequestBuilder request) throws Exception {
        int paymentsBefore = count("payments");
        int idempotencyBefore = count("idempotency_records");
        mvc.perform(request.with(value -> {
            value.setRemoteAddr("task-9-fix-" + UUID.randomUUID());
            return value;
        })).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
            .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(count("payments")).isEqualTo(paymentsBefore);
        assertThat(count("idempotency_records")).isEqualTo(idempotencyBefore);
    }

    private void assertOwnershipConflict(ResultActions response, int providerOperations) throws Exception {
        response.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
            .andExpect(jsonPath("$.checkout_token").doesNotExist());
        assertThat(count("fake_provider_operations")).isEqualTo(providerOperations);
    }

    private static UUID paymentId(ResultActions response) throws Exception {
        return UUID.fromString(JsonPath.read(response.andReturn().getResponse().getContentAsString(), "$.payment_id"));
    }

    private static String bearer(CustomerSessionCreation value) {
        return "Bearer " + value.rawToken();
    }

    private SiteId otherSite() {
        SiteId other = new SiteId(UUID.randomUUID());
        jdbc.update("""
            insert into sites(id,site_key,display_name,status,payment_account_id,payment_method_policy,created_at,updated_at,version)
            values (?,'other-site','Other','ACTIVE',?,'AUTOMATIC',?,?,0)
            """, other.value(), account.id().value(), Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,'https://shop.test')", other.value());
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'SITE_TOKEN')", other.value());
        return other;
    }
}
