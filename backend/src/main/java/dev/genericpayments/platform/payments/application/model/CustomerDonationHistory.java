package dev.genericpayments.platform.payments.application.model;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import java.time.Instant;
import java.util.*;
public record CustomerDonationHistory(List<Item> items,@JsonProperty("next_cursor") String nextCursor) {
    public CustomerDonationHistory {items=List.copyOf(items);}
    @Schema(name="CustomerDonationHistoryItem")
    public record Item(@JsonProperty("donation_id") UUID donationId,@JsonProperty("payment_id") UUID paymentId,
            @JsonProperty("campaign_id") UUID campaignId,@JsonProperty("campaign_key") String campaignKey,@JsonProperty("campaign_version") long campaignVersion,
            @JsonProperty("amount_minor") long amountMinor,String currency,PaymentStatus status,@JsonProperty("message_state") String messageState,
            @JsonProperty("created_at") Instant createdAt,@JsonProperty("updated_at") Instant updatedAt) {}
}
