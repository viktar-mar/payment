package dev.genericpayments.platform.donations.application.port.in;
import dev.genericpayments.platform.donations.domain.DonorMessageState;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.*;
public interface QueryDonationStatesUseCase {
    Map<UUID,DonorMessageState> states(SiteId site,Set<UUID> donationIds);
}
