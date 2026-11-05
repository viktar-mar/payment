package dev.genericpayments.acceptance.runtime;

import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.genericpayments.acceptance.runtime.OwnedBillingFailedRenewalScenario.*;

class OwnedBillingFailedRenewalScenarioTest {
    static final Instant U=Instant.parse("2025-01-01T00:00:00Z"), F=Instant.parse("2025-02-01T00:00:00Z"), E=Instant.parse("2025-03-01T00:00:00Z"), N=Instant.parse("2025-04-01T00:00:00Z"), G=E.plusSeconds(259200);
    static UUID id(){return UUID.randomUUID();}
    static class Fixture {
        final UUID owner=id(), allocation=id(); final String source="0123456789abcdef0123456789abcdef01234567";
        final ProductId product=new ProductId(id());
        final SubscriptionPriceTerms terms=new SubscriptionPriceTerms(new PriceId(id()),"monthly",1,product,Money.of(2000,"EUR"),BillingInterval.MONTH,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
        final Subscription initial=new Subscription(id(),new SiteId(id()),new CustomerId(id()),product,new PaymentAccountId(id()),terms,terms,Subscription.Status.ACTIVE,"sub",new Subscription.Period(F,E),E,null,null,null,null,null,1,U,U,null);
        final BillingRecord first=bill(id(),BillingRecord.Kind.INITIAL,new Subscription.Period(F,E),"inv-first",BillingRecord.Settlement.CONFIRMED_CASH);
        final BillingRecord open=bill(id(),BillingRecord.Kind.RENEWAL,new Subscription.Period(E,N),"inv-renewal",BillingRecord.Settlement.OPEN);
        final BillingRecord cash=bill(open.id(),BillingRecord.Kind.RENEWAL,open.period(),open.providerInvoiceReference(),BillingRecord.Settlement.CONFIRMED_CASH);
        final Payment initialPayment=new Payment(id(),first.id(),first.providerInvoiceReference(),"pi-first",2000,"EUR");
        final Payment renewalPayment=new Payment(id(),open.id(),open.providerInvoiceReference(),"pi-renewal",2000,"EUR");
        final Subscription failed=initial.renewalFailed(open.id(),open.period(),3,E.plusSeconds(3600),U);
        final Subscription repeated=failed.renewalFailed(open.id(),open.period(),99,E.plusSeconds(7200),U);
        final Subscription recovered=failed.confirmPaid(terms,open.period(),G.plusSeconds(1),U);
        final SubscriptionOperation op=new SubscriptionOperation(id(),initial.siteId(),initial.customerId(),initial.id(),SubscriptionOperation.Kind.RECOVER,SubscriptionOperation.Status.RESERVED,"owner","digest","v1.recover."+open.id()+"."+"a".repeat(64),"key",failed.revision(),1,null,null,U,U,0);
        BillingRecord bill(UUID id,BillingRecord.Kind kind,Subscription.Period p,String invoice,BillingRecord.Settlement settlement){return new BillingRecord(id,initial.siteId(),initial.id(),initial.paymentAccountId(),invoice,kind,terms,null,p,2000,"EUR",settlement,0,U,U);}
        Observation o(Stage stage,Subscription s,BillingRecord bill,List<Payment> payments,Instant at,SubscriptionOperation operation, Authority authority){return new Observation(stage,owner,source,s,first,bill,payments,allocation,"pi-renewal",1,at,operation,authority);}
        Observation[] transcript(){return new Observation[]{o(Stage.INITIAL_PAID,initial,open,List.of(initialPayment),F,null,Authority.NONE),o(Stage.FAILED_RENEWAL,failed,open,List.of(initialPayment),E.plusSeconds(3600),null,Authority.NONE),o(Stage.REPEATED_FAILURE,repeated,open,List.of(initialPayment),E.plusSeconds(7200),null,Authority.NONE),o(Stage.GRACE_LAST_SECOND,failed,open,List.of(initialPayment),G.minusSeconds(1),null,Authority.NONE),o(Stage.GRACE_EXPIRED,failed,open,List.of(initialPayment),G,null,Authority.NONE),o(Stage.METHOD_READY,failed,open,List.of(initialPayment),G,null,Authority.NONE),o(Stage.RECOVERY_REQUESTED,failed,open,List.of(initialPayment),G,op,Authority.FRESH),o(Stage.RECOVERED,recovered,cash,List.of(initialPayment,renewalPayment),G.plusSeconds(1),op,Authority.FRESH)};}
    }
    @Test void completeTranscriptValidatesOfflineAndCannotReplay(){
        var s=new OwnedBillingFailedRenewalScenario(); var observations=new Fixture().transcript();
        for(var o:observations) s.observe(o);
        assertTrue(s.complete());
        assertThrows(IllegalStateException.class,()->s.observe(observations[7]));
    }

    static Observation change(Observation o, UUID owner, String source, Subscription s, BillingRecord first, BillingRecord renewal, List<Payment> payments, UUID allocation, String pi, Instant at, SubscriptionOperation op, Authority authority) {
        return new Observation(o.stage(),owner,source,s,first,renewal,payments,allocation,pi,1,at,op,authority);
    }
    static void rejectsAt(Observation[] all, int index, Observation bad) {
        var scenario=new OwnedBillingFailedRenewalScenario();
        for(int i=0;i<index;i++) scenario.observe(all[i]);
        assertThrows(RuntimeException.class,()->scenario.observe(bad));
        assertThrows(IllegalStateException.class,()->scenario.observe(all[index]));
        assertFalse(scenario.complete());
    }
    @Test void rejectsChangedLineageBillPaymentsAndDeadline() {
        var f=new Fixture(); var a=f.transcript(); var o=a[1];
        rejectsAt(a,1,change(o,id(),o.source(),o.subscription(),o.initialBill(),o.renewalBill(),o.payments(),o.allocation(),o.paymentIntent(),o.businessAt(),null,Authority.NONE));
        rejectsAt(a,1,change(o,o.owner(),"bad-source",o.subscription(),o.initialBill(),o.renewalBill(),o.payments(),o.allocation(),o.paymentIntent(),o.businessAt(),null,Authority.NONE));
        rejectsAt(a,1,change(o,o.owner(),o.source(),o.subscription(),o.initialBill(),f.cash,o.payments(),o.allocation(),o.paymentIntent(),o.businessAt(),null,Authority.NONE));
        rejectsAt(a,1,change(o,o.owner(),o.source(),o.subscription(),o.initialBill(),o.renewalBill(),List.of(f.initialPayment,f.renewalPayment),o.allocation(),o.paymentIntent(),o.businessAt(),null,Authority.NONE));
        var moved=f.initial.renewalFailed(f.open.id(),f.open.period(),4,o.businessAt(),U);
        rejectsAt(a,1,change(o,o.owner(),o.source(),moved,o.initialBill(),o.renewalBill(),o.payments(),o.allocation(),o.paymentIntent(),o.businessAt(),null,Authority.NONE));
        var setup=a[5];
        rejectsAt(a,5,change(setup,setup.owner(),setup.source(),setup.subscription(),setup.initialBill(),setup.renewalBill(),List.of(f.initialPayment,f.renewalPayment),setup.allocation(),setup.paymentIntent(),setup.businessAt(),null,Authority.NONE));
        var recovery=a[7]; var changedInitial=new Payment(id(),f.first.id(),f.first.providerInvoiceReference(),"pi-first",2000,"EUR");
        rejectsAt(a,7,change(recovery,recovery.owner(),recovery.source(),recovery.subscription(),recovery.initialBill(),recovery.renewalBill(),List.of(changedInitial,f.renewalPayment),recovery.allocation(),recovery.paymentIntent(),recovery.businessAt(),recovery.operation(),Authority.FRESH));
        rejectsAt(a,7,change(recovery,recovery.owner(),recovery.source(),recovery.subscription(),recovery.initialBill(),recovery.renewalBill(),recovery.payments(),recovery.allocation(),"other-pi",recovery.businessAt(),recovery.operation(),Authority.FRESH));
    }
    @Test void rejectsUnsafeAuthorityOutOfOrderAndExpiryBoundary() {
        var a=new Fixture().transcript(); var o=a[6];
        for(var authority:List.of(Authority.LIVE,Authority.UNKNOWN,Authority.DISPATCHING,Authority.NONE))
            rejectsAt(a,6,change(o,o.owner(),o.source(),o.subscription(),o.initialBill(),o.renewalBill(),o.payments(),o.allocation(),o.paymentIntent(),o.businessAt(),o.operation(),authority));
        rejectsAt(a,0,a[1]);
        var last=a[3];
        rejectsAt(a,3,change(last,last.owner(),last.source(),last.subscription(),last.initialBill(),last.renewalBill(),last.payments(),last.allocation(),last.paymentIntent(),G,null,Authority.NONE));
    }
    @Test void rejectsChangedPaymentAmountCurrencyAndInvoice() {
        var f=new Fixture(); var a=f.transcript(); var o=a[7];
        for(var p:List.of(new Payment(id(),f.open.id(),"wrong", "pi-renewal",2000,"EUR"),
                new Payment(id(),f.open.id(),f.open.providerInvoiceReference(),"other",2000,"EUR"),
                new Payment(id(),f.open.id(),f.open.providerInvoiceReference(),"pi-renewal",1999,"EUR"),
                new Payment(id(),f.open.id(),f.open.providerInvoiceReference(),"pi-renewal",2000,"USD")))
            rejectsAt(a,7,change(o,o.owner(),o.source(),o.subscription(),o.initialBill(),o.renewalBill(),List.of(f.initialPayment,p),o.allocation(),o.paymentIntent(),o.businessAt(),o.operation(),Authority.FRESH));
    }
    @Test void captureAfterPeriodEndDoesNotRestoreCurrentAccess() {
        var f=new Fixture(); var late=N.plusSeconds(1);
        var captured=f.failed.confirmPaid(f.terms,f.open.period(),late,U);
        assertEquals(Subscription.Status.ACTIVE,captured.billingStatus());
        assertEquals(N,captured.paidThrough());
        var decision=new SubscriptionAccessPolicy().evaluate(captured,late);
        assertFalse(decision.allowed()); assertEquals(AccessDecision.Reason.EXPIRED,decision.reason());
    }

    @Test void rejectsCrossOwnerChangedTermsAndChangedPeriod() {
        var f=new Fixture(); var a=f.transcript(); var o=a[1]; var s=o.subscription();
        var other=new Subscription(s.id(),new SiteId(id()),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),s.debt(),s.terminalReason(),s.revision(),s.createdAt(),s.updatedAt(),s.lastReconciledAt());
        rejectsAt(a,1,change(o,o.owner(),o.source(),other,o.initialBill(),o.renewalBill(),o.payments(),o.allocation(),o.paymentIntent(),o.businessAt(),null,Authority.NONE));
        var changedTerms=new SubscriptionPriceTerms(f.terms.priceId(),f.terms.priceKey(),2,f.product,Money.of(2000,"EUR"),BillingInterval.MONTH,0,f.terms.trialPolicy(),f.terms.taxMode());
        var changed=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),changedTerms,changedTerms,s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),s.debt(),s.terminalReason(),s.revision(),s.createdAt(),s.updatedAt(),s.lastReconciledAt());
        rejectsAt(a,1,change(o,o.owner(),o.source(),changed,o.initialBill(),o.renewalBill(),o.payments(),o.allocation(),o.paymentIntent(),o.businessAt(),null,Authority.NONE));
        var changedPeriod=f.bill(f.open.id(),BillingRecord.Kind.RENEWAL,new Subscription.Period(E,N.plusSeconds(1)),f.open.providerInvoiceReference(),BillingRecord.Settlement.OPEN);
        rejectsAt(a,1,change(o,o.owner(),o.source(),s,o.initialBill(),changedPeriod,o.payments(),o.allocation(),o.paymentIntent(),o.businessAt(),null,Authority.NONE));
        rejectsAt(a,1,change(o,o.owner(),o.source(),s,o.initialBill(),o.renewalBill(),o.payments(),id(),o.paymentIntent(),o.businessAt(),null,Authority.NONE));
    }
    @Test void rejectsMissingRecoveryOperationAndEarlierRepeatedObservation() {
        var a=new Fixture().transcript(); var o=a[6];
        rejectsAt(a,6,change(o,o.owner(),o.source(),o.subscription(),o.initialBill(),o.renewalBill(),o.payments(),o.allocation(),o.paymentIntent(),o.businessAt(),null,Authority.FRESH));
        var repeat=a[2];
        rejectsAt(a,2,change(repeat,repeat.owner(),repeat.source(),repeat.subscription(),repeat.initialBill(),repeat.renewalBill(),repeat.payments(),repeat.allocation(),repeat.paymentIntent(),E, null,Authority.NONE));
    }

    @Test void recoveryOperationMustTargetTheSameDebtBill() {
        var f=new Fixture(); var a=f.transcript(); var o=a[6]; var op=o.operation();
        var wrong=new SubscriptionOperation(op.id(),op.siteId(),op.customerId(),op.subscriptionId(),op.kind(),op.status(),op.authorityScope(),op.keyDigest(),"v1.recover."+id()+"."+"a".repeat(64),op.providerOperationKey(),op.expectedRevision(),op.policyVersion(),op.providerReference(),op.safeFailureCode(),op.createdAt(),op.updatedAt(),op.version());
        rejectsAt(a,6,change(o,o.owner(),o.source(),o.subscription(),o.initialBill(),o.renewalBill(),o.payments(),o.allocation(),o.paymentIntent(),o.businessAt(),wrong,Authority.FRESH));
    }
    @Test void businessTimeCannotRollBackAfterRepeatedFailure() {
        var a=new Fixture().transcript(); var repeat=a[2];
        var late=change(repeat,repeat.owner(),repeat.source(),repeat.subscription(),repeat.initialBill(),repeat.renewalBill(),repeat.payments(),repeat.allocation(),repeat.paymentIntent(),G.minusNanos(1),null,Authority.NONE);
        var scenario=new OwnedBillingFailedRenewalScenario(); scenario.observe(a[0]); scenario.observe(a[1]); scenario.observe(late);
        assertThrows(IllegalStateException.class,()->scenario.observe(a[3]));
        assertThrows(IllegalStateException.class,()->scenario.observe(a[3]));
        assertFalse(scenario.complete());
    }
}
