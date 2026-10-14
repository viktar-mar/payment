package dev.genericpayments.platform.shared.application;

import java.util.Map;

/** Only bounded application-owned codes/titles and safe identifier extensions cross HTTP. */
public final class PaymentApplicationException extends RuntimeException {
    private final String code;
    private final int status;
    private final String title;
    private final Map<String, Object> extensions;
    public PaymentApplicationException(String code, int status, String title) { this(code, status, title, Map.of()); }
    public PaymentApplicationException(String code, int status, String title, Map<String, Object> extensions) {
        super(title);
        if (code == null || !code.matches("[A-Z_]{1,64}") || status < 400 || status > 599 || title == null) throw new IllegalArgumentException("Invalid application error");
        this.code = code; this.status = status; this.title = title;
        this.extensions = Map.copyOf(extensions);
        this.extensions.forEach((key, value) -> {
            if (!java.util.Set.of("payment_id", "operation_id").contains(key) || !(value instanceof java.util.UUID)) throw new IllegalArgumentException("Invalid error extension");
        });
    }
    public String code() { return code; }
    public int status() { return status; }
    public String title() { return title; }
    public Map<String, Object> extensions() { return extensions; }
}
