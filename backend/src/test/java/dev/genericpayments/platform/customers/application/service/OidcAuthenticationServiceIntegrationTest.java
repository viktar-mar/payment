package dev.genericpayments.platform.customers.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.customers.application.model.CustomerAuthenticationResult;
import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OidcAuthenticationServiceIntegrationTest extends AuthenticationServiceIntegrationSupport {
    @Autowired BeginOidcAuthenticationUseCase begin;
    @Autowired ExchangeOidcTokenUseCase exchange;

    @Test void nullIntentIsRejectedBeforeAttemptCreationVerificationOrConsumption() {
        assertFailure(() -> begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,null)));
        assertThat(jdbc.queryForObject("select count(*) from oidc_authentication_attempts",Integer.class)).isZero();

        var started=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        assertFailure(() -> exchange.exchange(command(started,token("subject","jti",started.nonce(),"-"),null,ORIGIN)));

        assertThat(verifier.oidcCalls()).isZero();
        assertThat(jdbc.queryForObject("select consumed_at is null from oidc_authentication_attempts where id=?",Boolean.class,started.attemptId())).isTrue();
    }

    @Test void expiredAttemptIsRejectedBeforeVerificationAndRemainsUnconsumed() {
        var started=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(started.expiresAt());

        assertFailure(() -> exchange.exchange(command(started,token("expired-subject","expired-jti",started.nonce(),"-"),AuthenticationIntent.AUTHENTICATE,ORIGIN)));

        assertThat(verifier.oidcCalls()).isZero();
        assertThat(jdbc.queryForObject("select consumed_at is null from oidc_authentication_attempts where id=?",Boolean.class,started.attemptId())).isTrue();
    }

    @Test void attemptExpiringWhileOidcVerificationIsBlockedIsRejectedAndRemainsUnconsumed() throws Exception {
        var started=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        var verifierEntered=new CountDownLatch(1); var releaseVerifier=new CountDownLatch(1);
        verifier.blockNextOidc(verifierEntered,releaseVerifier);

        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var outcome=executor.submit(() -> catchThrowable(() -> exchange.exchange(command(started,
                token("blocked-expiry-subject","blocked-expiry-jti",started.nonce(),"-"),AuthenticationIntent.AUTHENTICATE,ORIGIN))));
            assertThat(verifierEntered.await(5,TimeUnit.SECONDS)).isTrue();
            dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(started.expiresAt());
            releaseVerifier.countDown();
            assertThat(outcome.get(5,TimeUnit.SECONDS)).isInstanceOfSatisfying(PaymentApplicationException.class,
                OidcAuthenticationServiceIntegrationTest::assertUniform);
        }

        assertThat(verifier.oidcCalls()).isOne();
        assertThat(jdbc.queryForObject("select consumed_at is null from oidc_authentication_attempts where id=?",Boolean.class,started.attemptId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isZero();
    }

    @Test void attemptCannotCrossSiteBoundaryAndIsNotVerifiedOrConsumed() {
        var started=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        var otherSite=new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID());
        UUID account=jdbc.queryForObject("select id from payment_accounts",UUID.class);
        jdbc.update("insert into sites values (?,?, 'Other authentication',?,'ACTIVE','AUTOMATIC',now(),now(),0)",otherSite.value(),"cross-auth-site",account);
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,?)",otherSite.value(),ORIGIN);
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'OIDC_TOKEN')",otherSite.value());
        providers.save(IdentityProviderConfiguration.create(otherSite,OIDC_PROVIDER,providers.find(siteId,OIDC_PROVIDER).orElseThrow().configuration()));

        assertFailure(() -> exchange.exchange(new ExchangeOidcTokenUseCase.Command(started.attemptId(),"cross-auth-site",OIDC_PROVIDER,ORIGIN,
            AuthenticationIntent.AUTHENTICATE,started.nonce(),token("cross-subject","cross-jti",started.nonce(),"-"))));

        assertThat(verifier.oidcCalls()).isZero();
        assertThat(jdbc.queryForObject("select consumed_at is null from oidc_authentication_attempts where id=?",Boolean.class,started.attemptId())).isTrue();
    }

    @Test void concurrentDoubleConsumeHasOneWinnerOneUniformFailureAndOneCustomer() throws Exception {
        var started=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        var command=command(started,token("double-subject","double-jti",started.nonce(),"-"),AuthenticationIntent.AUTHENTICATE,ORIGIN);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new CountDownLatch(1);
            Callable<Object> call=() -> { gate.await(); try { return exchange.exchange(command); } catch(RuntimeException failure) { return failure; } };
            var first=executor.submit(call); var second=executor.submit(call); gate.countDown();
            var outcomes=List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(CustomerAuthenticationResult.AuthenticatedCustomer.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(PaymentApplicationException.class::isInstance).singleElement()
                .satisfies(value -> assertUniform((PaymentApplicationException)value));
        }
        assertThat(jdbc.queryForObject("select consumed_at is not null from oidc_authentication_attempts where id=?",Boolean.class,started.attemptId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
    }

    @Test void verifiedAttemptConsumptionRemainsCommittedWhenLaterIdentityResolutionFails() {
        var started=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        assertFailure(() -> exchange.exchange(command(started,
            token("invalid-email-subject","invalid-email-jti",started.nonce(),"not an email"),AuthenticationIntent.AUTHENTICATE,ORIGIN)));

        assertThat(verifier.oidcCalls()).isOne();
        assertThat(jdbc.queryForObject("select consumed_at is not null from oidc_authentication_attempts where id=?",Boolean.class,started.attemptId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isZero();
    }

    @Test void attemptConsumptionAndLinkProofRoundTripAtPostgresMicrosecondPrecision() {
        Instant precise=Instant.parse("2026-09-13T10:15:30.123456789Z");
        Instant canonical=precise.truncatedTo(ChronoUnit.MICROS);
        dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock.set(precise);

        var started=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.LINK));
        assertThat(started.expiresAt()).isEqualTo(canonical.plusSeconds(300));
        assertThat(jdbc.queryForObject("select created_at from oidc_authentication_attempts where id=?",Timestamp.class,started.attemptId()).toInstant())
            .isEqualTo(canonical);
        assertThat(jdbc.queryForObject("select expires_at from oidc_authentication_attempts where id=?",Timestamp.class,started.attemptId()).toInstant())
            .isEqualTo(started.expiresAt());

        var outcome=(CustomerAuthenticationResult.LinkProofIssued)exchange.exchange(command(started,
            token("microsecond-subject","microsecond-jti",started.nonce(),"-"),AuthenticationIntent.LINK,ORIGIN));
        assertThat(outcome.expiresAt()).isEqualTo(canonical.plusSeconds(300));
        assertThat(jdbc.queryForObject("select consumed_at from oidc_authentication_attempts where id=?",Timestamp.class,started.attemptId()).toInstant())
            .isEqualTo(canonical);
        assertThat(jdbc.queryForObject("select created_at from identity_link_proofs",Timestamp.class).toInstant()).isEqualTo(canonical);
        assertThat(jdbc.queryForObject("select expires_at from identity_link_proofs",Timestamp.class).toInstant()).isEqualTo(outcome.expiresAt());
    }

    @Test void failedSignatureDoesNotConsumeButVerifiedTokenConsumesTheBoundAttempt() {
        var started=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        assertThat(jdbc.queryForObject("select nonce_digest from oidc_authentication_attempts where id=?",String.class,started.attemptId()))
            .doesNotContain(started.nonce());
        assertFailure(() -> exchange.exchange(command(started,"bad-signature",AuthenticationIntent.AUTHENTICATE,ORIGIN)));
        var result=exchange.exchange(command(started,token("subject-a","jti-a",started.nonce(),"Person@Example.Test"),AuthenticationIntent.AUTHENTICATE,ORIGIN));
        assertThat(result).isInstanceOf(CustomerAuthenticationResult.AuthenticatedCustomer.class);
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customers where email_ciphertext is not null and email_digest is not null",Integer.class)).isOne();
        assertFailure(() -> exchange.exchange(command(started,token("subject-a","jti-a",started.nonce(),"Person@Example.Test"),AuthenticationIntent.AUTHENTICATE,ORIGIN)));
    }

    @Test void exactOriginProviderIntentAndNonceAreBoundBeforeCredentialUse() {
        var started=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        assertFailure(() -> exchange.exchange(command(started,token("subject","jti",started.nonce(),"-"),AuthenticationIntent.AUTHENTICATE,"https://other.example.test")));
        assertFailure(() -> exchange.exchange(new ExchangeOidcTokenUseCase.Command(started.attemptId(),SITE_KEY,"other-provider",ORIGIN,
            AuthenticationIntent.AUTHENTICATE,started.nonce(),token("subject","jti",started.nonce(),"-"))));
        assertFailure(() -> exchange.exchange(command(started,token("subject","jti",started.nonce(),"-"),AuthenticationIntent.LINK,ORIGIN)));
        assertFailure(() -> exchange.exchange(new ExchangeOidcTokenUseCase.Command(started.attemptId(),SITE_KEY,OIDC_PROVIDER,ORIGIN,
            AuthenticationIntent.AUTHENTICATE,"wrong-nonce",token("subject","jti","wrong-nonce","-"))));
        assertThat(jdbc.queryForObject("select consumed_at is null from oidc_authentication_attempts where id=?",Boolean.class,started.attemptId())).isTrue();
    }

    @Test void linkIntentReturnsOneTimeProofAndNeverCreatesCustomer() {
        var started=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.LINK));
        var outcome=exchange.exchange(command(started,token("link-subject","link-jti",started.nonce(),"person@example.test"),AuthenticationIntent.LINK,ORIGIN));
        var proof=assertThat(outcome).isInstanceOf(CustomerAuthenticationResult.LinkProofIssued.class)
            .extracting(value -> ((CustomerAuthenticationResult.LinkProofIssued)value).proof()).asString().isNotBlank().actual();
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select token_digest from identity_link_proofs",String.class)).doesNotContain(proof,"link-subject","person@example.test");
    }

    @Test void concurrentFirstLoginRollsBackLoserAndReturnsOneCustomer() throws Exception {
        var first=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        var second=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var gate=new CountDownLatch(1);
            var a=executor.submit(() -> { gate.await(); return exchange.exchange(command(first,token("race-subject","race-a",first.nonce(),"same@example.test"),AuthenticationIntent.AUTHENTICATE,ORIGIN)); });
            var b=executor.submit(() -> { gate.await(); return exchange.exchange(command(second,token("race-subject","race-b",second.nonce(),"same@example.test"),AuthenticationIntent.AUTHENTICATE,ORIGIN)); });
            gate.countDown();
            var ids=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)).stream()
                .map(CustomerAuthenticationResult.AuthenticatedCustomer.class::cast).map(CustomerAuthenticationResult.AuthenticatedCustomer::customerId).toList();
            assertThat(ids).containsOnly(ids.getFirst());
        }
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
    }

    @Test void existingIdentityWinsAndAChangedEmailNeverOverwritesItsProfileHint() {
        var first=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        var original=(CustomerAuthenticationResult.AuthenticatedCustomer)exchange.exchange(command(first,
            token("stable-subject","same-1",first.nonce(),"first@example.test"),AuthenticationIntent.AUTHENTICATE,ORIGIN));
        String ciphertext=jdbc.queryForObject("select email_ciphertext from customers",String.class);
        var second=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        var replay=(CustomerAuthenticationResult.AuthenticatedCustomer)exchange.exchange(command(second,
            token("stable-subject","same-2",second.nonce(),"second@example.test"),AuthenticationIntent.AUTHENTICATE,ORIGIN));
        assertThat(replay.customerId()).isEqualTo(original.customerId());
        assertThat(jdbc.queryForObject("select email_ciphertext from customers",String.class)).isEqualTo(ciphertext);
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isOne();
    }

    @Test void equalEmailsNeverMergeDifferentSubjectsOrDifferentSites() {
        var first=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        exchange.exchange(command(first,token("subject-one","email-1",first.nonce(),"equal@example.test"),AuthenticationIntent.AUTHENTICATE,ORIGIN));
        var second=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        exchange.exchange(command(second,token("subject-two","email-2",second.nonce(),"EQUAL@example.test"),AuthenticationIntent.AUTHENTICATE,ORIGIN));

        var otherSite=new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID());
        UUID account=jdbc.queryForObject("select id from payment_accounts",UUID.class);
        jdbc.update("insert into sites values (?,?, 'Other authentication',?,'ACTIVE','AUTOMATIC',now(),now(),0)",otherSite.value(),"other-auth-site",account);
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,?)",otherSite.value(),ORIGIN);
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'OIDC_TOKEN')",otherSite.value());
        var template=providers.find(siteId,OIDC_PROVIDER).orElseThrow();
        providers.save(IdentityProviderConfiguration.create(otherSite,OIDC_PROVIDER,template.configuration()));
        var third=begin.begin(new BeginOidcAuthenticationUseCase.Command("other-auth-site",OIDC_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE));
        exchange.exchange(new ExchangeOidcTokenUseCase.Command(third.attemptId(),"other-auth-site",OIDC_PROVIDER,ORIGIN,
            AuthenticationIntent.AUTHENTICATE,third.nonce(),token("subject-one","email-3",third.nonce(),"equal@example.test")));
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(distinct site_id) from customers",Integer.class)).isEqualTo(2);
    }

    private ExchangeOidcTokenUseCase.Command command(BeginOidcAuthenticationUseCase.Result started,String token,AuthenticationIntent intent,String origin) {
        return new ExchangeOidcTokenUseCase.Command(started.attemptId(),SITE_KEY,OIDC_PROVIDER,origin,intent,started.nonce(),token);
    }
    private static String token(String subject,String jti,String nonce,String email) { return "oidc:"+subject+":"+jti+":"+nonce+":"+email; }
    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PaymentApplicationException.class,error -> {
            assertUniform(error);
        });
    }
    private static void assertUniform(PaymentApplicationException error) {
        assertThat(error.code()).isEqualTo("AUTHENTICATION_FAILED"); assertThat(error.status()).isEqualTo(401);
        assertThat(error).hasMessage("Authentication failed").hasNoCause();
    }
}
