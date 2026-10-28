package dev.genericpayments.platform.sites.adapter.in.web;

import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.admin.domain.AuditAction;
import dev.genericpayments.platform.customers.application.port.in.ManageIdentityProviderConfigurationUseCase;
import dev.genericpayments.platform.customers.application.port.in.ManageIdentityProviderConfigurationUseCase.*;
import dev.genericpayments.platform.customers.domain.CustomerAuthenticationMode;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.shared.adapter.in.web.AdminAuditFacade;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.application.port.in.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin/v1/sites")
public class SiteAdminController {
    private final CreateSiteUseCase create;
    private final ListSitesUseCase list;
    private final AdminAuditFacade audit;
    private final ManageIdentityProviderConfigurationUseCase providers;
    public SiteAdminController(CreateSiteUseCase create, ListSitesUseCase list, AdminAuditFacade audit,
                               ManageIdentityProviderConfigurationUseCase providers) {
        this.create = create; this.list = list; this.audit = audit; this.providers = providers;
    }
    @GetMapping @Transactional(readOnly = true) @Operation(operationId = "listSites")
    public List<SiteResponse> list() { return list.list().stream().map(SiteResponse::from).toList(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional @Operation(operationId = "createSite")
    public SiteResponse create(@Valid @RequestBody CreateSiteRequest body,
                               @AuthenticationPrincipal AuthenticatedAdmin actor, HttpServletRequest request) {
        var site = create.create(new CreateSiteCommand(body.key(), body.displayName(), new PaymentAccountId(body.paymentAccountId()),
            body.allowedOrigins(), body.authenticationModes(), body.paymentMethodPolicy().toDomain()));
        View provider = null;
        if (body.emailOtpProvider() != null) {
            var inline = body.emailOtpProvider();
            provider = providers.create(site.id(), new Create(inline.providerKey(), new Definition(
                CustomerAuthenticationMode.EMAIL_OTP, null, null, null, null, null, null, null, null,
                inline.senderRef(), inline.templateKey())));
            if (provider.readiness() != Readiness.READY)
                throw new DomainValidationException("inline email provider configuration is unavailable");
        }
        audit.record(AuditAction.SITE_CREATED, "site", site.id().value(), actor, request, Map.of("key", site.key().value()));
        if (provider != null) {
            audit.record(AuditAction.IDENTITY_PROVIDER_CREATED, "IDENTITY_PROVIDER", provider.id(), actor, request, Map.of(
                "site_id", site.id().value().toString(), "provider_id", provider.id().toString(), "provider_key", provider.providerKey(),
                "mode", provider.mode().name(), "status", provider.status().name(), "version", Long.toString(provider.version()),
                "key_count", Integer.toString(provider.keys().size())));
        }
        return SiteResponse.from(site);
    }
}
