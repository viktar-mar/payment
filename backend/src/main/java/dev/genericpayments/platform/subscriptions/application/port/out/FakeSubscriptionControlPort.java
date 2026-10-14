package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import java.time.Instant;
import java.util.UUID;
public interface FakeSubscriptionControlPort {
    enum Scenario { SUCCESS, PROCESSING, REQUIRES_ACTION, DECLINE, UNKNOWN, ZERO_PRORATION, UNSUPPORTED_SETTLEMENT }
    void advance(PaymentAccountId account,Instant until);
    void scenario(PaymentAccountId account,UUID subscriptionId,Scenario scenario);
    void completeAction(PaymentAccountId account,UUID subscriptionId);
}
