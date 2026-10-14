package dev.genericpayments.platform.payments.adapter.out.integration;
import dev.genericpayments.platform.payments.application.port.out.DonationMessageStatePort;
import dev.genericpayments.platform.donations.application.port.in.QueryDonationStatesUseCase;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class DonationMessageStateAdapter implements DonationMessageStatePort {
    private final QueryDonationStatesUseCase queries;
    public DonationMessageStateAdapter(QueryDonationStatesUseCase queries){this.queries=queries;}
    public Map<UUID,String> states(SiteId site,Set<UUID> ids){return queries.states(site,ids).entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,e->e.getValue().name()));}
}
