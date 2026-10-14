package dev.genericpayments.platform.donations.application.port.in;
import dev.genericpayments.platform.donations.application.model.DonationViews.*;
import dev.genericpayments.platform.payments.application.model.DonationPaymentViews.Query;
import java.util.UUID;
public interface QueryAdminDonationsUseCase { Page list(Query query); Detail detail(UUID donationId); }
