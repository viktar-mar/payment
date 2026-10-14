package dev.genericpayments.platform.donations.adapter.out.security;
import dev.genericpayments.platform.donations.application.port.out.DonorMessageEncryptionPort;
import dev.genericpayments.platform.donations.domain.DonorMessage;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import org.springframework.stereotype.Component;

@Component
public final class AesGcmDonorMessageEncryptionAdapter implements DonorMessageEncryptionPort {
    private final EncryptedSecretPort encryption;
    public AesGcmDonorMessageEncryptionAdapter(EncryptedSecretPort encryption) { this.encryption=encryption; }
    @Override public EncryptedSecretPort.EncryptedValue encrypt(DonorMessage message) {
        // A fixed framing byte also permits a valid zero-code-point message with the shared nonempty-secret port.
        return encryption.encrypt("\u0001"+message.value());
    }
}
