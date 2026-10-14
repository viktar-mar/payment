package dev.genericpayments.platform.payments.application.port.in;
import dev.genericpayments.platform.payments.domain.Refund;
public interface CreateRefundUseCase { Refund create(CreateRefundCommand command); }
