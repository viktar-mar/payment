package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.model.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.*;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EmailOtpVerificationTransaction {
    record Outcome(CustomerAuthenticationResult result) { boolean succeeded(){return result!=null;} static Outcome invalid(){return new Outcome(null);} }
    private final LockSitePort sites; private final EmailOtpChallengeStore challenges; private final KeyedDigestPort digests;
    private final EncryptedSecretPort encryption; private final CustomerAuthenticationCompletionService completion;
    private final IdentityLinkProofService links; private final EmailOtpPolicyPort policy; private final Clock clock;
    private final IdentityProviderConfigurationStore providers;
    EmailOtpVerificationTransaction(LockSitePort sites,EmailOtpChallengeStore challenges,KeyedDigestPort digests,
            EncryptedSecretPort encryption,CustomerAuthenticationCompletionService completion,IdentityLinkProofService links,
            EmailOtpPolicyPort policy,IdentityProviderConfigurationStore providers,Clock clock) {
        this.sites=sites;this.challenges=challenges;this.digests=digests;this.encryption=encryption;this.completion=completion;
        this.links=links;this.policy=policy;this.providers=providers;this.clock=clock;
    }
    @Transactional
    Outcome verify(SiteId site,String origin,EmailOtpChallengeId id,String code) {
        sites.findByIdForUpdate(site).orElseThrow(CustomerAuthenticationSupport::failed);
        Optional<EmailOtpChallenge> found=challenges.findForUpdate(site,id);
        if(found.isEmpty()) return Outcome.invalid();
        EmailOtpChallenge challenge=found.orElseThrow();
        Instant now=clock.instant(); // sampled after the row lock
        if(!challenge.origin().equals(origin)||!challenge.siteId().equals(site)||!challenge.activeAt(now,policy.maxAttempts())) return Outcome.invalid();
        var provider=providers.findForUpdate(site,challenge.providerKey());
        if(provider.isEmpty()||provider.orElseThrow().status()!=IdentityProviderStatus.ACTIVE
            ||provider.orElseThrow().mode()!=CustomerAuthenticationMode.EMAIL_OTP) return Outcome.invalid();
        if(code==null||!digests.matches(KeyedDigestPort.Purpose.OTP_CODE,code,digest(challenge.otpDigest()))) {
            challenges.save(challenge.failedAttempt(policy.maxAttempts()));
            return Outcome.invalid();
        }
        String normalizedEmail;
        try {
            normalizedEmail=encryption.decrypt(challenges.encryptedEmail(site,id).orElseThrow());
        } catch(RuntimeException failure){throw CustomerAuthenticationSupport.failed();}
        var evidence=new VerifiedIdentityEvidence(normalizedEmail,challenge.providerKey(),Optional.of(normalizedEmail),Optional.empty(),now,challenge.expiresAt());
        challenges.save(challenge.consume(now));
        CustomerAuthenticationResult result=challenge.intent()==AuthenticationIntent.AUTHENTICATE
            ?completion.authenticate(site,CustomerAuthenticationMode.EMAIL_OTP,evidence,now,origin)
            :links.issue(site,origin,CustomerAuthenticationMode.EMAIL_OTP,evidence,now);
        return new Outcome(result);
    }
    private static KeyedDigestPort.DigestValue digest(String reference) {
        int colon=reference.indexOf(':');
        if(colon<1||colon==reference.length()-1) return null;
        return new KeyedDigestPort.DigestValue(reference.substring(colon+1),reference.substring(0,colon));
    }
}
