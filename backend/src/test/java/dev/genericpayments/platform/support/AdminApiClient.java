package dev.genericpayments.platform.support;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Real login and cookie/CSRF exchange shared by HTTP integration and acceptance tests. */
public final class AdminApiClient {
    public final MockMvc mvc;
    public Cookie session;
    public Cookie xsrf;
    public AdminApiClient(WebApplicationContext context) {
        this(context, null);
    }
    public AdminApiClient(WebApplicationContext context, String remoteAddress) {
        var builder = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(RequestIdFilter.class))
            .apply(springSecurity());
        if (remoteAddress != null) builder.defaultRequest(get("/").remoteAddress(remoteAddress));
        mvc = builder.build();
    }
    public void login() throws Exception {
        var response = mvc.perform(post("/api/admin/v1/auth/sessions").contentType("application/json")
            .content("{\"username\":\"admin\",\"password\":\"local-password\"}"))
            .andExpect(status().isNoContent()).andReturn().getResponse();
        session = response.getCookie("PAYMENT_ADMIN_SESSION");
        xsrf = response.getCookie("XSRF-TOKEN");
    }
    public ResultActions postJson(String path, String json) throws Exception {
        return mvc.perform(post(path).cookie(session, xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
            .header("X-Request-Id", "catalog-test").contentType("application/json").content(json));
    }
    public ResultActions getJson(String path) throws Exception {
        return mvc.perform(get(path).cookie(session));
    }
    public static String id(ResultActions response) throws Exception {
        return JsonPath.read(response.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }
    public String account(String key) throws Exception {
        return id(postJson("/api/admin/v1/payment-accounts", """
            {"key":"%s","provider":"FAKE","mode":"SANDBOX","external_account_id":"fake-account",
             "credentials_ref":"secret-reference"}
            """.formatted(key)).andExpect(jsonPath("$.credentials_ref").doesNotExist()));
    }
    public String site(String accountId, String key) throws Exception {
        return id(postJson("/api/admin/v1/sites", """
            {"key":"%s","display_name":"Demo Site","payment_account_id":"%s",
             "allowed_origins":["http://localhost:3000"],"authentication_modes":["SITE_TOKEN"],
             "payment_method_policy":{"mode":"AUTOMATIC","methods":[]}}
            """.formatted(key, accountId)).andExpect(jsonPath("$.payment_method_policy.methods").isEmpty()));
    }
    public String product(String siteId, String key) throws Exception {
        return id(postJson("/api/admin/v1/sites/" + siteId + "/products",
            "{\"key\":\"%s\",\"name\":\"Pro Plan\",\"description\":\"Monthly access\"}".formatted(key)));
    }
    public String price(String productId, String key, long amount, int trial) throws Exception {
        return id(postJson("/api/admin/v1/products/" + productId + "/prices", """
            {"price_key":"%s","type":"SUBSCRIPTION","amount_minor":%d,"currency":"EUR",
             "billing_interval":"MONTH","trial_days":%d,"tax_mode":"INCLUSIVE"}
            """.formatted(key, amount, trial)));
    }
}
