package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.OwnedClockSubscriptionReceipt;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.SubscriptionConfirmationUseCase.Scope;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionActionStore;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionInteractionTransaction;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingClockConfirmationSupport.*;

/** Explicit original receipt only; deliberately not a registered provider or public session port. */
public final class StripeBillingClockActionAdapter {
    private final OwnedClockSubscriptionReceipt receipt;
    private final SubscriptionInteractionTransaction phases;
    private final SubscriptionActionStore roots;
    private final StripeBillingGrantTokens tokens;
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final PaymentAccountVerificationPort verification;
    private final Clock utc;
    private final AtomicReference<String> selectionFailureStage=new AtomicReference<>();
    public StripeBillingClockActionAdapter(OwnedClockSubscriptionReceipt receipt,SubscriptionInteractionTransaction phases,SubscriptionActionStore roots,
        StripeBillingGrantTokens tokens,StripeCredentialResolver credentials,StripeClientFactory clients,PaymentAccountVerificationPort verification,Clock utc){
        this.receipt=Objects.requireNonNull(receipt);this.phases=Objects.requireNonNull(phases);this.roots=Objects.requireNonNull(roots);this.tokens=Objects.requireNonNull(tokens);
        this.credentials=Objects.requireNonNull(credentials);this.clients=Objects.requireNonNull(clients);this.verification=Objects.requireNonNull(verification);this.utc=Objects.requireNonNull(utc);
    }
    private static String settlementStage(StripeBillingSnapshotMapper.SettlementReason reason){
        if(reason==null)return "ACTION_GRAPH_INVOICE_SETTLEMENT";
        return switch(reason){
            case PRORATION_FLAG->"ACTION_GRAPH_INVOICE_SETTLEMENT_PRORATION_FLAG";
            case PRORATION_KIND->"ACTION_GRAPH_INVOICE_SETTLEMENT_PRORATION_KIND";
            case COLLECTION_METHOD->"ACTION_GRAPH_INVOICE_SETTLEMENT_COLLECTION_METHOD";
            case PAID_OFF_STRIPE->"ACTION_GRAPH_INVOICE_SETTLEMENT_PAID_OFF_STRIPE";
            case OVERPAID->"ACTION_GRAPH_INVOICE_SETTLEMENT_OVERPAID";
            case STARTING_BALANCE->"ACTION_GRAPH_INVOICE_SETTLEMENT_STARTING_BALANCE";
            case ENDING_BALANCE->"ACTION_GRAPH_INVOICE_SETTLEMENT_ENDING_BALANCE";
            case PRE_CREDIT_NOTES->"ACTION_GRAPH_INVOICE_SETTLEMENT_PRE_CREDIT_NOTES";
            case POST_CREDIT_NOTES->"ACTION_GRAPH_INVOICE_SETTLEMENT_POST_CREDIT_NOTES";
            case INVOICE_DISCOUNTS->"ACTION_GRAPH_INVOICE_SETTLEMENT_INVOICE_DISCOUNTS";
            case INVOICE_DISCOUNT_AMOUNTS->"ACTION_GRAPH_INVOICE_SETTLEMENT_INVOICE_DISCOUNT_AMOUNTS";
            case INVOICE_PRETAX_CREDITS->"ACTION_GRAPH_INVOICE_SETTLEMENT_INVOICE_PRETAX_CREDITS";
            case LINE_DISCOUNT_AMOUNTS->"ACTION_GRAPH_INVOICE_SETTLEMENT_LINE_DISCOUNT_AMOUNTS";
            case LINE_PRETAX_CREDITS->"ACTION_GRAPH_INVOICE_SETTLEMENT_LINE_PRETAX_CREDITS";
            case TOTAL->"ACTION_GRAPH_INVOICE_SETTLEMENT_TOTAL";
            case SUBTOTAL->"ACTION_GRAPH_INVOICE_SETTLEMENT_SUBTOTAL";
            case LINE_AMOUNT->"ACTION_GRAPH_INVOICE_SETTLEMENT_LINE_AMOUNT";
            case OPEN_DUE_POSITIVE->"ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_DUE_POSITIVE";
            case OPEN_DUE_CATALOG->"ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_DUE_CATALOG";
            case OPEN_PAID->"ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_PAID";
            case OPEN_REMAINING->"ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_REMAINING";
            case OPEN_ALLOCATION_STATUS->"ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_STATUS";
            case OPEN_ALLOCATION_PAYMENT->"ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_PAYMENT";
            case OPEN_ALLOCATION_TYPE->"ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_TYPE";
            case OPEN_ALLOCATION_INTENT->"ACTION_GRAPH_INVOICE_SETTLEMENT_OPEN_ALLOCATION_INTENT";
            case SNAPSHOT_UNSUPPORTED->"ACTION_GRAPH_INVOICE_SETTLEMENT_SNAPSHOT_UNSUPPORTED";
            case ACTION_OPEN_CASH->"ACTION_GRAPH_INVOICE_SETTLEMENT_ACTION_OPEN_CASH";
            case ACTION_OPEN->"ACTION_GRAPH_INVOICE_SETTLEMENT_ACTION_OPEN";
        };
    }
    private record Snapshot(SubscriptionInteractionTransaction.State state,SubscriptionClientGrant root){}
    public String selectionFailureStage(){return selectionFailureStage.get();}
    public NextAction nextAction(Scope scope){
        String checkpoint="ACTION_AUTHORITY";
        try{
            outside();if(!tokens.enabled())return null;
            require(scope.subscription().equals(receipt.intent().subscription())&&scope.operation().equals(receipt.intent().createOperation()));
            var before=phases.confirmationLocked(scope.context(),scope.subscription(),scope.operation(),receipt,state->{
                var current=roots.current(state.account().id(),state.operation().id()).orElse(null);
                return new Snapshot(state,current);
            });
            // One root for the lifetime of the owned intent. A reserved/expired/uncertain root cannot be reissued.
            if(before.root()!=null)return null;
            checkpoint="ACTION_VERIFICATION";
            verification.verify(before.state().account());
            checkpoint="ACTION_GRAPH";
            final StripeBillingSnapshotMapper.PaymentAction graph;
            var graphFailure=new StripeBillingSnapshotMapper.OwnedActionFailure();
            try{graph=graph(clients.create(credentials.resolve(before.state().account())),before.state(),receipt,utc.instant(),graphFailure);}
            catch(com.stripe.exception.StripeException|RuntimeException unsafe){
                if(graphFailure.guard()!=null)checkpoint=switch(graphFailure.guard()){
                    case LINE_ASSOCIATION->"ACTION_GRAPH_LINE_ASSOCIATION";
                    case INVOICE_SETTLEMENT->settlementStage(graphFailure.settlement());
                    case PAYMENT_ALLOCATION->"ACTION_GRAPH_PAYMENT_ALLOCATION";
                };
                throw StripeBillingOwnership.mismatch();
            }
            checkpoint="ACTION_RESERVATION";
            return phases.confirmationLocked(scope.context(),scope.subscription(),scope.operation(),receipt,state->{
                same(before.state(),state);if(roots.current(state.account().id(),state.operation().id()).isPresent())return null;
                var now=utc.instant().truncatedTo(ChronoUnit.MICROS);require(!now.isBefore(receipt.acknowledgedAt()));
                var s=state.subscription();var root=new SubscriptionClientGrant(UUID.randomUUID(),s.siteId(),s.customerId(),s.paymentAccountId(),s.id(),state.operation().id(),tokens.activeVersion(),graph.binding(),null,now,now.plusSeconds(600),null,null,null,null,null);
                var token=tokens.token(root.keyVersion(),root.descriptor());root=root.digest(StripeBillingGrantTokens.hash(token));
                roots.reserveOwnedInitialBilling(root,receipt);return new NextAction(ActionKind.PROVIDER_CLIENT,token,root.expiresAt());
            });
        }catch(RuntimeException failure){selectionFailureStage.compareAndSet(null,checkpoint);throw failure;}
    }
}
