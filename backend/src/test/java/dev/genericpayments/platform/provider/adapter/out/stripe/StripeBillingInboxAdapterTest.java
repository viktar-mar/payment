package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionCommandTransaction;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StripeBillingInboxAdapterTest {
    final StripeSubscriptionCreateTest f=new StripeSubscriptionCreateTest();
    final SubscriptionObjectPort objects=mock(SubscriptionObjectPort.class);
    final SubscriptionCommandTransaction commands=mock(SubscriptionCommandTransaction.class);
    StripeBillingInboxAdapter adapter;
    @BeforeEach void setup()throws Exception{
        f.setup();
        var credentials=new StripeCredentialResolver(new StripeSandboxProperties(true,"sk_test_"+"A".repeat(32),"pk_test_"+"B".repeat(32),"whsec_"+"C".repeat(32)));
        var factory=new StripeClientFactory(){@Override public com.stripe.StripeClient create(StripeCredentials ignored){return f.graph.http.client();}};
        var ownership=new StripeBillingOwnership(credentials,f.steps,f.bindings,StripeBillingGrantTokens::hash,f.accounts,f.sites,f.customerStore,f.subscriptions,f.operations,f.catalog,f.mappings);
        adapter=new StripeBillingInboxAdapter(f.accounts,objects,ownership,f.bindings,f.operations,credentials,factory,new StripeAccountVerificationAdapter(credentials,factory),f.adapter,commands);
        when(objects.resolve(eq(f.account.provider()),eq(f.account.id()),eq(SubscriptionObjectPort.Kind.SUBSCRIPTION),eq("sub_owned")))
            .thenAnswer(x->f.local.providerReference()==null?Optional.empty():Optional.of(f.local.id()));
        when(commands.recordReply(eq(f.operation.id()),any())).thenAnswer(x->{f.local=StripeSubscriptionCreateTest.copy(f.local,"sub_owned",Subscription.Status.INCOMPLETE);return null;});
    }
    @AfterEach void close(){f.close();}
    SubscriptionProviderEnvelope hint(String reference,String type){return new SubscriptionProviderEnvelope(f.account.provider(),f.account.id(),"evt_original",reference,type,Instant.EPOCH,Instant.now());}
    void acknowledged(){f.create();f.local=StripeSubscriptionCreateTest.copy(f.local,"sub_owned",Subscription.Status.INCOMPLETE);}
    @Test void afterAckBeforeFirstMappingUsesPinnedLocalReferenceWithoutEventRecovery(){
        acknowledged();when(f.operations.unresolved(f.local.siteId(),f.local.id())).thenReturn(List.of(f.operation));
        assertThat(adapter.resolve(hint("sub_owned","SUBSCRIPTION_CHANGED"))).contains(f.local.id());
        verify(commands).recordReply(eq(f.operation.id()),argThat(reply->"sub_owned".equals(reply.reference())));
        assertThat(f.graph.http.requestCount("GET","/v1/events/evt_original")).isZero();
        assertThat(f.graph.http.requestCount("POST","/v1/subscriptions")).isEqualTo(1);
    }
    @Test void invoiceBeforeAuxiliaryMappingUsesFreshExactParentAndOwnedCustomer(){
        acknowledged();f.identity();f.graph.http.respond("GET","/v1/invoices/in_owned",f.graph.invoice.toString());
        assertThat(adapter.resolve(hint("in_owned","SUBSCRIPTION_INVOICE_CHANGED"))).contains(f.local.id());
        verify(objects,never()).bind(any(),any(),any(),any(),any(),any());
        assertThat(f.graph.http.requestCount("GET","/v1/events/evt_original")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"customer","parent","live","id","kind"})
    void malformedOrForeignInvoiceNeverAttaches(String mutation){
        acknowledged();switch(mutation){case "customer"->f.graph.invoice.addProperty("customer","cus_foreign");case "parent"->f.graph.invoice.getAsJsonObject("parent").getAsJsonObject("subscription_details").addProperty("subscription","sub_foreign");case "live"->f.graph.invoice.addProperty("livemode",true);case "id"->f.graph.invoice.addProperty("id","in_foreign");case "kind"->f.graph.invoice.addProperty("object","subscription");}
        f.identity();f.graph.http.respond("GET","/v1/invoices/in_owned",f.graph.invoice.toString());
        try{assertThat(adapter.resolve(hint("in_owned","SUBSCRIPTION_INVOICE_CHANGED"))).isEmpty();}
        catch(dev.genericpayments.platform.shared.application.PaymentApplicationException safe){assertThat(safe.code()).isIn("PROVIDER_EVIDENCE_MISMATCH","PROVIDER_OPERATION_CONFLICT");}
        verifyNoInteractions(commands);verify(objects,never()).bind(any(),any(),any(),any(),any(),any());
    }
    @Test void exactCreatedEventRecoversOnlyOriginalOperationAndRecordsReceipt(){
        f.unknown();var step=f.steps.only();f.graph.subscription.addProperty("created",step.firstDispatchedAt().getEpochSecond());
        f.identity();f.event("evt_original",step.operationKey(),step.firstDispatchedAt().getEpochSecond());
        f.identity();f.event("evt_original",step.operationKey(),step.firstDispatchedAt().getEpochSecond());
        f.graph.http.respond("GET","/v1/customers/cus_owned",f.graph.customer.toString());f.graph.http.respond("GET","/v1/subscriptions/sub_owned",f.graph.subscription.toString());
        assertThat(adapter.resolve(hint("sub_owned","SUBSCRIPTION_CHANGED"))).contains(f.local.id());
        verify(commands).recordReply(eq(f.operation.id()),argThat(reply->"sub_owned".equals(reply.reference())));
        assertThat(f.graph.http.requestCount("GET","/v1/events/evt_original")).isEqualTo(2);
        assertThat(f.graph.http.requestCount("POST","/v1/subscriptions")).isEqualTo(1);
    }
    @Test void unmappedUpdateCannotSupplyOriginalEventProof(){
        f.identity();f.graph.http.respond("GET","/v1/events/evt_original",f.eventJson("evt_original",f.operation.providerOperationKey(),Instant.now().getEpochSecond()).replace("customer.subscription.created","customer.subscription.updated"));
        assertThat(adapter.resolve(hint("sub_owned","SUBSCRIPTION_CHANGED"))).isEmpty();verifyNoInteractions(commands);assertThat(f.steps.rows).isEmpty();
    }
    @Test void unknownInvoiceNeverInventsOriginalCreatedEvent(){
        f.identity();f.graph.http.respond("GET","/v1/invoices/in_owned",f.graph.invoice.toString());
        assertThat(adapter.resolve(hint("in_owned","SUBSCRIPTION_INVOICE_CHANGED"))).isEmpty();verifyNoInteractions(commands);assertThat(f.graph.http.requestCount("GET","/v1/events/evt_original")).isZero();assertThat(f.steps.rows).isEmpty();
    }
    @Test void setupAndScheduleAdmissionDoesNotEnableFutureWriteAuthority(){
        assertThat(adapter.resolve(hint("seti_owned","SUBSCRIPTION_SETUP_CHANGED"))).isEmpty();
        assertThat(adapter.resolve(hint("sub_sched_owned","SUBSCRIPTION_SCHEDULE_CHANGED"))).isEmpty();
        assertThat(f.adapter.capabilities(f.account)).isEqualTo(new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Capabilities(false,false,false,false));
        assertThat(f.graph.http.calls()).isEmpty();verifyNoInteractions(commands);
    }
}
