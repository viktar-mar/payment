package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionSweepPort;
import dev.genericpayments.platform.webhooks.application.port.out.SubscriptionInboxPort;
import java.util.UUID;
/** Explicit server command for an admitted original setup; never called by GET/retrieve. */
public interface ContinueOriginalSubscriptionSetupUseCase {
    boolean inbox(PaymentAccount account,UUID subscription,SubscriptionInboxPort.Claim claim);
    SubscriptionSweepPort.Claim sweep(PaymentAccount account,UUID subscription,SubscriptionSweepPort.Claim claim);
    SubscriptionOperationResult api(SubscriptionCommandContext context,UUID subscription,UUID operation);
}
