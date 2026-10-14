package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.model.VerifiedIdentityEvidence;
import dev.genericpayments.platform.customers.application.port.out.IdentityProviderConfigurationStore;
import dev.genericpayments.platform.customers.application.port.out.IdentityTokenVerifierPort;
import dev.genericpayments.platform.customers.application.port.out.KeyedDigestPort;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Import(AuthenticationServiceIntegrationSupport.VerifierConfiguration.class)
abstract class AuthenticationServiceIntegrationSupport extends PostgresIntegrationTest {
    static final String SITE_KEY="auth-site";
    static final String ORIGIN="https://shop.example.test";
    static final String OIDC_PROVIDER="oidc-provider";
    static final String SITE_PROVIDER="site-provider";
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityProviderConfigurationStore providers;
    @Autowired KeyedDigestPort digests;
    @Autowired ControllableIdentityTokenVerifier verifier;
    SiteId siteId;

    @BeforeEach void prepareAuthenticationFixture() {
        verifier.reset();
        clearDatabase();
        siteId=new SiteId(UUID.randomUUID());
        UUID account=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'auth-account','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        jdbc.update("insert into sites values (?,?, 'Authentication',?,'ACTIVE','AUTOMATIC',now(),now(),0)",siteId.value(),SITE_KEY,account);
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,?)",siteId.value(),ORIGIN);
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'OIDC_TOKEN'),(?,'SITE_TOKEN')",siteId.value(),siteId.value());
        providers.save(IdentityProviderConfiguration.create(siteId,OIDC_PROVIDER,
            new IdentityProviderConfiguration.OidcTokenConfiguration("https://issuer.example.test",Set.of("customer"),
                new JwksSource.Remote(URI.create("https://keys.example.test/jwks")),Set.of("RS256"),
                new IdentityProviderConfiguration.VerifiedEmailClaimMapping("email","email_verified"))));
        providers.save(IdentityProviderConfiguration.create(siteId,SITE_PROVIDER,
            new IdentityProviderConfiguration.SiteTokenConfiguration("https://site.example.test",Set.of("customer"),
                new JwksSource.Remote(URI.create("https://site.example.test/jwks")),Set.of("RS256"))));
    }

    static PaymentApplicationException authenticationFailure() {
        return new PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed");
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class VerifierConfiguration {
        @Bean @Primary ControllableIdentityTokenVerifier boundedAuthenticationVerifier() {
            return new ControllableIdentityTokenVerifier();
        }
    }

    static final class ControllableIdentityTokenVerifier implements IdentityTokenVerifierPort {
        private final AtomicInteger oidcCalls=new AtomicInteger();
        private final AtomicInteger siteCalls=new AtomicInteger();
        private final AtomicReference<Block> nextOidcBlock=new AtomicReference<>();
        private final AtomicReference<Block> nextSiteBlock=new AtomicReference<>();
        private final AtomicReference<Long> nextSiteExpiryOffsetSeconds=new AtomicReference<>();

        @Override public VerifiedIdentityEvidence verifyOidc(IdentityProviderConfiguration configuration,String token,String nonce,Instant now) {
            outsideTransaction(); oidcCalls.incrementAndGet(); await(nextOidcBlock.getAndSet(null));
            if("bad-signature".equals(token)) throw authenticationFailure();
            String[] fields=parts(token,"oidc");
            if(!fields[3].equals(nonce)) throw authenticationFailure();
            Optional<String> email="-".equals(fields[4])?Optional.empty():Optional.of(fields[4]);
            return new VerifiedIdentityEvidence(fields[1],configuration.providerKey(),email,Optional.of(fields[2]),now.minusSeconds(1),now.plusSeconds(120));
        }
        @Override public VerifiedIdentityEvidence verifySiteToken(IdentityProviderConfiguration configuration,String token,Instant now) {
            outsideTransaction(); siteCalls.incrementAndGet(); await(nextSiteBlock.getAndSet(null));
            if("bad-signature".equals(token)) throw authenticationFailure();
            String[] fields=parts(token,"site");
            Long offset=nextSiteExpiryOffsetSeconds.getAndSet(null);
            return new VerifiedIdentityEvidence(fields[1],configuration.providerKey(),Optional.empty(),Optional.of(fields[2]),now.minusSeconds(1),
                now.plusSeconds(offset==null?120:offset));
        }
        void reset() {
            oidcCalls.set(0); siteCalls.set(0); nextOidcBlock.set(null); nextSiteBlock.set(null); nextSiteExpiryOffsetSeconds.set(null);
        }
        int oidcCalls() { return oidcCalls.get(); }
        int siteCalls() { return siteCalls.get(); }
        void blockNextOidc(CountDownLatch entered,CountDownLatch release) { nextOidcBlock.set(new Block(entered,release)); }
        void blockNextSite(CountDownLatch entered,CountDownLatch release) { nextSiteBlock.set(new Block(entered,release)); }
        void nextSiteExpiryOffsetSeconds(long seconds) { nextSiteExpiryOffsetSeconds.set(seconds); }
        private static String[] parts(String token,String prefix) {
            String[] fields=token.split(":",-1);
            int expected="oidc".equals(prefix)?5:3;
            if(fields.length!=expected || !prefix.equals(fields[0])) throw authenticationFailure();
            return fields;
        }
        private static void outsideTransaction() {
            if(TransactionSynchronizationManager.isActualTransactionActive()) throw new AssertionError("JOSE verification ran in a database transaction");
        }
        private static void await(Block block) {
            if(block==null) return;
            block.entered().countDown();
            try { block.release().await(); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw authenticationFailure(); }
        }
        private record Block(CountDownLatch entered,CountDownLatch release) {}
    }
}
