package dev.genericpayments.platform.customers.adapter.out.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static dev.genericpayments.platform.customers.adapter.out.security.IdentitySecurityFixtures.*;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.read.ListAppender;
import dev.genericpayments.platform.customers.application.port.out.IdentityProviderConfigurationStore;
import dev.genericpayments.platform.customers.application.port.out.IdentityProviderConfigurationStore.PublicJwkSet;
import dev.genericpayments.platform.customers.domain.JwksSource;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;

class CustomerSecurityDiagnosticsTest {
    @Test void springDecoderClientIsPhysicallyConfinedToValidatedInMemoryMaterial() {
        var memory=new ValidatedJwkRestOperations(SafeJwksClient.validateStatic(new PublicJwkSet(jwks(RSA.toPublicJWK()))));
        assertThat(memory.exchange(RequestEntity.get(ValidatedJwkRestOperations.MEMORY_URI).build(),String.class).getStatusCode().value()).isEqualTo(200);
        NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->memory.exchange(RequestEntity.get("https://127.0.0.1/forbidden").build(),String.class));
        NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->memory.exchange(RequestEntity.get(java.net.URI.create("https://validated-jwks.invalid/other")).build(),String.class));
        NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->memory.exchange(RequestEntity.post(ValidatedJwkRestOperations.MEMORY_URI).build(),String.class));
        NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->memory.exchange(RequestEntity.get(ValidatedJwkRestOperations.MEMORY_URI).build(),byte[].class));
        NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->memory.exchange(null,String.class));
        NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->memory.getForObject("https://127.0.0.1/forbidden",String.class));
    }
    @Test void errorsAndTraceLoggingNeverContainCredentialsPiiKeysOrPayloads() throws Exception {
        var logger=(Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME); Level before=logger.getLevel();
        var appender=new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender); logger.setLevel(Level.TRACE);
        var payload=jwks(RSA.toPublicJWK()); var c=claims(); c.put("email","profile@example.test"); c.put("email_verified",true);
        var raw=token(RSA,c); var bad=token(rsa("rsa-key"),c);
        try {
            var store=mock(IdentityProviderConfigurationStore.class);
            var cfg=configuration(true,new JwksSource.StaticPublicJwkSet("stored"),Set.of("RS256"));
            when(store.findPublicJwkSet(cfg.siteId(),cfg.providerKey())).thenReturn(Optional.of(new PublicJwkSet(payload)));
            var verifier=new NimbusIdentityTokenVerifierAdapter(new SafeJwksClient(store,new JwksAddressPolicy(),Clock.fixed(NOW,ZoneOffset.UTC)),new CustomerEmailNormalizer());
            assertThat(verifier.verifyOidc(cfg,raw,"fixture-nonce",NOW).toString()).isEqualTo("VerifiedIdentityEvidence[redacted]");
            NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->verifier.verifyOidc(cfg,bad,"fixture-nonce",NOW));
            NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->SafeJwksClient.validateStatic(new PublicJwkSet(jwks(RSA))));
            String logs=appender.list.stream().map(event->event.getFormattedMessage()+render(event.getThrowableProxy())).reduce("",String::concat);
            for(var sensitive:List.of(raw,bad,"fixture-subject","profile@example.test",payload,RSA.getPrivateExponent().toString(),HmacSha256KeyedDigestAdapterTest.KEY))
                assertThat(logs.contains(sensitive)).isFalse();
        } finally { logger.setLevel(before); logger.detachAppender(appender); appender.stop(); }
    }
    private static String render(IThrowableProxy failure) { return failure==null?"":ThrowableProxyUtil.asString(failure); }
}
