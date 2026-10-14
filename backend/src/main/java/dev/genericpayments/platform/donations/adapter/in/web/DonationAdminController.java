package dev.genericpayments.platform.donations.adapter.in.web;
import dev.genericpayments.platform.donations.application.port.in.*;
import dev.genericpayments.platform.donations.application.model.DonationViews.*;
import dev.genericpayments.platform.payments.application.model.DonationPaymentViews.Query;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.admin.domain.AuditAction;
import dev.genericpayments.platform.shared.adapter.in.web.AdminAuditFacade;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/admin/donations")
public class DonationAdminController {
    private final QueryAdminDonationsUseCase queries;private final RedactDonationMessageUseCase redaction;private final AdminAuditFacade audit;
    public DonationAdminController(QueryAdminDonationsUseCase queries,RedactDonationMessageUseCase redaction,AdminAuditFacade audit){this.queries=queries;this.redaction=redaction;this.audit=audit;}
    @GetMapping @Operation(operationId="listAdminDonations")
    public ResponseEntity<Page> list(@RequestParam(name="site_id",required=false) UUID site,@RequestParam(name="campaign_id",required=false) UUID campaign,
        @RequestParam(name="customer_id",required=false) UUID customer,@RequestParam(name="donation_id",required=false) UUID donation,
        @RequestParam(name="payment_id",required=false) UUID payment,@RequestParam(required=false) PaymentStatus status,
        @RequestParam(name="created_from",required=false) String from,@RequestParam(name="created_to",required=false) String to,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        return ok(queries.list(new Query(site,campaign,customer,donation,payment,status,utc(from),utc(to),page,size)));
    }
    @GetMapping("/{donationId}") @Operation(operationId="getAdminDonation")
    public ResponseEntity<Detail> detail(@PathVariable UUID donationId){return ok(queries.detail(donationId));}
    @PostMapping("/{donationId}/message-redactions") @Transactional @Operation(operationId="redactDonationMessage",
        parameters=@io.swagger.v3.oas.annotations.Parameter(name="Idempotency-Key",required=true,in=io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,schema=@io.swagger.v3.oas.annotations.media.Schema(type="string",maxLength=255)))
    public ResponseEntity<Redaction> redact(@PathVariable UUID donationId,@RequestBody Confirmation body,
            @AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request){
        var keys=Collections.list(request.getHeaders("Idempotency-Key"));if(keys.size()!=1)throw new DomainValidationException("Invalid idempotency key");
        var result=redaction.redact(donationId,body.confirmation(),actor.id().value(),keys.getFirst());
        if(result.changed())audit.record(AuditAction.DONATION_MESSAGE_REDACTED,"DONATION",donationId,actor,request,Map.of("outcome",result.messageState().name()));
        return ok(result);
    }
    public record Confirmation(UUID confirmation) {}
    private static Instant utc(String text){if(text==null)return null;try{if(!text.endsWith("Z"))throw new IllegalArgumentException();return Instant.parse(text);}catch(RuntimeException invalid){throw new DomainValidationException("Invalid Donation query");}}
    private static <T> ResponseEntity<T> ok(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
