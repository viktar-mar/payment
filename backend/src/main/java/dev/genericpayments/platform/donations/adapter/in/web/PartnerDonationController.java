package dev.genericpayments.platform.donations.adapter.in.web;
import dev.genericpayments.platform.donations.application.port.in.GetPartnerDonationUseCase;
import dev.genericpayments.platform.donations.application.model.DonationViews.Partner;
import dev.genericpayments.platform.partners.adapter.in.web.AuthenticatedSite;
import dev.genericpayments.platform.partners.domain.PartnerScope;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/partner/donations")
public class PartnerDonationController {
    private final GetPartnerDonationUseCase queries;
    public PartnerDonationController(GetPartnerDonationUseCase queries){this.queries=queries;}
    @GetMapping("/{donationId}") @Operation(operationId="getPartnerDonation")
    public ResponseEntity<Partner> get(@AuthenticationPrincipal AuthenticatedSite principal,@PathVariable UUID donationId){
        if(principal==null||!principal.scopes().contains(PartnerScope.DONATIONS_READ))throw new PaymentApplicationException("SITE_API_SCOPE_REQUIRED",403,"Site API scope required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.get(principal.siteId(),donationId));
    }
}
