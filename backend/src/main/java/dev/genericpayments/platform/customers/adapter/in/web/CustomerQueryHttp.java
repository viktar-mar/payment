package dev.genericpayments.platform.customers.adapter.in.web;

import dev.genericpayments.platform.customers.application.model.SafeCustomerView;
import dev.genericpayments.platform.customers.application.port.in.QueryAdminCustomersUseCase;
import dev.genericpayments.platform.customers.domain.CustomerAuthenticationMode;
import dev.genericpayments.platform.customers.domain.CustomerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class CustomerQueryHttp {
    private CustomerQueryHttp() {}

    @Schema(name="CustomerAdminPage")
    record Page(List<Response> items) {
        Page { items=List.copyOf(items); }
        static Page from(QueryAdminCustomersUseCase.Page page) {
            return new Page(page.items().stream().map(Response::from).toList());
        }
    }

    @Schema(name="CustomerResponse")
    record Response(@Schema(name="customer_id") UUID customerId,@Schema(name="site_id") UUID siteId,
            CustomerStatus status,@Schema(name="masked_email",nullable=true) String maskedEmail,
            @Schema(name="created_at") Instant createdAt,@Schema(name="updated_at") Instant updatedAt,
            @Schema(name="anonymized_at",nullable=true) Instant anonymizedAt,List<Identity> identities) {
        Response { identities=List.copyOf(identities); }
        static Response from(SafeCustomerView view) {
            return new Response(view.customerId(),view.siteId(),view.status(),view.maskedEmail(),view.createdAt(),
                view.updatedAt(),view.anonymizedAt(),view.identities().stream().map(Identity::from).toList());
        }
    }

    @Schema(name="CustomerIdentityResponse")
    record Identity(CustomerAuthenticationMode mode,@Schema(name="issuer_key") String issuerKey,
            @Schema(name="created_at") Instant createdAt,@Schema(name="verified_at") Instant verifiedAt,
            @Schema(name="last_used_at") Instant lastUsedAt) {
        static Identity from(SafeCustomerView.Identity view) {
            return new Identity(view.mode(),view.issuerKey(),view.createdAt(),view.verifiedAt(),view.lastUsedAt());
        }
    }
}
