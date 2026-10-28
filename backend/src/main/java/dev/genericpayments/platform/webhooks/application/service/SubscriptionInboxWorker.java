package dev.genericpayments.platform.webhooks.application.service;
import dev.genericpayments.platform.webhooks.application.port.out.SubscriptionInboxPort;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Lookup;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionEvent;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
@Service
public class SubscriptionInboxWorker {
    private final SubscriptionInboxPort inbox;private final SubscriptionTransactionPort tx;private final SubscriptionObjectPort objects;
    private final SubscriptionStore subscriptions;private final SubscriptionOperationStore operations;private final LoadPaymentAccountPort accounts;
    private final SubscriptionProviderRegistry providers;private final ApplySubscriptionSnapshotUseCase facts;private final Clock clock;
    private final dev.genericpayments.platform.subscriptions.application.port.in.PrepareSubscriptionReadUseCase reads;
    private final SubscriptionTelemetryPort telemetry;private final SubscriptionObservationPort observations;
    private final String owner="subscription-inbox-"+UUID.randomUUID();private final int batch;private final Duration lease;
    public SubscriptionInboxWorker(SubscriptionInboxPort inbox,SubscriptionTransactionPort tx,SubscriptionObjectPort objects,SubscriptionStore subscriptions,
        SubscriptionOperationStore operations,LoadPaymentAccountPort accounts,SubscriptionProviderRegistry providers,ApplySubscriptionSnapshotUseCase facts,
        Clock clock,@Value("${payment.inbox.batch-size:20}")int batch,@Value("${payment.inbox.lease-seconds:30}")long seconds,dev.genericpayments.platform.subscriptions.application.port.in.PrepareSubscriptionReadUseCase reads,SubscriptionTelemetryPort telemetry,SubscriptionObservationPort observations){
        if(batch<1||batch>100||seconds<1||seconds>600)throw new IllegalArgumentException("Invalid inbox configuration");
        this.inbox=inbox;this.tx=tx;this.objects=objects;this.subscriptions=subscriptions;this.operations=operations;this.accounts=accounts;this.providers=providers;this.facts=facts;this.clock=clock;this.batch=batch;this.lease=Duration.ofSeconds(seconds);
        this.reads=reads;this.telemetry=telemetry;this.observations=observations;
    }
    public void runOnce(){
        if(tx.active())throw new IllegalStateException("Provider retrieval requires no transaction");
        for(var claim:inbox.claim(owner,batch,clock.instant(),lease))try{
            var envelope=tx.required(()->inbox.lock(claim,clock.instant()));
            var kind=switch(envelope.eventType()){case "SUBSCRIPTION_CHANGED"->SubscriptionObjectPort.Kind.SUBSCRIPTION;case "SUBSCRIPTION_INVOICE_CHANGED"->SubscriptionObjectPort.Kind.INVOICE;case "SUBSCRIPTION_SETUP_CHANGED"->SubscriptionObjectPort.Kind.SETUP;default->throw new IllegalArgumentException("Invalid event type");};
            var identity=objects.resolve(envelope.provider(),envelope.accountId(),kind,envelope.objectReference());
            if(identity.isEmpty()){inbox.fail(claim,"UNRESOLVED_OBJECT",clock.instant());continue;}
            var subscription=subscriptions.findOwned(envelope.accountId(),identity.get()).orElseThrow();
            var account=accounts.findById(envelope.accountId()).orElseThrow();
            if(account.provider()!=envelope.provider())throw new IllegalArgumentException("Provider mismatch");
            var original=reads.financial(subscription);
            String key=original==null?null:original.providerOperationKey();
            Long sequence=account.provider()==dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.STRIPE?observations.captureFence(subscription):null;
            var snapshot=providers.forAccount(account).retrieve(new Lookup(account,subscription.id(),subscription.providerReference(),key));
            tx.required(()->{inbox.lock(claim,clock.instant());facts.apply(new ApplySubscriptionSnapshotUseCase.Command(account,subscription.id(),snapshot,SubscriptionEvent.Source.PROVIDER_WEBHOOK,envelope.eventId(),"subscription-inbox",new ApplySubscriptionSnapshotUseCase.Fence(subscription.revision(),original==null?null:original.id(),original==null?null:original.version(),null,null,sequence)));inbox.complete(claim);return null;});
            telemetry.observeInboxLag(Duration.between(envelope.receivedAt(),clock.instant()));
        }catch(RuntimeException failure){inbox.fail(claim,"PROCESSING_FAILED",clock.instant());}
    }
}
