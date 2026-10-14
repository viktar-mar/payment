package dev.genericpayments.platform.donations.adapter.out.security;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.donations.domain.DonorMessage;
import dev.genericpayments.platform.shared.adapter.out.security.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort.EncryptedValue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;

class DonationMessageProtectionTest {
    static final String AES=Base64.getEncoder().encodeToString(new byte[32]);
    static final String HMAC=Base64.getEncoder().encodeToString("C".repeat(32).getBytes(StandardCharsets.UTF_8));
    @Test void actualPublicExampleProvidesAcceptedDistinct32ByteFingerprintKey() throws Exception {
        var example=new Properties();
        try(var reader=java.nio.file.Files.newBufferedReader(java.nio.file.Path.of("../infrastructure/.env.example"))) { example.load(reader); }
        var key=example.getProperty("PAYMENT_DONATION_MESSAGE_FINGERPRINT_KEY");
        var aes=example.getProperty("PAYMENT_DATA_ENCRYPTION_KEY");
        assertThat(Base64.getDecoder().decode(key)).hasSize(32).isNotEqualTo(Base64.getDecoder().decode(aes));
        assertThatCode(()->new HmacDonationMessageFingerprintAdapter(key,example.getProperty("PAYMENT_DONATION_MESSAGE_FINGERPRINT_VERSION"),aes)).doesNotThrowAnyException();
    }
    @Test void normalizedMessagesHaveKeyedVersionedPurposeSeparatedFingerprints() throws Exception {
        var a=new HmacDonationMessageFingerprintAdapter(HMAC,"v1",AES);
        var value=a.fingerprint(DonorMessage.of("cafe\u0301"));
        assertThat(value).matches("v1:[a-f0-9]{64}").isEqualTo(a.fingerprint(DonorMessage.of("café")));
        assertThat(a.matches(value,a.fingerprint(DonorMessage.of("café")))).isTrue();
        assertThat(a.matches(value,a.fingerprint(DonorMessage.of("other")))).isFalse();
        assertThat(a.matches(null,value)).isFalse();
        assertThat(value).doesNotContain(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("café".getBytes(StandardCharsets.UTF_8))));
        assertThat(value).isNotEqualTo(new HmacDonationMessageFingerprintAdapter(HMAC,"v2",AES).fingerprint(DonorMessage.of("café")));
        assertThat(a.fingerprint(null)).isNotEqualTo(a.fingerprint(DonorMessage.of("")));
        assertThatThrownBy(()->new HmacDonationMessageFingerprintAdapter(AES,"v1",AES)).hasMessageContaining("distinct versioned");
        assertThatThrownBy(()->new HmacDonationMessageFingerprintAdapter(HMAC,"bad",AES)).hasMessageContaining("distinct versioned");
    }
    @Test void ciphertextIsRandomizedAuthenticatedVersionedAndEmptySafe() {
        var shared=new AesGcmEncryptedSecretAdapter(new DataEncryptionProperties(AES,"v1"));
        var adapter=new AesGcmDonorMessageEncryptionAdapter(shared);
        var first=adapter.encrypt(DonorMessage.of("café"));var second=adapter.encrypt(DonorMessage.of("café"));
        assertThat(first.keyVersion()).isEqualTo("v1");assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(shared.decrypt(first)).isEqualTo("\u0001café");
        assertThat(shared.decrypt(adapter.encrypt(DonorMessage.of("")))).isEqualTo("\u0001");
        byte[] tampered=first.ciphertext();tampered[0]^=1;
        assertThatThrownBy(()->shared.decrypt(new EncryptedValue(tampered,first.nonce(),first.keyVersion()))).hasMessage("Secret decryption failed");
        assertThatThrownBy(()->shared.decrypt(new EncryptedValue(first.ciphertext(),first.nonce(),"v2"))).hasMessage("Secret decryption failed");
    }
}
