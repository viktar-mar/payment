package dev.genericpayments.platform.admin.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.genericpayments.platform.support.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;

@TestPropertySource(properties = {"payment.admin.bootstrap.username=admin", "payment.admin.bootstrap.password=local-password"})
class AdminCatalogApiIntegrationTest extends PostgresIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired AdminBootstrap bootstrap;
    @Autowired JdbcTemplate jdbc;
    AdminApiClient api;
    @BeforeEach void setup() throws Exception {
        clearDatabase(); bootstrap.initialize(); api = new AdminApiClient(context); api.login();
    }
    @Test void adminCreatesSiteProductAndPublishesPrice() throws Exception {
        String account = api.account("local-fake");
        String site = api.site(account, "demo-site");
        String product = api.product(site, "pro");
        String price = api.price(product, "pro-monthly", 1999, 14);
        api.postJson("/api/admin/v1/prices/" + price + "/publish", "")
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.provider_price_id").value(startsWith("fake_price_")))
            .andExpect(jsonPath("$.price_key").value("pro-monthly"))
            .andExpect(jsonPath("$.amount_minor").value(1999)).andExpect(jsonPath("$.trial_days").value(14));
        api.getJson("/api/admin/v1/payment-accounts").andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(account)).andExpect(jsonPath("$[0].credentials_ref").doesNotExist());
        api.getJson("/api/admin/v1/sites").andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(site));
        api.getJson("/api/admin/v1/sites/" + site + "/products").andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(product));
        api.price(product, "pro-monthly", 2499, 14);
        api.getJson("/api/admin/v1/sites/" + site + "/catalog").andExpect(status().isOk())
            .andExpect(jsonPath("$.products[0].id").value(product))
            .andExpect(jsonPath("$.prices[?(@.version == 1)].status").value(contains("PUBLISHED")))
            .andExpect(jsonPath("$.prices[?(@.version == 1)].amount_minor").value(contains(1999)))
            .andExpect(jsonPath("$.prices[?(@.version == 2)].status").value(contains("DRAFT")));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where request_id='catalog-test'", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where request_id='catalog-test' and actor_type='ADMIN' and actor_id=(select id::text from admin_users) and resource_id is not null and details <> '{}'::jsonb", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForList("select * from audit_log").toString()).doesNotContain("secret-reference", api.session.getValue(), "local-password");
    }
    @Test void validationAndFailedMutationsDoNotProduceSuccessAudits() throws Exception {
        api.postJson("/api/admin/v1/payment-accounts", "{}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"));
        String account = api.account("local-fake");
        api.postJson("/api/admin/v1/sites", """
            {"key":"bad-site","display_name":"Demo","payment_account_id":"%s","allowed_origins":[null],
             "authentication_modes":["SITE_TOKEN"],"payment_method_policy":{"mode":"AUTOMATIC","methods":[]}}
            """.formatted(account)).andExpect(status().isBadRequest());
        String site = api.site(account, "demo-site");
        api.product(site, "pro");
        api.postJson("/api/admin/v1/sites/" + site + "/products", "{\"key\":\"pro\",\"name\":\"Duplicate\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("resource_conflict"));
        api.postJson("/api/admin/v1/prices/00000000-0000-0000-0000-000000000001/publish", "")
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.request_id").value("catalog-test"));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where request_id='catalog-test'", Integer.class)).isEqualTo(3);
    }
    @Test void explicitTrialMethodPolicySurvivesCreationPublicationAndCatalogRead() throws Exception {
        String site = api.site(api.account("trial-account"), "trial-site");
        String product = api.product(site, "pro");
        var created = api.postJson("/api/admin/v1/products/" + product + "/prices", """
            {"price_key":"pro-monthly","type":"SUBSCRIPTION","amount_minor":1999,"currency":"EUR",
             "billing_interval":"MONTH","trial_days":14,"tax_mode":"NONE",
             "trial_payment_method_policy":"REQUIRED_BEFORE_TRIAL"}
            """).andExpect(status().isCreated())
            .andExpect(jsonPath("$.trial_payment_method_policy").value("REQUIRED_BEFORE_TRIAL")).andReturn();
        String price = tools.jackson.databind.json.JsonMapper.builder().build()
            .readTree(created.getResponse().getContentAsString()).get("id").asText();
        api.postJson("/api/admin/v1/prices/" + price + "/publish", "")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trial_payment_method_policy").value("REQUIRED_BEFORE_TRIAL"));
        api.getJson("/api/admin/v1/sites/" + site + "/catalog").andExpect(status().isOk())
            .andExpect(jsonPath("$.prices[0].trial_payment_method_policy").value("REQUIRED_BEFORE_TRIAL"));
        assertThat(jdbc.queryForObject("select trial_payment_method_policy from prices where id=?::uuid", String.class, price))
            .isEqualTo("REQUIRED_BEFORE_TRIAL");
    }
    @Test void catalogRoutesRequireSessionAndMutationCsrf() throws Exception {
        for (String route : new String[]{"payment-accounts", "sites", "sites/00000000-0000-0000-0000-000000000001/products", "sites/00000000-0000-0000-0000-000000000001/catalog"}) {
            api.mvc.perform(get("/api/admin/v1/" + route)).andExpect(status().isUnauthorized());
        }
        for (String route : new String[]{"payment-accounts", "sites", "sites/00000000-0000-0000-0000-000000000001/products", "products/00000000-0000-0000-0000-000000000001/prices", "prices/00000000-0000-0000-0000-000000000001/publish"}) {
            api.mvc.perform(post("/api/admin/v1/" + route)).andExpect(status().isUnauthorized());
            api.mvc.perform(post("/api/admin/v1/" + route).cookie(api.session)).andExpect(status().isForbidden());
        }
    }
    @Test void oversizedOriginIsRejectedBeforeDatabaseWrite() throws Exception {
        String account = api.account("local-fake");
        api.postJson("/api/admin/v1/sites", """
            {"key":"demo-site","display_name":"Demo","payment_account_id":"%s","allowed_origins":["https://%s.example"],
             "authentication_modes":["SITE_TOKEN"],"payment_method_policy":{"mode":"AUTOMATIC","methods":[]}}
            """.formatted(account, "a".repeat(250))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"));
        assertThat(jdbc.queryForObject("select count(*) from sites", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='SITE_CREATED'", Integer.class)).isZero();
    }
    @Test void existingStageOneDtoRejectsUnknownPropertiesWithoutChangingValidShape() throws Exception {
        api.postJson("/api/admin/v1/payment-accounts", """
            {"key":"strict-account","provider":"FAKE","mode":"SANDBOX","external_account_id":"fake-account",
             "credentials_ref":"secret-reference","unknown_field":"ignored?"}
            """).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_request"));
        assertThat(jdbc.queryForObject("select count(*) from payment_accounts", Integer.class)).isZero();
        api.account("strict-account");
        assertThat(jdbc.queryForObject("select count(*) from payment_accounts", Integer.class)).isEqualTo(1);
    }
    @Test void openApiExposesStableCatalogOperationsAndSafeResponseSchemas() throws Exception {
        api.mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/admin/v1/payment-accounts'].get.operationId").value("listPaymentAccounts"))
            .andExpect(jsonPath("$.paths['/api/admin/v1/payment-accounts'].post.operationId").value("createPaymentAccount"))
            .andExpect(jsonPath("$.paths['/api/admin/v1/sites'].get.operationId").value("listSites"))
            .andExpect(jsonPath("$.paths['/api/admin/v1/sites'].post.operationId").value("createSite"))
            .andExpect(jsonPath("$.paths['/api/admin/v1/sites/{siteId}/products'].get.operationId").value("listProducts"))
            .andExpect(jsonPath("$.paths['/api/admin/v1/sites/{siteId}/products'].post.operationId").value("createProduct"))
            .andExpect(jsonPath("$.paths['/api/admin/v1/products/{productId}/prices'].post.operationId").value("createPrice"))
            .andExpect(jsonPath("$.paths['/api/admin/v1/prices/{priceId}/publish'].post.operationId").value("publishPrice"))
            .andExpect(jsonPath("$.paths['/api/admin/v1/sites/{siteId}/catalog'].get.operationId").value("getCatalog"))
            .andExpect(jsonPath("$.components.schemas.PaymentAccountDto.properties.credentials_ref").doesNotExist())
            .andExpect(jsonPath("$.components.schemas.SiteResponse.properties.display_name").exists())
            .andExpect(jsonPath("$.components.schemas.SiteResponse.properties.displayName").doesNotExist())
            .andExpect(jsonPath("$.components.schemas.CreateSiteRequest.properties.allowed_origins").exists())
            .andExpect(jsonPath("$.components.schemas.CreatePriceRequest.properties.price_key").exists())
            .andExpect(jsonPath("$.components.schemas.PriceResponse.properties.provider_price_id").exists());
    }
}
