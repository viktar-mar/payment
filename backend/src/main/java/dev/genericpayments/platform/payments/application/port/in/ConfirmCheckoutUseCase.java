package dev.genericpayments.platform.payments.application.port.in;
import dev.genericpayments.platform.payments.application.model.CheckoutView;
public interface ConfirmCheckoutUseCase { CheckoutView confirm(ConfirmCheckoutCommand command); }
