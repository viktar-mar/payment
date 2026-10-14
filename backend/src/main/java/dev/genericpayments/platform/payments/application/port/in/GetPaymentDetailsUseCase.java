package dev.genericpayments.platform.payments.application.port.in;
import dev.genericpayments.platform.payments.application.model.AdminPaymentViews.*;
import java.util.UUID;
public interface GetPaymentDetailsUseCase { Details get(UUID paymentId); }
