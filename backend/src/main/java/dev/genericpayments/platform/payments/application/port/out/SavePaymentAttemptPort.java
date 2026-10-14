package dev.genericpayments.platform.payments.application.port.out;

import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

public interface SavePaymentAttemptPort {
 LoadPaymentAttemptPort.StoredAttempt insert(PaymentAttempt attempt, Instant now);
 LoadPaymentAttemptPort.StoredAttempt save(LoadPaymentAttemptPort.StoredAttempt attempt);
}

