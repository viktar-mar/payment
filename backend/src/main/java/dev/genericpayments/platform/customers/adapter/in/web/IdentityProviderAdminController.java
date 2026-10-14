package dev.genericpayments.platform.customers.adapter.in.web;

import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.admin.domain.AuditAction;
import dev.genericpayments.platform.customers.application.port.in.ManageIdentityProviderConfigurationUseCase;
import dev.genericpayments.platform.customers.application.port.in.ManageIdentityProviderConfigurationUseCase.*;
import dev.genericpayments.platform.customers.application.port.out.PublicJwkSetValidationPort.KeyMetadata;
import dev.genericpayments.platform.customers.application.port.out.JwksDiagnosticsPort.RefreshState;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.adapter.in.web.AdminAuditFacade;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.*;

@RestController
@RequestMapping("/api/v1/admin/sites/{siteId}/identity-providers")
public class IdentityProviderAdminController {
    private final ManageIdentityProviderConfigurationUseCase providers;
    private final AdminAuditFacade audit;
    private final ObjectMapper json;
    public IdentityProviderAdminController(ManageIdentityProviderConfigurationUseCase providers,AdminAuditFacade audit,ObjectMapper json) {
        this.providers=providers; this.audit=audit; this.json=json;
    }

    @GetMapping @Transactional(readOnly=true) @Operation(operationId="listIdentityProviders")
    public List<ProviderResponse> list(@PathVariable UUID siteId) { return providers.list(new SiteId(siteId)).stream().map(ProviderResponse::from).toList(); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional @Operation(operationId="createIdentityProvider")
    public ProviderResponse create(@PathVariable UUID siteId,@RequestBody ProviderRequest body,
            @AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request) {
        if(body.mode()==CustomerAuthenticationMode.EMAIL_OTP && body.expectedVersion()!=null)
            throw new DomainValidationException("expected version is only accepted for updates");
        View result=providers.create(new SiteId(siteId),new Create(body.providerKey(),body.definition(json)));
        record(AuditAction.IDENTITY_PROVIDER_CREATED,siteId,result,actor,request); return ProviderResponse.from(result);
    }

    @GetMapping("/{providerId}") @Transactional(readOnly=true) @Operation(operationId="getIdentityProvider")
    public ProviderResponse get(@PathVariable UUID siteId,@PathVariable UUID providerId) { return ProviderResponse.from(providers.get(new SiteId(siteId),providerId)); }

    @PutMapping("/{providerId}") @Transactional @Operation(operationId="updateIdentityProvider")
    public ProviderResponse update(@PathVariable UUID siteId,@PathVariable UUID providerId,@RequestBody ProviderRequest body,
            @AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request) {
        if(body.expectedVersion()==null) throw new DomainValidationException("expected version is required");
        View result=providers.update(new SiteId(siteId),providerId,new Update(body.providerKey(),body.expectedVersion(),body.definition(json)));
        record(AuditAction.IDENTITY_PROVIDER_UPDATED,siteId,result,actor,request); return ProviderResponse.from(result);
    }

    @PostMapping("/{providerId}/status") @Transactional @Operation(operationId="changeIdentityProviderStatus")
    public ProviderResponse status(@PathVariable UUID siteId,@PathVariable UUID providerId,@RequestBody StatusRequest body,
            @AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request) {
        if(body.status()!=IdentityProviderStatus.DISABLED || body.expectedVersion()==null)
            throw new DomainValidationException("only disabling an active provider is supported");
        View result=providers.disable(new SiteId(siteId),providerId,body.expectedVersion());
        record(AuditAction.IDENTITY_PROVIDER_STATUS_CHANGED,siteId,result,actor,request); return ProviderResponse.from(result);
    }

    private void record(AuditAction action,UUID siteId,View provider,AuthenticatedAdmin actor,HttpServletRequest request) {
        var facts=new HashMap<>(Map.of(
            "site_id",siteId.toString(),"provider_id",provider.id().toString(),"provider_key",provider.providerKey(),
            "mode",provider.mode().name(),"status",provider.status().name(),"version",Long.toString(provider.version()),
            "key_count",Integer.toString(provider.keys().size())));
        if(provider.jwksSourceType()!=null) facts.put("source_type",provider.jwksSourceType().name());
        audit.record(action,"IDENTITY_PROVIDER",provider.id(),actor,request,facts);
    }

    public record ProviderRequest(@Schema(name="provider_key") String providerKey,CustomerAuthenticationMode mode,String issuer,Set<String> audiences,
            @Schema(name="allowed_algorithms") Set<String> allowedAlgorithms,@Schema(name="jwks_source_type") JwksSourceType jwksSourceType,
            @Schema(name="jwks_uri") String jwksUri,
            @Schema(name="public_jwk_set",type="object",implementation=Map.class,additionalProperties=Schema.AdditionalPropertiesValue.TRUE) JsonNode publicJwkSet,
            @Schema(name="email_claim") String emailClaim,@Schema(name="verified_email_claim") String verifiedEmailClaim,
            @Schema(name="expected_version") Long expectedVersion,
            @Schema(name="sender_ref") String senderRef,@Schema(name="template_key") String templateKey) {
        Definition definition(ObjectMapper json) {
            if(mode==null) throw new DomainValidationException("provider mode is required");
            String keys=publicJwkSet==null?null:json.writeValueAsString(publicJwkSet);
            return new Definition(mode,issuer,audiences,allowedAlgorithms,jwksSourceType,jwksUri,keys,emailClaim,verifiedEmailClaim,senderRef,templateKey);
        }
        @Override public String toString() { return "ProviderRequest[redacted]"; }
    }
    public record ProviderResponse(UUID id,@Schema(name="provider_key") String providerKey,CustomerAuthenticationMode mode,
            IdentityProviderStatus status,long version,@Schema(nullable=true) String issuer,Set<String> audiences,
            @Schema(name="allowed_algorithms") Set<String> allowedAlgorithms,@Schema(name="jwks_source_type",nullable=true) JwksSourceType jwksSourceType,
            Readiness readiness,List<KeyMetadata> keys,@Schema(name="sender_ref",nullable=true) String senderRef,
            @Schema(name="template_key",nullable=true) String templateKey,@Schema(name="last_successful_refresh_at",nullable=true) Instant lastSuccessfulRefreshAt,
            @Schema(name="jwks_refresh_state") RefreshState jwksRefreshState) {
        public ProviderResponse { audiences=Set.copyOf(audiences); allowedAlgorithms=Set.copyOf(allowedAlgorithms); keys=List.copyOf(keys); }
        static ProviderResponse from(View view) {
            return new ProviderResponse(view.id(),view.providerKey(),view.mode(),view.status(),view.version(),view.issuer(),view.audiences(),
                view.allowedAlgorithms(),view.jwksSourceType(),view.readiness(),view.keys(),view.senderRef(),view.templateKey(),
                view.lastSuccessfulRefreshAt(),view.jwksRefreshState());
        }
        @Override public String toString() { return "IdentityProviderResponse[redacted]"; }
    }
    public record StatusRequest(IdentityProviderStatus status,@Schema(name="expected_version") Long expectedVersion) {}
}
