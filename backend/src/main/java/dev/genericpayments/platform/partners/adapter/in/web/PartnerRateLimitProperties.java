package dev.genericpayments.platform.partners.adapter.in.web;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("payment.partner-api.rate-limit")
public record PartnerRateLimitProperties(@DefaultValue("60") int createPerMinute,@DefaultValue("120") int handoffPerMinute,
    @DefaultValue("300") int readPerMinute,@DefaultValue("10000") int maxKeys) {}
