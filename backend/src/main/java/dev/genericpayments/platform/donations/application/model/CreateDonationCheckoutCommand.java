package dev.genericpayments.platform.donations.application.model;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import java.net.URI;

/** Transient request; the context was verified at admission and is revalidated under the creation lock. */
public record CreateDonationCheckoutCommand(String siteKey,String campaignKey,URI origin,CustomerSessionContext session,
        long amountMinor,String message,String idempotencyKey,String requestId) {
    @Override public String toString() { return "CreateDonationCheckoutCommand[redacted]"; }
}
