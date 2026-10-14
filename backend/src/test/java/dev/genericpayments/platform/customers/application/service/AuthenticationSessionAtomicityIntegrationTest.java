package dev.genericpayments.platform.customers.application.service;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.customers.adapter.out.persistence.CustomerSessionPersistenceAdapter;
import dev.genericpayments.platform.customers.application.model.CustomerAuthenticationResult;
import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerSessionStore;
import dev.genericpayments.platform.customers.domain.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

@Import(AuthenticationSessionAtomicityIntegrationTest.SessionFailureConfiguration.class)
class AuthenticationSessionAtomicityIntegrationTest extends AuthenticationServiceIntegrationSupport {
    @Autowired BeginOidcAuthenticationUseCase begin;
    @Autowired ExchangeOidcTokenUseCase oidc;
    @Autowired ExchangeSiteTokenUseCase siteTokens;
    @Autowired FailingCustomerSessionStore sessionStore;

    @BeforeEach void resetSessionFailure() { sessionStore.reset(); }

    @Test
    void oidcSessionInsertionFailureKeepsAttemptConsumedButRollsBackTheNewCustomerGraph() {
        var started=begin.begin(new BeginOidcAuthenticationUseCase.Command(
            SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        var command=new ExchangeOidcTokenUseCase.Command(started.attemptId(),SITE_KEY,OIDC_PROVIDER,ORIGIN,
            AuthenticationIntent.AUTHENTICATE,started.nonce(),"oidc:atomic-subject:atomic-jti:"+started.nonce()+":person@example.test");
        sessionStore.failNextSave();

        assertAuthenticationFailure(() -> oidc.exchange(command));

        assertThat(jdbc.queryForObject("select count(*) from oidc_authentication_attempts where consumed_at is not null",Integer.class)).isOne();
        assertRolledBackCustomerGraph();
        assertAuthenticationFailure(() -> oidc.exchange(command));

        var retry=begin.begin(new BeginOidcAuthenticationUseCase.Command(
            SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        var result=(CustomerAuthenticationResult.AuthenticatedCustomer)oidc.exchange(new ExchangeOidcTokenUseCase.Command(
            retry.attemptId(),SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE,retry.nonce(),
            "oidc:atomic-subject:atomic-jti-2:"+retry.nonce()+":person@example.test"));
        assertSuccessfulCustomerGraph(result);
    }

    @Test
    void siteTokenSessionInsertionFailureKeepsJtiConsumedButRollsBackTheNewCustomerGraph() {
        var command=new ExchangeSiteTokenUseCase.Command(SITE_KEY,SITE_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE,
            "site:atomic-site-subject:atomic-site-jti");
        sessionStore.failNextSave();

        assertAuthenticationFailure(() -> siteTokens.exchange(command));

        assertThat(jdbc.queryForObject("select count(*) from consumed_site_token_jtis",Integer.class)).isOne();
        assertRolledBackCustomerGraph();
        assertAuthenticationFailure(() -> siteTokens.exchange(command));

        var result=(CustomerAuthenticationResult.AuthenticatedCustomer)siteTokens.exchange(
            new ExchangeSiteTokenUseCase.Command(SITE_KEY,SITE_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE,
                "site:atomic-site-subject:atomic-site-jti-2"));
        assertSuccessfulCustomerGraph(result);
    }

    private void assertRolledBackCustomerGraph() {
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer_identity_subject_aliases",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer_sessions",Integer.class)).isZero();
    }

    private void assertSuccessfulCustomerGraph(CustomerAuthenticationResult.AuthenticatedCustomer result) {
        assertThat(result.session()).isNotNull();
        assertThat(result.session().context().customerId()).isEqualTo(result.customerId());
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identity_subject_aliases",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_sessions",Integer.class)).isOne();
    }

    private static void assertAuthenticationFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(
            dev.genericpayments.platform.shared.application.PaymentApplicationException.class,error -> {
                assertThat(error.code()).isEqualTo("AUTHENTICATION_FAILED");
                assertThat(error.status()).isEqualTo(401);
                assertThat(error).hasMessage("Authentication failed").hasNoCause();
            });
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class SessionFailureConfiguration {
        @Bean @Primary FailingCustomerSessionStore failingCustomerSessionStore(CustomerSessionPersistenceAdapter delegate) {
            return new FailingCustomerSessionStore(delegate);
        }
    }

    static final class FailingCustomerSessionStore implements CustomerSessionStore {
        private final CustomerSessionStore delegate;
        private final AtomicBoolean failNextSave=new AtomicBoolean();
        FailingCustomerSessionStore(CustomerSessionStore delegate) { this.delegate=delegate; }
        void failNextSave() { failNextSave.set(true); }
        void reset() { failNextSave.set(false); }
        @Override public List<CustomerSession> findCandidates(List<dev.genericpayments.platform.customers.application.port.out.KeyedDigestPort.DigestValue> digests) {
            return delegate.findCandidates(digests);
        }
        @Override public Optional<CustomerSession> findByIdForUpdate(CustomerSessionId id) { return delegate.findByIdForUpdate(id); }
        @Override public CustomerSession save(CustomerSession session) {
            if(failNextSave.compareAndSet(true,false)) throw new IllegalStateException("injected session persistence failure");
            return delegate.save(session);
        }
    }
}
