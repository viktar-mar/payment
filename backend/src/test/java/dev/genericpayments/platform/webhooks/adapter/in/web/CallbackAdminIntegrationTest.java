package dev.genericpayments.platform.webhooks.adapter.in.web;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.genericpayments.platform.partners.PartnerTestFixture;
import dev.genericpayments.platform.support.AdminApiClient;
import dev.genericpayments.platform.admin.adapter.in.web.AdminBootstrap;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
class CallbackAdminIntegrationTest extends PartnerTestFixture {
    @Autowired WebApplicationContext context;@Autowired AdminBootstrap bootstrap;
    @Autowired CallbackConfigurationPort callbacks;@Autowired AppendPaymentEventPort journal;@Autowired PaymentOutboxPort snapshots;@Autowired PlatformTransactionManager transactions;
    AdminApiClient client;String base;
    @BeforeEach void login()throws Exception {bootstrap.initialize();client=new AdminApiClient(context);client.login();base="/api/v1/admin/sites/"+site.value()+"/callback";}
    org.springframework.test.web.servlet.ResultActions put(String url)throws Exception {return client.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base).cookie(client.session,client.xsrf).header("X-XSRF-TOKEN",client.xsrf.getValue()).contentType("application/json").content("{\"endpoint_url\":\""+url+"\",\"enabled\":true}"));}
    UUID event() {
        callbacks.configure(site.value(),"https://example.com/callback",true);var p=save.save(payment().succeeded(INITIAL_TIME));
        var e=journal.append(p.id(),seq->new PaymentEvent(UUID.randomUUID(),p.id(),seq,site,account.id(),PaymentEventType.PAYMENT_SUCCEEDED,PaymentEventSource.PROVIDER_EVENT,p.money(),null,null,INITIAL_TIME,INITIAL_TIME,"request-8",null,"a".repeat(64),Map.of()));
        new TransactionTemplate(transactions).executeWithoutResult(tx->snapshots.snapshot(p,e,INITIAL_TIME));jdbc.update("update outbox_events set status='DEAD',attempt_count=6 where id=?",e.id());return e.id();
    }
    org.springframework.test.web.servlet.ResultActions retry(UUID event,String key,String mode)throws Exception {return client.mvc.perform(post("/api/v1/admin/outbox/"+event+"/retry").cookie(client.session,client.xsrf).header("X-XSRF-TOKEN",client.xsrf.getValue()).header("Idempotency-Key",key).contentType("application/json").content("{\"mode\":\""+mode+"\"}"));}
    @Test void configurationRevealIsOneTimeAndRequiresSessionAndCsrf()throws Exception {
        client.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base).contentType("application/json").content("{}" )).andExpect(status().isUnauthorized());
        client.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base).cookie(client.session).contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        put("https://example.com/path?q=private").andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.secret").isString()).andExpect(jsonPath("$.secret_version").value(1));
        put("https://example.com/new").andExpect(status().isOk()).andExpect(jsonPath("$.secret").isEmpty());
        client.postJson(base+"/rotate-secret","{}").andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.secret_version").value(2)).andExpect(jsonPath("$.secret").isString());
        assertThat(jdbc.queryForList("select * from audit_log where action like 'SITE_CALLBACK_%'").toString()).doesNotContain("private","?q=","ciphertext","nonce");
    }
    @Test void retryReplayReturnsSameRequestAndModeConflictDoesNotInsertAgain()throws Exception {
        UUID event=event();String first=retry(event,"retry-key","SNAPSHOT").andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.event_id").value(event.toString())).andReturn().getResponse().getContentAsString();
        assertThat(retry(event,"retry-key","SNAPSHOT").andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).isEqualTo(first);
        retry(event,"retry-key","CURRENT").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(count("outbox_retry_requests")).isEqualTo(1);assertThat(count("outbox_deliveries")).isZero();
        assertThat(jdbc.queryForObject("select resource_type from idempotency_records",String.class)).isEqualTo("OUTBOX_RETRY_REQUEST");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='CALLBACK_RETRY_REQUESTED'",Integer.class)).isEqualTo(1);
    }
    @Test void missingDuplicateOrOversizedKeysAndUnknownModesAreRejected()throws Exception {
        UUID event=event();String path="/api/v1/admin/outbox/"+event+"/retry";
        client.postJson(path,"{\"mode\":\"SNAPSHOT\"}").andExpect(status().isBadRequest());
        retry(event,"a".repeat(256),"SNAPSHOT").andExpect(status().isBadRequest());retry(event,"key","AUTOMATIC").andExpect(status().isBadRequest());
        client.mvc.perform(post(path).cookie(client.session,client.xsrf).header("X-XSRF-TOKEN",client.xsrf.getValue()).header("Idempotency-Key","one","two").contentType("application/json").content("{\"mode\":\"SNAPSHOT\"}")).andExpect(status().isBadRequest());
        assertThat(count("outbox_retry_requests")).isZero();assertThat(count("idempotency_records")).isZero();
    }
    @Test void mutationAndIdempotencyRollBackWhenAuditFails()throws Exception {
        rejectWrite("audit_log","insert","new.action='SITE_CALLBACK_CONFIGURED'");put("https://example.com/callback").andExpect(status().isConflict());assertThat(count("site_callback_configs")).isZero();assertThat(count("site_callback_secret_versions")).isZero();clearWriteFailure();
        UUID event=event();rejectWrite("audit_log","insert","new.action='CALLBACK_RETRY_REQUESTED'");retry(event,"key","SNAPSHOT").andExpect(status().isConflict());assertThat(count("outbox_retry_requests")).isZero();assertThat(count("idempotency_records")).isZero();clearWriteFailure();
        rejectWrite("audit_log","insert","new.action='SITE_CALLBACK_SECRET_ROTATED'");client.postJson(base+"/rotate-secret","{}").andExpect(status().isConflict());assertThat(count("site_callback_secret_versions")).isEqualTo(1);assertThat(callbacks.find(site.value()).orElseThrow().secretVersion()).isEqualTo(1);
    }
    @Test void concurrentHttpRetriesReplayOneDurableRequestAndAudit()throws Exception {
        UUID event=event();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<String> call=()->{gate.await();return retry(event,"parallel-key","CURRENT").andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();};
            var one=executor.submit(call);var two=executor.submit(call);gate.countDown();assertThat(one.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(two.get(10,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(count("outbox_retry_requests")).isEqualTo(1);assertThat(count("idempotency_records")).isEqualTo(1);assertThat(jdbc.queryForObject("select count(*) from audit_log where action='CALLBACK_RETRY_REQUESTED'",Integer.class)).isEqualTo(1);assertThat(count("outbox_deliveries")).isZero();
    }
    @Test void retryAndRotationAlsoRequireSessionAndCsrfAndCurrentDisabledFailsAtomically()throws Exception {
        UUID event=event();String path="/api/v1/admin/outbox/"+event+"/retry";
        for(String route:List.of(path,base+"/rotate-secret")) {
            client.mvc.perform(post(route).contentType("application/json").content("{\"mode\":\"CURRENT\"}")).andExpect(status().isUnauthorized());
            client.mvc.perform(post(route).cookie(client.session).contentType("application/json").content("{\"mode\":\"CURRENT\"}")).andExpect(status().isForbidden());
        }
        callbacks.configure(site.value(),"https://example.com/disabled",false);retry(event,"disabled","CURRENT").andExpect(status().isConflict());assertThat(count("outbox_retry_requests")).isZero();assertThat(count("idempotency_records")).isZero();
        retry(event,"snapshot","SNAPSHOT").andExpect(status().isAccepted());
    }
}
