package dev.genericpayments.platform.subscriptions.adapter.out.observability;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.junit.jupiter.api.Test;
class SubscriptionObservabilityIntegrationTest extends dev.genericpayments.platform.subscriptions.application.service.SubscriptionFactFixture {
    @Autowired ApplicationContext app;
    @Test void providerFailureDoesNotLogTokensEmailsUrlsOrProviderBodies() {
        var result=start();var service=app.getBean(dev.genericpayments.platform.subscriptions.application.service.SubscriptionReconciliationService.class);
        var worker=app.getBean(dev.genericpayments.platform.webhooks.application.service.SubscriptionInboxWorker.class);
        var root=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var captured=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();captured.start();root.addAppender(captured);
        var secrets=java.util.List.of("synthetic-bearer-token-T12","synthetic-setup-token-T12","person-t12@example.invalid","https://actions.example.invalid/t12-secret","provider-body-T12");
        dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry failing=a->{var actual=providers.forAccount(a);return (dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderPort)java.lang.reflect.Proxy.newProxyInstance(actual.getClass().getClassLoader(),new Class<?>[]{dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderPort.class},(p,m,args)->{if(m.getName().equals("retrieve"))throw new IllegalStateException(String.join(" ",secrets));try{return m.invoke(actual,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});};
        var oldService=org.springframework.test.util.ReflectionTestUtils.getField(service,"providers");var oldWorker=org.springframework.test.util.ReflectionTestUtils.getField(worker,"providers");
        try {
            root.info("Task12 controlled privacy capture active");org.springframework.test.util.ReflectionTestUtils.setField(service,"providers",failing);org.springframework.test.util.ReflectionTestUtils.setField(worker,"providers",failing);
            app.getBean(dev.genericpayments.platform.subscriptions.application.service.SubscriptionSweepWorker.class).runOnce();
            app.getBean(dev.genericpayments.platform.provider.adapter.out.fake.FakeSubscriptionQueueRelay.class).runOnce();worker.runOnce();
            assertThat(jdbc.queryForObject("select reconcile_attempts from subscriptions where id=?",Integer.class,result.subscriptionId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from provider_inbox where status<>'PROCESSED'",Integer.class)).isPositive();
            assertThat(captured.list).isNotEmpty();String logs=captured.list.stream().map(e->e.getFormattedMessage()+(e.getThrowableProxy()==null?"":ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(e.getThrowableProxy()))).collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logs).contains("Task12 controlled privacy capture active");for(String secret:secrets)assertThat(logs).doesNotContain(secret);
        }finally{root.detachAppender(captured);captured.stop();org.springframework.test.util.ReflectionTestUtils.setField(service,"providers",oldService);org.springframework.test.util.ReflectionTestUtils.setField(worker,"providers",oldWorker);}
    }
    @Test void boundedSubscriptionMetricsRecordRealCountsAndDurations() {
        var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();var telemetry=new MicrometerSubscriptionTelemetryAdapter(registry);
        telemetry.record(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTelemetryPort.Operation.RECONCILE,dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTelemetryPort.Outcome.UNKNOWN);
        telemetry.observeUnknownAge(java.time.Duration.ofSeconds(45));telemetry.observeInboxLag(java.time.Duration.ofSeconds(12));
        assertThat(registry.getMeters()).hasSize(3);
        assertThat(registry.get("subscription.operations").counter().count()).isEqualTo(1);
        assertThat(registry.get("subscription.unknown.age").timer().totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(45);
        assertThat(registry.get("subscription.inbox.lag").timer().totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(12);
        assertThat(registry.getMeters()).allSatisfy(m->assertThat(m.getId().getTags()).allSatisfy(t->assertThat(java.util.Set.of("operation","outcome")).contains(t.getKey())));
    }
}
