package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
@Component
public class SubscriptionSweepWorker {
    private final SubscriptionSweepPort sweep;private final SubscriptionReconciliationService reconcile;private final SubscriptionWorkerConfigurationPort properties;private final Clock clock;
    private final String owner="subscription-sweep-"+UUID.randomUUID();
    public SubscriptionSweepWorker(SubscriptionSweepPort sweep,SubscriptionReconciliationService reconcile,SubscriptionWorkerConfigurationPort properties,Clock clock){this.sweep=sweep;this.reconcile=reconcile;this.properties=properties;this.clock=clock;}
    public void runOnce(){for(var claim:sweep.claim(owner,properties.batchSize(),clock.instant(),properties.lease()))reconcile.reconcileClaim(claim);}
}
