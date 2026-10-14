package dev.genericpayments.platform.shared.application.port.out;

public interface EncryptedSecretPort {
    EncryptedValue encrypt(String plaintext);
    String decrypt(EncryptedValue value);

    record EncryptedValue(byte[] ciphertext, byte[] nonce, String keyVersion) {
        public EncryptedValue {
            if (ciphertext == null || ciphertext.length < 16 || nonce == null || nonce.length != 12
                || keyVersion == null || keyVersion.isBlank() || keyVersion.length() > 128)
                throw new IllegalArgumentException("Invalid encrypted secret");
            ciphertext = ciphertext.clone();
            nonce = nonce.clone();
        }
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
        @Override public byte[] nonce() { return nonce.clone(); }
        @Override public String toString() { return "EncryptedValue[redacted]"; }
    }
}
