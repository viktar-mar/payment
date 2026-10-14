package dev.genericpayments.platform.customers.adapter.in.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Each per-minute value is both the token-bucket burst capacity and the number of tokens refilled
 * per 60 seconds. Rates must be 1..10,000 and retained keys must be 1..100,000.
 */
@ConfigurationProperties("payment.customer-authentication.rate-limit")
public record CustomerAuthenticationRateLimitProperties(
    @DefaultValue("20") int oidcBeginPerMinute,
    @DefaultValue("30") int oidcExchangePerMinute,
    @DefaultValue("30") int siteTokenExchangePerMinute,
    @DefaultValue("20") int siteTokenLinkProofPerMinute,
    @DefaultValue("10000") int maxKeys) {
    static final int MAX_REQUESTS_PER_MINUTE=10_000;
    static final int MAX_RETAINED_KEYS=100_000;

    public CustomerAuthenticationRateLimitProperties {
        requireValid(oidcBeginPerMinute,oidcExchangePerMinute,siteTokenExchangePerMinute,siteTokenLinkProofPerMinute,maxKeys);
    }

    static void requireValid(int oidcBegin,int oidcExchange,int siteExchange,int siteLink,int retainedKeys) {
        if(!validRate(oidcBegin)||!validRate(oidcExchange)||!validRate(siteExchange)||!validRate(siteLink)
                ||retainedKeys<1||retainedKeys>MAX_RETAINED_KEYS) {
            throw new IllegalArgumentException("Customer authentication rate limits are outside the supported bounds");
        }
    }

    private static boolean validRate(int value) { return value>=1&&value<=MAX_REQUESTS_PER_MINUTE; }
}
