package dev.genericpayments.platform.payments.application.port.in;
import dev.genericpayments.platform.payments.application.model.AdminPaymentViews.*;
public interface ListPaymentsUseCase { Page list(Query query); }
