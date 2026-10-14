package dev.genericpayments.platform.webhooks.adapter.in.web;
import dev.genericpayments.platform.webhooks.application.port.in.*;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackTargetPolicyPort;
import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.admin.domain.AuditAction;
import dev.genericpayments.platform.shared.adapter.in.web.AdminAuditFacade;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/admin")
public class CallbackAdminController {
    private final CallbackTargetPolicyPort targets;private final CallbackConfigurationMutationFacade mutations;private final RotateCallbackSecretUseCase rotate;private final RetryCallbackUseCase retry;private final AdminAuditFacade audit;
    public CallbackAdminController(CallbackTargetPolicyPort targets,CallbackConfigurationMutationFacade mutations,RotateCallbackSecretUseCase rotate,RetryCallbackUseCase retry,AdminAuditFacade audit){this.targets=targets;this.mutations=mutations;this.rotate=rotate;this.retry=retry;this.audit=audit;}
    public record ConfigureRequest(@NotBlank @Size(max=2048)String endpointUrl,@NotNull Boolean enabled) {}
    public record RevealResponse(UUID siteId,String endpointUrl,boolean enabled,int secretVersion,String secret) {
        @Override public String toString(){return "RevealResponse[siteId="+siteId+", secretVersion="+secretVersion+", secret=redacted]";}
    }
    public record RetryRequest(@NotNull RetryCallbackUseCase.Mode mode) {}
    public record RetryResponse(UUID requestId,UUID eventId,RetryCallbackUseCase.Mode mode,String status) {}
    @PutMapping("/sites/{siteId}/callback") @Operation(operationId="configureAdminSiteCallback")
    public ResponseEntity<RevealResponse> configure(@PathVariable UUID siteId,@Valid @RequestBody ConfigureRequest body,@AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request){String normalized=targets.normalize(body.endpointUrl());var reveal=mutations.configure(siteId,normalized,body.enabled(),actor,request);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response(reveal));}
    @PostMapping("/sites/{siteId}/callback/rotate-secret") @Transactional @Operation(operationId="rotateAdminSiteCallbackSecret")
    public ResponseEntity<RevealResponse> rotate(@PathVariable UUID siteId,@AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request){var reveal=rotate.rotate(siteId);record(AuditAction.SITE_CALLBACK_SECRET_ROTATED,reveal,actor,request);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response(reveal));}
    @PostMapping("/outbox/{eventId}/retry") @Transactional @Operation(operationId="retryAdminOutboxEvent")
    public ResponseEntity<RetryResponse> retry(@PathVariable UUID eventId,@Valid @RequestBody RetryRequest body,@AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request) {
        var keys=Collections.list(request.getHeaders("Idempotency-Key"));if(keys.size()!=1)throw new PaymentApplicationException("CALLBACK_CONFIG_INVALID",400,"Exactly one Idempotency-Key required");
        var accepted=retry.retry(eventId,actor.id().value(),body.mode(),keys.getFirst());var r=accepted.request();
        if(accepted.created())audit.record(AuditAction.CALLBACK_RETRY_REQUESTED,"OUTBOX_RETRY_REQUEST",r.id(),actor,request,Map.of("request_id",r.id().toString(),"event_id",eventId.toString(),"site_id",r.siteId().toString(),"mode",body.mode().name(),"status","PENDING"));
        // Replay returns the original accepted representation even after worker completion.
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(new RetryResponse(r.id(),eventId,body.mode(),"PENDING"));
    }
    private void record(AuditAction action,CallbackConfigurationPort.Reveal reveal,AuthenticatedAdmin actor,HttpServletRequest request){var c=reveal.configuration();audit.record(action,"SITE_CALLBACK",c.siteId(),actor,request,Map.of("site_id",c.siteId().toString(),"enabled",Boolean.toString(c.enabled()),"secret_version",Integer.toString(c.secretVersion())));}
    private static RevealResponse response(CallbackConfigurationPort.Reveal reveal){var c=reveal.configuration();return new RevealResponse(c.siteId(),c.endpointUrl(),c.enabled(),c.secretVersion(),reveal.secret());}
}
