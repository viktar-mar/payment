package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** A length-delimited, typed business-field encoding; never pass raw transport DTOs. */
public final class CanonicalFingerprint {
    private final DigestPort digest;
    public CanonicalFingerprint(DigestPort digest) { this.digest = Objects.requireNonNull(digest); }
    public String hash(Map<String, ?> businessFields) {
        if (businessFields == null) throw invalid();
        StringBuilder canonical = new StringBuilder();
        append(canonical, businessFields, "", 0, false);
        return digest.sha256Hex(canonical.toString());
    }
    private static void append(StringBuilder out, Object value, String field, int depth, boolean literalMetadata) {
        if (value == null || depth > 16 || out.length() > 65536) throw invalid();
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 100) throw invalid();
            boolean metadataContainer = depth == 1 && field.equals("metadata");
            var sorted = new TreeMap<String,Object>();
            map.forEach((key, entry) -> {
                if (!(key instanceof String name)) throw invalid();
                validateField(name, metadataContainer); sorted.put(name, entry);
            });
            out.append('M').append(sorted.size()).append(':');
            sorted.forEach((key, entry) -> { text(out, 'K', key); append(out, entry, key, depth+1, literalMetadata || metadataContainer); });
        } else if (value instanceof List<?> list) {
            if (list.size() > 100) throw invalid();
            out.append('L').append(list.size()).append(':');
            list.forEach(item -> append(out, item, field, depth+1,literalMetadata));
        } else if (value instanceof UUID id) {
            text(out, 'U', id.toString());
        } else if (value instanceof String string) {
            if (string.length() > 8192 || string.chars().anyMatch(Character::isISOControl)) throw invalid();
            // codePoints combines valid pairs; any remaining surrogate is malformed UTF-16.
            if (string.codePoints().anyMatch(code -> code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE)) throw invalid();
            // Only trusted top-level metadata is literal; business identity/currency fields remain typed.
            String normalized = literalMetadata ? "" : field.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
            if (Set.of("siteid","paymentid","checkoutid","priceid","eventid","actorid","refundid").contains(normalized)) {
                try {
                    UUID id = UUID.fromString(string);
                    if (!id.toString().equalsIgnoreCase(string)) throw invalid();
                    text(out, 'U', id.toString());
                } catch (IllegalArgumentException invalidId) { throw invalid(); }
            } else if (normalized.equals("currency")) {
                String currency = string.toUpperCase(Locale.ROOT);
                try { Currency.getInstance(currency); } catch (IllegalArgumentException invalidCurrency) { throw invalid(); }
                text(out, 'S', currency);
            } else text(out, 'S', string);
        } else if (value instanceof Boolean bool) {
            out.append(bool ? "B1" : "B0");
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger) {
            text(out, 'I', value.toString());
        } else if (value instanceof BigDecimal decimal) {
            if (Math.abs((long)decimal.scale()) > 1000 || decimal.precision() > 1000) throw invalid();
            text(out, 'D', decimal.stripTrailingZeros().toPlainString());
        } else throw invalid();
        if (out.length() > 65536) throw invalid();
    }
    private static void text(StringBuilder out, char type, String value) { out.append(type).append(value.length()).append(':').append(value); }
    /** The same bounded, literal metadata-name policy used by the recursive fingerprint guard. */
    public static void requireMetadataPropertyName(String name) { validateField(name, true); }
    private static void validateField(String field, boolean metadata) {
        if (metadata) {
            if (field.isBlank() || field.length() > 64 || field.chars().anyMatch(Character::isISOControl)
                || field.codePoints().anyMatch(code -> code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE)) throw invalid();
        } else if (!field.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw invalid();
        String normalized = field.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        if (normalized.contains("secret") || normalized.contains("token") || normalized.contains("password")
            || normalized.contains("authorization") || normalized.contains("apikey")
            || Set.of("headers","header","requestid","correlationid","idempotencykey","cookie","cookies","transport").contains(normalized))
            throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid canonical business fields"); }
}
