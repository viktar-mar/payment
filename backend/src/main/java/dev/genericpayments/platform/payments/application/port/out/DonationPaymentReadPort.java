package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.payments.application.model.DonationPaymentViews.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;
public interface DonationPaymentReadPort {
    Page list(Query query);
    Optional<Item> find(UUID siteId,UUID donationId);
    List<Item> history(SiteId siteId,UUID customerId,Instant before,UUID beforeId,int limit);
}
