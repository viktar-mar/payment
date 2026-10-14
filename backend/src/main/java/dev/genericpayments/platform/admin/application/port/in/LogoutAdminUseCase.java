package dev.genericpayments.platform.admin.application.port.in;
public interface LogoutAdminUseCase {
    void logout(String rawSessionToken, String requestId, String sourceIp);
}
