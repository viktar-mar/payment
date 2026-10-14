package dev.genericpayments.platform.payments.application.port.in;
import dev.genericpayments.platform.payments.application.model.CheckoutCreation;
public interface CreateCatalogCheckoutUseCase { CheckoutCreation create(CreateCatalogCheckoutCommand command); }
