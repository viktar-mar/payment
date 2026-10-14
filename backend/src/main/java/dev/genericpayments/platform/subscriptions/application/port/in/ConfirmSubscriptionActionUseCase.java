package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.subscriptions.application.model.*;
import java.util.UUID;
public interface ConfirmSubscriptionActionUseCase {
    record Command(SubscriptionCommandContext context,UUID subscriptionId,UUID operationId,String actionToken) {
        @Override public String toString(){return "ConfirmSubscriptionAction[subscriptionId="+subscriptionId+", operationId="+operationId+", actionToken=[REDACTED]]";}
    }
    SubscriptionOperationResult confirm(Command command);
}
