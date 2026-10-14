package dev.genericpayments.platform.shared.adapter.out.observability;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.*;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.*;
import org.springframework.web.context.WebApplicationContext;

/** Catches missing correlation lifecycle, unbounded labels and missing DB readiness. */
@SpringBootTest(properties="payment.telemetry.site-keys[0]=observability")
class Stage2ObservabilityIntegrationTest extends PostgresIntegrationTest {
    @Autowired Stage2TelemetryPort telemetry;
    @Autowired MeterRegistry meters;
    @Autowired RequestIdFilter filter;
    @Autowired WebApplicationContext context;

    @Test void aggregateVocabularyUsesOnlyFiniteLabels() {
        for(var provider:Provider.values()) {
            var c=new Context("observability",provider);
            telemetry.paymentCreated(c);
            for(var from:PaymentState.values())for(var to:PaymentState.values())telemetry.paymentTransition(c,from,to);
            for(var operation:ProviderOperation.values())for(var result:ProviderResult.values())
                telemetry.providerCall(c,operation,result,Duration.ofMillis(1));
            for(var o:InboxOutcome.values())telemetry.inbox(c,o);
            telemetry.inboxLag(c,Duration.ofSeconds(1));telemetry.outboxPending(c,2);
            for(var o:OutboxOutcome.values())telemetry.outbox(c,o);
            telemetry.outboxLag(c,Duration.ofSeconds(1));telemetry.refundRequested(c);
            for(var result:RefundResult.values())telemetry.refundResult(c,result);
        }
        for(var operation:Operation.values()) {
            for(var result:ReplayState.values())telemetry.idempotencyReplay(operation,result);
            telemetry.idempotencyConflict(operation);
        }
        assertThat(meters.getMeters().stream().map(m->m.getId().getName()).filter(n->n.startsWith("payment.")).collect(java.util.stream.Collectors.toSet()))
            .containsExactlyInAnyOrder("payment.created","payment.transition","payment.provider.call","payment.provider.unknown",
                "payment.inbox.received","payment.inbox.duplicate","payment.inbox.processed","payment.inbox.failed","payment.inbox.lag",
                "payment.outbox.pending","payment.outbox.delivered","payment.outbox.retried","payment.outbox.dead","payment.outbox.lag",
                "payment.refund.requested","payment.refund.result","payment.idempotency.replay","payment.idempotency.conflict");
        meters.getMeters().stream().filter(m->m.getId().getName().startsWith("payment.")).forEach(m->{
            String family=m.getId().getName();
            var expected=switch(family) {
                case "payment.transition" -> Map.of("site",Set.of("observability"),"provider",Set.of("fake","stripe"),
                    "from",paymentStates(),"to",paymentStates());
                case "payment.provider.call" -> Map.of("site",Set.of("observability"),"provider",Set.of("fake","stripe"),
                    "operation",Set.of("prepare","confirm","refund","retrieve_payment","retrieve_refund"),
                    "result",Set.of("accepted","processing","requires_action","recoverable_decline","terminal_decline","temporarily_unavailable","unknown","succeeded","failed","cancelled"));
                case "payment.provider.unknown" -> Map.of("site",Set.of("observability"),"provider",Set.of("fake","stripe"),
                    "operation",Set.of("prepare","confirm","refund","retrieve_payment","retrieve_refund"),"result",Set.of("unknown"));
                case "payment.refund.result" -> Map.of("site",Set.of("observability"),"provider",Set.of("fake","stripe"),
                    "result",Set.of("pending","processing","succeeded","failed","unknown","cancelled"));
                case "payment.idempotency.replay" -> Map.of("operation",operations(),"result",Set.of("in_progress","completed","expired_secret"));
                case "payment.idempotency.conflict" -> Map.of("operation",operations());
                default -> Map.of("site",Set.of("observability"),"provider",Set.of("fake","stripe"));
            };
            assertThat(m.getId().getTags()).as(family).extracting(io.micrometer.core.instrument.Tag::getKey)
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
            m.getId().getTags().forEach(t->assertThat(t.getValue()).as(family+"/"+t.getKey()).isIn(expected.get(t.getKey())));
        });
        assertThat(meters.find("payment.transition").meters()).hasSize(162);
        assertThat(meters.find("payment.provider.call").meters()).hasSize(100);
        assertThat(meters.find("payment.provider.unknown").meters()).hasSize(10);
        assertThat(meters.find("payment.refund.result").meters()).hasSize(12);
        assertThat(meters.find("payment.idempotency.replay").meters()).hasSize(42);
        assertThat(meters.find("payment.idempotency.conflict").meters()).hasSize(14);
        assertThatThrownBy(()->telemetry.paymentCreated(new Context("unconfigured",Provider.FAKE))).isInstanceOf(IllegalArgumentException.class);
    }
    private static Set<String> paymentStates() {return Set.of("created","requires_payment_method","requires_action","processing","succeeded","failed","cancelled","partially_refunded","refunded");}
    private static Set<String> operations() {return Set.of("checkout_create","checkout_confirm","payment_create","handoff_issue","handoff_exchange","refund_create","reconcile","callback_retry","fake_event","campaign_create","campaign_update","campaign_status","donation_checkout_create","donation_message_redact");}
    @Test void propagatesValidRequestIdThroughAttributeResponseAndMdcThenClears() throws Exception {
        MDC.clear();var req=new MockHttpServletRequest("GET","/api/v1/public/test");req.addHeader("X-Request-Id","safe-correlation:1");
        var response=new MockHttpServletResponse();
        filter.doFilter(req,response,(r,s)->assertThat(MDC.get("request_id")).isEqualTo("safe-correlation:1"));
        assertThat(RequestIdFilter.requestId(req)).isEqualTo("safe-correlation:1");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("safe-correlation:1");assertThat(MDC.get("request_id")).isNull();
    }
    @Test void replacesUnsafeIdsAndRestoresWorkerMdcEvenOnFailure() throws Exception {
        try {
            for(String incoming:List.of("","x".repeat(65),"bad\nvalue","Bearer forbidden")) {
                MDC.put("request_id","worker-parent");var req=new MockHttpServletRequest();req.addHeader("X-Request-Id",incoming);
                var response=new MockHttpServletResponse();
                assertThatThrownBy(()->filter.doFilter(req,response,(r,s)->{
                    assertThat(MDC.get("request_id")).matches("[0-9a-f-]{36}");throw new jakarta.servlet.ServletException("safe failure");
                })).isInstanceOf(jakarta.servlet.ServletException.class);
                assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f-]{36}");
                assertThat(MDC.get("request_id")).isEqualTo("worker-parent");
            }
        } finally {MDC.clear();}
    }
    @Test void readinessIncludesDatabaseAndEmailOtpCapabilityWithoutNetworkMailProbeOrCallbackDependency() throws Exception {
        var environment=context.getEnvironment();
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
            .isEqualTo("readinessState,db,customerEmailOtpReadiness");
        assertThat(environment.getProperty("management.health.mail.enabled",Boolean.class)).isFalse();
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health/readiness"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("UP"));
    }
    @Test void onlyOneHighestPrecedenceFilterAndStructuredCorrelationPatternAreConfigured() {
        assertThat(context.getBeansOfType(RequestIdFilter.class)).hasSize(1);
        assertThat(filter).isInstanceOf(dev.genericpayments.platform.shared.adapter.in.web.RequestCorrelationFilter.class);
        assertThat(org.springframework.core.annotation.AnnotationUtils.findAnnotation(filter.getClass(),org.springframework.core.annotation.Order.class).value())
            .isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        assertThat(context.getEnvironment().getProperty("logging.pattern.correlation")).isEqualTo("[request_id=%X{request_id:-}]");
    }
}
