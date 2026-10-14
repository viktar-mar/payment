package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.payments.domain.*;
import java.time.Instant;
public interface PaymentOutboxPort { void snapshot(Payment payment,PaymentEvent event,Instant now); }
