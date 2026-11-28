package dev.genericpayments.platform.provider.application.model;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable admitted scope, never authority derived from a caller's clock reference. */
public record ClockOwner(UUID intent, UUID run, PaymentAccountId account, SiteId site,
        CustomerId customer, String externalAccount, String credentialsRef, String apiVersion,
        long initialFrozenTime, String name, String createKey, String createFingerprint, Instant admittedAt) {
    public ClockOwner {
        Objects.requireNonNull(intent); Objects.requireNonNull(run); Objects.requireNonNull(account);
        Objects.requireNonNull(site); Objects.requireNonNull(customer); Objects.requireNonNull(admittedAt);
        for (var value : new String[]{externalAccount,credentialsRef,apiVersion,name,createKey,createFingerprint})
            if (value == null || value.isBlank() || value.length()>255 || value.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid clock ownership identity");
        if (initialFrozenTime<=0) throw new IllegalArgumentException("Invalid frozen time");
    }
    public String customerKey() { return "billing_customer_"+account.value()+"_"+site.value()+"_"+customer.value(); }
}
