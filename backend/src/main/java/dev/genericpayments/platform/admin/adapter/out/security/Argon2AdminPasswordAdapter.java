package dev.genericpayments.platform.admin.adapter.out.security;

import dev.genericpayments.platform.admin.application.port.out.AdminPasswordPort;
import java.util.UUID;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

public final class Argon2AdminPasswordAdapter implements AdminPasswordPort {
    private static final Argon2PasswordEncoder ENCODER = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    // Computed once, before requests; unknown usernames perform the same Argon2id verification work.
    private static final String DUMMY_HASH = ENCODER.encode(UUID.randomUUID().toString());
    @Override public String hash(String password) { return ENCODER.encode(password); }
    @Override public boolean matches(String password, String hash) { return ENCODER.matches(password, hash); }
    @Override public String dummyHash() { return DUMMY_HASH; }
}
