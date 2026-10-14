package dev.genericpayments.platform.payments.application.port.in;
import dev.genericpayments.platform.payments.application.model.HandoffCreation;
public interface CreateDynamicPaymentUseCase { HandoffCreation create(CreateDynamicPaymentCommand command); }
