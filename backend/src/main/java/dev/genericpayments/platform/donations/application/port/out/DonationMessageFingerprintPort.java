package dev.genericpayments.platform.donations.application.port.out;
import dev.genericpayments.platform.donations.domain.DonorMessage;
public interface DonationMessageFingerprintPort {
    String fingerprint(DonorMessage message);
    boolean matches(String expected,String actual);
}
