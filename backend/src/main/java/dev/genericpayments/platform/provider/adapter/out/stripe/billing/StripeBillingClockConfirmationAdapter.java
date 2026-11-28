package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentConfirmParams;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.OwnedClockSubscriptionReceipt;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Lookup;
import dev.genericpayments.platform.subscriptions.application.port.in.SubscriptionConfirmationUseCase.Scope;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionInteractionTransaction;
import java.time.*;
import java.util.*;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingClockConfirmationSupport.*;

/** Explicit receipt/session-bound internal port. The service's durable claim owns dispatch; this port never claims or retries. */
public final class StripeBillingClockConfirmationAdapter implements SubscriptionConfirmationProvider {
    private final OwnedClockSubscriptionReceipt receipt;private final Scope scope;private final SubscriptionInteractionTransaction phases;
    private final SubscriptionActionStore roots;private final SubscriptionConfirmationStore confirmations;private final StripeBillingGrantTokens tokens;
    private final StripeCredentialResolver credentials;private final StripeClientFactory clients;private final PaymentAccountVerificationPort verification;private final Clock utc;
    public StripeBillingClockConfirmationAdapter(OwnedClockSubscriptionReceipt receipt,Scope scope,SubscriptionInteractionTransaction phases,
        SubscriptionActionStore roots,SubscriptionConfirmationStore confirmations,StripeBillingGrantTokens tokens,StripeCredentialResolver credentials,
        StripeClientFactory clients,PaymentAccountVerificationPort verification,Clock utc){
        this.receipt=Objects.requireNonNull(receipt);this.scope=Objects.requireNonNull(scope);this.phases=Objects.requireNonNull(phases);this.roots=Objects.requireNonNull(roots);this.confirmations=Objects.requireNonNull(confirmations);
        this.tokens=Objects.requireNonNull(tokens);this.credentials=Objects.requireNonNull(credentials);this.clients=Objects.requireNonNull(clients);this.verification=Objects.requireNonNull(verification);this.utc=Objects.requireNonNull(utc);
    }
    /** Pure encoding also used while the calling service holds its guarded phase. */
    public String capability(SubscriptionClientGrant root,SubscriptionConfirmationChain chain,SubscriptionConfirmationAttempt attempt){
        root(root,receipt,tokens,utc.instant());require(root.id().equals(chain.id())&&chain.id().equals(attempt.chain())&&attempt.predecessor()==null);
        String descriptor=SubscriptionClientGrant.canonical("gp:billing-server-confirm:v2",root.descriptor(),chain.sessionId(),chain.origin(),chain.expiresAt(),chain.policyVersion(),chain.subscriptionRevision(),attempt.id(),attempt.predecessor(),attempt.issuedAt(),attempt.expiresAt());
        return "bca2_"+tokens.token(attempt.keyVersion(),descriptor).substring(5);
    }
    private record Snapshot(SubscriptionInteractionTransaction.State state,SubscriptionClientGrant root,SubscriptionConfirmationChain chain,SubscriptionConfirmationAttempt attempt){}
    private Snapshot snapshot(Lookup lookup,SubscriptionClientGrant expected,SubscriptionConfirmationAttempt consumed,String token){
        require(scope.subscription().equals(receipt.intent().subscription())&&scope.operation().equals(receipt.intent().createOperation()));
        return phases.confirmationLocked(scope.context(),scope.subscription(),scope.operation(),receipt,state->{
            require(lookup!=null&&lookup.account().equals(state.account())&&lookup.subscriptionId().equals(state.subscription().id())
                &&Objects.equals(lookup.reference(),receipt.reference())&&Objects.equals(lookup.providerOperationKey(),receipt.intent().operationKey()));
            var root=roots.billing(expected.tokenDigest()).orElseThrow(StripeBillingOwnership::mismatch);require(root.equals(new SubscriptionClientGrant(expected.id(),expected.site(),expected.customer(),expected.account(),expected.subscription(),expected.operation(),expected.keyVersion(),expected.binding(),expected.tokenDigest(),expected.issuedAt(),expected.expiresAt(),consumed==null?expected.consumedAt():root.consumedAt(),expected.supersededAt(),expected.sessionId(),expected.origin(),expected.clientExpiresAt())));root(root,receipt,tokens,utc.instant());
            var chain=confirmations.chain(root.id()).orElseThrow(StripeBillingOwnership::mismatch);var attempt=confirmations.latest(chain.id());
            require(scope.context().actor() instanceof SubscriptionActor.Customer);var session=((SubscriptionActor.Customer)scope.context().actor()).session();
            var now=utc.instant();
            require(root.id().equals(chain.id())&&root.tokenDigest().equals(chain.rootDigest())&&chain.sessionId().equals(session.sessionId().value())&&chain.origin().equals(session.origin())
                &&chain.policyVersion()==state.policy().version()&&chain.subscriptionRevision()==state.subscription().revision()&&now.isBefore(chain.expiresAt())
                &&!chain.expiresAt().isAfter(root.expiresAt())&&!chain.expiresAt().isAfter(session.expiresAt())&&attempt!=null&&attempt.chain().equals(chain.id())
                &&attempt.predecessor()==null&&attempt.successor()==null&&attempt.receipt()==null&&attempt.retryKey()==null&&attempt.keyVersion().equals(root.keyVersion())
                &&attempt.expiresAt().equals(chain.expiresAt())&&!attempt.issuedAt().isBefore(root.issuedAt())&&!now.isBefore(attempt.issuedAt()));
            if(consumed==null)require(attempt.state()==SubscriptionConfirmationAttempt.State.READY&&root.consumedAt()==null&&attempt.version()==0);
            else{
                require(attempt.equals(consumed)&&attempt.equals(confirmations.attempt(consumed.id()))&&attempt.state()==SubscriptionConfirmationAttempt.State.DISPATCHING&&attempt.version()==1
                    &&root.consumedAt()!=null&&!root.consumedAt().isBefore(attempt.issuedAt())&&!now.isBefore(root.consumedAt())&&token!=null&&token.matches("ctoken_[A-Za-z0-9]{1,200}")
                    &&StripeBillingGrantTokens.hash(token).equals(attempt.tokenDigest())&&attempt.confirmKey()!=null&&attempt.confirmKey().matches("[a-f0-9]{64}")
                    &&StripeBillingGrantTokens.hash(SubscriptionClientGrant.canonical("SERVER_CONFIRM_V2",attempt.id(),root.descriptor(),attempt.tokenDigest(),"use_stripe_sdk=true")).equals(attempt.fingerprint()));
            }
            return new Snapshot(state,root,chain,attempt);
        });
    }
    private void unchanged(Snapshot before,Snapshot after){same(before.state(),after.state());require(before.root().equals(after.root())&&before.chain().equals(after.chain())&&before.attempt().equals(after.attempt()));}
    public Material read(Lookup lookup,SubscriptionClientGrant root,SubscriptionConfirmationAttempt.Receipt predecessor){
        outside();require(predecessor==null);var before=snapshot(lookup,root,null,null);verification.verify(before.state().account());
        try{
            var graph=graph(clients.create(credentials.resolve(lookup.account())),before.state(),receipt,utc.instant());require(graph.binding().equals(root.binding()));
            unchanged(before,snapshot(lookup,root,null,null));return new Material(credentials.resolve(lookup.account()).publishableKey(),graph.clientSecret());
        }catch(com.stripe.exception.StripeException|RuntimeException unsafe){throw StripeBillingOwnership.mismatch();}
    }
    public Outcome confirm(Lookup lookup,SubscriptionClientGrant root,SubscriptionConfirmationAttempt attempt,String token){
        outside();
        try{
            var before=snapshot(lookup,root,Objects.requireNonNull(attempt),token);verification.verify(before.state().account());
            var client=clients.create(credentials.resolve(lookup.account()));var graph=graph(client,before.state(),receipt,utc.instant());require(graph.binding().equals(root.binding()));
            unchanged(before,snapshot(lookup,root,attempt,token));
            var pi=client.v1().paymentIntents().confirm(root.binding().intentReference(),PaymentIntentConfirmParams.builder().setConfirmationToken(token).setUseStripeSdk(true).build(),
                StripeBillingTestClockAdapter.options(attempt.providerKey()));
            ready(client,receipt.intent().clock());unchanged(before,snapshot(lookup,root,attempt,token));return outcome(pi,graph,root);
        }catch(com.stripe.exception.StripeException|RuntimeException unsafe){return unknown();}
    }
    private static final com.fasterxml.jackson.databind.ObjectMapper RESPONSE_JSON=new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static void envelope(PaymentIntent pi){
        require(pi!=null&&pi.getLastResponse()!=null);
        try{
            var raw=RESPONSE_JSON.readTree(pi.getLastResponse().body());require(raw!=null&&raw.isObject());
            for(String field:List.of("id","object","customer","currency","status","capture_method","confirmation_method"))require(raw.path(field).isTextual());
            for(String field:List.of("amount","amount_received"))require(raw.path(field).isIntegralNumber()&&raw.path(field).canConvertToLong());
            require(raw.path("livemode").isBoolean()&&raw.path("payment_method_types").isArray()&&raw.has("next_action")&&(raw.get("next_action").isNull()||raw.get("next_action").isObject()));
        }catch(java.io.IOException invalid){throw StripeBillingOwnership.mismatch();}
    }
    private static Outcome outcome(PaymentIntent pi,StripeBillingSnapshotMapper.PaymentAction graph,SubscriptionClientGrant root){
        envelope(pi);
        require(pi!=null&&"payment_intent".equals(pi.getObject())&&root.binding().intentReference().equals(pi.getId())&&root.binding().customerReference().equals(pi.getCustomer())
            &&Boolean.FALSE.equals(pi.getLivemode())&&Objects.equals(graph.amount(),pi.getAmount())&&Objects.equals(graph.currency(),pi.getCurrency())
            &&List.of("card").equals(pi.getPaymentMethodTypes())&&"automatic".equals(pi.getCaptureMethod())&&"automatic".equals(pi.getConfirmationMethod()));
        if("succeeded".equals(pi.getStatus())&&Objects.equals(graph.amount(),pi.getAmountReceived())&&pi.getNextAction()==null)return new Outcome(SubscriptionConfirmationAttempt.State.OBSERVE,null);
        if("requires_action".equals(pi.getStatus())&&Objects.equals(0L,pi.getAmountReceived())&&pi.getNextAction()!=null&&"use_stripe_sdk".equals(pi.getNextAction().getType())
            &&pi.getNextAction().getUseStripeSdk()!=null&&!pi.getNextAction().getUseStripeSdk().isEmpty())return new Outcome(SubscriptionConfirmationAttempt.State.REQUIRES_ACTION,null);
        return unknown();
    }
    private static Outcome unknown(){return new Outcome(SubscriptionConfirmationAttempt.State.UNKNOWN,null);}
}
