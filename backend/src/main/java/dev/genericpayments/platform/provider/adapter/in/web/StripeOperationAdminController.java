package dev.genericpayments.platform.provider.adapter.in.web;

import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.payments.application.model.SafeFactJson;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.in.*;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin/provider-operations")
public class StripeOperationAdminController {
    private final InspectProviderOperationsUseCase reads;
    private final RecoverProviderOperationUseCase recovery;
    public StripeOperationAdminController(InspectProviderOperationsUseCase reads,RecoverProviderOperationUseCase recovery) {
        this.reads=reads; this.recovery=recovery;
    }
    public record RecoveryRequest(@NotBlank @Size(max=255) String providerReference,
            @NotBlank @Size(max=255) String creationEventId) {}
    public record RecoveryAccepted(String status) {}
    @GetMapping @Operation(operationId="inspectAdminProviderOperations")
    public ResponseEntity<List<ProviderOperationView>> inspect(@RequestParam("payment_id") UUID paymentId) {
        return ok(reads.inspect(new PaymentId(paymentId)));
    }
    @GetMapping("/unresolved") @Operation(operationId="listAdminUnresolvedProviderOperations")
    public ResponseEntity<List<ProviderOperationView>> unresolved(@RequestParam(required=false) UUID after,@RequestParam(defaultValue="20") int limit) {
        return ok(reads.unresolved(after,limit));
    }
    @GetMapping("/dead-references") @Operation(operationId="listAdminDeadProviderReferences")
    public ResponseEntity<List<DeadProviderReferenceView>> dead(@RequestParam(required=false) UUID after,@RequestParam(defaultValue="20") int limit) {
        return ok(reads.deadReferences(after,limit));
    }
    @PostMapping("/{operationId}/recover") @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId="recoverAdminProviderOperation",parameters=@io.swagger.v3.oas.annotations.Parameter(
        name="Idempotency-Key",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,required=true,
        schema=@io.swagger.v3.oas.annotations.media.Schema(maxLength=255)))
    public ResponseEntity<RecoveryAccepted> recover(@PathVariable UUID operationId,@Valid @RequestBody RecoveryRequest body,
            @AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request) {
        var keys=Collections.list(request.getHeaders("Idempotency-Key"));
        if(keys.size()!=1) throw invalid();
        try {
            SafeFactJson.text(keys.getFirst(),255);
            recovery.recover(new RecoverProviderOperationCommand(operationId,actor.id().value(),body.providerReference(),
                body.creationEventId(),keys.getFirst(),RequestIdFilter.requestId(request)));
        } catch(IllegalArgumentException invalid) { throw invalid(); }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(new RecoveryAccepted("ACCEPTED"));
    }
    private static PaymentApplicationException invalid() { return new PaymentApplicationException("PAYMENT_REQUEST_INVALID",400,"Invalid recovery request"); }
    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
