package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import static org.junit.jupiter.api.Assertions.*;
import static dev.genericpayments.platform.support.StripeBillingInertSdkFixture.port;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture;
import dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure construction and inert SDK reads only; no Mockito, Spring context, parent or extensions. */
class StripeBillingOwnedClockFenceTest {
    final StripeBillingInertSdkFixture f=new StripeBillingInertSdkFixture();
    final Instant now=Instant.parse("2026-10-29T12:00:00Z");
    final Subscription seed=SubscriptionFixtures.active(now,now.plusSeconds(3600));
    final Subscription s=new Subscription(seed.id(),f.site,f.customer,seed.productId(),f.account.id(),seed.billingPrice(),seed.entitledPrice(),seed.billingStatus(),"sub_owned",seed.currentPeriod(),seed.paidThrough(),null,null,null,null,null,seed.revision(),seed.createdAt(),seed.updatedAt(),null);
    final SubscriptionStore subscriptions=port(SubscriptionStore.class,m->{assertEquals("findOwned",m.name());return Optional.of(s);});
    final SubscriptionOperationStore operations=port(SubscriptionOperationStore.class,m->{throw new AssertionError("Operation store reached before clock fence");});
    final SubscriptionCatalogPort catalog=port(SubscriptionCatalogPort.class,m->{throw new AssertionError("Catalog reached");});
    final ProviderCatalogMappingPort mappings=port(ProviderCatalogMappingPort.class,m->{throw new AssertionError("Mapping reached");});
    final SubscriptionBillingProofStore proofs=port(SubscriptionBillingProofStore.class,m->{if(m.name().equals("setup"))return Optional.of(new SubscriptionBillingProofStore.Setup(this.operationId,s.id(),f.account.id(),UUID.randomUUID(),"seti_owned","pm_owned"));throw new AssertionError("Proof reached before clock fence");});
    final StripeBillingOwnership ownership=new StripeBillingOwnership(f.credentials,f.operations,f.bindings,f.digest,f.accounts,f.sites,f.customers,subscriptions,operations,catalog,mappings);
    final UUID operationId=UUID.randomUUID();
    final SubscriptionOperation operation=new SubscriptionOperation(operationId,f.site,f.customer,s.id(),SubscriptionOperation.Kind.CREATE,SubscriptionOperation.Status.PROCESSING,"site","digest","fingerprint","subscription:"+f.account.id().value()+":"+operationId+":CREATE",0,1,null,null,now,now,0);
    static void denied(org.junit.jupiter.api.function.Executable call){
        var failure=assertThrows(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,call);
        assertEquals("PROVIDER_CAPABILITY_UNAVAILABLE",failure.code());
    }
    Command requiredTrial(){var t=s.billingPrice();var required=new SubscriptionPriceTerms(t.priceId(),t.priceKey(),t.version(),t.productId(),t.regularAmount(),t.interval(),t.trialDays(),dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL,t.taxMode());return new Command(f.account,operation,required,null,s.providerReference(),null,null,true,true);}
    Command command(boolean trial){return new Command(f.account,operation,s.billingPrice(),null,s.providerReference(),null,null,true,trial);}
    StripeSubscriptionProviderAdapter provider(){var p=new StripeSubscriptionProviderAdapter(f.credentials,f.clients,f.verification,f.operations,f.bindings,f.customerAdapter(),f.digest,f.accounts,f.sites,f.customers,subscriptions,operations,catalog,mappings,Clock.systemUTC(),null,proofs,Clock.systemUTC());p.clockOwnership(f.clocks);return p;}
    void admit(){f.admit();ownership.clockOwnership(f.clocks);}
    @ParameterizedTest @ValueSource(strings={"INTENT","READY","BOUND","STOPPED"})
    void persistedOwnerAlwaysRefusesCommonOwnership(String state){
        if(state.equals("READY")||state.equals("BOUND")){f.createReady();}else f.admit();
        if(state.equals("BOUND")){f.http.reply(f.customerJson("clock_owned"));f.customerAdapter().ensureOwnedCustomer(f.account,f.owner);assertEquals(1,f.binds);}
        if(state.equals("STOPPED"))f.clocks.stop(f.owner,dev.genericpayments.platform.provider.application.port.out.StripeBillingClockStore.StopReason.AUTHORITY_REVOKED,now);
        ownership.clockOwnership(f.clocks);int calls=f.http.calls.size();
        denied(()->ownership.owned(f.account,s.id()));assertEquals(calls,f.http.calls.size());
    }
    @Test void ordinaryOwnerRetainsExistingOwnershipResult(){ownership.clockOwnership(f.clocks);assertEquals(s,ownership.owned(f.account,s.id()));}
    @Test void intentForForeignCustomerDoesNotBlockOrdinaryScope(){
        admit();var o=f.owner;f.owner=new dev.genericpayments.platform.provider.application.model.ClockOwner(o.intent(),o.run(),o.account(),o.site(),new dev.genericpayments.platform.customers.domain.CustomerId(UUID.randomUUID()),o.externalAccount(),o.credentialsRef(),o.apiVersion(),o.initialFrozenTime(),o.name(),o.createKey(),o.createFingerprint(),o.admittedAt());
        assertEquals(s,ownership.owned(f.account,s.id()));
    }
    @Test void prepareCreateRejectsBeforeBothFastReturnsOrProofRead(){admit();var p=provider();denied(()->p.prepareCreate(command(false)));denied(()->p.prepareCreate(requiredTrial()));assertTrue(f.rows.isEmpty());assertTrue(f.http.calls.isEmpty());}
    @Test void directCreateSetupAndRetrieveRefuseBeforeHttpOrReceiptReservation(){
        admit();var p=provider();denied(()->p.create(command(false)));
        denied(()->p.prepareCreate(command(true)));
        denied(()->p.retrieve(new Lookup(f.account,s.id(),s.providerReference(),null)));
        assertTrue(f.rows.isEmpty());assertTrue(f.http.calls.isEmpty());assertEquals(0,f.verifications);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void directActionConfirmationsRefuseOtherwiseValidConsumedGrantBeforeReadingIt(boolean setup){
        var reads=new int[1];var action=action(setup,reads);admit();
        if(setup)denied(()->action.confirmPreparedSetup(actionCommand(true),token()));
        else denied(()->action.confirmAction(actionCommand(false),token()));
        assertEquals(0,reads[0]);assertTrue(f.http.calls.isEmpty());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void ordinaryConsumedGrantReplayStillReturnsOriginalAuthority(boolean setup){
        ownership.clockOwnership(f.clocks);var reads=new int[1];var action=action(setup,reads);
        if(setup)assertEquals("seti_owned",action.confirmPreparedSetup(actionCommand(true),token()).setupReference());
        else assertEquals(Outcome.ACCEPTED,action.confirmAction(actionCommand(false),token()).outcome());
        assertEquals(1,reads[0]);assertTrue(f.http.calls.isEmpty());
    }
    String token(){return "bga1_"+"A".repeat(43);}
    Command actionCommand(boolean setup){return new Command(f.account,operation,s.billingPrice(),null,setup?null:s.providerReference(),null,null,true,false);}
    StripeBillingActionAdapter action(boolean setup,int[] reads){
        var binding=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant.Binding(UUID.randomUUID(),operation.providerOperationKey(),"proof",setup?null:s.providerReference(),"cus_owned",null,null,null,setup?"setup-v1":"payment-v1",setup?"seti_owned":null);
        var grant=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant(UUID.randomUUID(),f.site,f.customer,f.account.id(),s.id(),operationId,"v1",binding,StripeBillingGrantTokens.hash(token()),now,now.plusSeconds(600),now,null,null,null,null);
        var actions=port(SubscriptionActionStore.class,m->{assertEquals("billing",m.name());reads[0]++;return Optional.of(grant);});
        var tx=new SubscriptionTransactionPort(){public<T>T required(java.util.function.Supplier<T> call){return call.get();}public<T>T requiresNew(java.util.function.Supplier<T> call){return call.get();}public boolean active(){return false;}};
        return new StripeBillingActionAdapter(ownership,null,f.credentials,f.clients,f.verification,actions,operations,tx,Clock.systemUTC());
    }
    @Test void productionClockPresenceInjectionIsRequiredAndClockAdapterIsNotRegistered()throws Exception{
        var required=org.springframework.beans.factory.annotation.Autowired.class;
        assertTrue(StripeBillingOwnership.class.getDeclaredField("clocks").getAnnotation(required).required());
        assertTrue(StripeSubscriptionProviderAdapter.class.getDeclaredMethod("clockOwnership",dev.genericpayments.platform.provider.application.port.out.StripeBillingClockStore.class).getAnnotation(required).required());
        assertEquals(1,Arrays.stream(StripeBillingCustomerAdapter.class.getConstructors()).filter(c->c.isAnnotationPresent(required)&&c.getAnnotation(required).required()).count());
        assertFalse(StripeBillingTestClockAdapter.class.isAnnotationPresent(org.springframework.stereotype.Component.class));
    }
    @Test void unexpectedExternalSubscriptionClockFailsSharedShapeBeforeAnyAdoption(){
        var remote=new com.stripe.model.Subscription();remote.setId("sub_foreign");remote.setObject("subscription");remote.setLivemode(false);remote.setTestClock("clock_foreign");
        var error=assertThrows(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,()->StripeBillingOwnership.requireCurrentRemote(remote,s,null,null));
        assertEquals("PROVIDER_EVIDENCE_MISMATCH",error.code());
    }
    StripeBillingInboxAdapter inbox(SubscriptionObjectPort objects){return new StripeBillingInboxAdapter(f.accounts,objects,ownership,f.bindings,operations,f.credentials,f.clients,f.verification,provider(),null,proofs,f.operations);}
    SubscriptionProviderEnvelope envelope(String type,String ref){return new SubscriptionProviderEnvelope(f.account.provider(),f.account.id(),"evt_owned",ref,type,now,now);}
    @Test void mappedSubscriptionInboxFailsBeforeEventGetOrReplyRepair(){
        admit();var objects=port(SubscriptionObjectPort.class,m->Optional.of(s.id()));
        denied(()->inbox(objects).resolve(envelope("SUBSCRIPTION_CHANGED","sub_owned")));assertTrue(f.http.calls.isEmpty());
    }
    @Test void unboundOriginalEventMayReadExactEventButCannotAdoptOrRepair(){
        admit();f.http.reply("{\"id\":\"evt_owned\",\"object\":\"event\",\"api_version\":\"2026-09-30.endive\",\"livemode\":false,\"type\":\"customer.subscription.created\",\"data\":{\"object\":{\"id\":\"sub_owned\",\"object\":\"subscription\",\"livemode\":false,\"metadata\":{\"subscription_id\":\""+s.id()+"\",\"operation_id\":\""+operationId+"\"}}}}");
        denied(()->inbox(port(SubscriptionObjectPort.class,m->Optional.empty())).resolve(envelope("SUBSCRIPTION_CHANGED","sub_owned")));
        assertEquals(1,f.http.calls.size());assertEquals("https://api.stripe.com/v1/events/evt_owned",f.http.calls.getFirst().url().toString());assertTrue(f.rows.isEmpty());
    }
    @Test void exactInvoiceParentReadCannotAdoptClockOwner(){
        admit();f.http.reply("{\"id\":\"in_owned\",\"object\":\"invoice\",\"livemode\":false,\"customer\":\"cus_owned\",\"parent\":{\"type\":\"subscription_details\",\"subscription_details\":{\"subscription\":\"sub_owned\"}}}");
        denied(()->inbox(port(SubscriptionObjectPort.class,m->Optional.of(s.id()))).resolve(envelope("SUBSCRIPTION_INVOICE_CHANGED","in_owned")));
        assertEquals(1,f.http.calls.size());assertEquals("https://api.stripe.com/v1/invoices/in_owned",f.http.calls.getFirst().url().toString());assertTrue(f.rows.isEmpty());
    }
    @Test void exactSetupReadCannotRepairReceiptOfClockOwner(){
        admit();f.http.reply("{\"id\":\"seti_owned\",\"object\":\"setup_intent\",\"metadata\":{\"subscription_id\":\""+s.id()+"\",\"operation_id\":\""+operationId+"\"}}");
        denied(()->inbox(port(SubscriptionObjectPort.class,m->{throw new AssertionError("Object mapping reached");})).resolve(envelope("SUBSCRIPTION_SETUP_CHANGED","seti_owned")));
        assertEquals(1,f.http.calls.size());assertEquals("https://api.stripe.com/v1/setup_intents/seti_owned",f.http.calls.getFirst().url().toString());assertTrue(f.rows.isEmpty());
    }
}
