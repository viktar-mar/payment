package dev.genericpayments.platform.payments.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.genericpayments.platform.partners.PartnerTestFixture;
import dev.genericpayments.platform.support.AdminApiClient;
import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.ResultActions;

class CheckoutReturnUrlAdminIntegrationTest extends PartnerTestFixture {
    @Autowired WebApplicationContext context;
    @Autowired AdminBootstrap bootstrap;
    AdminApiClient client;
    String path;
    @BeforeEach void login() throws Exception {
        bootstrap.initialize(); client = new AdminApiClient(context); client.login();
        path = "/api/v1/admin/sites/" + site.value() + "/checkout-return-urls";
    }
    ResultActions update(long version, String origin, String url) throws Exception {
        return client.mvc.perform(put(path).cookie(client.session, client.xsrf)
            .header("X-XSRF-TOKEN", client.xsrf.getValue()).header("X-Request-ID", "return-policy-test")
            .contentType("application/json").content("""
                {"origin":"%s","return_url":"%s","expected_version":%d}
                """.formatted(origin,url,version)));
    }
    @Test void exactVersionedPolicyIsReadableAndAudited() throws Exception {
        update(-1,"https://shop.test","https://shop.test/payment-return").andExpect(status().isNoContent());
        client.getJson(path).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$[0].version").value(0)).andExpect(jsonPath("$[0].return_url").value("https://shop.test/payment-return"));
        update(-1,"https://shop.test","https://shop.test/other").andExpect(status().isConflict());
        update(0,"https://shop.test","https://shop.test/other").andExpect(status().isNoContent());
        update(0,"https://shop.test","https://shop.test/stale").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select return_url from checkout_return_urls",String.class)).isEqualTo("https://shop.test/other");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='CHECKOUT_RETURN_URL_UPDATED'",Integer.class)).isEqualTo(2);
    }
    @Test void auditFailureCannotLeaveChangedPolicy() throws Exception {
        rejectWrite("audit_log","insert","true");
        update(-1,"https://shop.test","https://shop.test/payment-return").andExpect(status().isConflict());
        assertThat(count("checkout_return_urls")).isZero();
    }
    @Test void auditFailureRollsBackAnExistingVersionAndConcurrentEditorsCannotOverwrite()throws Exception {
        update(-1,"https://shop.test","https://shop.test/payment-return").andExpect(status().isNoContent());
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->update(0,"https://shop.test","https://shop.test/first").andReturn().getResponse().getStatus());
            var second=executor.submit(()->update(0,"https://shop.test","https://shop.test/second").andReturn().getResponse().getStatus());
            assertThat(java.util.List.of(first.get(),second.get())).containsExactlyInAnyOrder(204,409);
        }
        String before=jdbc.queryForObject("select return_url from checkout_return_urls",String.class);
        rejectWrite("audit_log","insert","true");
        update(1,"https://shop.test","https://shop.test/rollback").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select version from checkout_return_urls",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select return_url from checkout_return_urls",String.class)).isEqualTo(before);
    }
    @Test void exactOriginAndSafeUrlAreRequired() throws Exception {
        for (String url : java.util.List.of("https://other.test/return","https://shop.test/return#secret","https://user@shop.test/return","https://shop.test/%250a"))
            update(-1,"https://shop.test",url).andExpect(status().is4xxClientError());
        update(-1,"https://other.test","https://other.test/payment-return").andExpect(status().is4xxClientError());
        assertThat(count("checkout_return_urls")).isZero();
    }
    @Test void sessionAndCsrfProtectProvisioningAndRead() throws Exception {
        client.mvc.perform(get(path)).andExpect(status().isUnauthorized());
        client.mvc.perform(get(path).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("customer").roles("CUSTOMER")))
            .andExpect(status().isForbidden());
        client.mvc.perform(put(path).cookie(client.session).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        assertThat(count("checkout_return_urls")).isZero();
    }
}
