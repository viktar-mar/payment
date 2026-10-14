package dev.genericpayments.platform.payments.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.customers.application.model.CustomerSessionCreation;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.application.service.CustomerSessionService;
import dev.genericpayments.platform.customers.domain.Customer;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.payments.application.port.out.CustomerPaymentHistoryCursorPort;
import dev.genericpayments.platform.payments.application.port.out.CustomerPaymentHistoryCursorPort.Position;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.payments.domain.PaymentSource;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.payments.domain.PriceSnapshot;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.AdminApiClient;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CustomerPaymentHistoryIntegrationTest extends CheckoutTestFixture {
    private static final String ORIGIN = "https://shop.test";
    private static final String PAYMENTS = "/api/v1/customer/payments";
    private static final String DONATIONS = "/api/v1/customer/donations";

    @Autowired WebApplicationContext context;
    @Autowired CustomerStore customers;
    @Autowired CustomerSessionService sessions;
    @Autowired CustomerPaymentHistoryCursorPort cursors;
    private final JsonMapper json = JsonMapper.builder().build();
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
    void historyIsPrincipalDerivedSiteIsolatedAndUsesAnExactSafeProjection() throws Exception {
        Payment catalog = saveCatalog(customer.id(), UUID.fromString("10000000-0000-0000-0000-000000000011"),
            INITIAL_TIME.plusSeconds(2));
        Payment dynamic = saveDynamic(site, customer.id(), UUID.fromString("10000000-0000-0000-0000-000000000012"),
            INITIAL_TIME.plusSeconds(1));
        Customer second = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        saveDynamic(site, second.id(), UUID.randomUUID(), INITIAL_TIME.plusSeconds(4));
        saveDynamic(site, null, UUID.randomUUID(), INITIAL_TIME.plusSeconds(5));
        SiteId other = otherSite();
        Customer foreign = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), other, null, null, INITIAL_TIME));
        saveDynamic(other, foreign.id(), UUID.randomUUID(), INITIAL_TIME.plusSeconds(6));

        String body = customerGet(PAYMENTS).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].payment_id").value(catalog.id().value().toString()))
            .andExpect(jsonPath("$.items[0].catalog.price_id").value(price.id().value().toString()))
            .andExpect(jsonPath("$.items[0].catalog.price_key").value("lifetime"))
            .andExpect(jsonPath("$.items[0].catalog.version").value(1))
            .andExpect(jsonPath("$.items[1].payment_id").value(dynamic.id().value().toString()))
            .andExpect(jsonPath("$.items[1].catalog").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.next_cursor").value(org.hamcrest.Matchers.nullValue()))
            .andReturn().getResponse().getContentAsString();

        JsonNode root = json.readTree(body);
        assertThat(fieldNames(root)).containsExactlyInAnyOrder("items", "next_cursor");
        for (JsonNode item : root.get("items")) {
            assertThat(fieldNames(item)).containsExactlyInAnyOrder("payment_id", "source", "amount_minor", "currency",
                "status", "created_at", "updated_at", "catalog");
            assertThat(item.has("customer_id")).isFalse();
            assertThat(item.has("external_order_id")).isFalse();
            assertThat(item.has("technical_metadata")).isFalse();
            assertThat(item.has("provider_payment_reference")).isFalse();
        }
    }

    @Test
    void keysetPaginationIsStableForEqualTimestampsWithoutGapsOrDuplicates() throws Exception {
        Instant same = INITIAL_TIME.plusSeconds(10);
        List<UUID> expected = List.of(
            UUID.fromString("30000000-0000-0000-0000-000000000005"),
            UUID.fromString("30000000-0000-0000-0000-000000000004"),
            UUID.fromString("30000000-0000-0000-0000-000000000003"),
            UUID.fromString("30000000-0000-0000-0000-000000000002"),
            UUID.fromString("30000000-0000-0000-0000-000000000001"));
        for (UUID id : expected.reversed()) saveDynamic(site, customer.id(), id, same);

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        do {
            String query = PAYMENTS + "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            String page = customerGet(query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> ids = JsonPath.read(page, "$.items[*].payment_id");
            ids.forEach(value -> seen.add(UUID.fromString(value)));
            cursor = JsonPath.read(page, "$.next_cursor");
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(new LinkedHashSet<>(seen)).hasSize(expected.size());
    }

    @Test
    void defaultMaximumAndInvalidLimitsAreEnforcedWithoutOffsetPagination() throws Exception {
        for (int index = 0; index < 21; index++) {
            saveDynamic(site, customer.id(), UUID.randomUUID(), INITIAL_TIME.plusSeconds(index));
        }
        customerGet(PAYMENTS).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20))
            .andExpect(jsonPath("$.next_cursor").isString());
        customerGet(PAYMENTS + "?limit=100").andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(21)).andExpect(jsonPath("$.next_cursor").value(org.hamcrest.Matchers.nullValue()));
        for (String invalid : List.of("0", "101", "-1")) {
            customerGet(PAYMENTS + "?limit=" + invalid).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        }
        customerGet(PAYMENTS + "?offset=1").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"));
        assertThat(count("payments")).isEqualTo(21);
    }

    @Test
    void cursorsAreOpaqueTamperEvidentVersionedAndBoundToSiteCustomerQueryAndPageSize() throws Exception {
        saveDynamic(site, customer.id(), UUID.randomUUID(), INITIAL_TIME.plusSeconds(2));
        saveDynamic(site, customer.id(), UUID.randomUUID(), INITIAL_TIME.plusSeconds(1));
        String page = customerGet(PAYMENTS + "?limit=1").andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page, "$.next_cursor");
        assertThat(cursor).startsWith("c1.v2.").doesNotContain("=", customer.id().value().toString(), site.value().toString());

        char last = cursor.charAt(cursor.length() - 1);
        String tampered = cursor.substring(0, cursor.length() - 1) + (last == 'A' ? 'B' : 'A');
        for (String invalid : List.of("not-a-cursor", tampered, cursor.replaceFirst("^c1", "c9"), cursor + ".trailing")) {
            assertInvalidCursor(PAYMENTS + "?limit=1&cursor=" + invalid);
        }
        assertInvalidCursor(PAYMENTS + "?limit=2&cursor=" + cursor);
        String wrongShape = cursors.encode(new Position(site, customer.id().value(), "customer-donations-v1", 1,
            INITIAL_TIME.plusSeconds(2), new PaymentId(UUID.randomUUID())));
        assertInvalidCursor(PAYMENTS + "?limit=1&cursor=" + wrongShape);
        assertInvalidCursor(PAYMENTS + "?cursor=");
        assertInvalidCursor(PAYMENTS + "?cursor=" + "x".repeat(4097));

        Customer second = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, INITIAL_TIME));
        CustomerSessionCreation secondSession = sessions.issue(site, second.id(), ORIGIN);
        customerGet(PAYMENTS + "?limit=1&cursor=" + cursor, secondSession)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CUSTOMER_HISTORY_CURSOR_INVALID"));

        SiteId other = otherSite();
        Customer foreign = customers.save(Customer.create(new CustomerId(UUID.randomUUID()), other, null, null, INITIAL_TIME));
        CustomerSessionCreation foreignSession = sessions.issue(other, foreign.id(), ORIGIN);
        customerGet(PAYMENTS + "?limit=1&cursor=" + cursor, foreignSession)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CUSTOMER_HISTORY_CURSOR_INVALID"));

        assertThat(count("payments")).isEqualTo(2);
    }

    @Test
    void paymentsAndDonationHistoryRequireCustomerReadAndReturnNoStoreExactShapes() throws Exception {
        customerGet(DONATIONS).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.next_cursor").value(org.hamcrest.Matchers.nullValue()));
        String donation = customerGet(DONATIONS + "?limit=100")
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.next_cursor").value(org.hamcrest.Matchers.nullValue()))
            .andReturn().getResponse().getContentAsString();
        assertThat(fieldNames(json.readTree(donation))).containsExactlyInAnyOrder("items", "next_cursor");
        customerGet(DONATIONS + "?limit=100&cursor=opaque-placeholder")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CUSTOMER_HISTORY_CURSOR_INVALID"))
            .andExpect(header().string("Cache-Control", "no-store"));
        for (String invalid : List.of("0", "101", "-1")) {
            customerGet(DONATIONS + "?limit=" + invalid).andExpect(status().isBadRequest());
        }
        customerGet(DONATIONS + "?cursor=").andExpect(status().isBadRequest());
        customerGet(DONATIONS + "?cursor=" + "x".repeat(4097)).andExpect(status().isBadRequest());

        jdbc.update("update customer_sessions set scopes='[\"CHECKOUT_CREATE\"]' where id=?", session.sessionId().value());
        for (String path : List.of(PAYMENTS, DONATIONS)) {
            customerGet(path).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    @Test
    void openApiPublishesOnlyTheTwoCustomerHistoryOperations() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/customer/payments'].get.operationId").value("listCustomerPayments"))
            .andExpect(jsonPath("$.paths['/api/v1/customer/donations'].get.operationId").value("listCustomerDonations"));
    }

    private void assertInvalidCursor(String path) throws Exception {
        customerGet(path).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("CUSTOMER_HISTORY_CURSOR_INVALID"))
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    private org.springframework.test.web.servlet.ResultActions customerGet(String path) throws Exception {
        return customerGet(path, session);
    }

    private org.springframework.test.web.servlet.ResultActions customerGet(String path, CustomerSessionCreation authentication)
            throws Exception {
        return mvc.perform(get(path).header("Origin", ORIGIN)
            .header("Authorization", "Bearer " + authentication.rawToken()));
    }

    private Payment saveCatalog(CustomerId owner, UUID id, Instant createdAt) {
        PriceSnapshot snapshot = new PriceSnapshot(price.id(), price.key().value(), price.version(), price.money(), price.taxMode());
        return save.save(new Payment(new PaymentId(id), site, owner.value(), account.id(), PaymentSource.CATALOG,
            snapshot.priceId(), snapshot, null, snapshot.money(), snapshot.taxMode(), null, PaymentStatus.CREATED,
            null, 0, 0, createdAt, createdAt, Map.of("private", "not-returned")));
    }

    private Payment saveDynamic(SiteId paymentSite, CustomerId owner, UUID id, Instant createdAt) {
        return save.save(new Payment(new PaymentId(id), paymentSite, owner == null ? null : owner.value(), account.id(),
            PaymentSource.DYNAMIC, null, null, "order-" + id, Money.of(2300, "EUR"), TaxMode.NONE, null,
            PaymentStatus.CREATED, null, 0, 0, createdAt, createdAt, Map.of("private", "not-returned")));
    }

    private SiteId otherSite() {
        SiteId other = new SiteId(UUID.randomUUID());
        jdbc.update("""
            insert into sites(id,site_key,display_name,status,payment_account_id,payment_method_policy,created_at,updated_at,version)
            values (?,'history-other-site','Other','ACTIVE',?,'AUTOMATIC',?,?,0)
            """, other.value(), account.id().value(), Timestamp.from(INITIAL_TIME), Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,'https://shop.test')", other.value());
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'SITE_TOKEN')", other.value());
        return other;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(node.propertyNames());
        return names;
    }
}
