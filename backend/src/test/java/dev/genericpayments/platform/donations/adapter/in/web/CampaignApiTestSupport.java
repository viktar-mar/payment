package dev.genericpayments.platform.donations.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.jayway.jsonpath.JsonPath;
import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import dev.genericpayments.platform.support.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.web.context.WebApplicationContext;

@TestPropertySource(properties={"payment.admin.bootstrap.username=admin","payment.admin.bootstrap.password=local-password"})
abstract class CampaignApiTestSupport extends PostgresIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired AdminBootstrap bootstrap;
    @Autowired JdbcTemplate jdbc;
    AdminApiClient api;
    String site, otherSite, path;
    static final String CONFIG = """
        {"key":"help-now","display_name":"Help <script>now</script>","purpose_description":"Medical support",
         "currency":"EUR","suggested_amounts":[500,100],"minimum_amount":100,"maximum_amount":1000,
         "custom_amount_allowed":false,"anonymous_allowed":true}
        """;
    @BeforeEach void prepare() throws Exception {
        clearDatabase(); bootstrap.initialize(); api=new AdminApiClient(context); api.login();
        var account=api.account("donation-account"); site=api.site(account,"donation-site");
        otherSite=api.site(account,"other-site"); path="/api/v1/admin/sites/"+site+"/donation-campaigns";
    }
    ResultActions write(String method,String target,String body,String key) throws Exception {
        var request=request(org.springframework.http.HttpMethod.valueOf(method),target)
            .cookie(api.session,api.xsrf).header("X-XSRF-TOKEN",api.xsrf.getValue())
            .header("X-Request-ID","campaign-tests").contentType("application/json").content(body);
        if(key!=null) request.header("Idempotency-Key",key);
        return api.mvc.perform(request);
    }
    String create() throws Exception { return JsonPath.read(write("POST",path,CONFIG,"create-intent").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"$.id"); }
    ResultActions changeStatus(String id,String status,long version,String key) throws Exception {
        return write("POST",path+"/"+id+"/status","{\"status\":\""+status+"\",\"expected_version\":"+version+"}",key);
    }
    ResultActions readPublic(String origin) throws Exception {
        var request=get("/api/v1/public/sites/donation-site/donation-campaigns/help-now");
        if(origin!=null) request.header("Origin",origin);
        return api.mvc.perform(request);
    }
}
