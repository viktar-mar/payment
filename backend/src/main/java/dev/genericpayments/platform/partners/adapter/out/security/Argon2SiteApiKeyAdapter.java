package dev.genericpayments.platform.partners.adapter.out.security;
import dev.genericpayments.platform.partners.application.port.out.SiteApiKeyHashPort;
import java.util.UUID;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
public final class Argon2SiteApiKeyAdapter implements SiteApiKeyHashPort {
    private static final Argon2PasswordEncoder ENCODER = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private static final String DUMMY_HASH=ENCODER.encode(UUID.randomUUID().toString());
    public String hash(String secret) { return ENCODER.encode(secret); }
    public boolean matches(String secret,String hash) { return ENCODER.matches(secret,hash); }
    public String dummyHash() { return DUMMY_HASH; }
}
