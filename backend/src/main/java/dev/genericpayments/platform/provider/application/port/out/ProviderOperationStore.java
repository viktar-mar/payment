package dev.genericpayments.platform.provider.application.port.out;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Independent, short receipt transactions. Provider network calls belong outside these transactions. */
public interface ProviderOperationStore {
    /**
     * Fingerprints must cover source, immutable money/tax, resource/version, original account identity
     * and credential alias. Never include a secret or its hash: verified same-account rotation is valid.
     */
    ProviderOperation reserve(PaymentAccount account, ProviderOperation.Kind kind,
        UUID resourceId, String key, String fingerprint);
    Optional<ProviderOperation> find(PaymentAccountId account, String key);
    Optional<ProviderOperation> findById(UUID id);
    /** Join manual recovery transaction; lock operation, original Account, resource in that order. */
    ProviderOperation lockRecovery(ProviderOperation expected);
    ProviderOperation bindRecoveredInTransaction(ProviderOperation expected,String reference,String fingerprint);

    /** Use real UTC, a fresh owner token per acquisition, and a 60-second lease. Returns its fencing version. */
    Optional<ProviderOperation> claim(UUID id, String owner, Instant now, Duration lease);
    ProviderOperation acknowledge(UUID id, String owner, long expectedVersion, String reference);
    /** Exact RESUME receipt only: row lock without Account/lifecycle locks, inside the caller transaction. */
    default ProviderOperation lockResumeReceipt(ProviderOperation expected){throw new UnsupportedOperationException();}
    /** Exact CHANGE/Schedule receipt row lock, before Account/lifecycle locks. */
    default ProviderOperation lockPlanReceipt(ProviderOperation expected){throw new UnsupportedOperationException();}
    default ProviderOperation acknowledgeInTransaction(UUID id,String owner,long version,String reference){throw new UnsupportedOperationException();}
    ProviderOperation unknown(UUID id, String owner, long expectedVersion);

    /**
     * Internal trusted recovery boundary: the caller must first retrieve provider evidence and verify
     * account identity, object kind, local resource mapping and immutable request facts. Browser results
     * or metadata alone are never sufficient proof. This store only compares the expected snapshot and
     * fingerprint, fences its version, refuses live leases and enforces reference uniqueness.
     */
    ProviderOperation bindRecovered(ProviderOperation expected, String reference, String fingerprint);
}
