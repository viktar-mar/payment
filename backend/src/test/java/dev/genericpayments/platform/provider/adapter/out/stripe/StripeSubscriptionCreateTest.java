package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.google.gson.*;
import dev.genericpayments.platform.catalog.application.model.*;
import dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.domain.Customer;
import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StripeSubscriptionCreateTest {
    final StripeBillingSnapshotMapperTest graph=new StripeBillingSnapshotMapperTest();
    final LoadPaymentAccountPort accounts=mock(LoadPaymentAccountPort.class);
    final LoadSitePort sites=mock(LoadSitePort.class);
    final CustomerStore customerStore=mock(CustomerStore.class);
    final SubscriptionStore subscriptions=mock(SubscriptionStore.class);
    final SubscriptionOperationStore operations=mock(SubscriptionOperationStore.class);
    final SubscriptionCatalogPort catalog=mock(SubscriptionCatalogPort.class);
    final ProviderCatalogMappingPort mappings=mock(ProviderCatalogMappingPort.class);
    final StripeBillingBindingPort bindings=mock(StripeBillingBindingPort.class);
    final StripeBillingCustomerAdapter customerAdapter=mock(StripeBillingCustomerAdapter.class);
    final StripeBillingCustomerAdapterTest.MemoryOperations steps=new StripeBillingCustomerAdapterTest.MemoryOperations();
    PaymentAccount account;Subscription local;SubscriptionOperation operation;CustomerBinding customer;
    StripeSubscriptionProviderAdapter adapter;
    @BeforeEach void setup() throws Exception {
        graph.setup();account=graph.account;var s=graph.local;
        local=copy(s,null,Subscription.Status.INCOMPLETE);
        var now=Instant.now().minusSeconds(2);
        var operationId=UUID.randomUUID();
        operation=new SubscriptionOperation(operationId,s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.CREATE,
            SubscriptionOperation.Status.RESERVED,"customer:owned","key","v1.paid."+"a".repeat(64),"subscription:"+account.id().value()+":"+operationId+":CREATE",0,1,null,null,now,now,1);
        customer=new CustomerBinding(UUID.randomUUID(),account.id(),s.siteId(),s.customerId(),"cus_owned",UUID.randomUUID(),0);
        when(accounts.findById(account.id())).thenReturn(Optional.of(account));
        when(sites.findById(s.siteId())).thenReturn(Optional.of(Site.create(s.siteId(),new SiteKey("billing-site"),"Billing",account.id(),Set.of(URI.create("https://shop.test")),Set.of(AuthenticationMode.SITE_TOKEN),PaymentMethodPolicy.automatic())));
        when(customerStore.findById(s.siteId(),s.customerId())).thenReturn(Optional.of(Customer.create(s.customerId(),s.siteId(),null,null,now)));
        when(subscriptions.findOwned(account.id(),s.id())).thenAnswer(x->Optional.of(local));
        when(operations.find(s.siteId(),s.id(),operation.id())).thenAnswer(x->Optional.of(operation));
        when(catalog.terms(s.siteId(),s.billingPrice().priceId())).thenReturn(s.billingPrice());
        when(mappings.findPrice(account.id(),s.billingPrice().priceId())).thenReturn(Optional.of(new ProviderPriceRef("price_owned")));
        when(mappings.findProduct(account.id(),s.productId())).thenReturn(Optional.of(new ProviderProductRef("prod_owned")));
        when(mappings.findPricesForProduct(account.id(),s.siteId(),s.productId())).thenReturn(List.of(new ProviderCatalogMapping(account.id(),s.productId(),new ProviderProductRef("prod_owned"),s.billingPrice().priceId(),new ProviderPriceRef("price_owned"),now)));
        when(bindings.findCustomer(account.id(),s.siteId(),s.customerId())).thenReturn(Optional.of(customer));
        DigestPort digest=value->{try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new AssertionError(e);}};
        var credentials=new StripeCredentialResolver(new StripeSandboxProperties(true,"sk_test_"+"A".repeat(32),"pk_test_"+"B".repeat(32),"whsec_"+"C".repeat(32)));
        var factory=new StripeClientFactory(){@Override public com.stripe.StripeClient create(StripeCredentials ignored){return graph.http.client();}};
        adapter=new StripeSubscriptionProviderAdapter(credentials,factory,new StripeAccountVerificationAdapter(credentials,factory),steps,bindings,customerAdapter,digest,accounts,sites,customerStore,subscriptions,operations,catalog,mappings,Clock.systemUTC());
        graph.subscription.addProperty("created",Instant.now().getEpochSecond());graph.subscription.add("metadata",metadata());
        graph.subscription.add("payment_settings",JsonParser.parseString("{\"payment_method_types\":[\"card\"],\"save_default_payment_method\":\"on_subscription\"}"));
    }
    @AfterEach void close(){graph.close();}
    @Test void positiveFixtureCreateSharesOneInstantAfterTheSetupSecondHasPassed(){
        var later=Clock.fixed(Instant.ofEpochSecond(graph.subscription.get("created").getAsLong()+1),ZoneOffset.UTC);
        org.springframework.test.util.ReflectionTestUtils.setField(adapter,"providerClock",later);
        assertThatCode(this::create).doesNotThrowAnyException();
        assertThat(steps.only().firstDispatchedAt().getEpochSecond()).isEqualTo(graph.subscription.get("created").getAsLong());
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(adapter,"providerClock")).isSameAs(later);
    }
    @Test void intentionallyEarlierCreatedResponseStillRefusesTheOriginalDispatchWindow(){
        var at=Instant.now();org.springframework.test.util.ReflectionTestUtils.setField(adapter,"providerClock",Clock.fixed(at,ZoneOffset.UTC));graph.subscription.addProperty("created",at.minusSeconds(1).getEpochSecond());
        identity();graph.http.respond("POST","/v1/subscriptions",graph.subscription.toString());
        assertThat(adapter.create(command()).outcome()).isEqualTo(Outcome.UNKNOWN);assertThat(steps.only().status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(steps.only().firstDispatchedAt()).isEqualTo(at);assertThat(steps.only().retryBefore()).isEqualTo(at.plus(Duration.ofHours(23)));
    }
    @Test void capabilitiesRemainReadableInsideTheQueryTransactionWhileWritesAreRejected(){
        var manager=new StripeAccountVerificationAdapterTest.TestTransactionManager();var proxy=new org.springframework.aop.framework.ProxyFactory(adapter);
        proxy.setProxyTargetClass(true);proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var proxied=(StripeSubscriptionProviderAdapter)proxy.getProxy();
        assertThatCode(()->new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->{
            assertThat(proxied.capabilities(account)).isEqualTo(new Capabilities(false,false,false,false));
            assertThatThrownBy(()->proxied.create(command())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
            assertThatThrownBy(()->proxied.retrieve(lookup())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
            assertThatThrownBy(()->proxied.recoverCreate(account,local.id(),operation.id(),"evt_original")).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        })).doesNotThrowAnyException();assertThat(graph.http.calls()).isEmpty();assertThat(steps.rows).isEmpty();
    }
    @Test void disabledOriginalAccountCanStillRenderCapabilitiesWithoutPermittingDispatch(){var disabled=account.disable();when(accounts.findById(account.id())).thenReturn(Optional.of(disabled));assertThatCode(()->assertThat(adapter.capabilities(disabled)).isEqualTo(new Capabilities(false,false,false,false))).doesNotThrowAnyException();assertThatThrownBy(()->adapter.create(new Command(disabled,operation,local.billingPrice(),null,null,null,null,true,false))).isInstanceOf(PaymentApplicationException.class);assertThat(graph.http.calls()).isEmpty();}
    // Missing dispatch, a fresh key or a separate PI all break this observable wire receipt.
    @Test void incompleteCreateUsesOriginalStepAndNeverAdvertisesASecret(){
        graph.subscription.addProperty("status","incomplete");graph.subscription.addProperty("client_secret","unsafe_fixture_secret");
        identity();graph.http.respond("POST","/v1/subscriptions",graph.subscription.toString());
        var result=adapter.create(command());assertThat(result.outcome()).isEqualTo(Outcome.ACCEPTED);assertThat(result.reference()).isEqualTo("sub_owned");assertThat(result.action()).isNull();assertThat(result.toString()).doesNotContain("unsafe_fixture_secret");
        var step=steps.only();assertThat(step.kind()).isEqualTo(ProviderOperation.Kind.SUBSCRIPTION_CREATE);assertThat(step.resourceId()).isEqualTo(operation.id());assertThat(step.operationKey()).isEqualTo(operation.providerOperationKey());assertThat(step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        var post=graph.http.calls().stream().filter(c->c.method().equals("POST")).toList();assertThat(post).singleElement().satisfies(c->{assertThat(c.path()).isEqualTo("/v1/subscriptions");assertThat(c.header("Idempotency-Key")).isEqualTo(operation.providerOperationKey());assertThat(c.form()).containsEntry("customer","cus_owned").containsEntry("billing_mode[type]","classic").containsEntry("collection_method","charge_automatically").containsEntry("payment_behavior","default_incomplete").containsEntry("items[0][price]","price_owned").containsEntry("items[0][quantity]","1").containsEntry("payment_settings[payment_method_types][0]","card").containsEntry("payment_settings[save_default_payment_method]","on_subscription").doesNotContainKeys("trial_end","trial_period_days");});
        assertThat(local.billingStatus()).isEqualTo(Subscription.Status.INCOMPLETE);assertThat(local.paidThrough()).isNull();
    }
    @Test void acknowledgedReplayDoesNotCreateAgain(){create();var first=steps.only();var result=adapter.create(command());assertThat(result.reference()).isEqualTo("sub_owned");assertThat(steps.only()).isEqualTo(first);assertThat(graph.http.requestCount("POST","/v1/subscriptions")).isEqualTo(1);}
    @Test void disconnectedCreateKeepsOriginalKeyAndCanReplayOnlyInsideWindow(){
        identity();graph.http.disconnectNext("POST","/v1/subscriptions");assertThat(adapter.create(command()).outcome()).isEqualTo(Outcome.UNKNOWN);var first=steps.only();
        create();assertThat(steps.only().firstDispatchedAt()).isEqualTo(first.firstDispatchedAt());assertThat(steps.only().retryBefore()).isEqualTo(first.retryBefore());
        assertThat(graph.http.calls().stream().filter(c->c.method().equals("POST"))).hasSize(2).allSatisfy(c->assertThat(c.header("Idempotency-Key")).isEqualTo(operation.providerOperationKey()));
    }
    @Test void expiredUnknownNeverCreatesAReplacement(){unknown();steps.expire();identity();assertThat(adapter.create(command()).outcome()).isEqualTo(Outcome.UNKNOWN);assertThat(graph.http.requestCount("POST","/v1/subscriptions")).isEqualTo(1);}
    @Test void staleOwnerCannotAcknowledge(){identity();graph.http.respond("POST","/v1/subscriptions",graph.subscription.toString());steps.stealBeforeAck=true;assertThatThrownBy(()->adapter.create(command())).isInstanceOf(PaymentApplicationException.class);assertThat(steps.only().status()).isEqualTo(ProviderOperation.Status.DISPATCHING);}
    @Test void existingCustomerRemainsOnOriginalAccountAfterSiteSwitch(){
        var site=sites.findById(local.siteId()).orElseThrow();when(sites.findById(local.siteId())).thenReturn(Optional.of(new Site(site.id(),site.key(),site.displayName(),new PaymentAccountId(UUID.randomUUID()),site.status(),site.allowedOrigins(),site.authenticationModes(),site.paymentMethodPolicy())));
        create();assertThat(steps.only().accountId()).isEqualTo(account.id());verifyNoInteractions(customerAdapter);
    }
    @Test void missingBindingUsesExistingCustomerBoundary(){when(bindings.findCustomer(account.id(),local.siteId(),local.customerId())).thenReturn(Optional.empty());when(customerAdapter.ensureCustomer(account,local.siteId(),local.customerId())).thenReturn(customer);create();assertThat(steps.only().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);}
    @ParameterizedTest @ValueSource(strings={"operation","account","customer","terms","mapping","priceShape","productShape","consent","trial"})
    void foreignOrUnsupportedRequestCannotDispatch(String mutation){
        var request=command();switch(mutation){case "operation"->when(operations.find(local.siteId(),local.id(),operation.id())).thenReturn(Optional.empty());case "account"->when(accounts.findById(account.id())).thenReturn(Optional.of(account.disable()));case "customer"->when(customerStore.findById(local.siteId(),local.customerId())).thenReturn(Optional.empty());case "terms"->when(catalog.terms(local.siteId(),local.billingPrice().priceId())).thenThrow(new IllegalStateException());case "mapping"->when(mappings.findPrice(account.id(),local.billingPrice().priceId())).thenReturn(Optional.empty());case "priceShape"->when(mappings.findPrice(account.id(),local.billingPrice().priceId())).thenReturn(Optional.of(new ProviderPriceRef("foreign")));case "productShape"->when(mappings.findProduct(account.id(),local.productId())).thenReturn(Optional.of(new ProviderProductRef("foreign")));case "consent"->request=new Command(account,operation,local.billingPrice(),null,null,null,null,false,false);case "trial"->request=new Command(account,operation,local.billingPrice(),null,null,null,null,true,true);}
        var selected=request;assertThatThrownBy(()->adapter.create(selected)).isInstanceOf(RuntimeException.class);assertThat(graph.http.calls()).isEmpty();assertThat(steps.rows).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"customer","metadata","quantity","price","livemode","collection","mode","method","priceType","priceObject","itemObject","subscriptionCurrency"})
    void malformedCreateNeverAcknowledges(String mutation){
        switch(mutation){case "customer"->graph.subscription.addProperty("customer","cus_foreign");case "metadata"->graph.subscription.getAsJsonObject("metadata").addProperty("subscription_id",UUID.randomUUID().toString());case "quantity"->graph.item().addProperty("quantity",2);case "price"->graph.price().addProperty("id","price_foreign");case "livemode"->graph.subscription.addProperty("livemode",true);case "collection"->graph.subscription.addProperty("collection_method","send_invoice");case "mode"->graph.subscription.getAsJsonObject("billing_mode").addProperty("type","flexible");case "method"->graph.subscription.getAsJsonObject("payment_settings").add("payment_method_types",JsonParser.parseString("[\"sepa_debit\"]"));case "priceType"->graph.price().addProperty("type","one_time");case "priceObject"->graph.price().addProperty("object","product");case "itemObject"->graph.item().addProperty("object","invoice_item");case "subscriptionCurrency"->graph.subscription.addProperty("currency","usd");}
        identity();graph.http.respond("POST","/v1/subscriptions",graph.subscription.toString());assertThat(adapter.create(command()).outcome()).isEqualTo(Outcome.UNKNOWN);assertThat(steps.only().status()).isEqualTo(ProviderOperation.Status.UNKNOWN);
    }
    @Test void changedOriginalAccountAfterHttpCannotAcknowledge(){when(accounts.findById(account.id())).thenReturn(Optional.of(account),Optional.of(account),Optional.of(account.disable()));identity();graph.http.respond("POST","/v1/subscriptions",graph.subscription.toString());assertThatThrownBy(()->adapter.create(command())).isInstanceOf(PaymentApplicationException.class);assertThat(steps.only().status()).isEqualTo(ProviderOperation.Status.DISPATCHING);}
    @Test void lateInitialCashCompletesOriginalCreateAfterCurrentPeriodAdvanced(){
        create();local=copy(local,"sub_owned",Subscription.Status.INCOMPLETE);graph.invoice.addProperty("billing_reason","subscription_create");graph.item().addProperty("current_period_start",1802678400L);graph.item().addProperty("current_period_end",1805270400L);
        identity();graph.graph();var snapshot=adapter.retrieve(lookup());assertThat(snapshot.operationObservation()).isEqualTo(new OperationObservation(operation.providerOperationKey(),OperationState.APPLIED));assertThat(snapshot.invoices().getFirst().period()).isNotEqualTo(snapshot.period());
    }
    @Test void renewalCashAloneCannotCompleteCreate(){create();local=copy(local,"sub_owned",Subscription.Status.INCOMPLETE);identity();graph.graph();assertThat(adapter.retrieve(lookup()).operationObservation().state()).isEqualTo(OperationState.PENDING);}
    @Test void initialCashUsesOriginalArchivedTermsAfterCurrentPriceChanges(){
        create();var first=local.billingPrice();var id=new dev.genericpayments.platform.catalog.domain.PriceId(UUID.randomUUID());
        var next=new SubscriptionPriceTerms(id,first.priceKey(),2,first.productId(),first.regularAmount(),first.interval(),first.trialDays(),first.trialPolicy(),first.taxMode());
        local=new Subscription(local.id(),local.siteId(),local.customerId(),local.productId(),local.paymentAccountId(),next,null,Subscription.Status.INCOMPLETE,"sub_owned",null,null,null,null,null,null,null,0,local.createdAt(),local.updatedAt(),null);
        when(catalog.terms(local.siteId(),id)).thenReturn(next);when(mappings.findPrice(account.id(),id)).thenReturn(Optional.of(new ProviderPriceRef("price_next")));
        when(mappings.findPricesForProduct(account.id(),local.siteId(),local.productId())).thenReturn(List.of(
            new ProviderCatalogMapping(account.id(),local.productId(),new ProviderProductRef("prod_owned"),first.priceId(),new ProviderPriceRef("price_owned"),Instant.now()),
            new ProviderCatalogMapping(account.id(),local.productId(),new ProviderProductRef("prod_owned"),id,new ProviderPriceRef("price_next"),Instant.now())));
        graph.price().addProperty("id","price_next");graph.price().getAsJsonObject("metadata").addProperty("local_price_id",id.value().toString());graph.price().getAsJsonObject("metadata").addProperty("local_price_version","2");graph.invoice.addProperty("billing_reason","subscription_create");
        identity();graph.graph();var result=adapter.retrieve(lookup());assertThat(result.billingPrice()).isEqualTo(next);assertThat(result.invoices().getFirst().price()).isEqualTo(first);assertThat(result.operationObservation().state()).isEqualTo(OperationState.APPLIED);
    }
    @Test void originalAckCanRepairLostLocalReceiptWithoutAnotherCreate(){create();identity();graph.graph();assertThat(adapter.retrieve(lookup()).subscriptionReference()).isEqualTo("sub_owned");assertThat(graph.http.requestCount("POST","/v1/subscriptions")).isEqualTo(1);assertThat(local.providerReference()).isNull();}
    @Test void unknownWithoutReferenceCannotBeResolvedByMetadata(){unknown();assertThatThrownBy(()->adapter.retrieve(lookup())).isInstanceOf(PaymentApplicationException.class);assertThat(graph.http.requestCount("GET","/v1/subscriptions/sub_owned")).isZero();}
    @Test void earlyCreatedEventRecoversExpiredUnknownWithoutAnotherWrite(){
        unknown();var first=steps.only();steps.expire();first=steps.only();graph.subscription.addProperty("created",first.firstDispatchedAt().getEpochSecond());identity();event("evt_original",first.operationKey(),first.firstDispatchedAt().getEpochSecond());graph.http.respond("GET","/v1/customers/cus_owned",graph.customer.toString());graph.http.respond("GET","/v1/subscriptions/sub_owned",graph.subscription.toString());
        var result=adapter.recoverCreate(account,local.id(),operation.id(),"evt_original");assertThat(result.reference()).isEqualTo("sub_owned");assertThat(result.action()).isNull();assertThat(steps.only().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);assertThat(graph.http.requestCount("POST","/v1/subscriptions")).isEqualTo(1);
    }
    @Test void metadataCannotReplaceOriginalEventKey(){unknown();var step=steps.only();identity();event("evt_wrong","foreign-key",step.firstDispatchedAt().getEpochSecond());assertThatThrownBy(()->adapter.recoverCreate(account,local.id(),operation.id(),"evt_wrong")).isInstanceOfSatisfying(PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("PROVIDER_EVIDENCE_MISMATCH"));assertThat(steps.only().status()).isEqualTo(ProviderOperation.Status.UNKNOWN);}
    @ParameterizedTest @ValueSource(strings={"version","live","context","account","type","eventId","time","createdTime","customer","metadata","price","freshCreated","freshReference","freshCustomer"})
    void originalEventAndFreshResourceMustBothProveTheOriginalCreate(String mutation){
        unknown();var step=steps.only();long created=step.firstDispatchedAt().getEpochSecond();graph.subscription.addProperty("created",created);
        var data=JsonParser.parseString(eventJson("evt_original",step.operationKey(),created)).getAsJsonObject();
        switch(mutation){case "version"->data.addProperty("api_version","2025-01-01.fixture");case "live"->data.addProperty("livemode",true);case "context"->data.addProperty("context","foreign");case "account"->data.addProperty("account","acct_other");case "type"->data.addProperty("type","customer.subscription.updated");case "eventId"->data.addProperty("id","evt_other");case "time"->data.addProperty("created",created-1);case "createdTime"->data.getAsJsonObject("data").getAsJsonObject("object").addProperty("created",created-1);case "customer"->data.getAsJsonObject("data").getAsJsonObject("object").addProperty("customer","cus_other");case "metadata"->data.getAsJsonObject("data").getAsJsonObject("object").getAsJsonObject("metadata").addProperty("operation_id",UUID.randomUUID().toString());case "price"->data.getAsJsonObject("data").getAsJsonObject("object").getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject().getAsJsonObject("price").addProperty("id","price_other");case "freshCreated"->graph.subscription.addProperty("created",created+1);case "freshReference"->graph.subscription.addProperty("id","sub_other");case "freshCustomer"->graph.customer.addProperty("id","cus_other");}
        identity();graph.http.respond("GET","/v1/events/evt_original",data.toString());graph.http.respond("GET","/v1/customers/cus_owned",graph.customer.toString());graph.http.respond("GET","/v1/subscriptions/sub_owned",graph.subscription.toString());
        assertThatThrownBy(()->adapter.recoverCreate(account,local.id(),operation.id(),"evt_original")).isInstanceOfSatisfying(PaymentApplicationException.class,e->assertThat(e.code()).isEqualTo("PROVIDER_EVIDENCE_MISMATCH"));assertThat(steps.only().status()).isEqualTo(ProviderOperation.Status.UNKNOWN);assertThat(graph.http.requestCount("POST","/v1/subscriptions")).isEqualTo(1);
    }
    @Test void anActiveLeaseCannotBeRecoveredByAnEarlyEvent(){unknown();var step=steps.only();steps.claim(step.id(),"another-worker",Instant.now(),Duration.ofSeconds(60));assertThatThrownBy(()->adapter.recoverCreate(account,local.id(),operation.id(),"evt_original")).isInstanceOf(PaymentApplicationException.class);assertThat(graph.http.requestCount("GET","/v1/events/evt_original")).isZero();}
    @Test void eventBeforeAckRecoversExpiredLeaseAndFencesTheLateAck(){
        unknown();var step=steps.only();var lost=steps.claim(step.id(),"lost-worker",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        steps.replace(lost,lost.status(),null,lost.firstDispatchedAt(),lost.retryBefore(),lost.leaseOwner(),Instant.now().minusSeconds(1));
        graph.subscription.addProperty("created",lost.firstDispatchedAt().getEpochSecond());identity();event("evt_original",lost.operationKey(),lost.firstDispatchedAt().getEpochSecond());graph.http.respond("GET","/v1/customers/cus_owned",graph.customer.toString());graph.http.respond("GET","/v1/subscriptions/sub_owned",graph.subscription.toString());
        assertThat(adapter.recoverCreate(account,local.id(),operation.id(),"evt_original").reference()).isEqualTo("sub_owned");
        assertThatThrownBy(()->steps.acknowledge(lost.id(),"lost-worker",lost.version(),"sub_wrong")).isInstanceOf(PaymentApplicationException.class);assertThat(steps.only().providerReference()).isEqualTo("sub_owned");assertThat(graph.http.requestCount("POST","/v1/subscriptions")).isEqualTo(1);
    }
    @Test void forgedLookupReferenceCannotSelectAnotherSubscription(){create();assertThatThrownBy(()->adapter.retrieve(new Lookup(account,local.id(),"sub_other",operation.providerOperationKey()))).isInstanceOf(PaymentApplicationException.class);assertThat(graph.http.requestCount("GET","/v1/subscriptions/sub_other")).isZero();}
    @Test void missingOldCustomerBindingAfterSiteSwitchRefusesWithoutRelocation(){when(bindings.findCustomer(account.id(),local.siteId(),local.customerId())).thenReturn(Optional.empty());when(customerAdapter.ensureCustomer(account,local.siteId(),local.customerId())).thenThrow(new PaymentApplicationException("PROVIDER_OPERATION_CONFLICT",409,"Provider operation conflicts with the request"));assertThatThrownBy(()->adapter.create(command())).isInstanceOf(PaymentApplicationException.class);assertThat(graph.http.calls()).isEmpty();assertThat(steps.rows).isEmpty();}
    @Test void mappingOverflowCannotProducePartialMoneyFacts(){create();when(mappings.findPricesForProduct(account.id(),local.siteId(),local.productId())).thenReturn(Collections.nCopies(101,new ProviderCatalogMapping(account.id(),local.productId(),new ProviderProductRef("prod_owned"),local.billingPrice().priceId(),new ProviderPriceRef("price_owned"),Instant.now())));assertThatThrownBy(()->adapter.retrieve(lookup())).isInstanceOf(PaymentApplicationException.class);assertThat(graph.http.requestCount("GET","/v1/invoices")).isZero();}
    @Test void futureCapabilitiesAndBrowserActionsStayUnavailable(){assertThat(adapter.capabilities(account)).isEqualTo(new Capabilities(false,false,false,false));assertThat(adapter.nextAction(lookup())).isNull();for(var action:List.<Runnable>of(()->adapter.setup(command()),()->adapter.recover(command()),()->adapter.resume(command()),()->adapter.cancel(command(),false),()->adapter.withdrawCancel(command()),()->adapter.change(command()),()->adapter.clearChange(command()),()->adapter.confirmAction(command(),"unsafe_fixture_secret"),()->adapter.portal(command(),"https://shop.test")))assertThatThrownBy(action::run).isInstanceOf(PaymentApplicationException.class);assertThat(graph.http.calls()).isEmpty();}
    Command command(){return new Command(account,operation,local.billingPrice(),null,null,null,null,true,false);}
    Lookup lookup(){return new Lookup(account,local.id(),local.providerReference(),operation.providerOperationKey());}
    void identity(){graph.http.respond("GET","/v1/account","{\"id\":\"acct_expected\",\"object\":\"account\"}");}
    // Known-positive responses share the dispatch instant; malformed-response tests call the adapter directly.
    void create(){
        var original=(Clock)org.springframework.test.util.ReflectionTestUtils.getField(adapter,"providerClock");
        var at=steps.find(account.id(),operation.providerOperationKey()).map(ProviderOperation::firstDispatchedAt).orElseGet(Instant::now);
        graph.subscription.addProperty("created",at.getEpochSecond());
        org.springframework.test.util.ReflectionTestUtils.setField(adapter,"providerClock",Clock.fixed(at,ZoneOffset.UTC));
        try{identity();graph.http.respond("POST","/v1/subscriptions",graph.subscription.toString());assertThat(adapter.create(command()).outcome()).isEqualTo(Outcome.ACCEPTED);}
        finally{org.springframework.test.util.ReflectionTestUtils.setField(adapter,"providerClock",original);}
    }
    void unknown(){identity();graph.http.disconnectNext("POST","/v1/subscriptions");assertThat(adapter.create(command()).outcome()).isEqualTo(Outcome.UNKNOWN);}
    JsonObject metadata(){var m=new JsonObject();m.addProperty("account_id",account.id().value().toString());m.addProperty("site_id",local.siteId().value().toString());m.addProperty("customer_id",local.customerId().value().toString());m.addProperty("subscription_id",local.id().toString());m.addProperty("operation_id",operation.id().toString());return m;}
    void event(String id,String key,long created){graph.http.respond("GET","/v1/events/"+id,eventJson(id,key,created));}
    String eventJson(String id,String key,long created){return "{\"id\":\""+id+"\",\"object\":\"event\",\"api_version\":\"2026-09-30.endive\",\"livemode\":false,\"type\":\"customer.subscription.created\",\"created\":"+created+",\"request\":{\"id\":\"req_owned\",\"idempotency_key\":\""+key+"\"},\"data\":{\"object\":"+graph.subscription+"}}";}
    static Subscription copy(Subscription s,String ref,Subscription.Status status){return new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),null,status,ref,null,null,null,null,null,null,null,0,s.createdAt(),s.updatedAt(),null);}
}
