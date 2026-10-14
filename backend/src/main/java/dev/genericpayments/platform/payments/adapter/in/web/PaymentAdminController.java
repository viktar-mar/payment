package dev.genericpayments.platform.payments.adapter.in.web;
import dev.genericpayments.platform.payments.application.model.AdminPaymentViews.*;
import dev.genericpayments.platform.payments.application.port.in.*;
import dev.genericpayments.platform.payments.application.service.AdminReconciliationService;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import dev.genericpayments.platform.shared.adapter.in.web.ApiProblem;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/admin/payments")
@ApiResponses({@ApiResponse(responseCode="400",description="Invalid request or field errors",content=@Content(schema=@Schema(oneOf={ApiProblem.class,PaymentAdminController.ApiValidationProblem.class}))),@ApiResponse(responseCode="401",description="Admin session required"),@ApiResponse(responseCode="403",description="Admin role/CSRF required"),@ApiResponse(responseCode="404",description="PAYMENT_NOT_FOUND"),@ApiResponse(responseCode="409",description="PAYMENT_STATE_CONFLICT, REFUND_EXCEEDS_AVAILABLE or IDEMPOTENCY_CONFLICT"),@ApiResponse(responseCode="503",description="PROVIDER_TEMPORARILY_UNAVAILABLE")})
public class PaymentAdminController {
    private final ListPaymentsUseCase list;private final GetPaymentDetailsUseCase details;private final CreateRefundUseCase refunds;private final SimulateFakeEventUseCase fake;private final AdminReconciliationService reconcile;
    public PaymentAdminController(ListPaymentsUseCase list,GetPaymentDetailsUseCase details,CreateRefundUseCase refunds,SimulateFakeEventUseCase fake,AdminReconciliationService reconcile){this.list=list;this.details=details;this.refunds=refunds;this.fake=fake;this.reconcile=reconcile;}
    public record RefundRequest(@NotNull Long amountMinor,@NotBlank @Size(min=3,max=3)String currency,@NotBlank @Schema(allowableValues={"REQUESTED_BY_CUSTOMER","DUPLICATE","FRAUDULENT","OTHER"})String reasonCode,@Size(max=500)String technicalNote){}
    public record RefundResponse(UUID refundId,UUID paymentId,long amountMinor,String currency,RefundStatus status){}
    public enum FakeEventType { PAYMENT_METHOD_REQUIRED,CUSTOMER_ACTION_REQUIRED,PAYMENT_PROCESSING,PAYMENT_SUCCEEDED,PAYMENT_FAILED,PAYMENT_CANCELLED,REFUND_PROCESSING,REFUND_SUCCEEDED,REFUND_FAILED }
    public record FakeRequest(@NotNull SimulateFakeEventUseCase.Target target,@NotNull FakeEventType eventType,@NotNull @Min(1) @Max(2147483647)Long sequence,UUID refundId,Instant occurredAt){}
    public record FieldError(String field,String code){}
    public record ApiValidationProblem(String type,String title,int status,String code,String requestId,List<FieldError> fieldErrors){}
    @GetMapping @Operation(operationId="listAdminPayments",summary="Safe bounded payment projections")
    public ResponseEntity<Page> list(@RequestParam(name="site_id",required=false)UUID site,@RequestParam(required=false)PaymentStatus status,@RequestParam(name="external_order_id",required=false)String order,@RequestParam(name="payment_id",required=false)UUID payment,@RequestParam(required=false)PaymentProviderType provider,@RequestParam(name="created_from",required=false)String from,@RequestParam(name="created_to",required=false)String to,@Parameter(schema=@Schema(minimum="0",maximum="100000",defaultValue="0"))@RequestParam(defaultValue="0")int page,@Parameter(schema=@Schema(minimum="1",maximum="100",defaultValue="20"))@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="CREATED_DESC")Sort sort,@RequestParam(name="customer_id",required=false)UUID customer,@RequestParam(name="campaign_id",required=false)UUID campaign,@RequestParam(name="donation_id",required=false)UUID donation){return ok(list.list(new Query(site,status,order,payment,provider,utc(from),utc(to),page,size,sort,customer,campaign,donation)));}
    @GetMapping("/{paymentId}") @Operation(operationId="getAdminPayment")
    public ResponseEntity<Details> detail(@PathVariable UUID paymentId){return ok(details.get(paymentId));}
    @PostMapping("/{paymentId}/refunds") @Operation(operationId="createAdminRefund",parameters=@Parameter(name="Idempotency-Key",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,required=true,schema=@Schema(maxLength=255))) @ApiResponse(responseCode="202",description="Refund accepted; synchronous acknowledgement is not success")
    public ResponseEntity<RefundResponse> refund(@PathVariable UUID paymentId,@Valid @RequestBody RefundRequest body,@AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request){String key=key(request);var view=details.get(paymentId).payment();Refund r;try{r=refunds.create(new CreateRefundCommand(new SiteId(view.siteId()),new PaymentId(paymentId),actor.id().value(),body.amountMinor(),body.currency(),body.reasonCode(),body.technicalNote(),key,RequestIdFilter.requestId(request)));}catch(IllegalArgumentException|dev.genericpayments.platform.shared.domain.DomainValidationException invalid){throw invalid();}return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(new RefundResponse(r.id().value(),r.paymentId().value(),r.money().minorUnits(),r.money().currency(),r.status()));}
    @PostMapping("/{paymentId}/reconcile") @Operation(operationId="reconcileAdminPayment",parameters=@Parameter(name="Idempotency-Key",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,required=true,schema=@Schema(maxLength=255)))
    public ResponseEntity<Details> reconcile(@PathVariable UUID paymentId,@AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request){reconcile.reconcile(new PaymentId(paymentId),actor.id().value(),key(request),RequestIdFilter.requestId(request));return ok(details.get(paymentId));}
    @PostMapping("/{paymentId}/fake-events") @Operation(operationId="simulateAdminFakeEvent",parameters=@Parameter(name="Idempotency-Key",in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,required=true,schema=@Schema(maxLength=255))) @ApiResponse(responseCode="202",description="Normalized inbox event accepted")
    public ResponseEntity<SimulateFakeEventUseCase.Accepted> fake(@PathVariable UUID paymentId,@Valid @RequestBody FakeRequest body,@AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request){var accepted=fake.simulate(new SimulateFakeEventUseCase.Command(new PaymentId(paymentId),actor.id().value(),body.target(),PaymentEventType.valueOf(body.eventType().name()),body.sequence(),body.refundId()==null?null:new RefundId(body.refundId()),body.occurredAt(),key(request),RequestIdFilter.requestId(request)));return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(accepted);}
    private static String key(HttpServletRequest request){var values=Collections.list(request.getHeaders("Idempotency-Key"));if(values.size()!=1)throw invalid();try{dev.genericpayments.platform.payments.application.model.SafeFactJson.text(values.getFirst(),255);}catch(IllegalArgumentException bad){throw invalid();}return values.getFirst();}
    private static Instant utc(String value){if(value==null)return null;try{if(!value.endsWith("Z"))throw invalid();return Instant.parse(value);}catch(java.time.format.DateTimeParseException bad){throw invalid();}}
    private static PaymentApplicationException invalid(){return new PaymentApplicationException("PAYMENT_REQUEST_INVALID",400,"Invalid payment request");}
    private static <T>ResponseEntity<T> ok(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
