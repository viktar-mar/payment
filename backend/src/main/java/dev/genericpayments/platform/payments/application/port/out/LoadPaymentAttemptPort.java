package dev.genericpayments.platform.payments.application.port.out;

import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

public interface LoadPaymentAttemptPort {
 record StoredAttempt(PaymentAttempt value, long version) {
  public StoredAttempt { Objects.requireNonNull(value); if(version<0) throw new IllegalArgumentException("version must not be negative"); }
 }
 Optional<StoredAttempt> findById(SiteId siteId, PaymentAttemptId id);
 /** Caller must hold a transaction; acknowledgement locks the parent Payment before the Attempt. */
 Optional<StoredAttempt> findByIdForUpdate(SiteId siteId, PaymentAttemptId id);
 List<StoredAttempt> findByPayment(SiteId siteId, PaymentId id);
}
