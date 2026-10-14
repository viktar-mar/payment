package dev.genericpayments.platform.customers.adapter.in.web;

import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter;
import dev.genericpayments.platform.sites.domain.SiteId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/sites/{siteId}/customers")
public final class CustomerAdminController {
    private final QueryAdminCustomersUseCase queries;
    private final AnonymizeCustomerUseCase anonymization;
    public CustomerAdminController(QueryAdminCustomersUseCase queries,AnonymizeCustomerUseCase anonymization) {
        this.queries=queries;this.anonymization=anonymization;
    }
    @GetMapping @Operation(operationId="listAdminCustomers")
    public ResponseEntity<CustomerQueryHttp.Page> list(@PathVariable UUID siteId,
            @RequestParam(required=false) CustomerStatus status,@RequestParam(name="customer_id",required=false) UUID customerId,
            @RequestParam(name="masked_email",required=false) String maskedEmail,@RequestParam(defaultValue="20") int limit,
            @RequestParam(defaultValue="0") int offset) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CustomerQueryHttp.Page.from(queries.list(new QueryAdminCustomersUseCase.Query(
            new SiteId(siteId),status,customerId==null?null:new CustomerId(customerId),maskedEmail,limit,offset))));
    }
    @GetMapping("/{customerId}") @Operation(operationId="getAdminCustomer")
    public ResponseEntity<CustomerQueryHttp.Response> get(@PathVariable UUID siteId,@PathVariable UUID customerId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CustomerQueryHttp.Response.from(queries.get(new SiteId(siteId),new CustomerId(customerId))));
    }
    @PostMapping("/{customerId}/anonymization")
    @Operation(operationId="anonymizeAdminCustomer",parameters=@Parameter(name="Idempotency-Key",in=ParameterIn.HEADER,required=true,schema=@Schema(maxLength=255)))
    public ResponseEntity<CustomerAnonymizationHttp.Response> anonymize(@PathVariable UUID siteId,@PathVariable UUID customerId,
            @AuthenticationPrincipal AuthenticatedAdmin actor,@Valid @RequestBody CustomerAnonymizationHttp.Body body,HttpServletRequest request) {
        var result=anonymization.anonymize(new AnonymizeCustomerUseCase.Command(new SiteId(siteId),new CustomerId(customerId),body.confirmation(),
            CustomerAnonymizationHttp.key(request),"ADMIN",actor.id().value().toString(),RequestIdFilter.requestId(request),request.getRemoteAddr()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CustomerAnonymizationHttp.Response.from(result));
    }
}
