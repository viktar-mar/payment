package dev.genericpayments.platform.payments.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
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
import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.sites.adapter.out.persistence.SitePersistenceAdapter;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.Site;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.sites.domain.SiteKey;
import dev.genericpayments.platform.support.AdminApiClient;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

@Import(PublicRateLimitRoutePolicyIntegrationTest.RecordingSiteConfiguration.class)
@TestPropertySource(properties = {
    "payment.public-api.rate-limit.create-per-minute=2",
    "payment.public-api.rate-limit.confirm-per-minute=2",
    "payment.public-api.rate-limit.read-per-minute=2"
})
class PublicRateLimitRoutePolicyIntegrationTest extends CheckoutTestFixture {
    private static final String CONTEXT = "/edge";
    private static final String ORIGIN = "https://shop.test";
    private static final String CREATE = "/api/v1/public/sites/test-site/checkouts";

    @Autowired WebApplicationContext context;
    @Autowired CustomerStore customers;
    @Autowired CustomerSessionService sessions;
    @Autowired RecordingLoadSitePort siteLoads;
    private MockMvc mvc;

    @BeforeEach
    void http() {
        mvc = new AdminApiClient(context).mvc;
    }

    @Test
    void canonicalContextPathRetainsCorsPreflightAndExactlyOneCreateAdmissionPerRequest() throws Exception {
        var customer = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        var session = sessions.issue(site, customer.id(), ORIGIN);
        var ip = "context-policy-" + UUID.randomUUID();

        mvc.perform(withIp(options(CONTEXT + CREATE).contextPath(CONTEXT)
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"), ip))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
            .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));

        String guest = mvc.perform(create(CONTEXT + CREATE, CONTEXT, "context-guest", null, ip))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
            .andReturn().getResponse().getContentAsString();
        mvc.perform(create(CONTEXT + CREATE, CONTEXT, "context-customer", bearer(session), ip))
            .andExpect(status().isCreated())
            .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
        mvc.perform(create(CONTEXT + CREATE, CONTEXT, "context-denied", null, ip))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "30"))
            .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));

        String checkoutId = JsonPath.read(guest, "$.checkout_id");
        String checkoutToken = JsonPath.read(guest, "$.checkout_token");
        String checkout = "/api/v1/public/checkouts/" + checkoutId;
        mvc.perform(withIp(options(CONTEXT + checkout).contextPath(CONTEXT)
                .header("Origin", ORIGIN).header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization"), ip))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
        mvc.perform(withIp(get(CONTEXT + checkout).contextPath(CONTEXT)
                .header("Origin", ORIGIN).header("Authorization", "Bearer " + checkoutToken), ip))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));

        mvc.perform(withIp(options(CONTEXT + checkout + "/confirm").contextPath(CONTEXT)
                .header("Origin", ORIGIN).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"), ip))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
        mvc.perform(withIp(post(CONTEXT + checkout + "/confirm").contextPath(CONTEXT)
                .header("Origin", ORIGIN).header("Authorization", "Bearer " + checkoutToken)
                .header("Idempotency-Key", "context-confirm").contentType("application/json")
                .content("{\"payment_method_family\":\"CARD\"}"), ip))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));

        mvc.perform(withIp(options(CONTEXT + "/api/v1/public/handoffs").contextPath(CONTEXT)
                .header("Origin", ORIGIN).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"), ip))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));

        assertThat(count("payments")).isEqualTo(2);
        assertThat(count("idempotency_records")).isEqualTo(3);
        assertThat(count("fake_provider_operations")).isEqualTo(3);
        assertThat(jdbc.queryForObject("select customer_id from payments where customer_id is not null", UUID.class))
            .isEqualTo(customer.id().value());
    }

    @Test
    void mixedCaseSiteKeyRemainsOpaqueUnderContextPathAndConsumesOneCreateAdmission() throws Exception {
        var ip = "mixed-site-policy-" + UUID.randomUUID();
        var mixedCaseCreate = "/api/v1/public/sites/TeSt-SiTe/checkouts";
        var upperCaseCreate = "/api/v1/public/sites/TEST-SITE/checkouts";

        mvc.perform(withIp(options(CONTEXT + mixedCaseCreate).contextPath(CONTEXT)
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,idempotency-key"), ip))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));

        mvc.perform(create(CONTEXT + mixedCaseCreate, CONTEXT, "mixed-site", null, ip))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
        mvc.perform(create(upperCaseCreate, "", "upper-site", null, ip))
            .andExpect(status().isCreated())
            .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
        mvc.perform(create(CONTEXT + CREATE, CONTEXT, "mixed-site-denied", null, ip))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "30"))
            .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));

        assertThat(count("payments")).isEqualTo(2);
        assertThat(count("idempotency_records")).isEqualTo(2);
        assertThat(count("fake_provider_operations")).isEqualTo(2);
    }

    @Test
    void uppercaseCheckoutUuidRemainsOpaqueForReadAndConfirmAdmissions() throws Exception {
        var ip = "uppercase-checkout-policy-" + UUID.randomUUID();
        String created = mvc.perform(create(CREATE, "", "uppercase-checkout", null, ip))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String checkoutId = "abcdef01-2345-4678-9abc-def012345678";
        jdbc.update("update checkout_sessions set id=? where id=?", UUID.fromString(checkoutId),
            UUID.fromString(JsonPath.read(created, "$.checkout_id")));
        String checkoutToken = JsonPath.read(created, "$.checkout_token");
        String uppercaseCheckout = "/api/v1/public/checkouts/"
            + checkoutId.toUpperCase(java.util.Locale.ROOT);

        for (int admitted = 0; admitted < 2; admitted++) {
            mvc.perform(withIp(get(CONTEXT + uppercaseCheckout).contextPath(CONTEXT)
                    .header("Origin", ORIGIN).header("Authorization", "Bearer " + checkoutToken), ip))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
        }
        mvc.perform(withIp(get(CONTEXT + uppercaseCheckout).contextPath(CONTEXT)
                .header("Origin", ORIGIN).header("Authorization", "Bearer " + checkoutToken), ip))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));

        for (int admitted = 0; admitted < 2; admitted++) {
            mvc.perform(withIp(post(CONTEXT + uppercaseCheckout + "/confirm").contextPath(CONTEXT)
                    .header("Origin", ORIGIN).header("Authorization", "Bearer " + checkoutToken)
                    .header("Idempotency-Key", "uppercase-confirm").contentType("application/json")
                    .content("{\"payment_method_family\":\"CARD\"}"), ip))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
        }
        mvc.perform(withIp(post(CONTEXT + uppercaseCheckout + "/confirm").contextPath(CONTEXT)
                .header("Origin", ORIGIN).header("Authorization", "Bearer " + checkoutToken)
                .header("Idempotency-Key", "uppercase-confirm").contentType("application/json")
                .content("{\"payment_method_family\":\"CARD\"}"), ip))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));

        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("payment_attempts")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(2);
        assertThat(count("fake_provider_operations")).isEqualTo(2);
    }

    @Test
    void fixedRouteLiteralCaseAliasesRemainRejectedBeforeSiteLimiterControllerOrPersistenceWork() throws Exception {
        var ip = "fixed-case-policy-" + UUID.randomUUID();
        String created = mvc.perform(create(CREATE, "", "fixed-case-checkout", null, ip))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String checkoutId = "abcdef01-2345-4678-9abc-def012345678";
        jdbc.update("update checkout_sessions set id=? where id=?", UUID.fromString(checkoutId),
            UUID.fromString(JsonPath.read(created, "$.checkout_id")));
        String checkoutToken = JsonPath.read(created, "$.checkout_token");
        String uppercaseId = checkoutId.toUpperCase(java.util.Locale.ROOT);
        long payments = count("payments");
        long attempts = count("payment_attempts");
        long idempotency = count("idempotency_records");
        long providerOperations = count("fake_provider_operations");
        siteLoads.reset();

        rejectFixedAlias(get("/API/v1/public/checkouts/" + uppercaseId)
            .header("Origin", ORIGIN).header("Authorization", "Bearer " + checkoutToken), ip,
            payments, attempts, idempotency, providerOperations);
        rejectFixedAlias(get("/api/v1/public/Checkouts/" + uppercaseId)
            .header("Origin", ORIGIN).header("Authorization", "Bearer " + checkoutToken), ip,
            payments, attempts, idempotency, providerOperations);
        rejectFixedAlias(post("/api/v1/public/checkouts/" + uppercaseId + "/Confirm")
            .header("Origin", ORIGIN).header("Authorization", "Bearer " + checkoutToken)
            .header("Idempotency-Key", "fixed-confirm").contentType("application/json")
            .content("{\"payment_method_family\":\"CARD\"}"), ip,
            payments, attempts, idempotency, providerOperations);

        for (int admitted = 0; admitted < 2; admitted++) {
            mvc.perform(withIp(get("/api/v1/public/checkouts/" + uppercaseId)
                    .header("Origin", ORIGIN).header("Authorization", "Bearer " + checkoutToken), ip))
                .andExpect(status().isOk());
        }
        mvc.perform(withIp(get("/api/v1/public/checkouts/" + uppercaseId)
                .header("Origin", ORIGIN).header("Authorization", "Bearer " + checkoutToken), ip))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void mvcEquivalentAliasesAreUniformlyRejectedBeforeSiteLimiterControllerOrPersistenceWork() throws Exception {
        var customer = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        CustomerSessionCreation session = sessions.issue(site, customer.id(), ORIGIN);
        var ip = "alias-policy-" + UUID.randomUUID();
        siteLoads.reset();

        rejectAlias("/api/v1/public/sites/test-site/%63heckouts", null, "encoded-guest", ip, false);
        rejectAlias("/api/v1/public/sites/test-site/%63heckouts", bearer(session), "encoded-customer", ip, false);
        rejectAlias("/api/v1/public/sites/test-site/checkouts;shadow=1", null, "matrix", ip, true);
        rejectAlias("/api/v1/public/sites/test-site/./checkouts", null, "dot", ip, true);
        rejectAlias("/api/v1/public/sites/test-site/Checkouts", null, "case", ip, false);
        rejectAlias("/api/v1/public/sites/test-site/%2563heckouts", null, "repeated", ip, true);

        assertThat(siteLoads.totalCalls()).isZero();
        assertThat(count("payments")).isZero();
        assertThat(count("idempotency_records")).isZero();
        assertThat(count("fake_provider_operations")).isZero();

        mvc.perform(create(CREATE, "", "canonical-guest", null, ip)).andExpect(status().isCreated());
        mvc.perform(create(CREATE, "", "canonical-customer", bearer(session), ip)).andExpect(status().isCreated());
        mvc.perform(create(CREATE, "", "canonical-denied", null, ip))
            .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
        assertThat(count("payments")).isEqualTo(2);
        assertThat(count("idempotency_records")).isEqualTo(2);
        assertThat(count("fake_provider_operations")).isEqualTo(2);
    }

    private void rejectAlias(String path, String authorization, String key, String ip, boolean containerMayReject)
            throws Exception {
        var request = post(URI.create(path)).header("Origin", ORIGIN).header("Idempotency-Key", key)
            .contentType("application/json").content("{\"price_key\":\"lifetime\"}");
        if (authorization != null) request.header("Authorization", authorization);
        var response = mvc.perform(withIp(request, ip)).andReturn().getResponse();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).as(path).isNull();
        if (containerMayReject && response.getStatus() == 400) {
            assertThat(response.getContentAsString()).as(path).isEmpty();
        } else {
            assertThat(response.getStatus()).as(path).isEqualTo(401);
            assertThat(response.getHeader("Cache-Control")).as(path).contains("no-store");
            assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code"))
                .as(path).isEqualTo("AUTHENTICATION_FAILED");
        }
        assertThat(siteLoads.totalCalls()).as(path).isZero();
        assertThat(count("payments")).as(path).isZero();
        assertThat(count("idempotency_records")).as(path).isZero();
        assertThat(count("fake_provider_operations")).as(path).isZero();
    }

    private void rejectFixedAlias(MockHttpServletRequestBuilder request, String ip, long payments, long attempts,
            long idempotency, long providerOperations) throws Exception {
        var response = mvc.perform(withIp(request, ip)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(JsonPath.<String>read(response.getContentAsString(), "$.code"))
            .isEqualTo("AUTHENTICATION_FAILED");
        assertThat(siteLoads.totalCalls()).isZero();
        assertThat(count("payments")).isEqualTo(payments);
        assertThat(count("payment_attempts")).isEqualTo(attempts);
        assertThat(count("idempotency_records")).isEqualTo(idempotency);
        assertThat(count("fake_provider_operations")).isEqualTo(providerOperations);
    }

    private MockHttpServletRequestBuilder create(String path, String contextPath, String key, String authorization, String ip) {
        var request = post(path).header("Origin", ORIGIN).header("Idempotency-Key", key)
            .contentType("application/json").content("{\"price_key\":\"lifetime\"}");
        if (!contextPath.isEmpty()) request.contextPath(contextPath);
        if (authorization != null) request.header("Authorization", authorization);
        return withIp(request, ip);
    }

    private static MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder request, String ip) {
        return request.with(value -> {
            value.setRemoteAddr(ip);
            return value;
        });
    }

    private static String bearer(CustomerSessionCreation value) {
        return "Bearer " + value.rawToken();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingSiteConfiguration {
        @Bean
        @Primary
        RecordingLoadSitePort publicCheckoutRouteSiteLoader(SitePersistenceAdapter delegate) {
            return new RecordingLoadSitePort(delegate);
        }
    }

    static final class RecordingLoadSitePort implements LoadSitePort {
        private final LoadSitePort delegate;
        private final AtomicInteger calls = new AtomicInteger();

        RecordingLoadSitePort(LoadSitePort delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<Site> findById(SiteId id) {
            calls.incrementAndGet();
            return delegate.findById(id);
        }

        @Override
        public Optional<Site> findByKey(SiteKey key) {
            calls.incrementAndGet();
            return delegate.findByKey(key);
        }

        @Override
        public boolean existsByKey(SiteKey key) {
            calls.incrementAndGet();
            return delegate.existsByKey(key);
        }

        @Override
        public List<Site> findAll() {
            calls.incrementAndGet();
            return delegate.findAll();
        }

        void reset() {
            calls.set(0);
        }

        int totalCalls() {
            return calls.get();
        }
    }
}
