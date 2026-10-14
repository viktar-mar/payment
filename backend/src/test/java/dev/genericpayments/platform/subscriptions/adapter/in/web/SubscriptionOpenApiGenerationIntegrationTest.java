package dev.genericpayments.platform.subscriptions.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt-in generator runner. The backend and database belong only to this test context. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.address=127.0.0.1")
@ActiveProfiles("local")
@EnabledIfSystemProperty(named="subscription.openapi.generate",matches="true")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SubscriptionOpenApiGenerationIntegrationTest extends PostgresIntegrationTest {
    private static Path ownedIssuerKeys;
    private static Thread cleanupHook;

    @DynamicPropertySource static void localIssuer(DynamicPropertyRegistry properties) throws Exception {
        ownedIssuerKeys=Files.createTempFile("task13-owned-issuer-", ".json",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            var keys=new JWKSet(List.of(new RSAKeyGenerator(2048).keyID("local-fixture-rs").generate(),
                    new ECKeyGenerator(Curve.P_256).keyID("local-fixture-es").generate()));
            Files.writeString(ownedIssuerKeys,keys.toString(false));
            cleanupHook=new Thread(SubscriptionOpenApiGenerationIntegrationTest::deleteOwnedKeys,"task13-owned-issuer-cleanup");
            Runtime.getRuntime().addShutdownHook(cleanupHook);
            properties.add("payment.customer-local-issuer.enabled",()->true);
            properties.add("payment.customer-local-issuer.private-jwk-set",()->ownedIssuerKeys.toString());
        } catch(Exception failure) {deleteOwnedKeys();throw failure;}
    }

    @TestConfiguration static class OwnedIssuerCleanup {
        @Bean org.springframework.beans.factory.DisposableBean cleanupOwnedIssuer() {
            return ()->{
                deleteOwnedKeys();
                if(cleanupHook!=null)Runtime.getRuntime().removeShutdownHook(cleanupHook);
                System.out.println("TASK13_OWNED_ISSUER_FIXTURE_REMOVED");
            };
        }
    }

    private static void deleteOwnedKeys() {
        if(ownedIssuerKeys==null)return;
        try {Files.deleteIfExists(ownedIssuerKeys);}
        catch(java.io.IOException failure) {throw new IllegalStateException("Owned issuer fixture cleanup failed");}
    }

    @Value("${local.server.port}") int port;
    @Test void generateAndTypecheckAgainstOwnedBackend() throws Exception {
        System.out.println("TASK13_OWNED_GENERATION_BACKEND pid="+ProcessHandle.current().pid()+" port="+port);
        for(String script:new String[]{"generate:api","typecheck"}) {
            var builder=new ProcessBuilder("pnpm",script).directory(Path.of("../frontend/admin").toFile()).inheritIO();
            builder.environment().put("BACKEND_OPENAPI_URL","http://127.0.0.1:"+port+"/v3/api-docs");
            var process=builder.start();
            try {assertThat(process.waitFor(90,TimeUnit.SECONDS)).as("bounded owned %s",script).isTrue();System.out.println("TASK13_OWNED_SCRIPT script="+script+" pid="+process.pid()+" exit="+process.exitValue());assertThat(process.exitValue()).as(script).isZero();}
            finally {if(process.isAlive())process.destroyForcibly();}
        }
        System.out.println("TASK13_OWNED_GENERATION_COMPLETE context teardown closes owned HTTP server");
    }
}
