package dev.genericpayments.platform.admin.application.port.in;
public record LoginAdminCommand(String username, String password) {
    @Override public String toString() { return "LoginAdminCommand[credentials=REDACTED]"; }
}
