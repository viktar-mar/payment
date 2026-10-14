package dev.genericpayments.platform.donations.application.port.out;
import dev.genericpayments.platform.payments.application.model.DonationPaymentViews.*;
import java.util.*;
/** Safe financial context through the Payments application boundary. */
public interface DonationPaymentQueryPort { Page list(Query query); Optional<Item> find(UUID siteId,UUID donationId); }
