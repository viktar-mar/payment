package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.payments.application.model.CheckoutSession;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

public final class CheckoutAuthorizationService {
    private final CheckoutSessionPort sessions;
    private final LoadSitePort sites;
    private final LoadPaymentPort payments;
    private final DigestPort digest;
    private final Clock clock;
    public CheckoutAuthorizationService(CheckoutSessionPort sessions, LoadSitePort sites, LoadPaymentPort payments, DigestPort digest, Clock clock) {
        this.sessions = sessions; this.sites = sites; this.payments = payments; this.digest = digest; this.clock = clock;
    }
    public record Authorized(CheckoutSession session, Site site, Payment payment) {}
    public Site site(String key, URI origin) {
        Site site;
        try { site = sites.findByKey(SiteKey.of(key)).orElseThrow(() -> unavailable()); }
        catch (dev.genericpayments.platform.shared.domain.DomainValidationException invalid) { throw unavailable(); }
        requireSite(site); requireOrigin(site, origin); return site;
    }
    public Site routeSite(UUID checkoutId, URI origin) {
        var session = session(checkoutId);
        var site = sites.findById(session.siteId()).orElseThrow(() -> unavailable());
        requireSite(site); requireOrigin(site, origin);
        if (!session.origin().toString().equals(origin.toString())) throw originDenied();
        return site;
    }
    public Authorized authorize(UUID id, URI origin, String token, String scope) {
        var session = session(id);
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}") || !MessageDigest.isEqual(
            digest.sha256Hex(token).getBytes(StandardCharsets.US_ASCII), session.tokenHash().getBytes(StandardCharsets.US_ASCII))) throw invalidToken();
        var site = routeSite(id, origin);
        if (!clock.instant().isBefore(session.expiresAt())) throw expired(session.paymentId().value());
        if (!session.scopes().contains(scope)) throw invalidToken();
        var payment = payments.findById(session.siteId(), session.paymentId()).orElseThrow(() -> invalidToken());
        if (!payment.siteId().equals(site.id()) || !payment.id().equals(session.paymentId())) throw invalidToken();
        return new Authorized(session, site, payment);
    }
    public CheckoutSession session(UUID id) { return sessions.findById(id).orElseThrow(() -> invalidToken()); }
    public static void requireOrigin(Site site, URI origin) {
        PartnerPaymentRules.origin(site, origin);
    }
    private static void requireSite(Site site) { if (site.status() != SiteStatus.ACTIVE) throw unavailable(); }
    public static PaymentApplicationException invalidToken() { return new PaymentApplicationException("CHECKOUT_TOKEN_INVALID", 401, "Invalid checkout token"); }
    public static PaymentApplicationException unavailable() { return new PaymentApplicationException("SITE_NOT_AVAILABLE", 404, "Site not available"); }
    public static PaymentApplicationException originDenied() { return new PaymentApplicationException("ORIGIN_NOT_ALLOWED", 403, "Origin not allowed"); }
    public static PaymentApplicationException expired(UUID payment) { return new PaymentApplicationException("CHECKOUT_EXPIRED", 410, "Checkout expired", Map.of("payment_id", payment)); }
}
