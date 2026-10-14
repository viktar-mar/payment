package dev.genericpayments.platform.donations.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.jayway.jsonpath.JsonPath;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.web.servlet.ResultActions;

@org.springframework.test.context.TestPropertySource(properties="payment.telemetry.site-keys[0]=donation-site")
class PublicDonationCheckoutApiIntegrationTest extends CampaignApiTestSupport {
    static final String URL="/api/v1/public/sites/donation-site/donation-campaigns/help-now/checkouts";
    String campaign;
    @BeforeEach void activate() throws Exception { campaign=create();changeStatus(campaign,"ACTIVE",0,"activate").andExpect(status().isOk()); }
    ResultActions checkout(String body,String key,String... auth) throws Exception {
        var r=post(URL).header("Origin","http://localhost:3000").header("Idempotency-Key",key).contentType("application/json").content(body);
        if(auth.length>0)r.header("Authorization",(Object[])auth);
        return api.mvc.perform(r);
    }
    @Test void createsOnlyLocalFactsWithSafeSnapshotAndStableReplayAfterCampaignEdit() throws Exception {
        String first=checkout("{\"amount_minor\":500,\"message\":\"café\"}","donate").andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("CREATED")).andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.donation.campaign_key").value("help-now")).andExpect(jsonPath("$.donation.campaign_version").value(1))
            .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Access-Control-Allow-Origin","http://localhost:3000"))
            .andReturn().getResponse().getContentAsString();
        assertThat(first).doesNotContain("café","message","customer_id");
        assertThat(jdbc.queryForObject("select count(*) from donations",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payments where source='DONATION' and tax_mode='NONE' and customer_id is null",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment_events",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from fake_provider_operations",Long.class)).isZero();
        changeStatus(campaign,"PAUSED",1,"pause").andExpect(status().isOk());
        assertThat(checkout("{\"amount_minor\":500,\"message\":\"café\"}","donate").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).isEqualTo(first);
        checkout("{\"amount_minor\":100,\"message\":\"café\"}","donate").andExpect(status().isConflict());
        checkout("{\"amount_minor\":500,\"message\":\"other\"}","donate").andExpect(status().isConflict());
    }
    @Test void activeUnconfiguredSiteGetsSafe503WithoutCheckoutFacts() throws Exception {
        jdbc.update("update sites set site_key='unconfigured' where site_key='donation-site'");
        api.mvc.perform(post(URL.replace("donation-site","unconfigured")).header("Origin","http://localhost:3000")
            .header("Idempotency-Key","preflight").contentType("application/json").content("{\"amount_minor\":500}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SITE_NOT_AVAILABLE"));
        for(var table:List.of("donations","payments","payment_events","checkout_sessions","fake_provider_operations"))
            assertThat(jdbc.queryForObject("select count(*) from "+table,Long.class)).as(table).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records where idempotency_key='preflight'",Long.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"","Bearer ","Basic abc","Bearer expired","Bearer a b","Bearer a,b"})
    void suppliedInvalidCredentialPrecedesBodyKeyAndCampaignParsing(String auth) throws Exception {
        api.mvc.perform(post(URL.replace("help-now","!invalid")).header("Origin","http://localhost:3000").header("Authorization",auth)
            .contentType("application/json").content("{")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        assertThat(jdbc.queryForObject("select count(*) from donations",Long.class)).isZero();
    }
    @ParameterizedTest @ValueSource(longs={-1,0,99,101,1001,Long.MAX_VALUE})
    void suggestedOnlyPolicyRejectsDisallowedAmounts(long amount) throws Exception {
        checkout("{\"amount_minor\":"+amount+"}","amount").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("AMOUNT_NOT_ALLOWED"));
        assertThat(jdbc.queryForObject("select count(*) from donations",Long.class)).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"currency","customer_id","site_id","tax_mode","provider","status","campaign_key"})
    void refusesBrowserOwnedServerFields(String field) throws Exception {
        checkout("{\"amount_minor\":500,\""+field+"\":\"forged\"}","forged").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from donations",Long.class)).isZero();
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"null","http://localhost:3000/","https://evil.test"})
    void requiresExactNonNullOrigin(String origin) throws Exception {
        var r=post(URL).header("Idempotency-Key","origin").contentType("application/json").content("{\"amount_minor\":500}");
        if(origin!=null)r.header("Origin",origin);
        api.mvc.perform(r).andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
    @Test void writePreflightAndBoundedCreateBucket() throws Exception {
        api.mvc.perform(options(URL).header("Origin","http://localhost:3000").header("Access-Control-Request-Method","POST")
            .header("Access-Control-Request-Headers","Authorization, Content-Type, Idempotency-Key"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Methods","POST, OPTIONS"));
        api.mvc.perform(options(URL).header("Origin","http://localhost:3000").header("Access-Control-Request-Method","PUT")).andExpect(status().isForbidden());
        for(int i=0;i<20;i++)checkout("{\"amount_minor\":500}","same").andExpect(status().isCreated());
        checkout("{\"amount_minor\":500}","same").andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
    @Test void bodyBoundPrecedesJsonAndReadsCanonicalContextPath() throws Exception {
        api.mvc.perform(post("/payment"+URL).contextPath("/payment").header("Origin","http://localhost:3000").header("Idempotency-Key","large")
            .contentType("application/json").content("{"+"x".repeat(16384)))
            .andExpect(status().is(413)).andExpect(jsonPath("$.code").value("DONATION_CHECKOUT_REQUEST_TOO_LARGE"));
        assertThat(jdbc.queryForObject("select count(*) from donations",Long.class)).isZero();
    }
    @Test void existingConfirmPreparesAfterLocalCreationAndKeepsDonationSnapshot() throws Exception {
        String response=checkout("{\"amount_minor\":500}","donate").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id=JsonPath.read(response,"$.checkout_id"),token=JsonPath.read(response,"$.checkout_token");
        api.mvc.perform(post("/api/v1/public/checkouts/"+id+"/confirm").header("Origin","http://localhost:3000").header("Authorization","Bearer "+token)
            .header("Idempotency-Key","confirm").contentType("application/json").content("{\"payment_method_family\":\"CARD\"}"))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select snapshot_campaign_version from payments",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from fake_provider_operations",Long.class)).isPositive();
    }
}
