package dev.genericpayments.acceptance.runtime;

import dev.genericpayments.platform.catalog.domain.BillingInterval;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Instant;
import java.util.*;

/** Validates synthetic observations only; it authenticates no external facts. */
final class OwnedBillingFailedRenewalScenario {
    enum Stage { INITIAL_PAID, FAILED_RENEWAL, REPEATED_FAILURE, GRACE_LAST_SECOND, GRACE_EXPIRED, METHOD_READY, RECOVERY_REQUESTED, RECOVERED }
    enum Authority { NONE, FRESH, LIVE, UNKNOWN, DISPATCHING }
    record Payment(UUID id, UUID billId, String invoice, String paymentIntent, long amountMinor, String currency) {}
    record Observation(Stage stage, UUID owner, String source, Subscription subscription, BillingRecord initialBill,
                       BillingRecord renewalBill, List<Payment> payments, UUID allocation, String paymentIntent, int quantity,
                       Instant businessAt, SubscriptionOperation operation, Authority authority) {
        Observation { payments = List.copyOf(payments); }
    }
    private int next;
    private boolean failed;
    private Observation initial, debt, request;
    private Instant lastBusinessAt;
    private final SubscriptionAccessPolicy access = new SubscriptionAccessPolicy();

    synchronized void observe(Observation o) {
        if (failed || next == Stage.values().length) throw new IllegalStateException("Scenario consumed");
        try {
            require(o != null && o.stage() == Stage.values()[next]);
            require(o.owner()!=null && o.source()!=null && o.source().matches("[0-9a-f]{40}") && o.subscription()!=null && o.initialBill()!=null
                && o.renewalBill()!=null && o.allocation()!=null && o.paymentIntent()!=null && !o.paymentIntent().isBlank()
                && o.quantity()==1 && o.businessAt()!=null && o.authority()!=null);
            require(lastBusinessAt == null || !o.businessAt().isBefore(lastBusinessAt));
            if (next == 0) validateInitial(o); else validateLater(o);
            lastBusinessAt=o.businessAt();
            next++;
        } catch (RuntimeException ex) { failed=true; throw ex; }
    }
    synchronized boolean complete() { return !failed && next == Stage.values().length; }
    private void validateInitial(Observation o) {
        var s=o.subscription(); var b=o.initialBill(); var r=o.renewalBill(); var t=s.billingPrice();
        require(s.billingStatus()==Subscription.Status.ACTIVE && s.currentPeriod()!=null && s.paidThrough()!=null
            && s.paidThrough().equals(s.currentPeriod().end()) && s.debt()==null && s.trial()==null
            && s.cancellation()==null && s.pendingChange()==null && t.equals(s.entitledPrice())
            && t.regularAmount().minorUnits()==2000 && t.regularAmount().currency().equals("EUR")
            && t.interval()==BillingInterval.MONTH && t.trialDays()==0);
        require(b.kind()==BillingRecord.Kind.INITIAL && b.settlement()==BillingRecord.Settlement.CONFIRMED_CASH
            && b.period().equals(s.currentPeriod()) && matchesBill(s,b));
        require(r.kind()==BillingRecord.Kind.RENEWAL && r.settlement()==BillingRecord.Settlement.OPEN
            && matchesBill(s,r) && r.period().start().equals(s.paidThrough())
            && r.period().end().isAfter(s.paidThrough().plusSeconds(259201)) && !r.id().equals(b.id())
            && !r.providerInvoiceReference().equals(b.providerInvoiceReference()));
        require(o.payments().size()==1 && paymentMatches(o.payments().get(0),b)
            && !o.payments().get(0).paymentIntent().equals(o.paymentIntent()));
        require(o.operation()==null && o.authority()==Authority.NONE);
        require(access.evaluate(s,o.businessAt()).reason()==AccessDecision.Reason.PAID);
        initial=o;
    }
    private void validateLater(Observation o) {
        var base=initial.subscription(); var s=o.subscription(); var r=o.renewalBill();
        require(initial.owner().equals(o.owner()) && initial.source().equals(o.source())
            && initial.initialBill().equals(o.initialBill()) && initial.allocation().equals(o.allocation())
            && initial.paymentIntent().equals(o.paymentIntent()));
        var original=initial.payments().get(0);
        require(!o.payments().isEmpty() && original.equals(o.payments().get(0)));
        var expectedBill=initial.renewalBill();
        if(o.stage()==Stage.RECOVERED) {
            require(r.equals(new BillingRecord(expectedBill.id(),expectedBill.siteId(),expectedBill.subscriptionId(),expectedBill.accountId(),expectedBill.providerInvoiceReference(),expectedBill.kind(),expectedBill.billedPrice(),expectedBill.previousPrice(),expectedBill.period(),expectedBill.amountDueMinor(),expectedBill.currency(),BillingRecord.Settlement.CONFIRMED_CASH,expectedBill.version(),expectedBill.createdAt(),expectedBill.updatedAt())));
        } else require(expectedBill.equals(r));
        if(o.stage()==Stage.FAILED_RENEWAL) {
            require(o.businessAt().equals(base.paidThrough().plusSeconds(3600)));
            var expected=base.renewalFailed(r.id(),r.period(),3,o.businessAt(),base.updatedAt());
            require(expected.equals(s)); debt=o;
        } else if(o.stage()==Stage.RECOVERED) {
            require(request!=null && request.operation().equals(o.operation()) && o.authority()==Authority.FRESH
                && o.businessAt().equals(base.paidThrough().plusSeconds(259201)));
            require(s.equals(debt.subscription().confirmPaid(base.billingPrice(),r.period(),o.businessAt(),base.updatedAt())));
            require(o.payments().size()==2 && paymentMatches(o.payments().get(1),r)
                && !o.payments().get(1).id().equals(original.id()) && o.paymentIntent().equals(o.payments().get(1).paymentIntent()));
            require(access.evaluate(s,o.businessAt()).reason()==AccessDecision.Reason.PAID);
            return;
        } else require(debt!=null && debt.subscription().equals(s));
        require(o.payments().equals(initial.payments()));
        if(o.stage()==Stage.REPEATED_FAILURE) {
            require(!o.businessAt().isBefore(debt.businessAt()) && s.equals(s.renewalFailed(r.id(),r.period(),99,o.businessAt(),base.updatedAt())));
        }
        Instant grace=base.paidThrough().plusSeconds(259200);
        switch(o.stage()) {
            case FAILED_RENEWAL, REPEATED_FAILURE -> require(access.evaluate(s,o.businessAt()).reason()==AccessDecision.Reason.GRACE);
            case GRACE_LAST_SECOND -> { var d=access.evaluate(s,o.businessAt()); require(o.businessAt().equals(grace.minusSeconds(1)) && d.reason()==AccessDecision.Reason.GRACE && grace.equals(d.validUntil())); }
            case GRACE_EXPIRED, METHOD_READY, RECOVERY_REQUESTED -> require(o.businessAt().equals(grace) && !access.evaluate(s,o.businessAt()).allowed());
            default -> throw new IllegalStateException("Unexpected stage");
        }
        if(o.stage()==Stage.RECOVERY_REQUESTED) {
            var op=o.operation();
            require(o.authority()==Authority.FRESH && op!=null && op.kind()==SubscriptionOperation.Kind.RECOVER
                && op.status()==SubscriptionOperation.Status.RESERVED && op.siteId().equals(s.siteId())
                && op.customerId().equals(s.customerId()) && op.subscriptionId().equals(s.id())
                && op.fingerprint().matches("v1\\.recover\\." + r.id() + "\\.[0-9a-f]{64}")
                && op.expectedRevision()==s.revision() && op.createdAt().equals(base.updatedAt()) && op.updatedAt().equals(base.updatedAt()));
            RecoveryRules.requireRecoverable(s,r,false); request=o;
        } else require(o.operation()==null && o.authority()==Authority.NONE);
    }
    private static boolean matchesBill(Subscription s,BillingRecord b) {
        return b.siteId().equals(s.siteId()) && b.accountId().equals(s.paymentAccountId()) && b.subscriptionId().equals(s.id())
            && b.billedPrice().equals(s.billingPrice()) && b.previousPrice()==null && b.amountDueMinor()==2000
            && b.currency().equals("EUR") && b.createdAt().equals(s.createdAt()) && b.updatedAt().equals(s.updatedAt());
    }
    private static boolean paymentMatches(Payment p,BillingRecord b) {
        return p!=null && p.id()!=null && b.id().equals(p.billId()) && b.providerInvoiceReference().equals(p.invoice())
            && p.paymentIntent()!=null && !p.paymentIntent().isBlank() && p.amountMinor()==2000 && "EUR".equals(p.currency());
    }
    private static void require(boolean condition) { if(!condition) throw new IllegalStateException("Invalid offline observation"); }
}
