package dev.genericpayments.platform.donations.application.service;
import dev.genericpayments.platform.donations.application.port.in.QueryDonationStatesUseCase;
import dev.genericpayments.platform.donations.application.port.out.DonationStateReadPort;
import dev.genericpayments.platform.donations.domain.DonorMessageState;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @Transactional(readOnly=true)
public class DonationStateQueryService implements QueryDonationStatesUseCase {
    private final DonationStateReadPort states;
    public DonationStateQueryService(DonationStateReadPort states){this.states=states;}
    public Map<UUID,DonorMessageState> states(SiteId site,Set<UUID> ids){
        Objects.requireNonNull(site);if(ids.size()>100)throw new IllegalArgumentException("Too many Donation states");
        return ids.isEmpty()?Map.of():states.states(site,Set.copyOf(ids));
    }
}
