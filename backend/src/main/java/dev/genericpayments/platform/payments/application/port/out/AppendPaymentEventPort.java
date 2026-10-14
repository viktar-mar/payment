package dev.genericpayments.platform.payments.application.port.out;

import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

public interface AppendPaymentEventPort {
 /** Allocates and inserts atomically; the factory must only construct the immutable fact. */
 PaymentEvent append(PaymentId paymentId, java.util.function.LongFunction<PaymentEvent> fact);
}

