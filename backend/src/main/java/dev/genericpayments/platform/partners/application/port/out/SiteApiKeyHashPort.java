package dev.genericpayments.platform.partners.application.port.out;
public interface SiteApiKeyHashPort {
    String hash(String secret);
    boolean matches(String secret, String hash);
    String dummyHash();
}
