package dev.genericpayments.platform.subscriptions.adapter.in.web;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
@org.springframework.test.context.ActiveProfiles("local")
class AdminSubscriptionApiIntegrationTest extends SubscriptionApiFixture {
    @org.springframework.beans.factory.annotation.Autowired java.time.Clock clock;
    @Test void fakeControlsAreBoundedIdempotentAdminOnlyAndDoNotChangeApplicationClock() throws Exception {
        var made=start();String path="/api/v1/admin/subscriptions/"+made.subscriptionId()+"/fake-controls?site_id="+site.value();
        adminWrite(path,"fake-scenario","{\"action\":\"SCENARIO\",\"scenario\":\"DECLINE\"}").andExpect(status().isOk()).andExpect(jsonPath("$.action").value("SCENARIO"));
        adminWrite(path,"fake-scenario","{\"action\":\"SCENARIO\",\"scenario\":\"DECLINE\"}").andExpect(status().isOk());
        adminWrite(path,"fake-scenario","{\"action\":\"SCENARIO\",\"scenario\":\"SUCCESS\"}").andExpect(status().isConflict());
        adminWrite(path,"fake-bad","{\"action\":\"ADVANCE\",\"until\":\"2099-01-01T00:00:00Z\"}").andExpect(status().isBadRequest());
        adminWrite(path,"fake-json","{\"action\":\"SCENARIO\",\"scenario\":\"SUCCESS\",\"event\":{}}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='SUBSCRIPTION_FAKE_CONTROL'",Integer.class)).isEqualTo(1);
        read(BASE+"/"+made.subscriptionId()+"/fake-controls").andExpect(status().isNotFound());
        var before=clock.instant();
        adminWrite(path,"fake-advance","{\"action\":\"ADVANCE\",\"until\":\""+before.plusSeconds(86400)+"\"}").andExpect(status().isOk()).andExpect(jsonPath("$.simulated").value(true));
        assertThat(clock.instant()).isEqualTo(before);
    }
    @Test void activeSubscriptionBlocksDisableAndLeavesReloginManagementScopes() throws Exception {
        start();String path="/api/v1/admin/sites/"+site.value()+"/subscription-policy";
        String body="{\"version\":1,\"enabled\":false,\"grace_days\":3,\"allow_immediate_cancellation\":false,\"portal_enabled\":false,\"defer_upgrades\":false,\"portal_return_urls\":[]}";
        adminWrite(path,"disable",body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_STATE_CONFLICT"));
        assertThat(policies.load(site).enabled()).isTrue();
        assertThat(sessions.resolveRequired(sessions.issue(site,customer.id(),"https://facts.test").rawToken(),"https://facts.test").scopes()).contains(dev.genericpayments.platform.customers.domain.CustomerSessionScope.SUBSCRIPTION_MANAGE);
        adminWrite(path,"grace",body.replace("false,\"grace_days\":3","true,\"grace_days\":7")).andExpect(status().isOk()).andExpect(jsonPath("$.grace_days").value(7));
        adminWrite(path,"grace",body.replace("false,\"grace_days\":3","true,\"grace_days\":7")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        assertThat(policies.load(site).version()).isEqualTo(2);
    }
    @Test void adminRoutesRetainSessionCsrfAndExplicitSite() throws Exception {
        var made=start();String path="/api/v1/admin/subscriptions/"+made.subscriptionId()+"/cancellation?site_id="+site.value();
        admin.mvc.perform(post(path).cookie(admin.session).contentType("application/json").content("{\"mode\":\"PERIOD_END\"}")).andExpect(status().isForbidden());
        admin.getJson("/api/v1/admin/subscriptions?site_id="+site.value()).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        admin.getJson("/api/v1/admin/subscriptions").andExpect(status().isBadRequest());
    }
    @Test void concurrentDisableAndCreateCannotCommitAnUnmanageableSubscription() throws Exception {
        String path="/api/v1/admin/sites/"+site.value()+"/subscription-policy";
        String disable="{\"version\":1,\"enabled\":false,\"grace_days\":3,\"allow_immediate_cancellation\":false,\"portal_enabled\":false,\"defer_upgrades\":false,\"portal_return_urls\":[]}";
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new java.util.concurrent.CountDownLatch(1);
            var creation=pool.submit(()->{gate.await();return write(BASE,"racing-create",createBody()).andReturn().getResponse().getStatus();});
            var disabling=pool.submit(()->{gate.await();return adminWrite(path,"racing-disable",disable).andReturn().getResponse().getStatus();});
            gate.countDown();int createStatus=creation.get(30,java.util.concurrent.TimeUnit.SECONDS),disableStatus=disabling.get(30,java.util.concurrent.TimeUnit.SECONDS);
            if(createStatus==202){assertThat(disableStatus).isEqualTo(409);assertThat(policies.load(site).enabled()).isTrue();assertThat(count("subscriptions")).isEqualTo(1);}
            else {assertThat(createStatus).isIn(401,409);assertThat(disableStatus).isEqualTo(200);assertThat(policies.load(site).enabled()).isFalse();assertThat(count("subscriptions")).isZero();}
        }
    }
}
