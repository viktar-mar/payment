package dev.genericpayments.platform.provider.adapter.out.stripe;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
class StripeBillingConfirmationAdapterTest {
 final StripeBillingActionAdapterTest fixture=new StripeBillingActionAdapterTest();StripeBillingConfirmationAdapter adapter;SubscriptionClientGrant root;
 @BeforeEach void setup()throws Exception{
  fixture.setup();fixture.graph();fixture.adapter.nextAction(fixture.fixture.lookup());root=fixture.row;
  var f=fixture.fixture;var credentials=new StripeCredentialResolver(new StripeSandboxProperties(true,"sk_test_"+"A".repeat(32),"pk_test_"+"B".repeat(32),"whsec_"+"C".repeat(32)));
  var factory=new StripeClientFactory(){@Override public com.stripe.StripeClient create(StripeCredentials ignored){return f.graph.http.client();}};
  var ownership=new StripeBillingOwnership(credentials,f.steps,f.bindings,StripeBillingGrantTokens::hash,f.accounts,f.sites,f.customerStore,f.subscriptions,f.operations,f.catalog,f.mappings);
  adapter=new StripeBillingConfirmationAdapter(ownership,new StripeBillingGrantTokens(true,"v1",Map.of("v1",Base64.getEncoder().encodeToString(new byte[32]))),credentials,factory,new StripeAccountVerificationAdapter(credentials,factory),fixture.clock);
 }
 @AfterEach void close(){fixture.close();}
 SubscriptionConfirmationAttempt claimed(UUID predecessor){return SubscriptionConfirmationAttempt.ready(UUID.randomUUID(),root.id(),predecessor,"v1",fixture.now,root.expiresAt()).claim("a".repeat(64),StripeBillingGrantTokens.hash("ctoken_original"),"b".repeat(64));}
 void failedGraph(){var g=fixture.fixture.graph;g.intent.addProperty("latest_charge","ch_owned");g.charge.addProperty("amount_captured",0);g.charge.addProperty("paid",false);g.charge.addProperty("captured",false);g.charge.addProperty("status","failed");g.charge.addProperty("failure_code","card_declined");g.charge.add("outcome",com.google.gson.JsonParser.parseString("{\"type\":\"issuer_declined\",\"reason\":\"generic_decline\"}"));}
 String error(){var g=fixture.fixture.graph;var last=com.google.gson.JsonParser.parseString("{\"type\":\"card_error\",\"code\":\"card_declined\",\"decline_code\":\"generic_decline\",\"charge\":\"ch_owned\"}").getAsJsonObject();g.intent.add("last_payment_error",last.deepCopy());var outer=last.deepCopy();outer.add("payment_intent",g.intent.deepCopy());var body=new com.google.gson.JsonObject();body.add("error",outer);return body.toString();}
 void eligibleGraph(boolean proof){fixture.graph();var g=fixture.fixture.graph;g.http.respond("GET","/v1/payment_intents/pi_owned",g.intent.toString());if(proof)g.http.respond("GET","/v1/charges/ch_owned",g.charge.toString());}
 void originalDecline(String error){fixture.fixture.identity();fixture.fixture.graph.http.respondError("POST","/v1/payment_intents/pi_owned/confirm",402,error,Map.of("Request-Id","req_original"));}
 @Test void original402ProofMakesSameIntentSuccessorUsableWithOnePostPerAttempt(){
  failedGraph();String error=error();var first=claimed(null);originalDecline(error);eligibleGraph(true);
  var declined=adapter.confirm(fixture.fixture.lookup(),root,first,"ctoken_original");assertThat(declined.state()).isEqualTo(SubscriptionConfirmationAttempt.State.DECLINED);assertThat(declined.receipt().charge()).isEqualTo("ch_owned");
  eligibleGraph(true);assertThat(adapter.read(fixture.fixture.lookup(),root,declined.receipt()).clientSecret()).isEqualTo("pi_owned_secret_canary");
  var next=claimed(first.id());fixture.fixture.identity();var paid=fixture.fixture.graph.intent.deepCopy();paid.addProperty("status","succeeded");fixture.fixture.graph.http.respond("POST","/v1/payment_intents/pi_owned/confirm",paid.toString());
  assertThat(adapter.confirm(fixture.fixture.lookup(),root,next,"ctoken_original").state()).isEqualTo(SubscriptionConfirmationAttempt.State.OBSERVE);
  var posts=fixture.fixture.graph.http.calls().stream().filter(c->c.path().endsWith("/confirm")).toList();assertThat(posts).hasSize(2);assertThat(posts).allSatisfy(c->{assertThat(c.path()).isEqualTo("/v1/payment_intents/pi_owned/confirm");assertThat(c.form()).containsExactlyInAnyOrderEntriesOf(Map.of("confirmation_token","ctoken_original","use_stripe_sdk","true"));});assertThat(posts.getFirst().header("Idempotency-Key")).isEqualTo(first.providerKey());assertThat(posts.getLast().header("Idempotency-Key")).isEqualTo(next.providerKey());
 }
 @ParameterizedTest @ValueSource(strings={"charge","intent","customer","currency","amount","live","cash","processing","canceled","challenge","invoice"})
 void freshGraphCannotReplaceAttributionOrOverrideCurrentIneligibility(String drift){
  failedGraph();String error=error();var g=fixture.fixture.graph;
  switch(drift){case "charge"->g.intent.addProperty("latest_charge","ch_other");case "intent"->g.charge.addProperty("payment_intent","pi_other");case "customer"->g.charge.addProperty("customer","cus_other");case "currency"->g.charge.addProperty("currency","usd");case "amount"->g.charge.addProperty("amount",900);case "live"->g.charge.addProperty("livemode",true);case "cash"->g.charge.addProperty("paid",true);case "processing"->g.intent.addProperty("status","processing");case "canceled"->g.intent.addProperty("status","canceled");case "challenge"->g.intent.addProperty("status","requires_action");case "invoice"->g.invoice.addProperty("id","in_other");}
  originalDecline(error);eligibleGraph(true);var result=adapter.confirm(fixture.fixture.lookup(),root,claimed(null),"ctoken_original");assertThat(result.state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(result.receipt()).isNull();assertThat(g.http.requestCount("POST","/v1/payment_intents/pi_owned/confirm")).isEqualTo(1);
 }
 @ParameterizedTest @ValueSource(strings={"amount","currency","capture_method","client_secret"})
 void secondIntentReadMustMatchTheValidatedInvoiceGraph(String field){
  failedGraph();String error=error();originalDecline(error);fixture.graph();var g=fixture.fixture.graph;var second=g.intent.deepCopy();
  if(field.equals("amount"))second.addProperty(field,900);else second.addProperty(field,switch(field){case "currency"->"usd";case "capture_method"->"manual";default->"pi_owned_secret_other";});
  g.http.respond("GET","/v1/payment_intents/pi_owned",second.toString());g.http.respond("GET","/v1/charges/ch_owned",g.charge.toString());
  assertThat(adapter.confirm(fixture.fixture.lookup(),root,claimed(null),"ctoken_original").state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);
 }
 @Test void networkUnknownCannotMineLaterFailedGraphForProof(){failedGraph();fixture.fixture.identity();fixture.fixture.graph.http.disconnectNext("POST","/v1/payment_intents/pi_owned/confirm");var result=adapter.confirm(fixture.fixture.lookup(),root,claimed(null),"ctoken_original");assertThat(result.state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(result.receipt()).isNull();assertThat(fixture.fixture.graph.http.requestCount("POST","/v1/payment_intents/pi_owned/confirm")).isEqualTo(1);}
}
