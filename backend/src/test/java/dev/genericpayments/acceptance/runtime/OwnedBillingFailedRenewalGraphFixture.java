package dev.genericpayments.acceptance.runtime;

import com.google.gson.*;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionUseCase;
import dev.genericpayments.platform.support.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import static org.assertj.core.api.Assertions.*;

/** Hosted-only wire builder. Real initial/clock/fact producers create every positive receipt.
 * Never exported by a native runtime. Catalog/policy prerequisites are configured before CREATE. */
abstract class OwnedBillingFailedRenewalGraphFixture extends StripeBillingFirstRenewalCompositionFixture {
    @Autowired protected ApplicationContext application;
    protected long amount=2000;
    protected String currency="EUR",interval="MONTH",taxMode="INCLUSIVE";
    protected JsonObject renewalInvoice,renewalLine;
    protected OwnedBillingFailedRenewalComponents components;
    protected record ReadyPath(OwnedFirstRenewal.Admission upstream,OwnedFirstRenewal.Pending pending,OwnedFirstRenewal.LaterReady finalReady){}

    protected void profile(long amount,String currency,String interval,String taxMode,int grace,String taxCode){
        this.amount=amount;this.currency=currency;this.interval=interval;this.taxMode=taxMode;
        var price=UUID.randomUUID();
        jdbc.update("""
            insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,
             trial_days,trial_payment_method_policy,tax_mode,tax_code,status,published_at,created_at,updated_at)
            select ?,site_id,product_id,'failed-profile',1,'SUBSCRIPTION',?,?,?,0,'OPTIONAL_DURING_TRIAL',?,?,'PUBLISHED',now(),now(),now()
            from prices where id=?
            """,price,amount,currency,interval,taxMode,taxCode,createCommand.priceId().value());
        jdbc.update("insert into provider_price_mappings(payment_account_id,price_id,provider_price_id,created_at) values (?,?,'price_profile',now())",billing.id().value(),price);
        jdbc.update("update site_subscription_policies set grace_days=?,version=version+1 where site_id=?",grace,site.value());
        createCommand=new CreateSubscriptionUseCase.Command(createCommand.context(),new PriceId(price),true);
    }
    @Override protected Command reserve(){
        var command=super.reserve();
        graph.price().addProperty("id","price_profile");graph.price().addProperty("unit_amount",amount);
        graph.price().getAsJsonObject("recurring").addProperty("interval",interval.toLowerCase(Locale.ROOT));
        graph.line.getAsJsonObject("pricing").getAsJsonObject("price_details").addProperty("price","price_profile");
        for(var object:List.of(graph.remote,graph.price(),graph.invoice,graph.line,graph.allocation,graph.pi,graph.charge))object.addProperty("currency",currency.toLowerCase(Locale.ROOT));
        for(var field:List.of("amount_due","amount_remaining","total","subtotal"))graph.invoice.addProperty(field,amount);
        graph.line.addProperty("amount",amount);
        for(var field:List.of("amount_paid","amount_requested"))graph.allocation.addProperty(field,amount);
        for(var field:List.of("amount","amount_received"))graph.pi.addProperty(field,amount);
        for(var field:List.of("amount","amount_captured"))graph.charge.addProperty(field,amount);
        if(interval.equals("YEAR")){
            long end=graph.line.getAsJsonObject("period").get("start").getAsLong()+365*86400L;
            graph.line.getAsJsonObject("period").addProperty("end",end);graph.item().addProperty("current_period_end",end);
        }
        return command;
    }
    @Override protected JsonObject response(String state){var reply=super.response(state);reply.addProperty("amount_received",state.equals("succeeded")?amount:0);return reply;}
    @Override protected Snapshot paidObservation(){
        graph.remote.addProperty("status","active");graph.invoice.addProperty("status","paid");graph.invoice.addProperty("amount_paid",amount);graph.invoice.addProperty("amount_remaining",0);
        graph.allocation.addProperty("status","paid");graph.allocation.addProperty("amount_paid",amount);graph.allocation.add("status_transitions",StripeBillingOwnedSubscriptionFixture.json("{\"paid_at\":1700000011}"));
        graph.pi.addProperty("status","succeeded");graph.pi.addProperty("amount_received",amount);graph.pi.addProperty("latest_charge","ch_owned");
        return retrievePaid();
    }
    protected void originalCash(){
        beginConfirmation();issueAndSelect();confirm("succeeded");apply(paidObservation(),fence());
        assertThat(currentOperation().status().name()).isEqualTo("SUCCEEDED");
        assertThat(current().paidThrough()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()));
        assertThat(count("payments")).isEqualTo(1);assertThat(count("payment_events")).isEqualTo(1);
        assertThat(count("subscription_billing_records")).isEqualTo(1);assertThat(count("subscription_events")).isEqualTo(1);assertThat(count("outbox_events")).isEqualTo(2);
        assertThat(jdbc.queryForMap("select amount_minor,currency,tax_mode,tax_amount_minor,provider_payment_reference from payments"))
            .containsEntry("amount_minor",amount).containsEntry("currency",currency).containsEntry("tax_mode",taxMode)
            .containsEntry("tax_amount_minor",null).containsEntry("provider_payment_reference","pi_owned");
        assertThat(http.replies).isEmpty();
    }
    protected ReadyPath originalToReady2(){
        originalCash();components=new OwnedBillingFailedRenewalComponents(application::getBean,Clock.systemUTC());
        var upstream=components.admit(receipt,"a".repeat(40));
        var first=advance(upstream,OwnedFirstRenewal.Step.BOUNDARY);
        makeRenewal(false);queueRenewal(first.observation().frozenTime(),false);
        var pending=(OwnedFirstRenewal.Pending)components.boundaryObserver().observe(upstream,first);
        var ready=advance(upstream,OwnedFirstRenewal.Step.FINALIZATION);
        assertThat(http.replies).isEmpty();assertThat(transaction.active()).isFalse();
        return new ReadyPath(upstream,pending,ready);
    }
    private DebtWire debt;
    protected void makeRenewal(boolean failed){
        long end=receipt.periodEnd(), next=end+(interval.equals("YEAR")?365:31)*86400L;
        debt=new DebtWire(graph,end,next,amount,failed);
        renewalInvoice=debt.renewalInvoice;
        renewalLine=debt.renewalLine;
    }
    protected void queueRenewal(long at,boolean failed){
        debt.bodies(clock(at,"ready"),customerJson("clock_owned"),failed).forEach(http::reply);
    }
    protected Map<String,List<Map<String,Object>>> moneyRows(){
        var rows=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(var table:List.of("payments","payment_events","subscription_billing_records","subscription_events","outbox_events","outbox_deliveries"))
            rows.put(table,jdbc.queryForList("select row_to_json(e)::text as row from "+table+" e order by row_to_json(e)::text"));
        return rows;
    }
}

/** Only provider wire bodies. The caller's genuine producers own all receipts and facts. */
final class DebtWire {
    private final StripeBillingOwnedSubscriptionFixture graph;
    final JsonObject renewalInvoice,renewalLine,renewalAllocation,renewalPi,renewalCharge;
    DebtWire(StripeBillingOwnedSubscriptionFixture graph,long end,long next,long amount,boolean failed){
        this.graph=graph;
        renewalInvoice=graph.invoice.deepCopy();renewalInvoice.addProperty("id","in_renewal");renewalInvoice.addProperty("billing_reason","subscription_cycle");
        renewalInvoice.addProperty("status",failed?"open":"draft");renewalInvoice.addProperty("amount_paid",0);renewalInvoice.addProperty("amount_remaining",amount);renewalInvoice.addProperty("amount_paid_off_stripe",0);
        renewalLine=graph.line.deepCopy();renewalLine.addProperty("id","il_renewal");renewalLine.addProperty("invoice","in_renewal");renewalLine.remove("subscription");renewalLine.remove("subscription_item");
        renewalLine.getAsJsonObject("period").addProperty("start",end);renewalLine.getAsJsonObject("period").addProperty("end",next);
        graph.item().addProperty("current_period_start",end);graph.item().addProperty("current_period_end",next);graph.remote.addProperty("status",failed?"past_due":"active");
        renewalAllocation=graph.allocation.deepCopy();renewalAllocation.addProperty("id","inpay_renewal");renewalAllocation.addProperty("invoice","in_renewal");
        renewalAllocation.addProperty("status","open");renewalAllocation.addProperty("is_default",true);renewalAllocation.addProperty("amount_paid",0);
        renewalAllocation.getAsJsonObject("payment").addProperty("payment_intent","pi_renewal");renewalAllocation.getAsJsonObject("status_transitions").add("paid_at",JsonNull.INSTANCE);
        renewalPi=graph.pi.deepCopy();renewalPi.addProperty("id","pi_renewal");renewalPi.addProperty("status","requires_payment_method");renewalPi.addProperty("amount_received",0);renewalPi.addProperty("latest_charge","ch_renewal");
        renewalPi.add("last_payment_error",StripeBillingOwnedSubscriptionFixture.json("{\"type\":\"card_error\",\"code\":\"card_declined\",\"decline_code\":\"generic_decline\",\"charge\":\"ch_renewal\"}"));
        renewalCharge=graph.charge.deepCopy();renewalCharge.addProperty("id","ch_renewal");renewalCharge.addProperty("payment_intent","pi_renewal");
        renewalCharge.addProperty("status","failed");renewalCharge.addProperty("paid",false);renewalCharge.addProperty("captured",false);renewalCharge.addProperty("amount_captured",0);renewalCharge.addProperty("failure_code","card_declined");
        renewalCharge.getAsJsonObject("payment_method_details").add("card",new JsonObject());
        renewalCharge.add("outcome",StripeBillingOwnedSubscriptionFixture.json("{\"type\":\"issuer_declined\",\"reason\":\"generic_decline\"}"));
    }
    List<String> bodies(String clock,String customer,boolean allocationPresent){
        var replies = new ArrayList<String>();
        replies.add(clock);replies.add(customer);replies.add(graph.remote.toString());
        replies.add(StripeBillingOwnedSubscriptionFixture.page(graph.invoice,renewalInvoice));replies.add(StripeBillingOwnedSubscriptionFixture.page(graph.line));
        replies.add(StripeBillingOwnedSubscriptionFixture.page(graph.allocation));replies.add(graph.pi.toString());replies.add(graph.charge.toString());replies.add(StripeBillingOwnedSubscriptionFixture.page(graph.allocation));
        replies.add(StripeBillingOwnedSubscriptionFixture.page(renewalLine));replies.add(allocationPresent?StripeBillingOwnedSubscriptionFixture.page(renewalAllocation):StripeBillingOwnedSubscriptionFixture.page());
        if(allocationPresent){replies.add(renewalPi.toString());replies.add(renewalCharge.toString());replies.add(StripeBillingOwnedSubscriptionFixture.page(renewalAllocation));}
        replies.add(clock);
        return replies;
    }
}
