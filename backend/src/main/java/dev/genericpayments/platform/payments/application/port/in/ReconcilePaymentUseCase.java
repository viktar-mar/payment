package dev.genericpayments.platform.payments.application.port.in;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
public interface ReconcilePaymentUseCase { Payment reconcile(SiteId siteId,PaymentId paymentId,String requestId); }
