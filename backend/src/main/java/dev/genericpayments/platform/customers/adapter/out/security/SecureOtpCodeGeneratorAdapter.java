package dev.genericpayments.platform.customers.adapter.out.security;

import dev.genericpayments.platform.customers.application.port.out.OtpCodeGeneratorPort;
import java.security.SecureRandom;

/** SecureRandom.nextInt(bound) uses rejection sampling, so all six-digit values are equiprobable. */
public final class SecureOtpCodeGeneratorAdapter implements OtpCodeGeneratorPort {
    private final SecureRandom random;
    public SecureOtpCodeGeneratorAdapter(){this(new SecureRandom());}
    SecureOtpCodeGeneratorAdapter(SecureRandom random){this.random=random;}
    @Override public String issueSixDigitCode(){return "%06d".formatted(random.nextInt(1_000_000));}
}
