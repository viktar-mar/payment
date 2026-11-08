package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.RecoveryPreparationContext;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.*;
import dev.genericpayments.platform.provider.application.model.ProviderRequestFingerprint;
import dev.genericpayments.platform.provider.application.port.out.OwnedRecoveryConfirmStore;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Explicit one-use original-intent financial admission. Provider I/O is always outside these transactions. */
public final class OwnedRecoveryConfirmService {
    private final OwnedFailedRenewalMethodService methods;private final OwnedRecoveryConfirmStore store;
    private final SubscriptionTransactionPort transactions;private final Clock clock;
    public OwnedRecoveryConfirmService(OwnedFailedRenewalMethodService methods,OwnedRecoveryConfirmStore store,SubscriptionTransactionPort transactions,Clock clock){
        this.methods=Objects.requireNonNull(methods);this.store=Objects.requireNonNull(store);this.transactions=Objects.requireNonNull(transactions);this.clock=Objects.requireNonNull(clock);
    }
    public ProviderOperation claim(RecoveryPreparationContext expected,SubscriptionCommandContext originalContext,String claimant,InvocationBudget budget){
        if(claimant==null||claimant.isBlank()||claimant.length()>128||claimant.codePoints().anyMatch(Character::isISOControl))throw denied();
        return transition(expected,originalContext,budget,null,c->{
            if(store.lockInTransaction(c).isPresent())throw denied();
        },c->{var claim=store.insertAndClaimInTransaction(c,claimant,clock.instant(),budget);requireExactClaim(c,claim);
            if(!claimant.equals(claim.leaseOwner()))throw denied();return claim;});
    }
    public ProviderOperation inspectClaim(RecoveryPreparationContext expected,ProviderOperation claim,SubscriptionCommandContext originalContext,InvocationBudget budget){
        requireExactClaim(expected,claim);
        return transition(expected,originalContext,budget,claim,c->{if(!store.lockInTransaction(c).filter(claim::equals).isPresent())throw denied();},
            c->{var current=store.requireClaimInTransaction(c,claim,budget,clock.instant());if(!claim.equals(current))throw denied();return current;});
    }
    public ConfirmationAck acknowledge(RecoveryPreparationContext expected,ProviderOperation claim,ConfirmReply reply,SubscriptionCommandContext originalContext,InvocationBudget budget){
        requireExactClaim(expected,claim);requireReply(expected,claim,reply);
        return transition(expected,originalContext,budget,claim,c->{if(!store.lockInTransaction(c).filter(claim::equals).isPresent())throw denied();},c->{
            if(!claim.equals(store.requireClaimInTransaction(c,claim,budget,clock.instant())))throw denied();requireReply(c,claim,reply);
            var ack=store.acknowledgeInTransaction(c,claim,reply,clock.instant(),budget);var op=ack.operation();
            if(!op.id().equals(claim.id())||!op.accountId().equals(claim.accountId())||!op.resourceId().equals(claim.resourceId())
                    ||!op.operationKey().equals(claim.operationKey())||!op.fingerprint().equals(claim.fingerprint())
                    ||!op.externalAccountId().equals(claim.externalAccountId())||!op.credentialsRef().equals(claim.credentialsRef())
                    ||!Objects.equals(op.firstDispatchedAt(),claim.firstDispatchedAt())||!Objects.equals(op.retryBefore(),claim.retryBefore())
                    ||!ack.reply().equals(reply)||ack.acknowledgedAt().isAfter(clock.instant()))throw denied();return ack;
        });
    }
    public void uncertain(RecoveryPreparationContext expected,ProviderOperation claim){
        fresh();try{
            requireExactClaim(expected,claim);
            transactions.requiresNew(()->{methods.consumeRecoveryConfirmationInTransaction(expected,()->{
                if(!store.lockInTransaction(expected).filter(claim::equals).isPresent())return false;return store.unknownInTransaction(expected,claim);
            });return null;});
        }catch(RuntimeException ignored){/* Preserve consumed evidence. Cleanup never reacquires authority. */}
    }
    private <T>T transition(RecoveryPreparationContext expected,SubscriptionCommandContext context,InvocationBudget budget,ProviderOperation claim,
            java.util.function.Consumer<RecoveryPreparationContext> lock,Function<RecoveryPreparationContext,T> effect){
        fresh();checkpoint(budget,claim);Objects.requireNonNull(expected);
        return transactions.requiresNew(()->{
            var result=methods.withRecoveryConfirmationInTransaction(expected,context,lock,c->{checkpoint(budget,claim);return effect.apply(c);});
            // This is the final precommit checkpoint. A committed ACK is never rejected on delayed return.
            checkpoint(budget,claim);return result;
        });
    }
    private void checkpoint(InvocationBudget budget,ProviderOperation claim){
        if(budget==null)throw denied();budget.remainingNanos(claim);var now=clock.instant();
        if(now.isBefore(budget.enteredAt())||!now.isBefore(budget.deadlineAt())||claim!=null&&(now.isBefore(claim.firstDispatchedAt())||!now.isBefore(claim.leaseExpiresAt())||!now.isBefore(claim.retryBefore())))throw denied();
    }
    private void requireExactClaim(RecoveryPreparationContext c,ProviderOperation o){
        if(c==null||o==null||o.kind()!=Kind.OWNED_RECOVERY_INTENT_CONFIRM||o.status()!=Status.DISPATCHING||o.version()!=1
                ||!o.accountId().equals(c.account().id())||!o.externalAccountId().equals(c.account().externalAccountId())||!o.credentialsRef().equals(c.account().credentialsRef())
                ||!o.resourceId().equals(c.recoveryOperation().id())||!o.operationKey().equals(c.recoveryOperation().providerOperationKey()+":CONFIRM")
                ||!o.fingerprint().equals(methods.recoveryConfirmFingerprint(c))||o.providerReference()!=null||o.firstDispatchedAt()==null||o.retryBefore()==null
                ||o.leaseOwner()==null||o.leaseOwner().isBlank()||o.leaseExpiresAt()==null||!o.leaseExpiresAt().equals(o.firstDispatchedAt().plusSeconds(60))
                ||!o.retryBefore().equals(o.firstDispatchedAt().plus(Duration.ofHours(23))))throw denied();
    }
    private void requireReply(RecoveryPreparationContext c,ProviderOperation claim,ConfirmReply r){
        var money=c.subscription().billingPrice().regularAmount();
        if(r==null||!r.paymentIntent().equals(c.reservation().failure().paymentIntent())||!r.customer().equals(c.binding().providerReference())
                ||!r.method().equals(c.completion().method())||!r.currency().equals(money.currency())||r.amount()!=money.minorUnits()
                ||r.observedAt().isBefore(claim.firstDispatchedAt())||r.observedAt().isAfter(clock.instant()))throw denied();
    }
    private void fresh(){if(transactions.active())throw denied();}
    public static String fingerprint(RecoveryPreparationContext c,DigestPort digest){
        var f=c.reservation().failure();var r=c.recoveryOperation();var x=c.completion();
        return ProviderRequestFingerprint.of(digest,c.account(),"OWNED_RECOVERY_CONFIRM_V1",r.id().toString(),r.providerOperationKey(),r.fingerprint(),c.reservation().id().toString(),
            f.billId().toString(),Long.toString(f.billVersion()),f.invoice(),f.allocation(),f.paymentIntent(),Long.toString(f.revision()),Long.toString(f.observationSequence()),
            c.binding().id().toString(),c.binding().providerReference(),x.setupStepId().toString(),Long.toString(x.setupStepVersion()),x.methodStepId().toString(),Long.toString(x.methodStepVersion()),x.method(),"off_session:true","error_on_requires_action:true");
    }
    private static IllegalStateException denied(){return new IllegalStateException("OWNED_RECOVERY_CONFIRM_UNAVAILABLE");}
}
