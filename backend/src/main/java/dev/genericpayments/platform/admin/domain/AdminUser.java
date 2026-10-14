package dev.genericpayments.platform.admin.domain;
import java.time.Instant;
public record AdminUser(AdminUserId id, String username, String passwordHash, Status status, Instant createdAt) {
    public enum Status { ACTIVE, DISABLED }
    @Override public String toString() { return "AdminUser[id=" + id + ", status=" + status + "]"; }
}
