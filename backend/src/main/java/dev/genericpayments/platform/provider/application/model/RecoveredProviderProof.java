package dev.genericpayments.platform.provider.application.model;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;

import java.util.UUID;

public record RecoveredProviderProof(
        UUID operationId,
        PaymentAccountId accountId,
        String providerReference,
        String fingerprint,
        String creationEventId,
        RecoveryRequestSnapshot request) {}
