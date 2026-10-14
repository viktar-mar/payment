package dev.genericpayments.platform.customers.application.model;

import java.time.Instant;
import java.util.Optional;

/** In-memory evidence only. No source credential is retained; callers must digest/encrypt PII before storage. */
public record VerifiedIdentityEvidence(String canonicalSubject, String issuerKey, Optional<String> verifiedEmail,
                                       Optional<String> tokenId, Instant issuedAt, Instant expiresAt) {
    @Override public String toString() { return "VerifiedIdentityEvidence[redacted]"; }
}
