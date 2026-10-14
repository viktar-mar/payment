package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.model.AdminPaymentViews.*;
import dev.genericpayments.platform.payments.application.port.in.*;
import dev.genericpayments.platform.payments.application.port.out.AdminPaymentReadPort;
import java.util.UUID;
public final class AdminPaymentQueryService implements ListPaymentsUseCase,GetPaymentDetailsUseCase {
    private final AdminPaymentReadPort reads;
    public AdminPaymentQueryService(AdminPaymentReadPort reads){this.reads=reads;}
    public Page list(Query query){return reads.list(query);}
    public Details get(UUID paymentId){return reads.details(paymentId).orElseThrow(PartnerPaymentRules::notFound);}
}
