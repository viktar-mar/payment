package dev.genericpayments.platform.payments.domain;

import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.shared.domain.DomainValidationException;

public record PriceSnapshot(PriceId priceId, String priceKey, int version, Money money, TaxMode taxMode) {
    public PriceSnapshot {
        PaymentDomainRules.required(priceId, "snapshot price id");
        PaymentDomainRules.text(priceKey, "snapshot price key", 255);
        if (version <= 0) throw new DomainValidationException("snapshot price version must be positive");
        PaymentDomainRules.required(money, "snapshot money");
        PaymentDomainRules.required(taxMode, "snapshot tax mode");
    }
}
