package dev.genericpayments.platform.payments.application.port.out;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.payments.domain.RefundId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.Objects;
import java.util.Optional;

/** Persisted identity only; exposes no Payment/Refund projection or provider truth. */
public interface LoadRefundOwnershipPort {
    record RefundOwnership(SiteId siteId, PaymentId paymentId, PaymentAccountId paymentAccountId) {
        public RefundOwnership {
            Objects.requireNonNull(siteId);Objects.requireNonNull(paymentId);Objects.requireNonNull(paymentAccountId);
        }
    }
    Optional<RefundOwnership> findOwnership(RefundId refundId);
}
