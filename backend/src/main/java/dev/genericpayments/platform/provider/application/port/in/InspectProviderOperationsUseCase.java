package dev.genericpayments.platform.provider.application.port.in;

import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.provider.application.model.*;
import java.util.List;
import java.util.UUID;

public interface InspectProviderOperationsUseCase {
    List<ProviderOperationView> inspect(PaymentId paymentId);
    List<ProviderOperationView> unresolved(UUID after,int limit);
    List<DeadProviderReferenceView> deadReferences(UUID after,int limit);
}
