package dev.genericpayments.platform.sites.adapter.in.web;

import dev.genericpayments.platform.sites.domain.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.Set;
import java.util.UUID;

public record CreateSiteRequest(@NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9-]{2,62}") String key,
                                @NotBlank @Size(max = 120) @JsonProperty("display_name") String displayName,
                                @NotNull @JsonProperty("payment_account_id") UUID paymentAccountId,
                                @NotEmpty @Size(max = 100) @JsonProperty("allowed_origins") Set<@NotNull URI> allowedOrigins,
                                @NotEmpty @JsonProperty("authentication_modes") Set<@NotNull AuthenticationMode> authenticationModes,
                                @NotNull @Valid @JsonProperty("payment_method_policy") Policy paymentMethodPolicy) {
    @AssertTrue(message = "allowed origin must be at most 255 characters")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isOriginLengthValid() {
        return allowedOrigins == null || allowedOrigins.stream()
            .allMatch(origin -> origin == null || origin.toString().length() <= 255);
    }
    public record Policy(@NotNull PaymentMethodPolicyMode mode, @NotNull Set<@NotNull PaymentMethod> methods) {
        PaymentMethodPolicy toDomain() { return new PaymentMethodPolicy(mode, methods); }
    }
}
