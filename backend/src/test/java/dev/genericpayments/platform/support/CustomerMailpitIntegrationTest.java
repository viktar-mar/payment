package dev.genericpayments.platform.support;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** Actual SMTP and bounded, test-only Mailpit HTTP access. Never reads application OTP state. */
@ActiveProfiles("local")
public abstract class CustomerMailpitIntegrationTest extends PostgresIntegrationTest {
    private static final GenericContainer<?> MAILPIT=new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.27"))
        .withExposedPorts(1025,8025);
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @DynamicPropertySource static synchronized void mailpit(DynamicPropertyRegistry registry) {
        if(!MAILPIT.isRunning()) {
            MAILPIT.start();
            Runtime.getRuntime().addShutdownHook(new Thread(MAILPIT::stop,"task12-mailpit-cleanup"));
        }
        registry.add("spring.mail.host",MAILPIT::getHost);
        registry.add("spring.mail.port",() -> MAILPIT.getMappedPort(1025));
        registry.add("spring.mail.username",() -> "");
        registry.add("spring.mail.password",() -> "");
        registry.add("spring.mail.properties.mail.smtp.auth",() -> false);
        registry.add("spring.mail.properties.mail.smtp.starttls.enable",() -> false);
        registry.add("payment.customer-email-otp.smtp.host",MAILPIT::getHost);
        registry.add("payment.customer-email-otp.smtp.port",() -> MAILPIT.getMappedPort(1025));
        registry.add("payment.customer-email-otp.smtp.username",() -> "");
        registry.add("payment.customer-email-otp.smtp.password",() -> "");
        registry.add("payment.customer-email-otp.smtp.start-tls-enabled",() -> false);
        registry.add("payment.customer-email-otp.smtp.ssl-enabled",() -> false);
        registry.add("payment.customer-email-otp.smtp.sender-ref",() -> "local-test");
        registry.add("payment.customer-email-otp.smtp.from-address",() -> "no-reply@payment.local");
        registry.add("payment.customer-email-otp.outbox.worker-enabled",() -> false);
    }
    public static void clearMailpit() throws Exception { api("DELETE","/api/v1/messages"); }
    public static String mailpitCode(String recipient) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<deadline) {
            var messages=JSON.readTree(api("GET","/api/v1/messages")).get("messages");
            if(messages!=null) for(var message:messages) for(var to:message.get("To")) {
                if(recipient.equalsIgnoreCase(to.get("Address").asString())) {
                    var detail=JSON.readTree(api("GET","/api/v1/message/"+message.get("ID").asString()));
                    var match=Pattern.compile("(?<![0-9])[0-9]{6}(?![0-9])").matcher(detail.get("Text").asString());
                    if(match.find())return match.group();
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Mailpit did not deliver a verification message within the bounded deadline");
    }
    private static String api(String method,String path) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://"+MAILPIT.getHost()+":"+MAILPIT.getMappedPort(8025)+path))
            .timeout(Duration.ofSeconds(2)).method(method,HttpRequest.BodyPublishers.noBody()).build();
        var response=HTTP.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try(var body=response.body()) {
            byte[] bytes=body.readNBytes(262145);
            if(response.statusCode()/100!=2||bytes.length>262144)throw new AssertionError("Mailpit test API failed safely");
            return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
