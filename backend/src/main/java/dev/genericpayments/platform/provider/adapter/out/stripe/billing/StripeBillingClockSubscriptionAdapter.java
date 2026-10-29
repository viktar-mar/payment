package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.exception.StripeException;
import com.stripe.model.testhelpers.TestClock;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.provider.application.service.ProviderOperationGuard;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit internal initial-only adapter. Deliberately absent from production registration.
 * Store calls are independent short transactions; provider traffic never holds lifecycle/owner locks. */
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NEVER)
public class StripeBillingClockSubscriptionAdapter {
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final PaymentAccountVerificationPort verification;
    private final ProviderOperationStore steps;
    private final StripeBillingClockStore clocks;
    private final StripeBillingClockSubscriptionStore owned;
    private final StripeBillingBindingPort bindings;
    private final SubscriptionStore subscriptions;
    private final SubscriptionOperationStore operations;
    private final Clock utc;
    public StripeBillingClockSubscriptionAdapter(StripeCredentialResolver credentials,StripeClientFactory clients,
        PaymentAccountVerificationPort verification,ProviderOperationStore steps,StripeBillingClockStore clocks,
        StripeBillingClockSubscriptionStore owned,StripeBillingBindingPort bindings,SubscriptionStore subscriptions,
        SubscriptionOperationStore operations,Clock utc){
        this.credentials=Objects.requireNonNull(credentials);this.clients=Objects.requireNonNull(clients);this.verification=Objects.requireNonNull(verification);
        this.steps=Objects.requireNonNull(steps);this.clocks=Objects.requireNonNull(clocks);this.owned=Objects.requireNonNull(owned);
        this.bindings=Objects.requireNonNull(bindings);this.subscriptions=Objects.requireNonNull(subscriptions);this.operations=Objects.requireNonNull(operations);this.utc=Objects.requireNonNull(utc);
    }
    public Reply createOwned(Command command,ClockOwner owner){
        requireNoTransaction();
        if(command==null||command.operation().kind()!=SubscriptionOperation.Kind.CREATE||!command.recurringConsent()||command.startTrial()
            ||command.current().trialDays()!=0||command.target()!=null||command.providerInvoiceReference()!=null||command.providerQuoteReference()!=null)throw StripeBillingOwnership.unsupported();
        requireOwner(command.account(),owner);
        var intent=owned.find(command.account().id(),command.operation().subscriptionId()).orElseGet(()->owned.admitFresh(command.account(),owner,command.operation().subscriptionId(),command.operation().id(),utc.instant()));
        current(intent,owner,command.account());
        var before=state(command.account(),intent);
        var supplied=command.operation();
        if(!command.current().equals(intent.terms())||!supplied.id().equals(intent.createOperation())||!supplied.subscriptionId().equals(intent.subscription())
            ||!supplied.siteId().equals(owner.site())||!supplied.customerId().equals(owner.customer())||!supplied.providerOperationKey().equals(intent.operationKey())
            ||!supplied.fingerprint().equals(intent.requestFingerprint())||supplied.expectedRevision()!=intent.expectedRevision()||supplied.policyVersion()!=intent.policyVersion()
            ||command.providerSubscriptionReference()!=null&&!command.providerSubscriptionReference().equals(before.subscription().providerReference()))throw StripeBillingOwnership.conflict();
        var receipt=owned.receipt(intent);
        if(receipt.isPresent())return accepted(requireOriginalReceipt(receipt.get(),intent).reference());
        var reserved=steps.reserveOwnedBillingSubscription(command.account(),intent);requireStep(reserved,intent);
        // A duplicate/uncertain invocation has no dispatch or stop authority, including bare ACK.
        if(reserved.status()!=ProviderOperation.Status.RESERVED||reserved.firstDispatchedAt()!=null)return unknown();
        String claimant=UUID.randomUUID().toString();
        var claim=steps.claim(reserved.id(),claimant,utc.instant(),Duration.ofSeconds(60));
        if(claim.isEmpty()){
            var completed=owned.receipt(intent);return completed.isPresent()?accepted(requireOriginalReceipt(completed.get(),intent).reference()):unknown();
        }
        var step=claim.get();var reason=StripeBillingClockStore.StopReason.SUBSCRIPTION_CREATE_FAILED;
        try{
            requireStep(step,intent);current(intent,owner,command.account());verification.verify(command.account());
            var guard=new ProviderOperationGuard();guard.requireDispatch(step,command.account(),intent.fingerprint(),utc.instant());
            var client=clients.create(credentials.resolve(command.account()));
            requireReady(client.v1().testHelpers().testClocks().retrieve(intent.clock().reference(),StripeBillingTestClockAdapter.options(null)),intent.clock());
            current(intent,owner,command.account());if(!before.equals(state(command.account(),intent)))throw StripeBillingOwnership.conflict();
            String key=guard.requireDispatch(step,command.account(),intent.fingerprint(),utc.instant());
            var remote=client.v1().subscriptions().create(StripeBillingOwnership.paidCreate(intent.customerReference(),intent.priceReference(),StripeBillingOwnership.metadata(intent)).build(),StripeBillingTestClockAdapter.options(key));
            StripeBillingOwnership.requireOwnedInitialRemote(remote,intent);
            if(!"incomplete".equals(remote.getStatus()))throw StripeBillingOwnership.mismatch();
            current(intent,owner,command.account());if(!before.equals(state(command.account(),intent)))throw StripeBillingOwnership.conflict();
            reason=StripeBillingClockStore.StopReason.SUBSCRIPTION_RECEIPT_FAILED;
            var item=remote.getItems().getData().getFirst();
            var saved=steps.acknowledgeOwnedBillingSubscription(intent,step,remote.getId(),remote.getCreated(),item.getId(),item.getCurrentPeriodStart(),item.getCurrentPeriodEnd(),utc.instant());
            current(intent,owner,command.account());
            return accepted(requireOriginalReceipt(saved,intent).reference());
        }catch(StripeException|RuntimeException unsafe){
            // Only this original winner may fail its claim and stop this finite run.
            try{steps.unknown(step.id(),claimant,step.version());}catch(RuntimeException ignored){}
            try{clocks.stop(owner,reason,utc.instant());}catch(RuntimeException ignored){}
            return unknown();
        }
    }
    public Snapshot retrieveOwned(Lookup lookup,ClockOwner owner){
        requireNoTransaction();
        if(lookup==null)throw StripeBillingOwnership.conflict();requireOwner(lookup.account(),owner);
        var receipt=owned.requireReceipt(lookup.account().id(),lookup.subscriptionId());var intent=receipt.intent();
        current(intent,owner,lookup.account());requireOriginalReceipt(receipt,intent);
        if(!receipt.reference().equals(lookup.reference())||!intent.operationKey().equals(lookup.providerOperationKey()))throw StripeBillingOwnership.unsupported();
        var before=state(lookup.account(),intent);
        verification.verify(lookup.account());var client=clients.create(credentials.resolve(lookup.account()));
        try{
            requireReady(client.v1().testHelpers().testClocks().retrieve(intent.clock().reference(),StripeBillingTestClockAdapter.options(null)),intent.clock());
            current(intent,owner,lookup.account());if(!before.equals(state(lookup.account(),intent)))throw StripeBillingOwnership.conflict();
            var ownership=new StripeBillingSnapshotMapper.Ownership(lookup.account(),StripeBillingOwnership.bound(before.subscription(),receipt.reference()),before.customer(),
                List.of(new StripeBillingSnapshotMapper.PublishedPrice(intent.terms(),intent.priceReference(),intent.productReference())));
            var result=StripeBillingSnapshotMapper.retrieveOwned(client,ownership,new StripeBillingSnapshotMapper.OwnedInitial(receipt),utc.instant());
            current(intent,owner,lookup.account());
            if(!receipt.equals(owned.requireReceipt(lookup.account().id(),lookup.subscriptionId()))||!before.equals(state(lookup.account(),intent)))throw StripeBillingOwnership.conflict();
            boolean paid=result.invoices().stream().anyMatch(i->i.settlement()==SettlementKind.CASH);
            return new Snapshot(result.subscriptionReference(),result.status(),result.billingPrice(),result.period(),null,null,result.invoices(),intent.operationKey(),result.paymentMethodReady(),0,result.observedAt(),
                new OperationObservation(intent.operationKey(),paid?OperationState.APPLIED:OperationState.PENDING));
        }catch(StripeException|RuntimeException unsafe){throw StripeBillingOwnership.mismatch();}
    }
    /** This adapter is also explicitly constructed, so annotation-only transaction protection is insufficient. */
    private static void requireNoTransaction(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw StripeBillingOwnership.conflict();
    }
    private record State(Subscription subscription,SubscriptionOperation operation,StripeBillingBindingPort.CustomerBinding customer){}
    private State state(PaymentAccount account,OwnedClockSubscription intent){
        var o=intent.clock().owner();
        var s=subscriptions.findOwned(account.id(),intent.subscription()).orElseThrow(StripeBillingOwnership::conflict);
        var operation=operations.find(o.site(),intent.subscription(),intent.createOperation()).orElseThrow(StripeBillingOwnership::conflict);
        var b=bindings.findCustomer(account.id(),o.site(),o.customer()).orElseThrow(StripeBillingOwnership::conflict);
        if(!s.id().equals(intent.subscription())||!s.paymentAccountId().equals(account.id())||!s.siteId().equals(o.site())||!s.customerId().equals(o.customer())||!s.billingPrice().equals(intent.terms())
            ||s.trial()!=null||s.pendingChange()!=null||s.cancellation()!=null||!s.nonTerminal()
            ||!operation.id().equals(intent.createOperation())||operation.kind()!=SubscriptionOperation.Kind.CREATE||!operation.subscriptionId().equals(s.id())
            ||!operation.siteId().equals(o.site())||!operation.customerId().equals(o.customer())||!operation.providerOperationKey().equals(intent.operationKey())
            ||!operation.fingerprint().equals(intent.requestFingerprint())||operation.expectedRevision()!=intent.expectedRevision()||operation.policyVersion()!=intent.policyVersion()
            ||!b.id().equals(intent.customerBinding())||!b.creationOperationId().equals(intent.customerCreationOperation())||!b.providerReference().equals(intent.customerReference()))throw StripeBillingOwnership.conflict();
        StripeBillingOwnership.requireBinding(account,s,b);return new State(s,operation,b);
    }
    private void current(OwnedClockSubscription intent,ClockOwner owner,PaymentAccount account){
        if(!intent.clock().owner().equals(owner)||!owned.requireCurrent(intent).equals(intent))throw StripeBillingOwnership.conflict();requireOwner(account,owner);
    }
    private void requireOwner(PaymentAccount account,ClockOwner owner){
        if(account==null||owner==null||!account.id().equals(owner.account())||!account.externalAccountId().equals(owner.externalAccount())||!account.credentialsRef().equals(owner.credentialsRef())
            ||account.provider()!=PaymentProviderType.STRIPE||account.mode()!=PaymentAccountMode.SANDBOX||account.status()!=PaymentAccountStatus.ACTIVE||!StripeBillingTestClockAdapter.API_VERSION.equals(owner.apiVersion()))throw StripeBillingOwnership.conflict();
        // Persisted owned find/admit/current/receipt validate active authority in lifecycle
        // owner order. Reentering the older Account-first clock helper would invert it.
    }
    private void requireStep(ProviderOperation step,OwnedClockSubscription intent){
        var owner=intent.clock().owner();
        if(step.kind()!=ProviderOperation.Kind.SUBSCRIPTION_CREATE||!step.accountId().equals(owner.account())||!step.externalAccountId().equals(owner.externalAccount())||!step.credentialsRef().equals(owner.credentialsRef())
            ||!step.resourceId().equals(intent.createOperation())||!step.operationKey().equals(intent.operationKey())||!step.fingerprint().equals(intent.fingerprint()))throw StripeBillingOwnership.conflict();
    }
    private OwnedClockSubscriptionReceipt requireOriginalReceipt(OwnedClockSubscriptionReceipt receipt,OwnedClockSubscription intent){
        if(!receipt.intent().equals(intent))throw StripeBillingOwnership.mismatch();
        var step=steps.findById(receipt.providerOperation()).orElseThrow(StripeBillingOwnership::mismatch);requireStep(step,intent);
        if(step.status()!=ProviderOperation.Status.ACKNOWLEDGED||!receipt.reference().equals(step.providerReference()))throw StripeBillingOwnership.mismatch();return receipt;
    }
    private static void requireReady(TestClock r,ReadyClock expected){
        var owner=expected.owner();
        if(r==null||!expected.reference().equals(r.getId())||!"test_helpers.test_clock".equals(r.getObject())||!Boolean.FALSE.equals(r.getLivemode())||Boolean.TRUE.equals(r.getDeleted())
            ||!owner.name().equals(r.getName())||!Objects.equals(expected.frozenTime(),r.getFrozenTime())||!Objects.equals(expected.providerCreated(),r.getCreated())||!Objects.equals(expected.providerDeletesAfter(),r.getDeletesAfter())
            ||!"ready".equals(r.getStatus())||r.getStatusDetails()!=null&&r.getStatusDetails().getAdvancing()!=null)throw StripeBillingOwnership.mismatch();
    }
    private static Reply accepted(String reference){return new Reply(Outcome.ACCEPTED,reference,null);}
    private static Reply unknown(){return new Reply(Outcome.UNKNOWN,null,null);}
}
