package dev.genericpayments.platform.customers.application.service;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@Import(EmailOtpServiceIntegrationTest.FixedOtpConfiguration.class)
@TestPropertySource(properties={
    "payment.customer-security.active-key-version=v2",
    "payment.customer-security.known-keys.v2=QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI="
})
class EmailOtpDigestRotationIntegrationTest extends PostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc; @Autowired IdentityProviderConfigurationStore providers;
    @Autowired EmailOtpChallengeStore challenges; @Autowired CustomerMailOutboxPort outbox;
    @Autowired KeyedDigestPort digests; @Autowired EncryptedSecretPort encryption;
    @Autowired RequestEmailOtpUseCase requests;
    private SiteId site;

    @BeforeEach void prepare() {
        clearDatabase(); site=new SiteId(UUID.randomUUID()); UUID account=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'rotation-account','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        jdbc.update("insert into sites values (?,'rotation-site','Rotation',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site.value(),account);
        jdbc.update("insert into site_allowed_origins values (?,'https://rotation.example.test')",site.value());
        jdbc.update("insert into site_authentication_modes values (?,'EMAIL_OTP')",site.value());
        providers.save(IdentityProviderConfiguration.create(site,"email-provider",
            new IdentityProviderConfiguration.EmailOtpConfiguration("test-default","otp-default")));
    }

    @Test void retainedEmailDigestCannotBypassCooldownAndIsInvalidatedBySuccessor() {
        String email="rotation@example.test";
        var candidates=digests.candidates(KeyedDigestPort.Purpose.EMAIL_LOOKUP,email);
        assertThat(candidates).extracting(KeyedDigestPort.DigestValue::keyVersion).containsExactly("v2","v1");
        var id=new EmailOtpChallengeId(UUID.randomUUID());
        String legacy=reference(candidates.get(1));
        String otp=reference(digests.digest(KeyedDigestPort.Purpose.OTP_CODE,"654321"));
        challenges.save(EmailOtpChallenge.create(id,site,"https://rotation.example.test",AuthenticationIntent.AUTHENTICATE,
            "email-provider",legacy,EmailOtpChallengeStore.emailReference(id),otp,null,INITIAL_TIME,INITIAL_TIME.plus(Duration.ofMinutes(10))),
            encryption.encrypt(email));
        String payload=new CustomerMailPayloadCodec().encode(new CustomerMailDeliveryPort.Message(
            "test-default",email,"Legacy","Legacy payload"));
        outbox.save(CustomerMailJob.pending(UUID.randomUUID(),site,id,INITIAL_TIME),encryption.encrypt(payload));

        var reused=requests.request(command(email));
        assertThat(reused.challengeId()).isEqualTo(id.value());
        assertThat(jdbc.queryForObject("select count(*) from email_otp_challenges where consumed_at is null and invalidated_at is null",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_mail_outbox",Integer.class)).isOne();

        TestClock.set(INITIAL_TIME.plusSeconds(61));
        var successor=requests.request(command(email));
        assertThat(successor.challengeId()).isNotEqualTo(id.value());
        assertThat(jdbc.queryForObject("select invalidated_at is not null from email_otp_challenges where id=?",Boolean.class,id.value())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from email_otp_challenges where consumed_at is null and invalidated_at is null",Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from customer_mail_outbox",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select email_digest_key_version from email_otp_challenges where id=?",String.class,successor.challengeId())).isEqualTo("v2");
    }

    private static String reference(KeyedDigestPort.DigestValue digest){return digest.keyVersion()+":"+digest.value();}
    private static RequestEmailOtpUseCase.Command command(String email) {
        return new RequestEmailOtpUseCase.Command("rotation-site","https://rotation.example.test",AuthenticationIntent.AUTHENTICATE,
            email,"203.0.113.40");
    }
}
