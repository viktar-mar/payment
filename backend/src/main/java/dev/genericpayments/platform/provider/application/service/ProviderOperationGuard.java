package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountStatus;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.Status;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.Duration;
import java.time.Instant;

public final class ProviderOperationGuard {
    public String requireDispatch(ProviderOperation operation, PaymentAccount account, String fingerprint, Instant now) {
        if (operation == null || account == null || fingerprint == null || now == null
            || !operation.accountId().equals(account.id())
            || !operation.externalAccountId().equals(account.externalAccountId())
            || !operation.credentialsRef().equals(account.credentialsRef())
            || !operation.fingerprint().equals(fingerprint)
            || account.status() != PaymentAccountStatus.ACTIVE
            || operation.status() == Status.ACKNOWLEDGED || operation.status() == Status.FAILED) {
            throw new PaymentApplicationException("PROVIDER_OPERATION_CONFLICT", 409, "Provider operation conflicts with the request");
        }
        if (operation.status() != Status.DISPATCHING || operation.leaseOwner() == null
            || operation.leaseOwner().isBlank() || operation.leaseExpiresAt() == null
            || !now.isBefore(operation.leaseExpiresAt()) || operation.firstDispatchedAt() == null
            || operation.retryBefore() == null || !now.isBefore(operation.retryBefore())
            || !withinWriteWindow(operation.firstDispatchedAt(), now)) {
            throw new PaymentApplicationException("PROVIDER_RESULT_UNKNOWN", 409, "Provider result requires recovery");
        }
        return operation.operationKey();
    }

    public static boolean withinWriteWindow(Instant firstDispatchedAt, Instant now) {
        return !now.isBefore(firstDispatchedAt)
            && now.isBefore(firstDispatchedAt.plus(Duration.ofHours(23)));
    }
}
