package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.model.VerifiedIdentityEvidence;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class IdentityResolutionService {
    private final KeyedDigestPort digests; private final EmailNormalizationPort emails;
    private final EncryptedSecretPort encryption; private final IdentityResolutionTransaction transactions;
    public IdentityResolutionService(KeyedDigestPort digests,EmailNormalizationPort emails,EncryptedSecretPort encryption,IdentityResolutionTransaction transactions) {
        this.digests=digests; this.emails=emails; this.encryption=encryption; this.transactions=transactions;
    }
    public Customer resolve(SiteId site,CustomerAuthenticationMode mode,VerifiedIdentityEvidence evidence,Instant now) {
        List<String> subjects=digests.candidates(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,evidence.canonicalSubject()).stream()
            .map(CustomerAuthenticationSupport::reference).toList();
        String activeSubject=subjects.getFirst();
        String emailDigest=null; EncryptedSecretPort.EncryptedValue encrypted=null;
        if(evidence.verifiedEmail().isPresent()) {
            String normalized=emails.normalize(evidence.verifiedEmail().orElseThrow());
            emailDigest=CustomerAuthenticationSupport.reference(digests.digest(KeyedDigestPort.Purpose.EMAIL_LOOKUP,normalized));
            encrypted=encryption.encrypt(normalized);
        }
        return transactions.createOrLoad(site,mode,evidence.issuerKey(),subjects,activeSubject,emailDigest,encrypted,now);
    }
}
