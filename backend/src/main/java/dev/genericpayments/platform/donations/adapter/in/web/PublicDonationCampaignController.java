package dev.genericpayments.platform.donations.adapter.in.web;

import dev.genericpayments.platform.donations.application.port.in.GetPublicDonationCampaignUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
public class PublicDonationCampaignController {
    private final GetPublicDonationCampaignUseCase campaigns;
    public PublicDonationCampaignController(GetPublicDonationCampaignUseCase campaigns) { this.campaigns=campaigns; }
    @GetMapping("/api/v1/public/sites/{siteKey}/donation-campaigns/{campaignKey}") @Operation(operationId="getPublicDonationCampaign")
    public PublicCampaignResponse get(@PathVariable String siteKey,@PathVariable String campaignKey,HttpServletRequest request) {
        var c=campaigns.getPublic(siteKey,campaignKey,DonationCampaignPublicRequestFilter.origin(request));
        return new PublicCampaignResponse(c.key(),c.displayName(),c.purposeDescription(),c.currency(),c.suggestedAmounts(),
            c.minimumAmount(),c.maximumAmount(),c.customAmountAllowed(),c.anonymousAllowed());
    }
    public record PublicCampaignResponse(String key,@Schema(name="display_name") String displayName,
        @Schema(name="purpose_description",nullable=true) String purposeDescription,String currency,@Schema(name="suggested_amounts") List<Long> suggestedAmounts,
        @Schema(name="minimum_amount") long minimumAmount,@Schema(name="maximum_amount") long maximumAmount,
        @Schema(name="custom_amount_allowed") boolean customAmountAllowed,@Schema(name="anonymous_allowed") boolean anonymousAllowed) {}
}
