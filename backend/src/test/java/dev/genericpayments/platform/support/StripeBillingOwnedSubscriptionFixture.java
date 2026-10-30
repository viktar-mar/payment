package dev.genericpayments.platform.support;

import com.google.gson.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.util.*;

/** Pure values plus the audited in-memory official SDK transport. No external resource initialization. */
public final class StripeBillingOwnedSubscriptionFixture {
    public final StripeBillingInertSdkFixture f=new StripeBillingInertSdkFixture();
    public final Instant utc=Instant.parse("2026-10-29T12:00:00Z");
    public final SubscriptionPriceTerms terms=new SubscriptionPriceTerms(new PriceId(new UUID(0,7)),"monthly",1,new ProductId(new UUID(0,8)),Money.of(1000,"EUR"),BillingInterval.MONTH,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
    public final UUID subscriptionId=new UUID(0,12),operationId=new UUID(0,13),bindingId=new UUID(0,10),customerStep=new UUID(0,11);
    public final OwnedClockSubscription intent;
    public Subscription local;
    public SubscriptionOperation operation;
    public final JsonObject customer,remote,invoice,line,allocation,pi,charge;
    public StripeBillingOwnedSubscriptionFixture(){
        f.admit();f.ready=new ReadyClock(f.owner,new UUID(0,6),"clock_owned",1700000000,1800000000,1801000000,utc.minusSeconds(50),"b".repeat(64));
        String key="subscription:"+f.account.id().value()+":"+operationId+":CREATE";
        intent=new OwnedClockSubscription(new UUID(0,9),f.ready,bindingId,customerStep,"cus_owned",subscriptionId,operationId,key,"v1.paid."+"d".repeat(64),0,1,terms,"price_owned","prod_owned","c".repeat(64),utc);
        local=new Subscription(subscriptionId,f.site,f.customer,terms.productId(),f.account.id(),terms,null,Subscription.Status.INCOMPLETE,null,null,null,null,null,null,null,null,0,utc,utc,null);
        operation=new SubscriptionOperation(operationId,f.site,f.customer,subscriptionId,SubscriptionOperation.Kind.CREATE,SubscriptionOperation.Status.PROCESSING,"authority","digest",intent.requestFingerprint(),key,0,1,null,null,utc,utc,1);
        customer=json(f.customerJson("clock_owned"));
        remote=json("""
          {"id":"sub_owned","object":"subscription","livemode":false,"created":42,"test_clock":"clock_owned","customer":"cus_owned","status":"incomplete","currency":"eur",
           "collection_method":"charge_automatically","billing_mode":{"type":"classic"},"payment_settings":{"payment_method_types":["card"],"save_default_payment_method":"on_subscription"},
           "cancel_at_period_end":false,"cancel_at":null,"schedule":null,"pending_update":null,"trial_start":null,"trial_end":null,
           "items":{"object":"list","has_more":false,"data":[{"id":"si_owned","object":"subscription_item","subscription":"sub_owned","quantity":1,
             "current_period_start":1699999900,"current_period_end":1702678400,"price":{"id":"price_owned","object":"price","livemode":false,"product":"prod_owned",
             "currency":"eur","unit_amount":1000,"tax_behavior":"inclusive","type":"recurring","recurring":{"interval":"month","interval_count":1,"usage_type":"licensed"}}}]}}
          """);
        var metadata=new JsonObject();StripeBillingOwnership.metadata(f.account,local,operation).forEach(metadata::addProperty);remote.add("metadata",metadata);
        price().add("metadata",json("{\"local_price_id\":\""+terms.priceId().value()+"\",\"local_price_version\":\"1\"}"));
        invoice=json("""
          {"id":"in_owned","object":"invoice","livemode":false,"created":1600000007,"test_clock":"clock_owned","customer":"cus_owned","currency":"eur","status":"open","billing_reason":"subscription_create",
           "collection_method":"charge_automatically","amount_due":1000,"amount_paid":0,"amount_remaining":1000,"amount_paid_off_stripe":0,"amount_overpaid":0,
           "total":1000,"subtotal":1000,"starting_balance":0,"ending_balance":0,"pre_payment_credit_notes_amount":0,"post_payment_credit_notes_amount":0,
           "discounts":[],"total_discount_amounts":[],"total_pretax_credit_amounts":[],"total_taxes":[],
           "parent":{"type":"subscription_details","subscription_details":{"subscription":"sub_owned"}}}
          """);
        line=json("""
          {"id":"il_owned","object":"line_item","livemode":false,"invoice":"in_owned","subscription":"sub_owned","currency":"eur","quantity":1,"amount":1000,
           "discount_amounts":[],"pretax_credit_amounts":[],"period":{"start":1699999900,"end":1702678400},
           "parent":{"type":"subscription_item_details","subscription_item_details":{"subscription":"sub_owned","subscription_item":"si_owned","proration":false}},
           "pricing":{"type":"price_details","price_details":{"price":"price_owned","product":"prod_owned"}}}
          """);
        allocation=json("""
          {"id":"inpay_owned","object":"invoice_payment","livemode":false,"invoice":"in_owned","currency":"eur","status":"paid","amount_paid":1000,"amount_requested":1000,
           "payment":{"type":"payment_intent","payment_intent":"pi_owned"},"status_transitions":{"paid_at":1700000011}}
          """);
        pi=json("""
          {"id":"pi_owned","object":"payment_intent","livemode":false,"created":1500000000,"customer":"cus_owned","currency":"eur","amount":1000,"amount_received":1000,
           "status":"succeeded","capture_method":"automatic","payment_method_types":["card"],"latest_charge":"ch_owned"}
          """);
        charge=json("""
          {"id":"ch_owned","object":"charge","livemode":false,"created":1500000001,"customer":"cus_owned","payment_intent":"pi_owned","currency":"eur","amount":1000,"amount_captured":1000,
           "paid":true,"captured":true,"status":"succeeded","payment_method_details":{"type":"card"},"amount_refunded":0,"refunded":false}
          """);
    }
    /** Mutate only the duplicate flat representation; the authoritative nested graph is unchanged. */
    public void flatSubscription(String representation){
        switch(representation){
            case "exact"->line.addProperty("subscription","sub_owned");
            case "omitted"->line.remove("subscription");
            case "null"->line.add("subscription",JsonNull.INSTANCE);
            default->throw new IllegalArgumentException("unknown flat representation");
        }
    }
    public CustomerBinding binding(){return new CustomerBinding(bindingId,f.account.id(),f.site,f.customer,"cus_owned",customerStep,0);}
    public Command command(){return new Command(f.account,operation,terms,null,null,null,null,true,false);}
    public Lookup lookup(){return new Lookup(f.account,subscriptionId,"sub_owned",intent.operationKey());}
    public StripeBillingSnapshotMapper.Ownership ordinary(){return new StripeBillingSnapshotMapper.Ownership(f.account,StripeBillingOwnership.bound(local,"sub_owned"),binding(),List.of(new StripeBillingSnapshotMapper.PublishedPrice(terms,"price_owned","prod_owned")));}
    public OwnedClockSubscriptionReceipt receipt(){return new OwnedClockSubscriptionReceipt(intent,new UUID(0,15),"sub_owned",42,"si_owned",1699999900,1702678400,utc.plusSeconds(1));}
    public void graph(){f.http.reply(customer.toString());f.http.reply(remote.toString());f.http.reply(page(invoice));f.http.reply(page(line));f.http.reply(page());}
    public void paidGraph(){invoice.addProperty("status","paid");invoice.addProperty("amount_paid",1000);invoice.addProperty("amount_remaining",0);remote.addProperty("status","active");f.http.reply(customer.toString());f.http.reply(remote.toString());f.http.reply(page(invoice));f.http.reply(page(line));f.http.reply(page(allocation));f.http.reply(pi.toString());f.http.reply(charge.toString());f.http.reply(page(allocation));}
    public JsonObject item(){return remote.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject();}
    public JsonObject price(){return item().getAsJsonObject("price");}
    public static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    public static String page(JsonObject... values){var page=json("{\"object\":\"list\",\"has_more\":false,\"data\":[]}");for(var value:values)page.getAsJsonArray("data").add(value);return page.toString();}
}
