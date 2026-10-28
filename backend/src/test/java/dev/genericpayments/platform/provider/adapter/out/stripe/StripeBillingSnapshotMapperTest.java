package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;
import com.google.gson.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingSnapshotMapper;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingSnapshotMapper.*;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StripeBillingSnapshotMapperTest {
    static final Instant START=Instant.ofEpochSecond(1800000000), END=Instant.ofEpochSecond(1802678400);
    final PaymentAccount account=PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),"billing",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_expected","stripe-sandbox-default");
    final Subscription seed=SubscriptionFixtures.active(START,END);
    final Subscription local=new Subscription(seed.id(),seed.siteId(),seed.customerId(),seed.productId(),account.id(),seed.billingPrice(),seed.entitledPrice(),seed.billingStatus(),"sub_owned",seed.currentPeriod(),seed.paidThrough(),null,null,null,null,null,seed.revision(),START,START,null);
    final Ownership owner=new Ownership(account,local,new CustomerBinding(UUID.randomUUID(),account.id(),local.siteId(),local.customerId(),"cus_owned",UUID.randomUUID(),0),List.of(new PublishedPrice(local.billingPrice(),"price_owned","prod_owned")));
    StripeBillingHttpFixture http;
    JsonObject subscription,invoice,line,allocation,intent,charge,customer;
    @BeforeEach void setup() throws Exception {
        http=new StripeBillingHttpFixture();
        customer=json("{\"id\":\"cus_owned\",\"object\":\"customer\",\"livemode\":false}");
        subscription=json("""
            {"id":"sub_owned","object":"subscription","livemode":false,"customer":"cus_owned","status":"active","currency":"eur",
             "collection_method":"charge_automatically","billing_mode":{"type":"classic"},
             "items":{"object":"list","has_more":false,"data":[{"id":"si_owned","object":"subscription_item","subscription":"sub_owned","quantity":1,
               "current_period_start":1800000000,"current_period_end":1802678400,"price":{"id":"price_owned","object":"price","livemode":false,"product":"prod_owned",
                "currency":"eur","unit_amount":1000,"tax_behavior":"inclusive","type":"recurring","recurring":{"interval":"month","interval_count":1,"usage_type":"licensed"}}}]}}
            """);
        price().add("metadata",json("{\"local_price_id\":\""+local.billingPrice().priceId().value()+"\",\"local_price_version\":\"1\"}"));
        invoice=json("""
            {"id":"in_owned","object":"invoice","livemode":false,"customer":"cus_owned","currency":"eur","status":"paid","billing_reason":"subscription_cycle",
             "collection_method":"charge_automatically","amount_due":1000,"amount_paid":1000,"amount_remaining":0,"amount_paid_off_stripe":0,"amount_overpaid":0,
             "total":1000,"subtotal":1000,"starting_balance":0,"ending_balance":0,"pre_payment_credit_notes_amount":0,"post_payment_credit_notes_amount":0,
             "discounts":[],"total_discount_amounts":[],"total_pretax_credit_amounts":[],"total_taxes":[],
             "parent":{"type":"subscription_details","subscription_details":{"subscription":"sub_owned"}}}
            """);
        line=json("""
            {"id":"il_owned","object":"line_item","livemode":false,"invoice":"in_owned","subscription":"sub_owned","currency":"eur","quantity":1,"amount":1000,
             "discount_amounts":[],"pretax_credit_amounts":[],"period":{"start":1800000000,"end":1802678400},
             "parent":{"type":"subscription_item_details","subscription_item_details":{"subscription":"sub_owned","subscription_item":"si_owned","proration":false}},
             "pricing":{"type":"price_details","price_details":{"price":"price_owned","product":"prod_owned"}}}
            """);
        allocation=json("""
            {"id":"inpay_owned","object":"invoice_payment","livemode":false,"invoice":"in_owned","currency":"eur","status":"paid","amount_paid":1000,"amount_requested":1000,
             "payment":{"type":"payment_intent","payment_intent":"pi_owned"},"status_transitions":{"paid_at":1800000001}}
            """);
        intent=json("""
            {"id":"pi_owned","object":"payment_intent","livemode":false,"customer":"cus_owned","currency":"eur","amount":1000,"amount_received":1000,
             "status":"succeeded","capture_method":"automatic","payment_method_types":["card"],"latest_charge":"ch_owned"}
            """);
        charge=json("""
            {"id":"ch_owned","object":"charge","livemode":false,"customer":"cus_owned","payment_intent":"pi_owned","currency":"eur","amount":1000,"amount_captured":1000,
             "paid":true,"captured":true,"status":"succeeded","created":1800000001,"payment_method_details":{"type":"card"},"amount_refunded":0,"refunded":false}
            """);
    }
    @AfterEach void close(){http.close();}
    // Catches settlement from status alone and changing the cash identity from its original PI.
    @Test void mapsOnlyFullCapturedOwnedGrossCash() {
        graph();var result=read();
        assertThat(result.providerRevision()).isZero();assertThat(result.operationObservation()).isNull();assertThat(result.operationKey()).isNull();
        assertThat(result.paymentMethodReady()).isFalse();assertThat(result.recoveryObservations()).isEmpty();
        assertThat(result.invoices()).singleElement().satisfies(i->{assertThat(i.settlement()).isEqualTo(SettlementKind.CASH);assertThat(i.cashObjects()).singleElement().satisfies(c->{assertThat(c.reference()).isEqualTo("pi_owned");assertThat(c.amountMinor()).isEqualTo(1000);});});
        assertThat(http.calls()).allSatisfy(c->assertThat(c.method()).isEqualTo("GET"));
    }
    // Removing any cross-owner/immutable terms check must make the corresponding mutation succeed incorrectly.
    @ParameterizedTest @ValueSource(strings={"customer","subscriptionCustomer","item","itemSubscription","quantity","price","priceVersion","product","currency","period","livemode","status","lineItem","lineSubscription","lineCurrency","linePrice","invoiceCustomer","invoiceSubscription","lineQuantity"})
    void refusesForeignOrMalformedOwnership(String mutation){
        switch(mutation){
            case "customer"->customer.addProperty("id","cus_other");case "subscriptionCustomer"->subscription.addProperty("customer","cus_other");
            case "item"->item().addProperty("id","si_other");case "itemSubscription"->item().addProperty("subscription","sub_other");case "quantity"->item().addProperty("quantity",2);
            case "price"->price().addProperty("id","price_other");case "priceVersion"->price().getAsJsonObject("metadata").addProperty("local_price_version","2");
            case "product"->price().addProperty("product","prod_other");case "currency"->price().addProperty("currency","usd");case "period"->item().remove("current_period_end");
            case "livemode"->subscription.addProperty("livemode",true);case "status"->subscription.addProperty("status","future_status");
            case "lineItem"->line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details").addProperty("subscription_item","si_other");
            case "lineSubscription"->line.addProperty("subscription","sub_other");case "lineCurrency"->line.addProperty("currency","usd");
            case "linePrice"->line.getAsJsonObject("pricing").getAsJsonObject("price_details").addProperty("price","price_other");
            case "invoiceCustomer"->invoice.addProperty("customer","cus_other");case "invoiceSubscription"->invoice.getAsJsonObject("parent").getAsJsonObject("subscription_details").addProperty("subscription","sub_other");
            case "lineQuantity"->line.addProperty("quantity",2);
        }graph();assertThatThrownBy(this::read).isInstanceOf(PaymentApplicationException.class);
    }
    @Test void refusesForeignLocalBindingBeforeHttp(){
        var foreign=new Ownership(account,local,new CustomerBinding(UUID.randomUUID(),new PaymentAccountId(UUID.randomUUID()),local.siteId(),local.customerId(),"cus_owned",UUID.randomUUID(),0),owner.prices());
        assertThatThrownBy(()->StripeBillingSnapshotMapper.retrieve(http.client(),foreign,START)).isInstanceOf(PaymentApplicationException.class);assertThat(http.calls()).isEmpty();
    }
    // Unknown/noncash settlement cannot produce even a partial successful Payment.
    @ParameterizedTest @ValueSource(strings={"offStripe","credit","balance","discount","allocation","uncaptured","intent","amount","chargeCustomer","chargeIntent","nonCard","unknownPayment","zero","proration"})
    void unsupportedSettlementNeverCreatesCash(String mutation){
        switch(mutation){
            case "offStripe"->invoice.addProperty("amount_paid_off_stripe",1000);case "credit"->invoice.addProperty("pre_payment_credit_notes_amount",1000);
            case "balance"->invoice.addProperty("starting_balance",-1000);case "discount"->invoice.add("total_discount_amounts",JsonParser.parseString("[{\"amount\":1}]"));
            case "allocation"->allocation.addProperty("amount_paid",600);case "uncaptured"->charge.addProperty("captured",false);case "intent"->intent.addProperty("status","requires_capture");
            case "amount"->charge.addProperty("amount_captured",600);case "chargeCustomer"->charge.addProperty("customer","cus_other");case "chargeIntent"->charge.addProperty("payment_intent","pi_other");
            case "nonCard"->charge.getAsJsonObject("payment_method_details").addProperty("type","sepa_debit");case "unknownPayment"->allocation.getAsJsonObject("payment").addProperty("type","future_payment");
            case "zero"->invoice.addProperty("amount_due",0);case "proration"->line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details").addProperty("proration",true);
        }graph();try{var s=read();assertThat(s.invoices()).allSatisfy(i->{assertThat(i.settlement()).isEqualTo(SettlementKind.UNSUPPORTED);assertThat(i.cashObjects()).isEmpty();});}catch(PaymentApplicationException safe){assertThat(safe.code()).isEqualTo("PROVIDER_EVIDENCE_MISMATCH");}
    }
    // Later refunds do not change the captured gross amount or consume a second cash identity.
    @ParameterizedTest @ValueSource(longs={300,1000}) void refundDoesNotEraseOriginalCapture(long refunded){charge.addProperty("amount_refunded",refunded);charge.addProperty("refunded",refunded==1000);graph();assertThat(read().invoices().getFirst().cashObjects().getFirst().amountMinor()).isEqualTo(1000);}
    @Test void splitAllocationIsUnsupported(){head();lines();var other=allocation.deepCopy();other.addProperty("id","inpay_second");other.getAsJsonObject("payment").addProperty("payment_intent","pi_second");http.respond("GET","/v1/invoice_payments",page(false,allocation,other));assertUnsupported(read());}
    @Test void reverseAllocationToAnotherInvoiceIsUnsupported(){head();lines();payments();http.respond("GET","/v1/payment_intents/pi_owned",intent.toString());http.respond("GET","/v1/charges/ch_owned",charge.toString());var other=allocation.deepCopy();other.addProperty("id","inpay_other");other.addProperty("invoice","in_other");http.respond("GET","/v1/invoice_payments",page(false,allocation,other));assertUnsupported(read());}
    @Test void duplicateAllocationRefusesWholeGraph(){head();lines();http.respond("GET","/v1/invoice_payments",page(false,allocation,allocation));assertThatThrownBy(this::read).isInstanceOf(PaymentApplicationException.class);}
    @Test void truncatedItemListNeverMeansSingleton(){subscription.getAsJsonObject("items").addProperty("has_more",true);graph();assertThatThrownBy(this::read).isInstanceOf(PaymentApplicationException.class);}
    @Test void truncatedEmptyPageRefuses(){base();http.respond("GET","/v1/invoices",page(true));assertThatThrownBy(this::read).isInstanceOf(PaymentApplicationException.class);}
    @Test void continuesActualInvoiceQueryWithCursorAndLimit(){base();http.respond("GET","/v1/invoices",page(true,invoice));http.respond("GET","/v1/invoices",page(false));tail();var s=read();assertThat(s.invoices()).hasSize(1);var calls=http.calls().stream().filter(c->c.path().equals("/v1/invoices")).toList();assertThat(calls).hasSize(2);assertThat(calls.get(0).query()).containsEntry("subscription","sub_owned").containsEntry("limit","10").doesNotContainKey("starting_after");assertThat(calls.get(1).query()).containsEntry("starting_after","in_owned");var reverse=http.calls().getLast().query();assertThat(reverse).containsEntry("payment[type]","payment_intent").containsEntry("payment[payment_intent]","pi_owned").containsEntry("limit","10").doesNotContainKey("invoice");}
    @Test void boundedPagesRefuseBeforeUnboundedRead(){base();for(int n=0;n<10;n++){var i=invoice.deepCopy();i.addProperty("id","in_page"+n);http.respond("GET","/v1/invoices",page(true,i));}assertThatThrownBy(this::read).isInstanceOf(PaymentApplicationException.class);assertThat(http.requestCount("GET","/v1/invoices")).isEqualTo(10);}
// A captured discounted amount is not the immutable quantity-one recurring Price.
@Test void arbitraryLowerInvoiceCannotBuyFullPricePeriod(){
    for(String field:List.of("amount_due","amount_paid","total","subtotal"))invoice.addProperty(field,900);
    line.addProperty("amount",900);allocation.addProperty("amount_paid",900);allocation.addProperty("amount_requested",900);
    intent.addProperty("amount",900);intent.addProperty("amount_received",900);charge.addProperty("amount",900);charge.addProperty("amount_captured",900);
    graph();assertUnsupported(read());
}
@Test void completeLineAllocationAndReversePagesUseExactFilters(){
    head();http.respond("GET","/v1/invoices/in_owned/lines",page(true,line));http.respond("GET","/v1/invoices/in_owned/lines",page(false));
    http.respond("GET","/v1/invoice_payments",page(true,allocation));http.respond("GET","/v1/invoice_payments",page(false));
    http.respond("GET","/v1/payment_intents/pi_owned",intent.toString());http.respond("GET","/v1/charges/ch_owned",charge.toString());
    http.respond("GET","/v1/invoice_payments",page(true,allocation));http.respond("GET","/v1/invoice_payments",page(false));
    assertThat(read().invoices().getFirst().settlement()).isEqualTo(SettlementKind.CASH);
    var lines=http.calls().stream().filter(c->c.path().endsWith("/lines")).toList();assertThat(lines.get(1).query()).containsEntry("starting_after","il_owned").containsEntry("limit","10");
    var payments=http.calls().stream().filter(c->c.path().equals("/v1/invoice_payments")).toList();
    assertThat(payments.get(0).query()).containsEntry("invoice","in_owned").containsEntry("limit","10").doesNotContainKey("starting_after");
    assertThat(payments.get(1).query()).containsEntry("invoice","in_owned").containsEntry("starting_after","inpay_owned");
    assertThat(payments.get(3).query()).containsEntry("payment[type]","payment_intent").containsEntry("payment[payment_intent]","pi_owned").containsEntry("starting_after","inpay_owned").doesNotContainKey("invoice");
}
@Test void onlyPreviouslyOwnedTrialCanNormalizeZero(){
    zeroTrial();var trial=new Subscription.Period(START,END);
    var ownedTrial=new Subscription(local.id(),local.siteId(),local.customerId(),local.productId(),account.id(),local.billingPrice(),local.entitledPrice(),Subscription.Status.TRIALING,"sub_owned",trial,null,trial,null,null,null,null,1,START,START,null);
    head();lines();http.respond("GET","/v1/invoice_payments",page(false));
    var result=StripeBillingSnapshotMapper.retrieve(http.client(),new Ownership(account,ownedTrial,owner.customer(),owner.prices()),START.plusSeconds(2));
    assertThat(result.invoices().getFirst().settlement()).isEqualTo(SettlementKind.ZERO);assertThat(result.invoices().getFirst().cashObjects()).isEmpty();
}
@Test void firstTrialWithoutOriginalCreateEvidenceRemainsUnsupported(){zeroTrial();head();lines();http.respond("GET","/v1/invoice_payments",page(false));var result=read();assertUnsupported(result);assertThat(result.trial()).isNull();}
void zeroTrial(){subscription.addProperty("status","trialing");subscription.addProperty("trial_start",1800000000);subscription.addProperty("trial_end",1802678400);invoice.addProperty("billing_reason","subscription_create");for(String f:List.of("amount_due","amount_paid","total","subtotal"))invoice.addProperty(f,0);line.addProperty("amount",0);}
@Test void duplicateCashAcrossInvoicesRefusesWholeGraph(){
    var second=invoice.deepCopy();second.addProperty("id","in_second");base();http.respond("GET","/v1/invoices",page(false,invoice,second));tail();
    line.addProperty("id","il_second");line.addProperty("invoice","in_second");allocation.addProperty("id","inpay_second");allocation.addProperty("invoice","in_second");
    http.respond("GET","/v1/invoices/in_second/lines",page(false,line));payments();http.respond("GET","/v1/payment_intents/pi_owned",intent.toString());http.respond("GET","/v1/charges/ch_owned",charge.toString());payments();
    assertThatThrownBy(this::read).isInstanceOf(PaymentApplicationException.class);
}
@Test void nonAdvancingCursorAndMissingHasMoreRefuse(){base();http.respond("GET","/v1/invoices",page(true,invoice));http.respond("GET","/v1/invoices",page(true,invoice));assertThatThrownBy(this::read).isInstanceOf(PaymentApplicationException.class);assertThat(http.requestCount("GET","/v1/invoices")).isEqualTo(2);}
@Test void absentCompletionFlagRefuses(){base();http.respond("GET","/v1/invoices","{\"object\":\"list\",\"data\":[]}");assertThatThrownBy(this::read).isInstanceOf(PaymentApplicationException.class);}
// Nested complete collections are still bounded by the logical read's total request budget.
@Test void fullGraphReadBudgetStopsBeforeNextRequest(){
    base();var invoices=new ArrayList<JsonObject>();for(int n=0;n<20;n++){var i=invoice.deepCopy();i.addProperty("id","in_"+n);invoices.add(i);}
    http.respond("GET","/v1/invoices",page(true,invoices.subList(0,10).toArray(JsonObject[]::new)));http.respond("GET","/v1/invoices",page(false,invoices.subList(10,20).toArray(JsonObject[]::new)));
    for(int n=0;n<20;n++){
        var l=line.deepCopy();l.addProperty("id","il_"+n);l.addProperty("invoice","in_"+n);var a=allocation.deepCopy();a.addProperty("id","inpay_"+n);a.addProperty("invoice","in_"+n);a.getAsJsonObject("payment").addProperty("payment_intent","pi_"+n);
        var pi=intent.deepCopy();pi.addProperty("id","pi_"+n);pi.addProperty("latest_charge","ch_"+n);var ch=charge.deepCopy();ch.addProperty("id","ch_"+n);ch.addProperty("payment_intent","pi_"+n);
        http.respond("GET","/v1/invoices/in_"+n+"/lines",page(false,l));http.respond("GET","/v1/invoice_payments",page(false,a));http.respond("GET","/v1/payment_intents/pi_"+n,pi.toString());http.respond("GET","/v1/charges/ch_"+n,ch.toString());http.respond("GET","/v1/invoice_payments",page(false,a));
    }
    assertThatThrownBy(this::read).isInstanceOf(PaymentApplicationException.class);assertThat(http.calls()).hasSize(100);
}
// Unknown allocation types/states cannot open grace through an OPEN invoice projection.
@ParameterizedTest @ValueSource(strings={"status","type"})
void unknownAllocationCannotOpenGrace(String field){
    invoice.addProperty("status","open");invoice.addProperty("amount_paid",0);invoice.addProperty("amount_remaining",1000);allocation.addProperty("status","open");allocation.addProperty("amount_paid",0);
    if(field.equals("status"))allocation.addProperty("status","future_status");else allocation.getAsJsonObject("payment").addProperty("type","future_payment");
    head();lines();payments();assertUnsupported(read());
}
    void base(){http.respond("GET","/v1/customers/cus_owned",customer.toString());http.respond("GET","/v1/subscriptions/sub_owned",subscription.toString());}
    void head(){base();http.respond("GET","/v1/invoices",page(false,invoice));}
    void lines(){http.respond("GET","/v1/invoices/in_owned/lines",page(false,line));}
    void payments(){http.respond("GET","/v1/invoice_payments",page(false,allocation));}
    void tail(){lines();payments();http.respond("GET","/v1/payment_intents/pi_owned",intent.toString());http.respond("GET","/v1/charges/ch_owned",charge.toString());http.respond("GET","/v1/invoice_payments",page(false,allocation));}
    void graph(){head();tail();}
    Snapshot read(){return StripeBillingSnapshotMapper.retrieve(http.client(),owner,START.plusSeconds(2));}
    static void assertUnsupported(Snapshot s){assertThat(s.invoices().getFirst().settlement()).isEqualTo(SettlementKind.UNSUPPORTED);assertThat(s.invoices().getFirst().cashObjects()).isEmpty();}
    JsonObject item(){return subscription.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject();}
    JsonObject price(){return item().getAsJsonObject("price");}
    static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    static String page(boolean more,JsonObject... objects){var p=json("{\"object\":\"list\",\"has_more\":"+more+",\"data\":[]}");for(var o:objects)p.getAsJsonArray("data").add(o);return p.toString();}
}
