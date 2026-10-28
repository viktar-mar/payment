package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.HttpClient;
import com.stripe.net.StripeRequest;
import com.stripe.net.StripeResponse;
import dev.genericpayments.platform.paymentaccounts.application.port.in.CreatePaymentAccountCommand;
import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.application.service.CreatePaymentAccountService;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.in.configuration.StripeSandboxConfiguration;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.admin.domain.*;
import dev.genericpayments.platform.shared.adapter.in.web.AdminAuditFacade;
import dev.genericpayments.platform.support.StripeHttpFixture;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;

class StripeAccountVerificationAdapterTest {
    private final StripeCredentialResolver resolver = new StripeCredentialResolver(new StripeSandboxProperties(
        true, StripeSandboxConfigurationTest.SECRET, StripeSandboxConfigurationTest.PUBLIC,
        StripeSandboxConfigurationTest.WEBHOOK));

    @Test void matchingAccountIsVerifiedByReadOnlyCurrentAccountLookup() throws Exception {
        try (var http = new StripeHttpFixture()) {
            http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_expected\",\"object\":\"account\"}");
            new StripeAccountVerificationAdapter(resolver, StripeClientFactoryTest.fixtureFactory(http.baseUri()))
                .verify(stripe());
            assertThat(http.calls()).singleElement().satisfies(call -> {
                assertThat(call.method()).isEqualTo("GET");
                assertThat(call.path()).isEqualTo("/v1/account");
                assertThat(call.form()).isEmpty();
            });
        }
    }

    @Test void fakeVerificationDoesNotCreateASdkClient() {
        var adapter = new StripeAccountVerificationAdapter(resolver, new StripeClientFactory() {
            @Override public StripeClient create(StripeCredentials credentials) {
                throw new AssertionError("Fake verification attempted SDK creation");
            }
        });
        adapter.verify(StripeSandboxConfigurationTest.account(PaymentProviderType.FAKE,
            PaymentAccountMode.SANDBOX, "any-alias"));
    }

    @Test void foreignAccountCannotBeSaved() throws Exception {
        try (var http = new StripeHttpFixture()) {
            http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_foreign\",\"object\":\"account\"}");
            var store = new Store();
            var service = new CreatePaymentAccountService(store, store,
                new StripeAccountVerificationAdapter(resolver, StripeClientFactoryTest.fixtureFactory(http.baseUri())));
            assertThatThrownBy(() -> service.create(command())).isInstanceOf(DomainValidationException.class)
                .hasMessageNotContaining("acct_foreign");
            assertThat(store.saved).isEmpty();
            assertThat(http.calls()).singleElement().extracting(StripeHttpFixture.Call::method).isEqualTo("GET");
        }
    }

    @ParameterizedTest @ValueSource(ints = {401, 403, 500})
    void upstreamErrorsAreSafeAndNeverSaveOrPost(int status) throws Exception {
        try (var http = new StripeHttpFixture()) {
            String unsafe = "unsafe-provider-message-" + StripeSandboxConfigurationTest.SECRET;
            http.enqueue("GET", "/v1/account", status,
                "{\"error\":{\"type\":\"api_error\",\"message\":\"" + unsafe + "\"}}");
            var store = new Store();
            var service = new CreatePaymentAccountService(store, store,
                new StripeAccountVerificationAdapter(resolver, StripeClientFactoryTest.fixtureFactory(http.baseUri())));
            assertThatThrownBy(() -> service.create(command())).isInstanceOf(DomainValidationException.class)
                .hasNoCause().satisfies(failure -> assertThat(org.assertj.core.util.Throwables.getStackTrace(failure))
                    .doesNotContain(unsafe, StripeSandboxConfigurationTest.SECRET));
            assertThat(store.saved).isEmpty();
            assertThat(http.calls()).singleElement().extracting(StripeHttpFixture.Call::method).isEqualTo("GET");
        }
    }

    @Test void timeoutIsSafeAndNeverSavesOrPosts() throws Exception {
        try (var http = new StripeHttpFixture()) {
            http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_expected\",\"object\":\"account\"}", Map.of(), 1000);
            var factory = new StripeClientFactory() {
                @Override protected StripeClient.StripeClientBuilder builder(StripeCredentials credentials) {
                    return super.builder(credentials).setApiBase(http.baseUri().toString()).setReadTimeout(100);
                }
            };
            var store = new Store();
            var service = new CreatePaymentAccountService(store, store, new StripeAccountVerificationAdapter(resolver, factory));
            assertThatThrownBy(() -> service.create(command())).isInstanceOf(DomainValidationException.class)
                .hasNoCause().satisfies(failure -> assertThat(org.assertj.core.util.Throwables.getStackTrace(failure))
                    .doesNotContain(StripeSandboxConfigurationTest.SECRET));
            assertThat(store.saved).isEmpty();
            assertThat(http.calls()).singleElement().extracting(StripeHttpFixture.Call::method).isEqualTo("GET");
        }
    }

    @Test void malformedProviderResponseDoesNotEscapeIntoDiagnosticsOrSaving() throws Exception {
        try (var http = new StripeHttpFixture()) {
            String unsafe = "unsafe-response-" + StripeSandboxConfigurationTest.SECRET;
            http.enqueue("GET", "/v1/account", 200, "{" + unsafe);
            var store = new Store();
            var service = new CreatePaymentAccountService(store, store,
                new StripeAccountVerificationAdapter(resolver, StripeClientFactoryTest.fixtureFactory(http.baseUri())));
            assertThatThrownBy(() -> service.create(command())).isInstanceOf(DomainValidationException.class)
                .hasNoCause().satisfies(failure -> assertThat(org.assertj.core.util.Throwables.getStackTrace(failure))
                    .doesNotContain(unsafe, StripeSandboxConfigurationTest.SECRET));
            assertThat(store.saved).isEmpty();
            assertThat(http.calls()).singleElement().extracting(StripeHttpFixture.Call::method).isEqualTo("GET");
        }
    }

    @Test void managedVerificationSuspendsOuterTransactionAndResumesItForSavingAndAudit() throws Exception {
        try (var http = new StripeHttpFixture()) {
            http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_expected\",\"object\":\"account\"}");
            var networkCalls = new AtomicInteger();
            var factory = new StripeClientFactory() {
                @Override protected StripeClient.StripeClientBuilder builder(StripeCredentials credentials) {
                    return super.builder(credentials).setApiBase(http.baseUri().toString()).setHttpClient(new HttpClient() {
                        @Override public StripeResponse request(StripeRequest request) throws StripeException {
                            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                            networkCalls.incrementAndGet();
                            return new StripeSdkHttpClient().request(request);
                        }
                    });
                }
            };
            new ApplicationContextRunner().withUserConfiguration(StripeSandboxConfiguration.class, Transactions.class)
                .withBean(StripeClientFactory.class, () -> factory)
                .withPropertyValues("spring.profiles.active=test", "payment.stripe.sandbox.enabled=true",
                    "payment.stripe.sandbox.secret-key=" + StripeSandboxConfigurationTest.SECRET,
                    "payment.stripe.sandbox.publishable-key=" + StripeSandboxConfigurationTest.PUBLIC,
                    "payment.stripe.sandbox.webhook-secret=" + StripeSandboxConfigurationTest.WEBHOOK)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var manager = context.getBean(TestTransactionManager.class);
                    var audited = new AtomicInteger();
                    var audit = new org.springframework.aop.framework.ProxyFactory(new AdminAuditFacade(entry -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                        assertThat(manager.current.get()).isSameAs(manager.initial);
                        audited.incrementAndGet();
                    }, Clock.systemUTC()));
                    audit.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                        new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
                    var auditFacade = (AdminAuditFacade) audit.getProxy();
                    var store = new Store() {
                        @Override public PaymentAccount save(PaymentAccount account) {
                            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                            assertThat(manager.current.get()).isSameAs(manager.initial);
                            return super.save(account);
                        }
                    };
                    new TransactionTemplate(manager).executeWithoutResult(status -> {
                        var service = new CreatePaymentAccountService(store, store,
                            context.getBean(PaymentAccountVerificationPort.class));
                        var account = service.create(command());
                        auditFacade.record(AuditAction.PAYMENT_ACCOUNT_CREATED, "payment_account", account.id().value(),
                            new AuthenticatedAdmin(new AdminUserId(UUID.randomUUID()), "admin",
                                new AdminSessionId(UUID.randomUUID()), Instant.now().plusSeconds(60)),
                            new MockHttpServletRequest(), Map.of("provider", "STRIPE"));
                    });
                    assertThat(store.saved).hasSize(1);
                    assertThat(networkCalls).hasValue(1);
                    assertThat(audited).hasValue(1);
                    assertThat(manager.suspensions).isEqualTo(1);
                    assertThat(manager.commits).isEqualTo(1);
                });
        }
    }

    private static PaymentAccount stripe() {
        return StripeSandboxConfigurationTest.account(PaymentProviderType.STRIPE, PaymentAccountMode.SANDBOX, "stripe-sandbox-default");
    }

    private static CreatePaymentAccountCommand command() {
        return new CreatePaymentAccountCommand("stripe-account", PaymentProviderType.STRIPE,
            PaymentAccountMode.SANDBOX, "acct_expected", "stripe-sandbox-default");
    }

    private static class Store implements LoadPaymentAccountPort, SavePaymentAccountPort {
        final List<PaymentAccount> saved = new ArrayList<>();
        public Optional<PaymentAccount> findById(PaymentAccountId id) { return Optional.empty(); }
        public Optional<PaymentAccount> findByKey(String key) { return Optional.empty(); }
        public PaymentAccount save(PaymentAccount account) { saved.add(account); return account; }
    }

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement
    static class Transactions {
        @Bean TestTransactionManager transactionManager() { return new TestTransactionManager(); }
    }

    static class TestTransactionManager extends AbstractPlatformTransactionManager {
        final ThreadLocal<Object> current = new ThreadLocal<>();
        Object initial;
        int suspensions;
        int commits;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected boolean isExistingTransaction(Object transaction) { return current.get() != null; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            initial = transaction; current.set(transaction);
        }
        @Override protected Object doSuspend(Object transaction) {
            var suspended = current.get(); current.remove(); suspensions++; return suspended;
        }
        @Override protected void doResume(Object transaction, Object suspended) { current.set(suspended); }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) {}
        @Override protected void doCleanupAfterCompletion(Object transaction) { current.remove(); }
    }
}
