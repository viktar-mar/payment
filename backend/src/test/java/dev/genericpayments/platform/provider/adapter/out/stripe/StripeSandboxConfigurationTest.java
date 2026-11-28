package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.in.configuration.StripeSandboxConfiguration;
import dev.genericpayments.platform.customers.adapter.in.localfixture.LocalCustomerIssuerGuard;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;

class StripeSandboxConfigurationTest {
    static final String SECRET = "sk_test_" + "A".repeat(32);
    static final String PUBLIC = "pk_test_" + "B".repeat(32);
    static final String WEBHOOK = "whsec_" + "C".repeat(32);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(StripeSandboxConfiguration.class);

    @Test void billingActionsAreAbsentSafeButEnabledConfigurationMustBeValid(){
        runner.withPropertyValues("PAYMENT_STRIPE_BILLING_ACTIONS_ENABLED=true","PAYMENT_STRIPE_BILLING_ACTIONS_KEY_V1=secret-canary-invalid").run(c->{assertThat(c).hasNotFailed().hasSingleBean(dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingGrantTokens.class);assertThat(c.getBean(dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingGrantTokens.class).enabled()).isFalse();});
        enabled("test").withPropertyValues("PAYMENT_STRIPE_BILLING_ACTIONS_ENABLED=true").run(c->assertThat(c).hasFailed());
        enabled("test").withPropertyValues("PAYMENT_STRIPE_BILLING_ACTIONS_ENABLED=true","PAYMENT_STRIPE_BILLING_ACTIONS_ACTIVE_KEY_VERSION=v1","PAYMENT_STRIPE_BILLING_ACTIONS_KEY_VERSIONS=v1","PAYMENT_STRIPE_BILLING_ACTIONS_KEY_V1=secret-canary-invalid").run(c->{assertThat(c).hasFailed();assertThat(org.assertj.core.util.Throwables.getStackTrace(c.getStartupFailure())).doesNotContain("secret-canary-invalid");});
    }
    @Test void disabledWorksWithoutKeysAndOnlyFakeVerificationIsAllowed() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StripeSandboxProperties.class).enabled()).isFalse();
            var verifier = context.getBean(PaymentAccountVerificationPort.class);
            verifier.verify(account(PaymentProviderType.FAKE, PaymentAccountMode.SANDBOX, "arbitrary"));
            assertThatThrownBy(() -> verifier.verify(account(PaymentProviderType.STRIPE,
                PaymentAccountMode.SANDBOX, "stripe-sandbox-default"))).isInstanceOf(DomainValidationException.class);
        });
    }

    @ParameterizedTest @ValueSource(strings = {"local", "test", "local,stripe-sandbox", "test,stripe-sandbox"})
    void allowedProfilesBindSandboxCredentials(String profiles) {
        enabled(profiles).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StripeCredentialResolver.class).resolve(
                account(PaymentProviderType.STRIPE, PaymentAccountMode.SANDBOX, "stripe-sandbox-default"))
                .secretKey()).isEqualTo(SECRET);
        });
    }

    @ParameterizedTest @ValueSource(strings = {"", "stripe-sandbox", "prod", "production", "local,prod", "test,production", "local,staging"})
    void rejectsProfilesOutsideLocalAndTest(String profiles) {
        enabled(profiles).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest @ValueSource(strings = {"secret-key", "publishable-key", "webhook-secret"})
    void enabledRequiresEveryKey(String field) {
        enabled("local").withPropertyValues("payment.stripe.sandbox." + field + "=")
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest @ValueSource(strings = {"sk_live_", "rk_live_", "pk_live_"})
    void rejectsEachLiveKeyPrefixWithoutLeakingIt(String prefix) {
        String rejected = prefix + "Z".repeat(32);
        String field = prefix.startsWith("pk_") ? "publishable-key" : "secret-key";
        enabled("test").withPropertyValues("payment.stripe.sandbox." + field + "=" + rejected)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(org.assertj.core.util.Throwables.getStackTrace(context.getStartupFailure())).doesNotContain(rejected);
            });
    }

    @ParameterizedTest @ValueSource(strings = {"sk_test_REPLACE_ME", "sk_test_your_key", "sk_test_...", "wrong", "sk_test_ A", "sk_test_A "})
    void rejectsMalformedAndExampleKeysWithoutLeakingThem(String rejected) {
        enabled("local").withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("invalid-secret", java.util.Map.of("payment.stripe.sandbox.secret-key", rejected))))
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(org.assertj.core.util.Throwables.getStackTrace(context.getStartupFailure())).doesNotContain(rejected);
            });
    }

    @Test void fixedAliasAndSandboxModeCannotBeBypassed() {
        enabled("local").run(context -> {
            var resolver = context.getBean(StripeCredentialResolver.class);
            for (String alias : new String[]{"env:OTHER", "file:/tmp/key", "https://example.invalid", "unknown"}) {
                assertThatThrownBy(() -> resolver.resolve(account(PaymentProviderType.STRIPE,
                    PaymentAccountMode.SANDBOX, alias))).isInstanceOf(DomainValidationException.class)
                    .hasMessageNotContaining(alias);
            }
            assertThatThrownBy(() -> resolver.resolve(account(PaymentProviderType.STRIPE,
                PaymentAccountMode.LIVE, "stripe-sandbox-default"))).isInstanceOf(DomainValidationException.class);
        });
    }

    @Test void environmentNamesBindWithoutReadingAnyEnvironmentFile() {
        runner.withPropertyValues("spring.profiles.active=test", "PAYMENT_STRIPE_SANDBOX_ENABLED=true",
            "PAYMENT_STRIPE_SANDBOX_SECRET_KEY=" + SECRET, "PAYMENT_STRIPE_SANDBOX_PUBLISHABLE_KEY=" + PUBLIC,
            "PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET=" + WEBHOOK).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(StripeSandboxProperties.class).enabled()).isTrue();
            });
    }

    @Test void publicComposeSandboxProfilesAdmitRealLocalIssuerGuardAndStripeConfiguration() throws IOException {
        var yaml = new YamlPropertySourceLoader();
        var base = yaml.load("public-compose-base", new FileSystemResource("../infrastructure/compose.yaml")).getFirst();
        var overlay = yaml.load("public-compose-sandbox", new FileSystemResource("../infrastructure/compose.stripe-sandbox.yaml")).getFirst();
        String prefix = "services.backend.environment.";
        assertThat(base.getProperty(prefix + "PAYMENT_CUSTOMER_LOCAL_ISSUER_ENABLED")).isEqualTo("true");
        assertThat(overlay.getProperty(prefix + "PAYMENT_STRIPE_SANDBOX_ENABLED")).isEqualTo("true");
        runner.withUserConfiguration(LocalCustomerIssuerGuard.class)
            .withPropertyValues("spring.profiles.active=" + overlay.getProperty(prefix + "SPRING_PROFILES_ACTIVE"),
                "PAYMENT_CUSTOMER_LOCAL_ISSUER_ENABLED=" + base.getProperty(prefix + "PAYMENT_CUSTOMER_LOCAL_ISSUER_ENABLED"),
                "PAYMENT_STRIPE_SANDBOX_ENABLED=" + overlay.getProperty(prefix + "PAYMENT_STRIPE_SANDBOX_ENABLED"),
                "PAYMENT_STRIPE_SANDBOX_SECRET_KEY=" + SECRET,
                "PAYMENT_STRIPE_SANDBOX_PUBLISHABLE_KEY=" + PUBLIC,
                "PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET=" + WEBHOOK)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(LocalCustomerIssuerGuard.class);
                assertThat(context.getBean(StripeSandboxProperties.class).enabled()).isTrue();
                assertThat(context.getBean(StripeCredentialResolver.class).resolve(
                    account(PaymentProviderType.STRIPE, PaymentAccountMode.SANDBOX, "stripe-sandbox-default"))
                    .secretKey()).isEqualTo(SECRET);
            });
    }

    @Test void credentialsCannotLeakThroughDiagnostics() {
        var credentials = new StripeCredentials(SECRET, PUBLIC, WEBHOOK);
        assertThat(credentials.toString()).isEqualTo("StripeCredentials[redacted]");
        assertThat(new StripeSandboxProperties(true, SECRET, PUBLIC, WEBHOOK).toString())
            .isEqualTo("StripeSandboxProperties[redacted]");
    }

    @Test void restrictedTestKeyCanBeBoundWithoutClaimingItsPermissionsAreVerified() {
        enabled("local").withPropertyValues("payment.stripe.sandbox.secret-key=rk_test_" + "D".repeat(32))
            .run(context -> assertThat(context).hasNotFailed());
    }

    private ApplicationContextRunner enabled(String profiles) {
        return runner.withPropertyValues("spring.profiles.active=" + profiles,
            "payment.stripe.sandbox.enabled=true", "payment.stripe.sandbox.secret-key=" + SECRET,
            "payment.stripe.sandbox.publishable-key=" + PUBLIC, "payment.stripe.sandbox.webhook-secret=" + WEBHOOK);
    }

    static PaymentAccount account(PaymentProviderType provider, PaymentAccountMode mode, String alias) {
        return PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()), "stripe-account", provider,
            mode, "acct_expected", alias);
    }
}
