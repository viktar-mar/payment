package dev.genericpayments.platform.partners.adapter.in.web;
import dev.genericpayments.platform.partners.application.port.in.ManageSiteApiKeysUseCase;
import dev.genericpayments.platform.partners.domain.*;
import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.admin.domain.AuditAction;
import dev.genericpayments.platform.shared.adapter.in.web.AdminAuditFacade;
import dev.genericpayments.platform.sites.domain.SiteId;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/admin/sites/{siteId}/api-keys")
public class SiteApiKeyAdminController {
    private final ManageSiteApiKeysUseCase keys; private final AdminAuditFacade audit;
    public SiteApiKeyAdminController(ManageSiteApiKeysUseCase keys,AdminAuditFacade audit) { this.keys=keys; this.audit=audit; }
    public record CreateRequest(@NotEmpty Set<String> scopes, Instant expiresAt) {}
    public record KeyRevealResponse(UUID keyId,UUID siteId,Set<String> scopes,String status,Instant createdAt,Instant expiresAt,String apiKey) {
        @Override public String toString() { return "KeyRevealResponse[keyId="+keyId+", secret=redacted]"; }
    }
    @PostMapping @Transactional @Operation(operationId="createAdminSiteApiKey")
    public ResponseEntity<KeyRevealResponse> create(@PathVariable UUID siteId,@Valid @RequestBody CreateRequest body,
        @AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request) {
        var scopes=new HashSet<PartnerScope>(); body.scopes().forEach(scope -> scopes.add(PartnerScope.parse(scope)));
        var reveal=keys.create(new SiteId(siteId),scopes,body.expiresAt());
        record(AuditAction.SITE_API_KEY_CREATED,reveal.key(),actor,request);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(response(reveal));
    }
    @PostMapping("/{keyId}/rotate") @Transactional @Operation(operationId="rotateAdminSiteApiKey")
    public ResponseEntity<KeyRevealResponse> rotate(@PathVariable UUID siteId,@PathVariable UUID keyId,@AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request) {
        var reveal=keys.rotate(new SiteId(siteId),new SiteApiKeyId(keyId));
        record(AuditAction.SITE_API_KEY_ROTATED,reveal.key(),actor,request);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(response(reveal));
    }
    @PostMapping("/{keyId}/revoke") @Transactional @Operation(operationId="revokeAdminSiteApiKey")
    public ResponseEntity<Void> revoke(@PathVariable UUID siteId,@PathVariable UUID keyId,@AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request) {
        keys.revoke(new SiteId(siteId),new SiteApiKeyId(keyId));
        audit.record(AuditAction.SITE_API_KEY_REVOKED,"SITE_API_KEY",keyId,actor,request,Map.of("key_id",keyId.toString(),"site_id",siteId.toString(),"status","REVOKED"));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private void record(AuditAction action,SiteApiKey key,AuthenticatedAdmin actor,HttpServletRequest request) {
        audit.record(action,"SITE_API_KEY",key.id().value(),actor,request,Map.of("key_id",key.id().value().toString(),"site_id",key.siteId().value().toString(),
            "scopes",key.scopes().stream().map(PartnerScope::value).sorted().toList().toString(),"status",key.status().name()));
    }
    private static KeyRevealResponse response(ManageSiteApiKeysUseCase.Reveal reveal) {
        var key=reveal.key();
        return new KeyRevealResponse(key.id().value(),key.siteId().value(),key.scopes().stream().map(PartnerScope::value).collect(java.util.stream.Collectors.toUnmodifiableSet()),
            key.status().name(),key.createdAt(),key.expiresAt(),reveal.apiKey());
    }
}
