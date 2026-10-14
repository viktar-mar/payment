package dev.genericpayments.platform.shared.application.port.out;

public interface RandomTokenPort {
    String issue(int bytes);
}
