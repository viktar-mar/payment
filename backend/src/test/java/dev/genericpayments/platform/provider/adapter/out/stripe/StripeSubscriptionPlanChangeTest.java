package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.google.gson.*;
import dev.genericpayments.platform.catalog.application.model.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Owned loopback only. Persistence durability/concurrency is separately hosted. */
class StripeSubscriptionPlanChangeTest {
    static final Instant NOW=StripeBillingSnapshotMapperTest.START.plusSeconds(100);
    StripeSubscriptionCreateTest fixture;
    SubscriptionPriceTerms target;
    StripeBillingOwnership ownership;
    boolean replacedActualLine;
    boolean originalOperationObservation;
    final dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore proofs=mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.class);
    final Map<UUID,dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Quote> quotes=new HashMap<>();
    final Map<UUID,dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Result> results=new HashMap<>();
    final Map<UUID,dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Schedule> schedules=new HashMap<>();
    @BeforeEach void setup() throws Exception {
        fixture=new StripeSubscriptionCreateTest();fixture.setup();configureExisting();
    }
    void borrowFixture(StripeSubscriptionCreateTest existing){fixture=existing;configureExisting();}
    void configureExisting(){
        fixture.local=fixture.graph.local;
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"clock",Clock.fixed(NOW,ZoneOffset.UTC));
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"providerClock",Clock.fixed(NOW,ZoneOffset.UTC));
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"planProofs",proofs);
        when(proofs.saveQuote(any())).thenAnswer(i->{dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Quote q=i.getArgument(0);quotes.put(q.id(),q);return q;});
        when(proofs.quote(any(),any())).thenAnswer(i->Optional.ofNullable(quotes.get(i.getArgument(1))));
        when(proofs.result(any(),any())).thenAnswer(i->Optional.ofNullable(results.get(i.getArgument(1))));
        when(proofs.schedule(any(),any())).thenAnswer(i->Optional.ofNullable(schedules.get(i.getArgument(1))));
        when(proofs.schedules(any(),any())).thenAnswer(i->schedules.values().stream().filter(s->s.updateStep()!=null).map(s->new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.ScheduleProof(quotes.get(s.quote()),s)).toList());
        when(proofs.recordSchedule(any(),any(),any(),any(),anyString(),anyString(),anyLong(),any())).thenAnswer(i->{Subscription s=i.getArgument(0);SubscriptionOperation o=i.getArgument(1);dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Quote q=i.getArgument(2);dev.genericpayments.platform.provider.application.model.ProviderOperation step=i.getArgument(3);fixture.steps.acknowledge(step.id(),i.getArgument(4),step.version(),i.getArgument(5));var saved=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Schedule(o.id(),s.id(),s.paymentAccountId(),q.id(),step.id(),i.getArgument(5),i.getArgument(6),null,null,null,null,i.getArgument(7));schedules.put(o.id(),saved);return saved;});
        when(proofs.recordScheduleUpdate(any(),any(),any(),any(),anyString(),anyLong())).thenAnswer(i->{dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Schedule old=i.getArgument(2);dev.genericpayments.platform.provider.application.model.ProviderOperation step=i.getArgument(3);fixture.steps.acknowledge(step.id(),i.getArgument(4),step.version(),old.reference());var saved=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Schedule(old.operation(),old.subscription(),old.account(),old.quote(),old.creationStep(),old.reference(),old.phaseStart(),step.id(),i.getArgument(5),null,null,old.sourcePhase());schedules.put(old.operation(),saved);return saved;});
        when(proofs.recordRelease(any(),any(),any(),any(),anyString())).thenAnswer(i->{SubscriptionOperation o=i.getArgument(1);dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Schedule old=i.getArgument(2);dev.genericpayments.platform.provider.application.model.ProviderOperation step=i.getArgument(3);fixture.steps.acknowledge(step.id(),i.getArgument(4),step.version(),old.reference());var saved=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Schedule(old.operation(),old.subscription(),old.account(),old.quote(),old.creationStep(),old.reference(),old.phaseStart(),old.updateStep(),old.targetEnd(),o.id(),step.id(),old.sourcePhase());schedules.put(old.operation(),saved);return saved;});
        when(proofs.recordResult(any(),any(),any(),any(),anyString(),anyString(),any(),anyString(),anyString())).thenAnswer(i->{
            Subscription s=i.getArgument(0);SubscriptionOperation o=i.getArgument(1);dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Quote q=i.getArgument(2);dev.genericpayments.platform.provider.application.model.ProviderOperation step=i.getArgument(3);
            fixture.steps.acknowledge(step.id(),i.getArgument(4),step.version(),s.providerReference());
            var result=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Result(o.id(),s.id(),s.paymentAccountId(),q.id(),step.id(),s.providerReference(),i.getArgument(5),i.getArgument(6),i.getArgument(7),i.getArgument(8));results.put(o.id(),result);return result;
        });
        var s=fixture.local;var old=s.billingPrice();
        target=new SubscriptionPriceTerms(new PriceId(UUID.randomUUID()),"upgrade",1,s.productId(),Money.of(2000,"EUR"),BillingInterval.MONTH,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
        when(fixture.catalog.terms(s.siteId(),target.priceId())).thenReturn(target);
        when(fixture.mappings.findPrice(fixture.account.id(),target.priceId())).thenReturn(Optional.of(new ProviderPriceRef("price_next")));
        when(fixture.mappings.findPricesForProduct(fixture.account.id(),s.siteId(),s.productId())).thenReturn(List.of(
            new ProviderCatalogMapping(fixture.account.id(),s.productId(),new ProviderProductRef("prod_owned"),old.priceId(),new ProviderPriceRef("price_owned"),Instant.now()),
            new ProviderCatalogMapping(fixture.account.id(),s.productId(),new ProviderProductRef("prod_owned"),target.priceId(),new ProviderPriceRef("price_next"),Instant.now())));
        ownership=new StripeBillingOwnership(new StripeCredentialResolver(new StripeSandboxProperties(true,"sk_test_"+"A".repeat(32),"pk_test_"+"B".repeat(32),"whsec_"+"C".repeat(32))),fixture.steps,fixture.bindings,StripeBillingGrantTokens::hash,fixture.accounts,fixture.sites,fixture.customerStore,fixture.subscriptions,fixture.operations,fixture.catalog,fixture.mappings);
    }
    @AfterEach void close(){fixture.close();}
    // Missing retained entitled/pending mappings must reject before any provider IO.
    @ParameterizedTest @ValueSource(strings={"entitled","pending"})
    void refusesMissingRetainedMappingEvenWithValidBillingMapping(String retained) {
        var s=fixture.local;
        var missing=new SubscriptionPriceTerms(new PriceId(UUID.randomUUID()),"archived",1,s.productId(),Money.of(500,"EUR"),BillingInterval.MONTH,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
        fixture.local=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),retained.equals("entitled")?missing:s.entitledPrice(),s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),retained.equals("pending")?new Subscription.Change(UUID.randomUUID(),missing,s.currentPeriod().end(),Subscription.ChangeState.SCHEDULED):null,s.debt(),null,s.revision(),s.createdAt(),s.updatedAt(),null);
        assertThatThrownBy(()->ownership.prices(fixture.account,fixture.local)).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
        assertThat(fixture.graph.http.calls()).isEmpty();
    }
    // The adapter must preview this exact item with a captured anchor, not return unsupported/fresh terms.
    @Test void immediatePreviewPreservesOriginalTermsAndAnchor() {
        identityAndCurrent();
        fixture.graph.http.respond("POST","/v1/invoices/create_preview",preview());
        var q=fixture.adapter.preview(new QuoteRequest(previewCommand(),target,ChangeTiming.IMMEDIATE));
        assertThat(q.dueNowMinor()).isEqualTo(500);assertThat(q.nextAmountMinor()).isEqualTo(2000);assertThat(q.currency()).isEqualTo("EUR");
        assertThat(q.reference()).startsWith("bq1:");assertThat(q.expiresAt()).isAfter(q.effectiveAt());
        var saved=org.mockito.ArgumentCaptor.forClass(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Quote.class);verify(proofs).saveQuote(saved.capture());
        assertThat(saved.getValue().period()).isEqualTo(fixture.local.currentPeriod());assertThat(saved.getValue().source()).isEqualTo(fixture.local.billingPrice());assertThat(saved.getValue().lines()).hasSize(2);
        assertThat(fixture.graph.http.calls().getLast().form()).containsEntry("subscription_details[proration_date]",Long.toString(q.effectiveAt().getEpochSecond()))
            .containsEntry("subscription_details[items][0][id]","si_owned").containsEntry("subscription_details[items][0][price]","price_next");
    }
    @Test void quoteRereadKeepsOriginalAnchorReferenceAndExpiry(){
        var command=previewCommand();identityAndCurrent();fixture.graph.http.respond("POST","/v1/invoices/create_preview",preview());
        var first=fixture.adapter.preview(new QuoteRequest(command,target,ChangeTiming.IMMEDIATE));
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"clock",Clock.fixed(NOW.plusSeconds(60),ZoneOffset.UTC));
        identityAndCurrent();fixture.graph.http.respond("POST","/v1/invoices/create_preview",preview());
        assertThat(fixture.adapter.preview(new QuoteRequest(withQuote(command,first.reference()),target,ChangeTiming.IMMEDIATE))).isEqualTo(first);
        assertThat(quotes).hasSize(1);assertThat(fixture.graph.http.calls().getLast().form()).containsEntry("subscription_details[proration_date]",Long.toString(NOW.getEpochSecond()));
    }
    @Test void expiredSavedQuoteCannotRepriceOrDispatch(){
        var command=previewCommand();identityAndCurrent();fixture.graph.http.respond("POST","/v1/invoices/create_preview",preview());
        var first=fixture.adapter.preview(new QuoteRequest(command,target,ChangeTiming.IMMEDIATE));int calls=fixture.graph.http.calls().size();
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"clock",Clock.fixed(first.expiresAt(),ZoneOffset.UTC));
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"providerClock",Clock.fixed(first.expiresAt(),ZoneOffset.UTC));
        assertThatThrownBy(()->fixture.adapter.preview(new QuoteRequest(withQuote(command,first.reference()),target,ChangeTiming.IMMEDIATE))).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
        assertThat(fixture.graph.http.calls()).hasSize(calls);
    }
    @ParameterizedTest @ValueSource(strings={"currency","amount","extraLine","credit","foreignItem","period","foreignSchedule","pendingUpdate","tax"})
    void unsupportedPreviewCannotCreateAnExecutableQuote(String fault){
        var json=JsonParser.parseString(preview()).getAsJsonObject();var lines=json.getAsJsonObject("lines").getAsJsonArray("data");
        switch(fault){case "currency"->lines.get(0).getAsJsonObject().addProperty("currency","usd");case "amount"->json.addProperty("amount_due",1);case "extraLine"->lines.add(lines.get(0).deepCopy());case "credit"->json.addProperty("pre_payment_credit_notes_amount",500);case "foreignItem"->lines.get(0).getAsJsonObject().getAsJsonObject("parent").getAsJsonObject("subscription_item_details").addProperty("subscription_item","si_other");case "period"->lines.get(1).getAsJsonObject().getAsJsonObject("period").addProperty("end",NOW.plusSeconds(60).getEpochSecond());case "foreignSchedule"->fixture.graph.subscription.addProperty("schedule","sub_sched_foreign");case "pendingUpdate"->fixture.graph.subscription.add("pending_update",JsonParser.parseString("{\"expires_at\":1800001000}"));case "tax"->json.add("total_taxes",JsonParser.parseString("[{\"amount\":5}]"));}
        identityAndCurrent();fixture.graph.http.respond("POST","/v1/invoices/create_preview",json.toString());
        assertThatThrownBy(()->fixture.adapter.preview(new QuoteRequest(previewCommand(),target,ChangeTiming.IMMEDIATE))).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);assertThat(quotes).isEmpty();
    }
    @Test void stripeProrationAnchorUsesExistingProviderUtcClock(){
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"clock",Clock.fixed(NOW.plusSeconds(60),ZoneOffset.UTC));identityAndCurrent();fixture.graph.http.respond("POST","/v1/invoices/create_preview",preview());
        assertThat(fixture.adapter.preview(new QuoteRequest(previewCommand(),target,ChangeTiming.IMMEDIATE)).effectiveAt()).isEqualTo(NOW);
    }
    @Test void deferredQuotePreservesPaidBoundaryWithoutImmediateInvoice(){
        identityAndCurrent();var q=fixture.adapter.preview(new QuoteRequest(previewCommand(),target,ChangeTiming.NEXT_PERIOD));
        assertThat(q.effectiveAt()).isEqualTo(fixture.local.paidThrough());assertThat(q.dueNowMinor()).isZero();assertThat(fixture.graph.http.requestCount("POST","/v1/invoices/create_preview")).isZero();assertThat(quotes).hasSize(1);
    }
    @ParameterizedTest @ValueSource(strings={"lineTax","lineDiscount","shipping","shippingDetails"})
    void equalNetPreviewStillRejectsUnsupportedAdjustments(String fault){
        var json=JsonParser.parseString(preview()).getAsJsonObject();var line=json.getAsJsonObject("lines").getAsJsonArray("data").get(0).getAsJsonObject();
        switch(fault){case "lineTax"->line.add("taxes",JsonParser.parseString("[{\"amount\":0}]"));case "lineDiscount"->line.add("discounts",JsonParser.parseString("[\"di_unsupported\"]"));case "shipping"->json.addProperty("amount_shipping",1);case "shippingDetails"->json.add("shipping_details",JsonParser.parseString("{\"name\":\"Unsupported\"}"));}
        identityAndCurrent();fixture.graph.http.respond("POST","/v1/invoices/create_preview",json.toString());assertThatThrownBy(()->fixture.adapter.preview(new QuoteRequest(previewCommand(),target,ChangeTiming.IMMEDIATE))).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);assertThat(quotes).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"line","customer","subscription","window","unpaid","tax"})
    void presentCreditedLinkNeedsItsLiteralFundedSource(String fault){
        var json=linkedPreview();var invoice=fixture.graph.invoice.deepCopy();var sourceLine=fixture.graph.line.deepCopy();
        switch(fault){case "line"->sourceLine.addProperty("id","il_other");case "customer"->invoice.addProperty("customer","cus_other");case "subscription"->invoice.getAsJsonObject("parent").getAsJsonObject("subscription_details").addProperty("subscription","sub_other");case "window"->sourceLine.getAsJsonObject("period").addProperty("end",NOW.minusSeconds(1).getEpochSecond());case "unpaid"->{invoice.addProperty("status","open");invoice.addProperty("amount_paid",0);invoice.addProperty("amount_remaining",1000);}case "tax"->sourceLine.add("taxes",JsonParser.parseString("[{\"amount\":0}]"));}
        identityAndCurrent();fixture.graph.http.respond("POST","/v1/invoices/create_preview",json.toString());creditedSource(invoice,sourceLine,!fault.equals("unpaid"));
        assertThatThrownBy(()->fixture.adapter.preview(new QuoteRequest(previewCommand(),target,ChangeTiming.IMMEDIATE))).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);assertThat(quotes).isEmpty();
    }
    @Test void literalCreditedSourceUsesExistingCompleteCashProof(){
        identityAndCurrent();fixture.graph.http.respond("POST","/v1/invoices/create_preview",linkedPreview().toString());creditedSource(fixture.graph.invoice,fixture.graph.line,true);
        assertThat(fixture.adapter.preview(new QuoteRequest(previewCommand(),target,ChangeTiming.IMMEDIATE)).dueNowMinor()).isEqualTo(500);assertThat(fixture.graph.http.requestCount("GET","/v1/charges/ch_owned")).isEqualTo(1);
    }
    JsonObject linkedPreview(){var json=JsonParser.parseString(preview()).getAsJsonObject();json.getAsJsonObject("lines").getAsJsonArray("data").get(0).getAsJsonObject().getAsJsonObject("parent").getAsJsonObject("subscription_item_details").add("proration_details",JsonParser.parseString("{\"credited_items\":{\"invoice\":\"in_owned\",\"invoice_line_items\":[\"il_owned\"]}}"));return json;}
    void creditedSource(JsonObject invoice,JsonObject line,boolean paid){fixture.graph.http.respond("GET","/v1/invoices/in_owned",invoice.toString());fixture.graph.http.respond("GET","/v1/invoices/in_owned/lines",StripeBillingSnapshotMapperTest.page(false,line));fixture.graph.http.respond("GET","/v1/invoice_payments",paid?StripeBillingSnapshotMapperTest.page(false,fixture.graph.allocation):StripeBillingSnapshotMapperTest.page(false));if(paid){fixture.graph.http.respond("GET","/v1/payment_intents/pi_owned",fixture.graph.intent.toString());fixture.graph.http.respond("GET","/v1/charges/ch_owned",fixture.graph.charge.toString());fixture.graph.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false,fixture.graph.allocation));}}
    @Test void originalChangeUpdateRetainsOldEntitlementAndOriginalReceipt(){
        var c=changeCommand();identityAndCurrent();updateResult();
        assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(fixture.local.entitledPrice()).isEqualTo(c.current());assertThat(fixture.local.paidThrough()).isEqualTo(StripeBillingSnapshotMapperTest.END);
        var step=fixture.steps.only();assertThat(step.kind()).isEqualTo(dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SUBSCRIPTION_UPDATE);assertThat(step.resourceId()).isEqualTo(c.operation().id());assertThat(step.status()).isEqualTo(dev.genericpayments.platform.provider.application.model.ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(results.get(c.operation().id()).creditLine()).isEqualTo("il_actualCredit");assertThat(results.get(c.operation().id()).debitLine()).isEqualTo("il_actualDebit");
        var call=fixture.graph.http.calls().stream().filter(x->x.path().equals("/v1/subscriptions/sub_owned")&&x.method().equals("POST")).findFirst().orElseThrow();
        assertThat(call.header("Idempotency-Key")).isEqualTo(c.operation().providerOperationKey());assertThat(call.form()).containsEntry("payment_behavior","pending_if_incomplete").containsEntry("proration_behavior","always_invoice").containsEntry("proration_date",Long.toString(NOW.getEpochSecond()));
        int calls=fixture.graph.http.calls().size();assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.ACCEPTED);assertThat(fixture.graph.http.calls()).hasSize(calls);
    }
    @Test void lostUpdateAckReplaysOnlyOriginalStepAndKey(){
        var c=changeCommand();identityAndCurrent();fixture.graph.http.disconnectNext("POST","/v1/subscriptions/sub_owned");
        assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.UNKNOWN);var first=fixture.steps.only();
        identityAndCurrent();updateResult();assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(fixture.steps.only().id()).isEqualTo(first.id());assertThat(fixture.steps.only().firstDispatchedAt()).isEqualTo(first.firstDispatchedAt());assertThat(fixture.steps.only().retryBefore()).isEqualTo(first.retryBefore());
        assertThat(fixture.graph.http.calls().stream().filter(x->x.method().equals("POST")&&x.path().equals("/v1/subscriptions/sub_owned"))).hasSize(2).allSatisfy(x->assertThat(x.header("Idempotency-Key")).isEqualTo(c.operation().providerOperationKey()));
    }
    @Test void explicitSameKeyServiceRetryReachesOriginalDispatchedUpdateAfterQuoteExpiry(){
        var first=changeCommand();identityAndCurrent();fixture.graph.http.disconnectNext("POST","/v1/subscriptions/sub_owned");
        assertThat(fixture.adapter.change(first).outcome()).isEqualTo(Outcome.UNKNOWN);
        var o=fixture.operation;fixture.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),SubscriptionOperation.Status.UNKNOWN,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),null,o.createdAt(),NOW,o.version()+1);
        var command=new Command(first.account(),fixture.operation,first.current(),first.target(),first.providerSubscriptionReference(),null,first.providerQuoteReference(),false,false);
        var originalStep=fixture.steps.only();
        var q=quotes.values().iterator().next();
        var localQuote=new SubscriptionQuote(UUID.randomUUID(),fixture.local.siteId(),fixture.local.id(),"original",q.source().priceId(),q.source().version(),target,q.timing(),q.dueNow(),target.regularAmount().minorUnits(),"EUR",q.anchor(),q.expiresAt(),true,q.revision(),q.policy(),q.reference());
        var transaction=mock(dev.genericpayments.platform.subscriptions.application.service.SubscriptionPlanTransaction.class);
        var candidate=new dev.genericpayments.platform.subscriptions.application.service.SubscriptionPlanTransaction.Candidate(fixture.local,fixture.account,new SubscriptionPolicy(fixture.local.siteId(),1,true,3,false,false,false,Set.of()));
        var reserved=new dev.genericpayments.platform.subscriptions.application.service.SubscriptionPlanTransaction.Reserved(command.operation(),fixture.local,fixture.account,localQuote,false);
        when(transaction.candidate(any(),eq(fixture.local.id()))).thenReturn(candidate);
        when(transaction.reserve(any(),eq(fixture.local.id()),eq(localQuote.id()),eq(candidate),anyBoolean(),eq(false))).thenReturn(reserved);
        when(transaction.result(reserved)).thenReturn(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult(command.operation().id(),fixture.local.id(),SubscriptionOperation.Status.UNKNOWN,null));
        when(transaction.reply(eq(reserved),any())).thenAnswer(i->{Reply reply=i.getArgument(1);return new dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult(command.operation().id(),fixture.local.id(),reply.outcome()==Outcome.UNKNOWN?SubscriptionOperation.Status.UNKNOWN:SubscriptionOperation.Status.PROCESSING,null);});
        var providers=mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry.class);when(providers.forAccount(fixture.account)).thenReturn(fixture.adapter);
        var service=new dev.genericpayments.platform.subscriptions.application.service.SubscriptionPlanService(transaction,providers);
        var context=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(null,fixture.local.siteId(),fixture.local.customerId(),"https://shop.test","original-key","request");
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"clock",Clock.fixed(q.expiresAt().plusSeconds(1),ZoneOffset.UTC));
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"providerClock",Clock.fixed(q.expiresAt().plusSeconds(1),ZoneOffset.UTC));
        identityAndCurrent();updateResult();
        assertThat(service.change(new dev.genericpayments.platform.subscriptions.application.port.in.ChangeSubscriptionPlanUseCase.Command(context,fixture.local.id(),localQuote.id())).status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        assertThat(fixture.steps.only().id()).isEqualTo(originalStep.id());
        assertThat(fixture.graph.http.requestCount("POST","/v1/invoices/create_preview")).isEqualTo(1);
        assertThat(fixture.graph.http.calls().stream().filter(x->x.method().equals("POST")&&x.path().equals("/v1/subscriptions/sub_owned"))).hasSize(2).allSatisfy(x->assertThat(x.header("Idempotency-Key")).isEqualTo(command.operation().providerOperationKey()));
    }
    @Test void conditionalUpdateCapabilityUsesInstalledPlanProofBoundary(){assertThat(fixture.adapter.capabilities(fixture.account).conditionalUpdate()).isTrue();}
    @Test void publicServiceFirstDispatchUsesSavedProcessingOperationVersion(){
        var c=changeCommand();var q=quotes.values().iterator().next();var o=c.operation();
        var reservedOperation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),SubscriptionOperation.Status.RESERVED,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),null,o.createdAt(),o.updatedAt(),o.version());
        fixture.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),SubscriptionOperation.Status.PROCESSING,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),null,o.createdAt(),o.updatedAt(),o.version()+1);
        var localQuote=new SubscriptionQuote(UUID.randomUUID(),fixture.local.siteId(),fixture.local.id(),"original",q.source().priceId(),q.source().version(),target,q.timing(),q.dueNow(),target.regularAmount().minorUnits(),"EUR",q.anchor(),q.expiresAt(),true,q.revision(),q.policy(),q.reference());
        var transaction=mock(dev.genericpayments.platform.subscriptions.application.service.SubscriptionPlanTransaction.class);
        var candidate=new dev.genericpayments.platform.subscriptions.application.service.SubscriptionPlanTransaction.Candidate(fixture.local,fixture.account,new SubscriptionPolicy(fixture.local.siteId(),1,true,3,false,false,false,Set.of()));
        var reserved=new dev.genericpayments.platform.subscriptions.application.service.SubscriptionPlanTransaction.Reserved(reservedOperation,fixture.local,fixture.account,localQuote,true);
        when(transaction.candidate(any(),eq(fixture.local.id()))).thenReturn(candidate);when(transaction.reserve(any(),eq(fixture.local.id()),eq(localQuote.id()),eq(candidate),anyBoolean(),eq(false))).thenReturn(reserved);
        when(transaction.beforeMutation(any(),eq(reserved),anyBoolean())).thenReturn(fixture.operation);
        when(transaction.reply(eq(reserved),any())).thenAnswer(i->{Reply reply=i.getArgument(1);return new dev.genericpayments.platform.subscriptions.application.model.SubscriptionOperationResult(c.operation().id(),fixture.local.id(),reply.outcome()==Outcome.UNKNOWN?SubscriptionOperation.Status.UNKNOWN:SubscriptionOperation.Status.PROCESSING,null);});
        var providers=mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionProviderRegistry.class);when(providers.forAccount(fixture.account)).thenReturn(fixture.adapter);var service=new dev.genericpayments.platform.subscriptions.application.service.SubscriptionPlanService(transaction,providers);
        identityAndCurrent();fixture.graph.http.respond("POST","/v1/invoices/create_preview",preview());identityAndCurrent();updateResult();
        var context=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(null,fixture.local.siteId(),fixture.local.customerId(),"https://shop.test","original-key","request");
        assertThat(service.change(new dev.genericpayments.platform.subscriptions.application.port.in.ChangeSubscriptionPlanUseCase.Command(context,fixture.local.id(),localQuote.id())).status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);assertThat(results).hasSize(1);
    }
    @Test void alreadyAppliedRemoteWithoutOriginalInvoiceCausalityCannotBindResult(){
        var c=changeCommand();identityAndCurrent();fixture.graph.http.disconnectNext("POST","/v1/subscriptions/sub_owned");assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.UNKNOWN);
        var price=fixture.graph.item().getAsJsonObject("price");price.addProperty("id","price_next");price.addProperty("unit_amount",2000);price.getAsJsonObject("metadata").addProperty("local_price_id",target.priceId().value().toString());
        identityAndCurrent();updateResult();assertThat(fixture.adapter.continueOriginalPlan(c).outcome()).isEqualTo(Outcome.UNKNOWN);assertThat(results).isEmpty();assertThat(fixture.graph.http.requestCount("POST","/v1/subscriptions/sub_owned")).isEqualTo(1);
    }
    @Test void preexistingPendingUpdateWithoutOriginalResultCannotBeAdopted(){
        var c=changeCommand();identityAndCurrent();fixture.graph.http.disconnectNext("POST","/v1/subscriptions/sub_owned");fixture.adapter.change(c);
        fixture.graph.subscription.add("pending_update",JsonParser.parseString("{\"expires_at\":"+NOW.plusSeconds(3600).getEpochSecond()+",\"subscription_items\":[{\"id\":\"si_owned\",\"quantity\":1,\"price\":{\"id\":\"price_next\"}}]}"));identityAndCurrent();
        assertThat(fixture.adapter.continueOriginalPlan(c).outcome()).isEqualTo(Outcome.UNKNOWN);assertThat(results).isEmpty();assertThat(fixture.graph.http.requestCount("POST","/v1/subscriptions/sub_owned")).isEqualTo(1);
    }
    @Test void explicitContinuationWithoutOriginalDispatchCannotStartANewStep(){
        var c=changeCommand();int calls=fixture.graph.http.calls().size();assertThat(fixture.adapter.continueOriginalPlan(c)).isNull();assertThat(fixture.steps.rows).isEmpty();assertThat(fixture.graph.http.calls()).hasSize(calls);
    }
    @Test void expiredOriginalWriteWindowCannotReplayOrCreateANewStep(){
        var c=changeCommand();identityAndCurrent();fixture.graph.http.disconnectNext("POST","/v1/subscriptions/sub_owned");fixture.adapter.change(c);var step=fixture.steps.only();int calls=fixture.graph.http.calls().size();
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"providerClock",Clock.fixed(step.retryBefore(),ZoneOffset.UTC));
        assertThat(fixture.adapter.continueOriginalPlan(c).outcome()).isEqualTo(Outcome.UNKNOWN);assertThat(fixture.steps.only()).isEqualTo(step);assertThat(fixture.graph.http.calls()).hasSize(calls);
    }
    @Test void activeOriginalLeaseCannotBeTakenByConcurrentExplicitRetry(){
        var c=changeCommand();identityAndCurrent();fixture.graph.http.disconnectNext("POST","/v1/subscriptions/sub_owned");fixture.adapter.change(c);var step=fixture.steps.only();fixture.steps.claim(step.id(),"other-owner",NOW,Duration.ofSeconds(60)).orElseThrow();int calls=fixture.graph.http.calls().size();
        assertThat(fixture.adapter.continueOriginalPlan(c).outcome()).isEqualTo(Outcome.UNKNOWN);assertThat(fixture.graph.http.calls()).hasSize(calls);assertThat(fixture.steps.rows).hasSize(1);
    }
    @Test void exactCapturedProrationKeepsSubperiodAndPreviousTerms(){var snapshot=prorationSnapshot(true,false);var i=snapshot.invoices().getFirst();assertThat(i.settlement()).isEqualTo(SettlementKind.CASH);assertThat(i.amountDueMinor()).isEqualTo(500);assertThat(i.price()).isEqualTo(target);assertThat(i.previousPrice()).isEqualTo(fixture.local.billingPrice());assertThat(i.period().start()).isEqualTo(NOW);assertThat(snapshot.period().start()).isEqualTo(StripeBillingSnapshotMapperTest.START);assertThat(i.cashObjects()).singleElement().satisfies(p->assertThat(p.amountMinor()).isEqualTo(500));}
    @Test void exactZeroProrationNeverInventsPositiveCash(){var snapshot=prorationSnapshot(false,false);assertThat(snapshot.invoices().getFirst().settlement()).isEqualTo(SettlementKind.ZERO);assertThat(snapshot.invoices().getFirst().cashObjects()).isEmpty();}
    @Test void originalProrationHistorySurvivesLaterItemAndPeriodChange(){var snapshot=prorationSnapshot(true,true);assertThat(snapshot.invoices().getFirst().settlement()).isEqualTo(SettlementKind.CASH);assertThat(snapshot.invoices().getFirst().period().end()).isEqualTo(StripeBillingSnapshotMapperTest.END);assertThat(snapshot.period().start()).isEqualTo(StripeBillingSnapshotMapperTest.END);}
    @Test void originalCommittedCashConfirmsImmediateOperationAfterLaterNativeRenewal(){originalOperationObservation=true;assertThat(prorationSnapshot(true,true).operationObservation().state()).isEqualTo(OperationState.APPLIED);}
    @Test void replacingActualLineIdentityCannotReuseOriginalProrationProof(){replacedActualLine=true;assertThatThrownBy(()->prorationSnapshot(true,false)).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);}
    @Test void originalChangeIssuesItsOwnOpaquePaymentActionWithoutReusingCreateGrant(){
        var c=changeCommand();identityAndCurrent();updateResult(30);fixture.adapter.change(c);
        var result=results.get(c.operation().id());var q=quotes.get(result.quote());var history=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Proration(q,result);
        when(proofs.prorations(fixture.account.id(),fixture.local.id())).thenReturn(List.of(history));
        org.springframework.test.util.ReflectionTestUtils.setField(ownership,"plans",proofs);
        var store=mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionActionStore.class);var row=new java.util.concurrent.atomic.AtomicReference<dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant>();
        when(store.current(fixture.account.id(),c.operation().id())).thenAnswer(i->Optional.ofNullable(row.get()));doAnswer(i->{row.set(i.getArgument(0));return null;}).when(store).reserveBilling(any());when(fixture.operations.lock(c.operation().id())).thenReturn(c.operation());
        var credentials=new StripeCredentialResolver(new StripeSandboxProperties(true,"sk_test_"+"A".repeat(32),"pk_test_"+"B".repeat(32),"whsec_"+"C".repeat(32)));
        var factory=new StripeClientFactory(){@Override public com.stripe.StripeClient create(StripeCredentials ignored){return fixture.graph.http.client();}};
        var tx=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort(){public <T>T required(java.util.function.Supplier<T> s){return s.get();}public <T>T requiresNew(java.util.function.Supplier<T> s){return s.get();}public boolean active(){return false;}};
        var actions=new StripeBillingActionAdapter(ownership,new StripeBillingGrantTokens(true,"v1",Map.of("v1",Base64.getEncoder().encodeToString(new byte[32]))),credentials,factory,new StripeAccountVerificationAdapter(credentials,factory),store,fixture.operations,tx,Clock.fixed(NOW,ZoneOffset.UTC));
        actionGraph(result);var lookup=new Lookup(fixture.account,fixture.local.id(),fixture.local.providerReference(),c.operation().providerOperationKey());var action=actions.nextAction(lookup);assertThat(action.expiresAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(action.secret()).matches("bga1_[A-Za-z0-9_-]{43}");assertThat(row.get().binding().schema()).isEqualTo("change-v1");assertThat(row.get().binding().stepKind()).isEqualTo("SUBSCRIPTION_UPDATE");assertThat(row.get().operation()).isEqualTo(c.operation().id());assertThat(row.get().binding().invoiceReference()).isEqualTo("in_change");
        actionGraph(result);var session=actions.retrieve(lookup,row.get(),action.secret());assertThat(session.clientSecret()).isEqualTo("pi_owned_secret_changeCanary");assertThat(session.flow()).isEqualTo(dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientSession.Flow.PAYMENT);assertThat(row.get().descriptor()).doesNotContain(session.clientSecret(),action.secret());
        var g=row.get();row.set(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant(g.id(),g.site(),g.customer(),g.account(),g.subscription(),g.operation(),g.keyVersion(),g.binding(),g.tokenDigest(),g.issuedAt(),g.expiresAt(),NOW,null,null,null,null));int calls=fixture.graph.http.calls().size();assertThat(actions.nextAction(lookup)).isNull();assertThatThrownBy(()->actions.retrieve(lookup,row.get(),action.secret())).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);assertThat(fixture.graph.http.calls()).hasSize(calls);assertThat(fixture.local.entitledPrice()).isEqualTo(c.current());
    }
    void actionGraph(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Result result){
        fixture.identity();var remote=fixture.graph.subscription.deepCopy();remote.add("pending_update",JsonParser.parseString("{\"expires_at\":"+result.pendingExpiry()+",\"subscription_items\":[{\"id\":\"si_owned\",\"quantity\":1,\"price\":{\"id\":\"price_next\"}}]}"));
        fixture.graph.http.respond("GET","/v1/customers/cus_owned",fixture.graph.customer.toString());fixture.graph.http.respond("GET","/v1/subscriptions/sub_owned",remote.toString());
        var invoice=fixture.graph.invoice.deepCopy();invoice.addProperty("id","in_change");invoice.addProperty("status","open");invoice.addProperty("billing_reason","subscription_update");invoice.addProperty("created",NOW.getEpochSecond());for(String f:List.of("amount_due","total","subtotal","amount_remaining"))invoice.addProperty(f,500);invoice.addProperty("amount_paid",0);fixture.graph.http.respond("GET","/v1/invoices",StripeBillingSnapshotMapperTest.page(false,invoice));
        var lines=JsonParser.parseString(preview()).getAsJsonObject().getAsJsonObject("lines");for(var l:lines.getAsJsonArray("data")){l.getAsJsonObject().addProperty("invoice","in_change");l.getAsJsonObject().addProperty("id",l.getAsJsonObject().getAsJsonObject("pricing").getAsJsonObject("price_details").get("price").getAsString().equals("price_next")?result.debitLine():result.creditLine());}fixture.graph.http.respond("GET","/v1/invoices/in_change/lines",lines.toString());
        var allocation=fixture.graph.allocation.deepCopy();allocation.addProperty("invoice","in_change");allocation.addProperty("status","open");allocation.addProperty("amount_requested",500);allocation.addProperty("amount_paid",0);allocation.addProperty("is_default",true);fixture.graph.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false,allocation));
        var intent=fixture.graph.intent.deepCopy();intent.addProperty("amount",500);intent.addProperty("amount_received",0);intent.addProperty("status","requires_action");intent.addProperty("client_secret","pi_owned_secret_changeCanary");intent.remove("latest_charge");fixture.graph.http.respond("GET","/v1/payment_intents/pi_owned",intent.toString());fixture.graph.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false,allocation));
    }
    Snapshot prorationSnapshot(boolean cash,boolean historical){
        var c=changeCommand();var q=quotes.values().iterator().next();
        if(originalOperationObservation){identityAndCurrent();updateResult();assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.ACCEPTED);when(fixture.operations.unresolved(fixture.local.siteId(),fixture.local.id())).thenReturn(List.of(c.operation()));fixture.identity();var price=fixture.graph.item().getAsJsonObject("price");price.addProperty("id","price_next");price.addProperty("unit_amount",2000);price.getAsJsonObject("metadata").addProperty("local_price_id",target.priceId().value().toString());}
        if(!cash){var lines=new ArrayList<dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Line>();for(var l:q.lines())lines.add(new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Line(l.price(),l.product(),l.item(),l.price().equals("price_next")?500:-500,l.period(),l.creditedInvoice(),l.creditedLines()));q=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Quote(q.id(),q.account(),q.site(),q.customer(),q.subscription(),q.subscriptionReference(),q.customerReference(),q.source(),q.entitled(),q.target(),q.sourcePrice(),q.targetPrice(),q.product(),q.item(),q.period(),q.paidThrough(),q.revision(),q.policy(),q.timing(),q.anchor(),q.expiresAt(),0,lines);}
        var result=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Result(c.operation().id(),q.subscription(),q.account(),q.id(),UUID.randomUUID(),q.subscriptionReference(),"in_change",null,"il_actualCredit","il_actualDebit");
        var invoice=fixture.graph.invoice.deepCopy();invoice.addProperty("id","in_change");invoice.addProperty("billing_reason","subscription_update");for(String field:List.of("amount_due","amount_paid","total","subtotal"))invoice.addProperty(field,cash?500:0);
        var raw=JsonParser.parseString(preview()).getAsJsonObject().getAsJsonObject("lines");for(var l:raw.getAsJsonArray("data")){l.getAsJsonObject().addProperty("invoice","in_change");boolean debit=l.getAsJsonObject().getAsJsonObject("pricing").getAsJsonObject("price_details").get("price").getAsString().equals("price_next");l.getAsJsonObject().addProperty("id",debit?"il_actualDebit":replacedActualLine?"il_replaced":"il_actualCredit");if(!cash&&debit)l.getAsJsonObject().addProperty("amount",500);}
        if(historical){fixture.graph.item().addProperty("id","si_later");fixture.graph.item().addProperty("current_period_start",StripeBillingSnapshotMapperTest.END.getEpochSecond());fixture.graph.item().addProperty("current_period_end",StripeBillingSnapshotMapperTest.END.plusSeconds(2678400).getEpochSecond());}
        fixture.graph.http.respond("GET","/v1/customers/cus_owned",fixture.graph.customer.toString());fixture.graph.http.respond("GET","/v1/subscriptions/sub_owned",fixture.graph.subscription.toString());fixture.graph.http.respond("GET","/v1/invoices",StripeBillingSnapshotMapperTest.page(false,invoice));fixture.graph.http.respond("GET","/v1/invoices/in_change/lines",raw.toString());
        var allocation=fixture.graph.allocation.deepCopy();allocation.addProperty("invoice","in_change");allocation.addProperty("amount_paid",500);allocation.addProperty("amount_requested",500);allocation.addProperty("is_default",true);
        fixture.graph.http.respond("GET","/v1/invoice_payments",cash?StripeBillingSnapshotMapperTest.page(false,allocation):StripeBillingSnapshotMapperTest.page(false));
        if(cash){for(String field:List.of("amount","amount_received"))fixture.graph.intent.addProperty(field,500);for(String field:List.of("amount","amount_captured"))fixture.graph.charge.addProperty(field,500);fixture.graph.http.respond("GET","/v1/payment_intents/pi_owned",fixture.graph.intent.toString());fixture.graph.http.respond("GET","/v1/charges/ch_owned",fixture.graph.charge.toString());fixture.graph.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false,allocation));}
        if(originalOperationObservation){when(proofs.prorations(any(),any())).thenReturn(List.of(new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Proration(q,results.get(c.operation().id()))));return fixture.adapter.retrieve(new Lookup(fixture.account,fixture.local.id(),fixture.local.providerReference(),c.operation().providerOperationKey()));}
        return StripeBillingSnapshotMapper.retrieve(fixture.graph.http.client(),new StripeBillingSnapshotMapper.Ownership(fixture.account,fixture.local,fixture.customer,List.of(new StripeBillingSnapshotMapper.PublishedPrice(q.source(),q.sourcePrice(),q.product()),new StripeBillingSnapshotMapper.PublishedPrice(q.target(),q.targetPrice(),q.product())),null,null,List.of(new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Proration(q,result))),NOW);
    }
    Command changeCommand(){return changeCommand(ChangeTiming.IMMEDIATE);}
    Command changeCommand(ChangeTiming timing){
        var request=previewCommand();identityAndCurrent();if(timing==ChangeTiming.IMMEDIATE)fixture.graph.http.respond("POST","/v1/invoices/create_preview",preview());var q=fixture.adapter.preview(new QuoteRequest(request,target,timing));
        var s=fixture.local;var id=UUID.randomUUID();fixture.operation=new SubscriptionOperation(id,s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.CHANGE,SubscriptionOperation.Status.PROCESSING,"customer:owned","key","v1.plan."+"a".repeat(64),"subscription:"+fixture.account.id().value()+":"+id+":CHANGE",s.revision(),1,s.providerReference(),null,NOW,NOW,0);
        when(fixture.operations.find(s.siteId(),s.id(),id)).thenAnswer(x->Optional.of(fixture.operation));
        fixture.local=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),null,null,new Subscription.Change(id,target,q.effectiveAt(),Subscription.ChangeState.UNKNOWN),null,null,s.revision()+1,s.createdAt(),s.updatedAt(),null);
        return new Command(fixture.account,fixture.operation,s.billingPrice(),target,s.providerReference(),null,q.reference(),false,false);
    }
    @Test void deferredChangeCreatesAndConfiguresOnlyOriginalOwnedSchedule(){
        var c=changeCommand(ChangeTiming.NEXT_PERIOD);identityAndCurrent();scheduleResponses();assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(fixture.local.entitledPrice()).isEqualTo(c.current());var saved=schedules.get(c.operation().id());assertThat(saved.reference()).isEqualTo("sub_sched_owned");assertThat(saved.updateStep()).isNotNull();assertThat(fixture.steps.rows.values()).hasSize(2).allSatisfy(s->assertThat(s.resourceId()).isEqualTo(c.operation().id()));
        var post=fixture.graph.http.calls().stream().filter(x->x.method().equals("POST")&&x.path().endsWith("/sub_sched_owned")).findFirst().orElseThrow();assertThat(post.form()).containsEntry("phases[0][start_date]",Long.toString(StripeBillingSnapshotMapperTest.START.getEpochSecond())).containsEntry("phases[0][end_date]",Long.toString(StripeBillingSnapshotMapperTest.END.getEpochSecond())).containsEntry("phases[1][items][0][price]","price_next").containsEntry("phases[1][duration][interval]","month").containsEntry("phases[1][proration_behavior]","none");
        int calls=fixture.graph.http.calls().size();assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.ACCEPTED);assertThat(fixture.graph.http.calls()).hasSize(calls);
    }
    @Test void schedulePreservesActualNativePhaseStartAndItsPaymentMethodOverride(){
        var c=changeCommand(ChangeTiming.NEXT_PERIOD);identityAndCurrent();var created=JsonParser.parseString(scheduleJson(false)).getAsJsonObject();var configured=JsonParser.parseString(scheduleJson(true)).getAsJsonObject();
        for(var value:List.of(created,configured)){value.getAsJsonObject("current_phase").addProperty("start_date",NOW.getEpochSecond());var first=value.getAsJsonArray("phases").get(0).getAsJsonObject();first.addProperty("start_date",NOW.getEpochSecond());first.addProperty("default_payment_method","pm_owned");}
        configured.getAsJsonArray("phases").get(1).getAsJsonObject().addProperty("default_payment_method","pm_owned");
        fixture.graph.http.respond("POST","/v1/subscription_schedules",created.toString());fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",created.toString());fixture.graph.http.respond("POST","/v1/subscription_schedules/sub_sched_owned",configured.toString());fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",configured.toString());
        assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.ACCEPTED);assertThat(schedules.get(c.operation().id()).phaseStart()).isEqualTo(NOW.getEpochSecond());assertThat(fixture.local.currentPeriod().start()).isEqualTo(StripeBillingSnapshotMapperTest.START);
        var call=fixture.graph.http.calls().stream().filter(x->x.method().equals("POST")&&x.path().endsWith("/sub_sched_owned")).findFirst().orElseThrow();assertThat(call.form()).containsEntry("phases[0][start_date]",Long.toString(NOW.getEpochSecond())).containsEntry("phases[0][default_payment_method]","pm_owned").containsEntry("phases[1][default_payment_method]","pm_owned");
    }
    @Test void lostScheduleCreationAckCannotCreateAnotherSchedule(){
        var c=changeCommand(ChangeTiming.NEXT_PERIOD);identityAndCurrent();fixture.graph.http.disconnectNext("POST","/v1/subscription_schedules");assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.UNKNOWN);var first=fixture.steps.only();
        identityAndCurrent();scheduleResponses();assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.ACCEPTED);assertThat(fixture.steps.rows.values().stream().filter(s->s.kind()==dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SCHEDULE_CREATE)).singleElement().satisfies(s->assertThat(s.id()).isEqualTo(first.id()));
        assertThat(fixture.graph.http.calls().stream().filter(x->x.method().equals("POST")&&x.path().equals("/v1/subscription_schedules"))).hasSize(2).allSatisfy(x->assertThat(x.header("Idempotency-Key")).isEqualTo(c.operation().providerOperationKey()+":SCHEDULE_CREATE"));
    }
    @Test void lostScheduleConfigurationAckRevalidatesOwnershipAndReplaysOriginalKey(){
        var c=changeCommand(ChangeTiming.NEXT_PERIOD);identityAndCurrent();fixture.graph.http.respond("POST","/v1/subscription_schedules",scheduleJson(false));fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",scheduleJson(false));fixture.graph.http.disconnectNext("POST","/v1/subscription_schedules/sub_sched_owned");
        assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.UNKNOWN);var step=fixture.steps.rows.values().stream().filter(x->x.kind()==dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SCHEDULE_UPDATE).findFirst().orElseThrow();
        var effective=JsonParser.parseString(scheduleJson(true)).getAsJsonObject();var first=effective.getAsJsonArray("phases").get(0).getAsJsonObject();first.remove("proration_behavior");first.addProperty("collection_method","charge_automatically");
        fixture.graph.subscription.addProperty("schedule","sub_sched_owned");identityAndCurrent();fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",effective.toString());fixture.graph.http.respond("POST","/v1/subscription_schedules/sub_sched_owned",scheduleJson(true));fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",scheduleJson(true));
        assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.ACCEPTED);assertThat(schedules.get(c.operation().id()).updateStep()).isEqualTo(step.id());assertThat(fixture.graph.http.requestCount("POST","/v1/subscription_schedules")).isEqualTo(1);
        assertThat(fixture.graph.http.calls().stream().filter(x->x.method().equals("POST")&&x.path().endsWith("/sub_sched_owned"))).hasSize(2).allSatisfy(x->assertThat(x.header("Idempotency-Key")).isEqualTo(c.operation().providerOperationKey()+":SCHEDULE_UPDATE"));
        var updates=fixture.graph.http.calls().stream().filter(x->x.method().equals("POST")&&x.path().endsWith("/sub_sched_owned")).toList();assertThat(updates.getLast().form()).isEqualTo(updates.getFirst().form()).containsEntry("phases[0][collection_method]","charge_automatically").doesNotContainKey("phases[1][collection_method]");
    }
    @ParameterizedTest @ValueSource(strings={"paymentMethod","proration"})
    void originalConfigurationCannotReplayChangedSourceSettings(String drift){
        var c=changeCommand(ChangeTiming.NEXT_PERIOD);identityAndCurrent();
        fixture.graph.http.respond("POST","/v1/subscription_schedules",scheduleJson(false));fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",scheduleJson(false));fixture.graph.http.disconnectNext("POST","/v1/subscription_schedules/sub_sched_owned");
        assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.UNKNOWN);
        var original=fixture.steps.rows.values().stream().filter(s->s.kind()==dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SCHEDULE_UPDATE).findFirst().orElseThrow();
        var changed=JsonParser.parseString(scheduleJson(true)).getAsJsonObject();var source=changed.getAsJsonArray("phases").get(0).getAsJsonObject();
        source.addProperty(drift.equals("paymentMethod")?"default_payment_method":"proration_behavior",drift.equals("paymentMethod")?"pm_changed":"always_invoice");
        if(drift.equals("paymentMethod"))changed.getAsJsonArray("phases").get(1).getAsJsonObject().addProperty("default_payment_method","pm_changed");
        fixture.graph.subscription.addProperty("schedule","sub_sched_owned");identityAndCurrent();fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",changed.toString());fixture.graph.http.respond("POST","/v1/subscription_schedules/sub_sched_owned",changed.toString());fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",changed.toString());
        assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(fixture.graph.http.requestCount("POST","/v1/subscription_schedules/sub_sched_owned")).isEqualTo(1);assertThat(schedules.get(c.operation().id()).updateStep()).isNull();
        assertThat(fixture.steps.rows.values().stream().filter(s->s.kind()==dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SCHEDULE_UPDATE)).singleElement().satisfies(s->{assertThat(s.id()).isEqualTo(original.id());assertThat(s.fingerprint()).isEqualTo(original.fingerprint());});
    }
    @Test void firstConfigurationRejectsSourceSettingDriftAfterOriginalCreateAck(){
        var c=changeCommand(ChangeTiming.NEXT_PERIOD);identityAndCurrent();var changed=JsonParser.parseString(scheduleJson(false)).getAsJsonObject();changed.getAsJsonArray("phases").get(0).getAsJsonObject().addProperty("default_payment_method","pm_changed");
        fixture.graph.http.respond("POST","/v1/subscription_schedules",scheduleJson(false));fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",changed.toString());fixture.graph.http.disconnectNext("POST","/v1/subscription_schedules/sub_sched_owned");
        assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.UNKNOWN);assertThat(fixture.graph.http.requestCount("POST","/v1/subscription_schedules/sub_sched_owned")).isZero();
    }
    @Test void configuredResponseCannotReplaceOriginalTargetPaymentMethod(){
        var c=changeCommand(ChangeTiming.NEXT_PERIOD);identityAndCurrent();var changed=JsonParser.parseString(scheduleJson(true)).getAsJsonObject();changed.getAsJsonArray("phases").get(1).getAsJsonObject().addProperty("default_payment_method","pm_changed");
        fixture.graph.http.respond("POST","/v1/subscription_schedules",scheduleJson(false));fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",scheduleJson(false));fixture.graph.http.respond("POST","/v1/subscription_schedules/sub_sched_owned",changed.toString());fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",changed.toString());
        assertThat(fixture.adapter.change(c).outcome()).isEqualTo(Outcome.UNKNOWN);assertThat(schedules.get(c.operation().id()).updateStep()).isNull();
    }
    Command clearCommand(){
        var c=changeCommand(ChangeTiming.NEXT_PERIOD);identityAndCurrent();scheduleResponses();fixture.adapter.change(c);var original=fixture.local;var id=UUID.randomUUID();fixture.operation=new SubscriptionOperation(id,original.siteId(),original.customerId(),original.id(),SubscriptionOperation.Kind.CLEAR_CHANGE,SubscriptionOperation.Status.PROCESSING,"customer:owned","clear","v1.plan."+"b".repeat(64),"subscription:"+fixture.account.id().value()+":"+id+":CLEAR_CHANGE",original.revision(),1,original.providerReference(),null,NOW,NOW,0);
        when(fixture.operations.find(original.siteId(),original.id(),c.operation().id())).thenReturn(Optional.of(c.operation()));when(fixture.operations.find(original.siteId(),original.id(),id)).thenAnswer(x->Optional.of(fixture.operation));fixture.local=new Subscription(original.id(),original.siteId(),original.customerId(),original.productId(),original.paymentAccountId(),original.billingPrice(),original.entitledPrice(),original.billingStatus(),original.providerReference(),original.currentPeriod(),original.paidThrough(),null,null,new Subscription.Change(c.operation().id(),target,original.paidThrough(),Subscription.ChangeState.SCHEDULED),null,null,original.revision(),original.createdAt(),original.updatedAt(),null);
        fixture.graph.subscription.addProperty("schedule","sub_sched_owned");identityAndCurrent();fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",scheduleJson(true));
        return new Command(fixture.account,fixture.operation,original.billingPrice(),null,original.providerReference(),null,null,false,false);
    }
    @Test void clearReleasesOnlyOriginalScheduleAndRetainsIntentUntilFact(){var clear=clearCommand();fixture.graph.http.respond("POST","/v1/subscription_schedules/sub_sched_owned/release",releasedSchedule());assertThat(fixture.adapter.clearChange(clear).outcome()).isEqualTo(Outcome.ACCEPTED);assertThat(fixture.local.pendingChange()).isNotNull();assertThat(schedules.get(fixture.local.pendingChange().operationId()).clearOperation()).isEqualTo(clear.operation().id());}
    @Test void lostReleaseAckReplaysOriginalKeyAfterScheduleIsAlreadyReleased(){
        var clear=clearCommand();fixture.graph.http.disconnectNext("POST","/v1/subscription_schedules/sub_sched_owned/release");assertThat(fixture.adapter.clearChange(clear).outcome()).isEqualTo(Outcome.UNKNOWN);
        fixture.graph.subscription.remove("schedule");identityAndCurrent();fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",releasedSchedule());fixture.graph.http.respond("POST","/v1/subscription_schedules/sub_sched_owned/release",releasedSchedule());
        assertThat(fixture.adapter.continueOriginalPlan(clear).outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(fixture.graph.http.calls().stream().filter(x->x.method().equals("POST")&&x.path().endsWith("/release"))).hasSize(2).allSatisfy(x->assertThat(x.header("Idempotency-Key")).isEqualTo(clear.operation().providerOperationKey()+":SCHEDULE_RELEASE"));
    }
    @Test void originalConfiguredScheduleObservationConfirmsSchedulingWithoutEntitlement(){
        var c=changeCommand(ChangeTiming.NEXT_PERIOD);identityAndCurrent();scheduleResponses();fixture.adapter.change(c);when(fixture.operations.unresolved(fixture.local.siteId(),fixture.local.id())).thenReturn(List.of(c.operation()));
        fixture.graph.subscription.addProperty("schedule","sub_sched_owned");observationGraph();fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",scheduleJson(true));
        var snapshot=fixture.adapter.retrieve(new Lookup(fixture.account,fixture.local.id(),fixture.local.providerReference(),c.operation().providerOperationKey()));
        assertThat(snapshot.operationObservation().state()).isEqualTo(OperationState.APPLIED);assertThat(fixture.local.entitledPrice()).isEqualTo(c.current());
    }
    @Test void originalReleasedScheduleObservationConfirmsClearWithoutApplyingTarget(){
        var c=clearCommand();fixture.graph.http.respond("POST","/v1/subscription_schedules/sub_sched_owned/release",releasedSchedule());fixture.adapter.clearChange(c);when(fixture.operations.unresolved(fixture.local.siteId(),fixture.local.id())).thenReturn(List.of(c.operation()));
        fixture.graph.subscription.remove("schedule");observationGraph();fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",releasedSchedule());
        var snapshot=fixture.adapter.retrieve(new Lookup(fixture.account,fixture.local.id(),fixture.local.providerReference(),c.operation().providerOperationKey()));assertThat(snapshot.operationObservation().state()).isEqualTo(OperationState.APPLIED);assertThat(fixture.local.entitledPrice()).isEqualTo(c.current());
    }
    @ParameterizedTest @ValueSource(strings={"missing","beforeDispatch","boundary","late"})
    void releaseNeedsOriginalNativeTimestampStrictlyBeforePaidBoundary(String fault){
        var clear=clearCommand();var response=JsonParser.parseString(releasedSchedule()).getAsJsonObject();
        switch(fault){case "missing"->response.remove("released_at");case "beforeDispatch"->response.addProperty("released_at",NOW.minusSeconds(1).getEpochSecond());case "boundary"->response.addProperty("released_at",fixture.local.paidThrough().getEpochSecond());case "late"->response.addProperty("released_at",fixture.local.paidThrough().plusSeconds(1).getEpochSecond());}
        fixture.graph.http.respond("POST","/v1/subscription_schedules/sub_sched_owned/release",response.toString());assertThat(fixture.adapter.clearChange(clear).outcome()).isEqualTo(Outcome.UNKNOWN);assertThat(schedules.get(fixture.local.pendingChange().operationId()).releaseStep()).isNull();assertThat(fixture.local.pendingChange()).isNotNull();
    }
    @Test void firstHistoricalScheduledCashUsesOriginalTargetPeriodAndGeneratingItem(){var snapshot=historicalSchedule(null);assertThat(snapshot.invoices()).singleElement().satisfies(i->{assertThat(i.settlement()).isEqualTo(SettlementKind.CASH);assertThat(i.price()).isEqualTo(target);assertThat(i.period().end()).isEqualTo(snapshot.period().start());});}
    @ParameterizedTest @ValueSource(strings={"missingItem","foreignSub","period","product","released","ambiguous","missingConfiguration","lineTax","shipping"})
    void historicalTargetCashNeedsItsExactNativeLinksAndOriginalConfiguration(String fault){assertThatThrownBy(()->historicalSchedule(fault)).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);}
    Snapshot historicalSchedule(String fault){
        var c=changeCommand(ChangeTiming.NEXT_PERIOD);identityAndCurrent();scheduleResponses();fixture.adapter.change(c);var binding=schedules.get(c.operation().id());when(fixture.operations.unresolved(fixture.local.siteId(),fixture.local.id())).thenReturn(List.of(c.operation()));
        long start=fixture.local.paidThrough().getEpochSecond(),end=binding.targetEnd();
        fixture.graph.item().addProperty("id","si_later");fixture.graph.item().addProperty("current_period_start",end);fixture.graph.item().addProperty("current_period_end",end+2678400);
        var at=Instant.ofEpochSecond(end+100);org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"clock",Clock.fixed(at,ZoneOffset.UTC));org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"providerClock",Clock.fixed(at,ZoneOffset.UTC));identityAndCurrent();
        var invoice=fixture.graph.invoice.deepCopy();invoice.addProperty("id","in_scheduled");for(String f:List.of("amount_due","amount_paid","total","subtotal"))invoice.addProperty(f,2000);
        var line=fixture.graph.line.deepCopy();line.addProperty("id","il_targetGenerated");line.addProperty("invoice","in_scheduled");line.addProperty("amount",2000);line.getAsJsonObject("period").addProperty("start",start);line.getAsJsonObject("period").addProperty("end",end);line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details").addProperty("subscription_item","si_targetGenerated");line.getAsJsonObject("pricing").getAsJsonObject("price_details").addProperty("price","price_next");
        var allocation=fixture.graph.allocation.deepCopy();allocation.addProperty("invoice","in_scheduled");allocation.addProperty("amount_paid",2000);allocation.addProperty("amount_requested",2000);allocation.addProperty("is_default",true);
        var intent=fixture.graph.intent.deepCopy();intent.addProperty("amount",2000);intent.addProperty("amount_received",2000);var charge=fixture.graph.charge.deepCopy();charge.addProperty("amount",2000);charge.addProperty("amount_captured",2000);charge.addProperty("created",start);
        if(fault!=null)switch(fault){case "missingItem"->line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details").remove("subscription_item");case "foreignSub"->line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details").addProperty("subscription","sub_other");case "period"->line.getAsJsonObject("period").addProperty("end",end+1);case "product"->line.getAsJsonObject("pricing").getAsJsonObject("price_details").addProperty("product","prod_other");case "released"->schedules.put(binding.operation(),new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Schedule(binding.operation(),binding.subscription(),binding.account(),binding.quote(),binding.creationStep(),binding.reference(),binding.phaseStart(),binding.updateStep(),binding.targetEnd(),UUID.randomUUID(),UUID.randomUUID(),binding.sourcePhase()));case "ambiguous"->{var proof=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.ScheduleProof(quotes.get(binding.quote()),binding);when(proofs.schedules(any(),any())).thenReturn(List.of(proof,proof));}case "missingConfiguration"->fixture.steps.rows.remove(binding.updateStep());case "lineTax"->line.add("taxes",JsonParser.parseString("[{\"amount\":0}]"));case "shipping"->invoice.addProperty("amount_shipping",1);}
        fixture.graph.http.respond("GET","/v1/invoices",StripeBillingSnapshotMapperTest.page(false,invoice));fixture.graph.http.respond("GET","/v1/invoices/in_scheduled/lines",StripeBillingSnapshotMapperTest.page(false,line));fixture.graph.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false,allocation));fixture.graph.http.respond("GET","/v1/payment_intents/pi_owned",intent.toString());fixture.graph.http.respond("GET","/v1/charges/ch_owned",charge.toString());fixture.graph.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false,allocation));
        return fixture.adapter.retrieve(new Lookup(fixture.account,fixture.local.id(),fixture.local.providerReference(),c.operation().providerOperationKey()));
    }
    @Test void originalPreBoundaryClearCanFirstBeObservedAfterNativeRenewal(){
        var c=clearCommand();fixture.graph.http.respond("POST","/v1/subscription_schedules/sub_sched_owned/release",releasedSchedule());fixture.adapter.clearChange(c);when(fixture.operations.unresolved(fixture.local.siteId(),fixture.local.id())).thenReturn(List.of(c.operation()));
        var boundary=fixture.local.paidThrough();fixture.graph.subscription.remove("schedule");fixture.graph.item().addProperty("current_period_start",boundary.getEpochSecond());fixture.graph.item().addProperty("current_period_end",boundary.plusSeconds(2678400).getEpochSecond());var at=Clock.fixed(boundary.plusSeconds(100),ZoneOffset.UTC);org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"clock",at);org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"providerClock",at);
        observationGraph();fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",releasedSchedule());
        var snapshot=fixture.adapter.retrieve(new Lookup(fixture.account,fixture.local.id(),fixture.local.providerReference(),c.operation().providerOperationKey()));assertThat(snapshot.operationObservation().state()).isEqualTo(OperationState.APPLIED);assertThat(fixture.local.paidThrough()).isEqualTo(boundary);assertThat(fixture.local.entitledPrice()).isEqualTo(c.current());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void onlyExactExpiredOriginalVoidInvoiceProvesDecline(boolean expired){
        var c=changeCommand();identityAndCurrent();updateResult();fixture.adapter.change(c);var r=results.get(c.operation().id());var q=quotes.get(r.quote());when(proofs.prorations(fixture.account.id(),fixture.local.id())).thenReturn(List.of(new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore.Proration(q,r)));when(fixture.operations.unresolved(fixture.local.siteId(),fixture.local.id())).thenReturn(List.of(c.operation()));
        var at=Clock.fixed(Instant.ofEpochSecond(r.pendingExpiry()+(expired?0:-1)),ZoneOffset.UTC);org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"clock",at);org.springframework.test.util.ReflectionTestUtils.setField(fixture.adapter,"providerClock",at);identityAndCurrent();
        var invoice=JsonParser.parseString(preview()).getAsJsonObject();var lines=invoice.remove("lines").getAsJsonObject();invoice.addProperty("id","in_change");invoice.addProperty("status","void");invoice.addProperty("billing_reason","subscription_update");invoice.addProperty("amount_paid",0);invoice.addProperty("amount_remaining",0);
        for(var line:lines.getAsJsonArray("data")){var value=line.getAsJsonObject();value.addProperty("invoice","in_change");value.addProperty("id",value.getAsJsonObject("pricing").getAsJsonObject("price_details").get("price").getAsString().equals("price_next")?r.debitLine():r.creditLine());}
        fixture.graph.http.respond("GET","/v1/invoices",StripeBillingSnapshotMapperTest.page(false,invoice));fixture.graph.http.respond("GET","/v1/invoices/in_change/lines",lines.toString());fixture.graph.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false));
        if(expired)fixture.graph.http.respond("GET","/v1/subscriptions/sub_owned",fixture.graph.subscription.toString());
        var snapshot=fixture.adapter.retrieve(new Lookup(fixture.account,fixture.local.id(),fixture.local.providerReference(),c.operation().providerOperationKey()));assertThat(snapshot.operationObservation().state()).isEqualTo(expired?OperationState.DECLINED:OperationState.PENDING);assertThat(fixture.local.entitledPrice()).isEqualTo(c.current());
    }
    String releasedSchedule(){return "{\"id\":\"sub_sched_owned\",\"object\":\"subscription_schedule\",\"livemode\":false,\"customer\":\"cus_owned\",\"status\":\"released\",\"released_at\":"+NOW.getEpochSecond()+",\"subscription\":null,\"released_subscription\":\"sub_owned\"}";}
    void observationGraph(){identityAndCurrent();fixture.graph.http.respond("GET","/v1/invoices",StripeBillingSnapshotMapperTest.page(false));}
    void scheduleResponses(){fixture.graph.http.respond("POST","/v1/subscription_schedules",scheduleJson(false));fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",scheduleJson(false));fixture.graph.http.respond("POST","/v1/subscription_schedules/sub_sched_owned",scheduleJson(true));fixture.graph.http.respond("GET","/v1/subscription_schedules/sub_sched_owned",scheduleJson(true));}
    String scheduleJson(boolean configured){long start=StripeBillingSnapshotMapperTest.START.getEpochSecond(),end=StripeBillingSnapshotMapperTest.END.getEpochSecond();return "{\"id\":\"sub_sched_owned\",\"object\":\"subscription_schedule\",\"livemode\":false,\"customer\":\"cus_owned\",\"subscription\":\"sub_owned\",\"status\":\"active\",\"end_behavior\":\"release\",\"current_phase\":{\"start_date\":"+start+",\"end_date\":"+end+"},\"default_settings\":{\"collection_method\":\"charge_automatically\"},\"phases\":[{\"start_date\":"+start+",\"end_date\":"+end+",\"proration_behavior\":\"none\",\"items\":[{\"price\":\"price_owned\",\"quantity\":1}]}"+(configured?",{\"start_date\":"+end+",\"end_date\":"+(end+2678400)+",\"proration_behavior\":\"none\",\"items\":[{\"price\":\"price_next\",\"quantity\":1}]}":"")+"]}";}
    void updateResult(){updateResult(3600);}
    void updateResult(long pendingSeconds){
        var sub=fixture.graph.subscription.deepCopy();sub.addProperty("latest_invoice","in_change");sub.add("pending_update",JsonParser.parseString("{\"expires_at\":"+NOW.plusSeconds(pendingSeconds).getEpochSecond()+",\"subscription_items\":[{\"id\":\"si_owned\",\"quantity\":1,\"price\":{\"id\":\"price_next\",\"object\":\"price\"}}]}"));
        fixture.graph.http.respond("POST","/v1/subscriptions/sub_owned",sub.toString());
        var invoice=JsonParser.parseString(preview()).getAsJsonObject();invoice.addProperty("id","in_change");invoice.addProperty("status","open");invoice.addProperty("billing_reason","subscription_update");invoice.addProperty("created",NOW.getEpochSecond());invoice.addProperty("amount_paid",0);invoice.addProperty("amount_remaining",500);
        var lines=invoice.remove("lines").getAsJsonObject();for(var line:lines.getAsJsonArray("data")){line.getAsJsonObject().addProperty("invoice","in_change");line.getAsJsonObject().addProperty("id",line.getAsJsonObject().getAsJsonObject("pricing").getAsJsonObject("price_details").get("price").getAsString().equals("price_next")?"il_actualDebit":"il_actualCredit");}
        fixture.graph.http.respond("GET","/v1/invoices/in_change",invoice.toString());fixture.graph.http.respond("GET","/v1/invoices/in_change/lines",lines.toString());
    }
    Command withQuote(Command c,String ref){return new Command(c.account(),c.operation(),c.current(),c.target(),c.providerSubscriptionReference(),null,ref,false,false);}
    Command previewCommand(){var s=fixture.local;var now=NOW;var id=UUID.randomUUID();var o=new SubscriptionOperation(id,s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.CHANGE,SubscriptionOperation.Status.RESERVED,"customer:owned","preview","preview","preview:"+id,s.revision(),1,s.providerReference(),null,now,now,0);return new Command(fixture.account,o,s.billingPrice(),target,s.providerReference(),null,null,false,false);}
    void identityAndCurrent(){fixture.identity();fixture.graph.http.respond("GET","/v1/customers/cus_owned",fixture.graph.customer.toString());fixture.graph.http.respond("GET","/v1/subscriptions/sub_owned",fixture.graph.subscription.toString());}
    String preview(){
        var invoice=fixture.graph.invoice.deepCopy();invoice.addProperty("id","upcoming_in_owned");invoice.addProperty("status","draft");invoice.addProperty("amount_due",500);invoice.addProperty("total",500);invoice.addProperty("subtotal",500);
        var credit=fixture.graph.line.deepCopy();credit.addProperty("id","il_credit");credit.addProperty("amount",-500);credit.getAsJsonObject("parent").getAsJsonObject("subscription_item_details").addProperty("proration",true);
        credit.getAsJsonObject("period").addProperty("start",NOW.getEpochSecond());
        var debit=credit.deepCopy();debit.addProperty("id","il_debit");debit.addProperty("amount",1000);debit.getAsJsonObject("pricing").getAsJsonObject("price_details").addProperty("price","price_next");
        invoice.add("lines",JsonParser.parseString(StripeBillingSnapshotMapperTest.page(false,credit,debit)));return invoice.toString();
    }
}
