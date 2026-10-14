package dev.genericpayments.platform.donations.adapter.in.web;

import dev.genericpayments.platform.donations.application.model.CreateDonationCheckoutCommand;
import dev.genericpayments.platform.donations.application.port.in.CreateDonationCheckoutUseCase;
import dev.genericpayments.platform.donations.application.port.out.DonationPaymentCreationPort;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public final class PublicDonationCheckoutController {
    private final CreateDonationCheckoutUseCase checkout;
    public PublicDonationCheckoutController(CreateDonationCheckoutUseCase checkout) {this.checkout=checkout;}
    @Schema(name="PublicDonationCheckoutRequest")
    public record Request(@NotNull @Schema(name="amount_minor",requiredMode=Schema.RequiredMode.REQUIRED) Long amountMinor,
        @Schema(nullable=true) String message) {
        @Override public String toString(){return "DonationCheckoutRequest[redacted]";}
    }
    @Schema(name="PublicDonationCheckoutContext")
    public record DonationContext(@Schema(name="donation_id",requiredMode=Schema.RequiredMode.REQUIRED) UUID donationId,
        @Schema(name="campaign_key",requiredMode=Schema.RequiredMode.REQUIRED) String campaignKey,
        @Schema(name="campaign_version",requiredMode=Schema.RequiredMode.REQUIRED) long campaignVersion) {}
    @Schema(name="PublicDonationCheckoutResponse")
    public record Response(@Schema(name="checkout_id",requiredMode=Schema.RequiredMode.REQUIRED) UUID checkoutId,
        @Schema(name="checkout_token",requiredMode=Schema.RequiredMode.REQUIRED) String checkoutToken,
        @Schema(name="payment_id",requiredMode=Schema.RequiredMode.REQUIRED) UUID paymentId,
        @Schema(name="amount_minor",requiredMode=Schema.RequiredMode.REQUIRED) long amountMinor,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String currency,
        @Schema(types={"null"},requiredMode=Schema.RequiredMode.REQUIRED) Object price,
        @Schema(name="provider_type",requiredMode=Schema.RequiredMode.REQUIRED) String providerType,
        @Schema(name="confirmation_mode",requiredMode=Schema.RequiredMode.REQUIRED) String confirmationMode,
        @Schema(name="provider_client_payload",requiredMode=Schema.RequiredMode.REQUIRED) Map<String,Object> providerClientPayload,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String status,
        @Schema(name="expires_at",requiredMode=Schema.RequiredMode.REQUIRED) Instant expiresAt,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) DonationContext donation) {
        @Override public String toString(){return "DonationCheckoutResponse[checkoutId="+checkoutId+", token=redacted]";}
    }
    @PostMapping("/api/v1/public/sites/{siteKey}/donation-campaigns/{campaignKey}/checkouts")
    @Operation(operationId="createDonationCheckout")
    @ApiResponse(responseCode="201",description="Created")
    public ResponseEntity<Response> create(@PathVariable String siteKey,@PathVariable String campaignKey,
        @Valid @RequestBody Request body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        if(Collections.list(request.getHeaders("Idempotency-Key")).size()!=1) throw new DomainValidationException("invalid idempotency key");
        var admitted=DonationCheckoutPublicRequestFilter.admitted(request);
        var result=checkout.create(new CreateDonationCheckoutCommand(siteKey,campaignKey,admitted.origin(),admitted.session(),
            body.amountMinor(),body.message(),key,RequestIdFilter.requestId(request)));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(response(result));
    }
    private Response response(DonationPaymentCreationPort.Checkout result) {
        var c=result.checkout();var v=c.view();
        return new Response(v.checkoutId(),c.checkoutToken(),v.paymentId(),v.amountMinor(),v.currency(),null,v.providerType(),
            v.confirmationMode(),v.providerClientPayload(),v.status(),v.expiresAt(),
            new DonationContext(result.donationId().value(),result.snapshot().campaignKey().value(),result.snapshot().version()));
    }
}
