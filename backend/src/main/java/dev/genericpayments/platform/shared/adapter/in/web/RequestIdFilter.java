package dev.genericpayments.platform.shared.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.filter.OncePerRequestFilter;

/** Compatibility facade for existing controller/audit callers and filter injection. */
public abstract class RequestIdFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    public static String requestId(HttpServletRequest request) { return (String) request.getAttribute(ATTRIBUTE); }
}
