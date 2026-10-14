package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.payments.application.port.in.RecordSubscriptionCashUseCase;

/** Requires the lifecycle projection transaction; performs no provider calls. */
public interface SubscriptionCashPort {
    RecordSubscriptionCashUseCase.Result record(RecordSubscriptionCashUseCase.Command command);
}
