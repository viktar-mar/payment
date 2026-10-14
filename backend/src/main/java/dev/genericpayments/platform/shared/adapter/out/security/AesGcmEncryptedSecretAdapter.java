package dev.genericpayments.platform.shared.adapter.out.security;

import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmEncryptedSecretAdapter implements EncryptedSecretPort {
    private final SecretKeySpec key;
    private final String version;
    private final SecureRandom random = new SecureRandom();

    public AesGcmEncryptedSecretAdapter(DataEncryptionProperties properties) {
        key = new SecretKeySpec(properties.key(), "AES");
        version = properties.keyVersion();
    }

    @Override public EncryptedValue encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) throw new IllegalArgumentException("Secret is required");
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            return new EncryptedValue(cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), nonce, version);
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException("Secret encryption failed");
        }
    }

    @Override public String decrypt(EncryptedValue value) {
        if (value == null || !version.equals(value.keyVersion())) throw new IllegalStateException("Secret decryption failed");
        try {
            return new String(cipher(Cipher.DECRYPT_MODE, value.nonce()).doFinal(value.ciphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException("Secret decryption failed");
        }
    }

    private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(version.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
