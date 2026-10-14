package dev.genericpayments.platform.payments.application.port.in;
import dev.genericpayments.platform.payments.application.model.CustomerDonationHistory;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.UUID;
public interface ListCustomerDonationsUseCase {
    CustomerDonationHistory list(Query query);
    record Query(SiteId siteId,UUID customerId,int limit,String cursor) {}
}
