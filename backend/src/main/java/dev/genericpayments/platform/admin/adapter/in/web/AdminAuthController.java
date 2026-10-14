package dev.genericpayments.platform.admin.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.admin.application.port.in.LoginAdminCommand;
import dev.genericpayments.platform.admin.application.service.AdminSessionService;
import dev.genericpayments.platform.admin.application.service.InvalidCredentialsException;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/v1/auth")
public class AdminAuthController {
    private final AdminSessionService sessions;
    private final AdminSecurityProperties properties;
    public AdminAuthController(AdminSessionService sessions, AdminSecurityProperties properties) {
        this.sessions = sessions;
        this.properties = properties;
    }

    @PostMapping("/sessions")
    @Operation(operationId = "loginAdmin")
    @ApiResponse(responseCode = "204", description = "Admin session created", content = @Content)
    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public ResponseEntity<Void> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                                      HttpServletResponse response) {
        var result = sessions.login(new LoginAdminCommand(body.username(), body.password()),
            RequestIdFilter.requestId(request), request.getRemoteAddr());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(result.rawSessionToken()).build().toString());
        ((CsrfToken) request.getAttribute(CsrfToken.class.getName())).getToken();
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/session", produces = "application/json")
    @Operation(operationId = "getAdminSession")
    @ApiResponse(responseCode = "200", description = "Authenticated admin session",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = SessionResponse.class)))
    public SessionResponse current(@AuthenticationPrincipal AuthenticatedAdmin admin) {
        return new SessionResponse(admin.id().value().toString(), admin.username(), admin.expiresAt());
    }

    @DeleteMapping("/session")
    @Operation(operationId = "logoutAdmin")
    @ApiResponse(responseCode = "204", description = "Admin session revoked", content = @Content)
    @Transactional
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(AdminSessionAuthenticationFilter.rawToken(request), RequestIdFilter.requestId(request),
            request.getRemoteAddr());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("").maxAge(0).build().toString());
        return ResponseEntity.noContent().build();
    }

    private ResponseCookie.ResponseCookieBuilder cookie(String token) {
        return ResponseCookie.from(AdminSessionAuthenticationFilter.COOKIE_NAME, token)
            .httpOnly(true).secure(properties.secureCookie()).sameSite("Strict").path("/");
    }

    public record LoginRequest(@NotBlank @Size(max = 120) String username,
                               @NotBlank @Size(max = 1024) String password) {
        @Override public String toString() { return "LoginRequest[credentials=REDACTED]"; }
    }
    public record SessionResponse(String id, String username,
                                  @JsonProperty("expires_at") @Schema(name = "expires_at") Instant expiresAt) {}
}
