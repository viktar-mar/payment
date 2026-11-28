package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.subscriptions.application.model.*;
import java.util.UUID;
public interface CreateSubscriptionClientSessionUseCase {
 SubscriptionClientSession issue(Command command);
 record Command(SubscriptionCommandContext context,UUID subscriptionId,UUID operationId,String actionToken){@Override public String toString(){return "CreateSubscriptionClientSession.Command[redacted]";}}
}
