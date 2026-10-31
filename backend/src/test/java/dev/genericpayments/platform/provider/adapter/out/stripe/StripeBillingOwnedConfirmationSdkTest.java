package dev.genericpayments.platform.provider.adapter.out.stripe;

import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.support.*;
import java.time.*;
import java.util.*;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Audited standalone tests: only value objects and an inert official SDK HttpClient. */
class StripeBillingOwnedConfirmationSdkTest {
    // Requiring the duplicate flat field in any owned phase breaks this complete receipt-bound flow.
    @ParameterizedTest @ValueSource(strings={"exact","omitted","null"})
    void ownedFlatRepresentationPreservesBindingRootMaterialAndPreconfirm(String representation){
        for(var indicator:List.of("omitted","null","zero")){
        var exact=new Harness();openGraph(exact.f);
        var expected=StripeBillingSnapshotMapper.retrieveOwnedPaymentAction(exact.f.f.clients.create(null),exact.f.ordinary(),new StripeBillingSnapshotMapper.OwnedInitial(exact.receipt),exact.now);
        assertThat(expected.amount()).isEqualTo(1000);assertThat(expected.currency()).isEqualTo("eur");
        var h=new Harness();h.f.flatSubscription(representation);StripeBillingClockSnapshotTest.indicator(h.f,indicator);h.graph();
        assertThatCode(()->assertThat(h.action().nextAction(h.scope)).isNotNull()).doesNotThrowAnyException();
        assertThat(h.root.binding()).isEqualTo(expected.binding());
        assertThat(h.root.binding().schema()).isEqualTo("owned-initial-v1");
        assertThat(h.root.binding().ownedIntent()).isEqualTo(h.f.intent.id());
        assertThat(h.inserts).isEqualTo(1);assertThat(h.root.expiresAt()).isEqualTo(h.now.plusSeconds(600));
        assertThat(h.f.f.http.calls).hasSize(9).allMatch(c->c.method().name().equals("GET"));
        h.reserve(false);h.graph();
        var material=h.provider().read(h.f.lookup(),h.root,null);
        assertThat(material.clientSecret()).isEqualTo("pi_owned_secret_INERT");
        assertThat(material.publishableKey()).isEqualTo("pk_test_INERT");
        assertThat(h.f.f.http.calls).hasSize(18).allMatch(c->c.method().name().equals("GET"));
        h.reserve(true);h.graph();h.f.f.http.reply(h.success());h.f.f.http.reply(h.f.f.clockJson());
        assertThat(h.provider().confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT").state()).isEqualTo(SubscriptionConfirmationAttempt.State.OBSERVE);
        assertThat(h.f.f.http.calls).hasSize(29);
        assertThat(h.f.f.http.calls.stream().filter(c->c.method().name().equals("POST")).map(c->c.url().getPath())).containsExactly("/v1/payment_intents/pi_owned/confirm");
        assertThat(h.action().nextAction(h.scope)).isNull();assertThat(h.inserts).isEqualTo(1);assertThat(h.f.f.http.calls).hasSize(29);
        var binding=h.root.binding();var rootId=h.root.id();
        h.f.allocation.addProperty("status","paid");h.f.allocation.addProperty("amount_paid",1000);h.f.allocation.add("status_transitions",StripeBillingOwnedSubscriptionFixture.json("{\"paid_at\":1700000011}"));
        h.f.pi.addProperty("status","succeeded");h.f.pi.addProperty("amount_received",1000);h.f.pi.addProperty("latest_charge","ch_owned");
        h.f.paidGraph();var snapshot=StripeBillingSnapshotMapper.retrieveOwned(h.f.f.clients.create(null),h.f.ordinary(),new StripeBillingSnapshotMapper.OwnedInitial(h.receipt),h.now);
        var paid=snapshot.invoices().getFirst();assertThat(paid.settlement()).isEqualTo(SubscriptionProviderModels.SettlementKind.CASH);
        assertThat(paid.amountDueMinor()).isEqualTo(1000);assertThat(paid.currency()).isEqualTo("EUR");
        assertThat(paid.cashObjects()).singleElement().satisfies(c->{assertThat(c.reference()).isEqualTo("pi_owned");assertThat(c.amountMinor()).isEqualTo(1000);assertThat(c.currency()).isEqualTo("EUR");});
        assertThat(h.root.id()).isEqualTo(rootId);assertThat(h.root.binding()).isEqualTo(binding);assertThat(h.inserts).isEqualTo(1);assertThat(h.f.f.http.calls).hasSize(37);
        assertThat(h.f.f.http.calls.stream().map(c->c.method().name()+" "+c.url().getPath()).toList()).containsExactly(
            "GET /v1/test_helpers/test_clocks/clock_owned","GET /v1/customers/cus_owned","GET /v1/subscriptions/sub_owned","GET /v1/invoices","GET /v1/invoices/in_owned/lines","GET /v1/invoice_payments","GET /v1/payment_intents/pi_owned","GET /v1/invoice_payments","GET /v1/test_helpers/test_clocks/clock_owned",
            "GET /v1/test_helpers/test_clocks/clock_owned","GET /v1/customers/cus_owned","GET /v1/subscriptions/sub_owned","GET /v1/invoices","GET /v1/invoices/in_owned/lines","GET /v1/invoice_payments","GET /v1/payment_intents/pi_owned","GET /v1/invoice_payments","GET /v1/test_helpers/test_clocks/clock_owned",
            "GET /v1/test_helpers/test_clocks/clock_owned","GET /v1/customers/cus_owned","GET /v1/subscriptions/sub_owned","GET /v1/invoices","GET /v1/invoices/in_owned/lines","GET /v1/invoice_payments","GET /v1/payment_intents/pi_owned","GET /v1/invoice_payments","GET /v1/test_helpers/test_clocks/clock_owned","POST /v1/payment_intents/pi_owned/confirm","GET /v1/test_helpers/test_clocks/clock_owned",
            "GET /v1/customers/cus_owned","GET /v1/subscriptions/sub_owned","GET /v1/invoices","GET /v1/invoices/in_owned/lines","GET /v1/invoice_payments","GET /v1/payment_intents/pi_owned","GET /v1/charges/ch_owned","GET /v1/invoice_payments");
        }
    }
    // Omitting a duplicate never substitutes for the complete nested ownership and line envelope.
    @ParameterizedTest @ValueSource(strings={"missingSubscription","nullSubscription","foreignSubscription","missingItem","nullItem","foreignItem","missingParent","nullParent","wrongParentType","missingParentType","nullParentType","missingDetails","nullDetails","missingProration","nullProration","trueProration","lineInvoice","quantity","nullQuantity","live","object","price","product","period","invoiceParentMissing","invoiceParentNull","invoiceParentType","invoiceDetailsMissing","invoiceDetailsNull"})
    void missingFlatStillRequiresEveryNestedAssociationAndLineValue(String bad){
        for(var indicator:List.of("omitted","null","zero"))for(var representation:List.of("omitted","null")){
            var h=new Harness();prepareOpen(h.f);h.f.flatSubscription(representation);StripeBillingClockSnapshotTest.indicator(h.f,indicator);
            var line=h.f.line;var parent=line.getAsJsonObject("parent");var details=parent.getAsJsonObject("subscription_item_details");
            switch(bad){
                case "invoiceParentMissing"->h.f.invoice.remove("parent");case "invoiceParentNull"->h.f.invoice.add("parent",com.google.gson.JsonNull.INSTANCE);case "invoiceParentType"->h.f.invoice.getAsJsonObject("parent").addProperty("type","invoice_item_details");case "invoiceDetailsMissing"->h.f.invoice.getAsJsonObject("parent").remove("subscription_details");case "invoiceDetailsNull"->h.f.invoice.getAsJsonObject("parent").add("subscription_details",com.google.gson.JsonNull.INSTANCE);
                case "missingSubscription"->details.remove("subscription");case "nullSubscription"->details.add("subscription",com.google.gson.JsonNull.INSTANCE);case "foreignSubscription"->details.addProperty("subscription","sub_foreign");
                case "missingItem"->details.remove("subscription_item");case "nullItem"->details.add("subscription_item",com.google.gson.JsonNull.INSTANCE);case "foreignItem"->details.addProperty("subscription_item","si_foreign");
                case "missingParent"->line.remove("parent");case "nullParent"->line.add("parent",com.google.gson.JsonNull.INSTANCE);case "wrongParentType"->parent.addProperty("type","invoice_item_details");case "missingParentType"->parent.remove("type");case "nullParentType"->parent.add("type",com.google.gson.JsonNull.INSTANCE);
                case "missingDetails"->parent.remove("subscription_item_details");case "nullDetails"->parent.add("subscription_item_details",com.google.gson.JsonNull.INSTANCE);
                case "missingProration"->details.remove("proration");case "nullProration"->details.add("proration",com.google.gson.JsonNull.INSTANCE);case "trueProration"->details.addProperty("proration",true);
                case "lineInvoice"->line.addProperty("invoice","in_foreign");case "quantity"->line.addProperty("quantity",2);case "nullQuantity"->line.add("quantity",com.google.gson.JsonNull.INSTANCE);case "live"->line.addProperty("livemode",true);case "object"->line.addProperty("object","invoice_item");
                case "price"->line.getAsJsonObject("pricing").getAsJsonObject("price_details").addProperty("price","price_foreign");case "product"->line.getAsJsonObject("pricing").getAsJsonObject("price_details").addProperty("product","prod_foreign");case "period"->line.getAsJsonObject("period").addProperty("end",1702678401);
            }
            queueActionGraph(h);var adapter=h.action();
            assertThat(catchThrowable(()->adapter.nextAction(h.scope))).as("%s with %s flat reference",bad,representation).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
            assertThat(h.root).isNull();assertThat(h.inserts).isZero();
            assertThat(h.f.f.http.calls.size()).isBetween(4,6);assertThat(h.f.f.http.calls).allMatch(c->c.method().name().equals("GET"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"nested","allocation","capture"})
    void allowedFlatAbsenceCannotDeferBrokenGraphToConfirmationPost(String broken){
        for(var indicator:List.of("omitted","null","zero"))for(var representation:List.of("omitted","null")){
            var h=new Harness();h.f.flatSubscription(representation);StripeBillingClockSnapshotTest.indicator(h.f,indicator);h.root();h.reserve(false);prepareOpen(h.f);
            switch(broken){case "nested"->h.f.line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details").remove("subscription");case "allocation"->h.f.allocation.remove("amount_paid");case "capture"->h.f.pi.addProperty("capture_method","manual");}
            queueActionGraph(h);assertThatThrownBy(()->h.provider().read(h.f.lookup(),h.root,null)).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
            h.f.f.http.replies.clear();h.reserve(true);queueActionGraph(h);
            assertThat(h.provider().confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT").state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);
            assertThat(h.f.f.http.calls.size()).isBetween(10,14);assertThat(h.f.f.http.calls).allMatch(c->c.method().name().equals("GET"));
        }
    }
    // Actual pinned SDK parsing must never turn malformed-present flat data into allowed absence.
    @ParameterizedTest @ValueSource(strings={"\"sub_foreign\"","\"\"","{\"id\":\"sub_foreign\",\"object\":\"subscription\"}","{}","{\"id\":null}","42","true","[]"})
    void suppliedForeignOrMalformedFlatSubscriptionCannotIssueRoot(String json){
        var h=new Harness();prepareOpen(h.f);h.f.line.add("subscription",com.google.gson.JsonParser.parseString(json));queueActionGraph(h);
        var adapter=h.action();assertThat(catchThrowable(()->adapter.nextAction(h.scope))).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
        assertThat(h.root).isNull();assertThat(h.inserts).isZero();assertThat(h.f.f.http.calls).hasSize(5).allMatch(c->c.method().name().equals("GET"));
    }
    @Test void explicitOwnedActionAcceptsOpaqueCreatedAndSeparatedClockWhileOrdinaryRefuses() throws Exception {
        var f=new StripeBillingOwnedSubscriptionFixture();
        f.graph();
        assertThatThrownBy(()->StripeBillingSnapshotMapper.retrieve(f.f.clients.create(null),f.ordinary(),f.utc)).isInstanceOf(RuntimeException.class);
        f.f.http.replies.clear();openGraph(f);
        var entry=java.util.Arrays.stream(StripeBillingSnapshotMapper.class.getMethods()).filter(m->m.getName().equals("retrieveOwnedPaymentAction")).findFirst();
        assertThat(entry).as("explicit typed owned payment action entry").isPresent();
        var action=(StripeBillingSnapshotMapper.PaymentAction)entry.orElseThrow().invoke(null,f.f.clients.create(null),f.ordinary(),new StripeBillingSnapshotMapper.OwnedInitial(f.receipt()),f.utc);
        assertThat(action.binding().schema()).isEqualTo("owned-initial-v1");
        assertThat(action.binding().ownedIntent()).isEqualTo(f.intent.id());
        assertThat(action.binding().proof()).isEqualTo(OwnedInitialConfirmationProof.hash(f.receipt(),"in_owned","inpay_owned","pi_owned"));
        assertThat(action.clientSecret()).isEqualTo("pi_owned_secret_INERT");
        assertThat(action.toString()).doesNotContain(action.clientSecret());
    }
    @Test void ownedActionIssuesOneUtcRootAndRefusesReservedReissue() throws Exception {
        var h=new Harness();h.graph();
        var type=type("StripeBillingClockActionAdapter");
        var adapter=type.getConstructors()[0].newInstance(h.receipt,h.phases,h.roots,h.tokens,h.f.f.credentials,h.f.f.clients,h.f.f.verification,h.clock);
        var next=type.getMethod("nextAction",SubscriptionConfirmationUseCase.Scope.class);
        assertThat(selectionFailureStage((StripeBillingClockActionAdapter)adapter)).isNull();
        var action=(SubscriptionProviderModels.NextAction)next.invoke(adapter,h.scope);
        assertThat(action.secret()).startsWith("bga1_");assertThat(action.expiresAt()).isEqualTo(h.now.plusSeconds(600));assertThat(h.inserts).isEqualTo(1);
        assertThat(selectionFailureStage((StripeBillingClockActionAdapter)adapter)).isNull();
        h.reserve(false);int reads=h.f.f.http.calls.size();
        assertThat(next.invoke(adapter,h.scope)).isNull();assertThat(h.inserts).isEqualTo(1);assertThat(h.f.f.http.calls).hasSize(reads);assertThat(h.f.f.verifications).isEqualTo(1);
        assertThat(selectionFailureStage((StripeBillingClockActionAdapter)adapter)).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"ambient","scope","snapshot"})
    void actionAuthorityFailuresStopBeforeVerificationOrIo(String boundary){
        var h=new Harness();var adapter=h.action();var failure=new UnreadableFailure();
        var scope=boundary.equals("scope")?new SubscriptionConfirmationUseCase.Scope(h.scope.context(),UUID.randomUUID(),h.scope.operation()):h.scope;
        if(boundary.equals("snapshot"))h.currentFailure=failure;
        if(boundary.equals("ambient"))org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        Throwable thrown;
        try{thrown=catchThrowable(()->adapter.nextAction(scope));}
        finally{org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}
        assertThat(thrown).isInstanceOf(RuntimeException.class);
        if(boundary.equals("snapshot"))assertThat(thrown).isSameAs(failure);
        assertThat(selectionFailureStage(adapter)).isEqualTo("ACTION_AUTHORITY");
        assertThat(h.f.f.verifications).isZero();assertThat(h.f.f.http.calls).isEmpty();assertThat(h.inserts).isZero();
    }
    @Test void actionVerificationFailureRetainsIdentityAndFirstStageThroughSuccessAndDuplicateRefusal(){
        var h=new Harness();var failure=new UnreadableFailure();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var adapter=new StripeBillingClockActionAdapter(h.receipt,h.phases,h.roots,h.tokens,h.f.f.credentials,h.f.f.clients,a->{
            h.f.f.verification.verify(a);if(calls.incrementAndGet()==1)throw failure;
        },h.clock);
        assertThat(catchThrowable(()->adapter.nextAction(h.scope))).isSameAs(failure);
        assertThat(selectionFailureStage(adapter)).isEqualTo("ACTION_VERIFICATION");
        assertThat(h.f.f.http.calls).isEmpty();assertThat(h.inserts).isZero();
        h.graph();assertThat(adapter.nextAction(h.scope)).isNotNull();assertThat(h.inserts).isEqualTo(1);
        assertThat(selectionFailureStage(adapter)).isEqualTo("ACTION_VERIFICATION");
        h.reserve(false);int reads=h.f.f.http.calls.size();
        assertThat(adapter.nextAction(h.scope)).isNull();assertThat(h.inserts).isEqualTo(1);assertThat(h.f.f.http.calls).hasSize(reads);assertThat(calls.get()).isEqualTo(2);
        assertThat(selectionFailureStage(adapter)).isEqualTo("ACTION_VERIFICATION");
        assertThat(catchThrowable(()->adapter.nextAction(new SubscriptionConfirmationUseCase.Scope(h.scope.context(),UUID.randomUUID(),h.scope.operation())))).isInstanceOf(RuntimeException.class);
        assertThat(selectionFailureStage(adapter)).isEqualTo("ACTION_VERIFICATION");
    }
    @ParameterizedTest @ValueSource(ints={1,2,9})
    void actionGraphFailuresKeepExistingNormalizationAcrossReadyGraphReady(int response){
        var h=new Harness();var adapter=h.action();var failure=new UnreadableFailure();h.graph();after(h,response,()->{throw failure;});
        assertThat(catchThrowable(()->adapter.nextAction(h.scope))).isInstanceOf(RuntimeException.class).isNotSameAs(failure);
        assertThat(selectionFailureStage(adapter)).isEqualTo("ACTION_GRAPH");
        assertThat(h.f.f.verifications).isEqualTo(1);assertThat(h.f.f.http.calls).hasSize(response);assertThat(h.root).isNull();assertThat(h.inserts).isZero();
    }
    @Test void actionClientConstructionFailureBelongsToGraphWithoutReadingTheException(){
        var h=new Harness();var failure=new UnreadableFailure();
        var clients=new StripeClientFactory(){@Override public com.stripe.StripeClient create(StripeCredentials ignored){throw failure;}};
        var adapter=new StripeBillingClockActionAdapter(h.receipt,h.phases,h.roots,h.tokens,h.f.f.credentials,clients,h.f.f.verification,h.clock);
        assertThat(catchThrowable(()->adapter.nextAction(h.scope))).isInstanceOf(RuntimeException.class).isNotSameAs(failure);
        assertThat(selectionFailureStage(adapter)).isEqualTo("ACTION_GRAPH");
        assertThat(h.f.f.verifications).isEqualTo(1);assertThat(h.f.f.http.calls).isEmpty();assertThat(h.inserts).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"foreignFlat","missingNested","nullNested","foreignNested","nullParent","wrongParentType","missingProration"})
    void ownedActionLineGuardReportsAssociationWithoutIssuingRoot(String variant){
        var h=new Harness();prepareOpen(h.f);var line=h.f.line;var detail=line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details");
        switch(variant){
            case "foreignFlat"->line.addProperty("subscription","sub_foreign");
            case "missingNested"->detail.remove("subscription");case "nullNested"->detail.add("subscription",com.google.gson.JsonNull.INSTANCE);case "foreignNested"->detail.addProperty("subscription","sub_foreign");
            case "nullParent"->line.add("parent",com.google.gson.JsonNull.INSTANCE);case "wrongParentType"->line.getAsJsonObject("parent").addProperty("type","invoice_item_details");case "missingProration"->detail.remove("proration");
        }
        queueActionGraph(h);assertActionGraphRefusal(h,"ACTION_GRAPH_LINE_ASSOCIATION",5);
    }
    @ParameterizedTest @ValueSource(strings={"nullEndingBalance","missingEndingBalance","nonzeroEndingBalance","foreignLineAmount","offStripePaid","paymentType"})
    void ownedActionUnsupportedInvoiceReportsSettlementWithoutIssuingRoot(String variant){
        for(var indicator:List.of("omitted","null","zero"))for(var representation:List.of("exact","omitted","null")){
            var h=new Harness();prepareOpen(h.f);h.f.flatSubscription(representation);StripeBillingClockSnapshotTest.indicator(h.f,indicator);
            switch(variant){
                case "nullEndingBalance"->h.f.invoice.add("ending_balance",com.google.gson.JsonNull.INSTANCE);case "missingEndingBalance"->h.f.invoice.remove("ending_balance");case "nonzeroEndingBalance"->h.f.invoice.addProperty("ending_balance",1);
                case "foreignLineAmount"->h.f.line.addProperty("amount",999);case "offStripePaid"->h.f.invoice.addProperty("amount_paid_off_stripe",1);case "paymentType"->h.f.allocation.getAsJsonObject("payment").addProperty("type","payment_record");
            }
            queueActionGraph(h);assertActionGraphRefusal(h,"ACTION_GRAPH_INVOICE_SETTLEMENT_"+switch(variant){case "nullEndingBalance","missingEndingBalance","nonzeroEndingBalance"->"ENDING_BALANCE";case "foreignLineAmount"->"LINE_AMOUNT";case "offStripePaid"->"PAID_OFF_STRIPE";default->"OPEN_ALLOCATION_TYPE";},6);
        }
    }
    @ParameterizedTest @ValueSource(strings={"nullPaid","missingPaid","positivePaid","negativePaid","notDefault","nullDefault","wrongRequested","canceled"})
    void ownedActionAllocationGuardReportsAllocationBeforeIntentRead(String variant){
        for(var indicator:List.of("omitted","null","zero"))for(var representation:List.of("exact","omitted","null")){
            var h=new Harness();prepareOpen(h.f);h.f.flatSubscription(representation);StripeBillingClockSnapshotTest.indicator(h.f,indicator);
            switch(variant){
                case "nullPaid"->h.f.allocation.add("amount_paid",com.google.gson.JsonNull.INSTANCE);case "missingPaid"->h.f.allocation.remove("amount_paid");case "positivePaid"->h.f.allocation.addProperty("amount_paid",1);case "negativePaid"->h.f.allocation.addProperty("amount_paid",-1);
                case "notDefault"->h.f.allocation.addProperty("is_default",false);case "nullDefault"->h.f.allocation.add("is_default",com.google.gson.JsonNull.INSTANCE);case "wrongRequested"->h.f.allocation.addProperty("amount_requested",999);case "canceled"->h.f.allocation.addProperty("status","canceled");
            }
            queueActionGraph(h);assertActionGraphRefusal(h,"ACTION_GRAPH_PAYMENT_ALLOCATION",6);
        }
    }
    @ParameterizedTest @ValueSource(strings={"customer","invoiceOwner","allocationOwner","lineItem","period","piAmount","piCustomer","reverse"})
    void otherOwnedGraphGuardsRetainGenericFailure(String variant){
        for(var indicator:List.of("omitted","null","zero"))for(var representation:List.of("exact","omitted","null")){
            var h=new Harness();prepareOpen(h.f);h.f.flatSubscription(representation);StripeBillingClockSnapshotTest.indicator(h.f,indicator);int reads=6;
            switch(variant){
                case "customer"->{h.f.customer.addProperty("id","cus_foreign");reads=2;}
                case "invoiceOwner"->{h.f.invoice.getAsJsonObject("parent").getAsJsonObject("subscription_details").addProperty("subscription","sub_foreign");reads=4;}
                case "allocationOwner"->h.f.allocation.addProperty("invoice","in_foreign");case "lineItem"->{h.f.line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details").addProperty("subscription_item","si_foreign");reads=5;}
                case "period"->h.f.line.getAsJsonObject("period").addProperty("end",1702678401);
                case "piAmount"->{h.f.pi.addProperty("amount",999);reads=7;}case "piCustomer"->{h.f.pi.addProperty("customer","cus_foreign");reads=7;}
                case "reverse"->reads=8;
            }
            h.f.f.http.reply(h.f.f.clockJson());
            h.f.f.http.reply(h.f.customer.toString());h.f.f.http.reply(h.f.remote.toString());h.f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(h.f.invoice));h.f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(h.f.line));h.f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(h.f.allocation));h.f.f.http.reply(h.f.pi.toString());
            var reverse=h.f.allocation.deepCopy();if(variant.equals("reverse"))reverse.addProperty("id","inpay_foreign");h.f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(reverse));h.f.f.http.reply(h.f.f.clockJson());
            assertActionGraphRefusal(h,"ACTION_GRAPH",reads);
        }
    }
    @Test void detailedGraphFailureRemainsFirstThroughGenericFailureSuccessAndDuplicateRefusal(){
        var h=new Harness();var adapter=h.action();prepareOpen(h.f);h.f.allocation.remove("amount_paid");queueActionGraph(h);
        assertThat(catchThrowable(()->adapter.nextAction(h.scope))).isInstanceOf(RuntimeException.class);
        assertThat(selectionFailureStage(adapter)).isEqualTo("ACTION_GRAPH_PAYMENT_ALLOCATION");
        h.f.f.http.replies.clear();h.graph();after(h,2,()->{throw new UnreadableFailure();});
        assertThat(catchThrowable(()->adapter.nextAction(h.scope))).isInstanceOf(RuntimeException.class);
        assertThat(selectionFailureStage(adapter)).isEqualTo("ACTION_GRAPH_PAYMENT_ALLOCATION");
        h.f.f.http.replies.clear();h.graph();assertThat(adapter.nextAction(h.scope)).isNotNull();h.reserve(false);int reads=h.f.f.http.calls.size();
        assertThat(adapter.nextAction(h.scope)).isNull();assertThat(h.inserts).isEqualTo(1);assertThat(h.f.f.http.calls).hasSize(reads);
        assertThat(selectionFailureStage(adapter)).isEqualTo("ACTION_GRAPH_PAYMENT_ALLOCATION");
        assertThat(h.f.f.http.calls).allMatch(c->c.method().name().equals("GET"));
    }
    @Test void concurrentDetailedAndGenericFailuresKeepCallLocalGuardProvenance()throws Exception{
        var detailed=new Harness();var generic=new Harness();prepareOpen(detailed.f);StripeBillingClockSnapshotTest.indicator(detailed.f,"omitted");detailed.f.invoice.addProperty("ending_balance",1);queueActionGraph(detailed);generic.graph();
        var detailedEntered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        after(detailed,6,()->{detailedEntered.countDown();awaitRelease(release);});after(generic,2,()->{throw new UnreadableFailure();});
        var detailedAdapter=detailed.action();var genericAdapter=generic.action();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var pending=executor.submit(()->catchThrowable(()->detailedAdapter.nextAction(detailed.scope)));
            assertThat(detailedEntered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try{assertThat(executor.submit(()->catchThrowable(()->genericAdapter.nextAction(generic.scope))).get(5,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(RuntimeException.class);}
            finally{release.countDown();}
            assertThat(pending.get(5,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(RuntimeException.class);
        }
        assertThat(selectionFailureStage(detailedAdapter)).isEqualTo("ACTION_GRAPH_INVOICE_SETTLEMENT_ENDING_BALANCE");assertThat(selectionFailureStage(genericAdapter)).isEqualTo("ACTION_GRAPH");
        assertThat(detailed.root).isNull();assertThat(generic.root).isNull();assertThat(detailed.inserts+generic.inserts).isZero();
        assertThat(detailed.f.f.http.calls).allMatch(c->c.method().name().equals("GET"));assertThat(generic.f.f.http.calls).allMatch(c->c.method().name().equals("GET"));
    }
    @Test void reentrantFailureCannotLendItsTrustedGuardToTheCallingAction(){
        var outer=new Harness();var inner=new Harness();var outerAdapter=outer.action();var innerAdapter=inner.action();
        outer.graph();prepareOpen(inner.f);StripeBillingClockSnapshotTest.indicator(inner.f,"null");inner.f.invoice.addProperty("ending_balance",1);queueActionGraph(inner);
        after(outer,2,()->{
            assertThat(catchThrowable(()->innerAdapter.nextAction(inner.scope))).isInstanceOf(RuntimeException.class);
            throw new UnreadableFailure();
        });
        assertThat(catchThrowable(()->outerAdapter.nextAction(outer.scope))).isInstanceOf(RuntimeException.class);
        assertThat(selectionFailureStage(innerAdapter)).isEqualTo("ACTION_GRAPH_INVOICE_SETTLEMENT_ENDING_BALANCE");
        assertThat(selectionFailureStage(outerAdapter)).isEqualTo("ACTION_GRAPH");
        assertThat(outer.root).isNull();assertThat(inner.root).isNull();assertThat(outer.inserts+inner.inserts).isZero();
        assertThat(outer.f.f.http.calls).hasSize(2).allMatch(c->c.method().name().equals("GET"));
        assertThat(inner.f.f.http.calls).hasSize(6).allMatch(c->c.method().name().equals("GET"));
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"invoice,amount_paid_off_stripe,positive,PAID_OFF_STRIPE",
        "invoice,amount_paid_off_stripe,negative,PAID_OFF_STRIPE",
        "invoice,amount_overpaid,missing,OVERPAID",
        "invoice,amount_overpaid,null,OVERPAID",
        "invoice,amount_overpaid,positive,OVERPAID",
        "invoice,amount_overpaid,negative,OVERPAID",
        "invoice,starting_balance,missing,STARTING_BALANCE",
        "invoice,starting_balance,null,STARTING_BALANCE",
        "invoice,starting_balance,positive,STARTING_BALANCE",
        "invoice,starting_balance,negative,STARTING_BALANCE",
        "invoice,ending_balance,missing,ENDING_BALANCE",
        "invoice,ending_balance,null,ENDING_BALANCE",
        "invoice,ending_balance,positive,ENDING_BALANCE",
        "invoice,ending_balance,negative,ENDING_BALANCE",
        "invoice,pre_payment_credit_notes_amount,missing,PRE_CREDIT_NOTES",
        "invoice,pre_payment_credit_notes_amount,null,PRE_CREDIT_NOTES",
        "invoice,pre_payment_credit_notes_amount,positive,PRE_CREDIT_NOTES",
        "invoice,pre_payment_credit_notes_amount,negative,PRE_CREDIT_NOTES",
        "invoice,post_payment_credit_notes_amount,missing,POST_CREDIT_NOTES",
        "invoice,post_payment_credit_notes_amount,null,POST_CREDIT_NOTES",
        "invoice,post_payment_credit_notes_amount,positive,POST_CREDIT_NOTES",
        "invoice,post_payment_credit_notes_amount,negative,POST_CREDIT_NOTES",
        "invoice,discounts,missing,INVOICE_DISCOUNTS",
        "invoice,discounts,null,INVOICE_DISCOUNTS",
        "invoice,discounts,list,INVOICE_DISCOUNTS",
        "invoice,total_discount_amounts,missing,INVOICE_DISCOUNT_AMOUNTS",
        "invoice,total_discount_amounts,null,INVOICE_DISCOUNT_AMOUNTS",
        "invoice,total_discount_amounts,list,INVOICE_DISCOUNT_AMOUNTS",
        "invoice,total_pretax_credit_amounts,missing,INVOICE_PRETAX_CREDITS",
        "invoice,total_pretax_credit_amounts,null,INVOICE_PRETAX_CREDITS",
        "invoice,total_pretax_credit_amounts,list,INVOICE_PRETAX_CREDITS",
        "line,discount_amounts,missing,LINE_DISCOUNT_AMOUNTS",
        "line,discount_amounts,null,LINE_DISCOUNT_AMOUNTS",
        "line,discount_amounts,list,LINE_DISCOUNT_AMOUNTS",
        "line,pretax_credit_amounts,missing,LINE_PRETAX_CREDITS",
        "line,pretax_credit_amounts,null,LINE_PRETAX_CREDITS",
        "line,pretax_credit_amounts,list,LINE_PRETAX_CREDITS",
        "invoice,total,positive,TOTAL",
        "invoice,total,negative,TOTAL",
        "invoice,total,null,TOTAL",
        "invoice,total,missing,TOTAL",
        "invoice,subtotal,positive,SUBTOTAL",
        "invoice,subtotal,negative,SUBTOTAL",
        "invoice,subtotal,null,SUBTOTAL",
        "invoice,subtotal,missing,SUBTOTAL",
        "line,amount,positive,LINE_AMOUNT",
        "line,amount,negative,LINE_AMOUNT",
        "invoice,amount_paid,positive,OPEN_PAID",
        "invoice,amount_paid,negative,OPEN_PAID",
        "invoice,amount_paid,null,OPEN_PAID",
        "invoice,amount_paid,missing,OPEN_PAID",
        "invoice,amount_remaining,positive,OPEN_REMAINING",
        "invoice,amount_remaining,negative,OPEN_REMAINING",
        "invoice,amount_remaining,null,OPEN_REMAINING",
        "invoice,amount_remaining,missing,OPEN_REMAINING",
        "invoice,collection_method,missing,COLLECTION_METHOD",
        "invoice,collection_method,null,COLLECTION_METHOD",
        "allocation,status,missing,OPEN_ALLOCATION_STATUS",
        "allocation,status,null,OPEN_ALLOCATION_STATUS",
        "allocation,payment,missing,OPEN_ALLOCATION_PAYMENT",
        "allocation,payment,null,OPEN_ALLOCATION_PAYMENT",
        "payment,type,missing,OPEN_ALLOCATION_TYPE",
        "payment,type,null,OPEN_ALLOCATION_TYPE",
        "payment,payment_intent,missing,OPEN_ALLOCATION_INTENT",
        "payment,payment_intent,null,OPEN_ALLOCATION_INTENT",
        "detail,proration,true,PRORATION_FLAG",
        "invoice,billing_reason,update,PRORATION_KIND",
        "invoice,collection_method,manual,COLLECTION_METHOD",
        "invoice,amount_due,zero,OPEN_DUE_POSITIVE",
        "invoice,amount_due,catalog,OPEN_DUE_CATALOG",
        "allocation,status,paid,OPEN_ALLOCATION_STATUS",
        "allocation,payment,null,OPEN_ALLOCATION_PAYMENT",
        "payment,type,record,OPEN_ALLOCATION_TYPE",
        "payment,payment_intent,null,OPEN_ALLOCATION_INTENT"})
    void settlementPredicateRefusalKeepsExactOrderAndNoRoot(String target,String field,String variant,String reason){
        for(var indicator:List.of("omitted","null","zero")){
        var h=new Harness();prepareOpen(h.f);StripeBillingClockSnapshotTest.indicator(h.f,indicator);
        var value=switch(target){case "invoice"->h.f.invoice;case "line"->h.f.line;case "detail"->h.f.line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details");case "allocation"->h.f.allocation;case "payment"->h.f.allocation.getAsJsonObject("payment");default->throw new AssertionError();};
        switch(variant){
            case "missing"->value.remove(field);case "null"->value.add(field,com.google.gson.JsonNull.INSTANCE);
            case "positive"->value.addProperty(field,1);case "negative"->value.addProperty(field,-1);case "zero"->value.addProperty(field,0);case "catalog"->value.addProperty(field,999);
            case "list"->value.add(field,com.google.gson.JsonParser.parseString(field.equals("discounts")?"[\"di_INERT\"]":"[{}]"));
            case "true"->value.addProperty(field,true);case "update"->value.addProperty(field,"subscription_update");case "manual"->value.addProperty(field,"send_invoice");case "paid"->value.addProperty(field,"paid");case "record"->value.addProperty(field,"payment_record");default->throw new AssertionError();
        }
        // These earlier equalities must pass before the OPEN due predicate is reached.
        if(field.equals("amount_due")){h.f.invoice.add(field,value.get(field));h.f.invoice.add("total",value.get(field));h.f.invoice.add("subtotal",value.get(field));h.f.line.add("amount",value.get(field));}
        var expectedReason=field.equals("amount_due")&&variant.equals("zero")&&!indicator.equals("zero")?"PAID_OFF_STRIPE":reason;
        queueActionGraph(h);assertActionGraphRefusal(h,field.equals("billing_reason")?"ACTION_GRAPH":"ACTION_GRAPH_INVOICE_SETTLEMENT_"+expectedReason,6);
        assertThat(h.f.f.http.calls.stream().map(c->c.url().getPath()).toList()).containsExactly("/v1/test_helpers/test_clocks/clock_owned","/v1/customers/cus_owned","/v1/subscriptions/sub_owned","/v1/invoices","/v1/invoices/in_owned/lines","/v1/invoice_payments");
        }
    }
    @ParameterizedTest @ValueSource(strings={"omitted","null","positive","negative"})
    void unavailableIndicatorReachesRealNextFailureButKnownNonzeroRemainsFirst(String indicator){
        var h=new Harness();prepareOpen(h.f);StripeBillingClockSnapshotTest.indicator(h.f,indicator);h.f.invoice.addProperty("ending_balance",1);queueActionGraph(h);
        assertActionGraphRefusal(h,"ACTION_GRAPH_INVOICE_SETTLEMENT_"+(List.of("positive","negative").contains(indicator)?"PAID_OFF_STRIPE":"ENDING_BALANCE"),6);
    }
    @ParameterizedTest @ValueSource(strings={"draft","uncollectible","void","zero","trial","proration","renewal"})
    void unavailableIndicatorDoesNotCreatePermissionOutsideActualPositiveInitialOpen(String scope){
        for(var indicator:List.of("omitted","null")){
            var h=new Harness();prepareOpen(h.f);StripeBillingClockSnapshotTest.indicator(h.f,indicator);
            switch(scope){case "draft","uncollectible","void"->h.f.invoice.addProperty("status",scope);case "zero"->{for(var field:List.of("amount_due","amount_paid","amount_remaining","total","subtotal"))h.f.invoice.addProperty(field,0);h.f.line.addProperty("amount",0);}case "trial"->h.f.remote.addProperty("trial_end",1702678400);case "proration"->h.f.line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details").addProperty("proration",true);case "renewal"->h.f.invoice.addProperty("billing_reason","subscription_cycle");}
            queueActionGraph(h);var adapter=h.action();assertThat(catchThrowable(()->adapter.nextAction(h.scope))).isInstanceOf(RuntimeException.class);assertThat(h.root).isNull();assertThat(h.inserts).isZero();assertThat(h.f.f.http.calls).allMatch(c->c.method().name().equals("GET"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"{}","[]","true","\"garbage\""})
    void malformedPresentIndicatorCannotCollapseIntoAcceptedUnavailable(String json){
        var h=new Harness();prepareOpen(h.f);h.f.invoice.add("amount_paid_off_stripe",com.google.gson.JsonParser.parseString(json));queueActionGraph(h);
        var adapter=h.action();assertThat(catchThrowable(()->adapter.nextAction(h.scope))).isInstanceOf(RuntimeException.class);assertThat(h.root).isNull();assertThat(h.inserts).isZero();assertThat(h.f.f.http.calls).hasSize(4).allMatch(c->c.method().name().equals("GET"));
    }
    @ParameterizedTest @ValueSource(strings={"omitted","null","zero"})
    void unavailableIndicatorDoesNotChangeUnknownOrNullStatusFailure(String indicator){
        for(var status:List.of("null","future")){
            var h=new Harness();prepareOpen(h.f);StripeBillingClockSnapshotTest.indicator(h.f,indicator);
            if(status.equals("null"))h.f.invoice.add("status",com.google.gson.JsonNull.INSTANCE);else h.f.invoice.addProperty("status",status);
            queueActionGraph(h);assertActionGraphRefusal(h,"ACTION_GRAPH",6);
        }
    }
    @ParameterizedTest @ValueSource(strings={"action","material","preconfirm"})
    void stoppedReceiptAuthorityRefusesUnavailableIndicatorBeforeAnyProviderRead(String phase){
        for(var indicator:List.of("omitted","null")){
            var h=new Harness();StripeBillingClockSnapshotTest.indicator(h.f,indicator);
            if(!phase.equals("action")){h.root();h.reserve(phase.equals("preconfirm"));}
            h.stopped=true;
            if(phase.equals("action")){assertThatThrownBy(()->h.action().nextAction(h.scope)).isInstanceOf(RuntimeException.class);assertThat(h.root).isNull();}
            else if(phase.equals("material"))assertThatThrownBy(()->h.provider().read(h.f.lookup(),h.root,null)).isInstanceOf(RuntimeException.class);
            else assertThat(h.provider().confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT").state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);
            assertThat(h.inserts).isZero();assertThat(h.f.f.http.calls).isEmpty();
        }
    }
    @Test void settlementFirstPredicateWinsWithoutLeakingIntoLaterFailure(){
        var h=new Harness();prepareOpen(h.f);h.f.invoice.addProperty("ending_balance",1);h.f.invoice.addProperty("total",999);queueActionGraph(h);
        var adapter=h.action();assertThat(catchThrowable(()->adapter.nextAction(h.scope))).isInstanceOf(RuntimeException.class);
        assertThat(selectionFailureStage(adapter)).isEqualTo("ACTION_GRAPH_INVOICE_SETTLEMENT_ENDING_BALANCE");
        h.f.f.http.replies.clear();h.f.invoice.addProperty("ending_balance",0);h.f.invoice.addProperty("total",1000);h.graph();after(h,2,()->{throw new UnreadableFailure();});
        assertThat(catchThrowable(()->adapter.nextAction(h.scope))).isInstanceOf(RuntimeException.class);
        assertThat(selectionFailureStage(adapter)).isEqualTo("ACTION_GRAPH_INVOICE_SETTLEMENT_ENDING_BALANCE");
        h.f.f.http.replies.clear();h.graph();assertThat(adapter.nextAction(h.scope)).isNotNull();h.reserve(false);int reads=h.f.f.http.calls.size();assertThat(adapter.nextAction(h.scope)).isNull();assertThat(h.f.f.http.calls).hasSize(reads);
        assertThat(selectionFailureStage(adapter)).isEqualTo("ACTION_GRAPH_INVOICE_SETTLEMENT_ENDING_BALANCE");
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"cash,ACTION_OPEN_CASH,9","paidPlain,ENDING_BALANCE,6","paidEligibility,SNAPSHOT_UNSUPPORTED,6","captureNull,SNAPSHOT_UNSUPPORTED,6","captureException,GENERIC,7","unknown,GENERIC,6","nullStatus,GENERIC,6","void,SNAPSHOT_UNSUPPORTED,6","empty,GENERIC,6","draft,GENERIC,6","uncollectible,GENERIC,6","lateOwnership,GENERIC,6"})
    void settlementAlternativesCommitOnlyAtRejectingBoundary(String variant,String reason,int reads){
        var h=new Harness();prepareOpen(h.f);
        if(List.of("cash","paidPlain","paidEligibility","captureNull","captureException").contains(variant)){
            h.f.invoice.addProperty("status","paid");h.f.invoice.addProperty("amount_paid",1000);h.f.invoice.addProperty("amount_remaining",0);
            h.f.allocation.addProperty("status","paid");h.f.allocation.addProperty("amount_paid",1000);h.f.allocation.add("status_transitions",StripeBillingOwnedSubscriptionFixture.json("{\"paid_at\":1700000011}"));h.f.pi.addProperty("status","succeeded");h.f.pi.addProperty("amount_received",1000);h.f.pi.addProperty("latest_charge","ch_owned");
        }
        switch(variant){
            case "paidPlain"->h.f.invoice.addProperty("ending_balance",1);case "paidEligibility"->h.f.invoice.addProperty("amount_remaining",1);case "captureNull"->h.f.allocation.addProperty("amount_paid",999);
            case "captureException"->h.f.pi.addProperty("customer","cus_foreign");case "unknown"->h.f.invoice.addProperty("status","future");case "nullStatus"->h.f.invoice.add("status",com.google.gson.JsonNull.INSTANCE);case "void"->h.f.invoice.addProperty("status","void");
            case "draft","uncollectible"->h.f.invoice.addProperty("status",variant);case "lateOwnership"->{h.f.line.getAsJsonObject("period").addProperty("end",1702678401);h.f.invoice.addProperty("ending_balance",1);}default->{}
        }
        h.f.f.http.reply(h.f.f.clockJson());h.f.f.http.reply(h.f.customer.toString());h.f.f.http.reply(h.f.remote.toString());h.f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(h.f.invoice));h.f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(h.f.line));h.f.f.http.reply(variant.equals("empty")||variant.equals("void")?StripeBillingOwnedSubscriptionFixture.page():StripeBillingOwnedSubscriptionFixture.page(h.f.allocation));
        h.f.f.http.reply(h.f.pi.toString());h.f.f.http.reply(h.f.charge.toString());h.f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(h.f.allocation));
        assertActionGraphRefusal(h,reason.equals("GENERIC")?"ACTION_GRAPH":reason.equals("ALLOCATION")?"ACTION_GRAPH_PAYMENT_ALLOCATION":"ACTION_GRAPH_INVOICE_SETTLEMENT_"+reason,reads);
    }
    @Test void earlierNullAndNegativeAmountAndLineGuardsStayGeneric(){
        for(String field:List.of("amount_due","amount"))for(String variant:List.of("missing","null","negative")){
            if(field.equals("amount")&&variant.equals("negative"))continue;
            var h=new Harness();prepareOpen(h.f);var value=field.equals("amount")?h.f.line:h.f.invoice;
            switch(variant){case "missing"->value.remove(field);case "null"->value.add(field,com.google.gson.JsonNull.INSTANCE);case "negative"->value.addProperty(field,-1);}
            queueActionGraph(h);assertActionGraphRefusal(h,"ACTION_GRAPH",5);
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"balance-list,PAID_OFF_STRIPE","list-total,INVOICE_DISCOUNTS","total-subtotal,TOTAL","paid-remaining,OPEN_PAID","allocation-status-payment,OPEN_ALLOCATION_STATUS","allocation-payment-type,OPEN_ALLOCATION_PAYMENT"})
    void firstSettlementFailureRetainsExpressionPrecedence(String variant,String reason){
        var h=new Harness();prepareOpen(h.f);
        switch(variant){
            case "balance-list"->{h.f.invoice.addProperty("amount_paid_off_stripe",1);h.f.invoice.remove("discounts");}
            case "list-total"->{h.f.invoice.remove("discounts");h.f.invoice.addProperty("total",999);}
            case "total-subtotal"->{h.f.invoice.addProperty("total",999);h.f.invoice.addProperty("subtotal",999);}
            case "paid-remaining"->{h.f.invoice.addProperty("amount_paid",1);h.f.invoice.addProperty("amount_remaining",999);}
            case "allocation-status-payment"->{h.f.allocation.addProperty("status","paid");h.f.allocation.remove("payment");}
            case "allocation-payment-type"->h.f.allocation.remove("payment");
        }
        queueActionGraph(h);assertActionGraphRefusal(h,"ACTION_GRAPH_INVOICE_SETTLEMENT_"+reason,6);
    }
    @Test void allocationEncounterOrderRetainsFirstActualFailure(){
        var h=new Harness();prepareOpen(h.f);var second=h.f.allocation.deepCopy();second.addProperty("id","inpay_second");second.remove("payment");h.f.allocation.getAsJsonObject("payment").addProperty("type","payment_record");
        h.f.f.http.reply(h.f.f.clockJson());h.f.f.http.reply(h.f.customer.toString());h.f.f.http.reply(h.f.remote.toString());h.f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(h.f.invoice));h.f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(h.f.line));h.f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(h.f.allocation,second));
        assertActionGraphRefusal(h,"ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_TYPE",6);
    }
    static void queueActionGraph(Harness h){h.f.f.http.reply(h.f.f.clockJson());queueGraph(h.f);h.f.f.http.reply(h.f.f.clockJson());}
    static void assertActionGraphRefusal(Harness h,String expected,int reads){
        var adapter=h.action();var failure=catchThrowable(()->adapter.nextAction(h.scope));
        assertThat(failure).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
        assertThat(selectionFailureStage(adapter)).isEqualTo(expected);assertThat(h.root).isNull();assertThat(h.inserts).isZero();
        assertThat(h.f.f.http.calls).hasSize(reads).allMatch(c->c.method().name().equals("GET"));
    }
    @ParameterizedTest @ValueSource(strings={"locked","reserve"})
    void actionReservationFailuresKeepIdentityAfterFullGraphWithoutIssuingRoot(String boundary){
        var h=new Harness();var adapter=h.action();var failure=new UnreadableFailure();h.graph();
        after(h,9,()->{if(boundary.equals("locked"))h.authorityFailure=failure;else h.reserveFailure=failure;});
        assertThat(catchThrowable(()->adapter.nextAction(h.scope))).isSameAs(failure);
        assertThat(selectionFailureStage(adapter)).isEqualTo("ACTION_RESERVATION");
        assertThat(h.f.f.http.calls).hasSize(9);assertThat(h.f.f.verifications).isEqualTo(1);assertThat(h.root).isNull();assertThat(h.inserts).isZero();
    }
    @Test void concurrentCallsKeepTheirOwnCheckpointAndTheFirstCaughtFailure()throws Exception{
        var h=new Harness();var verificationFailure=new UnreadableFailure();var authorityFailure=new UnreadableFailure();
        var verificationEntered=new java.util.concurrent.CountDownLatch(1);var authorityEntered=new java.util.concurrent.CountDownLatch(1);
        var releaseVerification=new java.util.concurrent.CountDownLatch(1);var releaseAuthority=new java.util.concurrent.CountDownLatch(1);
        var adapter=new StripeBillingClockActionAdapter(h.receipt,h.phases,h.roots,h.tokens,h.f.f.credentials,h.f.f.clients,a->{
            verificationEntered.countDown();awaitRelease(releaseVerification);throw verificationFailure;
        },h.clock);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var verification=executor.submit(()->catchThrowable(()->adapter.nextAction(h.scope)));
            assertThat(verificationEntered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            h.authorityFailure=authorityFailure;h.authorityEffect=()->{authorityEntered.countDown();awaitRelease(releaseAuthority);};
            var authority=executor.submit(()->catchThrowable(()->adapter.nextAction(h.scope)));
            try{
                assertThat(authorityEntered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                // The later call is still inside authority when the first call fails verification.
                releaseVerification.countDown();assertThat(verification.get(5,java.util.concurrent.TimeUnit.SECONDS)).isSameAs(verificationFailure);
                assertThat(selectionFailureStage(adapter)).isEqualTo("ACTION_VERIFICATION");
            }finally{releaseVerification.countDown();releaseAuthority.countDown();}
            assertThat(authority.get(5,java.util.concurrent.TimeUnit.SECONDS)).isSameAs(authorityFailure);
        }
        assertThat(selectionFailureStage(adapter)).isEqualTo("ACTION_VERIFICATION");
        assertThat(h.f.f.http.calls).isEmpty();assertThat(h.inserts).isZero();
    }
    static void awaitRelease(java.util.concurrent.CountDownLatch release){
        try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("test release timed out");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }
    @Test void disabledActionReturnsNullWithoutInventingFailureOrCallingCollaborators(){
        var h=new Harness();var disabled=new StripeBillingGrantTokens(false,null,null);
        var adapter=new StripeBillingClockActionAdapter(h.receipt,h.phases,h.roots,disabled,h.f.f.credentials,h.f.f.clients,h.f.f.verification,h.clock);
        assertThat(adapter.nextAction(null)).isNull();assertThat(selectionFailureStage(adapter)).isNull();
        assertThat(h.guards).isZero();assertThat(h.f.f.verifications).isZero();assertThat(h.f.f.http.calls).isEmpty();assertThat(h.inserts).isZero();
    }
    static String selectionFailureStage(StripeBillingClockActionAdapter adapter){
        var accessor=Arrays.stream(StripeBillingClockActionAdapter.class.getMethods()).filter(m->m.getName().equals("selectionFailureStage")&&m.getParameterCount()==0).findFirst();
        assertThat(accessor).as("finite first action-selection failure accessor").isPresent();
        try{return (String)accessor.orElseThrow().invoke(adapter);}catch(ReflectiveOperationException failure){throw new AssertionError("selection failure accessor failed");}
    }
    static final class UnreadableFailure extends RuntimeException {
        @Override public String getMessage(){throw new AssertionError("exception message read");}
        @Override public String getLocalizedMessage(){throw new AssertionError("exception localized message read");}
        @Override public synchronized Throwable getCause(){throw new AssertionError("exception cause read");}
        @Override public StackTraceElement[] getStackTrace(){throw new AssertionError("exception stack read");}
        @Override public String toString(){throw new AssertionError("exception string read");}
    }
    @Test void persistedOriginalConsumedAttemptConfirmsOnceWithStrictSdkRequest()throws Exception{
        var h=new Harness();h.root();h.reserve(true);h.graph();h.f.f.http.reply(h.success());h.f.f.http.reply(h.f.f.clockJson());
        var type=type("StripeBillingClockConfirmationAdapter");
        var provider=(SubscriptionConfirmationProvider)type.getConstructors()[0].newInstance(h.receipt,h.scope,h.phases,h.roots,h.confirmations,h.tokens,h.f.f.credentials,h.f.f.clients,h.f.f.verification,h.clock);
        assertThat(provider.confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT").state()).isEqualTo(SubscriptionConfirmationAttempt.State.OBSERVE);
        var posts=h.f.f.http.calls.stream().filter(c->c.method().name().equals("POST")).toList();assertThat(posts).hasSize(1);
        assertThat(posts.getFirst().url().getPath()).isEqualTo("/v1/payment_intents/pi_owned/confirm");
        assertThat(posts.getFirst().params()).containsOnlyKeys("confirmation_token","use_stripe_sdk").containsEntry("confirmation_token","ctoken_INERT").containsEntry("use_stripe_sdk",true);
        assertThat(posts.getFirst().headers().map().get("Idempotency-Key")).containsExactly(h.attempt.providerKey());
        assertThat(posts.getFirst().headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");assertThat(posts.getFirst().options().getMaxNetworkRetries()).isZero();
    }
    @Test void actualServiceClaimDispatchesOnceAndSameRequestReplayNeverPostsAgain(){
        var h=new Harness();h.root();var provider=h.provider();
        var ports=StripeBillingInertSdkFixture.port(org.springframework.beans.factory.ObjectProvider.class,c->provider);
        var service=new SubscriptionConfirmationService(h.phases,h.roots,h.confirmations,ports,null,h.clock,h.receipt);
        h.graph();var selected=service.select(h.scope,h.tokens.token("v1",h.root.descriptor()));
        h.graph();h.graph();h.f.f.http.reply(h.success());h.f.f.http.reply(h.f.f.clockJson());
        var confirmed=service.confirm(h.scope,selected.attemptId(),selected.capability(),"ctoken_INERT");
        assertThat(confirmed.status().state()).isEqualTo(SubscriptionConfirmationAttempt.State.OBSERVE);
        assertThat(service.confirm(h.scope,selected.attemptId(),selected.capability(),"ctoken_INERT").status().state()).isEqualTo(SubscriptionConfirmationAttempt.State.OBSERVE);
        assertThat(h.f.f.http.calls.stream().filter(c->c.method().name().equals("POST"))).hasSize(1);
        assertThatThrownBy(()->service.retry(h.scope,selected.attemptId())).isInstanceOf(RuntimeException.class);
    }
    @ParameterizedTest @ValueSource(strings={"object","id","customer","livemode","amount","currency","received","capture","confirmation","cards","next","missingAction","missingAmount","missingConfirmation","missingStatus","nullReceived","stringAmount","decimalAmount","stringMode","processing","canceled","requires_confirmation","requires_capture","requires_payment_method","unknown","sdkMissing","sdkEmpty","sdkRedirect","sdkReceived","sdkMissingType","disconnect","402","500","malformed"})
    void uncertainForeignOrMalformedResponseNeverGrantsObservationOrRetry(String bad){
        var h=new Harness();h.root();h.reserve(true);h.graph();var r=StripeBillingOwnedSubscriptionFixture.json(h.success());
        switch(bad){
            case "object"->r.addProperty("object","charge");case "id"->r.addProperty("id","pi_foreign");case "customer"->r.addProperty("customer","cus_foreign");case "livemode"->r.addProperty("livemode",true);
            case "amount"->r.addProperty("amount",999);case "currency"->r.addProperty("currency","usd");case "received"->r.addProperty("amount_received",999);
            case "capture"->r.addProperty("capture_method","manual");case "confirmation"->r.addProperty("confirmation_method","manual");case "cards"->r.add("payment_method_types",com.google.gson.JsonParser.parseString("[\"card\",\"link\"]"));
            case "next"->r.add("next_action",StripeBillingOwnedSubscriptionFixture.json("{\"type\":\"use_stripe_sdk\",\"use_stripe_sdk\":{\"opaque\":true}}"));
            case "missingAction"->r.remove("next_action");case "missingAmount"->r.remove("amount");case "missingConfirmation"->r.remove("confirmation_method");case "missingStatus"->r.remove("status");case "nullReceived"->r.add("amount_received",com.google.gson.JsonNull.INSTANCE);
            case "stringAmount"->r.addProperty("amount","1000");case "decimalAmount"->r.addProperty("amount",1000.5);case "stringMode"->r.addProperty("livemode","false");
            case "sdkMissing","sdkEmpty","sdkRedirect","sdkReceived","sdkMissingType"->{r.addProperty("status","requires_action");r.addProperty("amount_received",bad.equals("sdkReceived")?1000:0);var a=StripeBillingOwnedSubscriptionFixture.json("{\"type\":\"use_stripe_sdk\",\"use_stripe_sdk\":{\"opaque\":true}}");if(bad.equals("sdkMissing"))a.remove("use_stripe_sdk");if(bad.equals("sdkEmpty"))a.add("use_stripe_sdk",StripeBillingOwnedSubscriptionFixture.json("{}"));if(bad.equals("sdkRedirect"))a.addProperty("type","redirect_to_url");if(bad.equals("sdkMissingType"))a.remove("type");r.add("next_action",a);}
            case "disconnect","402","500","malformed"->{}default->r.addProperty("status",bad);
        }
        switch(bad){case "disconnect"->h.f.f.http.disconnect();case "402"->h.f.f.http.error(402);case "500"->h.f.f.http.error(500);case "malformed"->h.f.f.http.reply("{");default->h.f.f.http.reply(r.toString());}
        h.f.f.http.reply(h.f.f.clockJson());var result=h.provider().confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT");
        assertThat(result.state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(result.receipt()).isNull();
        assertThat(h.f.f.http.calls.stream().filter(c->c.method().name().equals("POST"))).hasSize(1);
        assertThat(result.toString()).doesNotContain("ctoken_INERT","secret","synthetic");
    }
    @Test void requiresActionUsesOpaqueNonemptySdkMapWithoutInspectingItsInternals(){
        var h=new Harness();h.root();h.reserve(true);h.graph();var r=StripeBillingOwnedSubscriptionFixture.json(h.success());r.addProperty("status","requires_action");r.addProperty("amount_received",0);
        r.add("next_action",StripeBillingOwnedSubscriptionFixture.json("{\"type\":\"use_stripe_sdk\",\"use_stripe_sdk\":{\"future_sdk_field\":{\"anything\":[null,42,true]}}}"));h.f.f.http.reply(r.toString());h.f.f.http.reply(h.f.f.clockJson());
        var result=h.provider().confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT");assertThat(result.state()).isEqualTo(SubscriptionConfirmationAttempt.State.REQUIRES_ACTION);assertThat(result.receipt()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"customerClock","subscriptionClock","invoiceClock","created","item","periodStart","periodEnd","customer","allocationInvoice","allocationDefault","allocationPaid","allocationRequested","allocationMissingPaid","allocationCanceled","allocationSecond","reverse","piId","piCustomer","piAmount","piCurrency","piLive","piObject","piReceived","piConfirmation","piCapture","piStatus","piCards","piLatest","secretMissing","secretForeign","secretOversize","invoiceSecret","invoicePaid","invoiceSecond","invoiceAmount","invoiceCurrency","lineCurrency","lineAmountMissing","lineAmountNull","invoicePaidNull","invoiceRemainingNull","invoiceTotalNull","invoiceSubtotalNull","startingBalanceNull","allocationRequestedNull","allocationCurrency","piAmountNull","piReceivedNull","piCurrencyNull"})
    void originalGraphSubstitutionNeverReleasesOwnedSecret(String bad){
        for(var indicator:List.of("omitted","null","zero"))for(var representation:List.of("exact","omitted","null")){
            var f=new StripeBillingOwnedSubscriptionFixture();prepareOpen(f);f.flatSubscription(representation);StripeBillingClockSnapshotTest.indicator(f,indicator);
            switch(bad){
                case "invoiceAmount"->f.invoice.add("amount_due",com.google.gson.JsonNull.INSTANCE);case "invoiceCurrency"->f.invoice.addProperty("currency","usd");case "lineCurrency"->f.line.addProperty("currency","usd");case "lineAmountMissing"->f.line.remove("amount");case "lineAmountNull"->f.line.add("amount",com.google.gson.JsonNull.INSTANCE);
                case "invoicePaidNull"->f.invoice.add("amount_paid",com.google.gson.JsonNull.INSTANCE);case "invoiceRemainingNull"->f.invoice.add("amount_remaining",com.google.gson.JsonNull.INSTANCE);case "invoiceTotalNull"->f.invoice.add("total",com.google.gson.JsonNull.INSTANCE);case "invoiceSubtotalNull"->f.invoice.add("subtotal",com.google.gson.JsonNull.INSTANCE);case "startingBalanceNull"->f.invoice.add("starting_balance",com.google.gson.JsonNull.INSTANCE);
                case "allocationRequestedNull"->f.allocation.add("amount_requested",com.google.gson.JsonNull.INSTANCE);case "allocationCurrency"->f.allocation.addProperty("currency","usd");case "piAmountNull"->f.pi.add("amount",com.google.gson.JsonNull.INSTANCE);case "piReceivedNull"->f.pi.add("amount_received",com.google.gson.JsonNull.INSTANCE);case "piCurrencyNull"->f.pi.add("currency",com.google.gson.JsonNull.INSTANCE);
                case "customerClock"->f.customer.addProperty("test_clock","clock_other");case "subscriptionClock"->f.remote.addProperty("test_clock","clock_other");case "invoiceClock"->f.invoice.addProperty("test_clock","clock_other");
                case "created"->f.remote.addProperty("created",43);case "item"->f.item().addProperty("id","si_other");case "periodStart"->f.item().addProperty("current_period_start",1699999901);case "periodEnd"->f.item().addProperty("current_period_end",1702678401);case "customer"->f.remote.addProperty("customer","cus_other");
                case "allocationInvoice"->f.allocation.addProperty("invoice","in_other");case "allocationDefault"->f.allocation.addProperty("is_default",false);case "allocationPaid"->f.allocation.addProperty("amount_paid",1);case "allocationRequested"->f.allocation.addProperty("amount_requested",999);case "allocationMissingPaid"->f.allocation.remove("amount_paid");case "allocationCanceled"->f.allocation.addProperty("status","canceled");
                case "piId"->f.pi.addProperty("id","pi_other");case "piCustomer"->f.pi.addProperty("customer","cus_other");case "piAmount"->f.pi.addProperty("amount",999);case "piCurrency"->f.pi.addProperty("currency","usd");case "piLive"->f.pi.addProperty("livemode",true);case "piObject"->f.pi.addProperty("object","charge");case "piReceived"->f.pi.addProperty("amount_received",1);case "piConfirmation"->f.pi.addProperty("confirmation_method","manual");case "piCapture"->f.pi.addProperty("capture_method","manual");case "piStatus"->f.pi.addProperty("status","requires_action");case "piCards"->f.pi.add("payment_method_types",com.google.gson.JsonParser.parseString("[\"card\",\"link\"]"));
                case "piLatest"->f.pi.addProperty("latest_charge","ch_owned");case "secretMissing"->f.pi.remove("client_secret");case "secretForeign"->f.pi.addProperty("client_secret","pi_other_secret_CANARY");case "secretOversize"->f.pi.addProperty("client_secret","pi_owned_secret_"+"x".repeat(497));case "invoiceSecret"->f.invoice.add("confirmation_secret",StripeBillingOwnedSubscriptionFixture.json("{\"type\":\"payment_intent\",\"client_secret\":\"pi_owned_secret_OTHER\"}"));case "invoicePaid"->f.invoice.addProperty("status","paid");default->{}
            }
            var second=f.allocation.deepCopy();second.addProperty("id","inpay_second");var invoice2=f.invoice.deepCopy();invoice2.addProperty("id","in_second");
            f.f.http.reply(f.customer.toString());f.f.http.reply(f.remote.toString());f.f.http.reply(bad.equals("invoiceSecond")?StripeBillingOwnedSubscriptionFixture.page(f.invoice,invoice2):StripeBillingOwnedSubscriptionFixture.page(f.invoice));
            f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(f.line));f.f.http.reply(bad.equals("allocationSecond")?StripeBillingOwnedSubscriptionFixture.page(f.allocation,second):StripeBillingOwnedSubscriptionFixture.page(f.allocation));f.f.http.reply(f.pi.toString());f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(bad.equals("reverse")?second:f.allocation));
            assertThatThrownBy(()->StripeBillingSnapshotMapper.retrieveOwnedPaymentAction(f.f.clients.create(null),f.ordinary(),new StripeBillingSnapshotMapper.OwnedInitial(f.receipt()),f.utc)).isInstanceOf(RuntimeException.class).hasMessageNotContaining("secret_CANARY");
            if(bad.equals("piLatest"))assertThat(f.f.http.calls.stream().map(c->c.url().getPath())).noneMatch(path->path.startsWith("/v1/charges"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"before","afterClock","afterGraph","afterPost","afterPostClock"})
    void revokedSessionOrPostIoPolicyDriftNeverPublishesOutcome(String when){
        for(var indicator:List.of("omitted","null","zero"))for(var representation:List.of("exact","omitted","null")){
            var h=new Harness();h.f.flatSubscription(representation);StripeBillingClockSnapshotTest.indicator(h.f,indicator);h.root();h.reserve(true);h.graph();h.f.f.http.reply(h.success());h.f.f.http.reply(h.f.f.clockJson());
            if(when.equals("before"))h.revoked=true;else after(h,when.equals("afterClock")?1:when.equals("afterGraph")?9:when.equals("afterPost")?10:11,()->h.revoked=true);
            var result=h.provider().confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT");assertThat(result.state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(result.receipt()).isNull();
            assertThat(h.f.f.http.calls.stream().filter(c->c.method().name().equals("POST"))).hasSize(when.startsWith("afterPost")?1:0);
        }
    }
    @ParameterizedTest @ValueSource(strings={"unconsumed","wrongToken","wrongDigest","wrongChain","wrongSession","wrongOrigin","wrongPolicy","wrongRevision","predecessor","successor","receipt","ready","finished","forgedVersion","forgedAttempt","wrongFingerprint","expired","wrongLookup","foreignScope"})
    void missingOrSubstitutedDurableAuthorityCannotPost(String bad){
        for(var indicator:List.of("omitted","null","zero"))for(var representation:List.of("exact","omitted","null")){
            var h=new Harness();h.f.flatSubscription(representation);StripeBillingClockSnapshotTest.indicator(h.f,indicator);h.root();h.reserve(!bad.equals("unconsumed"));String token="ctoken_INERT";var lookup=h.f.lookup();var a=h.attempt;var c=h.chain;
            switch(bad){
                case "wrongToken"->token="ctoken_OTHER";case "wrongDigest"->h.chain=new SubscriptionConfirmationChain(c.id(),"f".repeat(64),c.sessionId(),c.origin(),c.expiresAt(),c.policyVersion(),c.subscriptionRevision());
                case "wrongChain"->h.chain=new SubscriptionConfirmationChain(UUID.randomUUID(),c.rootDigest(),c.sessionId(),c.origin(),c.expiresAt(),c.policyVersion(),c.subscriptionRevision());
                case "wrongSession","wrongOrigin","wrongPolicy","wrongRevision","expired"->h.chain=new SubscriptionConfirmationChain(c.id(),c.rootDigest(),bad.equals("wrongSession")?UUID.randomUUID():c.sessionId(),bad.equals("wrongOrigin")?"https://other.test":c.origin(),bad.equals("expired")?h.now:c.expiresAt(),bad.equals("wrongPolicy")?2:c.policyVersion(),bad.equals("wrongRevision")?99:c.subscriptionRevision());
                case "predecessor","successor","receipt","ready","finished","forgedVersion","wrongFingerprint"->h.attempt=new SubscriptionConfirmationAttempt(a.id(),a.chain(),bad.equals("predecessor")?UUID.randomUUID():null,a.keyVersion(),a.issuedAt(),a.expiresAt(),bad.equals("ready")?SubscriptionConfirmationAttempt.State.READY:bad.equals("finished")?SubscriptionConfirmationAttempt.State.UNKNOWN:a.state(),a.confirmKey(),a.tokenDigest(),bad.equals("wrongFingerprint")?"e".repeat(64):a.fingerprint(),bad.equals("receipt")?new SubscriptionConfirmationAttempt.Receipt("req_fake","ch_fake","generic_decline"):null,bad.equals("successor")?UUID.randomUUID():null,null,bad.equals("forgedVersion")?2:a.version());
                case "wrongLookup"->lookup=new SubscriptionProviderModels.Lookup(lookup.account(),lookup.subscriptionId(),"sub_other",lookup.providerOperationKey());case "foreignScope"->h.revoked=true;default->{}
            }
            var supplied=bad.equals("forgedAttempt")?new SubscriptionConfirmationAttempt(UUID.randomUUID(),a.chain(),null,a.keyVersion(),a.issuedAt(),a.expiresAt(),a.state(),a.confirmKey(),a.tokenDigest(),a.fingerprint(),null,null,null,a.version()):h.attempt;
            assertThat(h.provider().confirm(lookup,h.root,supplied,token).state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(h.f.f.http.calls).isEmpty();
        }
    }
    @Test void directAmbientTransactionRefusesBothIoEntriesAndActionBeforeCollaborators(){
        var h=new Harness();h.root();h.reserve(true);var provider=h.provider();int guards=h.guards;
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try{
            assertThatThrownBy(()->provider.read(h.f.lookup(),h.root,null)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->provider.confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT")).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->h.action().nextAction(h.scope)).isInstanceOf(RuntimeException.class);
            assertThat(h.guards).isEqualTo(guards);assertThat(h.f.f.verifications).isZero();assertThat(h.f.f.http.calls).isEmpty();
        }finally{org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void capabilityKeepsExistingV2BytesAndAdaptersHaveNoRegistrationOrExtraMethods(){
        var h=new Harness();h.root();h.reserve(false);var ordinary=new StripeBillingConfirmationAdapter(null,h.tokens,null,null,null,h.clock);
        assertThat(h.provider().capability(h.root,h.chain,h.attempt)).isEqualTo(ordinary.capability(h.root,h.chain,h.attempt));
        assertThat(Arrays.stream(StripeBillingClockConfirmationAdapter.class.getDeclaredMethods()).filter(m->java.lang.reflect.Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName)).containsExactlyInAnyOrder("read","confirm","capability");
        assertThat(StripeBillingClockConfirmationAdapter.class.isAnnotationPresent(org.springframework.stereotype.Component.class)).isFalse();
        assertThat(StripeBillingClockActionAdapter.class.getInterfaces()).isEmpty();
    }
    static void after(Harness h,int count,Runnable effect){h.f.f.http.afterResponse=()->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();if(count==1)effect.run();else after(h,count-1,effect);};}
    @ParameterizedTest @ValueSource(strings={"id","object","live","deleted","name","frozen","created","deletes","advancing","details"})
    void originalReadyClockCannotDriftBeforeAnyGraphOrConfirm(String bad){
        var h=new Harness();h.root();h.reserve(true);var r=StripeBillingOwnedSubscriptionFixture.json(h.f.f.clockJson());
        switch(bad){case "id"->r.addProperty("id","clock_other");case "object"->r.addProperty("object","customer");case "live"->r.addProperty("livemode",true);case "deleted"->r.addProperty("deleted",true);case "name"->r.addProperty("name","other");case "frozen"->r.addProperty("frozen_time",1700000001);case "created"->r.addProperty("created",1800000001);case "deletes"->r.addProperty("deletes_after",1801000001);case "advancing"->r.addProperty("status","advancing");case "details"->r.add("status_details",StripeBillingOwnedSubscriptionFixture.json("{\"advancing\":{\"target_frozen_time\":1700000001}}"));}
        h.f.f.http.reply(r.toString());assertThat(h.provider().confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT").state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);
        assertThat(h.f.f.http.calls).hasSize(1);assertThat(h.f.f.http.calls.getFirst().method().name()).isEqualTo("GET");
    }
    @ParameterizedTest @ValueSource(strings={"account","site","customer","subscription","operation","ownedIntent","step","operationKey","proof","subscriptionReference","customerReference","invoice","allocation","intent","ordinary","expired","issuedBeforeAck","over600","superseded","digest","session"})
    void foreignOrExpiredRootCannotReachProvider(String bad){
        var h=new Harness();h.root();h.reserve(true);var g=h.root;var b=g.binding();var other=UUID.randomUUID();
        var altered=new SubscriptionClientGrant.Binding(bad.equals("step")?other:b.stepId(),bad.equals("operationKey")?"foreign":b.operationKey(),bad.equals("proof")?"f".repeat(64):b.proof(),bad.equals("subscriptionReference")?"sub_other":b.subscriptionReference(),bad.equals("customerReference")?"cus_other":b.customerReference(),bad.equals("invoice")?"in_other":b.invoiceReference(),bad.equals("allocation")?"inpay_other":b.allocationReference(),bad.equals("intent")?"pi_other":b.intentReference(),bad.equals("ordinary")?"payment-v1":b.schema(),null,bad.equals("ordinary")?null:bad.equals("ownedIntent")?other:b.ownedIntent());
        h.root=new SubscriptionClientGrant(g.id(),bad.equals("site")?new dev.genericpayments.platform.sites.domain.SiteId(other):g.site(),bad.equals("customer")?new CustomerId(other):g.customer(),bad.equals("account")?new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(other):g.account(),bad.equals("subscription")?other:g.subscription(),bad.equals("operation")?other:g.operation(),g.keyVersion(),altered,bad.equals("digest")?"f".repeat(64):g.tokenDigest(),bad.equals("issuedBeforeAck")?h.receipt.acknowledgedAt().minusSeconds(1):g.issuedAt(),bad.equals("expired")?h.now:bad.equals("over600")?g.issuedAt().plusSeconds(601):g.expiresAt(),g.consumedAt(),bad.equals("superseded")?h.now:null,bad.equals("session")?other:null,null,null);
        assertThat(h.provider().confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT").state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(h.f.f.http.calls).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"receipt","created","item","period","owner","clock"})
    void differentExpectedReceiptNeverGainsAuthority(String bad){
        for(var indicator:List.of("omitted","null","zero"))for(var representation:List.of("exact","omitted","null")){
            var h=new Harness();h.f.flatSubscription(representation);StripeBillingClockSnapshotTest.indicator(h.f,indicator);h.root();h.reserve(true);var r=h.receipt;var i=r.intent();
            if(bad.equals("owner")||bad.equals("clock")){var c=i.clock();var o=c.owner();o=new ClockOwner(bad.equals("owner")?UUID.randomUUID():o.intent(),o.run(),o.account(),o.site(),o.customer(),o.externalAccount(),o.credentialsRef(),o.apiVersion(),bad.equals("clock")?o.initialFrozenTime()+1:o.initialFrozenTime(),o.name(),o.createKey(),o.createFingerprint(),o.admittedAt());c=new ReadyClock(o,c.creationOperation(),c.reference(),bad.equals("clock")?c.frozenTime()+1:c.frozenTime(),c.providerCreated(),c.providerDeletesAfter(),c.observedAt(),c.customerFingerprint());i=new OwnedClockSubscription(i.id(),c,i.customerBinding(),i.customerCreationOperation(),i.customerReference(),i.subscription(),i.createOperation(),i.operationKey(),i.requestFingerprint(),i.expectedRevision(),i.policyVersion(),i.terms(),i.priceReference(),i.productReference(),i.fingerprint(),i.admittedAt());}
            var changed=new OwnedClockSubscriptionReceipt(i,bad.equals("receipt")?UUID.randomUUID():r.providerOperation(),r.reference(),bad.equals("created")?r.providerCreated()+1:r.providerCreated(),bad.equals("item")?"si_other":r.itemReference(),r.periodStart(),bad.equals("period")?r.periodEnd()+1:r.periodEnd(),r.acknowledgedAt());
            var provider=new StripeBillingClockConfirmationAdapter(changed,h.scope,h.phases,h.roots,h.confirmations,h.tokens,h.f.f.credentials,h.f.f.clients,h.f.f.verification,h.clock);
            assertThat(provider.confirm(h.f.lookup(),h.root,h.attempt,"ctoken_INERT").state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(h.f.f.http.calls).isEmpty();
        }
    }
    @ParameterizedTest @ValueSource(strings={"reserved","consumed","expired","unknown"})
    void anyLifetimeOwnedRootPreventsNewPermission(String kind){
        for(var indicator:List.of("omitted","null","zero"))for(var representation:List.of("exact","omitted","null")){
            var h=new Harness();h.f.flatSubscription(representation);StripeBillingClockSnapshotTest.indicator(h.f,indicator);h.root();if(!kind.equals("expired"))h.reserve(!kind.equals("reserved"));if(kind.equals("unknown"))h.attempt=h.attempt.finish(SubscriptionConfirmationAttempt.State.UNKNOWN,null);
            assertThat(h.action().nextAction(h.scope)).isNull();assertThat(h.inserts).isZero();assertThat(h.f.f.http.calls).isEmpty();
        }
    }
    @ParameterizedTest @ValueSource(strings={"revoke","policy","operation","subscription"})
    void issuanceRechecksActualLockedStateAfterGraphBeforeInsert(String bad){
        for(var indicator:List.of("omitted","null","zero"))for(var representation:List.of("exact","omitted","null")){
            var h=new Harness();h.f.flatSubscription(representation);StripeBillingClockSnapshotTest.indicator(h.f,indicator);h.graph();after(h,9,()->{switch(bad){case "revoke"->h.revoked=true;case "policy"->h.drift=true;case "operation"->{var o=h.f.operation;h.f.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),o.status(),o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),null,o.createdAt(),o.updatedAt(),o.version()+1);}case "subscription"->{var s=h.f.local;h.f.local=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),s.debt(),s.terminalReason(),s.revision()+1,s.createdAt(),s.updatedAt(),s.lastReconciledAt());}}});
            assertThatThrownBy(()->h.action().nextAction(h.scope)).isInstanceOf(RuntimeException.class);assertThat(h.inserts).isZero();assertThat(h.root).isNull();
        }
    }
    @Test void ordinaryMapperRetainsFixedProofAndLargerSecretWithOpaqueConfirmationMethod(){
        var f=new StripeBillingOwnedSubscriptionFixture();prepareOpen(f);f.customer.remove("test_clock");f.remote.remove("test_clock");f.invoice.remove("test_clock");f.remote.addProperty("created",f.utc.getEpochSecond());f.pi.remove("confirmation_method");f.pi.addProperty("client_secret","pi_owned_secret_"+"x".repeat(600));queueGraph(f);
        var step=new ProviderOperation(f.receipt().providerOperation(),f.f.account.id(),f.f.account.externalAccountId(),f.f.account.credentialsRef(),ProviderOperation.Kind.SUBSCRIPTION_CREATE,f.operationId,f.intent.operationKey(),f.intent.fingerprint(),ProviderOperation.Status.ACKNOWLEDGED,"sub_owned",f.utc,f.utc.plusSeconds(82800),null,null,2);
        var proof=new StripeBillingOwnership.OriginalCreate(f.f.account,StripeBillingOwnership.bound(f.local,"sub_owned"),f.operation,f.binding(),step,new StripeBillingSnapshotMapper.PublishedPrice(f.terms,"price_owned","prod_owned"));
        var action=StripeBillingSnapshotMapper.retrievePaymentAction(f.f.clients.create(null),proof,f.utc);
        assertThat(action.binding().schema()).isEqualTo("payment-v1");assertThat(action.binding().ownedIntent()).isNull();assertThat(action.binding().proof()).isEqualTo("e0cc069edcdff6e9162561ab45fe5e31370d6e7e94b540b3948efa72c249a434");assertThat(action.clientSecret()).hasSize(616);
    }
    @Test void ownedSecretAtExactSdkCeilingIsAccepted(){
        var f=new StripeBillingOwnedSubscriptionFixture();prepareOpen(f);f.pi.addProperty("client_secret","pi_owned_secret_"+"x".repeat(496));queueGraph(f);
        assertThat(StripeBillingSnapshotMapper.retrieveOwnedPaymentAction(f.f.clients.create(null),f.ordinary(),new StripeBillingSnapshotMapper.OwnedInitial(f.receipt()),f.utc).clientSecret()).hasSize(512);
    }
    @Test void concurrentServiceRequestSeesDurableClaimWithoutAnotherPost()throws Exception{
        var h=new Harness();h.root();var provider=h.provider();var ports=StripeBillingInertSdkFixture.port(org.springframework.beans.factory.ObjectProvider.class,c->provider);
        var service=new SubscriptionConfirmationService(h.phases,h.roots,h.confirmations,ports,null,h.clock,h.receipt);
        h.graph();var selected=service.select(h.scope,h.tokens.token("v1",h.root.descriptor()));h.graph();h.graph();h.f.f.http.reply(h.success());h.f.f.http.reply(h.f.f.clockJson());
        var claimed=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        after(h,10,()->{claimed.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("replay did not complete");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}});
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var first=executor.submit(()->service.confirm(h.scope,selected.attemptId(),selected.capability(),"ctoken_INERT"));
            assertThat(claimed.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try{assertThat(service.confirm(h.scope,selected.attemptId(),selected.capability(),"ctoken_INERT").status().state()).isEqualTo(SubscriptionConfirmationAttempt.State.DISPATCHING);}finally{release.countDown();}
            assertThat(first.get(5,java.util.concurrent.TimeUnit.SECONDS).status().state()).isEqualTo(SubscriptionConfirmationAttempt.State.OBSERVE);
        }
        assertThat(h.f.f.http.calls.stream().filter(c->c.method().name().equals("POST"))).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"requires_action","402","disconnect"})
    void ownedNonSuccessServiceOutcomesHaveNoNewPermissionOrRedispatch(String mode){
        var h=new Harness();h.root();var provider=h.provider();var ports=StripeBillingInertSdkFixture.port(org.springframework.beans.factory.ObjectProvider.class,c->provider);
        var service=new SubscriptionConfirmationService(h.phases,h.roots,h.confirmations,ports,null,h.clock,h.receipt);h.graph();var selected=service.select(h.scope,h.tokens.token("v1",h.root.descriptor()));h.graph();h.graph();
        if(mode.equals("402"))h.f.f.http.error(402);else if(mode.equals("disconnect"))h.f.f.http.disconnect();else{var r=StripeBillingOwnedSubscriptionFixture.json(h.success());r.addProperty("status","requires_action");r.addProperty("amount_received",0);r.add("next_action",StripeBillingOwnedSubscriptionFixture.json("{\"type\":\"use_stripe_sdk\",\"use_stripe_sdk\":{\"opaque\":true}}"));h.f.f.http.reply(r.toString());h.f.f.http.reply(h.f.f.clockJson());}
        var outcome=service.confirm(h.scope,selected.attemptId(),selected.capability(),"ctoken_INERT");assertThat(outcome.status().state()).isEqualTo(mode.equals("requires_action")?SubscriptionConfirmationAttempt.State.REQUIRES_ACTION:SubscriptionConfirmationAttempt.State.UNKNOWN);assertThat(outcome.nextActionAllowed()).isEqualTo(mode.equals("requires_action"));
        assertThat(service.confirm(h.scope,selected.attemptId(),selected.capability(),"ctoken_INERT").nextActionAllowed()).isFalse();assertThat(outcome.status().retryAllowed()).isFalse();
        assertThatThrownBy(()->service.retry(h.scope,selected.attemptId())).isInstanceOf(RuntimeException.class);assertThat(h.action().nextAction(h.scope)).isNull();
        assertThat(h.f.f.http.calls.stream().filter(c->c.method().name().equals("POST"))).hasSize(1);
    }
    static Class<?> type(String name){Class<?> type=null;try{type=Class.forName("dev.genericpayments.platform.provider.adapter.out.stripe.billing."+name);}catch(ClassNotFoundException absent){}
        assertThat(type).as("explicit original receipt bound adapter "+name).isNotNull();return type;}
    static class Harness {
        final StripeBillingOwnedSubscriptionFixture f=new StripeBillingOwnedSubscriptionFixture();
        final OwnedClockSubscriptionReceipt receipt=f.receipt();final Instant now=f.utc.plusSeconds(2);final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
        final StripeBillingGrantTokens tokens=new StripeBillingGrantTokens(true,"v1",Map.of("v1",Base64.getEncoder().encodeToString(new byte[32])));
        final CustomerSessionContext session=new CustomerSessionContext(new CustomerSessionId(new UUID(0,90)),f.f.site,f.f.customer,"https://shop.test",Set.of(CustomerSessionScope.SUBSCRIPTION_MANAGE),now.plusSeconds(1200));
        final SubscriptionConfirmationUseCase.Scope scope=new SubscriptionConfirmationUseCase.Scope(new SubscriptionCommandContext(new SubscriptionActor.Customer(session),f.f.site,f.f.customer,session.origin(),"key","test"),f.subscriptionId,f.operationId);
        SubscriptionClientGrant root;SubscriptionConfirmationChain chain;SubscriptionConfirmationAttempt attempt;boolean revoked,drift,active,stopped;int inserts,guards;RuntimeException currentFailure,reserveFailure,authorityFailure;Runnable authorityEffect;
        final SubscriptionActionStore roots=StripeBillingInertSdkFixture.port(SubscriptionActionStore.class,c->switch(c.name()){
            case "current","billing"->{if(currentFailure!=null)throw currentFailure;yield Optional.ofNullable(root);}case "serverConfirmationReserved"->chain!=null;
            case "reserveOwnedInitialBilling"->{if(reserveFailure!=null)throw reserveFailure;if(!active||!receipt.equals(c.args()[1])||root!=null)throw new AssertionError("invalid owned insert");root=(SubscriptionClientGrant)c.args()[0];inserts++;yield null;}
            default->throw new AssertionError(c.name());});
        final SubscriptionConfirmationStore confirmations=StripeBillingInertSdkFixture.port(SubscriptionConfirmationStore.class,c->switch(c.name()){
            case "chain","current"->Optional.ofNullable(chain);case "attempt","latest"->attempt;
            case "reserve"->{chain=(SubscriptionConfirmationChain)c.args()[1];attempt=(SubscriptionConfirmationAttempt)c.args()[2];yield null;}
            case "save"->{if(attempt.version()!=(Long)c.args()[1])throw new AssertionError("stale attempt");attempt=(SubscriptionConfirmationAttempt)c.args()[0];if(attempt.state()==SubscriptionConfirmationAttempt.State.DISPATCHING)root=new SubscriptionClientGrant(root.id(),root.site(),root.customer(),root.account(),root.subscription(),root.operation(),root.keyVersion(),root.binding(),root.tokenDigest(),root.issuedAt(),root.expiresAt(),now,null,null,null,null);yield null;}
            default->throw new AssertionError(c.name());
});
        final SubscriptionTransactionPort tx=new SubscriptionTransactionPort(){public <T>T required(Supplier<T> work){return requiresNew(work);}public synchronized <T>T requiresNew(Supplier<T> work){if(active)throw new AssertionError("nested transaction");active=true;org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);try{return work.get();}finally{active=false;org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}}public boolean active(){return org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();}};
        final SubscriptionInteractionTransaction phases;
        Harness(){
            f.local=StripeBillingOwnership.bound(f.local,"sub_owned");var o=f.operation;f.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),o.status(),o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),"sub_owned",null,o.createdAt(),o.updatedAt(),o.version());
            var ops=StripeBillingInertSdkFixture.port(SubscriptionOperationStore.class,c->f.operation);
            var subs=StripeBillingInertSdkFixture.port(SubscriptionStore.class,c->c.name().equals("lock")?f.local:Optional.of(f.local));
            var auth=StripeBillingInertSdkFixture.port(SubscriptionAuthorityPort.class,c->{if(authorityEffect!=null)authorityEffect.run();if(authorityFailure!=null)throw authorityFailure;if(revoked||!scope.context().equals(c.args()[0]))throw new IllegalStateException("revoked session");return null;});
            var policy=StripeBillingInertSdkFixture.port(SubscriptionPolicyPort.class,c->new SubscriptionPolicy(f.f.site,drift?2:1,true,0,false,false,false,Set.of()));
            var accounts=StripeBillingInertSdkFixture.port(LockPaymentAccountPort.class,c->Optional.of(f.f.account));
            var owned=StripeBillingInertSdkFixture.port(StripeBillingClockSubscriptionStore.class,c->{guards++;if(stopped||!active||!c.name().equals("requireConfirmationReceipt")||!receipt.equals(c.args()[0]))throw new IllegalStateException("stopped or foreign receipt");return receipt;});
            phases=new SubscriptionInteractionTransaction(tx,ops,subs,auth,policy,accounts,roots,null,null,clock,owned);
            try{var field=SubscriptionInteractionTransaction.class.getDeclaredField("securityClock");field.setAccessible(true);field.set(phases,clock);}catch(Exception e){throw new AssertionError(e);}
        }
        StripeBillingClockActionAdapter action(){return new StripeBillingClockActionAdapter(receipt,phases,roots,tokens,f.f.credentials,f.f.clients,f.f.verification,clock);}
        StripeBillingClockConfirmationAdapter provider(){return new StripeBillingClockConfirmationAdapter(receipt,scope,phases,roots,confirmations,tokens,f.f.credentials,f.f.clients,f.f.verification,clock);}
        void graph(){f.f.http.reply(f.f.clockJson());openGraph(f);f.f.http.reply(f.f.clockJson());}
        void root(){var b=new SubscriptionClientGrant.Binding(receipt.providerOperation(),f.intent.operationKey(),OwnedInitialConfirmationProof.hash(receipt,"in_owned","inpay_owned","pi_owned"),"sub_owned","cus_owned","in_owned","inpay_owned","pi_owned","owned-initial-v1",null,f.intent.id());root=new SubscriptionClientGrant(new UUID(0,80),f.f.site,f.f.customer,f.f.account.id(),f.subscriptionId,f.operationId,"v1",b,null,now,now.plusSeconds(600),null,null,null,null,null);root=root.digest(StripeBillingGrantTokens.hash(tokens.token("v1",root.descriptor())));}
        void reserve(boolean consume){chain=new SubscriptionConfirmationChain(root.id(),root.tokenDigest(),session.sessionId().value(),session.origin(),root.expiresAt(),1,f.local.revision());attempt=SubscriptionConfirmationAttempt.ready(new UUID(0,81),root.id(),null,"v1",now,chain.expiresAt());if(consume){attempt=attempt.claim(StripeBillingGrantTokens.hash("key"),StripeBillingGrantTokens.hash("ctoken_INERT"),StripeBillingGrantTokens.hash(SubscriptionClientGrant.canonical("SERVER_CONFIRM_V2",attempt.id(),root.descriptor(),StripeBillingGrantTokens.hash("ctoken_INERT"),"use_stripe_sdk=true")));root=new SubscriptionClientGrant(root.id(),root.site(),root.customer(),root.account(),root.subscription(),root.operation(),root.keyVersion(),root.binding(),root.tokenDigest(),root.issuedAt(),root.expiresAt(),now,null,null,null,null);}}
        String success(){return "{\"id\":\"pi_owned\",\"object\":\"payment_intent\",\"livemode\":false,\"customer\":\"cus_owned\",\"amount\":1000,\"currency\":\"eur\",\"amount_received\":1000,\"status\":\"succeeded\",\"capture_method\":\"automatic\",\"confirmation_method\":\"automatic\",\"payment_method_types\":[\"card\"],\"next_action\":null}";}
    }
    static void openGraph(StripeBillingOwnedSubscriptionFixture f){prepareOpen(f);queueGraph(f);}
    static void prepareOpen(StripeBillingOwnedSubscriptionFixture f){
        f.allocation.addProperty("status","open");f.allocation.addProperty("is_default",true);f.allocation.addProperty("amount_paid",0);f.allocation.remove("status_transitions");
        f.pi.addProperty("status","requires_payment_method");f.pi.addProperty("amount_received",0);f.pi.remove("latest_charge");f.pi.addProperty("confirmation_method","automatic");f.pi.addProperty("client_secret","pi_owned_secret_INERT");
    }
    static void queueGraph(StripeBillingOwnedSubscriptionFixture f){
        f.f.http.reply(f.customer.toString());f.f.http.reply(f.remote.toString());f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(f.invoice));f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(f.line));f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(f.allocation));f.f.http.reply(f.pi.toString());f.f.http.reply(StripeBillingOwnedSubscriptionFixture.page(f.allocation));
    }
}
