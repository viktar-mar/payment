package dev.genericpayments.platform.provider.application.model;

import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;

/** Transient original request facts; no SDK payloads or credentials. */
public sealed interface RecoveryRequestSnapshot {
    PaymentAccount account();

    SiteId siteId();

    record PaymentRequest(PaymentAccount account, Payment payment)
            implements RecoveryRequestSnapshot {
        public SiteId siteId() {
            return payment.siteId();
        }
    }

    record RefundRequest(PaymentAccount account, Payment payment, Refund refund)
            implements RecoveryRequestSnapshot {
        public SiteId siteId() {
            return payment.siteId();
        }
    }

    record ProductRequest(PaymentAccount account, Product product)
            implements RecoveryRequestSnapshot {
        public SiteId siteId() {
            return product.siteId();
        }
    }

    record PriceRequest(
            PaymentAccount account, Product product, Price price, String providerProductReference)
            implements RecoveryRequestSnapshot {
        public SiteId siteId() {
            return price.siteId();
        }
    }
}
