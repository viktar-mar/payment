package dev.genericpayments.platform.donations.adapter.out.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.jayway.jsonpath.JsonPath;
import dev.genericpayments.platform.support.*;
import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties={"payment.admin.bootstrap.username=admin","payment.admin.bootstrap.password=local-password","payment.telemetry.site-keys[0]=donation-site"})
class DonationObservabilityIntegrationTest extends PostgresIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired AdminBootstrap bootstrap;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry registry;
    AdminApiClient api;
    static final String URL="/api/v1/public/sites/donation-site/donation-campaigns/help-now/checkouts";
    @BeforeEach void setup() throws Exception {
        clearDatabase();bootstrap.initialize();api=new AdminApiClient(context);api.login();
        registry.getMeters().stream().filter(m->m.getId().getName().startsWith("donation.")).toList().forEach(registry::remove);
        var site=api.site(api.account("donation-account"),"donation-site");
        var path="/api/v1/admin/sites/"+site+"/donation-campaigns";
        var id=JsonPath.<String>read(write(path,"{\"key\":\"help-now\",\"display_name\":\"Help\",\"currency\":\"EUR\",\"suggested_amounts\":[500],\"minimum_amount\":100,\"maximum_amount\":1000,\"custom_amount_allowed\":false,\"anonymous_allowed\":true}","campaign").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"$.id");
        write(path+"/"+id+"/status","{\"status\":\"ACTIVE\",\"expected_version\":0}","activate").andExpect(status().isOk());
    }
    ResultActions write(String path,String body,String key) throws Exception {
        return api.mvc.perform(post(path).cookie(api.session,api.xsrf).header("X-XSRF-TOKEN",api.xsrf.getValue()).header("Idempotency-Key",key).contentType("application/json").content(body));
    }
    ResultActions checkout(int amount,String key,String...auth) throws Exception {
        var request=post(URL).header("Origin","http://localhost:3000").header("Idempotency-Key",key).contentType("application/json").content("{\"amount_minor\":"+amount+",\"message\":\"observability-distinctive-message\"}");
        if(auth.length>0)request.header("Authorization",auth[0]);
        return api.mvc.perform(request);
    }
    void count(String metric,String outcome,long expected) {
        var c=registry.find(metric).tag("outcome",outcome).counter();assertThat(c).as(metric+":"+outcome).isNotNull();assertThat(c.count()).isEqualTo(expected);
    }
    @Test void realCheckoutOutcomesAndLatencyUseOnlyBoundedOutcomeLabels() throws Exception {
        checkout(500,"intent").andExpect(status().isCreated());
        checkout(500,"intent").andExpect(status().isCreated());
        checkout(100,"intent").andExpect(status().isConflict());
        checkout(101,"amount").andExpect(status().isUnprocessableEntity());
        checkout(500,"session","Bearer invalid").andExpect(status().isUnauthorized());
        jdbc.update("update donation_campaigns set anonymous_allowed=false");
        checkout(500,"anonymous").andExpect(status().isUnprocessableEntity());
        for(String outcome:List.of("created","replayed","conflict","rejected_amount","rejected_session","rejected_anonymous")){
            count("donation.checkout",outcome,1);
            var timer=registry.find("donation.checkout.latency").tag("outcome",outcome).timer();assertThat(timer).isNotNull();assertThat(timer.count()).isOne();
        }
        assertSafeTags();
        assertThat(jdbc.queryForObject("select count(*) from donations",Integer.class)).isOne();
    }
    @Test void realRedactionAndReplayAreBoundedAndDoNotExposeMessage() throws Exception {
        var created=checkout(500,"intent").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id=JsonPath.read(created,"$.donation.donation_id"),path="/api/v1/admin/donations/"+id+"/message-redactions",body="{\"confirmation\":\""+id+"\"}";
        write(path,body,"redact").andExpect(status().isOk());write(path,body,"redact").andExpect(status().isOk());write(path,body,"again").andExpect(status().isOk());
        count("donation.message.redaction","redacted",1);count("donation.message.redaction","replayed",1);count("donation.message.redaction","unchanged",1);
        assertSafeTags();
        for(String table:List.of("payment_events","outbox_events","outbox_deliveries","audit_log"))assertThat(jdbc.queryForList("select row_to_json(t)::text from "+table+" t").toString().contains("observability-distinctive-message")).isFalse();
    }
    void assertSafeTags(){
        var allowed=Set.of("created","replayed","conflict","rejected_amount","rejected_session","rejected_anonymous","rejected","failed","redacted","unchanged");
        var names=Set.of("donation.checkout","donation.checkout.latency","donation.message.redaction");
        var snapshot=new StringBuilder();
        for(var meter:registry.getMeters()){
            var id=meter.getId();
            snapshot.append(id.getName());
            id.getTags().forEach(tag->snapshot.append(tag.getKey()).append(tag.getValue()));
            meter.measure().forEach(value->snapshot.append(value.getStatistic()).append(value.getValue()));
            if(id.getName().startsWith("donation.")){
                assertThat(names.contains(id.getName())).as("donation metric name is allowlisted").isTrue();
                assertThat(id.getTags().size()).isEqualTo(1);
                assertThat("outcome".equals(id.getTags().getFirst().getKey())).as("only outcome tag key").isTrue();
                assertThat(allowed.contains(id.getTag("outcome"))).as("bounded outcome value").isTrue();
            }
        }
        assertThat(snapshot.indexOf("observability-distinctive-message")<0).as("real registry snapshot omits donor fixture").isTrue();
    }
}
