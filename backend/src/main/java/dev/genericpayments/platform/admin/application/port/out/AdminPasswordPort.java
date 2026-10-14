package dev.genericpayments.platform.admin.application.port.out;
public interface AdminPasswordPort {
    String hash(String password);
    boolean matches(String password, String hash);
    String dummyHash();
}
