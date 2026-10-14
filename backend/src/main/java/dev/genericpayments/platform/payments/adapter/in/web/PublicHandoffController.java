package dev.genericpayments.platform.payments.adapter.in.web;
import dev.genericpayments.platform.payments.application.port.in.ExchangeHandoffUseCase;
import dev.genericpayments.platform.payments.application.service.CheckoutAuthorizationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/public/handoffs")
public final class PublicHandoffController {
    private final ExchangeHandoffUseCase handoffs;
    public PublicHandoffController(ExchangeHandoffUseCase handoffs) { this.handoffs=handoffs; }
    public record ExchangeRequest(String handoffToken) { @Override public String toString() { return "ExchangeRequest[secret=redacted]"; } }
    @PostMapping @Operation(operationId="exchangePublicHandoff")
    public ResponseEntity<PublicCheckoutDtos.CheckoutCreationResponse> exchange(@RequestBody ExchangeRequest body,HttpServletRequest request) {
        var origins=Collections.list(request.getHeaders("Origin"));
        if (origins.size()!=1) throw CheckoutAuthorizationService.invalidToken();
        URI origin;
        try { origin=URI.create(origins.getFirst()); } catch (IllegalArgumentException invalid) { throw CheckoutAuthorizationService.invalidToken(); }
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(PublicCheckoutDtos.created(handoffs.exchange(body.handoffToken(),origin,request.getRemoteAddr())));
    }
}
