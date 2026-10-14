package dev.genericpayments.platform.donations.application.port.out;

import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.Optional;

public interface DonationStore {
    Optional<Donation> findById(SiteId siteId,DonationId id);
    Optional<Donation> findByIdForUpdate(SiteId siteId,DonationId id);
    Optional<EncryptedValue> findEncryptedMessage(SiteId siteId,DonationId id);
    /** Preserve existing material for PRESENT; absence/redaction erases the encrypted tuple. */
    Donation save(Donation donation);
    Donation save(Donation donation,EncryptedValue encryptedMessage);
    static String messageReference(DonationId id) { return "donation-message:"+id.value(); }
}
