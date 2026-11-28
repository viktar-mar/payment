package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.adapter.out.persistence.JdbcStripeBillingClockSubscriptionStore;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingClockSubscriptionStore;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Audited standalone records, handwritten ports and orchestrator only. No Mockito, context, socket or environment. */
class OwnedInitialConfirmationContractTest {
 static UUID id(int n){return new UUID(0,n);}
 static final Instant NOW=Instant.parse("2026-10-29T00:00:00Z");
 static SubscriptionClientGrant.Binding binding(String schema,UUID owned){return new SubscriptionClientGrant.Binding(id(8),"subscription:"+id(3)+":"+id(5)+":CREATE","a".repeat(64),"sub_owned","cus_owned","in_owned","inpay_owned","pi_owned",schema,null,owned);}
 @Test void ownedDescriptorCannotExistWithoutOriginalIntentLinkage(){
  assertThatThrownBy(()->new SubscriptionClientGrant.Binding(id(8),"original","a".repeat(64),"sub_owned","cus_owned","in_owned","inpay_owned","pi_owned","owned-initial-v1",null)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void ordinaryDescriptorCannotClaimOwnedLinkage(){assertThatThrownBy(()->binding("payment-v1",id(9))).isInstanceOf(IllegalArgumentException.class);}
 @Test void ordinaryDescriptorRetainsFixedOriginalCanonicalBytes(){
  var f=new Fixture();var g=f.root;
  String expected="36:"+id(7)+"36:"+id(1)+"36:"+id(2)+"36:"+id(3)+"36:"+id(4)+"36:"+id(5)+"2:v136:"+id(8)+"93:subscription:"+id(3)+":"+id(5)+":CREATE64:"+"a".repeat(64)+"9:sub_owned9:cus_owned8:in_owned11:inpay_owned8:pi_owned20:2026-10-29T00:00:00Z20:2026-10-29T00:10:00Z";
  assertThat(g.descriptor()).isEqualTo(expected);
 }
 @Test void ownedDescriptorIsDomainSeparatedAndMapsToOriginalCreatePayment(){var f=new Fixture();String ordinary=f.root.descriptor();f.ownedRoot();assertThat(f.root.descriptor()).startsWith("45:gp:stripe-billing-descriptor:owned-initial-v1").contains(f.receipt.intent().id().toString()).isNotEqualTo(ordinary);assertThat(f.root.binding().stepKind()).isEqualTo("SUBSCRIPTION_CREATE");assertThat(f.root.binding().flow()).isEqualTo(SubscriptionClientSession.Flow.PAYMENT);}
 @Test void defaultServiceRejectsOwnedRootBeforeChainMutationOrProvider(){var f=new Fixture();f.ownedRoot();assertThatThrownBy(()->f.service(false).select(f.scope,"root")).isInstanceOf(RuntimeException.class);assertThat(f.store.chain).isNull();assertThat(f.provider.reads).isZero();}
 @Test void ownedServiceRejectsOrdinaryRootBeforeChainMutationOrProvider(){var f=new Fixture();assertThatThrownBy(()->f.service(true).select(f.scope,"root")).isInstanceOf(RuntimeException.class);assertThat(f.store.chain).isNull();assertThat(f.provider.reads).isZero();}
 @Test void ownedServiceRejectsDifferentOriginalIntentBeforeChainMutation(){var f=new Fixture();f.root=f.root(binding("owned-initial-v1",id(99)));assertThatThrownBy(()->f.service(true).select(f.scope,"root")).isInstanceOf(RuntimeException.class);assertThat(f.store.chain).isNull();}
 @Test void ownedRetryNeverReadsOrCallsProvider(){var f=new Fixture();assertThatThrownBy(()->f.service(true).retry(f.scope,id(999))).isInstanceOf(RuntimeException.class);assertThat(f.provider.reads).isZero();assertThat(f.store.loads).isZero();}
 @Test void ownedPredecessorCannotReleaseSessionOrStatus(){var f=new Fixture();f.ownedRoot();var service=f.service(true);var selected=service.select(f.scope,"root");var child=SubscriptionConfirmationAttempt.ready(id(99),f.root.id(),selected.attemptId(),"v1",NOW,NOW.plusSeconds(600));f.store.insert(child);assertThatThrownBy(()->service.session(f.scope,child.id())).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->service.status(f.scope,child.id())).isInstanceOf(RuntimeException.class);assertThat(f.provider.reads).isEqualTo(1);}
 @Test void ownedDeclineCannotBecomeRetryPermission(){var f=new Fixture();f.ownedRoot();var service=f.service(true);var selected=service.select(f.scope,"root");var result=service.confirm(f.scope,selected.attemptId(),selected.capability(),"ctoken_test");assertThat(result.status().state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(result.status().retryAllowed()).isFalse();assertThat(service.confirm(f.scope,selected.attemptId(),selected.capability(),"ctoken_test").status().state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(f.provider.confirms).isEqualTo(1);}
 @Test void ownedMissingProviderOutcomeBecomesUnknownWithoutRedispatch(){var f=new Fixture();f.ownedRoot();f.provider.outcome=null;var service=f.service(true);var selected=service.select(f.scope,"root");assertThat(service.confirm(f.scope,selected.attemptId(),selected.capability(),"ctoken_test").status().state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(f.provider.confirms).isEqualTo(1);}
 @Test void ordinaryDeclineAndExplicitSuccessorRemainSupported(){var f=new Fixture();var service=f.service(false);var selected=service.select(f.scope,"root");assertThat(service.confirm(f.scope,selected.attemptId(),selected.capability(),"ctoken_test").status().retryAllowed()).isTrue();assertThat(service.retry(f.scope,selected.attemptId()).successorAttemptId()).isNotNull();assertThat(f.store.attempts).hasSize(2);}
 @Test void currentWithoutChainStillRequiresAuthority(){var f=new Fixture();f.denied=true;assertThatThrownBy(()->f.service(false).current(f.scope)).isInstanceOf(RuntimeException.class);assertThat(f.store.loads).isZero();}
 @Test void expiredRealUtcSessionFailsWhileFrozenTimeIsFarAway(){var f=new Fixture();f.ownedRoot();var service=f.service(true);var selected=service.select(f.scope,"root");var expired=new CustomerSessionContext(new CustomerSessionId(id(6)),f.site,f.customer,"https://shop.test",Set.of(CustomerSessionScope.SUBSCRIPTION_MANAGE),NOW.minusSeconds(1));var scope=new SubscriptionConfirmationUseCase.Scope(new SubscriptionCommandContext(new SubscriptionActor.Customer(expired),f.site,f.customer,expired.origin(),"key","test"),id(4),id(5));assertThat(f.receipt.intent().clock().frozenTime()).isEqualTo(1000);assertThatThrownBy(()->service.status(scope,selected.attemptId())).isInstanceOf(RuntimeException.class);}
 @Test void directStoreGuardsRequireActualCallerTransactionBeforeAnyJdbc(){var f=new Fixture();var store=new JdbcStripeBillingClockSubscriptionStore(null,null);assertThatThrownBy(()->store.requireOrdinaryConfirmation(f.account.id(),f.site,f.customer)).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->store.requireConfirmationReceipt(f.receipt,f.account,f.sub,f.op)).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->JdbcStripeBillingClockSubscriptionStore.requireConfirmationGrant(null,f.root,f.receipt)).isInstanceOf(IllegalStateException.class);}
 @Test void explicitOwnedConstructorCannotAcceptNull(){var f=new Fixture();assertThatThrownBy(()->new SubscriptionConfirmationService(f.phases,f.roots,f.store,f.ports,null,Clock.fixed(NOW,ZoneOffset.UTC),null)).isInstanceOf(NullPointerException.class);}
@Test void originalReceiptAndEveryGraphAssociationChangeProof(){var f=new Fixture();var r=f.receipt;String proof=OwnedInitialConfirmationProof.hash(r,"in_owned","inpay_owned","pi_owned");assertThat(proof).matches("[a-f0-9]{64}");var changed=new OwnedClockSubscriptionReceipt(r.intent(),r.providerOperation(),r.reference(),r.providerCreated()+1,r.itemReference(),r.periodStart(),r.periodEnd(),r.acknowledgedAt());assertThat(OwnedInitialConfirmationProof.hash(changed,"in_owned","inpay_owned","pi_owned")).isNotEqualTo(proof);assertThat(OwnedInitialConfirmationProof.hash(r,"in_foreign","inpay_owned","pi_owned")).isNotEqualTo(proof);assertThat(OwnedInitialConfirmationProof.hash(r,"in_owned","inpay_foreign","pi_owned")).isNotEqualTo(proof);assertThat(OwnedInitialConfirmationProof.hash(r,"in_owned","inpay_owned","pi_foreign")).isNotEqualTo(proof);}
@Test void forgedProofFailsOwnedSelectionBeforeChainMutation(){var f=new Fixture();f.root=f.root(binding("owned-initial-v1",f.intent.id()));assertThatThrownBy(()->f.service(true).select(f.scope,"root")).isInstanceOf(RuntimeException.class);assertThat(f.store.chain).isNull();}
@Test void realPhasesUseOperationAuthorityAccountSubscriptionThenMandatoryOrdinaryGuard(){var f=new Fixture();var phases=f.realPhases();assertThat(phases.<SubscriptionInteractionTransaction.State>confirmationLocked(f.scope.context(),f.sub.id(),f.op.id(),state->{f.order.add("work");return state;})).isEqualTo(f.state);assertThat(f.order).containsExactly("operation","authority","find-subscription","account","subscription","policy","ordinary-guard","work");assertThat(f.transactions).isEqualTo(1);}
@Test void realOwnedPhaseUsesExactReceiptGuardWithoutNestedTransaction(){var f=new Fixture();var phases=f.realPhases();phases.confirmationLocked(f.scope.context(),f.sub.id(),f.op.id(),f.receipt,state->{f.order.add("work");return null;});assertThat(f.order).containsExactly("operation","authority","find-subscription","account","subscription","policy","owned-guard","work");assertThat(f.transactions).isEqualTo(1);}
@Test void realPhaseGuardRejectsBeforeAbsentChainLookup(){var f=new Fixture();f.denied=true;var service=new SubscriptionConfirmationService(f.realPhases(),f.roots,f.store,f.ports,null,Clock.fixed(NOW,ZoneOffset.UTC));assertThatThrownBy(()->service.current(f.scope)).isInstanceOf(RuntimeException.class);assertThat(f.store.loads).isZero();assertThat(f.order).endsWith("ordinary-guard");}
@Test void realPhaseExpiresSessionUsingUtcBeforeAuthorityCallback(){var f=new Fixture();var phases=f.realPhases();var old=new CustomerSessionContext(new CustomerSessionId(id(6)),f.site,f.customer,"https://shop.test",Set.of(CustomerSessionScope.SUBSCRIPTION_MANAGE),NOW.minusSeconds(1));var context=new SubscriptionCommandContext(new SubscriptionActor.Customer(old),f.site,f.customer,old.origin(),"key","test");assertThatThrownBy(()->phases.confirmationLocked(context,f.sub.id(),f.op.id(),f.receipt,state->{throw new AssertionError("must not run");})).isInstanceOf(RuntimeException.class);assertThat(f.order).doesNotContain("owned-guard");}
@Test void realPhaseRejectsAmbientTransactionBeforeAnyLock(){var f=new Fixture();var phases=f.realPhases();f.transactionActive=true;assertThatThrownBy(()->phases.confirmationLocked(f.scope.context(),f.sub.id(),f.op.id(),state->state)).isInstanceOf(IllegalStateException.class);assertThat(f.order).isEmpty();}
@Test void storeMethodsOverrideRequiresNewWithMandatory()throws Exception{var type=JdbcStripeBillingClockSubscriptionStore.class;for(var method:List.of(type.getMethod("requireOrdinaryConfirmation",PaymentAccountId.class,SiteId.class,CustomerId.class),type.getMethod("requireConfirmationReceipt",OwnedClockSubscriptionReceipt.class,PaymentAccount.class,Subscription.class,SubscriptionOperation.class)))assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class).propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.MANDATORY);}
 @SuppressWarnings("unchecked") static <T>T port(Class<T> type,java.lang.reflect.InvocationHandler h){return (T)java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},h);}
 static class Fixture {
  final SiteId site=new SiteId(id(1));final CustomerId customer=new CustomerId(id(2));
  final PaymentAccount account=PaymentAccount.create(new PaymentAccountId(id(3)),"test",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_fixture","fixture");
  final SubscriptionPriceTerms terms=new SubscriptionPriceTerms(new PriceId(id(10)),"price",1,new ProductId(id(11)),Money.of(1000,"EUR"),BillingInterval.MONTH,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
  final Subscription sub=new Subscription(id(4),site,customer,terms.productId(),account.id(),terms,null,Subscription.Status.INCOMPLETE,"sub_owned",null,null,null,null,null,null,null,1,NOW,NOW,null);
  final SubscriptionOperation op=new SubscriptionOperation(id(5),site,customer,sub.id(),SubscriptionOperation.Kind.CREATE,SubscriptionOperation.Status.PROCESSING,"customer:test","a".repeat(64),"v1.paid."+"b".repeat(64),binding("payment-v1",null).operationKey(),0,1,"sub_owned",null,NOW,NOW,0);
  final SubscriptionInteractionTransaction.State state=new SubscriptionInteractionTransaction.State(sub,account,new SubscriptionPolicy(site,1,true,0,false,false,false,Set.of()),op,false);
  final ClockOwner owner=new ClockOwner(id(12),id(13),account.id(),site,customer,account.externalAccountId(),account.credentialsRef(),"version",1000,"test","key","a".repeat(64),NOW);
  final ReadyClock clock=new ReadyClock(owner,id(14),"clock_test",1000,500,999999,NOW,"b".repeat(64));
  final OwnedClockSubscription intent=new OwnedClockSubscription(id(9),clock,id(15),id(16),"cus_owned",sub.id(),op.id(),op.providerOperationKey(),op.fingerprint(),0,1,terms,"price_test","prod_test","c".repeat(64),NOW);
  final OwnedClockSubscriptionReceipt receipt=new OwnedClockSubscriptionReceipt(intent,id(8),"sub_owned",777,"si_test",900,1100,NOW);
  final CustomerSessionContext session=new CustomerSessionContext(new CustomerSessionId(id(6)),site,customer,"https://shop.test",Set.of(CustomerSessionScope.SUBSCRIPTION_MANAGE),NOW.plusSeconds(1200));
  final SubscriptionConfirmationUseCase.Scope scope=new SubscriptionConfirmationUseCase.Scope(new SubscriptionCommandContext(new SubscriptionActor.Customer(session),site,customer,session.origin(),"key","test"),sub.id(),op.id());
  SubscriptionClientGrant root=root(binding("payment-v1",null));boolean denied;
  SubscriptionClientGrant root(SubscriptionClientGrant.Binding b){return new SubscriptionClientGrant(id(7),site,customer,account.id(),sub.id(),op.id(),"v1",b,"d".repeat(64),NOW,NOW.plusSeconds(600),null,null,null,null,null);}
  void ownedRoot(){var b=binding("owned-initial-v1",intent.id());root=root(new SubscriptionClientGrant.Binding(b.stepId(),b.operationKey(),OwnedInitialConfirmationProof.hash(receipt,b.invoiceReference(),b.allocationReference(),b.intentReference()),b.subscriptionReference(),b.customerReference(),b.invoiceReference(),b.allocationReference(),b.intentReference(),b.schema(),null,b.ownedIntent()));}
  final SubscriptionActionStore roots=port(SubscriptionActionStore.class,(p,m,a)->{if(m.getName().equals("billing"))return Optional.of(root);throw new UnsupportedOperationException(m.getName());});
  final Memory store=new Memory();final Provider provider=new Provider();
  final ObjectProvider<SubscriptionConfirmationProvider> ports=port(ObjectProvider.class,(p,m,a)->provider);
  final SubscriptionInteractionTransaction phases=new SubscriptionInteractionTransaction(null,null,null,null,null,null,null,null,null,Clock.fixed(NOW,ZoneOffset.UTC)){
   @Override public <T>T confirmationLocked(SubscriptionCommandContext c,UUID s,UUID o,Function<State,T> work){if(denied)throw SubscriptionRuleErrors.conflict();return work.apply(state);}
   @Override public <T>T confirmationLocked(SubscriptionCommandContext c,UUID s,UUID o,OwnedClockSubscriptionReceipt expected,Function<State,T> work){if(denied||!expected.equals(receipt))throw SubscriptionRuleErrors.conflict();return work.apply(state);}
   @Override SubscriptionClientGrant confirmationRoot(State state,String token){return root;}
  };
  SubscriptionConfirmationService service(boolean owned){return owned?new SubscriptionConfirmationService(phases,roots,store,ports,null,Clock.fixed(NOW,ZoneOffset.UTC),receipt):new SubscriptionConfirmationService(phases,roots,store,ports,null,Clock.fixed(NOW,ZoneOffset.UTC));}
final List<String> order=new ArrayList<>();boolean transactionActive;int transactions;
SubscriptionInteractionTransaction realPhases(){
 SubscriptionTransactionPort tx=new SubscriptionTransactionPort(){public <T>T required(Supplier<T> work){return requiresNew(work);}public <T>T requiresNew(Supplier<T> work){if(transactionActive)throw new AssertionError("nested transaction");transactions++;transactionActive=true;try{return work.get();}finally{transactionActive=false;}}public boolean active(){return transactionActive;}};
 var operations=port(SubscriptionOperationStore.class,(p,m,a)->{order.add("operation");return op;});
 var subscriptions=port(SubscriptionStore.class,(p,m,a)->{if(m.getName().equals("find")){order.add("find-subscription");return Optional.of(sub);}order.add("subscription");return sub;});
 var authority=port(SubscriptionAuthorityPort.class,(p,m,a)->{order.add("authority");return null;});
 var policies=port(SubscriptionPolicyPort.class,(p,m,a)->{order.add("policy");return state.policy();});
 var accounts=port(dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort.class,(p,m,a)->{order.add("account");return Optional.of(account);});
 var guards=port(StripeBillingClockSubscriptionStore.class,(p,m,a)->{if(!transactionActive)throw new AssertionError("guard outside transaction");order.add(m.getName().equals("requireOrdinaryConfirmation")?"ordinary-guard":"owned-guard");if(denied)throw SubscriptionRuleErrors.conflict();if(m.getName().equals("requireConfirmationReceipt")){assertThat(a[0]).isEqualTo(receipt);return receipt;}return null;});
 var result=new SubscriptionInteractionTransaction(tx,operations,subscriptions,authority,policies,accounts,roots,null,null,Clock.fixed(Instant.ofEpochSecond(1000),ZoneOffset.UTC),guards);
 try{var field=SubscriptionInteractionTransaction.class.getDeclaredField("securityClock");field.setAccessible(true);field.set(result,Clock.fixed(NOW,ZoneOffset.UTC));}catch(ReflectiveOperationException e){throw new AssertionError(e);}
 return result;
}
  class Memory implements SubscriptionConfirmationStore {
   SubscriptionConfirmationChain chain;Map<UUID,SubscriptionConfirmationAttempt> attempts=new HashMap<>();int loads;
   public Optional<SubscriptionConfirmationChain> chain(UUID id){loads++;return Optional.ofNullable(chain).filter(c->c.id().equals(id));}
   public Optional<SubscriptionConfirmationChain> current(UUID id){loads++;return Optional.ofNullable(chain);}
   public SubscriptionConfirmationAttempt attempt(UUID id){loads++;return attempts.get(id);}
   public SubscriptionConfirmationAttempt latest(UUID id){return attempts.values().stream().filter(a->a.successor()==null).findFirst().orElseThrow();}
   public void reserve(SubscriptionClientGrant r,SubscriptionConfirmationChain c,SubscriptionConfirmationAttempt a){chain=c;insert(a);}
   public void insert(SubscriptionConfirmationAttempt a){attempts.put(a.id(),a);}
   public void save(SubscriptionConfirmationAttempt a,long version,Instant at){attempts.put(a.id(),a);}
  }
  class Provider implements SubscriptionConfirmationProvider {
   int reads,confirms;Outcome outcome=new Outcome(SubscriptionConfirmationAttempt.State.DECLINED,new SubscriptionConfirmationAttempt.Receipt("req_test","ch_test","generic_decline"));
   public Material read(SubscriptionProviderModels.Lookup lookup,SubscriptionClientGrant root,SubscriptionConfirmationAttempt.Receipt receipt){reads++;return new Material("pk_test_fixture","pi_owned_secret_fixture");}
   public String capability(SubscriptionClientGrant root,SubscriptionConfirmationChain chain,SubscriptionConfirmationAttempt attempt){return "bca2_"+"a".repeat(43);}
   public Outcome confirm(SubscriptionProviderModels.Lookup lookup,SubscriptionClientGrant root,SubscriptionConfirmationAttempt attempt,String token){confirms++;return outcome;}
  }
 }
}
