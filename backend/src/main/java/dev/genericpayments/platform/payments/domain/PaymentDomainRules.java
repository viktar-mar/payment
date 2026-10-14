package dev.genericpayments.platform.payments.domain;

import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

final class PaymentDomainRules {
    private PaymentDomainRules() {}

    static void required(Object value, String name) {
        if (value == null) throw new DomainValidationException(name + " is required");
    }

    static void text(String value, String name, int limit) {
        if (value == null || value.isBlank() || value.length() > limit || hasControl(value)) {
            throw new DomainValidationException(name + " must be nonblank safe text of at most " + limit + " characters");
        }
    }

    static void optionalText(String value, String name, int limit) {
        if (value != null) text(value, name, limit);
    }

    static void code(String value, String name) {
        if (value != null && !value.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new DomainValidationException(name + " must be a bounded safe code");
        }
    }

    static void safeTechnicalValue(String value) {
        // Exact canonical UUIDs are safe internal correlation identities, not card numbers.
        try { if(java.util.UUID.fromString(value).toString().equals(value))return; }
        catch(IllegalArgumentException notIdentity) { }
        if (value.matches(".*[^\\s@]+@[^\\s@]+\\.[^\\s@]+.*")
                || value.matches("(?i).*\\bBearer\\s+\\S+.*")
                || value.matches(".*\\b(?:sk|pk)_(?:live|test)_\\S+.*")
                || value.matches(".*(?<![0-9])(?:[0-9][ -]?){13,19}(?![0-9]).*")) {
            throw new DomainValidationException("technical text must not contain sensitive data");
        }
    }

    static void timestamps(Instant createdAt, Instant updatedAt) {
        required(createdAt, "created time");
        time(updatedAt, createdAt);
    }

    static void time(Instant now, Instant previous) {
        required(now, "updated time");
        if (now.isBefore(previous)) throw new DomainValidationException("updated time cannot regress");
    }

    static Map<String, String> metadata(Map<String, String> metadata) {
        required(metadata, "technical metadata");
        if (metadata.size() > 20) throw new DomainValidationException("technical metadata exceeds 20 entries");
        int bytes = 0;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            text(entry.getKey(), "metadata key", 64);
            required(entry.getValue(), "metadata value");
            if (entry.getValue().length() > 500 || hasControl(entry.getValue())) {
                throw new DomainValidationException("metadata value exceeds safe text limits");
            }
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (key.matches(".*(email|token|secret|password|authorization|card|cvc|cvv|iban|account_number).*")) {
                throw new DomainValidationException("technical metadata must not contain sensitive data");
            }
            safeTechnicalValue(entry.getKey());
            safeTechnicalValue(entry.getValue());
            bytes += entry.getKey().getBytes(StandardCharsets.UTF_8).length
                + entry.getValue().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 4096) throw new DomainValidationException("technical metadata exceeds 4096 UTF-8 bytes");
        }
        return Map.copyOf(metadata);
    }

    private static boolean hasControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }
}
