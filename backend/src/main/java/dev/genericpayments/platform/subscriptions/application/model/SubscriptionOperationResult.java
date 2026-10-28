package dev.genericpayments.platform.subscriptions.application.model;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.util.UUID;
public record SubscriptionOperationResult(UUID operationId,UUID subscriptionId,SubscriptionOperation.Status status,SubscriptionProviderModels.NextAction nextAction,boolean browserConfirmAllowed) {
    public SubscriptionOperationResult(UUID operationId,UUID subscriptionId,SubscriptionOperation.Status status,SubscriptionProviderModels.NextAction nextAction){this(operationId,subscriptionId,status,nextAction,false);}
    @Override public String toString(){return "SubscriptionOperationResult[operationId="+operationId+", subscriptionId="+subscriptionId+", status="+status+", nextAction=[REDACTED]]";}
}
