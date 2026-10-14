package dev.genericpayments.platform.shared.adapter.out.security;

import dev.genericpayments.platform.shared.application.port.out.RandomTokenPort;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public final class SecureRandomTokenAdapter implements RandomTokenPort {
    private final SecureRandom random = new SecureRandom();
    @Override public String issue(int bytes) {
        if (bytes <= 0 || bytes > 4096) throw new IllegalArgumentException("Invalid token size");
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
