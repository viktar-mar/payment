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

/** Inert ports only; no Spring context, SQL, provider IO, or Mockito agent. */
class SubscriptionBillingTimeFactTest {
    private static final Instant UTC=Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant BILLING=UTC.plus(Duration.ofDays(35));

    @Test void paidAndUnpaidUseOneOperationalSampleForFenceAuditAndCallback() {
        var h=new Harness(BILLING);var paid=h.snapshot(Subscription.Status.ACTIVE,SettlementKind.CASH,BILLING,null);
        h.apply(paid);
        assertThat(h.s.currentPeriod()).isEqualTo(paid.period());assertThat(h.s.updatedAt()).isEqualTo(UTC);
        assertThat(h.fenceAt).isEqualTo(UTC);assertThat(h.clockReads).isEqualTo(1);
        assertThat(h.journal.getFirst().occurredAt()).isEqualTo(BILLING);assertThat(h.journal.getFirst().receivedAt()).isEqualTo(UTC);
        assertThat(h.callbacks).containsExactly(UTC);assertThat(h.cash).hasSize(1);
        assertThat(h.records.values()).allSatisfy(b->{assertThat(b.createdAt()).isEqualTo(UTC);assertThat(b.updatedAt()).isEqualTo(UTC);});
        h.billing=h.s.paidThrough();h.apply(h.snapshot(Subscription.Status.PAST_DUE,SettlementKind.OPEN,h.billing,null));
        assertThat(h.s.debt().graceDeadline()).isEqualTo(h.billing.plus(Duration.ofDays(3)));
        assertThat(h.s.updatedAt()).isEqualTo(UTC);assertThat(h.journal.getLast().occurredAt()).isEqualTo(h.billing);
        assertThat(h.callbacks).containsExactly(UTC,UTC);
    }
    @Test void equalTimeRecoveryAnnotationsAreRefusedWhilePlainPaidFactsAreAdmitted() {
        var h=new Harness(UTC);var paid=h.snapshot(Subscription.Status.ACTIVE,SettlementKind.CASH,UTC,null);
        var annotated=new Snapshot(paid.subscriptionReference(),paid.status(),paid.billingPrice(),paid.period(),null,null,paid.invoices(),null,true,paid.providerRevision(),UTC,null,
            List.of(new RecoveryObservation(paid.invoices().getFirst().reference(),RecoveryDisposition.BLOCKED)));
        assertRejected(h,annotated);
        h.apply(paid);
        assertThat(h.observations).isEqualTo(1);assertThat(h.cash).hasSize(1);assertThat(h.records).hasSize(1);
    }
    @Test void isolatedEqualTimeRejectsUnsupportedShapesBeforeObservationAndAnyWrite() {
        for(var status:List.of(Subscription.Status.TRIALING,Subscription.Status.PAUSED,Subscription.Status.CANCELLED)){
            var h=new Harness(UTC);assertRejected(h,h.snapshot(status,SettlementKind.OPEN,UTC,null));
        }
        var h=new Harness(UTC);assertRejected(h,h.snapshot(Subscription.Status.ACTIVE,SettlementKind.CASH,UTC,UTC.plusSeconds(100)));
        var changed=new Harness(UTC);var ordinary=changed.snapshot(Subscription.Status.ACTIVE,SettlementKind.CASH,UTC,null);
        var p=ordinary.billingPrice();var other=new SubscriptionPriceTerms(p.priceId(),p.priceKey(),p.version()+1,p.productId(),p.regularAmount(),p.interval(),p.trialDays(),p.trialPolicy(),p.taxMode());
        assertRejected(changed,new Snapshot(ordinary.subscriptionReference(),ordinary.status(),other,ordinary.period(),null,null,ordinary.invoices(),null,true,2,UTC));
        for(var kind:List.of(BillingRecord.Kind.PRORATION)){
            var x=new Harness(UTC);var v=x.snapshot(Subscription.Status.ACTIVE,SettlementKind.CASH,UTC,null);var i=v.invoices().getFirst();
            assertRejected(x,new Snapshot(v.subscriptionReference(),v.status(),v.billingPrice(),v.period(),null,null,List.of(new Invoice(i.reference(),kind,i.price(),i.price(),i.period(),i.amountDueMinor(),i.currency(),i.settlement(),i.cashObjects())),null,true,2,UTC));
        }
        var nativeRejected=new Harness(UTC,PaymentProviderType.STRIPE);
        assertRejected(nativeRejected,nativeRejected.snapshot(Subscription.Status.TRIALING,SettlementKind.OPEN,UTC,null));
        for(var originalShape:List.of("trial","cancellation","paused","change")){
            var x=new Harness(UTC);
            x.s=switch(originalShape){
                case "trial"->x.s.recordTrialHistory(x.s.currentPeriod(),UTC);
                case "cancellation"->x.s.scheduleCancellation(x.s.paidThrough(),UTC);
                case "paused"->x.s.pause(UTC);
                default->new Subscription(x.s.id(),x.s.siteId(),x.s.customerId(),x.s.productId(),x.s.paymentAccountId(),x.s.billingPrice(),x.s.entitledPrice(),x.s.billingStatus(),x.s.providerReference(),x.s.currentPeriod(),x.s.paidThrough(),null,null,new Subscription.Change(UUID.randomUUID(),x.s.billingPrice(),x.s.paidThrough(),Subscription.ChangeState.SCHEDULED),null,null,x.s.revision(),UTC,UTC,null);
            };
            assertRejected(x,x.snapshot(Subscription.Status.ACTIVE,SettlementKind.CASH,UTC,null));
        }
        var proof=new Harness(UTC);var v=proof.snapshot(Subscription.Status.ACTIVE,SettlementKind.CASH,UTC,null);
        assertRejected(proof,new Snapshot(v.subscriptionReference(),v.status(),v.billingPrice(),v.period(),null,null,v.invoices(),"resume",true,2,UTC,new OperationObservation("resume",OperationState.APPLIED)));
    }
    private void assertRejected(Harness h,Snapshot v){
        assertThatThrownBy(()->h.apply(v)).isInstanceOf(IllegalStateException.class).hasMessage("ISOLATED_BILLING_UNSUPPORTED");
        assertThat(h.observations).isZero();assertThat(h.writes).isZero();assertThat(h.records).isEmpty();assertThat(h.cash).isEmpty();
    }
    private static class Harness {
        Subscription s=SubscriptionFixtures.active(UTC,UTC.plus(Duration.ofDays(30)));
        final PaymentAccount account;
        Instant billing,fenceAt;int clockReads,observations,writes;
        final Map<String,BillingRecord> records=new HashMap<>();final List<SubscriptionEvent> journal=new ArrayList<>();final List<Instant> callbacks=new ArrayList<>();final List<Object> cash=new ArrayList<>();
        final SubscriptionFactService service;
        Harness(Instant billing){this(billing,PaymentProviderType.FAKE);}
        Harness(Instant billing,PaymentProviderType provider){
            account=PaymentAccount.create(s.paymentAccountId(),"account",provider,PaymentAccountMode.SANDBOX,"fake","ref");
            this.billing=billing;
            var operations=port(SubscriptionOperationStore.class,(m,a)->{if(m.equals("unresolved"))return List.of();throw new AssertionError(m);});
            var trials=port(TrialEligibilityStore.class,(m,a)->Optional.empty());
            var site=Site.create(s.siteId(),SiteKey.of("site"),"Site",s.paymentAccountId(),Set.of(java.net.URI.create("https://test.example")),Set.of(AuthenticationMode.SITE_TOKEN),PaymentMethodPolicy.automatic());
            var clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){clockReads++;return UTC;}};
            service=new SubscriptionFactService(port(SubscriptionStore.class,(m,a)->switch(m){case "findOwned"->Optional.of(s);case "lock"->s;case "save"->{writes++;s=(Subscription)a[0];yield s;}default->throw new AssertionError(m);}),operations,trials,
                port(dev.genericpayments.platform.sites.application.port.out.LockSitePort.class,(m,a)->Optional.of(site)),port(dev.genericpayments.platform.customers.application.port.out.CustomerStore.class,(m,a)->Optional.of(Customer.create(s.customerId(),s.siteId(),null,null,UTC))),
                id->Optional.of(account),port(BillingRecordStore.class,(m,a)->switch(m){case "find"->Optional.ofNullable(records.get(a[3]));case "upsert"->{writes++;var b=(BillingRecord)a[0];records.put(b.providerInvoiceReference(),b);yield b;}default->throw new AssertionError(m);}),
                command->{writes++;cash.add(command);return null;},port(SubscriptionEventStore.class,(m,a)->{writes++;journal.add((SubscriptionEvent)a[0]);return true;}),port(SubscriptionCatalogPort.class,(m,a)->s.billingPrice()),clock,
                (sub,event,access,enqueued)->{assertThat(access.evaluatedAt()).isEqualTo(event.occurredAt());callbacks.add(enqueued);},
                port(SubscriptionObjectPort.class,(m,a)->{writes++;assertThat(a[5]).isEqualTo(UTC);return null;}),
                (b,version,deadline)->new BillingMetadataPort.Captured(version,deadline),port(SubscriptionPolicyPort.class,(m,a)->new SubscriptionPolicy(s.siteId(),0,true,3,false,false,false,Set.of())),
                port(SubscriptionObservationPort.class,(m,a)->{observations++;assertThat(m).isEqualTo("acceptRevision");return true;}),value->"digest",inert(SubscriptionQuoteStore.class),
                port(SubscriptionSweepPort.class,(m,a)->{assertThat(m).isEqualTo("requireCurrent");fenceAt=(Instant)a[1];return null;}),new SubscriptionReadPreparation(operations,trials),inert(SubscriptionAuthorityPort.class),
                new SubscriptionBillingTimePort(){public Sample sample(PaymentAccountId a,SiteId site,dev.genericpayments.platform.customers.domain.CustomerId customer,Instant at){assertThat(a).isEqualTo(s.paymentAccountId());assertThat(site).isEqualTo(s.siteId());assertThat(customer).isEqualTo(s.customerId());return new Sample(Harness.this.billing,Mode.ISOLATED);}public void requireUnscopedAllowed(SiteId site,dev.genericpayments.platform.customers.domain.CustomerId customer){throw new AssertionError();}});
        }
        Snapshot snapshot(Subscription.Status status,SettlementKind settlement,Instant start,Instant cancellation){
            var period=new Subscription.Period(start,start.plus(Duration.ofDays(30)));
            var invoice=new Invoice("invoice-"+start.getEpochSecond(),BillingRecord.Kind.RENEWAL,s.billingPrice(),null,period,1000,"EUR",settlement,settlement==SettlementKind.CASH?List.of(new CashObject("cash-"+start.getEpochSecond(),1000,"EUR",dev.genericpayments.platform.payments.domain.PaymentStatus.SUCCEEDED,start)):List.of());
            return new Snapshot(s.providerReference(),status,s.billingPrice(),period,null,cancellation,List.of(invoice),null,true,2,UTC);
        }
        void apply(Snapshot snapshot){service.apply(new dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Command(account,s.id(),snapshot,SubscriptionEvent.Source.RECONCILIATION,"fact","unit",new Fence(s.revision(),null,null,new SubscriptionSweepPort.Claim(s.siteId(),s.id(),"owner",UUID.randomUUID(),s.revision()),null)));}
    }
}
