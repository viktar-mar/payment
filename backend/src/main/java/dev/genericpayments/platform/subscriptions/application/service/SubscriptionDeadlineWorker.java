package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Clock;
import java.util.UUID;
import java.util.Objects;
import org.springframework.stereotype.Component;
@Component
public class SubscriptionDeadlineWorker {
    private final SubscriptionSweepPort sweep;private final SubscriptionStore subscriptions;private final BillingRecordStore bills;
    private final SubscriptionTransactionPort tx;private final SubscriptionEventStore events;private final SubscriptionCallbackPort callbacks;
    private final SubscriptionWorkerConfigurationPort properties;private final SubscriptionTelemetryPort telemetry;private final Clock clock;
    private final SubscriptionBillingTimePort billingTime;
    public SubscriptionDeadlineWorker(SubscriptionSweepPort sweep,SubscriptionStore subscriptions,BillingRecordStore bills,SubscriptionTransactionPort tx,
        SubscriptionEventStore events,SubscriptionCallbackPort callbacks,SubscriptionWorkerConfigurationPort properties,SubscriptionTelemetryPort telemetry,Clock clock){
        this(sweep,subscriptions,bills,tx,events,callbacks,properties,telemetry,clock,SubscriptionBillingTimePort.ordinary());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public SubscriptionDeadlineWorker(SubscriptionSweepPort sweep,SubscriptionStore subscriptions,BillingRecordStore bills,SubscriptionTransactionPort tx,
        SubscriptionEventStore events,SubscriptionCallbackPort callbacks,SubscriptionWorkerConfigurationPort properties,SubscriptionTelemetryPort telemetry,Clock clock,SubscriptionBillingTimePort billingTime){
        this.billingTime=Objects.requireNonNull(billingTime);
        this.sweep=sweep;this.subscriptions=subscriptions;this.bills=bills;this.tx=tx;this.events=events;this.callbacks=callbacks;this.properties=properties;this.telemetry=telemetry;this.clock=clock;}
    public void runOnce(){billingTime.requireUnscopedAllowed(null,null);for(var claim:sweep.expiredGrace(properties.batchSize(),clock.instant()))expire(claim);}
    public void expire(SubscriptionSweepPort.Claim claim){tx.required(()->{
        var s=subscriptions.lock(claim.siteId(),claim.subscriptionId());var debt=s.debt();var now=clock.instant();
        var sample=billingTime.sample(s.paymentAccountId(),s.siteId(),s.customerId(),now);var billingAt=sample.billingAt();
        if(sample.isolated()&&(s.trial()!=null||s.cancellation()!=null||s.pendingChange()!=null))throw new IllegalStateException("ISOLATED_BILLING_UNSUPPORTED");
        if(s.revision()!=claim.revision()||s.billingStatus()!=Subscription.Status.PAST_DUE||debt==null||debt.graceDeadline()==null||billingAt.isBefore(debt.graceDeadline()))return null;
        var bill=bills.lock(s.siteId(),debt.billingRecordId());
        if(bill.settlement()!=BillingRecord.Settlement.OPEN||new SubscriptionAccessPolicy().evaluate(s,billingAt).allowed())return null;
        var e=new SubscriptionEvent(UUID.randomUUID(),s.siteId(),s.id(),s.paymentAccountId(),0,s.revision(),SubscriptionEvent.Type.ACCESS_SUSPENDED,SubscriptionEvent.Source.DEADLINE,billingAt,now,null,bill.id(),null,null,"grace-expired:"+bill.id(),"subscription-deadline");
        if(events.append(e)){callbacks.snapshot(s,e,new SubscriptionAccessPolicy().evaluate(s,billingAt),now);telemetry.record(SubscriptionTelemetryPort.Operation.DEADLINE,SubscriptionTelemetryPort.Outcome.SUCCESS);}return null;
    });}
}
