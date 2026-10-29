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
    public StripeBillingClockActionAdapter(OwnedClockSubscriptionReceipt receipt,SubscriptionInteractionTransaction phases,SubscriptionActionStore roots,
        StripeBillingGrantTokens tokens,StripeCredentialResolver credentials,StripeClientFactory clients,PaymentAccountVerificationPort verification,Clock utc){
        this.receipt=Objects.requireNonNull(receipt);this.phases=Objects.requireNonNull(phases);this.roots=Objects.requireNonNull(roots);this.tokens=Objects.requireNonNull(tokens);
        this.credentials=Objects.requireNonNull(credentials);this.clients=Objects.requireNonNull(clients);this.verification=Objects.requireNonNull(verification);this.utc=Objects.requireNonNull(utc);
    }
    private record Snapshot(SubscriptionInteractionTransaction.State state,SubscriptionClientGrant root){}
    public NextAction nextAction(Scope scope){
        outside();if(!tokens.enabled())return null;
        require(scope.subscription().equals(receipt.intent().subscription())&&scope.operation().equals(receipt.intent().createOperation()));
        var before=phases.confirmationLocked(scope.context(),scope.subscription(),scope.operation(),receipt,state->{
            var current=roots.current(state.account().id(),state.operation().id()).orElse(null);
            return new Snapshot(state,current);
        });
        // One root for the lifetime of the owned intent. A reserved/expired/uncertain root cannot be reissued.
        if(before.root()!=null)return null;
        verification.verify(before.state().account());
        final StripeBillingSnapshotMapper.PaymentAction graph;
        try{graph=graph(clients.create(credentials.resolve(before.state().account())),before.state(),receipt,utc.instant());}
        catch(com.stripe.exception.StripeException|RuntimeException unsafe){throw StripeBillingOwnership.mismatch();}
        return phases.confirmationLocked(scope.context(),scope.subscription(),scope.operation(),receipt,state->{
            same(before.state(),state);if(roots.current(state.account().id(),state.operation().id()).isPresent())return null;
            var now=utc.instant().truncatedTo(ChronoUnit.MICROS);require(!now.isBefore(receipt.acknowledgedAt()));
            var s=state.subscription();var root=new SubscriptionClientGrant(UUID.randomUUID(),s.siteId(),s.customerId(),s.paymentAccountId(),s.id(),state.operation().id(),tokens.activeVersion(),graph.binding(),null,now,now.plusSeconds(600),null,null,null,null,null);
            var token=tokens.token(root.keyVersion(),root.descriptor());root=root.digest(StripeBillingGrantTokens.hash(token));
            roots.reserveOwnedInitialBilling(root,receipt);return new NextAction(ActionKind.PROVIDER_CLIENT,token,root.expiresAt());
        });
    }
}
