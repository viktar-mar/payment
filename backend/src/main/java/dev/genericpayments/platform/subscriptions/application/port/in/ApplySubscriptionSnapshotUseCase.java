package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionEvent;
import java.util.UUID;
public interface ApplySubscriptionSnapshotUseCase {
    record Fence(long revision,UUID operationId,Long operationVersion,dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionSweepPort.Claim lease,dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext authority,Long observationSequence) {
        public Fence(long revision,UUID operationId,Long operationVersion,dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionSweepPort.Claim lease,dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext authority){this(revision,operationId,operationVersion,lease,authority,null);}
    }
    record Command(PaymentAccount account,UUID subscriptionId,SubscriptionProviderModels.Snapshot snapshot,SubscriptionEvent.Source source,String factKey,String requestId,Fence fence) {
        public Command(PaymentAccount account,UUID id,SubscriptionProviderModels.Snapshot snapshot,SubscriptionEvent.Source source,String key,String request){this(account,id,snapshot,source,key,request,null);}
    }
    void apply(Command command);
}
