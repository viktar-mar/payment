package dev.genericpayments.platform.payments.adapter.in.web;
import dev.genericpayments.platform.customers.application.port.in.GetPartnerCustomerUseCase;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.payments.application.port.in.*;
import dev.genericpayments.platform.payments.application.port.out.LoadPaymentPort;
import dev.genericpayments.platform.payments.application.model.HandoffCreation;
import dev.genericpayments.platform.payments.application.service.PartnerPaymentRules;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.partners.adapter.in.web.AuthenticatedSite;
import dev.genericpayments.platform.partners.domain.PartnerScope;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/partner/payments")
public final class PartnerPaymentController {
    private final CreateDynamicPaymentUseCase create; private final IssueHandoffUseCase handoffs; private final LoadPaymentPort payments;
    private final GetPartnerCustomerUseCase customers;
    public PartnerPaymentController(CreateDynamicPaymentUseCase create,IssueHandoffUseCase handoffs,LoadPaymentPort payments,
                                    GetPartnerCustomerUseCase customers) {
        this.create=create; this.handoffs=handoffs; this.payments=payments; this.customers=customers;
    }
    public record CreateRequest(@NotBlank @Size(max=255) String externalOrderId,
        @tools.jackson.databind.annotation.JsonDeserialize(using=StrictMinorAmountDeserializer.class) @Positive long amountMinor,
        @NotNull @Pattern(regexp="[A-Z]{3}") String currency,
        @NotBlank String checkoutOrigin,String returnUrl,
        @tools.jackson.databind.annotation.JsonDeserialize(contentUsing=StrictMetadataStringDeserializer.class) Map<String,String> metadata,
        UUID customerId) {}
    public record IssueRequest(@NotBlank String checkoutOrigin) {}
    public record DynamicPaymentResponse(UUID paymentId,UUID customerId,String externalOrderId,long amountMinor,String currency,String status,String handoffToken,Instant handoffExpiresAt) {
        @Override public String toString() { return "DynamicPaymentResponse[paymentId="+paymentId+", secret=redacted]"; }
    }
    public record HandoffResponse(UUID paymentId,String externalOrderId,long amountMinor,String currency,String status,String handoffToken,Instant handoffExpiresAt) {
        @Override public String toString() { return "HandoffResponse[paymentId="+paymentId+", secret=redacted]"; }
    }
    public record PaymentResponse(UUID paymentId,UUID customerId,String externalOrderId,long amountMinor,String currency,String status) {}
    @PostMapping @Operation(operationId="createPartnerPayment")
    public ResponseEntity<DynamicPaymentResponse> create(@AuthenticationPrincipal AuthenticatedSite site,@Valid @RequestBody CreateRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        require(site,PartnerScope.PAYMENTS_CREATE); validateKey(request,key);
        UUID customerId=body.customerId();
        if(customerId!=null) customers.getActive(site.siteId(),new CustomerId(customerId));
        var result=create.create(new CreateDynamicPaymentCommand(site.siteId(),customerId,body.externalOrderId(),body.amountMinor(),body.currency(),origin(body.checkoutOrigin()),body.returnUrl(),body.metadata(),key,RequestIdFilter.requestId(request)));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(response(result));
    }
    @GetMapping("/{paymentId}") @Operation(operationId="getPartnerPayment")
    public ResponseEntity<PaymentResponse> read(@AuthenticationPrincipal AuthenticatedSite site,@PathVariable UUID paymentId) {
        require(site,PartnerScope.PAYMENTS_READ);
        var payment=payments.findById(site.siteId(),new PaymentId(paymentId)).orElseThrow(PartnerPaymentRules::notFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PaymentResponse(paymentId,payment.customerId(),payment.externalOrderId(),payment.money().minorUnits(),payment.money().currency(),payment.status().name()));
    }
    @PostMapping("/{paymentId}/handoffs") @Operation(operationId="issuePartnerPaymentHandoff")
    public ResponseEntity<HandoffResponse> issue(@AuthenticationPrincipal AuthenticatedSite site,@PathVariable UUID paymentId,@Valid @RequestBody IssueRequest body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        require(site,PartnerScope.PAYMENTS_CREATE); validateKey(request,key);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(handoffResponse(handoffs.issue(site.siteId(),new PaymentId(paymentId),origin(body.checkoutOrigin()),key,RequestIdFilter.requestId(request))));
    }
    private static DynamicPaymentResponse response(HandoffCreation value) { return new DynamicPaymentResponse(value.paymentId(),value.customerId(),value.externalOrderId(),value.amountMinor(),value.currency(),value.status(),value.handoffToken(),value.handoffExpiresAt()); }
    private static HandoffResponse handoffResponse(HandoffCreation value) { return new HandoffResponse(value.paymentId(),value.externalOrderId(),value.amountMinor(),value.currency(),value.status(),value.handoffToken(),value.handoffExpiresAt()); }
    private static URI origin(String value) { try { return URI.create(value); } catch (IllegalArgumentException invalid) { throw dev.genericpayments.platform.payments.application.service.CheckoutAuthorizationService.originDenied(); } }
    private static void require(AuthenticatedSite site,PartnerScope scope) {
        if (site==null || !site.scopes().contains(scope)) throw new PaymentApplicationException("SITE_API_SCOPE_REQUIRED",403,"Site API scope required");
    }
    private static void validateKey(HttpServletRequest request,String key) {
        if (Collections.list(request.getHeaders("Idempotency-Key")).size()!=1) throw new DomainValidationException("Invalid idempotency key");
        try { dev.genericpayments.platform.payments.application.model.IdempotencyRecord.validateText(key,255); }
        catch (IllegalArgumentException invalid) { throw new DomainValidationException("Invalid idempotency key"); }
    }
}
