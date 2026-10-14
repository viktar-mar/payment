package dev.genericpayments.platform.payments.application.port.in;

import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.donations.domain.DonationId;
import dev.genericpayments.platform.payments.domain.DonationSnapshot;
import dev.genericpayments.platform.sites.domain.Site;
import java.net.URI;
import java.util.UUID;

public record CreateDonationPaymentCommand(Site site,DonationId donationId,DonationSnapshot snapshot,Money amount,
        UUID customerId,UUID checkoutId,URI origin,String requestId,String idempotencyKey) {}
