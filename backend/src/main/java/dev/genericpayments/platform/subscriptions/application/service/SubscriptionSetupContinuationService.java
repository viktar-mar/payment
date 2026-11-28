package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.webhooks.application.port.out.SubscriptionInboxPort;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SubscriptionSetupContinuationService implements ContinueOriginalSubscriptionSetupUseCase,SubscriptionSetupContinuationGuard {
    private final SubscriptionTransactionPort tx;private final SubscriptionOperationStore operations;private final SubscriptionStore subscriptions;
    private final SubscriptionReadPreparation reads;private final SubscriptionBillingProofStore proofs;private final SubscriptionProviderRegistry providers;
    private final SubscriptionCommandTransaction commands;private final SubscriptionInboxPort inbox;private final SubscriptionSweepPort sweep;
    private final SubscriptionAuthorityPort authority;private final SubscriptionPolicyPort policies;private final LockSitePort sites;
    private final CustomerStore customers;private final LockPaymentAccountPort accounts;private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired private TrialEligibilityStore trials;
    public SubscriptionSetupContinuationService(SubscriptionTransactionPort tx,SubscriptionOperationStore operations,SubscriptionStore subscriptions,SubscriptionReadPreparation reads,
        SubscriptionBillingProofStore proofs,SubscriptionProviderRegistry providers,SubscriptionCommandTransaction commands,SubscriptionInboxPort inbox,SubscriptionSweepPort sweep,
        SubscriptionAuthorityPort authority,SubscriptionPolicyPort policies,LockSitePort sites,CustomerStore customers,LockPaymentAccountPort accounts,Clock clock){
        this.tx=tx;this.operations=operations;this.subscriptions=subscriptions;this.reads=reads;this.proofs=proofs;this.providers=providers;this.commands=commands;this.inbox=inbox;this.sweep=sweep;this.authority=authority;this.policies=policies;this.sites=sites;this.customers=customers;this.accounts=accounts;this.clock=clock;
    }
    public void requireCurrent(Command c,SetupContinuationFence fence){
        tx.requiresNew(()->{
            var o=operations.lock(c.operation().id());
            if(!o.equals(c.operation())||!c.recurringConsent()||!java.util.Set.of(SubscriptionOperation.Kind.CREATE,SubscriptionOperation.Kind.SETUP).contains(o.kind())||o.status()==SubscriptionOperation.Status.SUCCEEDED||o.status()==SubscriptionOperation.Status.FAILED)throw SubscriptionRuleErrors.conflict();
            var site=sites.findByIdForUpdate(o.siteId()).orElseThrow(SubscriptionRuleErrors::conflict);
            var customer=customers.findByIdForUpdate(o.siteId(),o.customerId()).orElseThrow(SubscriptionRuleErrors::conflict);
            var a=accounts.findByIdForUpdate(c.account().id()).orElseThrow(SubscriptionRuleErrors::conflict);
            var s=subscriptions.lock(o.siteId(),o.subscriptionId());var policy=policies.load(o.siteId());
            if(site.status()!=dev.genericpayments.platform.sites.domain.SiteStatus.ACTIVE||customer.status()!=dev.genericpayments.platform.customers.domain.CustomerStatus.ACTIVE
                ||!a.equals(c.account())||a.status()!=PaymentAccountStatus.ACTIVE||a.provider()!=PaymentProviderType.STRIPE||a.mode()!=PaymentAccountMode.SANDBOX||!s.paymentAccountId().equals(a.id())||!s.customerId().equals(o.customerId())||!s.nonTerminal()||!s.billingPrice().equals(c.current())||!java.util.Objects.equals(s.providerReference(),c.providerSubscriptionReference())||!policy.enabled()||policy.version()!=o.policyVersion())throw SubscriptionRuleErrors.conflict();
            var setup=proofs.setup(a.id(),o.id()).orElseThrow(SubscriptionRuleErrors::conflict);
            if(!setup.subscription().equals(s.id())||!setup.account().equals(a.id())||!setup.operation().equals(o.id()))throw SubscriptionRuleErrors.conflict();
            if(o.kind()==SubscriptionOperation.Kind.CREATE&&(!c.startTrial()||!CreateSubscriptionFingerprint.startsTrial(o.fingerprint())||s.billingPrice().trialPolicy()!=dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL
                ||trials==null||!trials.find(s.siteId(),s.customerId(),s.productId()).filter(e->e.consumedAt()==null&&o.id().equals(e.reservedByOperation())).isPresent()))throw SubscriptionRuleErrors.conflict();
            switch(fence){
                case InboxSetupFence f -> {var e=inbox.lock(f.claim(),clock.instant());if(e.provider()!=PaymentProviderType.STRIPE||!e.accountId().equals(a.id())||!e.eventType().equals("SUBSCRIPTION_SETUP_CHANGED")||!e.objectReference().equals(setup.reference()))throw SubscriptionRuleErrors.conflict();}
                case SweepSetupFence f -> {if(!f.claim().siteId().equals(s.siteId())||!f.claim().subscriptionId().equals(s.id())||f.claim().revision()!=s.revision())throw SubscriptionRuleErrors.conflict();sweep.requireCurrent(f.claim(),clock.instant());}
                case ApiSetupFence f -> {if(f.context()==null||!f.context().siteId().equals(s.siteId())||!f.context().customerId().equals(s.customerId()))throw SubscriptionRuleErrors.conflict();authority.revalidate(f.context(),true);}
                case null -> throw SubscriptionRuleErrors.conflict();
            }
            return null;
        });
    }
    private Command pending(PaymentAccount a,UUID id){
        if(tx.active())throw new IllegalStateException("Setup continuation requires no ambient transaction");
        var s=subscriptions.findOwned(a.id(),id).orElseThrow(SubscriptionRuleErrors::conflict);var o=reads.financial(s);
        if(o==null||!java.util.Set.of(SubscriptionOperation.Kind.CREATE,SubscriptionOperation.Kind.SETUP).contains(o.kind()))return null;
        if(o.kind()==SubscriptionOperation.Kind.CREATE&&(s.providerReference()!=null||!CreateSubscriptionFingerprint.startsTrial(o.fingerprint())
            ||s.billingPrice().trialPolicy()!=dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL))return null;
        return new Command(a,o,s.billingPrice(),null,s.providerReference(),null,null,true,o.kind()==SubscriptionOperation.Kind.CREATE&&CreateSubscriptionFingerprint.startsTrial(o.fingerprint()));
    }
    private SubscriptionOperationResult record(Command c,SetupContinuationResult result){return c.operation().kind()==SubscriptionOperation.Kind.CREATE?commands.recordReply(c.operation().id(),result.reply()):commands.recordSetupUpdateReply(c.operation().id(),result.reply());}
    public boolean inbox(PaymentAccount a,UUID s,SubscriptionInboxPort.Claim claim){
        var c=pending(a,s);if(c==null)return subscriptions.findOwned(a.id(),s).map(v->v.providerReference()!=null).orElse(false);
        var result=providers.forAccount(a).continueOriginalSetup(c,new InboxSetupFence(claim));if(!result.progressed())return false;
        record(c,result);return result.reply().outcome()!=Outcome.UNKNOWN;
    }
    public SubscriptionSweepPort.Claim sweep(PaymentAccount a,UUID s,SubscriptionSweepPort.Claim claim){
        var c=pending(a,s);if(c==null)return claim;
        var result=providers.forAccount(a).continueOriginalSetup(c,new SweepSetupFence(claim));if(!result.progressed())return claim;
        record(c,result);var after=subscriptions.findOwned(a.id(),s).orElseThrow(SubscriptionRuleErrors::conflict);
        return tx.required(()->sweep.advanceRevision(claim,after.revision(),clock.instant()));
    }
    public SubscriptionOperationResult api(SubscriptionCommandContext context,UUID s,UUID operation){
        var state=tx.required(()->{authority.revalidate(context,true);var sub=subscriptions.find(context.siteId(),s).orElseThrow(SubscriptionRuleErrors::conflict);if(!sub.customerId().equals(context.customerId()))throw SubscriptionRuleErrors.conflict();return sub;});
        var a=tx.required(()->accounts.findByIdForUpdate(state.paymentAccountId()).orElseThrow(SubscriptionRuleErrors::conflict));
        var c=pending(a,s);if(c==null||!c.operation().id().equals(operation))return commands.readResult(operation);
        var result=providers.forAccount(a).continueOriginalSetup(c,new ApiSetupFence(context));return result.progressed()?record(c,result):commands.readResult(operation);
    }
}
