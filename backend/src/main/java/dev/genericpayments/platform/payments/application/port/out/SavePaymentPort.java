package dev.genericpayments.platform.payments.application.port.out;

import dev.genericpayments.platform.payments.domain.*;
public interface SavePaymentPort {
 /** Insert with no return URL, or update domain facts preserving existing routing metadata. */
 Payment save(Payment payment);
 /** Atomically insert/update all facts, explicitly replacing routing metadata (including null).
  * The embedded Payment version is always the required optimistic token. */
 LoadPaymentPort.StoredPayment save(LoadPaymentPort.StoredPayment payment);
}
