package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import dev.genericpayments.platform.support.AdminApiClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import java.util.UUID;

@TestPropertySource(properties={"payment.admin.bootstrap.username=admin","payment.admin.bootstrap.password=local-password"})
class StripeManualRecoveryHttpIntegrationTest extends StripeInboxFixture {
    @Autowired WebApplicationContext web;
    @Autowired AdminBootstrap bootstrap;
    AdminApiClient admin;
    UUID operation;
    @BeforeEach void login()throws Exception {
        bootstrap.initialize(); admin=new AdminApiClient(web); admin.login();
        operation=jdbc.queryForObject("select id from provider_operations",UUID.class);
        jdbc.update("update provider_operations set status='UNKNOWN',provider_reference=null,first_dispatched_at=now()-interval '24 hours',retry_before=now()-interval '1 hour'");
    }
    void proof(String key) {
        long created=jdbc.queryForObject("select extract(epoch from first_dispatched_at)::bigint+2 from provider_operations",Long.class);
        identity(); http.enqueue("GET","/v1/events/evt_created",200,"""
            {"id":"evt_created","object":"event","created":%d,"type":"payment_intent.created",
             "livemode":false,"api_version":"2026-09-30.endive","request":{"idempotency_key":"%s"},
             "data":{"object":{"id":"pi_owned","object":"payment_intent"}}}
            """.formatted(created,key));
        http.enqueue("GET","/v1/payment_intents/pi_owned",200,intent("requires_payment_method",null,2000));
    }
    org.springframework.test.web.servlet.ResultActions recover(String body)throws Exception {
        return admin.mvc.perform(post("/api/v1/admin/provider-operations/"+operation+"/recover")
            .cookie(admin.session,admin.xsrf).header("X-XSRF-TOKEN",admin.xsrf.getValue())
            .header("Idempotency-Key","http-original-recovery").header("X-Request-ID","http-recovery-request")
            .contentType("application/json").content(body));
    }
    String body(){return "{\"provider_reference\":\"pi_owned\",\"creation_event_id\":\"evt_created\"}";}
    @Test void delegatesOriginalProofAndAuditsAuthenticatedActorWithoutCreatingAgain()throws Exception {
        proof("prepare_"+original.id().value()); snapshot("processing",null);
        recover(body()).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        var audit=jdbc.queryForMap("select actor_id,request_id from audit_log where action='PROVIDER_OPERATION_RECOVERED'");
        assertThat(audit.get("actor_id")).isEqualTo(jdbc.queryForObject("select id::text from admin_users",String.class));
        assertThat(audit.get("request_id")).isEqualTo("http-recovery-request");
        assertThat(jdbc.queryForObject("select resource_type from idempotency_records",String.class)).isEqualTo("PROVIDER_OPERATION");
        snapshot("processing",null); recover(body()).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='PROVIDER_OPERATION_RECOVERED'",Integer.class)).isEqualTo(1);
        assertThat(http.calls().stream().filter(c->c.method().equals("POST"))).hasSize(1);
        assertThat(count("payments")).isEqualTo(1);
    }
    @Test void wrongCreationProofCannotWriteBindingOrReceipt()throws Exception {
        proof("wrong-creation-key"); recover(body()).andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("select status from provider_operations",String.class)).isEqualTo("UNKNOWN");
        assertThat(count("idempotency_records")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='PROVIDER_OPERATION_RECOVERED'",Integer.class)).isZero();
    }
    @Test void callerCannotSupplyActorOrRequestAuthority()throws Exception {
        int before=http.calls().size();
        recover(body().replace("}",",\"actor_id\":\"30000000-0000-0000-0000-000000000009\"}"))
            .andExpect(status().isBadRequest());
        assertThat(http.calls()).hasSize(before); assertThat(count("idempotency_records")).isZero();
    }
}
