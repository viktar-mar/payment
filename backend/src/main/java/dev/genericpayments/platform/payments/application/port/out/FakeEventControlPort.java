package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.payments.application.port.in.SimulateFakeEventUseCase.Target;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.domain.*;
import java.time.Instant;
public interface FakeEventControlPort {
    String enqueue(PaymentAccount account,Payment payment,Refund refund,Target target,PaymentEventType type,long sequence,Instant occurredAt);
}
