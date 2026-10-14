package dev.genericpayments.platform.payments.application.port.in;
import dev.genericpayments.platform.payments.application.model.HandoffCreation;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.net.URI;
public interface IssueHandoffUseCase { HandoffCreation issue(SiteId siteId,PaymentId paymentId,URI origin,String idempotencyKey,String requestId); }
