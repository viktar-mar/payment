package dev.genericpayments.platform.admin.adapter.in.web;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("payment.security")
public record AdminSecurityProperties(@DefaultValue("true") boolean secureCookie,
                                      @DefaultValue("PT8H") Duration idleTimeout,
                                      @DefaultValue("PT24H") Duration absoluteTimeout) {}
