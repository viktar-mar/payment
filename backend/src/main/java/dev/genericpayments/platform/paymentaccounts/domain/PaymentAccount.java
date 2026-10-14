package dev.genericpayments.platform.paymentaccounts.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record PaymentAccount(PaymentAccountId id, String key, PaymentProviderType provider,
                             PaymentAccountMode mode, PaymentAccountStatus status,
                             String externalAccountId, String credentialsRef) {
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{2,62}");

    public PaymentAccount {
        id = Objects.requireNonNull(id, "id");
        key = normalizeKey(key);
        provider = Objects.requireNonNull(provider, "provider");
        mode = Objects.requireNonNull(mode, "mode");
        status = Objects.requireNonNull(status, "status");
        externalAccountId = requireNonBlank(externalAccountId, "external account id");
        credentialsRef = requireNonBlank(credentialsRef, "credentials ref");
    }

    public static PaymentAccount create(PaymentAccountId id, String key, PaymentProviderType provider,
                                        PaymentAccountMode mode, String externalAccountId,
                                        String credentialsRef) {
        return new PaymentAccount(id, key, provider, mode, PaymentAccountStatus.ACTIVE,
            externalAccountId, credentialsRef);
    }

    public PaymentAccount disable() {
        return new PaymentAccount(id, key, provider, mode, PaymentAccountStatus.DISABLED,
            externalAccountId, credentialsRef);
    }

    public static String normalizeKey(String value) {
        if (value == null) {
            throw new DomainValidationException("payment account key is required");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!KEY_PATTERN.matcher(normalized).matches()) {
            throw new DomainValidationException("payment account key must match [a-z0-9][a-z0-9-]{2,62}");
        }
        return normalized;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(name + " must not be blank");
        }
        return value;
    }
}
