package dev.genericpayments.platform.admin.application.port.in;
public interface LoginAdminUseCase {
    LoginResult login(LoginAdminCommand command, String requestId, String sourceIp);
}
