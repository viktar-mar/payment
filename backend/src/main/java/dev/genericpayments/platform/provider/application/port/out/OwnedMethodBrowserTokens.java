package dev.genericpayments.platform.provider.application.port.out;

/** Dedicated existing grant-MAC utility boundary; never provider or customer-session credentials. */
public interface OwnedMethodBrowserTokens {
    String activeVersion();
    String token(String version,String descriptor);
    String hash(String raw);
    boolean matches(String raw,String digest);
}
