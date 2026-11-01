package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.page;
import static org.assertj.core.api.Assertions.*;

/** Pure/inert mapper consumer: no parent, extension, application, database, sockets or mock framework. */
class StripeBillingFirstRenewalMappingTest {
    @Test void earlyBoundaryCashIsStillPrivatePending(){
        var f=paidLocal();var admission=admission(f);queueGraph(f,true);
        assertThat(StripeBillingSnapshotMapper.retrieveOwnedFirstRenewal(f.f.clients.create(f.f.credentials.resolve(f.f.account)),f.ordinary(),admission,
            boundaryReady(admission,f.utc.plusSeconds(4)),f.utc.plusSeconds(5))).isInstanceOf(MappedPending.class);
    }
    @ParameterizedTest @ValueSource(strings={"missing","null","number","wrong","duplicate"})
    void renewalFlatItemMustBeLiteralExactEvidence(String representation){
        var f=paidLocal();var admission=admission(f);queueGraph(f,false);
        replaceReply(f,8,body->{var page=StripeBillingOwnedSubscriptionFixture.json(body);var line=page.getAsJsonArray("data").get(0).getAsJsonObject();
            switch(representation){case "missing"->line.remove("subscription_item");case "null"->line.add("subscription_item",com.google.gson.JsonNull.INSTANCE);
                case "number"->line.addProperty("subscription_item",42);case "wrong"->line.addProperty("subscription_item","si_foreign");
                case "duplicate"->{String raw=page.toString();int at=raw.lastIndexOf("\"subscription_item\":\"si_owned\"");return raw.substring(0,at)+"\"subscription_item\":\"si_owned\","+raw.substring(at);}
                default->throw new AssertionError(representation);}return page.toString();});
        assertThatThrownBy(()->StripeBillingSnapshotMapper.retrieveOwnedFirstRenewal(f.f.clients.create(f.f.credentials.resolve(f.f.account)),f.ordinary(),admission,
            boundaryReady(admission,f.utc.plusSeconds(4)),f.utc.plusSeconds(5))).isInstanceOf(RuntimeException.class);
    }
    @ParameterizedTest @ValueSource(strings={"third","duplicate-initial","duplicate-renewal","initial-allocation","initial-pi","renewal-reverse","split","created","item","terms","clock","gap","overlap","end","void","uncollectible","action","failed","zero","credit","flat-subscription","nested-item","off-stripe"})
    void malformedOrUnsupportedRenewalNeverProducesCash(String mutation){
        var f=paidLocal();var admission=admission(f);queueGraph(f,true);
        int index=switch(mutation){case "created","item","terms"->1;case "clock"->0;case "initial-allocation"->4;case "initial-pi"->5;
            case "renewal-reverse"->12;case "split"->9;case "action","failed"->10;case "gap","overlap","end","flat-subscription","nested-item"->8;default->2;};
        replaceReply(f,index,body->{var json=StripeBillingOwnedSubscriptionFixture.json(body);var data=json.has("data")?json.getAsJsonArray("data"):null;
            var first=data==null?json:data.get(0).getAsJsonObject();var renewal=data!=null&&data.size()>1?data.get(1).getAsJsonObject():null;
            switch(mutation){
                case "third"->{var third=renewal.deepCopy();third.addProperty("id","in_third");data.add(third);}
                case "duplicate-initial"->renewal.addProperty("billing_reason","subscription_create");
                case "duplicate-renewal"->first.addProperty("billing_reason","subscription_cycle");
                case "initial-allocation"->first.addProperty("id","inpay_foreign");case "initial-pi"->first.addProperty("id","pi_foreign");
                case "renewal-reverse"->first.addProperty("invoice","in_foreign");
                case "split"->{var second=first.deepCopy();second.addProperty("id","inpay_split");data.add(second);}
                case "created"->first.addProperty("created",43);case "item"->first.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject().addProperty("id","si_foreign");
                case "terms"->first.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject().getAsJsonObject("price").addProperty("unit_amount",999);
                case "clock"->first.addProperty("test_clock","clock_foreign");case "gap"->first.getAsJsonObject("period").addProperty("start",1702678401);
                case "overlap"->first.getAsJsonObject("period").addProperty("start",1702678399);case "end"->first.getAsJsonObject("period").addProperty("end",1702682000);
                case "void","uncollectible"->renewal.addProperty("status",mutation);case "action"->first.addProperty("status","requires_action");case "failed"->first.addProperty("status","requires_payment_method");
                case "zero"->renewal.addProperty("amount_due",0);case "credit"->renewal.addProperty("pre_payment_credit_notes_amount",1);
                case "flat-subscription"->first.remove("subscription");case "nested-item"->first.getAsJsonObject("parent").getAsJsonObject("subscription_item_details").addProperty("subscription_item","si_foreign");
                case "off-stripe"->renewal.remove("amount_paid_off_stripe");default->throw new AssertionError(mutation);
            }return json.toString();});
        assertThatThrownBy(()->StripeBillingSnapshotMapper.retrieveOwnedFirstRenewal(f.f.clients.create(f.f.credentials.resolve(f.f.account)),f.ordinary(),admission,
            boundaryReady(admission,f.utc.plusSeconds(4)),f.utc.plusSeconds(5))).as(mutation).isInstanceOf(RuntimeException.class);
    }
    @Test void historicalNullAllowancesStayConfinedToOriginalInitial(){
        var f=paidLocal();var admission=admission(f);f.line.remove("subscription");f.invoice.remove("amount_paid_off_stripe");queueGraph(f,false);
        // The renewal starts as a copy of the original, then receives its own required strict fields.
        replaceReply(f,2,body->{var json=StripeBillingOwnedSubscriptionFixture.json(body);json.getAsJsonArray("data").get(1).getAsJsonObject().addProperty("amount_paid_off_stripe",0);return json.toString();});
        replaceReply(f,8,body->{var json=StripeBillingOwnedSubscriptionFixture.json(body);json.getAsJsonArray("data").get(0).getAsJsonObject().addProperty("subscription","sub_owned");return json.toString();});
        assertThat(StripeBillingSnapshotMapper.retrieveOwnedFirstRenewal(f.f.clients.create(f.f.credentials.resolve(f.f.account)),f.ordinary(),admission,
            boundaryReady(admission,f.utc.plusSeconds(4)),f.utc.plusSeconds(5))).isInstanceOf(MappedPending.class);
    }
    @Test void firstRenewalUsesProviderPeriod(){
        var f=paidLocal();long s=Instant.parse("2026-01-31T00:00:00Z").getEpochSecond(),e=Instant.parse("2026-02-28T00:00:00Z").getEpochSecond(),n=Instant.parse("2026-03-31T00:00:00Z").getEpochSecond();
        var old=f.intent;var o=old.clock().owner();
        var owner=new dev.genericpayments.platform.provider.application.model.ClockOwner(o.intent(),o.run(),o.account(),o.site(),o.customer(),o.externalAccount(),o.credentialsRef(),o.apiVersion(),s,o.name(),o.createKey(),o.createFingerprint(),o.admittedAt());
        var clock=new dev.genericpayments.platform.provider.application.model.ReadyClock(owner,old.clock().creationOperation(),"clock_owned",s,1800000000,1801000000,old.clock().observedAt(),old.clock().customerFingerprint());
        var intent=new dev.genericpayments.platform.provider.application.model.OwnedClockSubscription(old.id(),clock,old.customerBinding(),old.customerCreationOperation(),old.customerReference(),old.subscription(),old.createOperation(),old.operationKey(),old.requestFingerprint(),old.expectedRevision(),old.policyVersion(),old.terms(),old.priceReference(),old.productReference(),old.fingerprint(),old.admittedAt());
        var receipt=new dev.genericpayments.platform.provider.application.model.OwnedClockSubscriptionReceipt(intent,id(15),"sub_owned",42,"si_owned",s,e,f.utc.plusSeconds(1));
        var admission=new Admission(id(16),receipt,"a".repeat(40),id(17),id(18),1,id(19),0,id(20),0,"in_owned","inpay_owned","pi_owned",2,1,"e".repeat(64),f.utc.plusSeconds(2));
        var original=new Subscription.Period(Instant.ofEpochSecond(s),Instant.ofEpochSecond(e));
        f.local=new Subscription(f.subscriptionId,f.f.site,f.f.customer,f.terms.productId(),f.f.account.id(),f.terms,f.terms,Subscription.Status.ACTIVE,"sub_owned",original,original.end(),null,null,null,null,null,2,f.utc,f.utc.plusSeconds(2),f.utc.plusSeconds(2));
        queueGraph(f,true);
        replaceReply(f,1,body->{var json=StripeBillingOwnedSubscriptionFixture.json(body);var item=json.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject();item.addProperty("current_period_start",e);item.addProperty("current_period_end",n);return json.toString();});
        replacePeriod(f,3,s,e);replacePeriod(f,8,e,n);
        var first=boundaryReady(admission,f.utc.plusSeconds(4));var boundary=new Boundary(id(24),admission.id(),first,"si_owned","in_renewal",new Subscription.Period(Instant.ofEpochSecond(e),Instant.ofEpochSecond(n)),"b".repeat(64),f.utc.plusSeconds(5));
        var mapped=(MappedPaid)StripeBillingSnapshotMapper.retrieveOwnedFirstRenewal(f.f.clients.create(f.f.credentials.resolve(f.f.account)),f.ordinary(),admission,finalReady(admission,boundary),f.utc.plusSeconds(10),boundary);
        assertThat(mapped.snapshot().invoices().getFirst().period()).isEqualTo(original);assertThat(mapped.snapshot().period().end()).isEqualTo(Instant.parse("2026-03-31T00:00:00Z"));
    }
    private static void replacePeriod(StripeBillingOwnedSubscriptionFixture f,int index,long start,long end){replaceReply(f,index,body->{var json=StripeBillingOwnedSubscriptionFixture.json(body);var period=json.getAsJsonArray("data").get(0).getAsJsonObject().getAsJsonObject("period");period.addProperty("start",start);period.addProperty("end",end);return json.toString();});}
    private static void replaceReply(StripeBillingOwnedSubscriptionFixture f,int index,java.util.function.UnaryOperator<String> transform){
        var values=new java.util.ArrayList<>(f.f.http.replies);var original=(com.stripe.net.StripeResponse)values.get(index);
        values.set(index,new com.stripe.net.StripeResponse(200,com.stripe.net.HttpHeaders.of(java.util.Map.of()),transform.apply(original.body())));
        f.f.http.replies.clear();f.f.http.replies.addAll(values);
    }
    @Test void cashRequiresFullJoinedGraph() {
        var f=paidLocal();var admission=admission(f);var first=boundaryReady(admission,f.utc.plusSeconds(4));
        var boundary=new Boundary(id(24),admission.id(),first,"si_owned","in_renewal",
            new Subscription.Period(Instant.ofEpochSecond(1702678400),Instant.ofEpochSecond(1705356800)),"b".repeat(64),f.utc.plusSeconds(5));
        var ready=finalReady(admission,boundary);queueGraph(f,true);
        var result=new AtomicReference<MappedObservation>();
        assertThatCode(()->result.set(StripeBillingSnapshotMapper.retrieveOwnedFirstRenewal(
            f.f.clients.create(f.f.credentials.resolve(f.f.account)),f.ordinary(),admission,ready,f.utc.plusSeconds(10),boundary)))
            .as("A complete historical and renewal cash graph must yield typed private Paid evidence").doesNotThrowAnyException();
        assertThat(result.get()).isInstanceOf(MappedPaid.class);var paid=(MappedPaid)result.get();
        assertThat(paid.boundary()).isEqualTo(boundary);var snapshot=paid.snapshot();
        assertThat(snapshot.status()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(snapshot.providerRevision()).isZero();assertThat(snapshot.operationKey()).isNull();assertThat(snapshot.operationObservation()).isNull();
        assertThat(snapshot.period()).isEqualTo(boundary.period());
        assertThat(snapshot.invoices()).hasSize(2);
        assertThat(snapshot.invoices().getFirst().cashObjects().getFirst().reference()).isEqualTo("pi_owned");
        assertThat(snapshot.invoices().getLast().cashObjects().getFirst().reference()).isEqualTo("pi_renewal");
        assertThat(snapshot.invoices().getLast().cashObjects().getFirst().occurredAt()).isEqualTo(Instant.ofEpochSecond(1702678411));
        assertThat(f.f.http.replies).isEmpty();assertThat(f.f.http.calls).hasSize(13);
    }
    @Test void finalReadyIsNotPaid() {
        var f=paidLocal();var admission=admission(f);var first=boundaryReady(admission,f.utc.plusSeconds(4));
        var boundary=new Boundary(id(24),admission.id(),first,"si_owned","in_renewal",
            new Subscription.Period(Instant.ofEpochSecond(1702678400),Instant.ofEpochSecond(1705356800)),"b".repeat(64),f.utc.plusSeconds(5));
        queueGraph(f,false);var result=new AtomicReference<MappedObservation>();
        assertThatCode(()->result.set(StripeBillingSnapshotMapper.retrieveOwnedFirstRenewal(
            f.f.clients.create(f.f.credentials.resolve(f.f.account)),f.ordinary(),admission,finalReady(admission,boundary),f.utc.plusSeconds(10),boundary)))
            .doesNotThrowAnyException();
        assertThat(result.get()).isInstanceOf(MappedPending.class);assertThat(((MappedPending)result.get()).boundary()).isEqualTo(boundary);
    }
    @Test void boundaryDraftWithoutPaymentIsPrivatePending() {
        var f=new StripeBillingOwnedSubscriptionFixture();
        var originalPeriod=new Subscription.Period(Instant.ofEpochSecond(1699999900),Instant.ofEpochSecond(1702678400));
        f.local=new Subscription(f.subscriptionId,f.f.site,f.f.customer,f.terms.productId(),f.f.account.id(),f.terms,f.terms,
            Subscription.Status.ACTIVE,"sub_owned",originalPeriod,Instant.ofEpochSecond(1702678400),null,null,null,null,null,2,
            f.utc,f.utc.plusSeconds(2),f.utc.plusSeconds(2));
        var admission=admission(f);
        var ready=boundaryReady(admission,f.utc.plusSeconds(4));
        queueHistoricalCashAndDraftRenewal(f);
        var client=f.f.clients.create(f.f.credentials.resolve(f.f.account));
        var result=new AtomicReference<MappedObservation>();

        // Catches routing a valid two-invoice renewal through the original-only mapper or rejecting draft.
        assertThatCode(()->result.set(StripeBillingSnapshotMapper.retrieveOwnedFirstRenewal(
            client,f.ordinary(),admission,ready,f.utc.plusSeconds(5))))
            .as("Historical INITIAL cash plus one draft renewal must yield private boundary Pending")
            .doesNotThrowAnyException();

        assertThat(result.get()).isInstanceOf(MappedPending.class);
        var boundary=((MappedPending)result.get()).boundary();
        assertThat(boundary.admissionId()).isEqualTo(id(16));
        assertThat(boundary.ready()).isEqualTo(ready);
        assertThat(boundary.item()).isEqualTo("si_owned");
        assertThat(boundary.renewalInvoice()).isEqualTo("in_renewal");
        assertThat(boundary.period().start()).isEqualTo(Instant.ofEpochSecond(1702678400));
        assertThat(boundary.period().end()).isEqualTo(Instant.ofEpochSecond(1705356800));
        assertThat(f.local.revision()).isEqualTo(2);
        assertThat(f.local.paidThrough()).isEqualTo(Instant.ofEpochSecond(1702678400));
        assertThat(f.local.currentPeriod()).isEqualTo(originalPeriod);
        assertThat(f.f.http.replies).as("Both invoice graphs, including the historical cash reverse join, were consumed").isEmpty();
        assertThat(f.f.http.calls).hasSize(10).allSatisfy(request->assertThat(request.method().name()).isEqualTo("GET"));
        assertThat(f.f.http.calls.stream().map(request->request.url().getPath()).toList()).containsExactly(
            "/v1/customers/cus_owned","/v1/subscriptions/sub_owned","/v1/invoices",
            "/v1/invoices/in_owned/lines","/v1/invoice_payments","/v1/payment_intents/pi_owned",
            "/v1/charges/ch_owned","/v1/invoice_payments","/v1/invoices/in_renewal/lines","/v1/invoice_payments");
    }

    static Admission admission(StripeBillingOwnedSubscriptionFixture f) {
        return new Admission(id(16),f.receipt(),"a".repeat(40),id(17),id(18),1,id(19),0,id(20),0,
            "in_owned","inpay_owned","pi_owned",2,1,"e".repeat(64),f.utc.plusSeconds(2));
    }

    static LaterReady boundaryReady(Admission admission,Instant at) {
        var clock=admission.receipt().intent().clock();var owner=clock.owner();
        var advance=new Advance(id(21),admission.id(),Step.BOUNDARY,admission.receipt().periodEnd(),null,null,
            "billing-renewal-advance-v1:"+admission.id()+":1","f".repeat(64),at.minusSeconds(1));
        var operation=new ProviderOperation(id(22),owner.account(),owner.externalAccount(),owner.credentialsRef(),
            ProviderOperation.Kind.TEST_CLOCK_ADVANCE,advance.id(),advance.operationKey(),advance.fingerprint(),
            ProviderOperation.Status.ACKNOWLEDGED,"clock_owned",at.minusSeconds(1),at.plusSeconds(82800),null,null,2);
        var ack=new Ack(advance,operation,"original-claimant",at.plusSeconds(59),
            new TestClockAck("clock_owned","test_helpers.test_clock",false,owner.name(),clock.frozenTime(),
                "advancing",1800000000,1801000000,admission.receipt().periodEnd()),at.minusSeconds(1));
        return new LaterReady(id(23),admission.id(),ack,
            new TestClockReady("clock_owned","test_helpers.test_clock",false,owner.name(),admission.receipt().periodEnd(),
                "ready",1800000000,1801000000,null),at);
    }

    private static void queueHistoricalCashAndDraftRenewal(StripeBillingOwnedSubscriptionFixture f) {
        queueGraph(f,false);
    }
    static void queueGraph(StripeBillingOwnedSubscriptionFixture f,boolean paid) {
        var renewal=f.invoice.deepCopy();renewal.addProperty("id","in_renewal");
        renewal.addProperty("status","draft");renewal.addProperty("billing_reason","subscription_cycle");
        renewal.addProperty("amount_paid",0);renewal.addProperty("amount_remaining",1000);
        if(paid){renewal.addProperty("status","paid");renewal.addProperty("amount_paid",1000);renewal.addProperty("amount_remaining",0);}
        var renewalLine=f.line.deepCopy();renewalLine.addProperty("id","il_renewal");
        renewalLine.addProperty("invoice","in_renewal");renewalLine.addProperty("subscription_item","si_owned");
        renewalLine.getAsJsonObject("period").addProperty("start",1702678400);
        renewalLine.getAsJsonObject("period").addProperty("end",1705356800);
        f.invoice.addProperty("status","paid");f.invoice.addProperty("amount_paid",1000);f.invoice.addProperty("amount_remaining",0);
        f.remote.addProperty("status","active");f.item().addProperty("current_period_start",1702678400);
        f.item().addProperty("current_period_end",1705356800);
        f.f.http.reply(f.customer.toString());f.f.http.reply(f.remote.toString());f.f.http.reply(page(f.invoice,renewal));
        f.f.http.reply(page(f.line));f.f.http.reply(page(f.allocation));f.f.http.reply(f.pi.toString());
        f.f.http.reply(f.charge.toString());f.f.http.reply(page(f.allocation));
        f.f.http.reply(page(renewalLine));
        if(paid){
            var allocation=f.allocation.deepCopy();allocation.addProperty("id","inpay_renewal");allocation.addProperty("invoice","in_renewal");
            allocation.getAsJsonObject("payment").addProperty("payment_intent","pi_renewal");allocation.getAsJsonObject("status_transitions").addProperty("paid_at",1702678411);
            var pi=f.pi.deepCopy();pi.addProperty("id","pi_renewal");pi.addProperty("latest_charge","ch_renewal");
            var charge=f.charge.deepCopy();charge.addProperty("id","ch_renewal");charge.addProperty("payment_intent","pi_renewal");
            f.f.http.reply(page(allocation));f.f.http.reply(pi.toString());f.f.http.reply(charge.toString());f.f.http.reply(page(allocation));
        }else f.f.http.reply(page());
    }

    static StripeBillingOwnedSubscriptionFixture paidLocal(){
        var f=new StripeBillingOwnedSubscriptionFixture();var period=new Subscription.Period(Instant.ofEpochSecond(1699999900),Instant.ofEpochSecond(1702678400));
        f.local=new Subscription(f.subscriptionId,f.f.site,f.f.customer,f.terms.productId(),f.f.account.id(),f.terms,f.terms,
            Subscription.Status.ACTIVE,"sub_owned",period,period.end(),null,null,null,null,null,2,f.utc,f.utc.plusSeconds(2),f.utc.plusSeconds(2));return f;
    }
    static LaterReady finalReady(Admission admission,Boundary boundary){
        var at=admission.admittedAt().plusSeconds(6);var c=admission.receipt().intent().clock();var o=c.owner();
        long target=admission.receipt().periodEnd()+3600;
        var advance=new Advance(id(25),admission.id(),Step.FINALIZATION,target,boundary.ready().id(),boundary.id(),
            "billing-renewal-advance-v1:"+admission.id()+":2","c".repeat(64),at);
        var operation=new ProviderOperation(id(26),o.account(),o.externalAccount(),o.credentialsRef(),ProviderOperation.Kind.TEST_CLOCK_ADVANCE,
            advance.id(),advance.operationKey(),advance.fingerprint(),ProviderOperation.Status.ACKNOWLEDGED,c.reference(),at,at.plusSeconds(82800),null,null,2);
        var ack=new Ack(advance,operation,"final-claimant",at.plusSeconds(60),new TestClockAck(c.reference(),"test_helpers.test_clock",false,o.name(),admission.receipt().periodEnd(),
            "advancing",c.providerCreated(),c.providerDeletesAfter(),target),at);
        return new LaterReady(id(27),admission.id(),ack,new TestClockReady(c.reference(),"test_helpers.test_clock",false,o.name(),target,
            "ready",c.providerCreated(),c.providerDeletesAfter(),null),at.plusSeconds(1));
    }

    private static UUID id(int value){return new UUID(0,value);}
}
