package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

public interface EmailOtpChallengeStore {
    Optional<EmailOtpChallenge> findForUpdate(SiteId site,EmailOtpChallengeId id);
    List<EmailOtpChallenge> findActiveForUpdate(SiteId site,List<String> emailDigests,String origin);
    EmailOtpChallenge save(EmailOtpChallenge challenge);
    EmailOtpChallenge save(EmailOtpChallenge challenge,EncryptedValue email);
    Optional<EncryptedValue> encryptedEmail(SiteId site,EmailOtpChallengeId id);
    int deleteExpiredWithoutOutboxReferences(Instant expiredBefore,int maximumRows);
    static String emailReference(EmailOtpChallengeId id){return "email-otp:"+id.value();}
}
