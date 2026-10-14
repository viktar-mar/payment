package dev.genericpayments.platform.payments.application.port.in;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.donations.domain.DonationId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.List;

/** Safe linkage query inside the caller's Site/Customer-locked anonymization transaction. */
public interface CustomerDonationQuery {
    List<DonationId> findDonationIds(SiteId siteId, CustomerId customerId);
}
