package dev.genericpayments.platform.payments.application.port.out;

import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

public interface LoadRefundPort {
    Optional<StoredRefund> findByIdForUpdate(SiteId siteId,RefundId id);
 record StoredRefund(Refund value, long version) {
  public StoredRefund { Objects.requireNonNull(value); if(version<0) throw new IllegalArgumentException("version must not be negative"); }
 }
 Optional<StoredRefund> findById(SiteId siteId, RefundId id);
 List<StoredRefund> findByPayment(SiteId siteId, PaymentId id);
 /** Caller holds the parent Payment lock in the same transaction. */
 List<StoredRefund> findReservedByPaymentForUpdate(SiteId siteId, PaymentId id);
}
