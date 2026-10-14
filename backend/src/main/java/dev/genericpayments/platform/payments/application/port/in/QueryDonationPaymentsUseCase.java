package dev.genericpayments.platform.payments.application.port.in;
import dev.genericpayments.platform.payments.application.model.DonationPaymentViews.*;
import java.util.*;
public interface QueryDonationPaymentsUseCase {
    Page list(Query query);
    Optional<Item> find(UUID siteId,UUID donationId);
}
