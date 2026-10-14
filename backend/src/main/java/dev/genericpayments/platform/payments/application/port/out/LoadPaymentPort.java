package dev.genericpayments.platform.payments.application.port.out;

import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

public interface LoadPaymentPort {
 record LockedPayment(Payment value, Instant reconciliationLeaseExpiresAt) {
  public LockedPayment { Objects.requireNonNull(value); }
  public boolean hasLiveLease(Instant now) { return reconciliationLeaseExpiresAt!=null && reconciliationLeaseExpiresAt.isAfter(now); }
 }
 /** Locks and returns the projection and lease from the same row; caller retains the transaction. */
 Optional<LockedPayment> findForAttemptStart(SiteId siteId, PaymentId id);
 /** Persistence-only routing metadata; URL policy validation belongs to checkout creation. */
 record StoredPayment(Payment value, String returnUrl) {
  public StoredPayment {
   Objects.requireNonNull(value, "payment");
   if (returnUrl != null && (returnUrl.isBlank() || returnUrl.length() > 2048
       || returnUrl.chars().anyMatch(Character::isISOControl)))
    throw new IllegalArgumentException("return URL must be bounded text without control characters");
   if (returnUrl != null && value.source() != PaymentSource.DYNAMIC)
    throw new IllegalArgumentException("only dynamic payments have a return URL");
  }
 }
 Optional<StoredPayment> findStateById(SiteId siteId, PaymentId id);
 Optional<Payment> findById(SiteId siteId, PaymentId id);
 List<Payment> findBySiteId(SiteId siteId);
 /** Exact Customer ownership query ordered by created_at DESC, id DESC; boundary values are both null or both present. */
 List<Payment> findCustomerHistory(SiteId siteId, UUID customerId, Instant beforeCreatedAt,
                                   PaymentId beforePaymentId, int maxResults);
 /** Caller must hold a transaction for the complete locked operation. */
 Optional<Payment> findByIdForUpdate(SiteId siteId, PaymentId id);
 boolean tryAcquireReconciliationLease(SiteId siteId, PaymentId id, String owner, Instant now, Instant expiresAt);
 boolean releaseReconciliationLease(SiteId siteId, PaymentId id, String owner);
}
