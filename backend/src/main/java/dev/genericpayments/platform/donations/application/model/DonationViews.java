package dev.genericpayments.platform.donations.application.model;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import dev.genericpayments.platform.donations.domain.DonorMessageState;
import dev.genericpayments.platform.payments.application.model.DonationPaymentViews;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import java.time.Instant;
import java.util.*;
public final class DonationViews {
    private DonationViews() {}
    public record Partner(@JsonProperty("donation_id") UUID donationId,@JsonProperty("payment_id") UUID paymentId,
            @JsonProperty("campaign_id") UUID campaignId,@JsonProperty("campaign_key") String campaignKey,@JsonProperty("campaign_version") long campaignVersion,
            @JsonProperty("customer_id") UUID customerId,@JsonProperty("amount_minor") long amountMinor,String currency,PaymentStatus status,
            @JsonProperty("created_at") Instant createdAt,@JsonProperty("updated_at") Instant updatedAt) {
        public static Partner from(DonationPaymentViews.Item p){return new Partner(p.donationId(),p.paymentId(),p.campaignId(),p.campaignKey(),p.campaignVersion(),p.customerId(),p.amountMinor(),p.currency(),p.status(),p.createdAt(),p.updatedAt());}
    }
    public record Summary(@JsonProperty("donation_id") UUID donationId,@JsonProperty("payment_id") UUID paymentId,@JsonProperty("site_id") UUID siteId,
            @JsonProperty("campaign_id") UUID campaignId,@JsonProperty("campaign_key") String campaignKey,@JsonProperty("campaign_version") long campaignVersion,
            @JsonProperty("customer_id") UUID customerId,@JsonProperty("amount_minor") long amountMinor,String currency,PaymentStatus status,
            @JsonProperty("message_state") DonorMessageState messageState,@JsonProperty("has_message") boolean hasMessage,
            @JsonProperty("created_at") Instant createdAt,@JsonProperty("updated_at") Instant updatedAt) {
        public static Summary from(DonationPaymentViews.Item p,DonorMessageState state){return new Summary(p.donationId(),p.paymentId(),p.siteId(),p.campaignId(),p.campaignKey(),p.campaignVersion(),p.customerId(),p.amountMinor(),p.currency(),p.status(),state,state==DonorMessageState.PRESENT,p.createdAt(),p.updatedAt());}
    }
    @Schema(name="DonationAdminPage")
    public record Page(List<Summary> items,long total,int page,int size){public Page{items=List.copyOf(items);}}
    /** The sole plaintext response projection. Never use its message in diagnostics, audit, or caching. */
    public record Detail(Summary donation,String message) {
        @Override public String toString(){return "DonationDetail[donation="+donation+", message=REDACTED]";}
    }
    public record Redaction(@JsonProperty("donation_id") UUID donationId,@JsonProperty("message_state") DonorMessageState messageState,boolean changed,boolean replayed) {}
}
