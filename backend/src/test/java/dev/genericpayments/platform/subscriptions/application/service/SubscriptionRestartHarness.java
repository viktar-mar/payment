package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationContext;
public final class SubscriptionRestartHarness {
    public static long run(ApplicationContext app,Instant at,String mode,UUID operation)throws Exception {
        var database=app.getBean(org.testcontainers.containers.PostgreSQLContainer.class);
        var properties=new Properties();properties.setProperty("spring.datasource.url",database.getJdbcUrl());properties.setProperty("spring.datasource.username",database.getUsername());properties.setProperty("spring.datasource.password",database.getPassword());
        properties.setProperty("server.port","0");properties.setProperty("spring.main.banner-mode","off");properties.setProperty("logging.level.root","ERROR");
        properties.setProperty("payment.telemetry.site-keys[0]","facts");
        properties.setProperty("payment.telemetry.site-keys[1]","subscription-acceptance");
        properties.setProperty("payment.subscriptions.scheduling-enabled","false");properties.setProperty("payment.inbox.scheduling-enabled","false");properties.setProperty("payment.callbacks.scheduling-enabled","false");
        properties.setProperty("test.at",at.toString());properties.setProperty("test.mode",mode);if(operation!=null)properties.setProperty("test.operation",operation.toString());
        var log=java.nio.file.Files.createTempFile(java.nio.file.Path.of("target"),"task12-restart-",".log");
        var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),SubscriptionRestartProcess.class.getName()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            try(var output=process.getOutputStream()){properties.store(output,null);}
            assertThat(process.waitFor(55,TimeUnit.SECONDS)).as("test-owned restarted JVM finished; log %s",log).isTrue();
            assertThat(process.exitValue()).as("test-owned restarted JVM exit; log %s",log).isZero();
            assertThat(java.nio.file.Files.readString(log)).contains("TEST_RESTART_COMPLETE:"+process.pid());return process.pid();
        } finally {if(process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
    }
}
