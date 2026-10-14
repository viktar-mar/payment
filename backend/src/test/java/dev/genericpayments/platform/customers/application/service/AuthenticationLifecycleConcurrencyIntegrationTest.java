package dev.genericpayments.platform.customers.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.customers.adapter.out.persistence.CustomerPersistenceAdapter;
import dev.genericpayments.platform.customers.application.model.CustomerAuthenticationResult;
import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.application.port.out.IdentityProviderConfigurationStore;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.adapter.out.persistence.SitePersistenceAdapter;
import dev.genericpayments.platform.sites.application.port.out.*;
import dev.genericpayments.platform.sites.domain.Site;
import java.net.URI;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(AuthenticationLifecycleConcurrencyIntegrationTest.GateConfiguration.class)
class AuthenticationLifecycleConcurrencyIntegrationTest extends AuthenticationServiceIntegrationSupport {
    @Autowired BeginOidcAuthenticationUseCase begin;
    @Autowired ExchangeOidcTokenUseCase oidc;
    @Autowired ExchangeSiteTokenUseCase siteTokens;
    @Autowired LoadSitePort sites;
    @Autowired GatedSaveSitePort siteMutations;
    @Autowired GatedProviderStore providerGate;
    @Autowired PlatformTransactionManager transactionManager;

    @Test void providerMutationCommittedDuringVerificationIsObservedBeforeConsumption() throws Exception {
        var started=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        verifier.blockNextOidc(entered,release);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var outcome=executor.submit(() -> capture(() -> oidc.exchange(oidcCommand(started))));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            providers.save(providers.find(siteId,OIDC_PROVIDER).orElseThrow().disable());
            release.countDown();
            assertUniformFailure(outcome.get(5,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("select consumed_at is null from oidc_authentication_attempts where id=?",Boolean.class,started.attemptId())).isTrue();
    }

    @Test void siteDisableCommittedDuringVerificationIsObservedBeforeConsumption() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        verifier.blockNextSite(entered,release);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var outcome=executor.submit(() -> capture(() -> siteTokens.exchange(siteCommand("disabled-gap-jti"))));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            siteMutations.save(sites.findByKey(dev.genericpayments.platform.sites.domain.SiteKey.of(SITE_KEY)).orElseThrow().disable());
            release.countDown();
            assertUniformFailure(outcome.get(5,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("select count(*) from consumed_site_token_jtis",Integer.class)).isZero();
    }

    @Test void originRemovalCommittedDuringVerificationIsObservedBeforeConsumption() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        verifier.blockNextSite(entered,release);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var outcome=executor.submit(() -> capture(() -> siteTokens.exchange(siteCommand("origin-gap-jti"))));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            Site current=sites.findByKey(dev.genericpayments.platform.sites.domain.SiteKey.of(SITE_KEY)).orElseThrow();
            siteMutations.save(new Site(current.id(),current.key(),current.displayName(),current.paymentAccountId(),current.status(),
                Set.of(URI.create("https://replacement.example.test")),current.authenticationModes(),current.paymentMethodPolicy()));
            release.countDown();
            assertUniformFailure(outcome.get(5,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("select count(*) from consumed_site_token_jtis",Integer.class)).isZero();
    }

    @Test void postgresLockTimeoutProvesAuthenticationHoldsTheSiteRowUntilItsDecisionCommits() throws Exception {
        var transactionEnteredProviderLock=new CountDownLatch(1); var releaseProviderLock=new CountDownLatch(1);
        providerGate.blockNextFindForUpdate(transactionEnteredProviderLock,releaseProviderLock);
        Site current=sites.findByKey(dev.genericpayments.platform.sites.domain.SiteKey.of(SITE_KEY)).orElseThrow();

        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var authentication=executor.submit(() -> siteTokens.exchange(siteCommand("serialized-jti")));
            assertThat(transactionEnteredProviderLock.await(5,TimeUnit.SECONDS)).isTrue();
            var blockedMutation=executor.submit(() -> catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.execute("set local lock_timeout='250ms'");
                siteMutations.save(current.disable());
            })));
            try {
                Throwable failure=blockedMutation.get(5,TimeUnit.SECONDS);
                assertThat(failure).isNotNull();
                while(failure.getCause()!=null) failure=failure.getCause();
                assertThat(failure).isInstanceOf(SQLException.class);
                assertThat(((SQLException)failure).getSQLState()).isEqualTo("55P03");
            } finally {
                releaseProviderLock.countDown();
            }
            assertThat(authentication.get(5,TimeUnit.SECONDS)).isInstanceOf(CustomerAuthenticationResult.AuthenticatedCustomer.class);
            assertThat(siteMutations.save(current.disable()).status()).isEqualTo(dev.genericpayments.platform.sites.domain.SiteStatus.DISABLED);
        }
    }

    private ExchangeOidcTokenUseCase.Command oidcCommand(BeginOidcAuthenticationUseCase.Result started) {
        return new ExchangeOidcTokenUseCase.Command(started.attemptId(),SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE,
            started.nonce(),"oidc:lifecycle-subject:lifecycle-jti:"+started.nonce()+":-");
    }
    private static ExchangeSiteTokenUseCase.Command siteCommand(String jti) {
        return new ExchangeSiteTokenUseCase.Command(SITE_KEY,SITE_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE,"site:lifecycle-subject:"+jti);
    }
    private static Object capture(Callable<Object> call) {
        try { return call.call(); } catch(Exception failure) { return failure; }
    }
    private static void assertUniformFailure(Object outcome) {
        assertThat(outcome).isInstanceOfSatisfying(PaymentApplicationException.class,error -> {
            assertThat(error.code()).isEqualTo("AUTHENTICATION_FAILED");
            assertThat(error.status()).isEqualTo(401);
            assertThat(error).hasMessage("Authentication failed").hasNoCause();
        });
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class GateConfiguration {
        @Bean @Primary GatedProviderStore gatedProviderStore(CustomerPersistenceAdapter delegate) { return new GatedProviderStore(delegate); }
        @Bean @Primary GatedSaveSitePort gatedSaveSitePort(SitePersistenceAdapter delegate) { return new GatedSaveSitePort(delegate); }
    }

    static final class GatedProviderStore implements IdentityProviderConfigurationStore {
        private final IdentityProviderConfigurationStore delegate;
        private final AtomicReference<Block> nextLock=new AtomicReference<>();
        GatedProviderStore(IdentityProviderConfigurationStore delegate) { this.delegate=delegate; }
        void blockNextFindForUpdate(CountDownLatch entered,CountDownLatch release) { nextLock.set(new Block(entered,release)); }
        @Override public Optional<IdentityProviderConfiguration> find(dev.genericpayments.platform.sites.domain.SiteId site,String key) { return delegate.find(site,key); }
        @Override public Optional<IdentityProviderConfiguration> findForUpdate(dev.genericpayments.platform.sites.domain.SiteId site,String key) {
            Block block=nextLock.getAndSet(null);
            if(block!=null) { block.entered().countDown(); await(block.release()); }
            return delegate.findForUpdate(site,key);
        }
        @Override public List<IdentityProviderConfiguration> list(dev.genericpayments.platform.sites.domain.SiteId site) { return delegate.list(site); }
        @Override public IdentityProviderConfiguration save(IdentityProviderConfiguration value) { return delegate.save(value); }
        @Override public IdentityProviderConfiguration save(IdentityProviderConfiguration value,PublicJwkSet keys) { return delegate.save(value,keys); }
        @Override public Optional<PublicJwkSet> findPublicJwkSet(dev.genericpayments.platform.sites.domain.SiteId site,String key) { return delegate.findPublicJwkSet(site,key); }
        @Override public Optional<StoredConfiguration> findById(dev.genericpayments.platform.sites.domain.SiteId site,UUID id) { return delegate.findById(site,id); }
        @Override public List<StoredConfiguration> listStored(dev.genericpayments.platform.sites.domain.SiteId site) { return delegate.listStored(site); }
        @Override public StoredConfiguration saveStored(IdentityProviderConfiguration value) { return delegate.saveStored(value); }
        @Override public StoredConfiguration saveStored(IdentityProviderConfiguration value,PublicJwkSet keys) { return delegate.saveStored(value,keys); }
        private record Block(CountDownLatch entered,CountDownLatch release) {}
    }

    static final class GatedSaveSitePort implements SaveSitePort {
        private final SaveSitePort delegate;
        private final AtomicReference<CountDownLatch> nextSave=new AtomicReference<>();
        GatedSaveSitePort(SaveSitePort delegate) { this.delegate=delegate; }
        void observeNextSave(CountDownLatch entered) { nextSave.set(entered); }
        @Override public Site save(Site site) {
            CountDownLatch entered=nextSave.getAndSet(null); if(entered!=null) entered.countDown();
            return delegate.save(site);
        }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
}
