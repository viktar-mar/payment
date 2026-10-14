package dev.genericpayments.platform.subscriptions.application.model;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Instant;
import java.util.*;

/** Provider-neutral observations. Acceptance is a receipt, never proof of entitlement. */
public final class SubscriptionProviderModels {
    private SubscriptionProviderModels() {}
    public record Capabilities(boolean conditionalUpdate,boolean safeImmediateCancel,boolean setup,boolean portal) {}
    public record Command(PaymentAccount account,SubscriptionOperation operation,SubscriptionPriceTerms current,
                          SubscriptionPriceTerms target,String providerSubscriptionReference,String providerInvoiceReference,
                          String providerQuoteReference,boolean recurringConsent,boolean startTrial) {
        /** Compatibility for original callers. CREATE orchestration supplies the durable server-only choice. */
        public Command(PaymentAccount account,SubscriptionOperation operation,SubscriptionPriceTerms current,
                       SubscriptionPriceTerms target,String subscription,String invoice,String quote,boolean consent) {
            this(account,operation,current,target,subscription,invoice,quote,consent,current.trialDays()>0);
        }
        // A continuation can carry today's non-trial price and the original CREATE trial decision.
        // Only first CREATE interprets trial terms; its provider boundary validates that combination.
        public Command {Objects.requireNonNull(account);Objects.requireNonNull(operation);Objects.requireNonNull(current);}
    }
    /** For CREATE only, DECLINED/null positively proves rejection before ANY resource/trial creation.
     * Missing identity, transport failure and uncertain creation MUST be UNKNOWN, never DECLINED/null.
     * DECLINED with identity may describe a failed invoice on a live resource, not terminal lifecycle facts
     * or proof that a reserved trial never started/cannot still start. Keep its reservation until authoritative facts. */
    public record Reply(Outcome outcome,String reference,NextAction action) {
        public Reply {Objects.requireNonNull(outcome);if(outcome==Outcome.ACCEPTED||outcome==Outcome.REQUIRES_ACTION)Objects.requireNonNull(reference);if((outcome==Outcome.REQUIRES_ACTION)!=(action!=null))throw new IllegalArgumentException("Invalid action receipt");}
    }
    public enum Outcome { ACCEPTED, REQUIRES_ACTION, DECLINED, UNKNOWN }
    public record NextAction(ActionKind kind,String secret,Instant expiresAt) {
        public NextAction {Objects.requireNonNull(kind);Objects.requireNonNull(secret);Objects.requireNonNull(expiresAt);}
        @Override public String toString(){return "NextAction[kind="+kind+", secret=[REDACTED], expiresAt="+expiresAt+"]";}
    }
    public enum ActionKind { FAKE_CONFIRM_PAYMENT, FAKE_CONFIRM_SETUP, PROVIDER_CLIENT }
    public record Lookup(PaymentAccount account,UUID subscriptionId,String reference,String providerOperationKey) {
        public Lookup {Objects.requireNonNull(account);Objects.requireNonNull(subscriptionId);}
    }
    public record Snapshot(String subscriptionReference,Subscription.Status status,SubscriptionPriceTerms billingPrice,
                           Subscription.Period period,Subscription.Period trial,Instant cancellationAt,List<Invoice> invoices,
                           String operationKey,boolean paymentMethodReady,long providerRevision,Instant observedAt,
                           OperationObservation operationObservation,List<RecoveryObservation> recoveryObservations) {
        public Snapshot(String subscriptionReference,Subscription.Status status,SubscriptionPriceTerms billingPrice,
                        Subscription.Period period,Subscription.Period trial,Instant cancellationAt,List<Invoice> invoices,
                        String operationKey,boolean paymentMethodReady,long providerRevision,Instant observedAt,
                        OperationObservation operationObservation) {
            this(subscriptionReference,status,billingPrice,period,trial,cancellationAt,invoices,operationKey,paymentMethodReady,providerRevision,observedAt,operationObservation,List.of());
        }
        public Snapshot(String subscriptionReference,Subscription.Status status,SubscriptionPriceTerms billingPrice,
                        Subscription.Period period,Subscription.Period trial,Instant cancellationAt,List<Invoice> invoices,
                        String operationKey,boolean paymentMethodReady,long providerRevision,Instant observedAt) {
            this(subscriptionReference,status,billingPrice,period,trial,cancellationAt,invoices,operationKey,paymentMethodReady,providerRevision,observedAt,null);
        }
        public Snapshot {Objects.requireNonNull(subscriptionReference);Objects.requireNonNull(status);Objects.requireNonNull(billingPrice);Objects.requireNonNull(observedAt);invoices=List.copyOf(invoices);recoveryObservations=List.copyOf(recoveryObservations);if(providerRevision<0)throw new IllegalArgumentException("Invalid provider revision");}
        /** Missing or ambiguous authority never proves that another financial attempt is safe. */
        public RecoveryDisposition recoveryDisposition(String invoice) {
            var matching=recoveryObservations.stream().filter(o->o.invoiceReference().equals(invoice)).toList();
            return matching.size()==1?matching.getFirst().disposition():RecoveryDisposition.BLOCKED;
        }
    }
    public record RecoveryObservation(String invoiceReference,RecoveryDisposition disposition) {
        public RecoveryObservation {Objects.requireNonNull(invoiceReference);Objects.requireNonNull(disposition);}
    }
    public enum RecoveryDisposition { BLOCKED, RETRY_ALLOWED, ASSOCIATE_EXISTING_ACTION }
    /** Authoritative disposition of the explicitly requested original operation, not its immutable receipt. */
    public record OperationObservation(String operationKey,OperationState state) {
        public OperationObservation {Objects.requireNonNull(operationKey);Objects.requireNonNull(state);}
    }
    public enum OperationState { PENDING, APPLIED, DECLINED }
    public record Invoice(String reference,BillingRecord.Kind kind,SubscriptionPriceTerms price,SubscriptionPriceTerms previousPrice,
                          Subscription.Period period,long amountDueMinor,String currency,SettlementKind settlement,List<CashObject> cashObjects) {
        public Invoice {
            Objects.requireNonNull(reference);Objects.requireNonNull(kind);Objects.requireNonNull(price);Objects.requireNonNull(period);Objects.requireNonNull(settlement);
            cashObjects=List.copyOf(cashObjects);
            if(amountDueMinor<0||!price.regularAmount().currency().equals(currency))throw new IllegalArgumentException("Invalid invoice amount or currency");
            if(previousPrice!=null&&(!previousPrice.productId().equals(price.productId())||!previousPrice.regularAmount().currency().equals(currency)))throw new IllegalArgumentException("Invalid previous terms");
            if(settlement==SettlementKind.ZERO&&(amountDueMinor!=0||!cashObjects.isEmpty()))throw new IllegalArgumentException("Zero invoice cannot carry cash");
            long paid=0;
            var references=new HashSet<String>();
            for(var cash:cashObjects) {
                if(!references.add(cash.reference()))throw new IllegalArgumentException("Duplicate cash object");
                if(!cash.currency().equals(currency))throw new IllegalArgumentException("Cash currency mismatch");
                if(cash.status()==PaymentStatus.SUCCEEDED)paid=Math.addExact(paid,cash.amountMinor());
            }
            if(settlement==SettlementKind.CASH&&(amountDueMinor<=0||paid!=amountDueMinor))throw new IllegalArgumentException("Cash settlement needs matching successful objects");
        }
    }
    public enum SettlementKind { OPEN, CASH, ZERO, UNSUPPORTED, VOID }
    /** A positive provider payment attempt; only SUCCEEDED proves movement. */
    public record CashObject(String reference,long amountMinor,String currency,PaymentStatus status,Instant occurredAt) {
        public CashObject {Objects.requireNonNull(reference);Objects.requireNonNull(currency);Objects.requireNonNull(status);Objects.requireNonNull(occurredAt);if(amountMinor<=0)throw new IllegalArgumentException("Cash object amount must be positive");}
    }
    public record QuoteRequest(Command command,SubscriptionPriceTerms target,ChangeTiming timing) {
        public QuoteRequest {Objects.requireNonNull(command);Objects.requireNonNull(target);Objects.requireNonNull(timing);}
    }
    public record ProviderQuote(String reference,long dueNowMinor,long nextAmountMinor,String currency,Instant effectiveAt,Instant expiresAt,boolean taxFinal) {}
    public record PortalNavigation(String navigationUrl,Instant expiresAt,boolean simulated) {
        public PortalNavigation {Objects.requireNonNull(navigationUrl);Objects.requireNonNull(expiresAt);}
        @Override public String toString(){return "PortalNavigation[navigationUrl=[REDACTED], expiresAt="+expiresAt+", simulated="+simulated+"]";}
    }
}
