package dev.genericpayments.platform.paymentaccounts.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.util.UUID;

public record PaymentAccountId(UUID value) {
    public PaymentAccountId {
        if (value == null) {
            throw new DomainValidationException("payment account id is required");
        }
    }
}
