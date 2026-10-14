package dev.genericpayments.platform.donations.application.port.in;
import dev.genericpayments.platform.donations.application.model.DonationViews.Redaction;
import java.util.UUID;
public interface RedactDonationMessageUseCase {
    Redaction redact(UUID donationId,UUID confirmation,UUID actorId,String idempotencyKey);
}
