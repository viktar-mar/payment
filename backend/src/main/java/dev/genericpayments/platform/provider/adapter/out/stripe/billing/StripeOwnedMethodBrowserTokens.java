package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.application.port.out.OwnedMethodBrowserTokens;
import java.util.Objects;

/** Explicit adapter; existing key versions, hashing and MAC implementation are reused unchanged. */
public final class StripeOwnedMethodBrowserTokens implements OwnedMethodBrowserTokens {
    private final StripeBillingGrantTokens tokens;
    public StripeOwnedMethodBrowserTokens(StripeBillingGrantTokens tokens){this.tokens=Objects.requireNonNull(tokens);}
    public String activeVersion(){return tokens.activeVersion();}
    public String token(String version,String descriptor){return tokens.token(version,descriptor);}
    public String hash(String raw){return StripeBillingGrantTokens.hash(raw);}
    public boolean matches(String raw,String digest){return StripeBillingGrantTokens.matches(raw,digest);}
    @Override public String toString(){return "StripeOwnedMethodBrowserTokens[redacted]";}
}
