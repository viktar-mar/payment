package dev.genericpayments.platform.donations.application.port.out;
import dev.genericpayments.platform.donations.domain.DonorMessage;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue;
public interface DonorMessageEncryptionPort { EncryptedValue encrypt(DonorMessage message); }
