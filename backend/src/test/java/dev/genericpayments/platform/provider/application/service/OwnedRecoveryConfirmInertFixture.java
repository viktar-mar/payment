package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.RecoveryPreparationContext;
import dev.genericpayments.platform.provider.application.model.OwnedRecoveryConfirm.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedRecoveryConfirmStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.assertThat;

/** Reusable pure bridge: actual original authentication + actual confirmation service, inert receipts only. */
public class OwnedRecoveryConfirmInertFixture {
    final OwnedRecoveryInertFixture f;final RecoveryPreparationContext expected;long nanos,transaction;Runnable afterCommit=()->{};
    final InvocationBudget budget;final Memory store;final SubscriptionTransactionPort tx;final OwnedRecoveryConfirmService service;
    public OwnedRecoveryConfirmInertFixture(){this(new OwnedRecoveryInertFixture(value->"digest:"+value));}
    public OwnedRecoveryConfirmInertFixture(OwnedRecoveryInertFixture original){
        f=Objects.requireNonNull(original);expected=f.inspect();budget=newBudget();store=new Memory(this);
        tx=new SubscriptionTransactionPort(){
            public boolean active(){return f.transactions().active();}public <T>T required(Supplier<T> work){throw new AssertionError("fresh transaction required");}
            public <T>T requiresNew(Supplier<T> work){
                var old=store.row;var acks=new ArrayList<>(store.acks);boolean committed=store.committed;long created=store.creatingTransaction;
                var invocation=store.invocation;var entered=store.entered;var deadline=store.deadline;
                transaction++;try{T result=f.transactions().requiresNew(work);if(store.creatingTransaction==transaction)store.committed=true;afterCommit.run();return result;}
                catch(RuntimeException e){store.row=old;store.acks.clear();store.acks.addAll(acks);store.committed=committed;store.creatingTransaction=created;store.invocation=invocation;store.entered=entered;store.deadline=deadline;throw e;}
            }
        };
        service=new OwnedRecoveryConfirmService(f.service(),store,tx,f.clock());f.trace().clear();f.h.b.operationLockTargets.clear();
    }
    public OwnedRecoveryConfirmService service(){return service;}
    public OwnedRecoveryConfirmStore receipts(){return store;}
    public SubscriptionTransactionPort transactions(){return tx;}
    public RecoveryPreparationContext expected(){return expected;}
    public ProviderOperation row(){return store.row;}
    public List<ConfirmationAck> acknowledgments(){return List.copyOf(store.acks);}
    public List<InvocationBudget> budgetUses(){return List.copyOf(store.budgets);}
    public Object receiptState(){return Arrays.asList(store.row,new ArrayList<>(store.acks),store.invocation,store.entered,store.deadline,store.creatingTransaction,store.committed);}
    public void afterCommit(Runnable action){afterCommit=Objects.requireNonNull(action);}
    public void afterLock(Runnable action){store.afterLock=Objects.requireNonNull(action);}
    public void afterEffect(Runnable action){store.afterEffect=Objects.requireNonNull(action);}
    public void failAck(boolean fail){store.failAck=fail;}
    InvocationBudget newBudget(){return new InvocationBudget(f.clock(),()->nanos);}
    ProviderOperation claim(){return service.claim(expected,f.command(),"first-claimant",budget);}
    ConfirmReply reply(){return new ConfirmReply(expected.reservation().failure().paymentIntent(),expected.binding().providerReference(),expected.completion().method(),"EUR",1000,1000,0,"ch_recovered",f.now());}

    /** Models only receipt storage and transaction identity; original authentication is never mocked. */
    static final class Memory implements OwnedRecoveryConfirmStore {
        final OwnedRecoveryConfirmInertFixture h;ProviderOperation row;final List<ConfirmationAck> acks=new ArrayList<>();final List<String> calls=new ArrayList<>();
        final List<InvocationBudget> budgets=new ArrayList<>();int writes;long creatingTransaction;boolean committed,failAck,effectReached;
        UUID invocation;Instant entered,deadline;Runnable afterLock=()->{},afterEffect=()->{};
        Memory(OwnedRecoveryConfirmInertFixture h){this.h=h;}
        void active(){assertThat(h.f.transactions().active()).isTrue();}
        void context(RecoveryPreparationContext c){assertThat(c).isEqualTo(h.expected);}
        public Optional<ProviderOperation> lockInTransaction(RecoveryPreparationContext c){active();context(c);calls.add("lock");h.f.trace().add("confirm-lock");afterLock.run();return Optional.ofNullable(row);}
        public ProviderOperation insertAndClaimInTransaction(RecoveryPreparationContext c,String claimant,Instant now,InvocationBudget budget){
            active();context(c);if(row!=null)throw new IllegalStateException("existing");budgets.add(budget);calls.add("insert");h.f.trace().add("confirm-insert");
            var a=c.account();row=new ProviderOperation(UUID.randomUUID(),a.id(),a.externalAccountId(),a.credentialsRef(),Kind.OWNED_RECOVERY_INTENT_CONFIRM,c.recoveryOperation().id(),c.recoveryOperation().providerOperationKey()+":CONFIRM",OwnedRecoveryConfirmService.fingerprint(c,h.f.digest),Status.DISPATCHING,null,now,now.plus(Duration.ofHours(23)),claimant,now.plusSeconds(60),1);
            invocation=budget.invocation();entered=budget.enteredAt();deadline=budget.deadlineAt();creatingTransaction=h.transaction;committed=false;writes++;effectReached=true;afterEffect.run();return row;
        }
        public ProviderOperation requireClaimInTransaction(RecoveryPreparationContext c,ProviderOperation claim,InvocationBudget budget,Instant now){
            active();context(c);calls.add("require");budgets.add(budget);
            if(!committed||creatingTransaction==h.transaction||!Objects.equals(row,claim)||row.status()!=Status.DISPATCHING||!invocation.equals(budget.invocation())||!entered.equals(budget.enteredAt())||!deadline.equals(budget.deadlineAt())||!now.isBefore(deadline)||!now.isBefore(row.leaseExpiresAt()))throw new IllegalStateException("claim unavailable");return row;
        }
        public ConfirmationAck acknowledgeInTransaction(RecoveryPreparationContext c,ProviderOperation claim,ConfirmReply reply,Instant now,InvocationBudget budget){
            requireClaimInTransaction(c,claim,budget,now);h.f.trace().add("confirm-ack");row=new ProviderOperation(row.id(),row.accountId(),row.externalAccountId(),row.credentialsRef(),row.kind(),row.resourceId(),row.operationKey(),row.fingerprint(),Status.ACKNOWLEDGED,reply.paymentIntent(),row.firstDispatchedAt(),row.retryBefore(),null,null,2);
            var ack=new ConfirmationAck(row,reply,now);acks.add(ack);writes++;effectReached=true;afterEffect.run();if(failAck)throw new IllegalStateException("receipt write failed");return ack;
        }
        public boolean unknownInTransaction(RecoveryPreparationContext c,ProviderOperation claim){
            active();context(c);calls.add("unknown");if(!committed||creatingTransaction==h.transaction||!Objects.equals(row,claim)||row.status()!=Status.DISPATCHING)return false;
            row=copy(row,Status.UNKNOWN,2);writes++;return true;
        }
        ProviderOperation copy(ProviderOperation o,Status status,long version){boolean dispatch=status==Status.DISPATCHING;return new ProviderOperation(o.id(),o.accountId(),o.externalAccountId(),o.credentialsRef(),o.kind(),o.resourceId(),o.operationKey(),o.fingerprint(),status,status==Status.ACKNOWLEDGED?h.expected.reservation().failure().paymentIntent():null,status==Status.RESERVED?null:o.firstDispatchedAt(),status==Status.RESERVED?null:o.retryBefore(),dispatch?o.leaseOwner():null,dispatch?o.leaseExpiresAt():null,version);}
    }
}
