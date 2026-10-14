package dev.genericpayments.platform.payments.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.domain.Customer;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.partners.PartnerTestFixture;
import dev.genericpayments.platform.partners.domain.PartnerScope;
import dev.genericpayments.platform.payments.application.service.IdempotencyService;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.AdminApiClient;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

class PartnerCustomerPaymentIntegrationTest extends PartnerTestFixture {
    private static final String PATH = "/api/v1/partner/payments";

    @Autowired WebApplicationContext context;
    @Autowired CustomerStore customers;
    @Autowired IdempotencyService idempotency;
    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;
    private String credential;
    private Customer customer;

    @BeforeEach
    void customerFixture() {
        mvc = new AdminApiClient(context).mvc;
        credential = key(PartnerScope.PAYMENTS_CREATE, PartnerScope.PAYMENTS_READ);
        customer = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
    }

    @Test
    void optionalActiveSameSiteCustomerIsPersistedAndReturnedWithoutExpandingTheResponse() throws Exception {
        String linked = create("linked", body("linked-order", customer.id().value()))
            .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.customer_id").value(customer.id().value().toString()))
            .andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(JsonPath.read(linked, "$.payment_id"));
        assertThat(jdbc.queryForObject("select customer_id from payments where id=?", UUID.class, paymentId))
            .isEqualTo(customer.id().value());
        assertThat(fieldNames(linked)).containsExactlyInAnyOrder("payment_id", "customer_id", "external_order_id",
            "amount_minor", "currency", "status", "handoff_token", "handoff_expires_at");

        String read = mvc.perform(get(PATH + "/" + paymentId).header("Authorization", "Bearer " + credential))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.customer_id").value(customer.id().value().toString()))
            .andReturn().getResponse().getContentAsString();
        assertThat(fieldNames(read)).containsExactlyInAnyOrder("payment_id", "customer_id", "external_order_id",
            "amount_minor", "currency", "status");

        create("guest", body("guest-order", null)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.customer_id").value(org.hamcrest.Matchers.nullValue()));
        assertThat(jdbc.queryForObject("select count(*) from payments where customer_id is null", Integer.class)).isOne();
    }

    @Test
    void unavailableCustomerVariantsAreUniformAndCauseNoPaymentOrIdempotencySideEffects() throws Exception {
        assertUnavailable("unknown", UUID.randomUUID());

        Customer anonymized = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        jdbc.update("update customers set status='ANONYMIZED',anonymized_at=?,updated_at=? where id=?",
            Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME), anonymized.id().value());
        assertUnavailable("anonymized", anonymized.id().value());

        SiteId otherSite = new SiteId(UUID.randomUUID());
        jdbc.update("""
            insert into sites(id,site_key,display_name,status,payment_account_id,payment_method_policy,created_at,updated_at,version)
            values (?,'customer-other-site','Other','ACTIVE',?,'AUTOMATIC',?,?,0)
            """, otherSite.value(), account.id().value(), Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME));
        Customer foreign = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), otherSite, null, null, INITIAL_TIME));
        assertUnavailable("cross-site", foreign.id().value());
    }

    @Test
    void customerOwnershipParticipatesInDynamicPaymentIdempotencyIdentity() throws Exception {
        create("guest-to-customer", body("guest-owned-order", null)).andExpect(status().isCreated());
        create("guest-to-customer", body("guest-owned-order", customer.id().value()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        Customer second = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        String first = create("owned", body("owned-order", customer.id().value()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        create("owned", body("owned-order", customer.id().value())).andExpect(status().isCreated())
            .andExpect(jsonPath("$.payment_id").value((String)JsonPath.read(first, "$.payment_id")));
        create("owned", body("owned-order", second.id().value()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(count("payments")).isEqualTo(2);
        assertThat(count("idempotency_records")).isEqualTo(2);
    }

    @Test
    void completedDynamicReplayRevalidatesThePersistedCurrentOwnerIncludingLegacyGuestRecords() throws Exception {
        String legacy = create("legacy-guest", body("legacy-order", null)).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID legacyPayment = UUID.fromString(JsonPath.read(legacy, "$.payment_id"));
        String legacyToken = JsonPath.read(legacy, "$.handoff_token");
        jdbc.update("update idempotency_records set fingerprint=? where idempotency_key='legacy-guest'",
            legacyGuestFingerprint("legacy-order"));
        create("legacy-guest", body("legacy-order", null)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.payment_id").value(legacyPayment.toString()))
            .andExpect(jsonPath("$.handoff_token").value(legacyToken));

        int providerOperations = count("fake_provider_operations");
        jdbc.update("update payments set customer_id=? where id=?", customer.id().value(), legacyPayment);
        assertOwnershipConflict(create("legacy-guest", body("legacy-order", null)), providerOperations);

        Customer second = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        UUID ownerChanged = paymentId(create("owner-changed", body("changed-order", customer.id().value()))
            .andExpect(status().isCreated()));
        jdbc.update("update payments set customer_id=? where id=?", second.id().value(), ownerChanged);
        assertOwnershipConflict(create("owner-changed", body("changed-order", customer.id().value())),
            count("fake_provider_operations"));

        UUID ownerCleared = paymentId(create("owner-cleared", body("cleared-order", customer.id().value()))
            .andExpect(status().isCreated()));
        jdbc.update("update payments set customer_id=null where id=?", ownerCleared);
        assertOwnershipConflict(create("owner-cleared", body("cleared-order", customer.id().value())),
            count("fake_provider_operations"));
        assertThat(count("payments")).isEqualTo(3);
    }

    private void assertUnavailable(String key, UUID customerId) throws Exception {
        int paymentCount = count("payments");
        int idempotencyCount = count("idempotency_records");
        create(key, body(key + "-order", customerId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_AVAILABLE"))
            .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(count("payments")).isEqualTo(paymentCount);
        assertThat(count("idempotency_records")).isEqualTo(idempotencyCount);
    }

    private ResultActions create(String idempotencyKey, String body) throws Exception {
        return mvc.perform(post(PATH).header("Authorization", "Bearer " + credential)
            .header("Idempotency-Key", idempotencyKey).header("X-Request-Id", "task-9-partner")
            .contentType("application/json").content(body));
    }

    private static String body(String order, UUID customerId) {
        return "{\"external_order_id\":\"" + order + "\",\"amount_minor\":2300,\"currency\":\"EUR\"," +
            "\"checkout_origin\":\"https://shop.test\",\"return_url\":\"https://shop.test/done\"," +
            "\"metadata\":{\"channel\":\"partner\"}" +
            (customerId == null ? "" : ",\"customer_id\":\"" + customerId + "\"") + "}";
    }

    private String legacyGuestFingerprint(String order) {
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("external_order_id", order);
        business.put("amount_minor", 2300L);
        business.put("currency", "EUR");
        business.put("checkout_origin", "https://shop.test");
        business.put("return_url_present", true);
        business.put("return_url", "https://shop.test/done");
        business.put("metadata", Map.of("channel", "partner"));
        return idempotency.fingerprint(business);
    }

    private void assertOwnershipConflict(ResultActions response, int providerOperations) throws Exception {
        response.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
            .andExpect(jsonPath("$.handoff_token").doesNotExist());
        assertThat(count("fake_provider_operations")).isEqualTo(providerOperations);
    }

    private static UUID paymentId(ResultActions response) throws Exception {
        return UUID.fromString(JsonPath.read(response.andReturn().getResponse().getContentAsString(), "$.payment_id"));
    }

    private Set<String> fieldNames(String body) throws Exception {
        Set<String> names = new java.util.LinkedHashSet<>();
        names.addAll(json.readTree(body).propertyNames());
        return names;
    }
}
