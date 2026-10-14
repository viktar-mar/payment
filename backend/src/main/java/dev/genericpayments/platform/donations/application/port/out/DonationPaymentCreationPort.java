package dev.genericpayments.platform.donations.application.port.out;

import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.payments.application.model.CheckoutCreation;
import dev.genericpayments.platform.sites.domain.*;
import java.net.URI;
import java.util.UUID;

/** Payments owns financial persistence, checkout storage and non-emitting creation preflight. All operations join the caller transaction. */
public interface DonationPaymentCreationPort {
    record Checkout(CheckoutCreation checkout,DonationId donationId,DonationCampaignSnapshot snapshot,UUID customerId) {}
    void preflight(Site site);
    Checkout create(Site site,DonationId donationId,DonationCampaignSnapshot snapshot,Money amount,UUID customerId,
                    UUID checkoutId,URI origin,String requestId,String idempotencyKey);
    Checkout load(SiteId site,UUID checkoutId,String token);
}
