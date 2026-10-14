package dev.genericpayments.platform.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import dev.genericpayments.platform.support.AdminApiClient;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import io.cucumber.java.Before;
import io.cucumber.java.en.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

public class FoundationCatalogSteps {
    private final AcceptanceWorld world;
    private final JdbcTemplate jdbc;
    private final AdminBootstrap bootstrap;
    private final WebApplicationContext context;
    public FoundationCatalogSteps(AcceptanceWorld world, JdbcTemplate jdbc, AdminBootstrap bootstrap, WebApplicationContext context) {
        this.world = world; this.jdbc = jdbc; this.bootstrap = bootstrap; this.context = context;
    }
    @Before public void resetScenario() {
        PostgresIntegrationTest.truncateDatabase(jdbc);
        PostgresIntegrationTest.TestClock.set(PostgresIntegrationTest.INITIAL_TIME);
        bootstrap.initialize();
        assertThat(world.siteIds()).isEmpty();
        assertThat(world.productIds()).isEmpty();
        assertThat(world.priceIds()).isEmpty();
        assertThat(world.api()).isNull();
        world.api(new AdminApiClient(context));
    }
    @Given("an active fake payment account {string}")
    public void activeFakeAccount(String key) throws Exception {
        authenticatedAdministrator();
        world.accountId(world.api().account(key));
        world.api().getJson("/api/admin/v1/payment-accounts").andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("ACTIVE")).andExpect(jsonPath("$[0].provider").value("FAKE"));
    }
    @Given("an authenticated administrator")
    public void authenticatedAdministrator() throws Exception {
        if (world.api().session == null) world.api().login();
        world.api().getJson("/api/admin/v1/auth/session").andExpect(status().isOk()).andExpect(jsonPath("$.username").value("admin"));
    }
    @When("the administrator creates site {string}")
    public void createSite(String key) throws Exception { world.siteIds().put(key, world.api().site(world.accountId(), key)); }
    @When("creates product {string} for site {string}")
    public void createProduct(String productKey, String siteKey) throws Exception {
        world.productIds().put(productKey, world.api().product(world.siteIds().get(siteKey), productKey));
    }
    @When("creates monthly price {string} for {long} EUR cents with a {int} day trial")
    public void createMonthlyPrice(String priceKey, long amountMinor, int trialDays) throws Exception {
        world.priceIds().put(priceKey, world.api().price(world.productIds().values().iterator().next(), priceKey, amountMinor, trialDays));
    }
    @When("publishes price {string}")
    public void publishPrice(String priceKey) throws Exception {
        world.lastResponse(world.api().postJson("/api/admin/v1/prices/" + world.priceIds().get(priceKey) + "/publish", "")
            .andExpect(status().isOk()).andExpect(jsonPath("$.provider_price_id").value(startsWith("fake_price_"))).andReturn());
    }
    @Then("price {string} version {int} is published")
    public void priceIsPublished(String key, int version) throws Exception {
        catalog().andExpect(jsonPath(pricePath(key, version) + ".status").value(contains("PUBLISHED")));
    }
    @Then("a fake provider product mapping exists")
    public void productMappingExists() {
        assertThat(jdbc.queryForObject("select provider_product_id from provider_product_mappings where payment_account_id=? and product_id=?",
            String.class, UUID.fromString(world.accountId()), UUID.fromString(world.productIds().values().iterator().next())))
            .isEqualTo("fake_prod_" + world.productIds().values().iterator().next());
    }
    @Then("a fake provider price mapping exists")
    public void priceMappingExists() {
        assertThat(jdbc.queryForObject("select provider_price_id from provider_price_mappings where payment_account_id=? and price_id=?",
            String.class, UUID.fromString(world.accountId()), UUID.fromString(world.priceIds().values().iterator().next())))
            .startsWith("fake_price_");
    }
    @Then("the audit log contains the catalog changes")
    public void catalogAuditExists() {
        assertThat(jdbc.queryForList("select action from audit_log where request_id='catalog-test'", String.class))
            .containsExactlyInAnyOrder("PAYMENT_ACCOUNT_CREATED", "SITE_CREATED", "PRODUCT_CREATED", "PRICE_CREATED", "PRICE_PUBLISHED");
        assertThat(jdbc.queryForList("select * from audit_log").toString()).doesNotContain("secret-reference", "local-password");
    }
    @Given("site {string} has published price {string} version {int} for {long} EUR cents")
    public void existingPublishedPrice(String siteKey, String priceKey, int version, long amountMinor) throws Exception {
        activeFakeAccount("local-fake"); createSite(siteKey); createProduct("pro", siteKey);
        createMonthlyPrice(priceKey, amountMinor, 14); publishPrice(priceKey);
        publishedVersionRemains(priceKey, version, amountMinor);
    }
    @When("the administrator creates price {string} for {long} EUR cents")
    public void createAnotherPrice(String priceKey, long amountMinor) throws Exception { createMonthlyPrice(priceKey, amountMinor, 14); }
    @Then("price {string} version {int} remains published for {long} EUR cents")
    public void publishedVersionRemains(String key, int version, long amountMinor) throws Exception {
        catalog().andExpect(jsonPath(pricePath(key, version) + ".status").value(contains("PUBLISHED")))
            .andExpect(jsonPath(pricePath(key, version) + ".amount_minor").value(contains((int) amountMinor)));
    }
    @Then("price {string} version {int} is draft for {long} EUR cents")
    public void draftVersionExists(String key, int version, long amountMinor) throws Exception {
        catalog().andExpect(jsonPath(pricePath(key, version) + ".status").value(contains("DRAFT")))
            .andExpect(jsonPath(pricePath(key, version) + ".amount_minor").value(contains((int) amountMinor)));
    }
    private ResultActions catalog() throws Exception {
        return world.api().getJson("/api/admin/v1/sites/" + world.siteIds().values().iterator().next() + "/catalog").andExpect(status().isOk());
    }
    private String pricePath(String key, int version) { return "$.prices[?(@.price_key == '" + key + "' && @.version == " + version + ")]"; }
}
