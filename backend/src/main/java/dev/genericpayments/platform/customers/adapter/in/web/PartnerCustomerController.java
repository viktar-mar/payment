package dev.genericpayments.platform.customers.adapter.in.web;

import dev.genericpayments.platform.customers.application.port.in.LinkPartnerCustomerIdentityUseCase;
import dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase;
import dev.genericpayments.platform.customers.application.port.in.GetPartnerCustomerUseCase;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.partners.adapter.in.web.AuthenticatedSite;
import dev.genericpayments.platform.partners.domain.PartnerScope;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/partner/customers")
public final class PartnerCustomerController {
    private final LinkPartnerCustomerIdentityUseCase identities;
    private final GetPartnerCustomerUseCase queries;
    private final AnonymizeCustomerUseCase anonymization;
    public PartnerCustomerController(LinkPartnerCustomerIdentityUseCase identities,GetPartnerCustomerUseCase queries,AnonymizeCustomerUseCase anonymization){
        this.identities=identities;this.queries=queries;this.anonymization=anonymization;
    }

    @GetMapping("/{customerId}") @Operation(operationId="getPartnerCustomer")
    public ResponseEntity<CustomerQueryHttp.Response> get(@AuthenticationPrincipal AuthenticatedSite site,@PathVariable UUID customerId) {
        require(site,PartnerScope.CUSTOMERS_READ);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CustomerQueryHttp.Response.from(queries.getActive(site.siteId(),new CustomerId(customerId))));
    }
    @PostMapping("/{customerId}/anonymization")
    @Operation(operationId="anonymizePartnerCustomer",parameters=@Parameter(name="Idempotency-Key",in=ParameterIn.HEADER,required=true,schema=@Schema(maxLength=255)))
    public ResponseEntity<CustomerAnonymizationHttp.Response> anonymize(@AuthenticationPrincipal AuthenticatedSite site,@PathVariable UUID customerId,
            @Valid @RequestBody CustomerAnonymizationHttp.Body body,HttpServletRequest request) {
        require(site,PartnerScope.CUSTOMERS_MANAGE);
        var result=anonymization.anonymize(new AnonymizeCustomerUseCase.Command(site.siteId(),new CustomerId(customerId),body.confirmation(),
            CustomerAnonymizationHttp.key(request),"SITE_API_KEY",site.keyId().value().toString(),RequestIdFilter.requestId(request),request.getRemoteAddr()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CustomerAnonymizationHttp.Response.from(result));
    }

    @PostMapping("/{customerId}/identities")
    @Operation(operationId="linkPartnerCustomerIdentity",parameters=@Parameter(name="Idempotency-Key",in=ParameterIn.HEADER,
        required=true,schema=@Schema(maxLength=255)))
    public ResponseEntity<IdentityLinkResponse> linkIdentity(@AuthenticationPrincipal AuthenticatedSite site,
            @PathVariable UUID customerId,@Valid @RequestBody PartnerIdentityLinkRequest body,
            @RequestHeader("Idempotency-Key") String ignoredKey,HttpServletRequest request) {
        require(site,PartnerScope.CUSTOMERS_MANAGE);
        String key=idempotencyKey(request);
        var result=identities.link(new LinkPartnerCustomerIdentityUseCase.Command(site.siteId(),site.keyId(),new CustomerId(customerId),
            body.mode(),body.issuerKey(),body.subject(),key,RequestIdFilter.requestId(request),request.getRemoteAddr()));
        return ResponseEntity.status(result.responseStatus()).cacheControl(CacheControl.noStore())
            .body(new IdentityLinkResponse(result.identityId().value(),result.outcome().name()));
    }

    @Schema(name="PartnerCustomerIdentityLinkRequest")
    public record PartnerIdentityLinkRequest(@NotNull CustomerAuthenticationMode mode,
        @NotBlank @Size(max=63) @Schema(name="issuer_key") String issuerKey,
        @NotBlank @Size(max=2048) @Schema(writeOnly=true) String subject) {
        @Override public String toString(){return "PartnerIdentityLinkRequest[mode="+mode+", issuerKey="+issuerKey+", subject=redacted]";}
    }
    public record IdentityLinkResponse(@Schema(name="identity_id") UUID identityId,String outcome) {}

    private static void require(AuthenticatedSite site,PartnerScope scope) {
        if(site==null||!site.scopes().contains(scope))throw new PaymentApplicationException("SITE_API_SCOPE_REQUIRED",403,"Site API scope required");
    }
    private static String idempotencyKey(HttpServletRequest request) {
        var values=Collections.list(request.getHeaders("Idempotency-Key"));
        if(values.size()!=1)throw invalid();
        String value=values.getFirst();
        if(value==null||value.isBlank()||value.length()>255||value.chars().anyMatch(Character::isISOControl)
            ||value.codePoints().anyMatch(code -> code>=Character.MIN_SURROGATE&&code<=Character.MAX_SURROGATE))throw invalid();
        return value;
    }
    private static DomainValidationException invalid(){return new DomainValidationException("Invalid idempotency key");}
}
