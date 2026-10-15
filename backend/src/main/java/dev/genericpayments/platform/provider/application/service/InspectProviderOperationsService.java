package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.payments.application.service.PartnerPaymentRules;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.in.InspectProviderOperationsUseCase;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationReadPort;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class InspectProviderOperationsService implements InspectProviderOperationsUseCase {
    private final ProviderOperationReadPort reads;
    public InspectProviderOperationsService(ProviderOperationReadPort reads) { this.reads=reads; }
    public List<ProviderOperationView> inspect(PaymentId paymentId) {
        if(!reads.paymentExists(paymentId)) throw PartnerPaymentRules.notFound();
        return reads.forPayment(paymentId);
    }
    public List<ProviderOperationView> unresolved(UUID after,int limit) { bounded(limit); return reads.unresolved(after,limit); }
    public List<DeadProviderReferenceView> deadReferences(UUID after,int limit) { bounded(limit); return reads.deadReferences(after,limit); }
    private static void bounded(int limit) {
        if(limit<1 || limit>100) throw new PaymentApplicationException("PAYMENT_QUERY_INVALID",400,"Invalid operation query");
    }
}
