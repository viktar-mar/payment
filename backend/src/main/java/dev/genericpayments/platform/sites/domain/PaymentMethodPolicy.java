package dev.genericpayments.platform.sites.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.Objects;
import java.util.Set;

public record PaymentMethodPolicy(PaymentMethodPolicyMode mode, Set<PaymentMethod> allowedMethods) {
    public PaymentMethodPolicy {
        mode = Objects.requireNonNull(mode, "mode");
        allowedMethods = Set.copyOf(Objects.requireNonNull(allowedMethods, "allowedMethods"));
        if (mode == PaymentMethodPolicyMode.AUTOMATIC && !allowedMethods.isEmpty()) {
            throw new DomainValidationException("automatic payment method policy cannot have an allowlist");
        }
        if (mode == PaymentMethodPolicyMode.ALLOWLIST && allowedMethods.isEmpty()) {
            throw new DomainValidationException("payment method allowlist must not be empty");
        }
    }

    public static PaymentMethodPolicy automatic() {
        return new PaymentMethodPolicy(PaymentMethodPolicyMode.AUTOMATIC, Set.of());
    }

    public static PaymentMethodPolicy allow(Set<PaymentMethod> allowedMethods) {
        return new PaymentMethodPolicy(PaymentMethodPolicyMode.ALLOWLIST, allowedMethods);
    }
}
