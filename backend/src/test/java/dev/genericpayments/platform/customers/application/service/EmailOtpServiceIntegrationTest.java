package dev.genericpayments.platform.customers.application.service;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerMailDeliveryPort;
import dev.genericpayments.platform.customers.application.port.out.IdentityProviderConfigurationStore;
import dev.genericpayments.platform.customers.application.port.out.OtpCodeGeneratorPort;
import dev.genericpayments.platform.customers.application.model.CustomerAuthenticationResult;
import dev.genericpayments.platform.customers.adapter.in.configuration.CustomerEmailOtpProperties;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(EmailOtpServiceIntegrationTest.FixedOtpConfiguration.class)
class EmailOtpServiceIntegrationTest extends PostgresIntegrationTest {
    private static final String SITE_KEY="otp-site";
    private static final String ORIGIN="https://otp.example.test";
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityProviderConfigurationStore providers;
    @Autowired RequestEmailOtpUseCase requests;
    @Autowired VerifyEmailOtpUseCase verifications;
    @Autowired CustomerEmailOtpProperties properties;
    @Autowired CustomerMailCapability mailCapability;
    @Autowired EncryptedSecretPort encryption;
    @Autowired PlatformTransactionManager transactions;
    @Autowired @Qualifier("customerEmailOtpReadiness") HealthIndicator readiness;
    SiteId siteId;

    @BeforeEach void prepare() {
        clearDatabase();
        siteId=new SiteId(UUID.randomUUID());
        UUID account=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'otp-account','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        jdbc.update("insert into sites values (?,?, 'OTP',?,'ACTIVE','AUTOMATIC',now(),now(),0)",siteId.value(),SITE_KEY,account);
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,?)",siteId.value(),ORIGIN);
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'EMAIL_OTP')",siteId.value());
        providers.save(IdentityProviderConfiguration.create(siteId,"email-provider",
            new IdentityProviderConfiguration.EmailOtpConfiguration("test-default","otp-default")));
        assertThat(properties.smtp().senderRef()).isEqualTo("test-default");
        assertThat(mailCapability.available("test-default")).isTrue();
    }

    @Test void challengeAndEncryptedOutboxCommitTogetherAndCooldownReusesChallenge() {
        var command=new RequestEmailOtpUseCase.Command(SITE_KEY,ORIGIN,AuthenticationIntent.AUTHENTICATE,
            " Person@Example.Test ","203.0.113.7");
        var first=requests.request(command);
        var second=requests.request(command);

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from email_otp_challenges",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from customer_mail_outbox",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select email_ciphertext is not null and email_digest <> '' from email_otp_challenges where id=?",
            Boolean.class,first.challengeId())).isTrue();
        assertThat(jdbc.queryForObject("select payload_ciphertext is not null from customer_mail_outbox where challenge_id=?",
            Boolean.class,first.challengeId())).isTrue();
    }

    @Test void normalizedEmailVariantsShareTheSameDistributedLimit() {
        String[] variants={" Person@Example.Test ","person@example.test","PERSON@EXAMPLE.TEST",
            " person@EXAMPLE.test ","Person@example.TEST"};
        for(int index=0;index<variants.length;index++) {
            requests.request(command(AuthenticationIntent.AUTHENTICATE,variants[index],"203.0.113."+(40+index)));
        }

        assertThatThrownBy(() -> requests.request(command(AuthenticationIntent.AUTHENTICATE,
            "PERSON@example.test","203.0.113.45")))
            .isInstanceOfSatisfying(PaymentApplicationException.class,
                error -> assertThat(error.code()).isEqualTo("OTP_RATE_LIMITED"));
        assertThat(jdbc.queryForObject("select request_count from customer_rate_limit_buckets where bucket_type='EMAIL'",
            Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select request_count from customer_rate_limit_buckets where bucket_type='SITE'",
            Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from email_otp_challenges",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_mail_outbox",Integer.class)).isOne();
    }

    @Test void switchingIntentDuringCooldownInvalidatesTheExactPredecessorInBothDirections() {
        var authenticate=requests.request(command(AuthenticationIntent.AUTHENTICATE,"first-direction@example.test","203.0.113.20"));
        var link=requests.request(command(AuthenticationIntent.LINK,"first-direction@example.test","203.0.113.20"));

        assertThat(link.challengeId()).isNotEqualTo(authenticate.challengeId());
        assertUniformInvalid(new VerifyEmailOtpUseCase.Command(authenticate.challengeId(),SITE_KEY,ORIGIN,"654321"));
        assertThat(verifications.verify(new VerifyEmailOtpUseCase.Command(link.challengeId(),SITE_KEY,ORIGIN,"654321")))
            .isInstanceOf(CustomerAuthenticationResult.LinkProofIssued.class);

        var firstLink=requests.request(command(AuthenticationIntent.LINK,"second-direction@example.test","203.0.113.21"));
        var secondAuthenticate=requests.request(command(AuthenticationIntent.AUTHENTICATE,"second-direction@example.test","203.0.113.21"));

        assertThat(secondAuthenticate.challengeId()).isNotEqualTo(firstLink.challengeId());
        assertUniformInvalid(new VerifyEmailOtpUseCase.Command(firstLink.challengeId(),SITE_KEY,ORIGIN,"654321"));
        assertThat(verifications.verify(new VerifyEmailOtpUseCase.Command(secondAuthenticate.challengeId(),SITE_KEY,ORIGIN,"654321")))
            .isInstanceOf(CustomerAuthenticationResult.AuthenticatedCustomer.class);
        assertThat(jdbc.queryForObject("select count(*) from identity_link_proofs",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_sessions",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_mail_outbox",Integer.class)).isEqualTo(4);
    }

    @Test void multipleProvidersUseTheLexicographicallySmallestAndChallengeKeepsThatIssuer() {
        providers.save(IdentityProviderConfiguration.create(siteId,"aaa-provider",
            new IdentityProviderConfiguration.EmailOtpConfiguration("test-default","otp-default")));
        providers.save(IdentityProviderConfiguration.create(siteId,"zzz-provider",
            new IdentityProviderConfiguration.EmailOtpConfiguration("test-default","otp-default")));

        var result=requests.request(command(AuthenticationIntent.AUTHENTICATE,"provider-choice@example.test","203.0.113.22"));

        assertThat(jdbc.queryForObject("select provider_key from email_otp_challenges where id=?",String.class,result.challengeId()))
            .isEqualTo("aaa-provider");
    }

    @Test void changingSelectedProviderDuringCooldownInvalidatesThePredecessorInsteadOfReusingIt() {
        var first=requests.request(command(AuthenticationIntent.AUTHENTICATE,"provider-switch@example.test","203.0.113.31"));
        providers.save(IdentityProviderConfiguration.create(siteId,"aaa-provider",
            new IdentityProviderConfiguration.EmailOtpConfiguration("test-default","otp-default")));

        var second=requests.request(command(AuthenticationIntent.AUTHENTICATE,"provider-switch@example.test","203.0.113.31"));

        assertThat(second.challengeId()).isNotEqualTo(first.challengeId());
        assertUniformInvalid(new VerifyEmailOtpUseCase.Command(first.challengeId(),SITE_KEY,ORIGIN,"654321"));
        assertThat(jdbc.queryForObject("select provider_key from email_otp_challenges where id=?",String.class,second.challengeId()))
            .isEqualTo("aaa-provider");
        assertThat(jdbc.queryForObject("select count(*) from customer_mail_outbox",Integer.class)).isEqualTo(2);
    }

    @Test void disablingBoundProviderDuringTtlDoesNotRebindChallengeToAnotherProvider() {
        providers.save(IdentityProviderConfiguration.create(siteId,"aaa-provider",
            new IdentityProviderConfiguration.EmailOtpConfiguration("test-default","otp-default")));
        var result=requests.request(command(AuthenticationIntent.AUTHENTICATE,"immutable-issuer@example.test","203.0.113.23"));
        jdbc.update("update identity_provider_configurations set status='DISABLED',version=version+1 where site_id=? and provider_key='aaa-provider'",siteId.value());

        assertUniformInvalid(new VerifyEmailOtpUseCase.Command(result.challengeId(),SITE_KEY,ORIGIN,"654321"));
        assertThat(jdbc.queryForObject("select consumed_at is null from email_otp_challenges where id=?",Boolean.class,result.challengeId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from customer_sessions",Integer.class)).isZero();
    }

    @Test void configuredTemplateKeyMateriallySelectsEncryptedMailContent() {
        var standard=requests.request(command(AuthenticationIntent.AUTHENTICATE,"standard-template@example.test","203.0.113.24"));
        jdbc.update("update identity_provider_configurations set template_key='otp-alternate',version=version+1 where site_id=? and provider_key='email-provider'",siteId.value());
        var alternate=requests.request(command(AuthenticationIntent.AUTHENTICATE,"alternate-template@example.test","203.0.113.25"));

        var first=mailMessage(standard.challengeId());
        var second=mailMessage(alternate.challengeId());
        assertThat(first.subject()).isEqualTo("Your verification code");
        assertThat(first.body()).contains("654321","10 minutes");
        assertThat(second.subject()).isEqualTo("Alternate verification code");
        assertThat(second.body()).contains("654321","10 minutes","alternate").isNotEqualTo(first.body());
    }

    @Test void unknownTemplateFailsClosedAndMakesEnabledSiteReadinessDown() {
        jdbc.update("update identity_provider_configurations set template_key='unknown-template',version=version+1 where site_id=?",siteId.value());

        assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
        assertThatThrownBy(() -> requests.request(command(AuthenticationIntent.AUTHENTICATE,"unknown-template@example.test","203.0.113.26")))
            .isInstanceOfSatisfying(PaymentApplicationException.class,error -> assertThat(error.code()).isEqualTo("AUTHENTICATION_MODE_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("select count(*) from email_otp_challenges",Integer.class)).isZero();
    }

    @Test void challengeAndOutboxRollBackTogetherWhenOutboxPersistenceFailsAfterChallengeInsert() {
        jdbc.execute("create function fail_customer_mail_insert() returns trigger language plpgsql as $$ begin raise exception 'injected outbox failure'; end $$");
        jdbc.execute("create trigger fail_customer_mail_insert before insert on customer_mail_outbox for each row execute function fail_customer_mail_insert()");
        try {
            assertThatThrownBy(() -> requests.request(command(AuthenticationIntent.AUTHENTICATE,"atomic@example.test","203.0.113.27")))
                .isInstanceOf(PaymentApplicationException.class);
        } finally {
            jdbc.execute("drop trigger fail_customer_mail_insert on customer_mail_outbox");
            jdbc.execute("drop function fail_customer_mail_insert()");
        }
        assertThat(jdbc.queryForObject("select count(*) from email_otp_challenges",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer_mail_outbox",Integer.class)).isZero();
    }

    @Test void authenticateConsumptionRollsBackWhenSessionPersistenceFailsAndEventuallyConsumesOnce() {
        var result=requests.request(command(AuthenticationIntent.AUTHENTICATE,"auth-rollback@example.test","203.0.113.28"));
        jdbc.execute("create function fail_customer_session_insert() returns trigger language plpgsql as $$ begin raise exception 'injected session failure'; end $$");
        jdbc.execute("create trigger fail_customer_session_insert before insert on customer_sessions for each row execute function fail_customer_session_insert()");
        try {
            assertUniformInvalid(new VerifyEmailOtpUseCase.Command(result.challengeId(),SITE_KEY,ORIGIN,"654321"));
        } finally {
            jdbc.execute("drop trigger fail_customer_session_insert on customer_sessions");
            jdbc.execute("drop function fail_customer_session_insert()");
        }
        assertThat(jdbc.queryForObject("select consumed_at is null from email_otp_challenges where id=?",Boolean.class,result.challengeId())).isTrue();
        assertThat(verifications.verify(new VerifyEmailOtpUseCase.Command(result.challengeId(),SITE_KEY,ORIGIN,"654321")))
            .isInstanceOf(CustomerAuthenticationResult.AuthenticatedCustomer.class);
        assertUniformInvalid(new VerifyEmailOtpUseCase.Command(result.challengeId(),SITE_KEY,ORIGIN,"654321"));
        assertThat(jdbc.queryForObject("select count(*) from customer_sessions",Integer.class)).isOne();
    }

    @Test void linkConsumptionRollsBackWhenProofPersistenceFailsAndEventuallyConsumesOnce() {
        var result=requests.request(command(AuthenticationIntent.LINK,"link-rollback@example.test","203.0.113.29"));
        jdbc.execute("create function fail_link_proof_insert() returns trigger language plpgsql as $$ begin raise exception 'injected proof failure'; end $$");
        jdbc.execute("create trigger fail_link_proof_insert before insert on identity_link_proofs for each row execute function fail_link_proof_insert()");
        try {
            assertUniformInvalid(new VerifyEmailOtpUseCase.Command(result.challengeId(),SITE_KEY,ORIGIN,"654321"));
        } finally {
            jdbc.execute("drop trigger fail_link_proof_insert on identity_link_proofs");
            jdbc.execute("drop function fail_link_proof_insert()");
        }
        assertThat(jdbc.queryForObject("select consumed_at is null from email_otp_challenges where id=?",Boolean.class,result.challengeId())).isTrue();
        assertThat(verifications.verify(new VerifyEmailOtpUseCase.Command(result.challengeId(),SITE_KEY,ORIGIN,"654321")))
            .isInstanceOf(CustomerAuthenticationResult.LinkProofIssued.class);
        assertUniformInvalid(new VerifyEmailOtpUseCase.Command(result.challengeId(),SITE_KEY,ORIGIN,"654321"));
        assertThat(jdbc.queryForObject("select count(*) from identity_link_proofs",Integer.class)).isOne();
    }

    @Test void fifthWrongCodeExhaustsChallengeWithOneUniformError() {
        var result=requests.request(new RequestEmailOtpUseCase.Command(SITE_KEY,ORIGIN,AuthenticationIntent.AUTHENTICATE,
            "person@example.test","203.0.113.7"));
        for(int attempt=0;attempt<5;attempt++) {
            assertThatThrownBy(() -> verifications.verify(new VerifyEmailOtpUseCase.Command(result.challengeId(),SITE_KEY,ORIGIN,"000000")))
                .isInstanceOfSatisfying(PaymentApplicationException.class,
                    error -> assertThat(error.code()).isEqualTo("OTP_INVALID_OR_EXPIRED"));
        }
        assertThat(jdbc.queryForObject("select attempt_count from email_otp_challenges where id=?",Integer.class,result.challengeId()))
            .isEqualTo(5);
        assertThatThrownBy(() -> verifications.verify(new VerifyEmailOtpUseCase.Command(result.challengeId(),SITE_KEY,ORIGIN,"111111")))
            .isInstanceOfSatisfying(PaymentApplicationException.class,
                error -> assertThat(error.code()).isEqualTo("OTP_INVALID_OR_EXPIRED"));
    }

    @Test void concurrentCorrectVerificationSucceedsExactlyOnceAndCreatesOneIdentityAndSession() throws Exception {
        var result=requests.request(command(AuthenticationIntent.AUTHENTICATE,"person@example.test","203.0.113.7"));
        List<Object> outcomes=concurrently(2,() -> {
            try { return verifications.verify(new VerifyEmailOtpUseCase.Command(result.challengeId(),SITE_KEY,ORIGIN,"654321")); }
            catch(PaymentApplicationException failure) { return failure; }
        });

        assertThat(outcomes.stream().filter(CustomerAuthenticationResult.AuthenticatedCustomer.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(PaymentApplicationException.class::isInstance)
            .map(PaymentApplicationException.class::cast).map(PaymentApplicationException::code)).containsExactly("OTP_INVALID_OR_EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from customer_identities",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_sessions",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select consumed_at is not null from email_otp_challenges where id=?",Boolean.class,result.challengeId())).isTrue();
    }

    @Test void linkVerificationReturnsOneTimeProofWithoutCreatingSession() {
        var result=requests.request(command(AuthenticationIntent.LINK,"link@example.test","203.0.113.8"));
        var verified=verifications.verify(new VerifyEmailOtpUseCase.Command(result.challengeId(),SITE_KEY,ORIGIN,"654321"));

        assertThat(verified).isInstanceOf(CustomerAuthenticationResult.LinkProofIssued.class);
        assertThat(jdbc.queryForObject("select count(*) from identity_link_proofs",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_sessions",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customers",Integer.class)).isZero();
    }

    @Test void concurrentResendAfterCooldownCreatesOneSuccessorAndOneAdditionalMail() throws Exception {
        var original=requests.request(command(AuthenticationIntent.AUTHENTICATE,"resend@example.test","203.0.113.9"));
        TestClock.set(INITIAL_TIME.plusSeconds(61));
        var results=concurrently(2,() -> requests.request(command(AuthenticationIntent.AUTHENTICATE,
            " RESEND@example.test ","203.0.113.9")));

        assertThat(results).extracting(RequestEmailOtpUseCase.Result::challengeId).containsOnly(results.getFirst().challengeId());
        assertThat(results.getFirst().challengeId()).isNotEqualTo(original.challengeId());
        assertThat(jdbc.queryForObject("select count(*) from email_otp_challenges",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from customer_mail_outbox",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select invalidated_at is not null from email_otp_challenges where id=?",Boolean.class,original.challengeId())).isTrue();
    }

    @Test void wrongSiteOriginConsumedInvalidatedAndPreCallExpiryShareOnePublicError() {
        var result=requests.request(command(AuthenticationIntent.AUTHENTICATE,"uniform@example.test","203.0.113.10"));
        assertUniformInvalid(new VerifyEmailOtpUseCase.Command(UUID.randomUUID(),SITE_KEY,ORIGIN,"654321"));
        assertUniformInvalid(new VerifyEmailOtpUseCase.Command(result.challengeId(),SITE_KEY,"https://other.example.test","654321"));

        SiteId other=createSite("wrong-site",true,true);
        providers.save(IdentityProviderConfiguration.create(other,"email-provider",
            new IdentityProviderConfiguration.EmailOtpConfiguration("test-default","otp-default")));
        assertUniformInvalid(new VerifyEmailOtpUseCase.Command(result.challengeId(),"wrong-site",ORIGIN,"654321"));

        assertThat(verifications.verify(new VerifyEmailOtpUseCase.Command(result.challengeId(),SITE_KEY,ORIGIN,"654321")))
            .isInstanceOf(CustomerAuthenticationResult.AuthenticatedCustomer.class);
        assertUniformInvalid(new VerifyEmailOtpUseCase.Command(result.challengeId(),SITE_KEY,ORIGIN,"654321"));

        var predecessor=requests.request(command(AuthenticationIntent.AUTHENTICATE,"invalidated@example.test","203.0.113.30"));
        TestClock.set(INITIAL_TIME.plusSeconds(61));
        requests.request(command(AuthenticationIntent.AUTHENTICATE,"invalidated@example.test","203.0.113.30"));
        assertUniformInvalid(new VerifyEmailOtpUseCase.Command(predecessor.challengeId(),SITE_KEY,ORIGIN,"654321"));

        var expiring=requests.request(command(AuthenticationIntent.AUTHENTICATE,"expired@example.test","203.0.113.31"));
        TestClock.set(INITIAL_TIME.plusSeconds(61).plus(Duration.ofMinutes(10)));
        assertUniformInvalid(new VerifyEmailOtpUseCase.Command(expiring.challengeId(),SITE_KEY,ORIGIN,"654321"));
    }

    @Test void expiryWhileQueuedBehindChallengeLockUsesTheSameUniformError() throws Exception {
        var result=requests.request(command(AuthenticationIntent.AUTHENTICATE,"queued-expiry@example.test","203.0.113.32"));
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try {
            Future<?> blocker=executor.submit(() -> {
                new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    jdbc.queryForObject("select id from email_otp_challenges where id=? for update",UUID.class,result.challengeId());
                    locked.countDown();
                    try { assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); }
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                });
            });
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            Future<CustomerAuthenticationResult> verification=executor.submit(() -> verifications.verify(
                new VerifyEmailOtpUseCase.Command(result.challengeId(),SITE_KEY,ORIGIN,"654321")));
            Thread.sleep(150);
            assertThat(verification).isNotDone();
            TestClock.set(INITIAL_TIME.plus(Duration.ofMinutes(10)));
            release.countDown(); blocker.get(5,TimeUnit.SECONDS);
            assertThatThrownBy(() -> verification.get(5,TimeUnit.SECONDS))
                .isInstanceOfSatisfying(ExecutionException.class,error -> assertThat(error.getCause())
                    .isInstanceOfSatisfying(PaymentApplicationException.class,app -> {
                        assertThat(app.code()).isEqualTo("OTP_INVALID_OR_EXPIRED");
                        assertThat(app.status()).isEqualTo(401);
                        assertThat(app.title()).isEqualTo("OTP is invalid or expired");
                    }));
        } finally {release.countDown();executor.shutdownNow();}
    }

    @Test void sameNormalizedEmailIsolatedAcrossSites() {
        String secondKey="otp-site-two"; SiteId second=new SiteId(UUID.randomUUID()); UUID account=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'otp-account-two','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        jdbc.update("insert into sites values (?,?, 'OTP 2',?,'ACTIVE','AUTOMATIC',now(),now(),0)",second.value(),secondKey,account);
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,?)",second.value(),ORIGIN);
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'EMAIL_OTP')",second.value());
        providers.save(IdentityProviderConfiguration.create(second,"email-provider",
            new IdentityProviderConfiguration.EmailOtpConfiguration("test-default","otp-default")));

        var first=requests.request(command(AuthenticationIntent.AUTHENTICATE,"shared@example.test","203.0.113.11"));
        var other=requests.request(new RequestEmailOtpUseCase.Command(secondKey,ORIGIN,AuthenticationIntent.AUTHENTICATE,
            "SHARED@example.test","203.0.113.11"));
        assertThat(first.challengeId()).isNotEqualTo(other.challengeId());
        assertThat(jdbc.queryForObject("select count(distinct site_id) from email_otp_challenges",Integer.class)).isEqualTo(2);
    }

    @Test void existingAndNewCustomerEmailsReceiveTheSameSafeRequestResultShape() {
        var initial=requests.request(command(AuthenticationIntent.AUTHENTICATE,"existing@example.test","203.0.113.13"));
        verifications.verify(new VerifyEmailOtpUseCase.Command(initial.challengeId(),SITE_KEY,ORIGIN,"654321"));

        var existing=requests.request(command(AuthenticationIntent.AUTHENTICATE," EXISTING@example.test ","203.0.113.13"));
        var unknown=requests.request(command(AuthenticationIntent.AUTHENTICATE,"unknown@example.test","203.0.113.13"));

        assertThat(existing.challengeId()).isNotNull().isNotEqualTo(unknown.challengeId());
        assertThat(existing.expiresAt()).isEqualTo(unknown.expiresAt());
        assertThat(existing.getClass()).isEqualTo(unknown.getClass());
    }

    @Test void distributedRateStateSurvivesLaterCapabilityFailure() {
        jdbc.update("update identity_provider_configurations set sender_ref='unavailable-sender'");
        assertThatThrownBy(() -> requests.request(command(AuthenticationIntent.AUTHENTICATE,"failure@example.test","203.0.113.12")))
            .isInstanceOfSatisfying(PaymentApplicationException.class,error -> assertThat(error.code()).isEqualTo("AUTHENTICATION_MODE_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("select count(*) from customer_rate_limit_buckets",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select sum(request_count) from customer_rate_limit_buckets",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from email_otp_challenges",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer_mail_outbox",Integer.class)).isZero();
    }

    @Test void readinessFailsClosedWhenAnyActiveEmailProviderReferencesAnUnavailableSender() {
        assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);

        jdbc.update("update identity_provider_configurations set sender_ref='unavailable-sender'");

        assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(readiness.health().getDetails()).containsEntry("capability","configuration-failure");
    }

    @Test void readinessEvaluatesOnlyActiveSitesWithEmailOtpEnabledAndSelectsTheSameProvider() {
        SiteId enabledMissing=createSite("enabled-missing",true,true);
        assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
        jdbc.update("delete from sites where id=?",enabledMissing.value());

        SiteId inactive=createSite("inactive-broken",false,true);
        providers.save(IdentityProviderConfiguration.create(inactive,"broken-provider",
            new IdentityProviderConfiguration.EmailOtpConfiguration("unavailable-sender","unknown-template")));
        SiteId disabledMode=createSite("mode-disabled-broken",true,false);
        providers.save(IdentityProviderConfiguration.create(disabledMode,"broken-provider",
            new IdentityProviderConfiguration.EmailOtpConfiguration("unavailable-sender","unknown-template")));
        providers.save(IdentityProviderConfiguration.create(siteId,"zzz-broken",
            new IdentityProviderConfiguration.EmailOtpConfiguration("unavailable-sender","unknown-template")));

        assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
    }

    private SiteId createSite(String key,boolean active,boolean emailOtpEnabled) {
        SiteId id=new SiteId(UUID.randomUUID()); UUID account=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,?, 'FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account,key+"-account");
        jdbc.update("insert into sites values (?,?, 'OTP readiness',?,?,'AUTOMATIC',now(),now(),0)",id.value(),key,account,active?"ACTIVE":"DISABLED");
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,?)",id.value(),ORIGIN);
        if(emailOtpEnabled) jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'EMAIL_OTP')",id.value());
        return id;
    }

    private CustomerMailDeliveryPort.Message mailMessage(UUID challengeId) {
        Map<String,Object> row=jdbc.queryForMap("select payload_ciphertext,payload_nonce,payload_key_version from customer_mail_outbox where challenge_id=?",challengeId);
        var encrypted=new EncryptedSecretPort.EncryptedValue((byte[])row.get("payload_ciphertext"),(byte[])row.get("payload_nonce"),(String)row.get("payload_key_version"));
        return new CustomerMailPayloadCodec().decode(encryption.decrypt(encrypted));
    }

    private RequestEmailOtpUseCase.Command command(AuthenticationIntent intent,String email,String source) {
        return new RequestEmailOtpUseCase.Command(SITE_KEY,ORIGIN,intent,email,source);
    }
    private void assertUniformInvalid(VerifyEmailOtpUseCase.Command command) {
        assertThatThrownBy(() -> verifications.verify(command)).isInstanceOfSatisfying(PaymentApplicationException.class,error -> {
            assertThat(error.code()).isEqualTo("OTP_INVALID_OR_EXPIRED");
            assertThat(error.status()).isEqualTo(401);
            assertThat(error.title()).isEqualTo("OTP is invalid or expired");
        });
    }
    private static <T> List<T> concurrently(int count,Callable<T> action) throws Exception {
        ExecutorService executor=Executors.newFixedThreadPool(count);
        CountDownLatch ready=new CountDownLatch(count),start=new CountDownLatch(1);
        try {
            List<Future<T>> futures=new ArrayList<>();
            for(int index=0;index<count;index++) futures.add(executor.submit(() -> {ready.countDown();start.await();return action.call();}));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue(); start.countDown();
            List<T> results=new ArrayList<>(); for(Future<T> future:futures)results.add(future.get(10,TimeUnit.SECONDS));
            return results;
        } finally { executor.shutdownNow(); }
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class FixedOtpConfiguration {
        @Bean @Primary OtpCodeGeneratorPort fixedOtpCodeGenerator(){return () -> "654321";}
    }
}
