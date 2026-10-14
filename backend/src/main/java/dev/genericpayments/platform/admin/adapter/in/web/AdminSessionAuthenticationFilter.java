package dev.genericpayments.platform.admin.adapter.in.web;

import dev.genericpayments.platform.admin.application.service.AdminSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class AdminSessionAuthenticationFilter extends OncePerRequestFilter {
    public static final String COOKIE_NAME = "PAYMENT_ADMIN_SESSION";
    private final AdminSessionService sessions;
    public AdminSessionAuthenticationFilter(AdminSessionService sessions) { this.sessions = sessions; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/v1/partner/")
            || request.getRequestURI().startsWith("/api/v1/customer/");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        sessions.authenticate(rawToken(request)).ifPresent(admin -> {
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(admin, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            SecurityContextHolder.setContext(context);
        });
        chain.doFilter(request, response);
    }
    static String rawToken(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        String token = null;
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                if (token != null) return null;
                token = cookie.getValue();
            }
        }
        return token;
    }
}
