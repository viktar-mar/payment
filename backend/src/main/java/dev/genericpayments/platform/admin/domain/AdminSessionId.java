package dev.genericpayments.platform.admin.domain;
import java.util.Objects;
import java.util.UUID;
public record AdminSessionId(UUID value) {
    public AdminSessionId { Objects.requireNonNull(value); }
}
