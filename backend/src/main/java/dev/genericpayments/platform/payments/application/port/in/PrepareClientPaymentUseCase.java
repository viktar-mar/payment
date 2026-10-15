package dev.genericpayments.platform.payments.application.port.in;
import dev.genericpayments.platform.payments.application.model.*;
public interface PrepareClientPaymentUseCase {
    ClientPaymentSession prepare(PrepareClientPaymentCommand command);
}
