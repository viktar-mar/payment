package dev.genericpayments.platform.payments.adapter.in.web;

import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.in.PrepareClientPaymentUseCase;
import dev.genericpayments.platform.payments.application.service.CheckoutAuthorizationService;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public final class PublicClientPaymentController {
    private final PrepareClientPaymentUseCase prepare;
    public PublicClientPaymentController(PrepareClientPaymentUseCase prepare) { this.prepare=prepare; }
    @PostMapping("/api/v1/public/checkouts/{checkoutId}/client-session")
    @Operation(operationId="prepareClientPaymentSession")
    public ResponseEntity<PublicCheckoutDtos.ClientPaymentSessionResponse> prepare(@PathVariable UUID checkoutId,
            @RequestBody(required=false) byte[] body,HttpServletRequest request) {
        if(body!=null&&body.length!=0)throw new DomainValidationException("Client session body must be empty");
        var auth=Collections.list(request.getHeaders("Authorization"));
        if(auth.size()!=1||!auth.getFirst().matches("Bearer [A-Za-z0-9_-]{43}"))throw CheckoutAuthorizationService.invalidToken();
        var keys=Collections.list(request.getHeaders("Idempotency-Key"));
        if(keys.size()!=1)throw new DomainValidationException("Invalid idempotency key");
        try { IdempotencyRecord.validateText(keys.getFirst(),255); }
        catch(IllegalArgumentException invalid) { throw new DomainValidationException("Invalid idempotency key"); }
        var result=prepare.prepare(new PrepareClientPaymentCommand(checkoutId,DynamicSiteCorsConfigurationSource.origin(request),
            auth.getFirst().substring(7),keys.getFirst(),RequestIdFilter.requestId(request)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PublicCheckoutDtos.clientSession(result));
    }
}
