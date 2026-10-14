package dev.genericpayments.platform.customers.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.customers.application.model.CustomerAuthenticationResult;
import dev.genericpayments.platform.customers.application.port.in.ExchangeSiteTokenUseCase;
import dev.genericpayments.platform.customers.domain.AuthenticationIntent;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.customers.application.port.out.KeyedDigestPort;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SiteTokenAuthenticationServiceIntegrationTest extends AuthenticationServiceIntegrationSupport {
    @Autowired ExchangeSiteTokenUseCase exchange;

    @Test void nullIntentIsRejectedBeforeVerificationOrJtiConsumption() {
        assertFailure(() -> exchange.exchange(new ExchangeSiteTokenUseCase.Command(SITE_KEY,SITE_PROVIDER,ORIGIN,null,"site:subject:null-intent-jti")));
        assertThat(verifier.siteCalls()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from consumed_site_token_jtis",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isZero();
    }

    @Test void verifiedJtiIsConsumedBeforeIdentityResolutionAndCannotReplay() {
        var command=new ExchangeSiteTokenUseCase.Command(SITE_KEY,SITE_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE,"site:subject-a:jti-a");
        assertThat(exchange.exchange(command)).isInstanceOf(CustomerAuthenticationResult.AuthenticatedCustomer.class);
        assertThat(jdbc.queryForObject("select expires_at > consumed_at from consumed_site_token_jtis",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select extract(epoch from (expires_at-consumed_at)) from consumed_site_token_jtis",Double.class)).isEqualTo(180.0);
        assertFailure(() -> exchange.exchange(command));
    }

    @Test void linkConsumesJtiButDoesNotCreateCustomerAndBoundaryExpiryRemainsValid() {
        verifier.nextSiteExpiryOffsetSeconds(-60);
        var outcome=exchange.exchange(new ExchangeSiteTokenUseCase.Command(SITE_KEY,SITE_PROVIDER,ORIGIN,AuthenticationIntent.LINK,"site:link-subject:link-jti"));
        assertThat(outcome).isInstanceOf(CustomerAuthenticationResult.LinkProofIssued.class);
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from identity_link_proofs",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select expires_at > consumed_at from consumed_site_token_jtis",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select extract(epoch from (expires_at-consumed_at)) from consumed_site_token_jtis",Double.class)).isEqualTo(1.0);
    }

    @Test void verifiedJtiConsumptionRemainsCommittedWhenLaterIdentityResolutionFails() {
        String subject="anonymized-subject";
        var digest=digests.digest(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,subject);
        UUID customer=UUID.randomUUID(); UUID identity=UUID.randomUUID();
        jdbc.update("""
            insert into customers(id,site_id,status,created_at,updated_at,anonymized_at,version)
            values (?,?,'ANONYMIZED',?,?,?,0)
            """,customer,siteId.value(),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME));
        jdbc.update("""
            insert into customer_identities(id,site_id,customer_id,mode,issuer_key,subject_digest,subject_digest_key_version,
              verified_at,created_at,last_used_at,version) values (?,?,?,'SITE_TOKEN',?,?,?,?,?,?,0)
            """,identity,siteId.value(),customer,SITE_PROVIDER,digest.value(),digest.keyVersion(),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME));
        // V7 lookup follows aliases; without this row the fixture creates a duplicate identity
        // instead of reaching the intended anonymized-owner rejection after JTI consumption.
        jdbc.update("""
            insert into customer_identity_subject_aliases(site_id,issuer_key,subject_digest,subject_digest_key_version,identity_id)
            values (?,?,?,?,?)
            """,siteId.value(),SITE_PROVIDER,digest.value(),digest.keyVersion(),identity);

        assertFailure(() -> exchange.exchange(new ExchangeSiteTokenUseCase.Command(SITE_KEY,SITE_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE,
            "site:"+subject+":later-failure-jti")));

        assertThat(jdbc.queryForObject("select count(*) from consumed_site_token_jtis",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select status from customers where id=?",String.class,customer)).isEqualTo("ANONYMIZED");
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_identity_subject_aliases",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_sessions",Integer.class)).isZero();
        assertFailure(() -> exchange.exchange(new ExchangeSiteTokenUseCase.Command(SITE_KEY,SITE_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE,
            "site:"+subject+":later-failure-jti")));
        assertThat(jdbc.queryForObject("select count(*) from consumed_site_token_jtis",Integer.class)).isOne();
    }

    @Test void postgresMicrosecondCanonicalizationIsReturnedAndPersistedForAttemptsProofsAndJtis() {
        Instant precise=Instant.parse("2026-09-13T10:15:30.123456789Z");
        Instant canonical=precise.truncatedTo(ChronoUnit.MICROS);
        PostgresIntegrationTest.TestClock.set(precise);

        var outcome=(CustomerAuthenticationResult.LinkProofIssued)exchange.exchange(new ExchangeSiteTokenUseCase.Command(
            SITE_KEY,SITE_PROVIDER,ORIGIN,AuthenticationIntent.LINK,"site:timestamp-subject:timestamp-jti"));

        assertThat(outcome.expiresAt()).isEqualTo(canonical.plus(Duration.ofMinutes(5)));
        assertThat(jdbc.queryForObject("select consumed_at from consumed_site_token_jtis",Timestamp.class).toInstant()).isEqualTo(canonical);
        assertThat(jdbc.queryForObject("select created_at from identity_link_proofs",Timestamp.class).toInstant()).isEqualTo(canonical);
        assertThat(jdbc.queryForObject("select verified_at from identity_link_proofs",Timestamp.class).toInstant()).isEqualTo(canonical);
        assertThat(jdbc.queryForObject("select expires_at from identity_link_proofs",Timestamp.class).toInstant()).isEqualTo(outcome.expiresAt());
    }

    @Test void signatureOriginAndDisabledProviderFailuresHaveUniformShapeAndDoNotInsertJti() {
        assertFailure(() -> exchange.exchange(new ExchangeSiteTokenUseCase.Command(SITE_KEY,SITE_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE,"bad-signature")));
        assertFailure(() -> exchange.exchange(new ExchangeSiteTokenUseCase.Command(SITE_KEY,SITE_PROVIDER,"https://other.example.test",AuthenticationIntent.AUTHENTICATE,"site:s:j")));
        var provider=providers.find(siteId,SITE_PROVIDER).orElseThrow(); providers.save(provider.disable());
        assertFailure(() -> exchange.exchange(new ExchangeSiteTokenUseCase.Command(SITE_KEY,SITE_PROVIDER,ORIGIN,AuthenticationIntent.AUTHENTICATE,"site:s:j")));
        assertThat(jdbc.queryForObject("select count(*) from consumed_site_token_jtis",Integer.class)).isZero();
    }

    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PaymentApplicationException.class,error -> {
            assertThat(error.code()).isEqualTo("AUTHENTICATION_FAILED"); assertThat(error.status()).isEqualTo(401);
            assertThat(error).hasMessage("Authentication failed").hasNoCause();
        });
    }
}
