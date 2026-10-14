package dev.genericpayments.platform.sites.adapter.in.web;

import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.admin.domain.AuditAction;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.shared.adapter.in.web.AdminAuditFacade;
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
    public SiteAdminController(CreateSiteUseCase create, ListSitesUseCase list, AdminAuditFacade audit) {
        this.create = create; this.list = list; this.audit = audit;
    }
    @GetMapping @Transactional(readOnly = true) @Operation(operationId = "listSites")
    public List<SiteResponse> list() { return list.list().stream().map(SiteResponse::from).toList(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional @Operation(operationId = "createSite")
    public SiteResponse create(@Valid @RequestBody CreateSiteRequest body,
                               @AuthenticationPrincipal AuthenticatedAdmin actor, HttpServletRequest request) {
        var site = create.create(new CreateSiteCommand(body.key(), body.displayName(), new PaymentAccountId(body.paymentAccountId()),
            body.allowedOrigins(), body.authenticationModes(), body.paymentMethodPolicy().toDomain()));
        audit.record(AuditAction.SITE_CREATED, "site", site.id().value(), actor, request, Map.of("key", site.key().value()));
        return SiteResponse.from(site);
    }
}
