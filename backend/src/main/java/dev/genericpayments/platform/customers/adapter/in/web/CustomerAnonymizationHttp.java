package dev.genericpayments.platform.customers.adapter.in.web;

import dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase;
import dev.genericpayments.platform.customers.application.service.CustomerAnonymizationService;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import java.util.*;

final class CustomerAnonymizationHttp {
    private CustomerAnonymizationHttp() {}
    @Schema(name="CustomerAnonymizationRequest")
    record Body(@NotNull UUID confirmation) {}
    @Schema(name="CustomerAnonymizationResponse")
    record Response(@Schema(name="customer_id") UUID customerId,String status) {
        static Response from(AnonymizeCustomerUseCase.Result result){return new Response(result.customerId().value(),result.status().name());}
    }
    static String key(HttpServletRequest request) {
        var headers=Collections.list(request.getHeaders("Idempotency-Key"));
        if(headers.size()!=1)throw new DomainValidationException("Invalid idempotency key");
        String key=headers.getFirst();CustomerAnonymizationService.validateKey(key);return key;
    }
}
