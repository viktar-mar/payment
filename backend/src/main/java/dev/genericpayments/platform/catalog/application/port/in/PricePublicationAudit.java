package dev.genericpayments.platform.catalog.application.port.in;

import java.util.Objects;
import java.util.UUID;

/** Server-derived identity and request context for an Admin publication. */
public record PricePublicationAudit(UUID actorId, String requestId, String sourceIp) {
    public PricePublicationAudit { Objects.requireNonNull(actorId, "actorId"); }
}
