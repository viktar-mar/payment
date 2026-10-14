package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.customers.domain.IdentityLinkProof;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

public interface IdentityLinkProofStore {
    IdentityLinkProof save(IdentityLinkProof proof);
    Optional<IdentityLinkProof> findForUpdate(UUID id);
    Optional<IdentityLinkProof> findForUpdate(SiteId siteId,List<String> versionedTokenDigests);
    IdentityLinkProof consume(IdentityLinkProof proof,Instant consumedAt);
}
