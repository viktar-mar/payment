package dev.genericpayments.platform.payments.application.port.in;

import dev.genericpayments.platform.payments.application.model.CheckoutCreation;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.sites.domain.Site;
import java.util.UUID;

/** Donation creation cannot open an independent transaction. */
public interface CreateDonationPaymentUseCase {
    record Result(CheckoutCreation checkout,Payment payment) {}
    void preflight(Site site);
    Result create(CreateDonationPaymentCommand command);
    Result load(SiteId siteId,UUID checkoutId,String token);
}
