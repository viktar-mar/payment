package dev.genericpayments.platform.payments.adapter.in.web;

import dev.genericpayments.platform.payments.application.port.out.RateLimitPort;
import dev.genericpayments.platform.shared.adapter.in.web.ApiProblem;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** CORS and no-store apply before security, binding, rate rejection, and controller errors. */
public final class PublicRateLimitFilter extends OncePerRequestFilter {
    private final DynamicSiteCorsConfigurationSource cors;
    private final RateLimitPort limiter;
    private final ObjectMapper mapper;
    public PublicRateLimitFilter(DynamicSiteCorsConfigurationSource cors, RateLimitPort limiter, ObjectMapper mapper) { this.cors = cors; this.limiter = limiter; this.mapper = mapper; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PublicCheckoutRoutePolicy.routeFamily(PublicCheckoutRoutePolicy.applicationPath(
            request.getRequestURI(), request.getContextPath()));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        try {
            var route = cors.route(request);
            if (route == null) throw PublicCheckoutController.customerAuthenticationFailed();
            var origin = DynamicSiteCorsConfigurationSource.origin(request).toString();
            var config = cors.configuration(route, origin);
            response.addHeader("Vary", "Origin");
            response.addHeader("Vary", "Access-Control-Request-Method");
            response.addHeader("Vary", "Access-Control-Request-Headers");
            var method = request.getMethod();
            if ("OPTIONS".equals(method)) {
                var requested = request.getHeader("Access-Control-Request-Method");
                var requestedHeaders = request.getHeader("Access-Control-Request-Headers");
                var headers = requestedHeaders == null || requestedHeaders.isBlank() ? List.<String>of()
                    : Arrays.stream(requestedHeaders.split(",")).map(String::trim).toList();
                if (!route.method().equals(requested) || config.checkHeaders(headers) == null) throw dev.genericpayments.platform.payments.application.service.CheckoutAuthorizationService.originDenied();
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Access-Control-Allow-Methods", route.method() + ", OPTIONS");
                response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Idempotency-Key");
                response.setStatus(200); return;
            }
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Expose-Headers", "Retry-After");
            var decision = route.operation() == RateLimitPort.Operation.HANDOFF ? new RateLimitPort.Decision(true, 0)
                : limiter.acquire(new RateLimitPort.Key(route.site().id().value(), request.getRemoteAddr(), route.operation()));
            if (!decision.allowed()) {
                response.setHeader("Retry-After", Integer.toString(decision.retryAfterSeconds()));
                ApiProblem.write(request, response, mapper, 429, "RATE_LIMIT_EXCEEDED", "Rate limit exceeded"); return;
            }
            chain.doFilter(request, response);
        } catch (PaymentApplicationException error) {
            ApiProblem.write(request, response, mapper, error.status(), error.code(), error.title());
        }
    }
}
