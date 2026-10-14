package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.port.in.RequestEmailOtpUseCase;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EmailOtpCreationTransaction {
    private final LockSitePort sites; private final EmailOtpChallengeStore challenges; private final CustomerMailOutboxPort outbox;
    private final KeyedDigestPort digests; private final EncryptedSecretPort encryption; private final OtpCodeGeneratorPort codes;
    private final EmailOtpPolicyPort properties; private final CustomerMailTemplatePort templates;
    private final CustomerMailPayloadCodec payloads=new CustomerMailPayloadCodec();
    EmailOtpCreationTransaction(LockSitePort sites,EmailOtpChallengeStore challenges,CustomerMailOutboxPort outbox,
            KeyedDigestPort digests,EncryptedSecretPort encryption,OtpCodeGeneratorPort codes,EmailOtpPolicyPort properties,
            CustomerMailTemplatePort templates) {
        this.sites=sites;this.challenges=challenges;this.outbox=outbox;this.digests=digests;this.encryption=encryption;this.codes=codes;
        this.properties=properties;this.templates=templates;
    }
    @Transactional
    RequestEmailOtpUseCase.Result create(SiteId site,String origin,AuthenticationIntent intent,String normalizedEmail,
            List<String> emailDigests,String providerKey,String senderRef,String templateKey,Instant now) {
        sites.findByIdForUpdate(site).orElseThrow(CustomerAuthenticationSupport::failed);
        var current=challenges.findActiveForUpdate(site,emailDigests,origin);
        if(!current.isEmpty()&&current.stream().allMatch(challenge -> challenge.intent()==intent&&challenge.providerKey().equals(providerKey))
            &&current.getFirst().activeAt(now,properties.maxAttempts())
            &&now.isBefore(current.getFirst().createdAt().plus(properties.resendCooldown()))) {
            var existing=current.getFirst();
            current.stream().skip(1).forEach(challenge -> challenges.save(challenge.invalidate(now)));
            return new RequestEmailOtpUseCase.Result(existing.id().value(),existing.expiresAt());
        }
        EmailOtpChallengeId previous=current.isEmpty()?null:current.getFirst().id();
        current.forEach(old -> challenges.save(old.invalidate(now)));
        String code=codes.issueSixDigitCode();
        if(code==null||!code.matches("[0-9]{6}")) throw new IllegalStateException("OTP generator failed");
        var id=new EmailOtpChallengeId(UUID.randomUUID());
        Instant expiry=now.plus(properties.ttl());
        String otpDigest=CustomerAuthenticationSupport.reference(digests.digest(KeyedDigestPort.Purpose.OTP_CODE,code));
        var encryptedEmail=encryption.encrypt(normalizedEmail);
        var challenge=EmailOtpChallenge.create(id,site,origin,intent,providerKey,emailDigests.getFirst(),EmailOtpChallengeStore.emailReference(id),otpDigest,previous,now,expiry);
        challenges.save(challenge,encryptedEmail);
        var template=templates.render(templateKey,code,properties.ttl());
        String rendered=payloads.encode(new CustomerMailDeliveryPort.Message(senderRef,normalizedEmail,template.subject(),template.body()));
        outbox.save(CustomerMailJob.pending(UUID.randomUUID(),site,id,now),encryption.encrypt(rendered));
        return new RequestEmailOtpUseCase.Result(id.value(),expiry);
    }
}
