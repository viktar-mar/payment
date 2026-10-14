package dev.genericpayments.platform.donations.adapter.out.security;

import dev.genericpayments.platform.donations.application.port.out.DonationMessageFingerprintPort;
import dev.genericpayments.platform.donations.domain.DonorMessage;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class HmacDonationMessageFingerprintAdapter implements DonationMessageFingerprintPort {
    private final SecretKeySpec key;
    private final String version;
    public HmacDonationMessageFingerprintAdapter(@Value("${payment.donation-message-fingerprint.key-base64}") String encoded,
        @Value("${payment.donation-message-fingerprint.key-version}") String version,
        @Value("${payment.data-encryption.key-base64}") String encryptionKey) {
        try {
            byte[] bytes=Base64.getDecoder().decode(encoded);
            if(bytes.length!=32 || MessageDigest.isEqual(bytes,Base64.getDecoder().decode(encryptionKey))
                || version==null || !version.matches("v[1-9][0-9]{0,8}")) throw new IllegalArgumentException();
            this.key=new SecretKeySpec(bytes,"HmacSHA256"); this.version=version;
        } catch(RuntimeException invalid) { throw new IllegalArgumentException("A distinct versioned 32-byte donation fingerprint key is required"); }
    }
    @Override public String fingerprint(DonorMessage message) {
        if(message==null)return "message:absent";
        try {
            var mac=Mac.getInstance("HmacSHA256");mac.init(key);
            mac.update(("donation-message-fingerprint:"+version+"\u0000").getBytes(StandardCharsets.UTF_8));
            return version+":"+HexFormat.of().formatHex(mac.doFinal(message.value().getBytes(StandardCharsets.UTF_8)));
        } catch(GeneralSecurityException failure) { throw new IllegalStateException("Donation fingerprint unavailable"); }
    }
    @Override public boolean matches(String expected,String actual) {
        return expected!=null && actual!=null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8));
    }
}
