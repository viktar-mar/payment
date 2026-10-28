package dev.genericpayments.platform.payments.adapter.in.web;

import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.payments.application.port.out.CheckoutReturnUrlAdminPort;
import dev.genericpayments.platform.payments.application.service.CheckoutReturnUrlAdminService;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import dev.genericpayments.platform.sites.domain.SiteId;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/sites/{siteId}/checkout-return-urls")
public class CheckoutReturnUrlAdminController {
    private final CheckoutReturnUrlAdminService service;
    public CheckoutReturnUrlAdminController(CheckoutReturnUrlAdminService service) { this.service=service; }
    public record UpdateReturnUrl(@NotBlank @Size(max=255) String origin,
            @NotBlank @Size(max=2048) String returnUrl,@NotNull @Min(-1) Long expectedVersion) {}
    @GetMapping @Operation(operationId="listAdminCheckoutReturnUrls")
    public ResponseEntity<List<CheckoutReturnUrlAdminPort.CheckoutReturnUrlPolicy>> list(@PathVariable UUID siteId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(new SiteId(siteId)));
    }
    @PutMapping @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(operationId="updateAdminCheckoutReturnUrl")
    public ResponseEntity<Void> update(@PathVariable UUID siteId,@Valid @RequestBody UpdateReturnUrl body,
            @AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request) {
        try {
            service.update(new SiteId(siteId),URI.create(body.origin()),URI.create(body.returnUrl()),body.expectedVersion(),
                actor.id().value(),RequestIdFilter.requestId(request));
        } catch(IllegalArgumentException invalid) {
            throw new dev.genericpayments.platform.shared.application.PaymentApplicationException("PAYMENT_REQUEST_INVALID",400,"Invalid return URL policy");
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
