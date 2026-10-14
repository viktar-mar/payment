package dev.genericpayments.platform.customers.adapter.in.web;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.application.port.out.PublicCustomerAuthenticationRateLimitPort;
import dev.genericpayments.platform.customers.domain.CustomerAuthenticationMode;
import dev.genericpayments.platform.customers.domain.CustomerSessionScope;
import dev.genericpayments.platform.payments.application.model.CustomerPaymentHistory;
import dev.genericpayments.platform.payments.application.port.in.ListCustomerPaymentsUseCase;
import dev.genericpayments.platform.payments.application.port.in.ListCustomerDonationsUseCase;
import dev.genericpayments.platform.payments.application.model.CustomerDonationHistory;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.RateLimitExceededException;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customer")
public class CustomerController {
    private final GetCurrentCustomerUseCase customers;
    private final LogoutCustomerSessionUseCase sessions;
    private final LinkCustomerIdentityUseCase identities;
    private final PublicCustomerAuthenticationRateLimitPort limits;
    private final ListCustomerPaymentsUseCase payments;
    private final ListCustomerDonationsUseCase donations;
    public CustomerController(GetCurrentCustomerUseCase customers,LogoutCustomerSessionUseCase sessions,
            LinkCustomerIdentityUseCase identities,PublicCustomerAuthenticationRateLimitPort limits,
            ListCustomerPaymentsUseCase payments,ListCustomerDonationsUseCase donations){
        this.customers=customers;this.sessions=sessions;this.identities=identities;this.limits=limits;this.payments=payments;
        this.donations=donations;
    }

    @GetMapping("/me") @Operation(operationId="getCurrentCustomer")
    public MeResponse me(@AuthenticationPrincipal CustomerSessionContext session) {
        session.requireScope(CustomerSessionScope.CUSTOMER_READ);
        var customer=customers.get(session);
        return new MeResponse(customer.id().value(),customer.status().name(),customer.maskedEmail().orElse(null),
            customer.authenticationModes(),customer.sessionExpiresAt());
    }

    @DeleteMapping("/session") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(operationId="logoutCurrentCustomerSession")
    public void logout(@AuthenticationPrincipal CustomerSessionContext session) {
        sessions.logout(session); SecurityContextHolder.clearContext();
    }

    @PostMapping("/identities") @Operation(operationId="linkCurrentCustomerIdentity")
    public ResponseEntity<IdentityLinkResponse> linkIdentity(@AuthenticationPrincipal CustomerSessionContext session,
            @RequestBody IdentityLinkRequest body,HttpServletRequest request) {
        session.requireScope(CustomerSessionScope.IDENTITY_LINK);
        if(body==null||body.proofToken()==null)throw new PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed");
        var decision=limits.acquire(new PublicCustomerAuthenticationRateLimitPort.Key(session.siteId().value(),
            PublicCustomerAuthenticationRateLimitPort.Operation.CUSTOMER_IDENTITY_LINK));
        if(!decision.allowed())throw new RateLimitExceededException(decision.retryAfterSeconds());
        var result=identities.link(new LinkCustomerIdentityUseCase.Command(session,body.proofToken(),
            RequestIdFilter.requestId(request),request.getRemoteAddr()));
        int status=result.outcome()==LinkCustomerIdentityUseCase.Outcome.LINKED?201:200;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(new IdentityLinkResponse(result.identityId().value(),result.outcome().name()));
    }

    @GetMapping("/payments") @Operation(operationId="listCustomerPayments")
    public ResponseEntity<CustomerPaymentHistory> payments(@AuthenticationPrincipal CustomerSessionContext session,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor,HttpServletRequest request) {
        session.requireScope(CustomerSessionScope.CUSTOMER_READ);validateHistoryQuery(request,limit,cursor,false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payments.list(
            new ListCustomerPaymentsUseCase.Query(session.siteId(),session.customerId().value(),limit,cursor)));
    }

    @GetMapping("/donations") @Operation(operationId="listCustomerDonations")
    public ResponseEntity<CustomerDonationHistory> donations(@AuthenticationPrincipal CustomerSessionContext session,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor,HttpServletRequest request) {
        session.requireScope(CustomerSessionScope.CUSTOMER_READ);validateHistoryQuery(request,limit,cursor,true);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(donations.list(new ListCustomerDonationsUseCase.Query(session.siteId(),session.customerId().value(),limit,cursor)));
    }

    private static void validateHistoryQuery(HttpServletRequest request,int limit,String cursor,boolean validateCursor) {
        if(limit<1||limit>100||validateCursor&&cursor!=null&&(cursor.isBlank()||cursor.length()>4096)
                ||request.getParameterMap().keySet().stream().anyMatch(name->!Set.of("limit","cursor").contains(name))
                ||request.getParameterMap().values().stream().anyMatch(values->values.length!=1))
            throw new DomainValidationException("Invalid Customer history query");
    }

    @Schema(name="CustomerIdentityLinkRequest")
    public record IdentityLinkRequest(@Schema(name="proof_token",writeOnly=true) String proofToken) {
        @Override public String toString(){return "IdentityLinkRequest[proofToken=redacted]";}
    }
    public record IdentityLinkResponse(@Schema(name="identity_id") UUID identityId,String outcome) {}
    public record EmptyHistoryResponse(List<Object> items,@Schema(name="next_cursor") String nextCursor) {
        public EmptyHistoryResponse { items=List.copyOf(items); }
    }

    public record MeResponse(UUID id,String status,@Schema(name="masked_email") String maskedEmail,
        @Schema(name="authentication_modes") Set<CustomerAuthenticationMode> authenticationModes,
        @Schema(name="session_expires_at") Instant sessionExpiresAt) {}
}
