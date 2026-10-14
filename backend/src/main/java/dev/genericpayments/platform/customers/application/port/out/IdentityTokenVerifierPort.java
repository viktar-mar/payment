package dev.genericpayments.platform.customers.application.port.out;

import dev.genericpayments.platform.customers.application.model.VerifiedIdentityEvidence;
import dev.genericpayments.platform.customers.domain.IdentityProviderConfiguration;
import java.time.Instant;

public interface IdentityTokenVerifierPort {
    VerifiedIdentityEvidence verifyOidc(IdentityProviderConfiguration configuration,String token,String expectedNonce,Instant now);
    VerifiedIdentityEvidence verifySiteToken(IdentityProviderConfiguration configuration,String token,Instant now);
}
