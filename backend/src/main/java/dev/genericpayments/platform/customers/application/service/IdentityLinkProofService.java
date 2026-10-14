package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.model.*;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.port.out.RandomTokenPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
class IdentityLinkProofService {
    private final IdentityLinkProofStore proofs; private final KeyedDigestPort digests; private final RandomTokenPort random;
    private final CustomerAuthenticationLifetimePort lifetimes;
    IdentityLinkProofService(IdentityLinkProofStore proofs,KeyedDigestPort digests,RandomTokenPort random,
            CustomerAuthenticationLifetimePort lifetimes) {
        this.proofs=proofs; this.digests=digests; this.random=random; this.lifetimes=lifetimes;
    }
    CustomerAuthenticationResult issue(SiteId site,String origin,CustomerAuthenticationMode mode,VerifiedIdentityEvidence evidence,Instant now) {
        String raw=random.issue(32);
        List<String> subjects=CustomerIdentityLinkService.references(
            digests.candidates(KeyedDigestPort.Purpose.IDENTITY_SUBJECT,evidence.canonicalSubject()));
        if(subjects.isEmpty())throw new IllegalStateException("No retained identity subject digest is configured");
        String subject=subjects.getFirst();
        String token=CustomerAuthenticationSupport.reference(digests.digest(KeyedDigestPort.Purpose.LINK_PROOF,raw));
        Instant expiry=now.plus(lifetimes.linkProofTtl());
        IdentityLinkProof saved=proofs.save(new IdentityLinkProof(UUID.randomUUID(),site,origin,mode,evidence.issuerKey(),subject,subjects,
            token,now,now,expiry,null,0));
        return new CustomerAuthenticationResult.LinkProofIssued(raw,saved.expiresAt());
    }
}
