package dev.genericpayments.platform.support;

import com.google.gson.JsonParser;
import com.stripe.StripeClient;
import com.sun.net.httpserver.HttpServer;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentials;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackPayloadPort;
import dev.genericpayments.platform.webhooks.application.port.out.OutboxPort;
import dev.genericpayments.platform.webhooks.application.service.CallbackDeliveryService;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

import static org.assertj.core.api.Assertions.assertThat;

/** A single opt-in Sandbox case in a private PostgreSQL cluster created by the launcher. */
@org.junit.jupiter.api.extension.ExtendWith(NativeFailureBoundary.class)
@ActiveProfiles("local")
@SpringBootTest
public abstract class NativeBillingSandboxSupport {
    private static final String ACCOUNT = "acct_1UPiMpELswAd8tpR";
    private static final String VERSION = "2026-09-30.endive";
    private static StripeCredentials credentials;
    private static StripeClient client;
    @Autowired private CallbackConfigurationPort configs;
    @Autowired private CallbackDeliveryService delivery;
    @Autowired private OutboxPort outbox;
    @Autowired private JdbcTemplate proofDb;
    @Autowired private CallbackPayloadPort canonicalPayload;
    private final Map<UUID, byte[]> expectedBodies = new ConcurrentHashMap<>();
    private HttpServer receiver;
    private byte[] signingKey;
    private final Map<UUID,UUID> received = new ConcurrentHashMap<>();

    protected UUID runId() { return UUID.fromString(System.getenv("PAYMENT_NATIVE_RUN_ID")); }

    @DynamicPropertySource
    static void sandboxProperties(DynamicPropertyRegistry properties) {
        int port=Integer.parseInt(System.getenv("PAYMENT_NATIVE_DB_PORT"));
        if (port<1024 || port>65535) throw new IllegalArgumentException("NATIVE_DATABASE_PORT");
        String password;
        try {
            password=java.nio.file.Files.readString(java.nio.file.Path.of(System.getenv("PAYMENT_NATIVE_DB_PASSWORD_FILE"))).strip();
            if (!password.matches("[0-9a-f]{64}")) throw new IOException();
        } catch(IOException failure) { throw new IllegalStateException("NATIVE_DATABASE_PASSWORD"); }
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:"+port+"/postgres");
        properties.add("spring.datasource.username", () -> "billing_native");
        properties.add("spring.datasource.password", () -> password);
        openCredentials();
        String runSite = "s5a-" + UUID.fromString(System.getenv("PAYMENT_NATIVE_RUN_ID")).toString().replace("-", "");
        properties.add("payment.telemetry.site-keys[0]", () -> runSite);
        properties.add("payment.telemetry.site-keys[1]", () -> "method-isolation");
        properties.add("payment.stripe.sandbox.enabled", () -> true);
        properties.add("payment.stripe.sandbox.secret-key", credentials::secretKey);
        properties.add("payment.stripe.sandbox.publishable-key", credentials::publishableKey);
        properties.add("payment.stripe.sandbox.webhook-secret", credentials::webhookSecret);
        properties.add("payment.stripe.billing-actions.enabled", () -> true);
        properties.add("payment.stripe.billing-actions.active-key-version", () -> "v1");
        properties.add("payment.stripe.billing-actions.key-versions", () -> "v1");
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String actionKey = Base64.getEncoder().encodeToString(key);
        properties.add("payment.stripe.billing-actions.keys.v1", () -> actionKey);
    }

    /** The launcher sends validated test credentials once over a private local socket. */
    private static synchronized void openCredentials() {
        if (client != null) return;
        try {
            if (!VERSION.equals(com.stripe.Stripe.API_VERSION)) throw new IllegalStateException();
            try (var socket = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                socket.connect(UnixDomainSocketAddress.of(System.getenv("PAYMENT_NATIVE_SOCKET")));
                byte[] nonce=(System.getenv("PAYMENT_NATIVE_NONCE")+"\n").getBytes(StandardCharsets.US_ASCII);
                Channels.newOutputStream(socket).write(nonce);
                var bytes=Channels.newInputStream(socket).readNBytes(2049);
                if(bytes.length==0 || bytes.length>2048) throw new IllegalStateException();
                var pair=JsonParser.parseString(new String(bytes,StandardCharsets.US_ASCII)).getAsJsonArray();
                if(pair.size()!=2) throw new IllegalStateException();
                credentials=new StripeCredentials(pair.get(0).getAsString(),pair.get(1).getAsString(),
                    "whsec_"+UUID.randomUUID().toString().replace("-",""));
                Arrays.fill(bytes,(byte)0);
            }
            var candidate=new StripeClientFactory().create(credentials);
            var account=candidate.v1().accounts().retrieveCurrent();
            if(!ACCOUNT.equals(account.getId()) || !VERSION.equals(account.getLastResponse().headers().firstValue("Stripe-Version").orElse("")))
                throw new IllegalStateException();
            client=candidate;
        } catch(Exception failure) {
            throw new IllegalStateException("NATIVE_SANDBOX_BINDING");
        }
    }

    protected StripeClient sandboxClient() { openCredentials(); return client; }

    protected void configureCallbacks(SiteId site) throws IOException {
        if(receiver!=null) throw new IllegalStateException("NATIVE_RECEIVER_ALREADY_BOUND");
        for (int port : List.of(8081, 3000, 8080)) {
            try {
                receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 8);
                break;
            } catch (BindException occupied) {
                // Each fallback is also admitted by the application callback URL policy.
            }
        }
        if (receiver == null) throw new BindException("NATIVE_CALLBACK_PORT");
        String path="/billing/"+runId();
        String endpoint="http://127.0.0.1:"+receiver.getAddress().getPort()+path;
        signingKey=Base64.getUrlDecoder().decode(configs.configure(site.value(),endpoint,true).secret());
        receiver.createContext(path,exchange -> {
            int status=403;
            try(exchange) {
                try {
                    var headers=exchange.getRequestHeaders();
                    String at=headers.getFirst("Payment-Timestamp");
                    var event=UUID.fromString(headers.getFirst("Payment-Event-Id"));
                    byte[] body=exchange.getRequestBody().readNBytes(65537);
                    var mac=Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(signingKey,"HmacSHA256"));
                    mac.update((at+".").getBytes(StandardCharsets.US_ASCII));
                    String signature="v1="+HexFormat.of().formatHex(mac.doFinal(body));
                    long timestamp = Long.parseLong(at);
                    long now = Instant.now().getEpochSecond();
                    if(!path.equals(exchange.getRequestURI().toString()) || !exchange.getRequestMethod().equals("POST") ||
                        body.length>65536 || timestamp < now - 120 || timestamp > now + 120 ||
                        !MessageDigest.isEqual(signature.getBytes(StandardCharsets.US_ASCII),
                            headers.getFirst("Payment-Signature").getBytes(StandardCharsets.US_ASCII))) throw new IllegalArgumentException();
                    var payload=JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject();
                    if(!site.value().toString().equals(payload.get("site_id").getAsString()) ||
                        !event.toString().equals(payload.get("event_id").getAsString())) throw new IllegalArgumentException();
                    var subscription=UUID.fromString(payload.get("subscription_id").getAsString());
                    var expected = expectedBodies.get(event);
                    if (expected == null || !MessageDigest.isEqual(expected,body) ||
                        received.putIfAbsent(event,subscription)!=null) throw new IllegalArgumentException();
                    status=204;
                } catch(Exception rejected) { status=403; }
                exchange.sendResponseHeaders(status,-1);
            }
        });
        receiver.start();
    }

    protected void deliverCallbacks(SiteId site, Set<UUID> subscriptions) {
        var expected=proofDb.query("""
            select e.id, coalesce(e.subscription_id, p.subscription_id) from outbox_events e
             left join payments p on p.id=e.payment_id and p.site_id=e.site_id
             where e.site_id=?
            """,
            (rs,n)->Map.entry(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),site.value());
        assertThat(expected).isNotEmpty();
        for(var claim:outbox.claimAutomatic("sandbox-"+runId(),100,Instant.now(),Duration.ofSeconds(60))) {
            assertThat(claim.siteId()).isEqualTo(site.value());
            expectedBodies.put(claim.eventId(),canonicalPayload.bytes(claim));
            delivery.deliver(claim);
        }
        assertThat(received.entrySet()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(new HashSet<>(received.values())).isEqualTo(subscriptions);
        assertThat(proofDb.queryForObject("select count(*) from outbox_events where site_id=? and status='DELIVERED'",Integer.class,site.value())).isEqualTo(expected.size());
        assertThat(proofDb.queryForObject("select count(*) from outbox_deliveries where outbox_event_id in (select id from outbox_events where site_id=?)",Integer.class,site.value())).isEqualTo(expected.size());
    }

    @AfterEach void stopReceiver() {
        if(receiver!=null) receiver.stop(0);
        if(signingKey!=null) Arrays.fill(signingKey,(byte)0);
    }
}
