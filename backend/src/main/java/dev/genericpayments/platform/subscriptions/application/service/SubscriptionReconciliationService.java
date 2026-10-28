package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Lookup;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
@Service
public class SubscriptionReconciliationService implements ReconcileSubscriptionUseCase {
    private final SubscriptionStore subscriptions;private final SubscriptionOperationStore operations;
    private final SubscriptionTransactionPort tx;private final SubscriptionAuthorityPort authority;
    private final LoadPaymentAccountPort accounts;private final SubscriptionProviderRegistry providers;
    private final ApplySubscriptionSnapshotUseCase facts;private final SubscriptionReadPreparation reads;
    private final SubscriptionSweepPort sweep;private final SubscriptionTelemetryPort telemetry;private final Clock clock;
    private final SubscriptionWorkerConfigurationPort properties;private final SubscriptionObservationPort observations;
    public SubscriptionReconciliationService(SubscriptionStore subscriptions,SubscriptionOperationStore operations,SubscriptionTransactionPort tx,
        SubscriptionAuthorityPort authority,LoadPaymentAccountPort accounts,SubscriptionProviderRegistry providers,ApplySubscriptionSnapshotUseCase facts,
        SubscriptionReadPreparation reads,SubscriptionSweepPort sweep,SubscriptionTelemetryPort telemetry,Clock clock,
        SubscriptionWorkerConfigurationPort properties,SubscriptionObservationPort observations){
        this.subscriptions=subscriptions;this.operations=operations;this.tx=tx;this.authority=authority;this.accounts=accounts;this.providers=providers;this.facts=facts;this.reads=reads;this.sweep=sweep;this.telemetry=telemetry;this.clock=clock;this.properties=properties;this.observations=observations;
    }
    public SubscriptionOperationResult reconcile(Command c){
        outside();var context=Objects.requireNonNull(c.context());
        if(context.idempotencyKey()==null||context.idempotencyKey().isBlank()||context.idempotencyKey().length()>255||context.idempotencyKey().codePoints().anyMatch(Character::isISOControl))throw SubscriptionRuleErrors.conflict();
        var s=tx.required(()->{authority.revalidate(context,true);return owned(context,c.subscriptionId());});
        String principal=switch(context.actor()){case SubscriptionActor.Customer a->"customer:"+a.session().customerId().value();case SubscriptionActor.Partner a->"partner:"+a.site().keyId().value();case SubscriptionActor.Admin a->"admin:"+a.admin().id().value();};
        var id=UUID.randomUUID();var now=clock.instant();
        String fingerprint=CreateSubscriptionFingerprint.digest("RECONCILE:"+s.id()+":"+s.paymentAccountId().value()+":"+context.customerId().value()+":"+context.origin());
        var proposed=new SubscriptionOperation(id,s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.RECONCILE,SubscriptionOperation.Status.RESERVED,principal,CreateSubscriptionFingerprint.digest(context.idempotencyKey()),fingerprint,"reconcile:"+id,s.revision(),0,null,null,now,now,0);
        var reserved=tx.requiresNew(()->{var claim=operations.claim(proposed);authority.revalidate(context,true);var o=claim.operation();if(!o.fingerprint().equals(fingerprint))throw SubscriptionRuleErrors.conflict();return o;});
        if(reserved.status()==SubscriptionOperation.Status.SUCCEEDED||reserved.status()==SubscriptionOperation.Status.FAILED)return result(reserved);
        try {observe(owned(context,s.id()),null,context);return tx.required(()->result(complete(reserved.id(),SubscriptionOperation.Status.SUCCEEDED)));}
        catch(RuntimeException e){tx.required(()->complete(reserved.id(),SubscriptionOperation.Status.UNKNOWN));telemetry.record(SubscriptionTelemetryPort.Operation.RECONCILE,SubscriptionTelemetryPort.Outcome.UNKNOWN);throw e;}
    }
    public void reconcileClaim(SubscriptionSweepPort.Claim claim){
        outside();
        try {var s=subscriptions.find(claim.siteId(),claim.subscriptionId()).orElseThrow();if(s.revision()!=claim.revision())throw new IllegalStateException("OBSERVATION_STALE");observe(s,claim,null);}
        catch(RuntimeException failure){sweep.finish(claim,clock.instant().plus(properties.backoff(sweep.attempts(claim))),false);telemetry.record(SubscriptionTelemetryPort.Operation.RECONCILE,SubscriptionTelemetryPort.Outcome.UNKNOWN);}
    }
    private void observe(Subscription s,SubscriptionSweepPort.Claim claim,SubscriptionCommandContext context){
        var original=reads.financial(s);
        if(original!=null&&original.status()==SubscriptionOperation.Status.UNKNOWN)telemetry.observeUnknownAge(Duration.between(original.createdAt(),clock.instant()));
        var account=accounts.findById(s.paymentAccountId()).orElseThrow();
        Long sequence=account.provider()==dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType.STRIPE?observations.captureFence(s):null;
        var snapshot=providers.forAccount(account).retrieve(new Lookup(account,s.id(),s.providerReference(),original==null?null:original.providerOperationKey()));
        tx.required(()->{
            facts.apply(new ApplySubscriptionSnapshotUseCase.Command(account,s.id(),snapshot,SubscriptionEvent.Source.RECONCILIATION,"reconcile:"+UUID.randomUUID(),"subscription-reconcile",
                new ApplySubscriptionSnapshotUseCase.Fence(s.revision(),original==null?null:original.id(),original==null?null:original.version(),claim,context,sequence)));
            var after=subscriptions.find(s.siteId(),s.id()).orElseThrow();
            boolean unresolved=operations.hasUnresolved(s.siteId(),s.id());
            if(claim!=null){var advanced=sweep.advanceRevision(claim,after.revision(),clock.instant());sweep.finish(advanced,clock.instant().plus(unresolved?properties.backoff(sweep.attempts(claim)):properties.delay()),!unresolved);}
            telemetry.record(SubscriptionTelemetryPort.Operation.RECONCILE,unresolved?SubscriptionTelemetryPort.Outcome.UNKNOWN:after.revision()==s.revision()?SubscriptionTelemetryPort.Outcome.NO_CHANGE:SubscriptionTelemetryPort.Outcome.SUCCESS);
            return null;
        });
    }
    private Subscription owned(SubscriptionCommandContext c,UUID id){var s=subscriptions.find(c.siteId(),id).orElseThrow(SubscriptionRuleErrors::conflict);if(!s.customerId().equals(c.customerId()))throw SubscriptionRuleErrors.conflict();return s;}
    private SubscriptionOperation complete(UUID id,SubscriptionOperation.Status status){var o=operations.lock(id);if(o.status()==SubscriptionOperation.Status.SUCCEEDED)return o;return operations.save(new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),status,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),null,o.createdAt(),clock.instant(),o.version()+1),o.version());}
    private SubscriptionOperationResult result(SubscriptionOperation o){return new SubscriptionOperationResult(o.id(),o.subscriptionId(),o.status(),null);}
    private void outside(){if(tx.active())throw new IllegalStateException("Provider retrieval requires no transaction");}
}
