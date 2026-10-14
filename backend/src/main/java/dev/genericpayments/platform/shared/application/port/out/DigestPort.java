package dev.genericpayments.platform.shared.application.port.out;

public interface DigestPort {
    String sha256Hex(String value);
}
