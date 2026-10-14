package dev.genericpayments.platform.payments.adapter.in.web;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("payment.public-api.rate-limit")
public record PublicRateLimitProperties(@DefaultValue("20") int createPerMinute, @DefaultValue("30") int confirmPerMinute,
    @DefaultValue("120") int readPerMinute, @DefaultValue("10000") int maxKeys) {}
