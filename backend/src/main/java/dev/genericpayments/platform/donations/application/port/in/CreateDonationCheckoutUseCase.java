package dev.genericpayments.platform.donations.application.port.in;

import dev.genericpayments.platform.donations.application.model.CreateDonationCheckoutCommand;
import dev.genericpayments.platform.donations.application.port.out.DonationPaymentCreationPort;

public interface CreateDonationCheckoutUseCase {
    DonationPaymentCreationPort.Checkout create(CreateDonationCheckoutCommand command);
}
