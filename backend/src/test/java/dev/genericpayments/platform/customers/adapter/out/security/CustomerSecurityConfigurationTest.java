package dev.genericpayments.platform.customers.adapter.out.security;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.customers.adapter.in.configuration.CustomerSecurityConfiguration;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.IdentityProviderConfiguration;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CustomerSecurityConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner()
        .withUserConfiguration(CustomerSecurityConfiguration.class)
        .withBean(IdentityProviderConfigurationStore.class,EmptyIdentityProviderStore::new)
        .withBean(Clock.class,Clock::systemUTC);
    @Test void requiresExplicitKeyMaterial() {
        runner.run(context -> assertThat(context).hasFailed());
    }
    @Test void bindsVersionedKnownKeysAndExposesSecurityPorts() {
        runner.withPropertyValues("payment.customer-security.active-key-version=v1",
            "payment.customer-security.known-keys.v1="+HmacSha256KeyedDigestAdapterTest.KEY)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(KeyedDigestPort.class).hasSingleBean(IdentityTokenVerifierPort.class);
                assertThat(context.getBean(KeyedDigestPort.class).digest(KeyedDigestPort.Purpose.EMAIL_LOOKUP,"fixture-input").keyVersion()).isEqualTo("v1");
            });
    }
    @Test void rejectsDigestVersionsThatTheDatabaseCannotStore() {
        runner.withPropertyValues("payment.customer-security.active-key-version=test-v1",
            "payment.customer-security.known-keys.test-v1="+HmacSha256KeyedDigestAdapterTest.KEY)
            .run(context -> assertThat(context).hasFailed());
    }
    private static final class EmptyIdentityProviderStore implements IdentityProviderConfigurationStore {
        @Override public Optional<IdentityProviderConfiguration> find(SiteId siteId,String providerKey) { return Optional.empty(); }
        @Override public Optional<IdentityProviderConfiguration> findForUpdate(SiteId siteId,String providerKey) { return Optional.empty(); }
        @Override public List<IdentityProviderConfiguration> list(SiteId siteId) { return List.of(); }
        @Override public IdentityProviderConfiguration save(IdentityProviderConfiguration configuration) { return configuration; }
        @Override public IdentityProviderConfiguration save(IdentityProviderConfiguration configuration,PublicJwkSet publicJwkSet) { return configuration; }
        @Override public Optional<PublicJwkSet> findPublicJwkSet(SiteId siteId,String providerKey) { return Optional.empty(); }
        @Override public Optional<StoredConfiguration> findById(SiteId siteId,java.util.UUID providerId) { return Optional.empty(); }
        @Override public List<StoredConfiguration> listStored(SiteId siteId) { return List.of(); }
        @Override public StoredConfiguration saveStored(IdentityProviderConfiguration configuration) { throw new UnsupportedOperationException(); }
        @Override public StoredConfiguration saveStored(IdentityProviderConfiguration configuration,PublicJwkSet publicJwkSet) { throw new UnsupportedOperationException(); }
    }
}
