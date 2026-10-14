package dev.genericpayments.platform.admin.adapter.in.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("payment.admin.bootstrap")
public record AdminBootstrapProperties(String username, String password) {
    @Override public String toString() { return "AdminBootstrapProperties[credentials=REDACTED]"; }
}
