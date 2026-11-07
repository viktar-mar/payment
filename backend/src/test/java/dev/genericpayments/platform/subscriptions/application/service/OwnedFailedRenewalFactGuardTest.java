package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import static dev.genericpayments.platform.subscriptions.application.service.SubscriptionBillingTimeServiceTest.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.sites.domain.*;
import dev.genericpayments.platform.customers.domain.Customer;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Pure inert-port fact guard tests: no Spring context, database, provider, or extensions. */
class OwnedFailedRenewalFactGuardTest {
    private static final Instant UTC=Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant BILLING=UTC.plus(Duration.ofDays(35));
    @Test void authenticatedMethodCompletesProcessingSetupWithoutChangingDebtOrRevision() {
        var h=new Harness(BILLING,PaymentProviderType.STRIPE,(c,s,o,b,u)->{},false,true);
        var v=method(h);var original=h.s;var records=new HashMap<>(h.records);
        h.apply(v);
        assertThat(h.operation.status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(h.operation.version()).isEqualTo(2);assertThat(h.s).isEqualTo(original);
        assertThat(h.records).isEqualTo(records);assertThat(h.observations).isEqualTo(1);
        assertThat(h.journal).isEmpty();assertThat(h.callbacks).isEmpty();
    }
    @Test void legacyConstructionStillDeniesMethodBeforeFenceOrCash() {
        var h=new Harness(BILLING,PaymentProviderType.STRIPE,null,true);reject(h,method(h));
    }
    @Test void explicitMethodCompositionRetainsOneOrdinaryAutowiredConstructorAndRequiresBillingProofStore() {
        assertThat(Arrays.stream(SubscriptionFactService.class.getConstructors()).filter(c->c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)).count()).isEqualTo(1);
        assertThat(dev.genericpayments.platform.support.SubscriptionFactConstructors.methodEnabled().getParameterTypes()).endsWith(SubscriptionIsolatedMethodProofPort.class,SubscriptionBillingProofStore.class);
    }
    @Test void approvingMethodProofCannotAdmitWrongStatusForeignMarkerOrChangedDebtShape() {
        for(var bad:List.of("reserved","unknown","foreign","pending","cash","notReady","revision","period")){
            var h=new Harness(BILLING,PaymentProviderType.STRIPE,null,false,true);var v=method(h);var o=h.operation;
            if(bad.equals("reserved")||bad.equals("unknown"))h.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),bad.equals("reserved")?SubscriptionOperation.Status.RESERVED:SubscriptionOperation.Status.UNKNOWN,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),null,o.createdAt(),o.updatedAt(),o.version());
            var invoices=v.invoices();if(bad.equals("cash")){var i=invoices.get(1);invoices=List.of(invoices.getFirst(),new Invoice(i.reference(),i.kind(),i.price(),null,i.period(),1000,"EUR",SettlementKind.CASH,List.of(new CashObject("pi_other",1000,"EUR",dev.genericpayments.platform.payments.domain.PaymentStatus.SUCCEEDED,UTC))));}
            var snapshot=new Snapshot(v.subscriptionReference(),v.status(),v.billingPrice(),bad.equals("period")?new Subscription.Period(v.period().start().plusSeconds(1),v.period().end()):v.period(),null,null,invoices,bad.equals("foreign")?"foreign":v.operationKey(),!bad.equals("notReady"),bad.equals("revision")?1:0,UTC,new OperationObservation(v.operationKey(),bad.equals("pending")?OperationState.PENDING:OperationState.APPLIED));
            reject(h,snapshot);
        }
    }
    @Test void methodRequiresExpectedSampleAndExactSelectedFenceBeforeEffects() {
        for(var bad:List.of("missingSample","time","mode","version","sequence","operation")){
            var h=new Harness(BILLING,PaymentProviderType.STRIPE,null,false,true);var v=method(h);var o=h.operation;
            var expected=bad.equals("missingSample")?null:new SubscriptionBillingTimePort.Sample(bad.equals("time")?BILLING.plusSeconds(1):BILLING,bad.equals("mode")?SubscriptionBillingTimePort.Mode.ORDINARY:SubscriptionBillingTimePort.Mode.ISOLATED);
            var fence=new Fence(h.s.revision(),bad.equals("operation")?UUID.randomUUID():o.id(),bad.equals("version")?o.version()+1:o.version(),null,null,bad.equals("sequence")?-1L:0L);
            var command=new dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Command(h.account,h.s.id(),v,SubscriptionEvent.Source.RECONCILIATION,"fact","test",fence,expected);
            assertThatThrownBy(()->h.service.apply(command)).isInstanceOf(RuntimeException.class);assertThat(h.observations).isZero();assertThat(h.writes).isZero();assertThat(h.cash).isEmpty();
        }
    }
    private static Snapshot method(Harness h) {
        var v=recovery(h);var o=h.operation;String key="subscription:"+h.s.paymentAccountId().value()+":"+o.id()+":SETUP";
        h.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Status.PROCESSING,o.authorityScope(),o.keyDigest(),"method",key,o.expectedRevision(),o.policyVersion(),o.providerReference(),null,o.createdAt(),o.updatedAt(),1);
        var i=v.invoices().getFirst();var open=new Invoice(i.reference(),i.kind(),i.price(),null,i.period(),i.amountDueMinor(),i.currency(),SettlementKind.OPEN,List.of());
        var firstPeriod=new Subscription.Period(h.s.paidThrough().minus(Duration.ofDays(30)),h.s.paidThrough());
        var initial=new Invoice("in_initial",BillingRecord.Kind.INITIAL,h.s.billingPrice(),null,firstPeriod,1000,"EUR",SettlementKind.CASH,List.of(new CashObject("pi_initial",1000,"EUR",dev.genericpayments.platform.payments.domain.PaymentStatus.SUCCEEDED,UTC)));
        h.records.put(initial.reference(),new BillingRecord(UUID.randomUUID(),h.s.siteId(),h.s.id(),h.s.paymentAccountId(),initial.reference(),initial.kind(),initial.price(),null,firstPeriod,1000,"EUR",BillingRecord.Settlement.CONFIRMED_CASH,0,UTC,UTC));
        return new Snapshot(v.subscriptionReference(),Subscription.Status.PAST_DUE,v.billingPrice(),v.period(),null,null,List.of(initial,open),key,true,0,UTC,new OperationObservation(key,OperationState.APPLIED));
    }
    @Test void explicitProofAdmitsOnlyReservedMatchingRecoveryBeforeCashAndFence() {
        var calls=new ArrayList<String>();var holder=new Harness[1];
        var h=new Harness(BILLING,PaymentProviderType.STRIPE,(c,s,o,b,u)->{assertThat(holder[0].writes).isZero();assertThat(holder[0].observations).isZero();calls.add("proof");assertThat(b).isEqualTo(BILLING);assertThat(u).isEqualTo(UTC);});
        holder[0]=h;var snapshot=recovery(h);h.apply(snapshot);
        assertThat(calls).containsExactly("proof");assertThat(h.observations).isEqualTo(1);assertThat(h.cash).hasSize(1);
        assertThat(h.operation.status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(h.s.debt()).isNull();assertThat(h.s.billingStatus()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(h.journal).anySatisfy(e->assertThat(e.type()).isEqualTo(SubscriptionEvent.Type.PERIOD_PAID));
    }
    @Test void legacyBillingTimeConstructorStillDeniesRecovery() {var h=new Harness(BILLING,PaymentProviderType.STRIPE,null,true);reject(h,recovery(h));}
    @Test void foreignHarmlessOperationCannotPassApprovingProof() {
        var calls=new ArrayList<String>();var h=new Harness(BILLING,PaymentProviderType.STRIPE,(c,s,o,b,u)->calls.add("proof"));var v=recovery(h);var o=h.operation;
        h.extra.add(new SubscriptionOperation(UUID.randomUUID(),new SiteId(UUID.randomUUID()),o.customerId(),o.subscriptionId(),SubscriptionOperation.Kind.RECONCILE,SubscriptionOperation.Status.RESERVED,"test","digest","test","test",0,0,null,null,UTC,UTC,0));
        reject(h,v);assertThat(calls).isEmpty();
    }
    @Test void duplicateRecoverOrUnrelatedOperationIsRejectedEvenWithApprovingProof() {
        for(var kind:List.of(SubscriptionOperation.Kind.RECOVER,SubscriptionOperation.Kind.SETUP,SubscriptionOperation.Kind.CREATE)) {
            var calls=new ArrayList<String>();var h=new Harness(BILLING,PaymentProviderType.STRIPE,(c,s,o,b,u)->calls.add("proof"));var v=recovery(h);var o=h.operation;
            h.extra.add(new SubscriptionOperation(UUID.randomUUID(),o.siteId(),o.customerId(),o.subscriptionId(),kind,SubscriptionOperation.Status.RESERVED,"test","digest","v1.paid."+"a".repeat(64),"test",o.expectedRevision(),0,null,null,UTC,UTC,0));
            assertThatThrownBy(()->h.apply(v)).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class);
            assertThat(h.writes).isZero();assertThat(h.observations).isZero();assertThat(h.cash).isEmpty();assertThat(calls).isEmpty();
        }
    }
    @Test void deniedProofRejectsBeforeEffects() {var h=new Harness(BILLING,PaymentProviderType.STRIPE);reject(h,recovery(h));}
    @Test void proofFailureRejectsBeforeEffects() {var h=new Harness(BILLING,PaymentProviderType.STRIPE,(c,s,o,b,u)->{throw new IllegalStateException("ISOLATED_BILLING_UNSUPPORTED");});reject(h,recovery(h));}
    @Test void approvingProofCannotAuthorizeUnknownOperationOrForeignMarkerOrUnsupportedShape() {
        for(var bad:List.of("unknown","foreign","annotation","cancel","revision","pending","wrongFence")) {
            var calls=new ArrayList<String>();var h=new Harness(BILLING,PaymentProviderType.STRIPE,(c,s,o,b,u)->calls.add("proof"));var v=recovery(h);
            if(bad.equals("unknown")){var o=h.operation;h.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),SubscriptionOperation.Status.UNKNOWN,o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),null,o.createdAt(),o.updatedAt(),o.version());}
            else if(bad.equals("wrongFence")){var o=h.operation;h.operation=new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),o.status(),o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision()+1,o.policyVersion(),o.providerReference(),null,o.createdAt(),o.updatedAt(),o.version());}
            else v=new Snapshot(v.subscriptionReference(),v.status(),v.billingPrice(),v.period(),null,bad.equals("cancel")?BILLING:null,v.invoices(),bad.equals("foreign")?"foreign":v.operationKey(),true,bad.equals("revision")?1:0,UTC,new OperationObservation(v.operationKey(),bad.equals("pending")?OperationState.PENDING:OperationState.APPLIED),bad.equals("annotation")?List.of(new RecoveryObservation("invoice",RecoveryDisposition.BLOCKED)):List.of());
            reject(h,v);assertThat(calls).as(bad).isEmpty();
        }
    }
    private static Snapshot recovery(Harness h) {
        var period=new Subscription.Period(BILLING.minusSeconds(10),BILLING.plusSeconds(100));
        var bill=UUID.randomUUID();h.s=h.s.renewalFailed(bill,period,3,BILLING,UTC);
        UUID id=UUID.randomUUID();String key="subscription:"+h.s.paymentAccountId().value()+":"+id+":RECOVER";
        h.operation=new SubscriptionOperation(id,h.s.siteId(),h.s.customerId(),h.s.id(),SubscriptionOperation.Kind.RECOVER,SubscriptionOperation.Status.RESERVED,"owned-failed-renewal:"+UUID.randomUUID(),"digest","v1.recover."+bill+"."+"a".repeat(64),key,h.s.revision(),0,h.s.providerReference(),null,UTC,UTC,0);
        var invoice=new Invoice("in_renewal",BillingRecord.Kind.RENEWAL,h.s.billingPrice(),null,period,1000,"EUR",SettlementKind.CASH,List.of(new CashObject("pi_recovery",1000,"EUR",dev.genericpayments.platform.payments.domain.PaymentStatus.SUCCEEDED,UTC)));
        h.records.put(invoice.reference(),new BillingRecord(bill,h.s.siteId(),h.s.id(),h.s.paymentAccountId(),invoice.reference(),BillingRecord.Kind.RENEWAL,h.s.billingPrice(),null,period,1000,"EUR",BillingRecord.Settlement.OPEN,0,UTC,UTC));
        return new Snapshot(h.s.providerReference(),Subscription.Status.ACTIVE,h.s.billingPrice(),period,null,null,List.of(invoice),key,true,0,UTC,new OperationObservation(key,OperationState.APPLIED));
    }
    private static void reject(Harness h,Snapshot snapshot) {assertThatThrownBy(()->h.apply(snapshot)).isInstanceOf(IllegalStateException.class).hasMessage("ISOLATED_BILLING_UNSUPPORTED");assertThat(h.observations).isZero();assertThat(h.writes).isZero();assertThat(h.cash).isEmpty();}
    private static class Harness {
        Subscription s=SubscriptionFixtures.active(UTC,UTC.plus(Duration.ofDays(30)));
        final PaymentAccount account; SubscriptionOperation operation;final List<SubscriptionOperation> extra=new ArrayList<>(); boolean isolated=true;
        Instant billing,fenceAt;int clockReads,observations,writes;
        final Map<String,BillingRecord> records=new HashMap<>();final List<SubscriptionEvent> journal=new ArrayList<>();final List<Instant> callbacks=new ArrayList<>();final List<Object> cash=new ArrayList<>();
        final SubscriptionFactService service;
        Harness(Instant billing){this(billing,PaymentProviderType.FAKE);}
        Harness(Instant billing,PaymentProviderType provider){this(billing,provider,null);}
        Harness(Instant billing,PaymentProviderType provider,SubscriptionIsolatedRecoveryProofPort proof){this(billing,provider,proof,false);}
        Harness(Instant billing,PaymentProviderType provider,SubscriptionIsolatedRecoveryProofPort proof,boolean legacy){
            this(billing,provider,proof,legacy,false);
        }
        Harness(Instant billing,PaymentProviderType provider,SubscriptionIsolatedRecoveryProofPort proof,boolean legacy,boolean method){
            account=PaymentAccount.create(s.paymentAccountId(),"account",provider,PaymentAccountMode.SANDBOX,"fake","ref");
            this.billing=billing;
            var operations=port(SubscriptionOperationStore.class,(m,a)->{if(m.equals("unresolved"))return java.util.stream.Stream.concat(operation==null?java.util.stream.Stream.empty():java.util.stream.Stream.of(operation),extra.stream()).toList();if(m.equals("lock"))return operation!=null&&operation.id().equals(a[0])?operation:extra.stream().filter(o->o.id().equals(a[0])).findFirst().orElseThrow();if(m.equals("find"))return Optional.ofNullable(operation);if(m.equals("save")){operation=(SubscriptionOperation)a[0];return operation;}throw new AssertionError(m);});
            var trials=port(TrialEligibilityStore.class,(m,a)->Optional.empty());
            var site=Site.create(s.siteId(),SiteKey.of("site"),"Site",s.paymentAccountId(),Set.of(java.net.URI.create("https://test.example")),Set.of(AuthenticationMode.SITE_TOKEN),PaymentMethodPolicy.automatic());
            var clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){clockReads++;return UTC;}};
            if(legacy){
                service=new SubscriptionFactService(port(SubscriptionStore.class,(m,a)->switch(m){case "findOwned"->Optional.of(s);case "lock"->s;case "save"->{writes++;s=(Subscription)a[0];yield s;}default->throw new AssertionError(m);}),
                operations,
                trials,
                port(dev.genericpayments.platform.sites.application.port.out.LockSitePort.class,(m,a)->Optional.of(site)),
                port(dev.genericpayments.platform.customers.application.port.out.CustomerStore.class,(m,a)->Optional.of(Customer.create(s.customerId(),s.siteId(),null,null,UTC))),
                id->Optional.of(account),
                port(BillingRecordStore.class,(m,a)->switch(m){case "find"->Optional.ofNullable(records.get(a[3]));case "upsert"->{writes++;var b=(BillingRecord)a[0];records.put(b.providerInvoiceReference(),b);yield b;}default->throw new AssertionError(m);}),
                command->{writes++;cash.add(command);return null;},
                port(SubscriptionEventStore.class,(m,a)->{writes++;journal.add((SubscriptionEvent)a[0]);return true;}),
                port(SubscriptionCatalogPort.class,(m,a)->s.billingPrice()),
                clock,
                (sub,event,access,enqueued)->{assertThat(access.evaluatedAt()).isEqualTo(event.occurredAt());callbacks.add(enqueued);},
                port(SubscriptionObjectPort.class,(m,a)->{writes++;assertThat(a[5]).isEqualTo(UTC);return null;}),
                (b,version,deadline)->new BillingMetadataPort.Captured(version,deadline),
                port(SubscriptionPolicyPort.class,(m,a)->new SubscriptionPolicy(s.siteId(),0,true,3,false,false,false,Set.of())),
                port(SubscriptionObservationPort.class,(m,a)->{observations++;assertThat(m).isIn("acceptRevision","consumeFence");return m.equals("consumeFence")?null:true;}),
                value->"digest",
                inert(SubscriptionQuoteStore.class),
                port(SubscriptionSweepPort.class,(m,a)->{assertThat(m).isEqualTo("requireCurrent");fenceAt=(Instant)a[1];return null;}),
                new SubscriptionReadPreparation(operations,trials),
                inert(SubscriptionAuthorityPort.class),
                new SubscriptionBillingTimePort(){public Sample sample(PaymentAccountId a,SiteId site,dev.genericpayments.platform.customers.domain.CustomerId customer,Instant at){assertThat(a).isEqualTo(s.paymentAccountId());assertThat(site).isEqualTo(s.siteId());assertThat(customer).isEqualTo(s.customerId());return new Sample(isolated?Harness.this.billing:at,isolated?Mode.ISOLATED:Mode.ORDINARY);}public void requireUnscopedAllowed(SiteId site,dev.genericpayments.platform.customers.domain.CustomerId customer){throw new AssertionError();}});
            }else{
                service=new SubscriptionFactService(port(SubscriptionStore.class,(m,a)->switch(m){case "findOwned"->Optional.of(s);case "lock"->s;case "save"->{writes++;s=(Subscription)a[0];yield s;}default->throw new AssertionError(m);}),operations,trials,
                port(dev.genericpayments.platform.sites.application.port.out.LockSitePort.class,(m,a)->Optional.of(site)),port(dev.genericpayments.platform.customers.application.port.out.CustomerStore.class,(m,a)->Optional.of(Customer.create(s.customerId(),s.siteId(),null,null,UTC))),
                id->Optional.of(account),port(BillingRecordStore.class,(m,a)->switch(m){case "find"->Optional.ofNullable(records.get(a[3]));case "upsert"->{writes++;var b=(BillingRecord)a[0];records.put(b.providerInvoiceReference(),b);yield b;}default->throw new AssertionError(m);}),
                command->{writes++;cash.add(command);return null;},port(SubscriptionEventStore.class,(m,a)->{writes++;journal.add((SubscriptionEvent)a[0]);return true;}),port(SubscriptionCatalogPort.class,(m,a)->s.billingPrice()),clock,
                (sub,event,access,enqueued)->{assertThat(access.evaluatedAt()).isEqualTo(event.occurredAt());callbacks.add(enqueued);},
                port(SubscriptionObjectPort.class,(m,a)->{writes++;assertThat(a[5]).isEqualTo(UTC);return null;}),
                (b,version,deadline)->new BillingMetadataPort.Captured(version,deadline),port(SubscriptionPolicyPort.class,(m,a)->new SubscriptionPolicy(s.siteId(),0,true,3,false,false,false,Set.of())),
                port(SubscriptionObservationPort.class,(m,a)->{observations++;assertThat(m).isIn("acceptRevision","consumeFence");return m.equals("consumeFence")?null:true;}),value->"digest",inert(SubscriptionQuoteStore.class),
                port(SubscriptionSweepPort.class,(m,a)->{assertThat(m).isEqualTo("requireCurrent");fenceAt=(Instant)a[1];return null;}),new SubscriptionReadPreparation(operations,trials),inert(SubscriptionAuthorityPort.class),
                new SubscriptionBillingTimePort(){public Sample sample(PaymentAccountId a,SiteId site,dev.genericpayments.platform.customers.domain.CustomerId customer,Instant at){assertThat(a).isEqualTo(s.paymentAccountId());assertThat(site).isEqualTo(s.siteId());assertThat(customer).isEqualTo(s.customerId());return new Sample(isolated?Harness.this.billing:at,isolated?Mode.ISOLATED:Mode.ORDINARY);}public void requireUnscopedAllowed(SiteId site,dev.genericpayments.platform.customers.domain.CustomerId customer){throw new AssertionError();}},proof==null?SubscriptionIsolatedRecoveryProofPort.denied():proof,
                method?(c,s,o,b,u)->{assertThat(writes).isZero();assertThat(observations).isZero();}:SubscriptionIsolatedMethodProofPort.denied(),
                port(SubscriptionBillingProofStore.class,(m,a)->{assertThat(m).isEqualTo("setup");return Optional.of(new SubscriptionBillingProofStore.Setup(operation.id(),s.id(),account.id(),UUID.randomUUID(),"seti_owned","pm_owned"));}));
            }
        }
        Snapshot snapshot(Subscription.Status status,SettlementKind settlement,Instant start,Instant cancellation){
            var period=new Subscription.Period(start,start.plus(Duration.ofDays(30)));
            var invoice=new Invoice("invoice-"+start.getEpochSecond(),BillingRecord.Kind.RENEWAL,s.billingPrice(),null,period,1000,"EUR",settlement,settlement==SettlementKind.CASH?List.of(new CashObject("cash-"+start.getEpochSecond(),1000,"EUR",dev.genericpayments.platform.payments.domain.PaymentStatus.SUCCEEDED,start)):List.of());
            return new Snapshot(s.providerReference(),status,s.billingPrice(),period,null,cancellation,List.of(invoice),null,true,2,UTC);
        }
        void initial(){
            s=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),null,Subscription.Status.INCOMPLETE,s.providerReference(),null,null,null,null,null,null,null,0,UTC,UTC,null);
            UUID id=UUID.randomUUID();String key="subscription:"+s.paymentAccountId().value()+":"+id+":CREATE";
            operation=new SubscriptionOperation(id,s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.CREATE,SubscriptionOperation.Status.PROCESSING,"customer:test","digest","v1.paid."+"a".repeat(64),key,0,0,s.providerReference(),null,UTC,UTC,1);
        }
        Snapshot initialSnapshot(Subscription.Status status,BillingRecord.Kind kind,List<CashObject> cash,OperationState state){
            var period=new Subscription.Period(BILLING.minusSeconds(10),BILLING.plusSeconds(100));
            return new Snapshot(s.providerReference(),status,s.billingPrice(),period,null,null,List.of(new Invoice("initial",kind,s.billingPrice(),null,period,1000,"EUR",SettlementKind.OPEN,cash)),operation.providerOperationKey(),false,0,UTC,new OperationObservation(operation.providerOperationKey(),state));
        }
        void apply(Snapshot snapshot){service.apply(new dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Command(account,s.id(),snapshot,SubscriptionEvent.Source.RECONCILIATION,"fact","unit",new Fence(s.revision(),operation==null?null:operation.id(),operation==null?null:operation.version(),null,null,0L),new SubscriptionBillingTimePort.Sample(billing,SubscriptionBillingTimePort.Mode.ISOLATED)));}
    }
}
