package dev.genericpayments.platform.donations.adapter.in.web;

import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.admin.domain.AuditAction;
import dev.genericpayments.platform.donations.application.port.in.ManageDonationCampaignUseCase;
import dev.genericpayments.platform.donations.application.port.in.ManageDonationCampaignUseCase.*;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.shared.adapter.in.web.AdminAuditFacade;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin/sites/{siteId}/donation-campaigns")
public class DonationCampaignAdminController {
    private final ManageDonationCampaignUseCase campaigns;
    private final AdminAuditFacade audit;
    public DonationCampaignAdminController(ManageDonationCampaignUseCase campaigns,AdminAuditFacade audit) { this.campaigns=campaigns; this.audit=audit; }
    @GetMapping @Transactional(readOnly=true) @Operation(operationId="listDonationCampaigns")
    public CampaignPage list(@PathVariable UUID siteId,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="20") int limit) {
        return new CampaignPage(campaigns.list(new SiteId(siteId),offset,limit).stream().map(CampaignResponse::from).toList(),offset,limit);
    }
    @GetMapping("/{campaignId}") @Transactional(readOnly=true) @Operation(operationId="getDonationCampaign")
    public CampaignResponse get(@PathVariable UUID siteId,@PathVariable UUID campaignId) {
        return CampaignResponse.from(campaigns.get(new SiteId(siteId),new DonationCampaignId(campaignId)));
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional @Operation(operationId="createDonationCampaign")
    public CampaignResponse create(@PathVariable UUID siteId,@RequestHeader("Idempotency-Key") String key,
            @RequestBody CampaignConfigurationRequest body,@AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request) {
        if(body.expectedVersion()!=null) throw new DomainValidationException("expected version is only accepted on update");
        return result(campaigns.create(new SiteId(siteId),actor.id().value(),key,body.configuration()),AuditAction.DONATION_CAMPAIGN_CREATED,actor,request);
    }
    @PutMapping("/{campaignId}") @Transactional @Operation(operationId="updateDonationCampaign")
    public CampaignResponse update(@PathVariable UUID siteId,@PathVariable UUID campaignId,@RequestHeader("Idempotency-Key") String key,
            @RequestBody CampaignConfigurationRequest body,@AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request) {
        if(body.expectedVersion()==null) throw new DomainValidationException("expected version is required");
        return result(campaigns.update(new SiteId(siteId),new DonationCampaignId(campaignId),actor.id().value(),key,body.expectedVersion(),body.configuration()),
            AuditAction.DONATION_CAMPAIGN_UPDATED,actor,request);
    }
    @PostMapping("/{campaignId}/status") @Transactional @Operation(operationId="changeDonationCampaignStatus")
    public CampaignResponse status(@PathVariable UUID siteId,@PathVariable UUID campaignId,@RequestHeader("Idempotency-Key") String key,
            @RequestBody CampaignStatusRequest body,@AuthenticationPrincipal AuthenticatedAdmin actor,HttpServletRequest request) {
        if(body.expectedVersion()==null) throw new DomainValidationException("expected version is required");
        return result(campaigns.changeStatus(new SiteId(siteId),new DonationCampaignId(campaignId),actor.id().value(),key,body.expectedVersion(),body.status()),
            AuditAction.DONATION_CAMPAIGN_STATUS_CHANGED,actor,request);
    }
    private CampaignResponse result(Mutation result,AuditAction action,AuthenticatedAdmin actor,HttpServletRequest request) {
        var c=result.campaign();
        if(!result.replayed()) audit.record(action,"DONATION_CAMPAIGN",c.id().value(),actor,request,
            Map.of("site_id",c.siteId().value().toString(),"campaign_key",c.campaignKey().value(),"status",c.status().name(),"version",Long.toString(c.version())));
        return CampaignResponse.from(c);
    }
    public record CampaignPage(List<CampaignResponse> items,int offset,int limit) {}
    public record CampaignConfigurationRequest(String key,@Schema(name="display_name") String displayName,
            @Schema(name="purpose_description",nullable=true) String purposeDescription,String currency,
            @Schema(name="suggested_amounts") List<Long> suggestedAmounts,@Schema(name="minimum_amount") Long minimumAmount,
            @Schema(name="maximum_amount") Long maximumAmount,@Schema(name="custom_amount_allowed") Boolean customAmountAllowed,
            @Schema(name="anonymous_allowed") Boolean anonymousAllowed,@Schema(name="expected_version") Long expectedVersion) {
        Configuration configuration() {
            if(minimumAmount==null || maximumAmount==null || customAmountAllowed==null || anonymousAllowed==null)
                throw new DomainValidationException("campaign policy fields are required");
            return new Configuration(key,displayName,purposeDescription,currency,suggestedAmounts,minimumAmount,maximumAmount,customAmountAllowed,anonymousAllowed);
        }
    }
    public record CampaignStatusRequest(CampaignStatus status,@Schema(name="expected_version") Long expectedVersion) {}
    public record CampaignResponse(UUID id,@Schema(name="site_id") UUID siteId,String key,@Schema(name="display_name") String displayName,
            @Schema(name="purpose_description",nullable=true) String purposeDescription,String currency,@Schema(name="suggested_amounts") List<Long> suggestedAmounts,
            @Schema(name="minimum_amount") long minimumAmount,@Schema(name="maximum_amount") long maximumAmount,
            @Schema(name="custom_amount_allowed") boolean customAmountAllowed,@Schema(name="anonymous_allowed") boolean anonymousAllowed,
            CampaignStatus status,@Schema(name="activated_once") boolean activatedOnce,long version,
            @Schema(name="created_at") Instant createdAt,@Schema(name="updated_at") Instant updatedAt) {
        static CampaignResponse from(DonationCampaign c) {
            return new CampaignResponse(c.id().value(),c.siteId().value(),c.campaignKey().value(),c.displayName(),c.purposeDescription(),c.currency(),
                c.suggestedAmounts(),c.minimumAmount(),c.maximumAmount(),c.customAmountAllowed(),c.anonymousAllowed(),c.status(),c.activatedOnce(),c.version(),c.createdAt(),c.updatedAt());
        }
    }
}
