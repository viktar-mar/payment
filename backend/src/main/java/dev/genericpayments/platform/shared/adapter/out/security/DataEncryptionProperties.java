package dev.genericpayments.platform.shared.adapter.out.security;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("payment.data-encryption")
public final class DataEncryptionProperties {
    private final byte[] key;
    private final String keyVersion;

    public DataEncryptionProperties(String keyBase64, String keyVersion) {
        byte[] decoded;
        try {
            if (keyBase64 == null) throw new IllegalArgumentException();
            decoded = Base64.getDecoder().decode(keyBase64);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("A valid 32-byte data encryption key is required");
        }
        if (decoded.length != 32) throw new IllegalArgumentException("A valid 32-byte data encryption key is required");
        if (keyVersion == null || keyVersion.isBlank() || keyVersion.length() > 128)
            throw new IllegalArgumentException("A nonblank data encryption key identifier of at most 128 characters is required");
        this.key = decoded;
        this.keyVersion = keyVersion;
    }

    byte[] key() { return key.clone(); }
    public String keyVersion() { return keyVersion; }
    @Override public String toString() { return "DataEncryptionProperties[redacted]"; }
}
