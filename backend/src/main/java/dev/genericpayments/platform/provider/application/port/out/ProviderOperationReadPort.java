package dev.genericpayments.platform.provider.application.port.out;

import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.provider.application.model.*;
import java.util.List;
import java.util.UUID;

public interface ProviderOperationReadPort {
    boolean paymentExists(PaymentId paymentId);
    List<ProviderOperationView> forPayment(PaymentId paymentId);
    List<ProviderOperationView> unresolved(UUID after,int limit);
    List<DeadProviderReferenceView> deadReferences(UUID after,int limit);
}
