package dev.genericpayments.platform.customers.application.port.out;

import java.time.Duration;

public interface CustomerMailTemplatePort {
    record Rendered(String subject,String body) {}
    boolean available(String templateKey);
    Rendered render(String templateKey,String code,Duration ttl);
}
