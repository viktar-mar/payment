package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
@Component
public class SubscriptionDeadlineWorker {
    private final SubscriptionSweepPort sweep;private final SubscriptionStore subscriptions;private final BillingRecordStore bills;
    private final SubscriptionTransactionPort tx;private final SubscriptionEventStore events;private final SubscriptionCallbackPort callbacks;
    private final SubscriptionWorkerConfigurationPort properties;private final SubscriptionTelemetryPort telemetry;private final Clock clock;
    public SubscriptionDeadlineWorker(SubscriptionSweepPort sweep,SubscriptionStore subscriptions,BillingRecordStore bills,SubscriptionTransactionPort tx,
        SubscriptionEventStore events,SubscriptionCallbackPort callbacks,SubscriptionWorkerConfigurationPort properties,SubscriptionTelemetryPort telemetry,Clock clock){this.sweep=sweep;this.subscriptions=subscriptions;this.bills=bills;this.tx=tx;this.events=events;this.callbacks=callbacks;this.properties=properties;this.telemetry=telemetry;this.clock=clock;}
    public void runOnce(){for(var claim:sweep.expiredGrace(properties.batchSize(),clock.instant()))expire(claim);}
    public void expire(SubscriptionSweepPort.Claim claim){tx.required(()->{
        var s=subscriptions.lock(claim.siteId(),claim.subscriptionId());var debt=s.debt();var now=clock.instant();
        if(s.revision()!=claim.revision()||s.billingStatus()!=Subscription.Status.PAST_DUE||debt==null||debt.graceDeadline()==null||now.isBefore(debt.graceDeadline()))return null;
        var bill=bills.lock(s.siteId(),debt.billingRecordId());
        if(bill.settlement()!=BillingRecord.Settlement.OPEN||new SubscriptionAccessPolicy().evaluate(s,now).allowed())return null;
        var e=new SubscriptionEvent(UUID.randomUUID(),s.siteId(),s.id(),s.paymentAccountId(),0,s.revision(),SubscriptionEvent.Type.ACCESS_SUSPENDED,SubscriptionEvent.Source.DEADLINE,now,now,null,bill.id(),null,null,"grace-expired:"+bill.id(),"subscription-deadline");
        if(events.append(e)){callbacks.snapshot(s,e,new SubscriptionAccessPolicy().evaluate(s,now),now);telemetry.record(SubscriptionTelemetryPort.Operation.DEADLINE,SubscriptionTelemetryPort.Outcome.SUCCESS);}return null;
    });}
}
