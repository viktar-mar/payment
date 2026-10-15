package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.admin.application.port.out.AuditLogPort;
import dev.genericpayments.platform.admin.domain.AuditAction;
import dev.genericpayments.platform.admin.domain.AuditEntry;
import dev.genericpayments.platform.payments.application.port.out.CheckoutReturnUrlAdminPort;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class CheckoutReturnUrlAdminService {
    private final CheckoutReturnUrlAdminPort policies;
    private final LoadSitePort sites;
    private final AuditLogPort audit;
    private final Clock clock;
    public CheckoutReturnUrlAdminService(CheckoutReturnUrlAdminPort policies, LoadSitePort sites, AuditLogPort audit, Clock clock) {
        this.policies=policies; this.sites=sites; this.audit=audit; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public List<CheckoutReturnUrlAdminPort.CheckoutReturnUrlPolicy> list(SiteId siteId) {
        sites.findById(siteId).orElseThrow(PartnerPaymentRules::notFound);
        return policies.list(siteId);
    }
    @Transactional
    public void update(SiteId siteId, URI origin, URI returnUrl, long expectedVersion, UUID actorId, String requestId) {
        var site=sites.findById(siteId).orElseThrow(PartnerPaymentRules::notFound);
        if(expectedVersion < -1 || expectedVersion == Long.MAX_VALUE || actorId == null
                || !site.allowedOrigins().contains(origin)) throw invalid();
        PartnerPaymentRules.returnUrl(returnUrl.toString(),PartnerPaymentRules.normalizeOrigin(origin,true));
        // The persistence boundary also applies the runtime policy validation before CAS.
        if(!policies.compareAndSet(siteId,origin,returnUrl,expectedVersion))
            throw new PaymentApplicationException("CHECKOUT_RETURN_URL_CONFLICT",409,"Return URL version conflict");
        audit.append(new AuditEntry(UUID.randomUUID(),"ADMIN",actorId.toString(),AuditAction.CHECKOUT_RETURN_URL_UPDATED,
            "SITE",siteId.value().toString(),requestId,null,
            Map.of("origin",origin.toString(),"version",Long.toString(expectedVersion+1)),clock.instant()));
    }
    private static PaymentApplicationException invalid() {
        return new PaymentApplicationException("PAYMENT_REQUEST_INVALID",400,"Invalid return URL policy");
    }
}
