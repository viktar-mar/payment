package dev.genericpayments.platform.paymentaccounts.adapter.in.web;

import dev.genericpayments.platform.paymentaccounts.domain.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record PaymentAccountDto(UUID id, String key, PaymentProviderType provider, PaymentAccountMode mode,
                                PaymentAccountStatus status, @JsonProperty("external_account_id") String externalAccountId) {
    static PaymentAccountDto from(PaymentAccount account) {
        return new PaymentAccountDto(account.id().value(), account.key(), account.provider(), account.mode(),
            account.status(), account.externalAccountId());
    }
    public record CreateRequest(@NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9-]{2,62}") String key,
                                @NotNull PaymentProviderType provider, @NotNull PaymentAccountMode mode,
                                @NotBlank @Size(max = 255) @JsonProperty("external_account_id") String externalAccountId,
                                @NotBlank @Size(max = 255) @JsonProperty("credentials_ref") String credentialsRef) {
        @Override public String toString() { return "CreateRequest[credentials=REDACTED]"; }
    }
}
