package dev.genericpayments.platform.webhooks.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import dev.genericpayments.platform.PaymentPlatformApplication;
import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** An owned fresh OS process reads pre-existing future work, without metric seeding. */
class PendingOutboxRestartIntegrationTest extends CheckoutTestFixture {
    @Autowired DataSource dataSource;
    @Autowired CallbackConfigurationPort callbacks;
    @Autowired PaymentOutboxPort snapshots;
    @Autowired AppendPaymentEventPort append;
    @Autowired PlatformTransactionManager transactions;

    @Test void freshProcessRegistersPersistedFutureBacklogAndRefreshesZeroWithoutClaims() throws Exception {
        callbacks.configure(site.value(),"https://example.com/callback",true);
        var payment=save.save(payment().succeeded(INITIAL_TIME));
        var transaction=new TransactionTemplate(transactions);
        var event=transaction.execute(tx->append.append(payment.id(),sequence->
            new PaymentEvent(UUID.randomUUID(),payment.id(),sequence,site,account.id(),PaymentEventType.PAYMENT_SUCCEEDED,
                PaymentEventSource.PROVIDER_EVENT,payment.money(),null,null,INITIAL_TIME,INITIAL_TIME,"restart-gauge",null,"a".repeat(64),Map.of())));
        transaction.executeWithoutResult(tx->snapshots.snapshot(payment,event,INITIAL_TIME));
        jdbc.update("update outbox_events set next_attempt_at=? where id=?",Timestamp.from(Instant.now().plusSeconds(43200)),event.id());
        var properties=new Properties();
        try(var connection=dataSource.getConnection()) {
            properties.setProperty("spring.datasource.url",connection.getMetaData().getURL());
            properties.setProperty("spring.datasource.username",connection.getMetaData().getUserName());
        }
        properties.setProperty("spring.datasource.password","test");
        var child=new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),"-Xmx192m","-cp",
            System.getProperty("surefire.test.class.path"),ProbeApplication.class.getName()).start();
        var readings=new LinkedBlockingQueue<String>();
        var output=Thread.ofPlatform().daemon().start(()->{
            try(var reader=child.inputReader(StandardCharsets.UTF_8)) {
                reader.lines().filter(line->line.startsWith("FIX1_GAUGE=")).forEach(readings::offer);
            } catch(IOException ignored) { }
        });
        var errors=Thread.ofPlatform().daemon().start(()->{
            try(var stream=child.getErrorStream()) { stream.transferTo(OutputStream.nullOutputStream()); } catch(IOException ignored) { }
        });
        try {
            // Database credentials use private stdin, never command-line arguments or retained output.
            properties.store(child.getOutputStream(),null);child.getOutputStream().close();
            assertThat(readings.poll(55,TimeUnit.SECONDS)).as("startup in a fresh Spring process").isEqualTo("FIX1_GAUGE=startup:1.0");
            assertThat(readings.poll(10,TimeUnit.SECONDS)).as("explicit real idle worker pass").isEqualTo("FIX1_GAUGE=idle:1.0");
            assertThat(jdbc.queryForObject("select attempt_count from outbox_events where id=?",Integer.class,event.id())).isZero();
            assertThat(count("outbox_deliveries")).isZero();
            jdbc.update("update outbox_events set status='DEAD' where id=?",event.id());
            assertThat(readings.poll(10,TimeUnit.SECONDS)).as("current-state refresh after backlog clears").isEqualTo("FIX1_GAUGE=cleared:0.0");
            System.out.println("Fix1 fresh-process current-state gauge: startup=1, idle=1, cleared=0, attempts=0, deliveries=0");
        } finally {
            child.destroy();if(!child.waitFor(10,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS);}
            assertThat(child.isAlive()).as("owned verification child process cleaned up").isFalse();
            output.join(1000);errors.join(1000);
        }
    }

    public static class ProbeApplication {
        public static void main(String[] args) throws Exception {
            var properties=new Properties();properties.load(System.in);
            var defaults=new HashMap<String,Object>();properties.forEach((key,value)->defaults.put(key.toString(),value));
            defaults.put("payment.telemetry.site-keys[0]","test-site");defaults.put("spring.main.banner-mode","off");
            // Retain real web security configuration but bind only a private ephemeral loopback listener.
            defaults.put("server.address","127.0.0.1");defaults.put("server.port",0);
            try(var context=new SpringApplicationBuilder(PaymentPlatformApplication.class)
                .initializers(application->application.getEnvironment().getPropertySources()
                    .addFirst(new org.springframework.core.env.MapPropertySource("private-restart-probe",defaults))).run()) {
                var registry=context.getBean(MeterRegistry.class);var worker=context.getBean(OutboxWorker.class);
                print(registry,"startup");worker.runOnce();print(registry,"idle");
                var deadline=Instant.now().plusSeconds(10);
                while(value(registry)!=0 && Instant.now().isBefore(deadline)){Thread.sleep(100);worker.runOnce();}
                print(registry,"cleared");
            }
        }
        private static double value(MeterRegistry registry) {
            var gauge=registry.find("payment.outbox.pending").tags("site","test-site","provider","fake").gauge();
            if(gauge==null)throw new AssertionError("fresh registry did not register durable backlog");
            return gauge.value();
        }
        private static void print(MeterRegistry registry,String phase) {System.out.println("FIX1_GAUGE="+phase+":"+value(registry));}
    }
}
