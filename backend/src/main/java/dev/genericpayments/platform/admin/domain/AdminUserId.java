package dev.genericpayments.platform.admin.domain;
import java.util.Objects;
import java.util.UUID;
public record AdminUserId(UUID value) {
    public AdminUserId { Objects.requireNonNull(value); }
}
