package dev.genericpayments.platform.donations.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.jayway.jsonpath.JsonPath;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PublicDonationCampaignApiIntegrationTest extends CampaignApiTestSupport {
    @org.springframework.beans.factory.annotation.Autowired dev.genericpayments.platform.payments.adapter.in.web.PublicRateLimitProperties rateLimits;
    static final String PUBLIC="/api/v1/public/sites/donation-site/donation-campaigns/help-now";
    @Test void activeReadsUseTheEstablishedSiteAndIpReadAdmissionLimit() throws Exception {
        var id=create();changeStatus(id,"ACTIVE",0,"activate").andExpect(status().isOk());
        for(int i=0;i<rateLimits.readPerMinute();i++) readPublic("http://localhost:3000").andExpect(status().isOk());
        readPublic("http://localhost:3000").andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
            .andExpect(header().string("Access-Control-Allow-Origin","http://localhost:3000"))
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
        api.mvc.perform(get(PUBLIC).with(request->{request.setRemoteAddr("192.0.2.5");return request;}).header("Origin","http://localhost:3000"))
            .andExpect(status().isOk());
    }
    @Test void contextRelativeCanonicalRouteStillEnforcesExactOrigin() throws Exception {
        var id=create();changeStatus(id,"ACTIVE",0,"activate").andExpect(status().isOk());
        api.mvc.perform(get("/payment"+PUBLIC).contextPath("/payment").header("Origin","http://localhost:3000"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://localhost:3000"));
        api.mvc.perform(get("/payment"+PUBLIC).contextPath("/payment")).andExpect(status().isForbidden());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"/api//v1/public/,400","/api/v1/./public/,400","/api/v1/public;alias=1/,400","/api/%76%31/public/,403","/api/v1/%2570ublic/,400","/API/v1/public/,401"})
    void aliasedPublicPrefixesCannotBypassAdmission(String prefix,int expectedStatus) throws Exception {
        assertAliasRejected(prefix+"sites/donation-site/donation-campaigns/help-now",expectedStatus);
    }
    @Test void activeReadHasExactlyTheSafeFieldsAndAllowedCorsWithNoStore() throws Exception {
        var id=create(); changeStatus(id,"ACTIVE",0,"activate").andExpect(status().isOk());
        var response=readPublic("http://localhost:3000").andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin","http://localhost:3000"))
            .andExpect(header().string("Cache-Control","no-store")).andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
            .andExpect(jsonPath("$.suggested_amounts[0]").value(500)).andExpect(jsonPath("$.suggested_amounts[1]").value(100))
            .andExpect(jsonPath("$.suggested_amounts.length()").value(2)).andReturn().getResponse().getContentAsString();
        Map<String,Object> body=JsonPath.read(response,"$");
        assertThat(body.keySet()).containsExactlyInAnyOrder("key","display_name","purpose_description","currency","suggested_amounts","minimum_amount","maximum_amount","custom_amount_allowed","anonymous_allowed");
        assertThat(body).containsEntry("currency","EUR").containsEntry("anonymous_allowed",true);
    }
    @Test void draftPausedArchivedMissingAndDisabledSitesAreUnavailable() throws Exception {
        var id=create(); readPublic("http://localhost:3000").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_AVAILABLE"));
        changeStatus(id,"ACTIVE",0,"activate").andExpect(status().isOk()); changeStatus(id,"PAUSED",1,"pause").andExpect(status().isOk());
        readPublic("http://localhost:3000").andExpect(status().isNotFound()); changeStatus(id,"ARCHIVED",2,"archive").andExpect(status().isOk());
        readPublic("http://localhost:3000").andExpect(status().isNotFound());
        api.mvc.perform(get(PUBLIC.replace("help-now","missing-campaign")).header("Origin","http://localhost:3000")).andExpect(status().isNotFound());
        api.mvc.perform(get(PUBLIC.replace("donation-site","missing-site")).header("Origin","http://localhost:3000")).andExpect(status().isNotFound());
        jdbc.update("update sites set status='DISABLED' where id=?",UUID.fromString(site));
        readPublic("http://localhost:3000").andExpect(status().isNotFound()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"null","https://evil.test","http://localhost:3000/","http://localhost:3000.evil.test","http://localhost:3001"})
    void rejectsMissingNullAndCrossOriginsBeforeRead(String origin) throws Exception {
        readPublic(origin).andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
            .andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void preflightsValidateSiteOriginMethodAndHeaders() throws Exception {
        api.mvc.perform(options(PUBLIC).header("Origin","http://localhost:3000").header("Access-Control-Request-Method","GET")
            .header("Access-Control-Request-Headers","Content-Type"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://localhost:3000"))
            .andExpect(header().string("Access-Control-Allow-Methods","GET, OPTIONS"));
        api.mvc.perform(options(PUBLIC).header("Origin","https://evil.test").header("Access-Control-Request-Method","GET")).andExpect(status().isForbidden());
        api.mvc.perform(options(PUBLIC).header("Origin","http://localhost:3000").header("Access-Control-Request-Method","POST")).andExpect(status().isForbidden());
        api.mvc.perform(options(PUBLIC).header("Origin","http://localhost:3000").header("Access-Control-Request-Method","GET").header("Access-Control-Request-Headers","X-Evil")).andExpect(status().isForbidden());
        api.mvc.perform(get(PUBLIC).header("Origin","http://localhost:3000","https://evil.test")).andExpect(status().isForbidden());
        api.mvc.perform(post(PUBLIC).header("Origin","http://localhost:3000")).andExpect(status().isForbidden());
        jdbc.update("update sites set status='DISABLED' where id=?",UUID.fromString(site));
        api.mvc.perform(options(PUBLIC).header("Origin","http://localhost:3000").header("Access-Control-Request-Method","GET"))
            .andExpect(status().isNotFound()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"/help-now/,403","/help-now;alias=1,400","//help-now,400","/%68elp-now,403","/./help-now,400"})
    void nonCanonicalPublicUrlsCannotBypassOriginAdmission(String suffix,int expectedStatus) throws Exception {
        assertAliasRejected(PUBLIC.substring(0,PUBLIC.lastIndexOf('/'))+suffix,expectedStatus);
    }
    private void assertAliasRejected(String alias,int expectedStatus) throws Exception {
        var id=create();changeStatus(id,"ACTIVE",0,"activate").andExpect(status().isOk());
        readPublic("http://localhost:3000").andExpect(status().isOk())
            .andExpect(jsonPath("$.key").value("help-now"));
        for(boolean validOrigin:List.of(true,false)){
            // The String overload normalizes duplicate slashes before the security chain sees them.
            var request=get(java.net.URI.create(alias));if(validOrigin)request.header("Origin","http://localhost:3000");
            api.mvc.perform(request).andExpect(result->assertThat(result.getRequest().getRequestURI()).isEqualTo(alias))
                .andExpect(status().is(expectedStatus))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }
}
