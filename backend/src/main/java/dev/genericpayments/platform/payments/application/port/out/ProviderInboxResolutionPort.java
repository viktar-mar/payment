package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.payments.application.model.*;

public interface ProviderInboxResolutionPort {
    ResolvedProviderReceipt resolve(ProviderReferenceReceipt receipt);
}
