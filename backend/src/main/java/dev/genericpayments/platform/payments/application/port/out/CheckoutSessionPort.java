package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.payments.application.model.CheckoutSession;
import java.util.*;
public interface CheckoutSessionPort {
    void save(CheckoutSession session);
    Optional<CheckoutSession> findById(UUID id);
}
