package dev.genericpayments.platform.donations.application.port.in;
import dev.genericpayments.platform.donations.application.model.DonationViews.Partner;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.UUID;
public interface GetPartnerDonationUseCase { Partner get(SiteId site,UUID donationId); }
