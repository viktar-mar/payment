package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Synthetic owned SDK peer only. Real SQL/eligibility transactions are hosted separately. */
class StripeSubscriptionTrialTest {
    final StripeSubscriptionCreateTest f=new StripeSubscriptionCreateTest();
    final MemoryProofs proofs=new MemoryProofs();
    final StripeBillingActionAdapter configuredActions=org.mockito.Mockito.mock(StripeBillingActionAdapter.class);
    Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    @BeforeEach void setup()throws Exception{f.setup();now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);f.graph.subscription.addProperty("created",now.getEpochSecond());}
    @AfterEach void close(){f.close();}

    @Test void originalInitialZeroIsProvedOnFirstRead(){assertOriginalZero(false);}
    @Test void paidCreatedCardDefaultIsVerifiedPerSubscriptionWithoutAnySetup(){
        var g=f.graph;g.subscription.addProperty("default_payment_method","pm_paid");g.graph();g.http.respond("GET","/v1/payment_methods/pm_paid","{\"id\":\"pm_paid\",\"object\":\"payment_method\",\"livemode\":false,\"customer\":\"cus_owned\",\"type\":\"card\",\"card\":{\"brand\":\"visa\"}}");
        assertThat(g.read().paymentMethodReady()).isTrue();assertThat(proofs.setups).isEmpty();
    }
    @Test void exactHistoricalZeroRemainsZeroAfterTrialBecomesActive(){assertOriginalZero(true);}
    void assertOriginalZero(boolean historical){
        var g=f.graph;g.zeroTrial();if(historical)g.subscription.addProperty("status","active");
        var s=StripeSubscriptionCreateTest.copy(f.local,"sub_owned",historical?Subscription.Status.ACTIVE:Subscription.Status.INCOMPLETE);
        var trial=new SubscriptionBillingProofStore.Trial(f.operation.id(),s.id(),f.account.id(),StripeBillingSnapshotMapperTest.END.getEpochSecond(),historical?UUID.randomUUID():null,historical?"sub_owned":null,historical?"si_owned":null,historical?"in_owned":null,historical?StripeBillingSnapshotMapperTest.START.getEpochSecond():null);
        var step=new dev.genericpayments.platform.provider.application.model.ProviderOperation(historical?trial.step():UUID.randomUUID(),f.account.id(),f.account.externalAccountId(),f.account.credentialsRef(),dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.SUBSCRIPTION_CREATE,f.operation.id(),f.operation.providerOperationKey(),"trial-proof",dev.genericpayments.platform.provider.application.model.ProviderOperation.Status.ACKNOWLEDGED,"sub_owned",StripeBillingSnapshotMapperTest.START,StripeBillingSnapshotMapperTest.START.plusSeconds(82800),null,null,1);
        var context=new StripeBillingSnapshotMapper.TrialContext(trial,step,g.owner.prices().getFirst(),null);
        g.subscription.add("trial_settings",com.google.gson.JsonParser.parseString("{\"end_behavior\":{\"missing_payment_method\":\"pause\"}}"));
        g.head();g.lines();g.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false));
        assertThatCode(()->{
            var snapshot=StripeBillingSnapshotMapper.retrieve(g.http.client(),new StripeBillingSnapshotMapper.Ownership(f.account,s,f.customer,g.owner.prices(),context,proofs),StripeBillingSnapshotMapperTest.START.plusSeconds(2));
            assertThat(snapshot.invoices()).singleElement().satisfies(i->{assertThat(i.settlement()).isEqualTo(SettlementKind.ZERO);assertThat(i.cashObjects()).isEmpty();});
            assertThat(snapshot.trialEvidence()).isNotNull();assertThat(snapshot.trialEvidence().invoice()).isEqualTo("in_owned");
        }).doesNotThrowAnyException();
    }

    @Test void requiredPreparationCreatesOnlyAnOwnedSetupReceiptBeforeAnySubscription(){
        chooseTrial(dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL);configure();
        var setup=new com.google.gson.JsonObject();setup.addProperty("id","seti_owned");setup.addProperty("object","setup_intent");setup.addProperty("livemode",false);setup.addProperty("customer","cus_owned");setup.addProperty("usage","off_session");setup.addProperty("status","requires_payment_method");setup.addProperty("created",now.getEpochSecond());setup.add("payment_method_types",com.google.gson.JsonParser.parseString("[\"card\"]"));setup.add("metadata",f.metadata());setup.addProperty("client_secret","seti_owned_secret_canary");
        f.identity();f.graph.http.respond("POST","/v1/setup_intents",setup.toString());
        assertThatCode(()->assertThat(f.adapter.prepareCreate(new Command(f.account,f.operation,f.local.billingPrice(),null,null,null,null,true,true))).isInstanceOf(PendingSetup.class)).doesNotThrowAnyException();
        assertThat(f.graph.http.requestCount("POST","/v1/subscriptions")).isZero();
        assertThat(proofs.setup(f.account.id(),f.operation.id())).hasValueSatisfying(p->{assertThat(p.reference()).isEqualTo("seti_owned");assertThat(p.method()).isNull();});
        assertThat(proofs.trial).isNull();assertThat(f.local.trial()).isNull();
        assertThat(f.graph.http.calls().stream().filter(c->c.method().equals("POST"))).singleElement().satisfies(c->{assertThat(c.header("Idempotency-Key")).isEqualTo(f.operation.providerOperationKey()+":SETUP");assertThat(c.form()).containsEntry("customer","cus_owned").containsEntry("usage","off_session").containsEntry("allowed_payment_method_types[0]","card").doesNotContainKeys("confirm","payment_method");});
    }
    @Test void disabledGrantSignerCannotAdvertiseOrStartRequiredSetup(){
        chooseTrial(dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL);configure();
        org.mockito.Mockito.when(configuredActions.enabled()).thenReturn(false);
        org.springframework.test.util.ReflectionTestUtils.setField(f.adapter,"continuationGuard",org.mockito.Mockito.mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionSetupContinuationGuard.class));
        assertThat(f.adapter.capabilities(f.account).setup()).isFalse();
        assertThatThrownBy(()->f.adapter.prepareCreate(new Command(f.account,f.operation,f.local.billingPrice(),null,null,null,null,true,true))).isInstanceOf(RuntimeException.class);assertThat(f.graph.http.calls()).isEmpty();
    }
    @Test void optionalTrialNeverOffersARequiredSetupGrant(){
        chooseTrial(dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL);configure();
        var adapter=actionAdapter(new java.util.concurrent.atomic.AtomicReference<>());
        assertThatCode(()->assertThat(adapter.nextAction(new Lookup(f.account,f.local.id(),null,f.operation.providerOperationKey()))).isNull()).doesNotThrowAnyException();
        f.local=StripeSubscriptionCreateTest.copy(f.local,"sub_owned",Subscription.Status.TRIALING);
        assertThatCode(()->assertThat(adapter.nextAction(new Lookup(f.account,f.local.id(),"sub_owned",f.operation.providerOperationKey()))).isNull()).doesNotThrowAnyException();assertThat(f.graph.http.calls()).isEmpty();
    }
    @Test void clockOwnedCustomerRefusesTrialBeforeFreezingEndOrDispatch(){
        chooseTrial(dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL);configure();f.graph.customer.addProperty("test_clock","clock_owned");f.identity();f.graph.http.respond("GET","/v1/customers/cus_owned",f.graph.customer.toString());
        assertThatThrownBy(()->f.adapter.create(new Command(f.account,f.operation,f.local.billingPrice(),null,null,null,null,true,true))).isInstanceOf(RuntimeException.class);assertThat(proofs.trial).isNull();assertThat(f.graph.http.requestCount("POST","/v1/subscriptions")).isZero();
    }
    @Test void revokedOriginalSetupAuthorityRefusesBeforeProviderPost(){
        chooseTrial(dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL);configure();proofs.setupAuthorized=false;f.identity();
        assertThatThrownBy(()->f.adapter.prepareCreate(new Command(f.account,f.operation,f.local.billingPrice(),null,null,null,null,true,true))).isInstanceOf(RuntimeException.class);assertThat(f.graph.http.requestCount("POST","/v1/setup_intents")).isZero();
    }
    @Test void unknownTrialKeepsItsFrozenEndAndRefusesToExtendAnElapsedRequest(){
        chooseTrial(dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL);configure();f.identity();f.graph.http.respond("GET","/v1/customers/cus_owned",f.graph.customer.toString());f.identity();f.graph.http.disconnectNext("POST","/v1/subscriptions");var c=new Command(f.account,f.operation,f.local.billingPrice(),null,null,null,null,true,true);
        assertThat(f.adapter.create(c).outcome()).isEqualTo(Outcome.UNKNOWN);var saved=proofs.trial;var step=f.steps.only();now=now.plus(Duration.ofDays(15));
        assertThat(f.adapter.create(c).outcome()).isEqualTo(Outcome.UNKNOWN);assertThat(proofs.trial).isEqualTo(saved);assertThat(f.steps.only().id()).isEqualTo(step.id());assertThat(f.steps.only().retryBefore()).isEqualTo(step.retryBefore());assertThat(f.graph.http.requestCount("POST","/v1/subscriptions")).isEqualTo(1);assertThat(f.local.paidThrough()).isNull();
    }
    @Test void lostTrialCreationIsRecoveredOnlyFromItsOriginalVersionedRequestAndExactEvent(){
        chooseTrial(dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL);configure();f.identity();f.graph.http.respond("GET","/v1/customers/cus_owned",f.graph.customer.toString());f.identity();f.graph.http.disconnectNext("POST","/v1/subscriptions");
        assertThat(f.adapter.create(new Command(f.account,f.operation,f.local.billingPrice(),null,null,null,null,true,true)).outcome()).isEqualTo(Outcome.UNKNOWN);var end=proofs.trial.requestedEnd();f.steps.expire();var step=f.steps.only();
        var remote=f.graph.subscription;remote.addProperty("created",step.firstDispatchedAt().getEpochSecond());remote.addProperty("status","trialing");remote.addProperty("trial_start",step.firstDispatchedAt().getEpochSecond());remote.addProperty("trial_end",end);remote.add("trial_settings",com.google.gson.JsonParser.parseString("{\"end_behavior\":{\"missing_payment_method\":\"pause\"}}"));remote.add("metadata",f.metadata());
        f.identity();f.event("evt_originalTrial",step.operationKey(),step.firstDispatchedAt().getEpochSecond());f.graph.http.respond("GET","/v1/customers/cus_owned",f.graph.customer.toString());f.graph.http.respond("GET","/v1/subscriptions/sub_owned",remote.toString());
        assertThatCode(()->assertThat(f.adapter.recoverCreate(f.account,f.local.id(),f.operation.id(),"evt_originalTrial").reference()).isEqualTo("sub_owned")).doesNotThrowAnyException();assertThat(proofs.trial.requestedEnd()).isEqualTo(end);assertThat(f.graph.http.requestCount("POST","/v1/subscriptions")).isEqualTo(1);assertThat(f.local.paidThrough()).isNull();
    }
    @Test void preSubscriptionSetupActionReturnsOnlyItsOwnedSetupSecret(){
        chooseTrial(dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL);configure();
        var setup=setupJson("requires_payment_method");f.identity();f.graph.http.respond("POST","/v1/setup_intents",setup.toString());assertThat(f.adapter.prepareCreate(new Command(f.account,f.operation,f.local.billingPrice(),null,null,null,null,true,true))).isInstanceOf(PendingSetup.class);
        var store=org.mockito.Mockito.mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionActionStore.class);
        var row=new java.util.concurrent.atomic.AtomicReference<dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant>();
        org.mockito.Mockito.when(store.current(f.account.id(),f.operation.id())).thenAnswer(x->Optional.ofNullable(row.get()));org.mockito.Mockito.doAnswer(x->{row.set(x.getArgument(0));return null;}).when(store).reserveBilling(org.mockito.ArgumentMatchers.any());
        var credentials=new StripeCredentialResolver(new StripeSandboxProperties(true,"sk_test_"+"A".repeat(32),"pk_test_"+"B".repeat(32),"whsec_"+"C".repeat(32)));var factory=new StripeClientFactory(){@Override public com.stripe.StripeClient create(StripeCredentials ignored){return f.graph.http.client();}};
        var ownership=new StripeBillingOwnership(credentials,f.steps,f.bindings,StripeBillingGrantTokens::hash,f.accounts,f.sites,f.customerStore,f.subscriptions,f.operations,f.catalog,f.mappings,proofs);
        var tx=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort(){public <T>T required(java.util.function.Supplier<T> s){return s.get();}public <T>T requiresNew(java.util.function.Supplier<T>s){return s.get();}public boolean active(){return false;}};
        org.mockito.Mockito.when(f.operations.lock(f.operation.id())).thenReturn(f.operation);
        var adapter=new StripeBillingActionAdapter(ownership,new StripeBillingGrantTokens(true,"v1",Map.of("v1",Base64.getEncoder().encodeToString(new byte[32]))),credentials,factory,new StripeAccountVerificationAdapter(credentials,factory),store,f.operations,tx,Clock.fixed(now,ZoneOffset.UTC));
        f.identity();f.graph.http.respond("GET","/v1/setup_intents/seti_owned",setup.toString());
        assertThatCode(()->{
            var action=adapter.nextAction(new Lookup(f.account,f.local.id(),null,f.operation.providerOperationKey()));assertThat(action).isNotNull();
            f.identity();f.graph.http.respond("GET","/v1/setup_intents/seti_owned",setup.toString());
            var session=adapter.retrieve(new Lookup(f.account,f.local.id(),null,f.operation.providerOperationKey()),row.get(),action.secret());assertThat(session.flow()).isEqualTo(dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientSession.Flow.SETUP);assertThat(session.clientSecret()).isEqualTo("seti_owned_secret_canary");assertThat(row.get().toString()).doesNotContain(session.clientSecret(),action.secret());
        }).doesNotThrowAnyException();
    }
    com.google.gson.JsonObject setupJson(String status){var r=StripeBillingSnapshotMapperTest.json("{\"id\":\"seti_owned\",\"object\":\"setup_intent\",\"livemode\":false,\"customer\":\"cus_owned\",\"usage\":\"off_session\",\"payment_method_types\":[\"card\"],\"client_secret\":\"seti_owned_secret_canary\"}");r.addProperty("created",now.getEpochSecond());r.addProperty("status",status);r.add("metadata",f.metadata());return r;}

    @Test void requiredSetupContinuationFreshlyVerifiesMethodAndUsesOriginalCreate(){createRequiredFromSetup(false);}
    @Test void missingLocalSetupReceiptIsRebuiltOnlyFromExactAcknowledgedSetup(){createRequiredFromSetup(true);}
    @Test void originalCreateAckRepairsWithoutReadingARemovedMethodOrCreatingAgain(){
        createRequiredFromSetup(false);int reads=f.graph.http.calls().size();
        var request=new Command(f.account,f.operation,f.local.billingPrice(),null,null,null,null,true,true);
        assertThatCode(()->assertThat(f.adapter.continueOriginalSetup(request,new InboxSetupFence(new dev.genericpayments.platform.webhooks.application.port.out.SubscriptionInboxPort.Claim(UUID.randomUUID(),"owned"))).reply().reference()).isEqualTo("sub_owned")).doesNotThrowAnyException();
        assertThat(f.graph.http.calls()).hasSize(reads);
    }
    void createRequiredFromSetup(boolean missingProof){
        chooseTrial(dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL);configure();
        var setup=setupJson("requires_payment_method");f.identity();f.graph.http.respond("POST","/v1/setup_intents",setup.toString());
        var command=new Command(f.account,f.operation,f.local.billingPrice(),null,null,null,null,true,true);f.adapter.prepareCreate(command);
        if(missingProof)proofs.setups.clear();
        var guard=org.mockito.Mockito.mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionSetupContinuationGuard.class);org.springframework.test.util.ReflectionTestUtils.setField(f.adapter,"continuationGuard",guard);
        var fence=new InboxSetupFence(new dev.genericpayments.platform.webhooks.application.port.out.SubscriptionInboxPort.Claim(UUID.randomUUID(),"owned"));
        setup.addProperty("status","succeeded");setup.addProperty("payment_method","pm_owned");
        f.identity();f.graph.http.respond("GET","/v1/setup_intents/seti_owned",setup.toString());f.graph.http.respond("GET","/v1/payment_methods/pm_owned","{\"id\":\"pm_owned\",\"object\":\"payment_method\",\"livemode\":false,\"customer\":\"cus_owned\",\"type\":\"card\",\"card\":{\"brand\":\"visa\"}}");
        f.identity();f.graph.http.respond("GET","/v1/customers/cus_owned",f.graph.customer.toString());f.identity();
        var remote=f.graph.subscription;remote.addProperty("status","trialing");remote.addProperty("trial_start",now.getEpochSecond());remote.addProperty("trial_end",now.plusSeconds(1209600).getEpochSecond());remote.addProperty("default_payment_method","pm_owned");remote.add("trial_settings",com.google.gson.JsonParser.parseString("{\"end_behavior\":{\"missing_payment_method\":\"pause\"}}"));remote.add("metadata",f.metadata());
        f.graph.http.respond("POST","/v1/subscriptions",remote.toString());
        assertThatCode(()->{var result=f.adapter.continueOriginalSetup(command,fence);assertThat(result.progressed()).isTrue();assertThat(result.reply().outcome()).isEqualTo(Outcome.ACCEPTED);}).doesNotThrowAnyException();
        assertThat(proofs.setup(f.account.id(),f.operation.id())).hasValueSatisfying(p->assertThat(p.method()).isEqualTo("pm_owned"));
        assertThat(f.graph.http.calls().stream().filter(c->c.path().equals("/v1/subscriptions")&&c.method().equals("POST"))).singleElement().satisfies(c->{assertThat(c.header("Idempotency-Key")).isEqualTo(f.operation.providerOperationKey());assertThat(c.form()).containsEntry("default_payment_method","pm_owned");});
        org.mockito.Mockito.verify(guard,org.mockito.Mockito.atLeast(2)).requireCurrent(command,fence);
    }
    @Test void setupForOneSubscriptionNeverChangesAnotherWithTheSameCustomer(){
        var before=f.local;f.local=StripeSubscriptionCreateTest.copy(before,"sub_owned",Subscription.Status.ACTIVE);
        var o=f.operation;f.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),SubscriptionOperation.Kind.SETUP,o.status(),o.authorityScope(),o.keyDigest(),"b".repeat(64),"subscription:"+f.account.id().value()+":"+o.id()+":SETUP",o.expectedRevision(),o.policyVersion(),"sub_owned",null,o.createdAt(),o.updatedAt(),o.version());configure();
        var terms=before.billingPrice();var product=new dev.genericpayments.platform.catalog.domain.ProductId(UUID.randomUUID());var otherTerms=new SubscriptionPriceTerms(new dev.genericpayments.platform.catalog.domain.PriceId(UUID.randomUUID()),"other",1,product,terms.regularAmount(),terms.interval(),terms.trialDays(),terms.trialPolicy(),terms.taxMode());
        var other=new Subscription(UUID.randomUUID(),before.siteId(),before.customerId(),product,before.paymentAccountId(),otherTerms,otherTerms,Subscription.Status.ACTIVE,"sub_other",before.currentPeriod(),before.paidThrough(),null,null,null,null,null,0,before.createdAt(),before.updatedAt(),null);
        var setup=setupJson("requires_payment_method");f.identity();f.graph.http.respond("POST","/v1/setup_intents",setup.toString());
        var command=new Command(f.account,f.operation,f.local.billingPrice(),null,"sub_owned",null,null,true,false);f.adapter.setup(command);
        var guard=org.mockito.Mockito.mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionSetupContinuationGuard.class);org.springframework.test.util.ReflectionTestUtils.setField(f.adapter,"continuationGuard",guard);
        setup.addProperty("status","succeeded");setup.addProperty("payment_method","pm_owned");f.identity();f.graph.http.respond("GET","/v1/setup_intents/seti_owned",setup.toString());f.graph.http.respond("GET","/v1/payment_methods/pm_owned","{\"id\":\"pm_owned\",\"object\":\"payment_method\",\"livemode\":false,\"customer\":\"cus_owned\",\"type\":\"card\",\"card\":{\"brand\":\"visa\"}}");f.identity();
        var remote=f.graph.subscription;remote.addProperty("default_payment_method","pm_owned");f.graph.http.respond("POST","/v1/subscriptions/sub_owned",remote.toString());
        assertThatCode(()->assertThat(f.adapter.continueOriginalSetup(command,new InboxSetupFence(new dev.genericpayments.platform.webhooks.application.port.out.SubscriptionInboxPort.Claim(UUID.randomUUID(),"owned"))).progressed()).isTrue()).doesNotThrowAnyException();
        assertThat(f.graph.http.calls().stream().filter(c->c.method().equals("POST"))).extracting(c->c.path()).containsExactly("/v1/setup_intents","/v1/subscriptions/sub_owned");
        assertThat(proofs.method(f.account.id(),other.id(),"pm_owned")).isEmpty();assertThat(proofs.method(f.account.id(),f.local.id(),"pm_owned")).isPresent();
    }
    @Test void explicitResumeBindsOnlyItsOriginalResultInvoiceAndDoesNotGrantFromAck(){
        resumeAndBind();
    }
    @Test void resumePaymentActionIsBoundToOnlyTheOriginalResumeInvoice(){
        resumeAndBind();
        var row=new java.util.concurrent.atomic.AtomicReference<dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant>();
        var adapter=actionAdapter(row);queueResumeAction();
        var lookup=new Lookup(f.account,f.local.id(),"sub_owned",f.operation.providerOperationKey());
        assertThatCode(()->{
            var action=adapter.nextAction(lookup);assertThat(action).isNotNull();assertThat(row.get().binding().schema()).isEqualTo("resume-v1");assertThat(row.get().binding().invoiceReference()).isEqualTo("in_resume");
            queueResumeAction();var session=adapter.retrieve(lookup,row.get(),action.secret());assertThat(session.flow()).isEqualTo(dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientSession.Flow.PAYMENT);assertThat(session.clientSecret()).isEqualTo("pi_owned_secret_canary");assertThat(row.get().toString()).doesNotContain(session.clientSecret(),action.secret());
        }).doesNotThrowAnyException();
        assertThat(f.local.billingStatus()).isEqualTo(Subscription.Status.PAUSED);
    }
    StripeBillingActionAdapter actionAdapter(java.util.concurrent.atomic.AtomicReference<dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant> row){
        var store=org.mockito.Mockito.mock(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionActionStore.class);
        org.mockito.Mockito.when(store.current(f.account.id(),f.operation.id())).thenAnswer(x->Optional.ofNullable(row.get()));org.mockito.Mockito.doAnswer(x->{row.set(x.getArgument(0));return null;}).when(store).reserveBilling(org.mockito.ArgumentMatchers.any());
        var credentials=new StripeCredentialResolver(new StripeSandboxProperties(true,"sk_test_"+"A".repeat(32),"pk_test_"+"B".repeat(32),"whsec_"+"C".repeat(32)));var factory=new StripeClientFactory(){@Override public com.stripe.StripeClient create(StripeCredentials ignored){return f.graph.http.client();}};
        var ownership=new StripeBillingOwnership(credentials,f.steps,f.bindings,StripeBillingGrantTokens::hash,f.accounts,f.sites,f.customerStore,f.subscriptions,f.operations,f.catalog,f.mappings,proofs);
        var tx=new dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort(){public <T>T required(java.util.function.Supplier<T> s){return s.get();}public <T>T requiresNew(java.util.function.Supplier<T>s){return s.get();}public boolean active(){return false;}};
        org.mockito.Mockito.when(f.operations.lock(f.operation.id())).thenReturn(f.operation);
        return new StripeBillingActionAdapter(ownership,new StripeBillingGrantTokens(true,"v1",Map.of("v1",Base64.getEncoder().encodeToString(new byte[32]))),credentials,factory,new StripeAccountVerificationAdapter(credentials,factory),store,f.operations,tx,Clock.fixed(now,ZoneOffset.UTC));
    }
    void queueResumeAction(){
        var g=f.graph;g.subscription.addProperty("status","active");g.subscription.remove("default_payment_method");var item=g.subscription.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject();item.addProperty("current_period_start",now.getEpochSecond());item.addProperty("current_period_end",now.plusSeconds(2592000).getEpochSecond());
        var invoice=g.invoice.deepCopy();invoice.addProperty("id","in_resume");invoice.addProperty("created",now.getEpochSecond());invoice.addProperty("billing_reason","subscription_cycle");invoice.addProperty("status","open");invoice.addProperty("amount_paid",0);invoice.addProperty("amount_remaining",1000);
        var line=g.line.deepCopy();line.addProperty("invoice","in_resume");line.getAsJsonObject("period").addProperty("start",now.getEpochSecond());line.getAsJsonObject("period").addProperty("end",now.plusSeconds(2592000).getEpochSecond());
        var allocation=g.allocation.deepCopy();allocation.addProperty("invoice","in_resume");allocation.addProperty("status","open");allocation.addProperty("is_default",true);allocation.addProperty("amount_paid",0);
        var intent=g.intent.deepCopy();intent.addProperty("status","requires_payment_method");intent.addProperty("amount_received",0);intent.remove("latest_charge");intent.addProperty("client_secret","pi_owned_secret_canary");
        f.identity();g.http.respond("GET","/v1/customers/cus_owned",g.customer.toString());g.http.respond("GET","/v1/subscriptions/sub_owned",g.subscription.toString());g.http.respond("GET","/v1/invoices",StripeBillingSnapshotMapperTest.page(false,invoice));g.http.respond("GET","/v1/invoices/in_resume/lines",StripeBillingSnapshotMapperTest.page(false,line));g.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false,allocation));g.http.respond("GET","/v1/payment_intents/pi_owned",intent.toString());g.http.respond("GET","/v1/invoice_payments",StripeBillingSnapshotMapperTest.page(false,allocation));
    }
    void resumeAndBind(){
        f.local=StripeSubscriptionCreateTest.copy(f.local,"sub_owned",Subscription.Status.PAUSED);var old=f.operation;
        f.operation=new SubscriptionOperation(old.id(),old.siteId(),old.customerId(),old.subscriptionId(),SubscriptionOperation.Kind.RESUME,old.status(),old.authorityScope(),old.keyDigest(),"v1.resume.none."+"a".repeat(64),"subscription:"+f.account.id().value()+":"+old.id()+":RESUME",old.expectedRevision(),old.policyVersion(),"sub_owned",null,old.createdAt(),old.updatedAt(),old.version());configure();
        var setupId=UUID.randomUUID();proofs.setups.put(setupId,new SubscriptionBillingProofStore.Setup(setupId,f.local.id(),f.account.id(),UUID.randomUUID(),"seti_previous","pm_owned"));
        var receipt=org.mockito.Mockito.mock(dev.genericpayments.platform.subscriptions.application.service.SubscriptionResumeReceiptTransaction.class);org.springframework.test.util.ReflectionTestUtils.setField(f.adapter,"resumeReceipts",receipt);
        org.mockito.Mockito.when(receipt.record(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any())).thenAnswer(x->{var step=(dev.genericpayments.platform.provider.application.model.ProviderOperation)x.getArgument(2);var ack=f.steps.acknowledge(step.id(),x.getArgument(3),step.version(),"sub_owned");proofs.recordResume(f.local,f.operation,step.id(),x.getArgument(4),x.getArgument(5));return ack;});
        var remote=f.graph.subscription;remote.addProperty("status","paused");remote.addProperty("default_payment_method","pm_owned");f.identity();f.graph.http.respond("GET","/v1/subscriptions/sub_owned",remote.toString());f.graph.http.respond("GET","/v1/payment_methods/pm_owned","{\"id\":\"pm_owned\",\"object\":\"payment_method\",\"livemode\":false,\"customer\":\"cus_owned\",\"type\":\"card\",\"card\":{\"brand\":\"visa\"}}");
        var response=remote.deepCopy();response.addProperty("latest_invoice","in_resume");f.graph.http.respond("POST","/v1/subscriptions/sub_owned/resume",response.toString());
        var invoice=f.graph.invoice.deepCopy();invoice.addProperty("id","in_resume");invoice.addProperty("created",now.getEpochSecond());invoice.addProperty("billing_reason","subscription_cycle");invoice.addProperty("status","open");invoice.addProperty("amount_paid",0);invoice.addProperty("amount_remaining",1000);f.graph.http.respond("GET","/v1/invoices/in_resume",invoice.toString());
        var line=f.graph.line.deepCopy();line.addProperty("invoice","in_resume");line.getAsJsonObject("period").addProperty("start",now.getEpochSecond());line.getAsJsonObject("period").addProperty("end",now.plusSeconds(2592000).getEpochSecond());f.graph.http.respond("GET","/v1/invoices/in_resume/lines",StripeBillingSnapshotMapperTest.page(false,line));
        assertThatCode(()->assertThat(f.adapter.resume(new Command(f.account,f.operation,f.local.billingPrice(),null,"sub_owned",null,null,true,false)).outcome()).isEqualTo(Outcome.ACCEPTED)).doesNotThrowAnyException();
        assertThat(proofs.resume(f.account.id(),f.operation.id())).hasValueSatisfying(p->{assertThat(p.invoice()).isEqualTo("in_resume");assertThat(p.period().start()).isEqualTo(now);});
        assertThat(f.local.billingStatus()).isEqualTo(Subscription.Status.PAUSED);assertThat(f.local.paidThrough()).isNull();assertThat(f.graph.http.calls().stream().filter(c->c.method().equals("POST"))).singleElement().satisfies(c->{assertThat(c.path()).isEqualTo("/v1/subscriptions/sub_owned/resume");assertThat(c.header("Idempotency-Key")).isEqualTo(f.operation.providerOperationKey());assertThat(c.form()).containsEntry("payment_behavior","resume_on_payment_success");});
    }

    // Removing the trial branch must refuse this admitted original request rather than create it paid.
    @Test void optionalTrialUsesAnExactServerEndAndPausePolicy(){
        var o=f.operation;
        f.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),o.status(),o.authorityScope(),o.keyDigest(),"v1.trial."+"a".repeat(64),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),null,null,o.createdAt(),o.updatedAt(),o.version());
        configure();long now=this.now.getEpochSecond();
        f.graph.subscription.addProperty("status","trialing");
        f.graph.subscription.addProperty("trial_start",now);
        f.graph.subscription.addProperty("trial_end",now+14*86400);
        f.graph.subscription.add("trial_settings",com.google.gson.JsonParser.parseString("{\"end_behavior\":{\"missing_payment_method\":\"pause\"}}"));
        f.graph.subscription.add("metadata",f.metadata());
        f.identity();f.graph.http.respond("GET","/v1/customers/cus_owned",f.graph.customer.toString());f.identity();f.graph.http.respond("POST","/v1/subscriptions",f.graph.subscription.toString());
        var request=new Command(f.account,f.operation,f.local.billingPrice(),null,null,null,null,true,true);
        assertThatCode(()->assertThat(f.adapter.create(request).outcome()).isEqualTo(Outcome.ACCEPTED)).doesNotThrowAnyException();
        var post=f.graph.http.calls().stream().filter(c->c.method().equals("POST")).toList();
        assertThat(post).singleElement().satisfies(c->{
            assertThat(c.form()).containsEntry("trial_settings[end_behavior][missing_payment_method]","pause").doesNotContainKey("trial_period_days");
            assertThat(Long.parseLong(c.form().get("trial_end"))).isBetween(now+14*86400,Instant.now().getEpochSecond()+14*86400);
        });
        assertThat(f.local.trial()).isNull();assertThat(f.local.paidThrough()).isNull();
        assertThat(proofs.trial.requestedEnd()).isEqualTo(now+14*86400);
    }
    void configure(){
        var credentials=new StripeCredentialResolver(new StripeSandboxProperties(true,"sk_test_"+"A".repeat(32),"pk_test_"+"B".repeat(32),"whsec_"+"C".repeat(32)));
        var factory=new StripeClientFactory(){@Override public com.stripe.StripeClient create(StripeCredentials ignored){return f.graph.http.client();}};
        org.mockito.Mockito.when(configuredActions.enabled()).thenReturn(true);
        f.adapter=new StripeSubscriptionProviderAdapter(credentials,factory,new StripeAccountVerificationAdapter(credentials,factory),f.steps,f.bindings,f.customerAdapter,StripeBillingGrantTokens::hash,f.accounts,f.sites,f.customerStore,f.subscriptions,f.operations,f.catalog,f.mappings,Clock.systemUTC(),()->configuredActions,proofs,new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}});
    }
    void chooseTrial(dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy policy){
        var s=f.local;var t=s.billingPrice();var p=new SubscriptionPriceTerms(t.priceId(),t.priceKey(),t.version(),t.productId(),t.regularAmount(),t.interval(),t.trialDays(),policy,t.taxMode());
        f.local=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),p,null,s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),s.debt(),s.terminalReason(),s.revision(),s.createdAt(),s.updatedAt(),s.lastReconciledAt());
        org.mockito.Mockito.when(f.catalog.terms(s.siteId(),p.priceId())).thenReturn(p);
        var o=f.operation;f.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),o.status(),o.authorityScope(),o.keyDigest(),"v1.trial."+"a".repeat(64),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),null,null,o.createdAt(),o.updatedAt(),o.version());
    }
    static class MemoryProofs implements SubscriptionBillingProofStore {
        boolean setupAuthorized=true;
        Trial trial;final Map<UUID,Setup> setups=new HashMap<>();final Map<UUID,Resume> resumes=new HashMap<>();
        public Optional<Trial> trial(PaymentAccountId a,UUID s){return Optional.ofNullable(trial).filter(t->t.account().equals(a)&&t.subscription().equals(s));}
        public synchronized Trial reserveTrial(Subscription s,SubscriptionOperation o,long end){if(trial==null)trial=new Trial(o.id(),s.id(),s.paymentAccountId(),end,null,null,null,null,null);return trial;}
        public Trial bindTrial(Trial t,UUID step,String ref,String item,String invoice,long start){if(t.verified())return t;return trial=new Trial(t.operation(),t.subscription(),t.account(),t.requestedEnd(),step,ref,item,invoice,start);}
        public Optional<Setup> setup(PaymentAccountId a,UUID op){return Optional.ofNullable(setups.get(op));}
        public Optional<Setup> setupReference(PaymentAccountId a,String r){return setups.values().stream().filter(s->s.account().equals(a)&&s.reference().equals(r)).findFirst();}
        public Optional<Setup> method(PaymentAccountId a,UUID sub,String m){return setups.values().stream().filter(s->s.account().equals(a)&&s.subscription().equals(sub)&&Objects.equals(s.method(),m)).findFirst();}
        public Setup recordSetup(Subscription s,SubscriptionOperation o,UUID step,String ref){var p=new Setup(o.id(),s.id(),s.paymentAccountId(),step,ref,null);setups.putIfAbsent(o.id(),p);return setups.get(o.id());}
        public Setup bindMethod(Setup p,String m){var saved=new Setup(p.operation(),p.subscription(),p.account(),p.step(),p.reference(),m);setups.put(p.operation(),saved);return saved;}
        public Optional<Resume> resume(PaymentAccountId a,UUID op){return Optional.ofNullable(resumes.get(op));}
        public Resume recordResume(Subscription s,SubscriptionOperation o,UUID step,String invoice,Subscription.Period period){var p=new Resume(o.id(),s.id(),s.paymentAccountId(),step,s.providerReference(),invoice,period);resumes.putIfAbsent(o.id(),p);return resumes.get(o.id());}
        public void authorizeResume(Subscription s,SubscriptionOperation o){}
        public void authorizeSetup(Subscription s,SubscriptionOperation o){if(!setupAuthorized)throw new IllegalStateException("Original setup authority changed");}
    }
}
