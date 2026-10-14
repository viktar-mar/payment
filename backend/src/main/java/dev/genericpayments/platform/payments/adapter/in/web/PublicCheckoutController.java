package dev.genericpayments.platform.payments.adapter.in.web;

import dev.genericpayments.platform.payments.application.port.in.*;
import dev.genericpayments.platform.payments.application.service.CheckoutAuthorizationService;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.payments.adapter.in.web.PublicCheckoutDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public")
public final class PublicCheckoutController {
    private final CreateCatalogCheckoutUseCase create;
    private final ConfirmCheckoutUseCase confirm;
    private final GetCheckoutUseCase read;
    public PublicCheckoutController(CreateCatalogCheckoutUseCase create, ConfirmCheckoutUseCase confirm, GetCheckoutUseCase read) {
        this.create = create; this.confirm = confirm; this.read = read;
    }
    @PostMapping("/sites/{siteKey}/checkouts") @Operation(operationId = "createCatalogCheckout")
    public ResponseEntity<CheckoutCreationResponse> create(@PathVariable String siteKey, @Valid @RequestBody CreateCheckoutRequest body,
        @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        var origin = DynamicSiteCorsConfigurationSource.origin(request);
        var customerId = CatalogCheckoutCustomerAuthorizationInterceptor.customerId(request);
        validateKey(request, key);
        var result = create.create(new CreateCatalogCheckoutCommand(siteKey, body.priceKey(), origin, customerId, key, RequestIdFilter.requestId(request)));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(PublicCheckoutDtos.created(result));
    }
    @PostMapping("/checkouts/{checkoutId}/confirm") @Operation(operationId = "confirmCheckout")
    public ResponseEntity<CheckoutResponse> confirm(@PathVariable UUID checkoutId, @Valid @RequestBody ConfirmCheckoutRequest body,
        @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        validateKey(request, key);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PublicCheckoutDtos.read(confirm.confirm(new ConfirmCheckoutCommand(checkoutId,
            DynamicSiteCorsConfigurationSource.origin(request), token(request), body.paymentMethodFamily(), key, RequestIdFilter.requestId(request)))));
    }
    @GetMapping("/checkouts/{checkoutId}") @Operation(operationId = "getCheckout")
    public ResponseEntity<CheckoutResponse> read(@PathVariable UUID checkoutId, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PublicCheckoutDtos.read(read.get(checkoutId, DynamicSiteCorsConfigurationSource.origin(request), token(request))));
    }
    private static String token(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("Authorization"));
        if (values.size() != 1 || !values.getFirst().matches("Bearer [A-Za-z0-9_-]{43}")) throw CheckoutAuthorizationService.invalidToken();
        return values.getFirst().substring(7);
    }
    private static void validateKey(HttpServletRequest request, String key) {
        if (Collections.list(request.getHeaders("Idempotency-Key")).size() != 1) throw new dev.genericpayments.platform.shared.domain.DomainValidationException("Invalid idempotency key");
        try { dev.genericpayments.platform.payments.application.model.IdempotencyRecord.validateText(key, 255); }
        catch (IllegalArgumentException invalid) { throw new dev.genericpayments.platform.shared.domain.DomainValidationException("Invalid idempotency key"); }
    }
    static PaymentApplicationException customerAuthenticationFailed() {
        return new PaymentApplicationException("AUTHENTICATION_FAILED", 401, "Authentication failed");
    }
}
