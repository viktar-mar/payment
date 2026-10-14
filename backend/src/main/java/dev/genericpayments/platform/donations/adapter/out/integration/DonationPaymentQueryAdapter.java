package dev.genericpayments.platform.donations.adapter.out.integration;
import dev.genericpayments.platform.donations.application.port.out.DonationPaymentQueryPort;
import dev.genericpayments.platform.payments.application.port.in.QueryDonationPaymentsUseCase;
import dev.genericpayments.platform.payments.application.model.DonationPaymentViews.*;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class DonationPaymentQueryAdapter implements DonationPaymentQueryPort {
    private final QueryDonationPaymentsUseCase queries;
    public DonationPaymentQueryAdapter(QueryDonationPaymentsUseCase queries){this.queries=queries;}
    public Page list(Query query){return queries.list(query);}
    public Optional<Item> find(UUID siteId,UUID donationId){return queries.find(siteId,donationId);}
}
