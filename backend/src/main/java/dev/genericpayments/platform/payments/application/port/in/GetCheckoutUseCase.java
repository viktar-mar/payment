package dev.genericpayments.platform.payments.application.port.in;
import dev.genericpayments.platform.payments.application.model.CheckoutView;
import java.net.URI;
import java.util.UUID;
public interface GetCheckoutUseCase { CheckoutView get(UUID checkoutId, URI origin, String token); }
