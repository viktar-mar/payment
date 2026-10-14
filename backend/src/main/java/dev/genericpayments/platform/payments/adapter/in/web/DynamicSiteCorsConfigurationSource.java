package dev.genericpayments.platform.payments.adapter.in.web;

import dev.genericpayments.platform.payments.application.service.CheckoutAuthorizationService;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.Site;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.web.cors.*;

public final class DynamicSiteCorsConfigurationSource implements CorsConfigurationSource {
    private static final Pattern CREATE = Pattern.compile("^/api/v1/public/sites/([^/]+)/checkouts$");
    private static final Pattern CHECKOUT = Pattern.compile("^/api/v1/public/checkouts/([^/]+)(/confirm)?$");
    private final CheckoutAuthorizationService authorization;
    private final dev.genericpayments.platform.sites.application.port.out.LoadSitePort sites;
    public DynamicSiteCorsConfigurationSource(CheckoutAuthorizationService authorization, dev.genericpayments.platform.sites.application.port.out.LoadSitePort sites) { this.authorization = authorization; this.sites = sites; }
    public record Route(Site site, String method, dev.genericpayments.platform.payments.application.port.out.RateLimitPort.Operation operation) {}
    public Route route(HttpServletRequest request) {
        String applicationPath = PublicCheckoutRoutePolicy.applicationPath(
            request.getRequestURI(), request.getContextPath());
        if (!PublicCheckoutRoutePolicy.canonicalSyntax(applicationPath)) return null;
        if (applicationPath.equals("/api/v1/public/handoffs")) {
            URI origin;
            try { origin = origin(request); }
            catch (PaymentApplicationException denied) { throw CheckoutAuthorizationService.invalidToken(); }
            var site = sites.findAll().stream().filter(s -> s.status() == dev.genericpayments.platform.sites.domain.SiteStatus.ACTIVE)
                .filter(s -> s.allowedOrigins().stream().anyMatch(allowed ->
                    dev.genericpayments.platform.payments.application.service.PartnerPaymentRules.normalizeOrigin(allowed, true).toString().equals(origin.toString())))
                .findFirst().orElseThrow(CheckoutAuthorizationService::invalidToken);
            // Preflight has no body. Exchange admission derives the actual Site from the locked handoff.
            return new Route(site, "POST", dev.genericpayments.platform.payments.application.port.out.RateLimitPort.Operation.HANDOFF);
        }
        var create = CREATE.matcher(applicationPath);
        if (create.matches() && !"OPTIONS".equals(request.getMethod())
                && request.getHeaders("Authorization").hasMoreElements()) {
            try {
                var authenticatedOrigin = origin(request);
                return new Route(authorization.site(create.group(1), authenticatedOrigin), "POST",
                    dev.genericpayments.platform.payments.application.port.out.RateLimitPort.Operation.CREATE);
            } catch (RuntimeException denied) {
                throw PublicCheckoutController.customerAuthenticationFailed();
            }
        }
        URI origin = origin(request);
        if (create.matches()) return new Route(authorization.site(create.group(1), origin), "POST", dev.genericpayments.platform.payments.application.port.out.RateLimitPort.Operation.CREATE);
        var checkout = CHECKOUT.matcher(applicationPath);
        if (!checkout.matches()) return null;
        UUID id;
        try { id = UUID.fromString(checkout.group(1)); }
        catch (IllegalArgumentException invalid) { throw CheckoutAuthorizationService.invalidToken(); }
        var confirm = checkout.group(2) != null;
        return new Route(authorization.routeSite(id, origin), confirm ? "POST" : "GET", confirm
            ? dev.genericpayments.platform.payments.application.port.out.RateLimitPort.Operation.CONFIRM : dev.genericpayments.platform.payments.application.port.out.RateLimitPort.Operation.READ);
    }
    @Override public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        var route = route(request);
        if (route == null) return null;
        return configuration(route, origin(request).toString());
    }
    CorsConfiguration configuration(Route route, String origin) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(origin));
        config.setAllowedMethods(List.of(route.method(), "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        config.setExposedHeaders(List.of("Retry-After"));
        config.setAllowCredentials(false);
        return config;
    }
    public static URI origin(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("Origin"));
        if (values.size() != 1) throw CheckoutAuthorizationService.originDenied();
        try { return URI.create(values.getFirst()); }
        catch (IllegalArgumentException malformed) { throw CheckoutAuthorizationService.originDenied(); }
    }
}
