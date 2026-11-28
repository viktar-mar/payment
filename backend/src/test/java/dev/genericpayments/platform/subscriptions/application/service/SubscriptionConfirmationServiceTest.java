package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
/** Real orchestrator + memory persistence; no Spring container, database, credentials or network. */
class SubscriptionConfirmationServiceTest {
 final Instant now=Instant.parse("2026-10-29T00:00:00Z");
 final Subscription sub=SubscriptionFixtures.active(now.minusSeconds(60),now.plusSeconds(3600));
 final PaymentAccount account=PaymentAccount.create(sub.paymentAccountId(),"test",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_owned","stripe-sandbox-default");
 final UUID operation=UUID.randomUUID();
 final SubscriptionOperation op=new SubscriptionOperation(operation,sub.siteId(),sub.customerId(),sub.id(),SubscriptionOperation.Kind.CREATE,SubscriptionOperation.Status.PROCESSING,"customer:test","a".repeat(64),"v1.paid."+"a".repeat(64),"original",sub.revision(),1,"sub_owned",null,now,now,0);
 final SubscriptionInteractionTransaction.State state=new SubscriptionInteractionTransaction.State(sub,account,new SubscriptionPolicy(sub.siteId(),1,true,0,false,false,false,Set.of()),op,false);
 final CustomerSessionContext session=new CustomerSessionContext(new CustomerSessionId(UUID.randomUUID()),sub.siteId(),sub.customerId(),"https://shop.test",Set.of(CustomerSessionScope.SUBSCRIPTION_MANAGE),now.plusSeconds(1800));
 SubscriptionClientGrant root=new SubscriptionClientGrant(UUID.randomUUID(),sub.siteId(),sub.customerId(),account.id(),sub.id(),operation,"v1",new SubscriptionClientGrant.Binding(UUID.randomUUID(),"original","a".repeat(64),"sub_owned","cus_owned","in_owned","inpay_owned","pi_owned"),"b".repeat(64),now,now.plusSeconds(600),null,null,null,null,null);
 final SubscriptionInteractionTransaction phases=mock(SubscriptionInteractionTransaction.class);final SubscriptionActionStore roots=mock(SubscriptionActionStore.class);
 final Memory store=new Memory();final Provider provider=new Provider();final ThreadLocal<Boolean> locked=ThreadLocal.withInitial(()->false);boolean revoked;Instant currentTime=now;SubscriptionConfirmationService service;
 SubscriptionConfirmationUseCase.Scope scope(String key){return new SubscriptionConfirmationUseCase.Scope(new SubscriptionCommandContext(new SubscriptionActor.Customer(session),sub.siteId(),sub.customerId(),session.origin(),key,"test"),sub.id(),operation);}
 @SuppressWarnings("unchecked") @BeforeEach void setup(){
  when(phases.confirmationLocked(any(),any(),any(),any())).thenAnswer(x->{synchronized(store){locked.set(true);try{if(revoked)throw SubscriptionRuleErrors.conflict();return ((Function<SubscriptionInteractionTransaction.State,Object>)x.getArgument(3)).apply(state);}finally{locked.set(false);}}});
  when(phases.confirmationRoot(any(),anyString())).thenAnswer(x->root);when(roots.billing(anyString())).thenAnswer(x->Optional.of(root));
  ObjectProvider<SubscriptionConfirmationProvider> ports=mock(ObjectProvider.class);when(ports.getIfAvailable()).thenReturn(provider);
  service=new SubscriptionConfirmationService(phases,roots,store,ports,mock(CreateSubscriptionClientSessionUseCase.class),new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return currentTime;}});
 }
 @Test void declineExplicitRetryAndSameRootSuccessorRemainUsableAfterRootConsumption(){
  var initial=service.select(scope("select"),"root");assertThat(store.chain).isNotNull();assertThat(provider.reads).isEqualTo(1);
  var failed=service.confirm(scope("pay"),initial.attemptId(),initial.capability(),"ctoken_first");assertThat(failed.status().retryAllowed()).isTrue();assertThat(root.consumedAt()).isNotNull();
  assertThat(service.current(scope("read")).attemptId()).isEqualTo(initial.attemptId());
  var retried=service.retry(scope("retry"),initial.attemptId());assertThat(retried.successorAttemptId()).isNotNull();
  var next=service.session(scope("material"),retried.successorAttemptId());assertThat(next.attemptId()).isNotEqualTo(initial.attemptId());assertThat(next.capability()).isNotEqualTo(initial.capability());
  service.confirm(scope("pay-again"),next.attemptId(),next.capability(),"ctoken_second");assertThat(provider.confirmedIntents).containsExactly("pi_owned","pi_owned");assertThat(store.attempts).hasSize(2);
 }
 @Test void lostAcknowledgementReadAndCommandReplaysNeverDispatchOrMintAgain(){
  var initial=service.select(scope("select"),"root");service.confirm(scope("pay"),initial.attemptId(),initial.capability(),"ctoken_first");
  assertThat(service.confirm(scope("pay"),initial.attemptId(),initial.capability(),"ctoken_first").nextActionAllowed()).isFalse();
  var status=service.status(scope("read"),initial.attemptId());assertThat(status.retryAllowed()).isTrue();assertThat(provider.confirmedIntents).hasSize(1);
  var first=service.retry(scope("retry"),initial.attemptId());assertThat(service.retry(scope("retry"),initial.attemptId()).successorAttemptId()).isEqualTo(first.successorAttemptId());assertThat(store.attempts).hasSize(2);
  assertThatThrownBy(()->service.retry(scope("another"),initial.attemptId())).isInstanceOf(RuntimeException.class);
 }
 @Test void unknownAndOutstandingChallengeNeverGainRetry(){
  provider.outcome=SubscriptionConfirmationAttempt.State.REQUIRES_ACTION;var initial=service.select(scope("select"),"root");
  assertThat(service.confirm(scope("pay"),initial.attemptId(),initial.capability(),"ctoken_first").nextActionAllowed()).isTrue();
  assertThat(service.confirm(scope("pay"),initial.attemptId(),initial.capability(),"ctoken_first").nextActionAllowed()).isFalse();
  assertThatThrownBy(()->service.retry(scope("retry"),initial.attemptId())).isInstanceOf(RuntimeException.class);assertThat(store.attempts).hasSize(1);
 }
 @Test void unknownAfterTransportLossNeverReconfirmsOrAcquiresProof(){
  provider.throwOnConfirm=true;var initial=service.select(scope("select"),"root");
  assertThat(service.confirm(scope("pay"),initial.attemptId(),initial.capability(),"ctoken_first").status().state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);
  assertThat(service.confirm(scope("pay"),initial.attemptId(),initial.capability(),"ctoken_first").status().retryAllowed()).isFalse();
  assertThatThrownBy(()->service.retry(scope("retry"),initial.attemptId())).isInstanceOf(RuntimeException.class);assertThat(provider.confirmedIntents).hasSize(1);
 }
 @Test void expiryDuringGraphReadRefusesMaterialAndDispatch(){
  var initial=service.select(scope("select"),"root");provider.afterRead=()->currentTime=now.plusSeconds(600);
  assertThatThrownBy(()->service.confirm(scope("pay"),initial.attemptId(),initial.capability(),"ctoken_first")).isInstanceOf(RuntimeException.class);assertThat(provider.confirmedIntents).isEmpty();
 }
 @Test void authorityRevocationDuringGraphReadRefusesMaterial(){
  provider.afterRead=()->revoked=true;assertThatThrownBy(()->service.select(scope("select"),"root")).isInstanceOf(RuntimeException.class);assertThat(provider.confirmedIntents).isEmpty();
 }
 @Test void racingSelectionReturnsOneImmutableAttempt()throws Exception{
  try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
   var a=pool.submit(()->service.select(scope("select-one"),"root"));var b=pool.submit(()->service.select(scope("select-two"),"root"));assertThat(a.get().attemptId()).isEqualTo(b.get().attemptId());assertThat(store.attempts).hasSize(1);
  }
 }
 @Test void differentCustomerSessionCannotReadOrRetryExistingChain(){
  var first=service.select(scope("select"),"root");var other=new CustomerSessionContext(new CustomerSessionId(UUID.randomUUID()),sub.siteId(),sub.customerId(),session.origin(),session.scopes(),session.expiresAt());
  var foreign=new SubscriptionConfirmationUseCase.Scope(new SubscriptionCommandContext(new SubscriptionActor.Customer(other),sub.siteId(),sub.customerId(),other.origin(),"read","test"),sub.id(),operation);
  assertThatThrownBy(()->service.status(foreign,first.attemptId())).isInstanceOf(RuntimeException.class);assertThat(provider.confirmedIntents).isEmpty();
 }
 @Test void racingConfirmAndRetryHaveOneWinner()throws Exception{
  var initial=service.select(scope("select"),"root");
  try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
   var first=pool.submit(()->service.confirm(scope("pay"),initial.attemptId(),initial.capability(),"ctoken_first"));var second=pool.submit(()->service.confirm(scope("pay"),initial.attemptId(),initial.capability(),"ctoken_first"));first.get();second.get();
   assertThat(provider.confirmedIntents).hasSize(1);
   var a=pool.submit(()->service.retry(scope("retry"),initial.attemptId()));var b=pool.submit(()->service.retry(scope("retry"),initial.attemptId()));assertThat(a.get().successorAttemptId()).isEqualTo(b.get().successorAttemptId());assertThat(store.attempts).hasSize(2);
  }
 }
 @Test void changedTokenOrCapabilityCannotRedispatch(){
  var initial=service.select(scope("select"),"root");assertThatThrownBy(()->service.confirm(scope("pay"),initial.attemptId(),"bca2_"+"z".repeat(43),"ctoken_first")).isInstanceOf(RuntimeException.class);
  service.confirm(scope("pay"),initial.attemptId(),initial.capability(),"ctoken_first");assertThatThrownBy(()->service.confirm(scope("pay"),initial.attemptId(),initial.capability(),"ctoken_changed")).isInstanceOf(RuntimeException.class);assertThat(provider.confirmedIntents).hasSize(1);
 }
 @Test void legacySessionBindingCannotMigrate(){root=copyRoot(null,session.sessionId().value());assertThatThrownBy(()->service.select(scope("select"),"root")).isInstanceOf(RuntimeException.class);assertThat(store.chain).isNull();assertThat(provider.reads).isZero();}
 @Test void readCurrentWithoutChainDoesNotAllocateOrCallProvider(){assertThat(service.current(scope("read"))).isNull();assertThat(store.attempts).isEmpty();assertThat(provider.reads).isZero();}
 SubscriptionClientGrant copyRoot(Instant consumed,UUID sessionId){var r=root;return new SubscriptionClientGrant(r.id(),r.site(),r.customer(),r.account(),r.subscription(),r.operation(),r.keyVersion(),r.binding(),r.tokenDigest(),r.issuedAt(),r.expiresAt(),consumed,null,sessionId,sessionId==null?null:session.origin(),sessionId==null?null:r.expiresAt());}
 final class Memory implements SubscriptionConfirmationStore {
  SubscriptionConfirmationChain chain;Map<UUID,SubscriptionConfirmationAttempt> attempts=new HashMap<>();
  public Optional<SubscriptionConfirmationChain> chain(UUID id){return Optional.ofNullable(chain).filter(c->c.id().equals(id));}public Optional<SubscriptionConfirmationChain> current(UUID op){return Optional.ofNullable(chain);}
  public SubscriptionConfirmationAttempt attempt(UUID id){return Optional.ofNullable(attempts.get(id)).orElseThrow();}
  public SubscriptionConfirmationAttempt latest(UUID id){return attempts.values().stream().filter(a->a.successor()==null).findFirst().orElseThrow();}
  public void reserve(SubscriptionClientGrant root,SubscriptionConfirmationChain c,SubscriptionConfirmationAttempt a){if(chain!=null)throw new IllegalStateException();chain=c;insert(a);}
  public void insert(SubscriptionConfirmationAttempt a){if(attempts.putIfAbsent(a.id(),a)!=null)throw new IllegalStateException();}
  public void save(SubscriptionConfirmationAttempt a,long version,Instant at){assertThat(attempt(a.id()).version()).isEqualTo(version);attempts.put(a.id(),a);if(a.state()==SubscriptionConfirmationAttempt.State.DISPATCHING)root=copyRoot(now,null);}
 }
 final class Provider implements SubscriptionConfirmationProvider {
  int reads;Runnable afterRead=()->{};boolean throwOnConfirm;List<String> confirmedIntents=new java.util.concurrent.CopyOnWriteArrayList<>();SubscriptionConfirmationAttempt.State outcome=SubscriptionConfirmationAttempt.State.DECLINED;
  public String capability(SubscriptionClientGrant r,SubscriptionConfirmationChain c,SubscriptionConfirmationAttempt a){return "bca2_"+Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.HexFormat.of().parseHex(CreateSubscriptionFingerprint.digest(a.id().toString())));}
  public Material read(SubscriptionProviderModels.Lookup l,SubscriptionClientGrant r,SubscriptionConfirmationAttempt.Receipt receipt){assertThat(locked.get()).isFalse();assertThat(store.chain).isNotNull();reads++;afterRead.run();return new Material("pk_test_fixture","pi_owned_secret_canary");}
  public Outcome confirm(SubscriptionProviderModels.Lookup l,SubscriptionClientGrant r,SubscriptionConfirmationAttempt a,String token){assertThat(locked.get()).isFalse();assertThat(store.attempt(a.id()).state()).isEqualTo(SubscriptionConfirmationAttempt.State.DISPATCHING);confirmedIntents.add(r.binding().intentReference());if(throwOnConfirm)throw new IllegalStateException("transport lost");return new Outcome(outcome,outcome==SubscriptionConfirmationAttempt.State.DECLINED?new SubscriptionConfirmationAttempt.Receipt("req_original","ch_original","generic_decline"):null);}
 }
}
