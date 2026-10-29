package dev.genericpayments.platform.subscriptions.adapter.in.web;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.jayway.jsonpath.JsonPath;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingGrantTokens;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
/** Hosted PostgreSQL only. Never select this class for local execution. */
@Import(StripeSubscriptionConfirmationIntegrationTest.Ports.class)
class StripeSubscriptionConfirmationIntegrationTest extends SubscriptionApiFixture {
 @TestConfiguration(proxyBeanMethods=false) static class Ports {
  @Bean @Primary SubscriptionConfirmationProvider confirmationFixture(){return mock(SubscriptionConfirmationProvider.class);}
  @Bean @Primary SubscriptionClientSessionPort clientFixture(){return mock(SubscriptionClientSessionPort.class);}
  @Bean @Primary SubscriptionProviderRegistry registryFixture(){return mock(SubscriptionProviderRegistry.class);}
 }
 @Autowired SubscriptionConfirmationProvider confirmations;@Autowired SubscriptionClientSessionPort clients;
 @Autowired SubscriptionRecoveryProofStore recoveryProofs;
 @Autowired SubscriptionActionStore actions;@Autowired SubscriptionConfirmationStore attempts;@Autowired ProviderOperationStore steps;@Autowired StripeBillingBindingPort bindings;
 SubscriptionClientGrant grant;UUID subscriptionId,operationId;Instant at;
 String raw="bga1_"+"C".repeat(43),secret="pi_owned_secret_canary",capability="bca2_"+"D".repeat(43);
 @BeforeEach void original(){
  reset(confirmations,clients,providers);var legacyProvider=mock(SubscriptionProviderPort.class);when(providers.forAccount(any())).thenReturn(legacyProvider);when(legacyProvider.nextAction(any())).thenAnswer(x->new NextAction(ActionKind.PROVIDER_CLIENT,raw,grant.expiresAt()));at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);TestClock.set(at);
  token=sessions.issue(site,customer.id(),"https://facts.test").rawToken();actor=new SubscriptionActor.Customer(sessions.resolveRequired(token,"https://facts.test"));
  jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_owned',credentials_ref='stripe-sandbox-default' where id=?",account.id().value());account=accounts.findById(account.id()).orElseThrow();
  var reserved=commands.reserveCreate(command("original-create"));subscriptionId=reserved.subscription().id();operationId=reserved.operation().id();
  var customerStep=ack(ProviderOperation.Kind.CUSTOMER_CREATE,customer.id().value(),"customer-step","cus_owned");bindings.bindCustomer(account.id(),site,customer.id(),"cus_owned",customerStep.id());
  var step=ack(ProviderOperation.Kind.SUBSCRIPTION_CREATE,operationId,reserved.operation().providerOperationKey(),"sub_owned");commands.recordReply(operationId,new Reply(Outcome.ACCEPTED,"sub_owned",null));
  grant=new SubscriptionClientGrant(UUID.randomUUID(),site,customer.id(),account.id(),subscriptionId,operationId,"v1",new SubscriptionClientGrant.Binding(step.id(),reserved.operation().providerOperationKey(),"a".repeat(64),"sub_owned","cus_owned","in_owned","inpay_owned","pi_owned"),StripeBillingGrantTokens.hash(raw),at,at.plusSeconds(600),null,null,null,null,null);
  tx.required(()->{operations.lock(operationId);actions.reserveBilling(grant);return null;});
  when(confirmations.capability(any(),any(),any())).thenReturn(capability);
  when(confirmations.read(any(),any(),any())).thenAnswer(x->{assertThat(tx.active()).isFalse();assertThat(jdbc.queryForObject("select count(*) from subscription_confirmation_chains",Integer.class)).isEqualTo(1);return new SubscriptionConfirmationProvider.Material("pk_test_fixture",secret);});
  when(confirmations.confirm(any(),any(),any(),anyString())).thenAnswer(x->{assertThat(tx.active()).isFalse();assertThat(jdbc.queryForObject("select count(*) from subscription_confirmation_attempts where state='DISPATCHING'",Integer.class)).isEqualTo(1);return new SubscriptionConfirmationProvider.Outcome(SubscriptionConfirmationAttempt.State.DECLINED,new SubscriptionConfirmationAttempt.Receipt("req_original","ch_owned","generic_decline"));});
  when(clients.retrieve(any(),any(),anyString())).thenReturn(new SubscriptionClientSession("stripe",SubscriptionClientSession.Flow.PAYMENT,"pk_test_fixture",secret,raw,grant.expiresAt()));
 }
 ProviderOperation ack(ProviderOperation.Kind kind,UUID resource,String key,String reference){var p=steps.reserve(account,kind,resource,key,"b".repeat(64));String owner=UUID.randomUUID().toString();var claimed=steps.claim(p.id(),owner,at,Duration.ofSeconds(60)).orElseThrow();return steps.acknowledge(p.id(),owner,claimed.version(),reference);}
 String path(){return BASE+"/"+subscriptionId+"/operations/"+operationId;}
 String rootBody(){return "{\"action_token\":\""+raw+"\"}";}
 String confirmBody(String token){return "{\"capability\":\""+capability+"\",\"confirmation_token\":\""+token+"\"}";}
 UUID select()throws Exception{return UUID.fromString(JsonPath.read(write(path()+"/confirmation-session","select",rootBody()).andExpect(status().isOk()).andExpect(jsonPath("$.protocol").value("SERVER_CONFIRM_V2")).andExpect(jsonPath("$.client_secret").value(secret)).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString(),"$.attempt_id"));}
 String confirm(UUID id,String key)throws Exception{return write(path()+"/confirmation-attempts/"+id+"/confirm",key,confirmBody("ctoken_original")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();}
 String retry(UUID id)throws Exception{return write(path()+"/confirmation-attempts/"+id+"/retry","retry","{}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();}
 @Test void positiveDurableDeclineRetryAndConsumedRootSuccessorDoNotSettleCash()throws Exception{
  var id=select();assertThat(JsonPath.<Boolean>read(confirm(id,"pay"),"$.retry_allowed")).isTrue();assertThat(JsonPath.<Boolean>read(confirm(id,"pay"),"$.next_action_allowed")).isFalse();
  read(path()+"/confirmation-attempts/current").andExpect(status().isOk()).andExpect(jsonPath("$.attempt_id").value(id.toString())).andExpect(jsonPath("$.capability").doesNotExist());
  UUID next=UUID.fromString(JsonPath.read(retry(id),"$.successor_attempt_id"));assertThat(JsonPath.<String>read(retry(id),"$.successor_attempt_id")).isEqualTo(next.toString());
  write(path()+"/confirmation-attempts/"+next+"/client-session","session","{}").andExpect(status().isOk()).andExpect(jsonPath("$.attempt_id").value(next.toString()));confirm(next,"pay-next");
  verify(confirmations,times(2)).confirm(any(),argThat(g->g.binding().intentReference().equals("pi_owned")),any(),eq("ctoken_original"));assertThat(count("payments")).isZero();assertThat(sub(subscriptionId).paidThrough()).isNull();
  String rows=jdbc.queryForObject("select json_agg(a)::text from subscription_confirmation_attempts a",String.class);assertThat(rows).doesNotContain(secret,capability,"ctoken_original");
  assertThat(jdbc.queryForObject("select consumed_at from subscription_action_capabilities where id=?",java.sql.Timestamp.class,grant.id()).toInstant()).isAfter(at);
 }
 @Test void v2ReservationBlocksLegacySessionClaimAndExpiredRootReplacement()throws Exception{
  select();write(path()+"/client-session","legacy",rootBody()).andExpect(status().isConflict());write(path()+"/action-confirmations","legacy-pay",rootBody()).andExpect(status().isConflict());
  var future=at.plusSeconds(601);var rotated=new SubscriptionClientGrant(UUID.randomUUID(),grant.site(),grant.customer(),grant.account(),grant.subscription(),grant.operation(),grant.keyVersion(),grant.binding(),"f".repeat(64),future,future.plusSeconds(600),null,null,null,null,null);
  assertThatThrownBy(()->tx.required(()->{actions.reserveBilling(rotated);return null;})).isInstanceOf(RuntimeException.class);assertThat(jdbc.queryForObject("select count(*) from subscription_action_capabilities",Integer.class)).isEqualTo(1);
 }
 @Test void legacyBoundGrantCannotMigrate()throws Exception{write(path()+"/client-session","legacy",rootBody()).andExpect(status().isOk());write(path()+"/confirmation-session","v2",rootBody()).andExpect(status().isConflict());verifyNoInteractions(confirmations);}
 @Test void v1FinalSecretFenceRejectsV2WinnerDuringProviderRead()throws Exception{
  var readStarted=new CountDownLatch(1);var release=new CountDownLatch(1);
  when(clients.retrieve(any(),any(),anyString())).thenAnswer(x->{readStarted.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException();return new SubscriptionClientSession("stripe",SubscriptionClientSession.Flow.PAYMENT,"pk_test_fixture",secret,raw,grant.expiresAt());});
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
   var legacy=pool.submit(()->write(path()+"/client-session","legacy",rootBody()));assertThat(readStarted.await(10,TimeUnit.SECONDS)).isTrue();try{select();}finally{release.countDown();}legacy.get(10,TimeUnit.SECONDS).andExpect(status().isConflict()).andExpect(jsonPath("$.client_secret").doesNotExist());
  }
 }
 @Test void concurrencyHasOneConfirmDispatchAndOneSuccessor()throws Exception{
  var id=select();try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var a=pool.submit(()->confirm(id,"same"));var b=pool.submit(()->confirm(id,"same"));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);verify(confirmations,times(1)).confirm(any(),any(),any(),any());var c=pool.submit(()->retry(id));var d=pool.submit(()->retry(id));assertThat(JsonPath.<String>read(c.get(10,TimeUnit.SECONDS),"$.successor_attempt_id")).isEqualTo(JsonPath.read(d.get(10,TimeUnit.SECONDS),"$.successor_attempt_id"));}
  assertThat(jdbc.queryForObject("select count(*) from subscription_confirmation_attempts",Integer.class)).isEqualTo(2);
 }
 @Test void proofClaimAndProtocolIdentityCannotBeRewritten()throws Exception{
  var id=select();confirm(id,"pay");for(String change:List.of("state='READY'","request_id='req_other'","token_digest='changed'","provider_key='other'","claimed_at=claimed_at+interval '1 second'"))assertThatThrownBy(()->jdbc.update("update subscription_confirmation_attempts set "+change+",version=version+1 where id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
  assertThatThrownBy(()->jdbc.update("update subscription_confirmation_chains set origin='https://other.test' where id=?",grant.id())).isInstanceOf(DataIntegrityViolationException.class);
  assertThatThrownBy(()->tx.required(()->{attempts.insert(SubscriptionConfirmationAttempt.ready(UUID.randomUUID(),grant.id(),null,"v1",at,grant.expiresAt()));return null;})).isInstanceOf(DataIntegrityViolationException.class);
 }
 @Test void recoveryInvoiceExclusionAllowsOnlyExactConsumedV2Root()throws Exception{
  var id=select();confirm(id,"pay");
  assertThat(recoveryProofs.available(account.id(),subscriptionId,"in_owned",operationId)).isFalse();
  assertThat(recoveryProofs.availableForConfirmation(account.id(),subscriptionId,"in_owned",operationId,grant.id())).isTrue();
  assertThat(recoveryProofs.availableForConfirmation(account.id(),subscriptionId,"in_owned",operationId,UUID.randomUUID())).isFalse();
  assertThat(recoveryProofs.availableForConfirmation(account.id(),subscriptionId,"in_owned",UUID.randomUUID(),grant.id())).isFalse();
 }
 @Test void legacyConsumedRootDoesNotAcquireTheRecoveryException(){
  jdbc.update("update subscription_action_capabilities set consumed_at=? where id=?",java.sql.Timestamp.from(at),grant.id());
  assertThat(recoveryProofs.availableForConfirmation(account.id(),subscriptionId,"in_owned",operationId,grant.id())).isFalse();
 }
 @Test void crashAfterClaimBeforeReceiptRemainsNonAuthorizing()throws Exception{
  var id=select();tx.required(()->{var ready=attempts.attempt(id);attempts.save(ready.claim("1".repeat(64),"2".repeat(64),"3".repeat(64)),ready.version(),Instant.now());return null;});
  read(path()+"/confirmation-attempts/"+id).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DISPATCHING")).andExpect(jsonPath("$.retry_allowed").value(false));
  write(path()+"/confirmation-attempts/"+id+"/retry","retry","{}").andExpect(status().isConflict());verify(confirmations,never()).confirm(any(),any(),any(),any());
 }
 @Test void unknownAndStrictBodiesCannotAcquireSuccessor()throws Exception{
  var id=select();when(confirmations.confirm(any(),any(),any(),any())).thenThrow(new IllegalStateException("lost response"));confirm(id,"pay");write(path()+"/confirmation-attempts/"+id+"/retry","retry","{}").andExpect(status().isConflict());
  write(path()+"/confirmation-attempts/"+id+"/retry","invalid","{\"force\":true}").andExpect(status().isBadRequest());read(path()+"/confirmation-attempts/"+id).andExpect(jsonPath("$.state").value("UNKNOWN")).andExpect(jsonPath("$.retry_allowed").value(false));
 }
}
