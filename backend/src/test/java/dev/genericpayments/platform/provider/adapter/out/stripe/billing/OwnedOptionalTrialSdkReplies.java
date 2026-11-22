package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.google.gson.JsonObject;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;

/** Owned, synthetic Stripe responses shared by the isolated and database composition tests. */
final class OwnedOptionalTrialSdkReplies {
    static void queue(StripeBillingInertSdkFixture.Transport http,OwnedOptionalTrialCreate.Authority a,String fault){
        var f=new StripeBillingOwnedSubscriptionFixture(TaxMode.INCLUSIVE,2000);
        var clock=StripeBillingOwnedSubscriptionFixture.json(f.f.clockJson());clock.addProperty("name",a.clockName());
        var customer=f.customer.deepCopy();
        customer.add("invoice_settings",new JsonObject());
        customer.getAsJsonObject("metadata").addProperty("account_id",a.account().toString());
        customer.getAsJsonObject("metadata").addProperty("site_id",a.site().toString());
        customer.getAsJsonObject("metadata").addProperty("customer_id",a.customer().toString());
        if(fault.equals("foreign customer"))customer.addProperty("test_clock","clock_foreign");
        var price=f.price().deepCopy();price.addProperty("active",true);
        price.getAsJsonObject("metadata").addProperty("local_price_id",a.price().toString());
        if(fault.equals("different price"))price.addProperty("unit_amount",100);
        var response=f.remote.deepCopy();
        a.metadata().forEach((key,value)->response.getAsJsonObject("metadata").addProperty(key,value));
        response.addProperty("status","trialing");response.addProperty("created",a.frozen());
        response.addProperty("trial_start",a.frozen());response.addProperty("trial_end",a.trialEnd());
        response.addProperty("latest_invoice","in_trial");
        var end=new JsonObject();end.addProperty("missing_payment_method",fault.equals("wrong behavior")?"cancel":"pause");
        var settings=new JsonObject();settings.add("end_behavior",end);response.add("trial_settings",settings);
        response.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject().add("price",price);
        var item=response.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject();
        item.addProperty("current_period_start",a.frozen());item.addProperty("current_period_end",a.trialEnd());
        if(fault.equals("changed trial end"))response.addProperty("trial_end",a.trialEnd()+1);
        http.reply(clock.toString());http.reply(customer.toString());http.reply(price.toString());
        http.reply(fault.equals("attached method")?StripeBillingOwnedSubscriptionFixture.page(new JsonObject()):StripeBillingOwnedSubscriptionFixture.page());
        http.reply(fault.equals("prior subscription")?StripeBillingOwnedSubscriptionFixture.page(new JsonObject()):StripeBillingOwnedSubscriptionFixture.page());
        if(fault.equals("lost ack"))http.disconnect();else http.reply(response.toString());
        var invoice=f.invoice.deepCopy();invoice.addProperty("id","in_trial");invoice.addProperty("status","paid");
        for(String field:java.util.List.of("amount_due","amount_remaining","total","subtotal"))invoice.addProperty(field,0);
        if(fault.equals("paid invoice"))invoice.addProperty("amount_paid",2000);
        http.reply(invoice.toString());
        var line=f.line.deepCopy();line.addProperty("invoice","in_trial");line.addProperty("amount",0);
        line.getAsJsonObject("period").addProperty("start",a.frozen());line.getAsJsonObject("period").addProperty("end",a.trialEnd());
        if(fault.equals("foreign line"))line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details").addProperty("subscription_item","si_other");
        http.reply(StripeBillingOwnedSubscriptionFixture.page(line));
        http.reply(fault.equals("allocation")?"{\"object\":\"list\",\"data\":[{}],\"has_more\":false}":"{\"object\":\"list\",\"data\":[],\"has_more\":false}");
    }
}
