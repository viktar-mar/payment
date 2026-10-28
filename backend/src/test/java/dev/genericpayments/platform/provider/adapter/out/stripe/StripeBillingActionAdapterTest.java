package dev.genericpayments.platform.provider.adapter.out.stripe;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.*;import java.util.*;import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;import org.junit.jupiter.params.provider.ValueSource;
class StripeBillingActionAdapterTest {
 final StripeSubscriptionCreateTest fixture=new StripeSubscriptionCreateTest();
 final SubscriptionActionStore store=mock(SubscriptionActionStore.class);
 SubscriptionClientGrant row;StripeBillingActionAdapter adapter;Instant now=Instant.now();
 final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}};
 final SubscriptionTransactionPort tx=new SubscriptionTransactionPort(){public <T>T required(Supplier<T> s){return s.get();}public <T>T requiresNew(Supplier<T>s){return s.get();}public boolean active(){return false;}};
 @BeforeEach void setup()throws Exception{
  fixture.setup();fixture.create();fixture.local=StripeSubscriptionCreateTest.copy(fixture.local,"sub_owned",dev.genericpayments.platform.subscriptions.domain.Subscription.Status.INCOMPLETE);
  when(fixture.operations.lock(fixture.operation.id())).thenAnswer(x->fixture.operation);
  when(store.current(fixture.account.id(),fixture.operation.id())).thenAnswer(x->Optional.ofNullable(row));
  when(store.billing(anyString())).thenAnswer(x->Optional.ofNullable(row).filter(r->r.tokenDigest().equals(x.getArgument(0))));
  doAnswer(x->{row=x.getArgument(0);return null;}).when(store).reserveBilling(any());
  var g=fixture.graph;g.subscription.addProperty("status","incomplete");g.invoice.addProperty("billing_reason","subscription_create");g.invoice.addProperty("status","open");g.invoice.addProperty("amount_paid",0);g.invoice.addProperty("amount_remaining",1000);
  g.allocation.addProperty("status","open");g.allocation.addProperty("is_default",true);g.allocation.addProperty("amount_paid",0);
  g.intent.addProperty("status","requires_payment_method");g.intent.addProperty("amount_received",0);g.intent.remove("latest_charge");g.intent.addProperty("client_secret","pi_owned_secret_canary");
  adapter=adapter(true);
 }
 StripeBillingActionAdapter adapter(boolean enabled){
  var credentials=new StripeCredentialResolver(new StripeSandboxProperties(true,"sk_test_"+"A".repeat(32),"pk_test_"+"B".repeat(32),"whsec_"+"C".repeat(32)));
  var factory=new StripeClientFactory(){@Override public com.stripe.StripeClient create(StripeCredentials ignored){return fixture.graph.http.client();}};
  dev.genericpayments.platform.shared.application.port.out.DigestPort digest=StripeBillingGrantTokens::hash;
  var ownership=new StripeBillingOwnership(credentials,fixture.steps,fixture.bindings,digest,fixture.accounts,fixture.sites,fixture.customerStore,fixture.subscriptions,fixture.operations,fixture.catalog,fixture.mappings);
  return new StripeBillingActionAdapter(ownership,new StripeBillingGrantTokens(enabled,"v1",Map.of("v1",Base64.getEncoder().encodeToString(new byte[32]))),credentials,factory,new StripeAccountVerificationAdapter(credentials,factory),store,fixture.operations,tx,clock);
 }
 @AfterEach void close(){fixture.close();}
 void graph(){fixture.identity();var g=fixture.graph;g.head();g.lines();g.payments();g.http.respond("GET","/v1/payment_intents/pi_owned",g.intent.toString());if(g.intent.has("latest_charge"))g.http.respond("GET","/v1/charges/ch_owned",g.charge.toString());g.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false,g.allocation));}
 @Test void repeatsOpaqueGrantAcrossNodesAndReleasesOnlyVerifiedOriginalSecret(){
  graph();var first=adapter.nextAction(fixture.lookup());assertThat(first).isNotNull();assertThat(first.secret()).matches("bga1_[A-Za-z0-9_-]{43}").doesNotContain("pi_owned");
  graph();assertThat(adapter(true).nextAction(fixture.lookup())).isEqualTo(first);
  graph();var session=adapter.retrieve(fixture.lookup(),row,first.secret());assertThat(session.provider()).isEqualTo("stripe");assertThat(session.flow()).isEqualTo(SubscriptionClientSession.Flow.PAYMENT);assertThat(session.clientSecret()).isEqualTo("pi_owned_secret_canary");assertThat(session.actionToken()).isEqualTo(first.secret());assertThat(session.toString()).doesNotContain(first.secret(),session.clientSecret());assertThat(row.toString()).doesNotContain(first.secret(),session.clientSecret());
  assertThat(fixture.graph.http.requestCount("POST","/v1/subscriptions")).isEqualTo(1);assertThat(fixture.graph.http.calls().stream().filter(c->c.method().equals("POST"))).hasSize(1);
 }
 @Test void nullKeyCannotCreateOrRotateAndConsumedNeverReopens(){
  var nullKey=new Lookup(fixture.account,fixture.local.id(),"sub_owned",null);assertThat(adapter.nextAction(nullKey)).isNull();assertThat(row).isNull();
  graph();var first=adapter.nextAction(fixture.lookup());assertThat(first).isNotNull();now=now.plusSeconds(601);assertThat(adapter.nextAction(nullKey)).isNull();
  graph();var second=adapter.nextAction(fixture.lookup());assertThat(second.secret()).isNotEqualTo(first.secret());
  var r=row;row=new SubscriptionClientGrant(r.id(),r.site(),r.customer(),r.account(),r.subscription(),r.operation(),r.keyVersion(),r.binding(),r.tokenDigest(),r.issuedAt(),r.expiresAt(),now,null,null,null,null);
  now=now.plusSeconds(601);assertThat(adapter.nextAction(fixture.lookup())).isNull();
 }
 @ParameterizedTest @ValueSource(strings={"invoiceCustomer","invoiceParent","initialReason","amount","allocationCustomerIntent","allocationType","allocationPaid","allocationDefault","intentCustomer","intentAmount","intentReceived","intentStatus","intentLive","intentMethods","secret","linePeriod","trial"})
 void refusesUnprovenOrUnsupportedActionWithoutGrant(String change){
  var g=fixture.graph;switch(change){case "invoiceCustomer"->g.invoice.addProperty("customer","cus_other");case "invoiceParent"->g.invoice.getAsJsonObject("parent").getAsJsonObject("subscription_details").addProperty("subscription","sub_other");case "initialReason"->g.invoice.addProperty("billing_reason","subscription_cycle");case "amount"->g.invoice.addProperty("amount_due",500);case "allocationCustomerIntent"->g.allocation.getAsJsonObject("payment").addProperty("payment_intent","pi_other");case "allocationType"->g.allocation.getAsJsonObject("payment").addProperty("type","payment_record");case "allocationPaid"->g.allocation.addProperty("amount_paid",1);case "allocationDefault"->g.allocation.addProperty("is_default",false);case "intentCustomer"->g.intent.addProperty("customer","cus_other");case "intentAmount"->g.intent.addProperty("amount",500);case "intentReceived"->g.intent.addProperty("amount_received",1000);case "intentStatus"->g.intent.addProperty("status","processing");case "intentLive"->g.intent.addProperty("livemode",true);case "intentMethods"->g.intent.add("payment_method_types",com.google.gson.JsonParser.parseString("[\"sepa_debit\"]"));case "secret"->g.intent.addProperty("client_secret","pi_foreign_secret_canary");case "linePeriod"->g.line.getAsJsonObject("period").addProperty("end",1802678401L);case "trial"->g.subscription.addProperty("trial_end",1802678400L);}
  graph();assertThatThrownBy(()->adapter.nextAction(fixture.lookup())).isInstanceOf(PaymentApplicationException.class);assertThat(row).isNull();
 }
 @Test void disabledActionsRequireNoGraphAndDirectConfirmNeedsDurableReceipt(){assertThat(adapter(false).nextAction(fixture.lookup())).isNull();assertThatThrownBy(()->adapter.confirmAction(fixture.command(),"bga1_"+"a".repeat(43))).isInstanceOf(PaymentApplicationException.class);assertThat(row).isNull();}
}
