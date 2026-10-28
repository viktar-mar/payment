package dev.genericpayments.platform.subscriptions.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.jayway.jsonpath.JsonPath;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingGrantTokens;
import dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase;
import dev.genericpayments.platform.customers.domain.*;
import java.time.*;import java.util.*;import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;

/** Hosted PostgreSQL contract suite. Provider graph verification has separate SDK/HTTP tests. */
@Import(StripeSubscriptionClientSessionIntegrationTest.Ports.class)
class StripeSubscriptionClientSessionIntegrationTest extends SubscriptionApiFixture {
 @TestConfiguration(proxyBeanMethods=false) static class Ports {
  @Bean @Primary SubscriptionClientSessionPort clientSessionFixture(){return mock(SubscriptionClientSessionPort.class);}
  @Bean @Primary SubscriptionProviderRegistry subscriptionRegistryFixture(){return mock(SubscriptionProviderRegistry.class);}
 }
 @Autowired SubscriptionClientSessionPort clients;
 @Autowired SubscriptionActionStore actions;
 @Autowired ProviderOperationStore steps;
 @Autowired StripeBillingBindingPort bindings;
 @Autowired AnonymizeCustomerUseCase anonymizer;
 SubscriptionProviderPort provider;SubscriptionClientGrant grant;UUID subscriptionId,operationId;
 String raw="bga1_"+"C".repeat(43),secret="pi_owned_secret_memory_canary";Instant now;
 @BeforeEach void billingFixture(){
  reset(clients,providers);provider=mock(SubscriptionProviderPort.class);when(providers.forAccount(any())).thenReturn(provider);
  now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);TestClock.set(now);
  token=sessions.issue(site,customer.id(),"https://facts.test").rawToken();actor=new SubscriptionActor.Customer(sessions.resolveRequired(token,"https://facts.test"));
  jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_owned',credentials_ref='stripe-sandbox-default' where id=?",account.id().value());account=accounts.findById(account.id()).orElseThrow();
  var reserved=commands.reserveCreate(command("original-create"));subscriptionId=reserved.subscription().id();operationId=reserved.operation().id();
  var customerStep=ack(ProviderOperation.Kind.CUSTOMER_CREATE,customer.id().value(),"customer-step","cus_owned");
  bindings.bindCustomer(account.id(),site,customer.id(),"cus_owned",customerStep.id());
  var step=ack(ProviderOperation.Kind.SUBSCRIPTION_CREATE,operationId,reserved.operation().providerOperationKey(),"sub_owned");
  commands.recordReply(operationId,new Reply(Outcome.ACCEPTED,"sub_owned",null));
  grant=new SubscriptionClientGrant(UUID.randomUUID(),site,customer.id(),account.id(),subscriptionId,operationId,"v1",new SubscriptionClientGrant.Binding(step.id(),reserved.operation().providerOperationKey(),"a".repeat(64),"sub_owned","cus_owned","in_owned","inpay_owned","pi_owned"),StripeBillingGrantTokens.hash(raw),now,now.plusSeconds(600),null,null,null,null,null);
  tx.required(()->{operations.lock(operationId);actions.reserveBilling(grant);return null;});
  when(provider.nextAction(any())).thenReturn(new NextAction(ActionKind.PROVIDER_CLIENT,raw,grant.expiresAt()));
  when(provider.confirmAction(any(),eq(raw))).thenAnswer(x->{assertThat(tx.required(()->actions.billing(grant.tokenDigest()).orElseThrow().consumedAt())).isNotNull();return new Reply(Outcome.ACCEPTED,"sub_owned",null);});
  when(clients.retrieve(any(),any(),eq(raw))).thenReturn(new SubscriptionClientSession("stripe",SubscriptionClientSession.Flow.PAYMENT,"pk_test_fixture",secret,raw,grant.expiresAt()));
 }
 ProviderOperation ack(ProviderOperation.Kind kind,UUID resource,String key,String reference){var p=steps.reserve(account,kind,resource,key,"b".repeat(64));var owner=UUID.randomUUID().toString();var claimed=steps.claim(p.id(),owner,now,Duration.ofSeconds(60)).orElseThrow();return steps.acknowledge(p.id(),owner,claimed.version(),reference);}
 String path(){return BASE+"/"+subscriptionId+"/operations/"+operationId;}
 String body(){return "{\"action_token\":\""+raw+"\"}";}
 void issue()throws Exception{write(path()+"/client-session","client",body()).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.client_secret").value(secret));}
 String confirm(String key)throws Exception{return write(path()+"/action-confirmations",key,body()).andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();}
 @Test void processingOriginalActionHandoffAndSinglePermission()throws Exception{
  read(path()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSING")).andExpect(jsonPath("$.action_available").value(false));
  read(path()+"/action").andExpect(status().isOk()).andExpect(jsonPath("$.secret").value(raw));issue();
  assertThat(JsonPath.<Boolean>read(confirm("pay"),"$.browser_confirm_allowed")).isTrue();
  assertThat(JsonPath.<Boolean>read(confirm("pay"),"$.browser_confirm_allowed")).isFalse();verify(provider,times(1)).confirmAction(any(),eq(raw));
  assertThat(sub(subscriptionId).paidThrough()).isNull();assertThat(count("payments")).isZero();
  assertThat(jdbc.queryForObject("select row_to_json(a)::text from subscription_action_capabilities a where id=?",String.class,grant.id())).doesNotContain(raw,secret);
 }
 @Test void lostAckConsumesWithoutEverReauthorizing()throws Exception{issue();when(provider.confirmAction(any(),eq(raw))).thenThrow(new IllegalStateException("lost ack"));assertThat(JsonPath.<Boolean>read(confirm("pay"),"$.browser_confirm_allowed")).isFalse();assertThat(JsonPath.<Boolean>read(confirm("pay"),"$.browser_confirm_allowed")).isFalse();verify(provider,times(1)).confirmAction(any(),eq(raw));}
 @Test void parallelPayHasOneWinningResponse()throws Exception{issue();try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var a=pool.submit(()->confirm("parallel"));var b=pool.submit(()->confirm("parallel"));assertThat(List.of(JsonPath.<Boolean>read(a.get(10,TimeUnit.SECONDS),"$.browser_confirm_allowed"),JsonPath.<Boolean>read(b.get(10,TimeUnit.SECONDS),"$.browser_confirm_allowed"))).containsExactlyInAnyOrder(true,false);}verify(provider,times(1)).confirmAction(any(),eq(raw));}
 @Test void differentConfirmationKeyAndConsumedClientSessionFailClosed()throws Exception{issue();confirm("pay");write(path()+"/action-confirmations","again",body()).andExpect(status().isConflict());write(path()+"/client-session","again",body()).andExpect(status().isConflict());}
 @Test void differentCustomerSessionCannotTakeBoundGrant()throws Exception{issue();token=sessions.issue(site,customer.id(),"https://facts.test").rawToken();write(path()+"/client-session","new-session",body()).andExpect(status().isConflict());}
 @Test void logoutDuringProviderReadRefusesSecret()throws Exception{when(clients.retrieve(any(),any(),eq(raw))).thenAnswer(x->{sessions.logout(((SubscriptionActor.Customer)actor).session());return new SubscriptionClientSession("stripe",SubscriptionClientSession.Flow.PAYMENT,"pk_test_fixture",secret,raw,grant.expiresAt());});var response=write(path()+"/client-session","client",body()).andExpect(status().isUnauthorized()).andReturn().getResponse();assertThat(response.getContentAsString()).doesNotContain(secret);assertThat(tx.required(()->actions.billing(grant.tokenDigest()).orElseThrow().sessionId())).isNull();}
 @Test void foreignOriginAndCustomerCannotReadClientMaterial()throws Exception{
  admin.mvc.perform(post(path()+"/client-session").header("Authorization","Bearer "+token).header("Origin","https://foreign.test").header("Idempotency-Key","client").contentType("application/json").content(body())).andExpect(status().isUnauthorized());
  var other=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,now));token=sessions.issue(site,other.id(),"https://facts.test").rawToken();write(path()+"/client-session","client",body()).andExpect(status().isNotFound());verifyNoInteractions(clients);
 }
 @Test void paymentProofAndConsentCannotBeRewritten(){for(String assignment:List.of("recurring_consent=false","billing_intent_reference=null","billing_step_id='00000000-0000-0000-0000-000000000001'"))assertThatThrownBy(()->jdbc.update("update subscription_action_capabilities set "+assignment+" where id=?",grant.id())).isInstanceOf(DataIntegrityViolationException.class);}
 @Test void terminalAnonymizationPreservesNonAuthorizingReceipt()throws Exception{
  issue();confirm("pay");var s=sub(subscriptionId);subscriptions.save(s.cancel("CONFIRMED",now),s.revision());var o=op(operationId);
  tx.required(()->operations.save(new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),SubscriptionOperation.Status.SUCCEEDED,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),null,o.createdAt(),now,o.version()+1),o.version()));
  assertThat(anonymizer.anonymize(new AnonymizeCustomerUseCase.Command(site,customer.id(),customer.id().value(),"erase","ADMIN","00000000-0000-0000-0000-000000000001","erase-request","127.0.0.1")).status()).isEqualTo(CustomerStatus.ANONYMIZED);
  assertThat(tx.required(()->actions.billing(grant.tokenDigest()).orElseThrow().consumedAt())).isNotNull();
  assertThatThrownBy(()->jdbc.update("update subscription_action_capabilities set consumed_at=null where id=?",grant.id())).isInstanceOf(DataIntegrityViolationException.class);
  // Expired/revoked sessions can still be removed independently of financial receipt retention.
  jdbc.update("delete from customer_sessions where customer_id=?",customer.id().value());
  assertThat(count("subscription_action_capabilities")).isEqualTo(1);
 }
 @Test void openApiDocumentsOnlyCustomerSessionRouteAndActualStatus()throws Exception{
  var json=admin.mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  var paths=JsonPath.<Map<String,Object>>read(json,"$.paths");var route="/api/v1/customer/subscriptions/{id}/operations/{operationId}/client-session";
  assertThat(paths).containsKey(route);
  assertThat(paths.keySet()).filteredOn(p->p.contains("/subscriptions/")&&p.endsWith("/client-session")).containsExactly(route);
  var response=JsonPath.<Map<String,Object>>read(json,"$.paths['"+route+"'].post.responses");assertThat(response).containsKey("200").doesNotContainKey("202");
 }
}
