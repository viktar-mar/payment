package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.stripe.exception.ApiConnectionException;
import com.stripe.net.RequestOptions;
import com.stripe.param.billingportal.ConfigurationCreateParams;
import com.stripe.param.billingportal.SessionCreateParams;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionPortalUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionInteractionTransaction;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionPortalService;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pinned Portal wire contract and actual local admission/publication boundaries. */
class StripeBillingPortalTest {
    @Test void provedOwnedCustomerCreatesConfiguredRealNavigationOnlyOnce() throws Exception {
        try(var f=new PortalCase()){
            f.remote();f.fixture.graph.http.respond("POST",CONFIGURATIONS,f.config());f.fixture.graph.http.respond("GET",CONFIGURATION,f.config());f.fixture.graph.http.respond("POST",SESSIONS,f.sessionJson());
            var before=f.fixture.local;var received=new java.util.concurrent.atomic.AtomicReference<PortalNavigation>();
            assertThatCode(()->received.set(f.adapter.create(f.command(),RETURN))).doesNotThrowAnyException();var navigation=received.get();
            assertThat(navigation.simulated()).isFalse();assertThat(navigation.navigationUrl()).isEqualTo(PortalCase.REAL_URL);assertThat(navigation.toString()).doesNotContain("test_fixture");
            assertThat(f.fixture.local).isEqualTo(before);assertThat(f.saved.reference()).isEqualTo("bps_owned");
            assertThat(f.fixture.steps.rows.values()).hasSize(2).allSatisfy(s->assertThat(s.status()).isEqualTo(dev.genericpayments.platform.provider.application.model.ProviderOperation.Status.ACKNOWLEDGED));
            assertThat(f.fixture.steps.rows.values()).filteredOn(s->s.kind()==dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.PORTAL_SESSION_CREATE).singleElement().satisfies(s->assertThat(s.resourceId()).isEqualTo(f.fixture.operation.id()));
            f.remote();f.fixture.graph.http.respond("GET",CONFIGURATION,f.config());
            assertThatThrownBy(()->f.adapter.create(f.command(),RETURN)).isInstanceOf(PaymentApplicationException.class);
            assertThat(f.fixture.graph.http.requestCount("POST",SESSIONS)).isEqualTo(1);assertThat(f.fixture.graph.http.requestCount("POST",CONFIGURATIONS)).isEqualTo(1);
        }
    }
    @ParameterizedTest @ValueSource(strings={"foreignSub","incomplete","foreignInvoice","config","session","url","missingCancellationFlag"})
    void unprovedScopeOrProviderResponseCannotPublishNavigation(String fault) throws Exception {
        try(var f=new PortalCase()){
            if(fault.equals("foreignSub"))f.remoteSubscription=f.remoteSubscription.replace("sub_owned","sub_foreign");
            if(fault.equals("foreignInvoice"))f.invoice=f.invoice.replace("in_owned","in_foreign");
            if(fault.equals("incomplete"))f.hasMore="null";
            if(fault.equals("missingCancellationFlag")){var remote=com.google.gson.JsonParser.parseString(f.remoteSubscription).getAsJsonObject();remote.remove("cancel_at_period_end");f.remoteSubscription=remote.toString();}
            f.remote();var config=f.config();if(fault.equals("config"))config=config.replace("\"payment_method_update\":{\"enabled\":false}","\"payment_method_update\":{\"enabled\":true}");
            f.fixture.graph.http.respond("POST",CONFIGURATIONS,config);f.fixture.graph.http.respond("GET",CONFIGURATION,config);
            var response=f.sessionJson();if(fault.equals("session"))response=response.replace("cus_owned","cus_foreign");if(fault.equals("url"))response=response.replace("billing.stripe.com","billing.stripe.com.evil");
            f.fixture.graph.http.respond("POST",SESSIONS,response);
            assertThatThrownBy(()->f.adapter.create(f.command(),RETURN)).isInstanceOf(PaymentApplicationException.class);
            assertThat(f.saved).isNull();if(!Set.of("session","url").contains(fault))assertThat(f.fixture.graph.http.requestCount("POST",SESSIONS)).isZero();
        }
    }
    @Test void compatibleSiblingWithDifferentRetainedTermsAndTerminalHistoryIsAllowed() throws Exception {
        try(var f=new PortalCase()){
            var s=f.fixture.local;var next=new SubscriptionPriceTerms(new dev.genericpayments.platform.catalog.domain.PriceId(UUID.randomUUID()),"sibling",2,s.productId(),dev.genericpayments.platform.catalog.domain.Money.of(2000,"EUR"),s.billingPrice().interval(),0,s.billingPrice().trialPolicy(),s.billingPrice().taxMode());
            var sibling=new Subscription(UUID.randomUUID(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),next,next,Subscription.Status.ACTIVE,"sub_sibling",s.currentPeriod(),s.paidThrough(),null,null,null,null,null,s.revision(),s.createdAt(),s.updatedAt(),null);
            var terminal=new Subscription(UUID.randomUUID(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),null,Subscription.Status.CANCELLED,"sub_history",null,null,null,null,null,null,"PROVIDER_CANCELLED",s.revision(),s.createdAt(),s.updatedAt(),null);
            when(f.fixture.catalog.terms(s.siteId(),next.priceId())).thenReturn(next);when(f.fixture.mappings.findPrice(f.fixture.account.id(),next.priceId())).thenReturn(Optional.of(new dev.genericpayments.platform.catalog.application.model.ProviderPriceRef("price_sibling")));
            var remote=f.fixture.graph.subscription.deepCopy();remote.addProperty("id","sub_sibling");var item=remote.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject();item.addProperty("id","si_sibling");item.addProperty("subscription","sub_sibling");var price=item.getAsJsonObject("price");price.addProperty("id","price_sibling");price.addProperty("unit_amount",2000);price.getAsJsonObject("metadata").addProperty("local_price_id",next.priceId().value().toString());price.getAsJsonObject("metadata").addProperty("local_price_version","2");
            var history=f.fixture.graph.subscription.deepCopy();history.addProperty("id","sub_history");history.addProperty("status","canceled");f.members=List.of(s,sibling,terminal);f.remoteSubscription+=","+remote+","+history;
            f.successResponses();assertThatCode(()->f.adapter.create(f.command(),RETURN)).doesNotThrowAnyException();assertThat(f.fixture.graph.http.requestCount("POST",SESSIONS)).isEqualTo(1);
        }
    }
    @ParameterizedTest @ValueSource(strings={"unknownFeature","missingFlag","inactive","live","immediate","prorating","retention","login"})
    void effectiveConfigDriftRefusesSessionButHarmlessPresentationDoesNotGrantAuthority(String fault)throws Exception{
        try(var f=new PortalCase()){
            var json=com.google.gson.JsonParser.parseString(f.config()).getAsJsonObject();var features=json.getAsJsonObject("features");
            switch(fault){case "unknownFeature"->features.add("future_mutation",com.google.gson.JsonParser.parseString("{\"enabled\":true}"));case "missingFlag"->features.getAsJsonObject("customer_update").remove("enabled");case "inactive"->json.addProperty("active",false);case "live"->json.addProperty("livemode",true);case "immediate"->features.getAsJsonObject("subscription_cancel").addProperty("mode","immediately");case "prorating"->features.getAsJsonObject("subscription_cancel").addProperty("proration_behavior","create_prorations");case "retention"->features.getAsJsonObject("subscription_cancel").add("retention",com.google.gson.JsonParser.parseString("{\"type\":\"coupon_offer\"}"));case "login"->json.getAsJsonObject("login_page").addProperty("enabled",true);}
            f.remote();f.fixture.graph.http.respond("POST",CONFIGURATIONS,json.toString());assertThatThrownBy(()->f.adapter.create(f.command(),RETURN)).isInstanceOf(PaymentApplicationException.class);assertThat(f.fixture.graph.http.requestCount("POST",SESSIONS)).isZero();assertThat(f.saved).isNull();
        }
    }
    @Test void cosmeticConfigChangesDoNotReplaceTheImmutableBinding()throws Exception{
        try(var f=new PortalCase()){
            var json=com.google.gson.JsonParser.parseString(f.config()).getAsJsonObject();json.addProperty("name","presentation only");json.add("metadata",com.google.gson.JsonParser.parseString("{\"note\":\"changed\"}"));
            f.remote();f.fixture.graph.http.respond("POST",CONFIGURATIONS,f.config());f.fixture.graph.http.respond("GET",CONFIGURATION,json.toString());f.fixture.graph.http.respond("POST",SESSIONS,f.sessionJson());
            assertThatCode(()->f.adapter.create(f.command(),RETURN)).doesNotThrowAnyException();assertThat(f.fixture.graph.http.requestCount("POST",CONFIGURATIONS)).isEqualTo(1);
        }
    }
    @Test void lostConfigResponseUsesTheOriginalIntentStepKeyAndPayload()throws Exception{
        try(var f=new PortalCase()){
            f.remote();f.fixture.graph.http.disconnectNext("POST",CONFIGURATIONS);assertThatThrownBy(()->f.adapter.create(f.command(),RETURN)).isInstanceOf(PaymentApplicationException.class);var first=f.fixture.steps.only();
            f.successResponses();assertThatCode(()->f.adapter.create(f.command(),RETURN)).doesNotThrowAnyException();
            var posts=f.fixture.graph.http.calls().stream().filter(c->c.method().equals("POST")&&c.path().equals(CONFIGURATIONS)).toList();assertThat(posts).hasSize(2);assertThat(posts.get(0).form()).isEqualTo(posts.get(1).form());assertThat(posts).allSatisfy(p->assertThat(p.header("Idempotency-Key")).isEqualTo(first.operationKey()));assertThat(f.fixture.steps.findById(first.id()).orElseThrow().firstDispatchedAt()).isEqualTo(first.firstDispatchedAt());
        }
    }
    @Test void configAcknowledgementSurvivesBindingFailureWithoutAnotherCreate()throws Exception{
        try(var f=new PortalCase()){
            f.failBind=true;f.remote();f.fixture.graph.http.respond("POST",CONFIGURATIONS,f.config());assertThatThrownBy(()->f.adapter.create(f.command(),RETURN)).isInstanceOf(PaymentApplicationException.class);assertThat(f.fixture.steps.only().status()).isEqualTo(dev.genericpayments.platform.provider.application.model.ProviderOperation.Status.ACKNOWLEDGED);
            f.remote();f.fixture.graph.http.respond("GET",CONFIGURATION,f.config());f.fixture.graph.http.respond("GET",CONFIGURATION,f.config());f.fixture.graph.http.respond("POST",SESSIONS,f.sessionJson());assertThatCode(()->f.adapter.create(f.command(),RETURN)).doesNotThrowAnyException();assertThat(f.fixture.graph.http.requestCount("POST",CONFIGURATIONS)).isEqualTo(1);
        }
    }
    @Test void lostSessionResponseReusesOnlyOriginalSessionResourceAndKey()throws Exception{
        try(var f=new PortalCase()){
            f.remote();f.fixture.graph.http.respond("POST",CONFIGURATIONS,f.config());f.fixture.graph.http.respond("GET",CONFIGURATION,f.config());f.fixture.graph.http.disconnectNext("POST",SESSIONS);assertThatThrownBy(()->f.adapter.create(f.command(),RETURN)).isInstanceOf(PaymentApplicationException.class);
            var first=f.fixture.steps.rows.values().stream().filter(s->s.kind()==dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.PORTAL_SESSION_CREATE).findFirst().orElseThrow();
            f.remote();f.fixture.graph.http.respond("GET",CONFIGURATION,f.config());f.fixture.graph.http.respond("POST",SESSIONS,f.sessionJson());assertThatCode(()->f.adapter.create(f.command(),RETURN)).doesNotThrowAnyException();
            var posts=f.fixture.graph.http.calls().stream().filter(c->c.method().equals("POST")&&c.path().equals(SESSIONS)).toList();assertThat(posts).hasSize(2).allSatisfy(p->assertThat(p.header("Idempotency-Key")).isEqualTo(first.operationKey()));assertThat(posts.get(0).form()).isEqualTo(posts.get(1).form());assertThat(f.fixture.steps.findById(first.id()).orElseThrow().firstDispatchedAt()).isEqualTo(first.firstDispatchedAt());
        }
    }
    @Test void distinctPublicPortalOperationsShareConfigButHaveDifferentSessionResources()throws Exception{
        try(var f=new PortalCase()){
            f.successResponses();f.adapter.create(f.command(),RETURN);var first=f.fixture.operation.id();f.newOperation();
            f.remote();f.fixture.graph.http.respond("GET",CONFIGURATION,f.config());f.fixture.graph.http.respond("POST",SESSIONS,f.sessionJson().replace("bps_owned","bps_second"));assertThatCode(()->f.adapter.create(f.command(),RETURN)).doesNotThrowAnyException();
            assertThat(f.fixture.graph.http.requestCount("POST",CONFIGURATIONS)).isEqualTo(1);assertThat(f.fixture.graph.http.requestCount("POST",SESSIONS)).isEqualTo(2);assertThat(f.fixture.steps.rows.values()).filteredOn(s->s.kind()==dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.PORTAL_SESSION_CREATE).extracting(dev.genericpayments.platform.provider.application.model.ProviderOperation::resourceId).containsExactlyInAnyOrder(first,f.fixture.operation.id());
        }
    }
    @Test void completeSubscriptionAndInvoicePagesRetainCustomerAndProgressCursors()throws Exception{
        try(var f=new PortalCase()){
            f.prefix();f.page("/v1/subscriptions",true,f.remoteSubscription);f.page("/v1/subscriptions",false,"");f.page("/v1/invoices",true,f.invoice);f.page("/v1/invoices",false,"");f.creationResponses();
            assertThatCode(()->f.adapter.create(f.command(),RETURN)).doesNotThrowAnyException();
            var reads=f.fixture.graph.http.calls().stream().filter(c->c.path().equals("/v1/subscriptions")).toList();assertThat(reads).hasSize(2);
            assertThat(reads.get(0).query()).containsEntry("customer","cus_owned").containsEntry("status","all").doesNotContainKey("starting_after");assertThat(reads.get(1).query()).containsEntry("starting_after","sub_owned");
            assertThat(f.fixture.graph.http.calls().stream().filter(c->c.path().equals("/v1/invoices")).toList().get(1).query()).containsEntry("starting_after","in_owned");
        }
    }
    @ParameterizedTest @ValueSource(strings={"subRepeat","invoiceRepeat","subEmptyMore","invoiceEmptyMore","missingHasMore","wrongList","cap"})
    void incompleteOrNonprogressingRemoteInventoryCannotIssueASession(String fault)throws Exception{
        try(var f=new PortalCase()){
            f.prefix();
            if(fault.equals("cap")){
                var source=f.fixture.local;var members=new ArrayList<Subscription>();members.add(source);
                for(int i=0;i<10;i++){
                    String ref=i==0?"sub_owned":"sub_cap"+i;var remote=f.fixture.graph.subscription.deepCopy();remote.addProperty("id",ref);remote.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject().addProperty("subscription",ref);
                    if(i>0)members.add(new Subscription(UUID.randomUUID(),source.siteId(),source.customerId(),source.productId(),source.paymentAccountId(),source.billingPrice(),source.entitledPrice(),source.billingStatus(),ref,source.currentPeriod(),source.paidThrough(),null,null,null,null,null,source.revision(),source.createdAt(),source.updatedAt(),null));
                    f.page("/v1/subscriptions",true,remote.toString());
                }f.members=members;
            }else if(fault.equals("subRepeat")){f.page("/v1/subscriptions",true,f.remoteSubscription);f.page("/v1/subscriptions",false,f.remoteSubscription);}
            else if(fault.equals("subEmptyMore"))f.page("/v1/subscriptions",true,"");
            else if(fault.equals("missingHasMore"))f.fixture.graph.http.respond("GET","/v1/subscriptions","{\"object\":\"list\",\"data\":["+f.remoteSubscription+"]}");
            else if(fault.equals("wrongList"))f.fixture.graph.http.respond("GET","/v1/subscriptions","{\"object\":\"subscription\",\"has_more\":false,\"data\":["+f.remoteSubscription+"]}");
            else {f.page("/v1/subscriptions",false,f.remoteSubscription);f.page("/v1/invoices",true,fault.equals("invoiceEmptyMore")?"":f.invoice);if(fault.equals("invoiceRepeat"))f.page("/v1/invoices",false,f.invoice);}
            assertThatThrownBy(()->f.adapter.create(f.command(),RETURN)).isInstanceOf(PaymentApplicationException.class);assertThat(f.fixture.graph.http.requestCount("POST",SESSIONS)).isZero();assertThat(f.configuration).isNull();assertThat(f.saved).isNull();
            if(fault.equals("cap"))assertThat(f.fixture.graph.http.requestCount("GET","/v1/subscriptions")).isEqualTo(10);
        }
    }
    @ParameterizedTest @ValueSource(strings={"reference","object","customer","configuration","live","createdBefore","createdAfter","flow","target","topReturn","doneReturn","homepage","retention","unknownFlow"})
    void sessionIdentityAndEffectiveFlowMustMatchTheOriginalRequest(String fault)throws Exception{
        try(var f=new PortalCase()){
            var json=com.google.gson.JsonParser.parseString(f.sessionJson()).getAsJsonObject();var flow=json.getAsJsonObject("flow");
            switch(fault){case "reference"->json.addProperty("id","bpc_wrong");case "object"->json.addProperty("object","customer");case "customer"->json.addProperty("customer","cus_foreign");case "configuration"->json.addProperty("configuration","bpc_foreign");case "live"->json.addProperty("livemode",true);case "createdBefore"->json.addProperty("created",f.now.minusSeconds(1).getEpochSecond());case "createdAfter"->json.addProperty("created",f.now.plusSeconds(1).getEpochSecond());case "flow"->flow.addProperty("type","payment_method_update");case "target"->flow.getAsJsonObject("subscription_cancel").addProperty("subscription","sub_foreign");case "topReturn"->json.addProperty("return_url","https://foreign.test");case "doneReturn"->flow.getAsJsonObject("after_completion").getAsJsonObject("redirect").addProperty("return_url","https://foreign.test");case "homepage"->flow.getAsJsonObject("after_completion").addProperty("type","portal_homepage");case "retention"->flow.getAsJsonObject("subscription_cancel").add("retention",com.google.gson.JsonParser.parseString("{\"type\":\"coupon_offer\"}"));case "unknownFlow"->flow.add("future_action",com.google.gson.JsonParser.parseString("{}"));}
            f.remote();f.fixture.graph.http.respond("POST",CONFIGURATIONS,f.config());f.fixture.graph.http.respond("GET",CONFIGURATION,f.config());f.fixture.graph.http.respond("POST",SESSIONS,json.toString());
            assertThatThrownBy(()->f.adapter.create(f.command(),RETURN)).isInstanceOf(PaymentApplicationException.class);assertThat(f.saved).isNull();
            assertThat(f.fixture.steps.rows.values()).filteredOn(s->s.kind()==dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.PORTAL_SESSION_CREATE).singleElement().satisfies(s->assertThat(s.status()).isEqualTo(dev.genericpayments.platform.provider.application.model.ProviderOperation.Status.UNKNOWN));
        }
    }
    @ParameterizedTest @ValueSource(strings={"configuration","session"})
    void expiredUnknownCannotDispatchAnotherCreateOrRotateItsKey(String kind)throws Exception{
        try(var f=new PortalCase()){
            f.remote();if(kind.equals("session")){f.fixture.graph.http.respond("POST",CONFIGURATIONS,f.config());f.fixture.graph.http.respond("GET",CONFIGURATION,f.config());}f.fixture.graph.http.disconnectNext("POST",kind.equals("session")?SESSIONS:CONFIGURATIONS);
            assertThatThrownBy(()->f.adapter.create(f.command(),RETURN)).isInstanceOf(PaymentApplicationException.class);
            var step=f.fixture.steps.rows.values().stream().filter(s->s.kind()==(kind.equals("session")?dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.PORTAL_SESSION_CREATE:dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.PORTAL_CONFIGURATION_CREATE)).findFirst().orElseThrow();
            var expired=f.fixture.steps.replace(step,step.status(),null,f.now.minus(Duration.ofHours(23)),f.now,null,null);
            f.remote();if(kind.equals("session"))f.fixture.graph.http.respond("GET",CONFIGURATION,f.config());assertThatThrownBy(()->f.adapter.create(f.command(),RETURN)).isInstanceOf(PaymentApplicationException.class);
            assertThat(f.fixture.steps.findById(step.id())).contains(expired);assertThat(f.fixture.graph.http.requestCount("POST",kind.equals("session")?SESSIONS:CONFIGURATIONS)).isEqualTo(1);assertThat(f.saved).isNull();
        }
    }
    @ParameterizedTest @ValueSource(strings={"configuration","session"})
    void stolenWorkerCannotAcknowledgeOrPublishTheProviderResponse(String kind)throws Exception{
        try(var f=new PortalCase()){
            if(kind.equals("session")){
                f.remote();f.fixture.graph.http.respond("POST",CONFIGURATIONS,f.config());f.fixture.graph.http.respond("GET",CONFIGURATION,f.config());f.fixture.graph.http.disconnectNext("POST",SESSIONS);assertThatThrownBy(()->f.adapter.create(f.command(),RETURN)).isInstanceOf(PaymentApplicationException.class);
                f.remote();f.fixture.graph.http.respond("GET",CONFIGURATION,f.config());f.fixture.graph.http.respond("POST",SESSIONS,f.sessionJson());
            }else f.successResponses();
            f.fixture.steps.stealBeforeAck=true;assertThatThrownBy(()->f.adapter.create(f.command(),RETURN)).isInstanceOf(PaymentApplicationException.class);assertThat(f.saved).isNull();
            assertThat(f.fixture.steps.rows.values()).anySatisfy(s->{assertThat(s.status()).isEqualTo(dev.genericpayments.platform.provider.application.model.ProviderOperation.Status.DISPATCHING);assertThat(s.leaseOwner()).isEqualTo("another-owner");});
        }
    }
    static final class PortalCase implements AutoCloseable {
        static final String REAL_URL="https://billing.stripe.com/p/session?secret=test_fixture";
        final StripeSubscriptionCreateTest fixture=new StripeSubscriptionCreateTest();
        final dev.genericpayments.platform.provider.application.port.out.StripePortalConfigurationPort proofs=mock(dev.genericpayments.platform.provider.application.port.out.StripePortalConfigurationPort.class);
        final SubscriptionObjectPort objects=mock(SubscriptionObjectPort.class);
        final Instant now=Instant.ofEpochSecond(1800000100L);
        final dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingPortalAdapter adapter;
        dev.genericpayments.platform.provider.application.port.out.StripePortalConfigurationPort.Configuration configuration;
        dev.genericpayments.platform.provider.application.port.out.StripePortalConfigurationPort.Session intent,saved;
        List<Subscription> members;SubscriptionPolicy policy;String remoteSubscription,invoice,hasMore="false";boolean failBind;
        PortalCase() throws Exception {
            fixture.setup();fixture.local=fixture.graph.local;var s=fixture.local;var id=UUID.randomUUID();
            fixture.operation=new SubscriptionOperation(id,s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.PORTAL,SubscriptionOperation.Status.RESERVED,"customer:owned","key","a".repeat(64),"subscription:"+fixture.account.id().value()+":"+id+":PORTAL",s.revision(),7,s.providerReference(),null,now,now,0);
            when(fixture.operations.find(eq(s.siteId()),eq(s.id()),any())).thenAnswer(i->fixture.operation.id().equals(i.getArgument(2))?Optional.of(fixture.operation):Optional.empty());members=List.of(s);policy=new SubscriptionPolicy(s.siteId(),7,true,3,false,true,false,Set.of(RETURN));
            when(proofs.capture(any(),eq(RETURN))).thenAnswer(i->new dev.genericpayments.platform.provider.application.port.out.StripePortalConfigurationPort.Scope(fixture.account,fixture.customer,fixture.operation,policy,members,RETURN));
            when(proofs.intent(any())).thenAnswer(i->{if(configuration==null)configuration=i.getArgument(0);return configuration;});
            when(proofs.bind(any(),any(),anyString())).thenAnswer(i->{if(failBind){failBind=false;throw new IllegalStateException("injected binding failure");}var c=configuration;configuration=new dev.genericpayments.platform.provider.application.port.out.StripePortalConfigurationPort.Configuration(c.id(),c.account(),c.site(),c.policy(),c.digest(),c.owner(),c.key(),c.fingerprint(),i.getArgument(2),i.getArgument(1));return configuration;});
            when(proofs.session(any())).thenAnswer(i->{dev.genericpayments.platform.provider.application.port.out.StripePortalConfigurationPort.Session proposed=i.getArgument(0);if(intent==null||!intent.operation().equals(proposed.operation()))intent=proposed;return intent;});
            when(proofs.record(any(),any(),anyString(),any())).thenAnswer(i->{var p=intent;return saved=new dev.genericpayments.platform.provider.application.port.out.StripePortalConfigurationPort.Session(p.operation(),p.configuration(),p.scope(),p.fingerprint(),i.getArgument(2),i.getArgument(1),i.getArgument(3));});
            when(objects.resolve(any(),any(),eq(SubscriptionObjectPort.Kind.INVOICE),eq("in_owned"))).thenReturn(Optional.of(s.id()));
            var credentials=new StripeCredentialResolver(new StripeSandboxProperties(true,"sk_test_"+"A".repeat(32),"pk_test_"+"B".repeat(32),"whsec_"+"C".repeat(32)));
            var factory=new StripeClientFactory(){@Override public com.stripe.StripeClient create(StripeCredentials ignored){return fixture.graph.http.client();}};
            dev.genericpayments.platform.shared.application.port.out.DigestPort digest=value->{try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new AssertionError(e);}};
            var ownership=new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnership(credentials,fixture.steps,fixture.bindings,digest,fixture.accounts,fixture.sites,fixture.customerStore,fixture.subscriptions,fixture.operations,fixture.catalog,fixture.mappings);
            adapter=new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingPortalAdapter(ownership,proofs,fixture.steps,credentials,factory,new StripeAccountVerificationAdapter(credentials,factory),Clock.fixed(now,ZoneOffset.UTC),objects);
            fixture.graph.subscription.addProperty("cancel_at_period_end",false);remoteSubscription=fixture.graph.subscription.toString();invoice=fixture.graph.invoice.toString();
        }
        Command command(){return new Command(fixture.account,fixture.operation,fixture.local.billingPrice(),null,"sub_owned",null,null,false,false);}
        void newOperation(){var o=fixture.operation;var id=UUID.randomUUID();fixture.operation=new SubscriptionOperation(id,o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),o.status(),o.authorityScope(),o.keyDigest(),o.fingerprint(),"subscription:"+fixture.account.id().value()+":"+id+":PORTAL",o.expectedRevision(),o.policyVersion(),o.providerReference(),null,now,now,0);}
        void successResponses(){remote();fixture.graph.http.respond("POST",CONFIGURATIONS,config());fixture.graph.http.respond("GET",CONFIGURATION,config());fixture.graph.http.respond("POST",SESSIONS,sessionJson());}
        void prefix(){fixture.identity();fixture.graph.http.respond("GET","/v1/customers/cus_owned",fixture.graph.customer.toString());}
        void page(String path,boolean more,String data){fixture.graph.http.respond("GET",path,"{\"object\":\"list\",\"has_more\":"+more+",\"data\":["+data+"]}");}
        void creationResponses(){fixture.graph.http.respond("POST",CONFIGURATIONS,config());fixture.graph.http.respond("GET",CONFIGURATION,config());fixture.graph.http.respond("POST",SESSIONS,sessionJson());}
        void remote(){fixture.identity();fixture.graph.http.respond("GET","/v1/customers/cus_owned",fixture.graph.customer.toString());fixture.graph.http.respond("GET","/v1/subscriptions","{\"object\":\"list\",\"has_more\":"+hasMore+",\"data\":["+remoteSubscription+"]}");fixture.graph.http.respond("GET","/v1/invoices","{\"object\":\"list\",\"has_more\":false,\"data\":["+invoice+"]}");}
        String config(){return configurationJson().replace("1793188800",Long.toString(now.getEpochSecond()));}
        String sessionJson(){return StripeBillingPortalTest.sessionJson().replace("1793188800",Long.toString(now.getEpochSecond())).replace(NAVIGATION,REAL_URL);}
        public void close(){fixture.close();}
    }
    static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    static final String RETURN = "https://shop.example/account";
    static final String CONFIGURATIONS = "/v1/billing_portal/configurations";
    static final String CONFIGURATION = CONFIGURATIONS + "/bpc_owned";
    static final String SESSIONS = "/v1/billing_portal/sessions";
    static final String CONFIG_KEY = "portal-config-original-fixture";
    static final String SESSION_KEY = "portal-session-original-fixture";
    static final String NAVIGATION = "https://billing.stripe.test/p/session_fixture";

    @Nested
    class PinnedWireContract {
        @Test
        void createsAndRetrievesAnExplicitlyRestrictedConfiguration() throws Exception {
            try (var http = new StripeBillingHttpFixture()) {
                http.respond("POST", CONFIGURATIONS, configurationJson());
                var created = http.client().v1().billingPortal().configurations().create(
                    configurationParams(), key(CONFIG_KEY));
                assertConfigurationShape(created);
                assertThat(http.calls()).singleElement().satisfies(call -> {
                    assertThat(call.form()).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                        Map.entry("features[invoice_history][enabled]", "true"),
                        Map.entry("features[customer_update][enabled]", "false"),
                        Map.entry("features[payment_method_update][enabled]", "false"),
                        Map.entry("features[subscription_update][enabled]", "false"),
                        Map.entry("features[subscription_cancel][enabled]", "true"),
                        Map.entry("features[subscription_cancel][mode]", "at_period_end"),
                        Map.entry("features[subscription_cancel][proration_behavior]", "none"),
                        Map.entry("features[subscription_cancel][cancellation_reason][enabled]", "false"),
                        Map.entry("login_page[enabled]", "false"),
                        Map.entry("default_return_url", RETURN)));
                    assertThat(call.header("Idempotency-Key")).isEqualTo(CONFIG_KEY);
                });
                http.respond("GET", CONFIGURATION, configurationJson());
                var retrieved = http.client().v1().billingPortal().configurations().retrieve("bpc_owned");
                assertConfigurationShape(retrieved);
                assertThat(http.requestCount("POST", CONFIGURATIONS)).isEqualTo(1);
                assertThat(http.requestCount("GET", CONFIGURATION)).isEqualTo(1);
                pins(http);
            }
        }

        @Test
        void scopesCancellationToTheOriginalSubscriptionAndRedirectsToExactReturn() throws Exception {
            try (var http = new StripeBillingHttpFixture()) {
                http.respond("POST", SESSIONS, sessionJson());
                var session = http.client().v1().billingPortal().sessions().create(
                    sessionParams(), key(SESSION_KEY));
                assertThat(session.getId()).isEqualTo("bps_owned");
                assertThat(session.getObject()).isEqualTo("billing_portal.session");
                assertThat(session.getLivemode()).isFalse();
                assertThat(session.getCreated()).isEqualTo(NOW.getEpochSecond());
                assertThat(session.getCustomer()).isEqualTo("cus_owned");
                assertThat(session.getConfiguration()).isEqualTo("bpc_owned");
                assertThat(session.getReturnUrl()).isEqualTo(RETURN);
                assertThat(session.getUrl()).isEqualTo(NAVIGATION);
                assertThat(session.getFlow().getType()).isEqualTo("subscription_cancel");
                assertThat(session.getFlow().getSubscriptionCancel().getSubscription()).isEqualTo("sub_owned");
                assertThat(session.getFlow().getSubscriptionCancel().getRetention()).isNull();
                assertThat(session.getFlow().getAfterCompletion().getType()).isEqualTo("redirect");
                assertThat(session.getFlow().getAfterCompletion().getRedirect().getReturnUrl()).isEqualTo(RETURN);
                assertThat(session.getFlow().getAfterCompletion().getHostedConfirmation()).isNull();
                assertThat(http.calls()).singleElement().satisfies(call -> {
                    assertThat(call.form()).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "customer", "cus_owned", "configuration", "bpc_owned", "return_url", RETURN,
                        "flow_data[type]", "subscription_cancel",
                        "flow_data[subscription_cancel][subscription]", "sub_owned",
                        "flow_data[after_completion][type]", "redirect",
                        "flow_data[after_completion][redirect][return_url]", RETURN));
                    assertThat(call.header("Idempotency-Key")).isEqualTo(SESSION_KEY);
                });
                assertThat(http.requestCount("POST", SESSIONS)).isEqualTo(1);
                pins(http);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"configuration", "session"})
        void disconnectedCreatesHaveOneSdkAttemptAndTheExplicitOriginalKey(String kind) throws Exception {
            try (var http = new StripeBillingHttpFixture()) {
                var path = kind.equals("configuration") ? CONFIGURATIONS : SESSIONS;
                var originalKey = kind.equals("configuration") ? CONFIG_KEY : SESSION_KEY;
                http.disconnectNext("POST", path);
                assertThatThrownBy(() -> {
                    if (kind.equals("configuration")) http.client().v1().billingPortal().configurations()
                        .create(configurationParams(), key(originalKey));
                    else http.client().v1().billingPortal().sessions().create(sessionParams(), key(originalKey));
                }).isInstanceOf(ApiConnectionException.class);
                assertThat(http.requestCount("POST", path)).isEqualTo(1);
                assertThat(http.calls()).singleElement().satisfies(call ->
                    assertThat(call.header("Idempotency-Key")).isEqualTo(originalKey));
                pins(http);
            }
        }

        @Test
        void missingSecurityFlagsRemainUnknownInsteadOfDecodingAsFalse() throws Exception {
            try (var http = new StripeBillingHttpFixture()) {
                http.respond("GET", CONFIGURATION,
                    "{\"id\":\"bpc_owned\",\"object\":\"billing_portal.configuration\",\"features\":{\"subscription_cancel\":{\"enabled\":true}}}");
                var config = http.client().v1().billingPortal().configurations().retrieve("bpc_owned");
                assertThat(config.getActive()).isNull();
                assertThat(config.getLivemode()).isNull();
                assertThat(config.getLoginPage()).isNull();
                assertThat(config.getFeatures().getInvoiceHistory()).isNull();
                assertThat(config.getFeatures().getCustomerUpdate()).isNull();
                assertThat(config.getFeatures().getPaymentMethodUpdate()).isNull();
                assertThat(config.getFeatures().getSubscriptionUpdate()).isNull();
                assertThat(config.getFeatures().getSubscriptionCancel().getMode()).isNull();
                assertThat(config.getFeatures().getSubscriptionCancel().getProrationBehavior()).isNull();
                assertThat(config.getFeatures().getSubscriptionCancel().getCancellationReason()).isNull();
            }
        }
    }

    @Nested
    class ExistingLocalBoundary {
        @ParameterizedTest
        @ValueSource(strings = {"https://shop.example/account.evil", "https://shop.example/account/",
            "https://shop.example/account?next=foreign", "https://shop.example/account#foreign",
            "http://shop.example/account", "https://other.example/account"})
        void foreignReturnNeverReachesProvider(String url) {
            var f = new LocalCase();
            assertThatThrownBy(() -> f.create("foreign-return", url)).isInstanceOf(PaymentApplicationException.class);
            verifyNoInteractions(f.provider);
            assertThat(f.rows).isEmpty();
            assertThat(f.receipts).isEmpty();
        }

        @Test
        void disabledPortalNeverReservesOrCallsProvider() {
            var f = new LocalCase();
            f.policy = f.policy(7, true, false);
            assertThatThrownBy(() -> f.create("disabled", RETURN)).isInstanceOf(PaymentApplicationException.class);
            verifyNoInteractions(f.provider);
            assertThat(f.rows).isEmpty();
        }

        @Test
        void wrongLocalCustomerNeverReachesProvider() {
            var f = new LocalCase();
            var foreign = f.context("foreign-customer", new CustomerId(UUID.randomUUID()));
            assertThatThrownBy(() -> f.service.create(new CreateSubscriptionPortalUseCase.Command(
                foreign, f.local.id(), RETURN))).isInstanceOf(PaymentApplicationException.class);
            verifyNoInteractions(f.provider);
            assertThat(f.rows).isEmpty();
        }

        @Test
        void providerLookupUsesTheSubscriptionsOriginalAccount() {
            var f = new LocalCase();
            f.acceptSimulated();
            f.create("original-account", RETURN);
            verify(f.registry).forAccount(f.account);
            assertThat(f.commands).singleElement().satisfies(command -> {
                assertThat(command.account()).isEqualTo(f.account);
                assertThat(command.operation().kind()).isEqualTo(SubscriptionOperation.Kind.PORTAL);
                assertThat(command.operation().subscriptionId()).isEqualTo(f.local.id());
                assertThat(command.providerSubscriptionReference()).isEqualTo("sub_owned");
                assertThat(command.operation().policyVersion()).isEqualTo(7);
                assertThat(command.operation().expectedRevision()).isEqualTo(f.local.revision());
            });
        }

        @Test
        void providerNavigationWithoutTrustedInternalProofCannotBecomeARealReceipt() {
            var f = new LocalCase();
            when(f.provider.portal(any(), eq(RETURN))).thenReturn(new PortalNavigation(NAVIGATION, NOW.plusSeconds(60), false));
            assertThatThrownBy(() -> f.create("unproven", RETURN)).isInstanceOf(PaymentApplicationException.class);
            assertThat(f.receipts).isEmpty();
            assertThat(f.rows.values()).noneMatch(o -> o.status() == SubscriptionOperation.Status.SUCCEEDED);
            assertThat(f.local.cancellation()).isNull();
            verify(f.subscriptions, never()).save(any(), anyLong());
        }

        @Test
        void navigationAndRepeatedBrowserReturnDoNotChangeLifecycleOrMoney() {
            var f = new LocalCase();
            f.acceptSimulated();
            var before = f.local;
            var navigation = f.create("navigation", RETURN);
            var id = f.rows.values().iterator().next().id();
            assertThat(f.service.returned(f.context(null, f.local.customerId()), f.local.id(), id)).isEqualTo(before);
            assertThat(f.service.returned(f.context(null, f.local.customerId()), f.local.id(), id)).isEqualTo(before);
            assertThat(f.local).isSameAs(before);
            assertThat(f.local.cancellation()).isNull();
            assertThat(f.local.paidThrough()).isEqualTo(before.paidThrough());
            assertThat(navigation.toString()).doesNotContain(NAVIGATION);
            verify(f.subscriptions, never()).save(any(), anyLong());
            verify(f.provider, never()).cancel(any(), anyBoolean());
            verify(f.provider, never()).withdrawCancel(any());
            verify(f.provider, never()).recover(any());
            verify(f.provider, never()).retrieve(any());
        }

        @Test
        void browserReturnRequiresTheSameCustomerSubscriptionAndUnexpiredReceipt() {
            var f = new LocalCase();
            f.acceptSimulated();
            f.create("return-owner", RETURN);
            var id = f.rows.values().iterator().next().id();
            assertThatThrownBy(() -> f.service.returned(f.context(null, new CustomerId(UUID.randomUUID())), f.local.id(), id))
                .isInstanceOf(PaymentApplicationException.class);
            assertThatThrownBy(() -> f.service.returned(f.context(null, f.local.customerId()), UUID.randomUUID(), id))
                .isInstanceOf(PaymentApplicationException.class);
            f.receipts.replace(id, NOW);
            assertThatThrownBy(() -> f.service.returned(f.context(null, f.local.customerId()), f.local.id(), id))
                .isInstanceOf(PaymentApplicationException.class);
            verify(f.subscriptions, never()).save(any(), anyLong());
        }

        @Test
        void changedReturnCannotReuseTheOriginalPublicOperation() {
            var f = new LocalCase();
            f.policy = new SubscriptionPolicy(f.local.siteId(), 7, true, 3, false, true, false,
                Set.of(RETURN, "https://shop.example/other"));
            f.acceptSimulated();
            f.create("same-key", RETURN);
            assertThatThrownBy(() -> f.create("same-key", "https://shop.example/other"))
                .isInstanceOf(PaymentApplicationException.class);
            verify(f.provider, times(1)).portal(any(), anyString());
            assertThat(f.rows).hasSize(1);
        }

        @Test
        void globalDisablementBlocksReplayBeforeAnyNewProviderDispatch() {
            var f = new LocalCase();
            f.acceptSimulated();
            f.create("replay-disabled", RETURN);
            f.policy = f.policy(8, false, true);
            assertThatThrownBy(() -> f.create("replay-disabled", RETURN)).isInstanceOf(PaymentApplicationException.class);
            verify(f.provider, times(1)).portal(any(), anyString());
            assertThat(f.receipts).hasSize(1);
        }

        @Test
        void policyVersionDriftCannotSubstituteNewTermsIntoAnOriginalReplay() {
            var f = new LocalCase();
            f.acceptSimulated();
            f.create("replay-version", RETURN);
            f.policy = f.policy(8, true, true);
            assertThatThrownBy(() -> f.create("replay-version", RETURN)).isInstanceOf(PaymentApplicationException.class);
            verify(f.provider, times(1)).portal(any(), anyString());
            assertThat(f.rows.values()).singleElement().satisfies(o -> assertThat(o.policyVersion()).isEqualTo(7));
        }

        @Test
        void policyChangedDuringProviderIoWithholdsTheNavigationReceipt() {
            var f = new LocalCase();
            when(f.provider.portal(any(), eq(RETURN))).thenAnswer(call -> {
                assertThat(f.tx.active()).isFalse();
                f.policy = f.policy(8, true, true);
                return new PortalNavigation(NAVIGATION, NOW.plusSeconds(60), true);
            });
            assertThatThrownBy(() -> f.create("inflight-version", RETURN)).isInstanceOf(PaymentApplicationException.class);
            assertThat(f.receipts).isEmpty();
            assertThat(f.rows.values()).noneMatch(o -> o.status() == SubscriptionOperation.Status.SUCCEEDED);
            verify(f.subscriptions, never()).save(any(), anyLong());
        }
    }

    static ConfigurationCreateParams configurationParams() {
        return ConfigurationCreateParams.builder().setDefaultReturnUrl(RETURN)
            .setLoginPage(ConfigurationCreateParams.LoginPage.builder().setEnabled(false).build())
            .setFeatures(ConfigurationCreateParams.Features.builder()
                .setInvoiceHistory(ConfigurationCreateParams.Features.InvoiceHistory.builder().setEnabled(true).build())
                .setCustomerUpdate(ConfigurationCreateParams.Features.CustomerUpdate.builder().setEnabled(false).build())
                .setPaymentMethodUpdate(ConfigurationCreateParams.Features.PaymentMethodUpdate.builder().setEnabled(false).build())
                .setSubscriptionUpdate(ConfigurationCreateParams.Features.SubscriptionUpdate.builder().setEnabled(false).build())
                .setSubscriptionCancel(ConfigurationCreateParams.Features.SubscriptionCancel.builder().setEnabled(true)
                    .setMode(ConfigurationCreateParams.Features.SubscriptionCancel.Mode.AT_PERIOD_END)
                    .setProrationBehavior(ConfigurationCreateParams.Features.SubscriptionCancel.ProrationBehavior.NONE)
                    .setCancellationReason(ConfigurationCreateParams.Features.SubscriptionCancel.CancellationReason.builder()
                        .setEnabled(false).build()).build()).build()).build();
    }

    static SessionCreateParams sessionParams() {
        return SessionCreateParams.builder().setCustomer("cus_owned").setConfiguration("bpc_owned").setReturnUrl(RETURN)
            .setFlowData(SessionCreateParams.FlowData.builder().setType(SessionCreateParams.FlowData.Type.SUBSCRIPTION_CANCEL)
                .setSubscriptionCancel(SessionCreateParams.FlowData.SubscriptionCancel.builder().setSubscription("sub_owned").build())
                .setAfterCompletion(SessionCreateParams.FlowData.AfterCompletion.builder()
                    .setType(SessionCreateParams.FlowData.AfterCompletion.Type.REDIRECT)
                    .setRedirect(SessionCreateParams.FlowData.AfterCompletion.Redirect.builder().setReturnUrl(RETURN).build())
                    .build()).build()).build();
    }

    static RequestOptions key(String value) { return RequestOptions.builder().setIdempotencyKey(value).build(); }

    static void pins(StripeBillingHttpFixture http) {
        assertThat(http.calls()).allSatisfy(call -> {
            assertThat(call.header("Stripe-Version")).isEqualTo("2026-09-30.endive");
            assertThat(call.header("User-Agent")).contains("JavaBindings/34.0.0");
            assertThat(call.header("Stripe-Account")).isNull();
        });
    }

    static void assertConfigurationShape(com.stripe.model.billingportal.Configuration config) {
        assertThat(config.getId()).isEqualTo("bpc_owned");
        assertThat(config.getObject()).isEqualTo("billing_portal.configuration");
        assertThat(config.getActive()).isTrue();
        assertThat(config.getLivemode()).isFalse();
        assertThat(config.getDefaultReturnUrl()).isEqualTo(RETURN);
        assertThat(config.getLoginPage().getEnabled()).isFalse();
        var features = config.getFeatures();
        assertThat(features.getInvoiceHistory().getEnabled()).isTrue();
        assertThat(features.getCustomerUpdate().getEnabled()).isFalse();
        assertThat(features.getPaymentMethodUpdate().getEnabled()).isFalse();
        assertThat(features.getSubscriptionUpdate().getEnabled()).isFalse();
        assertThat(features.getSubscriptionCancel().getEnabled()).isTrue();
        assertThat(features.getSubscriptionCancel().getMode()).isEqualTo("at_period_end");
        assertThat(features.getSubscriptionCancel().getProrationBehavior()).isEqualTo("none");
        assertThat(features.getSubscriptionCancel().getCancellationReason().getEnabled()).isFalse();
    }

    static String configurationJson() {
        return """
            {"id":"bpc_owned","object":"billing_portal.configuration","active":true,"livemode":false,
             "created":1793188800,"updated":1793188800,"default_return_url":"https://shop.example/account",
             "login_page":{"enabled":false,"url":null},"features":{
               "invoice_history":{"enabled":true},"customer_update":{"enabled":false,"allowed_updates":[]},
               "payment_method_update":{"enabled":false},
               "subscription_update":{"enabled":false,"default_allowed_updates":[],"products":[]},
               "subscription_cancel":{"enabled":true,"mode":"at_period_end","proration_behavior":"none",
                 "cancellation_reason":{"enabled":false,"options":[]}}}}
            """;
    }

    static String sessionJson() {
        return """
            {"id":"bps_owned","object":"billing_portal.session","livemode":false,"created":1793188800,
             "customer":"cus_owned","configuration":"bpc_owned",
             "url":"https://billing.stripe.test/p/session_fixture","return_url":"https://shop.example/account",
             "flow":{"type":"subscription_cancel","subscription_cancel":{"subscription":"sub_owned"},
               "after_completion":{"type":"redirect","redirect":{"return_url":"https://shop.example/account"}}}}
            """;
    }

    /** Actual local phase logic; memory ports do not simulate DB rollback, SQL ownership or concurrency. */
    static final class LocalCase {
        final InlineTransactions tx = new InlineTransactions();
        final SubscriptionStore subscriptions = mock(SubscriptionStore.class);
        final SubscriptionOperationStore operations = mock(SubscriptionOperationStore.class);
        final SubscriptionAuthorityPort authority = mock(SubscriptionAuthorityPort.class);
        final SubscriptionPolicyPort policies = mock(SubscriptionPolicyPort.class);
        final LockPaymentAccountPort accounts = mock(LockPaymentAccountPort.class);
        final SubscriptionPortalStore portals = mock(SubscriptionPortalStore.class);
        final SubscriptionProviderPort provider = mock(SubscriptionProviderPort.class);
        final SubscriptionProviderRegistry registry = mock(SubscriptionProviderRegistry.class);
        final Map<UUID, SubscriptionOperation> rows = new LinkedHashMap<>();
        final Map<UUID, Instant> receipts = new LinkedHashMap<>();
        final List<Command> commands = new ArrayList<>();
        final Subscription local;
        final PaymentAccount account;
        final SubscriptionPortalService service;
        SubscriptionPolicy policy;

        LocalCase() {
            var seed = SubscriptionFixtures.active(NOW.minusSeconds(60), NOW.plusSeconds(3600));
            account = PaymentAccount.create(seed.paymentAccountId(), "portal-fixture", PaymentProviderType.FAKE,
                PaymentAccountMode.SANDBOX, "acct_expected", "stripe-sandbox-default");
            local = new Subscription(seed.id(), seed.siteId(), seed.customerId(), seed.productId(), account.id(),
                seed.billingPrice(), seed.entitledPrice(), seed.billingStatus(), "sub_owned", seed.currentPeriod(),
                seed.paidThrough(), null, null, null, null, null, seed.revision(), seed.createdAt(), seed.updatedAt(), null);
            policy = policy(7, true, true);
            when(subscriptions.find(local.siteId(), local.id())).thenAnswer(i -> Optional.of(local));
            when(subscriptions.lock(local.siteId(), local.id())).thenAnswer(i -> local);
            when(accounts.findByIdForUpdate(account.id())).thenReturn(Optional.of(account));
            when(policies.load(local.siteId())).thenAnswer(i -> policy);
            when(registry.forAccount(account)).thenReturn(provider);
            when(provider.capabilities(account)).thenReturn(new Capabilities(false, false, false, true));
            when(operations.claim(any())).thenAnswer(i -> {
                SubscriptionOperation proposed = i.getArgument(0);
                var existing = rows.values().stream().filter(o -> o.keyDigest().equals(proposed.keyDigest())
                    && o.authorityScope().equals(proposed.authorityScope())).findFirst();
                if (existing.isPresent()) return new SubscriptionOperationStore.Claim(existing.orElseThrow(), false);
                rows.put(proposed.id(), proposed);
                return new SubscriptionOperationStore.Claim(proposed, true);
            });
            when(operations.lock(any())).thenAnswer(i -> Objects.requireNonNull(rows.get(i.getArgument(0))));
            when(operations.save(any(), anyLong())).thenAnswer(i -> {
                SubscriptionOperation next = i.getArgument(0);
                assertThat(rows.get(next.id()).version()).isEqualTo((long) i.getArgument(1));
                rows.put(next.id(), next);
                return next;
            });
            doAnswer(i -> { receipts.put(i.getArgument(3), i.getArgument(4)); return null; })
                .when(portals).save(eq(local.siteId()), eq(local.customerId()), eq(local.id()), any(), any());
            when(portals.valid(eq(local.siteId()), eq(local.customerId()), eq(local.id()), any(), any()))
                .thenAnswer(i -> { Instant expiry = receipts.get(i.getArgument(3)); return expiry != null && NOW.isBefore(expiry); });
            var phases = new SubscriptionInteractionTransaction(tx, operations, subscriptions, authority, policies, accounts,
                mock(SubscriptionActionStore.class), portals, mock(TrialEligibilityStore.class), Clock.fixed(NOW, ZoneOffset.UTC));
            service = new SubscriptionPortalService(phases, registry);
        }

        SubscriptionPolicy policy(long version, boolean enabled, boolean portalEnabled) {
            return new SubscriptionPolicy(local.siteId(), version, enabled, 3, false, portalEnabled, false, Set.of(RETURN));
        }

        SubscriptionCommandContext context(String key, CustomerId customer) {
            var session = new CustomerSessionContext(new CustomerSessionId(UUID.randomUUID()), local.siteId(), customer,
                "https://shop.example", Set.of(CustomerSessionScope.SUBSCRIPTION_MANAGE), NOW.plusSeconds(7200));
            return new SubscriptionCommandContext(new SubscriptionActor.Customer(session), local.siteId(), customer,
                "https://shop.example", key, "portal-fixture");
        }

        PortalNavigation create(String key, String url) {
            return service.create(new CreateSubscriptionPortalUseCase.Command(context(key, local.customerId()), local.id(), url));
        }

        void acceptSimulated() {
            when(provider.portal(any(), anyString())).thenAnswer(i -> {
                assertThat(tx.active()).isFalse();
                commands.add(i.getArgument(0));
                return new PortalNavigation(NAVIGATION, NOW.plusSeconds(60), true);
            });
        }
    }

    static final class InlineTransactions implements SubscriptionTransactionPort {
        private boolean active;
        public boolean active() { return active; }
        public <T> T required(Supplier<T> work) { return execute(work); }
        public <T> T requiresNew(Supplier<T> work) { return execute(work); }
        private <T> T execute(Supplier<T> work) {
            var previous = active;
            active = true;
            try { return work.get(); } finally { active = previous; }
        }
    }
}
