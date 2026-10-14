package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.port.in.QueryDonationPaymentsUseCase;
import dev.genericpayments.platform.payments.application.port.out.DonationPaymentReadPort;
import dev.genericpayments.platform.payments.application.model.DonationPaymentViews.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @Transactional(readOnly=true)
public class DonationPaymentQueryService implements QueryDonationPaymentsUseCase {
    private final DonationPaymentReadPort reads;
    public DonationPaymentQueryService(DonationPaymentReadPort reads){this.reads=reads;}
    public Page list(Query query){return reads.list(query);}
    public Optional<Item> find(UUID siteId,UUID donationId){Objects.requireNonNull(donationId);return reads.find(siteId,donationId);}
}
