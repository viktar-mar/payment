package dev.genericpayments.platform.payments.domain;

import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PaymentEvent(UUID id, PaymentId paymentId, long sequence, SiteId siteId,
                           PaymentAccountId paymentAccountId, PaymentEventType type, PaymentEventSource source,
                           Money money, String providerEventId, String providerObjectId,
                           Instant occurredAt, Instant receivedAt, String requestId, String idempotencyKey,
                           String payloadHash, Map<String, String> technicalMetadata) {
    public PaymentEvent {
        PaymentDomainRules.required(id, "event id");
        PaymentDomainRules.required(paymentId, "payment id");
        PaymentDomainRules.required(siteId, "site id");
        PaymentDomainRules.required(paymentAccountId, "payment account id");
        if (sequence <= 0) throw new DomainValidationException("event sequence must be positive");
        PaymentDomainRules.required(type, "event type");
        PaymentDomainRules.required(source, "event source");
        PaymentDomainRules.required(occurredAt, "occurred time");
        PaymentDomainRules.required(receivedAt, "received time");
        PaymentDomainRules.text(requestId, "request id", 128);
        PaymentDomainRules.optionalText(providerEventId, "provider event id", 255);
        PaymentDomainRules.optionalText(providerObjectId, "provider object id", 255);
        PaymentDomainRules.optionalText(idempotencyKey, "idempotency key", 255);
        if (payloadHash == null || !payloadHash.matches("[0-9a-fA-F]{64}")) {
            throw new DomainValidationException("payload hash must be SHA-256 hex");
        }
        technicalMetadata = PaymentDomainRules.metadata(technicalMetadata);
    }
}
