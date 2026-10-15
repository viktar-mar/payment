package dev.genericpayments.platform.provider.application.model;

import dev.genericpayments.platform.payments.application.model.SafeFactJson;

import java.util.Objects;
import java.util.UUID;

public record RecoverProviderOperationCommand(
        UUID operationId,
        UUID actorId,
        String providerReference,
        String creationEventId,
        String idempotencyKey,
        String requestId) {
    public RecoverProviderOperationCommand {
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(actorId);
        SafeFactJson.text(providerReference, 255);
        SafeFactJson.text(creationEventId, 255);
        SafeFactJson.text(idempotencyKey, 255);
        SafeFactJson.text(requestId, 128);
    }

    @Override
    public String toString() {
        return "RecoverProviderOperationCommand[redacted]";
    }
}
