package dev.genericpayments.platform.provider.adapter.out.stripe.billing;
import com.stripe.exception.*;
import com.stripe.model.*;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentConfirmParams;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Lookup;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import java.time.Clock;
import java.util.*;
import org.springframework.transaction.annotation.*;
/** Original synchronous SDK exception is the only entry point for causal decline proof. */
@Transactional(propagation=Propagation.NEVER)
public class StripeBillingConfirmationAdapter implements SubscriptionConfirmationProvider {
 private final StripeBillingOwnership ownership;private final StripeBillingGrantTokens tokens;private final StripeCredentialResolver credentials;
 private final StripeClientFactory clients;private final PaymentAccountVerificationPort verification;private final Clock clock;
 private static final Set<String> DECLINES=Set.of("generic_decline","insufficient_funds","do_not_honor");
 public StripeBillingConfirmationAdapter(StripeBillingOwnership ownership,StripeBillingGrantTokens tokens,StripeCredentialResolver credentials,StripeClientFactory clients,PaymentAccountVerificationPort verification,Clock clock){this.ownership=ownership;this.tokens=tokens;this.credentials=credentials;this.clients=clients;this.verification=verification;this.clock=clock;}
 @Transactional(propagation=Propagation.SUPPORTS,readOnly=true)
 public String capability(SubscriptionClientGrant root,SubscriptionConfirmationChain chain,SubscriptionConfirmationAttempt attempt){
  String descriptor=SubscriptionClientGrant.canonical("gp:billing-server-confirm:v2",root.descriptor(),chain.sessionId(),chain.origin(),chain.expiresAt(),chain.policyVersion(),chain.subscriptionRevision(),attempt.id(),attempt.predecessor(),attempt.issuedAt(),attempt.expiresAt());
  return "bca2_"+tokens.token(attempt.keyVersion(),descriptor).substring(5);
 }
 public Material read(Lookup lookup,SubscriptionClientGrant root,SubscriptionConfirmationAttempt.Receipt receipt){return inspect(lookup,root,receipt).material();}
 private record Read(Material material,PaymentIntent intent) {@Override public String toString(){return "ConfirmationRead[redacted]";}}
 private Read inspect(Lookup lookup,SubscriptionClientGrant root,SubscriptionConfirmationAttempt.Receipt receipt){
  if(!tokens.enabled()||root.binding().flow()!=SubscriptionClientSession.Flow.PAYMENT||!root.account().equals(lookup.account().id())||!root.subscription().equals(lookup.subscriptionId()))throw StripeBillingOwnership.mismatch();
  var proof=ownership.acknowledgedPayment(lookup);require(ownership.confirmationActionAvailable(proof,root.id()));verification.verify(proof.account());
  if(!root.operation().equals(proof.operation().id())||!root.binding().operationKey().equals(proof.operation().providerOperationKey()))throw StripeBillingOwnership.mismatch();
  try{
   var client=clients.create(credentials.resolve(lookup.account()));var graph=StripeBillingSnapshotMapper.retrievePaymentAction(client,proof,clock.instant());
   if(!graph.binding().equals(root.binding())||!proof.equals(ownership.acknowledgedPayment(lookup)))throw StripeBillingOwnership.mismatch();
   var pi=client.v1().paymentIntents().retrieve(root.binding().intentReference());
   require(root.binding().intentReference().equals(pi.getId())&&"requires_payment_method".equals(pi.getStatus())&&Boolean.FALSE.equals(pi.getLivemode())&&root.binding().customerReference().equals(pi.getCustomer())&&Objects.equals(pi.getAmountReceived(),0L)&&Objects.equals(graph.amount(),pi.getAmount())&&Objects.equals(graph.currency(),pi.getCurrency())&&"automatic".equals(pi.getCaptureMethod())&&List.of("card").equals(pi.getPaymentMethodTypes())&&graph.clientSecret().equals(pi.getClientSecret())&&"payment_intent".equals(pi.getObject()));
   if(receipt!=null){
    require(receipt.charge().equals(pi.getLatestCharge()));
    // Retrieve the exact attributed Charge; latest_charge alone can never establish attribution.
    var charge=client.v1().charges().retrieve(receipt.charge());
    require(receipt.charge().equals(charge.getId())&&"charge".equals(charge.getObject())&&Boolean.FALSE.equals(charge.getLivemode())&&pi.getId().equals(charge.getPaymentIntent())&&root.binding().customerReference().equals(charge.getCustomer())
      &&Objects.equals(pi.getAmount(),charge.getAmount())&&Objects.equals(pi.getCurrency(),charge.getCurrency())&&Objects.equals(charge.getAmountCaptured(),0L)&&Boolean.FALSE.equals(charge.getPaid())&&Boolean.FALSE.equals(charge.getCaptured())&&"failed".equals(charge.getStatus())
      &&"card_declined".equals(charge.getFailureCode())&&charge.getOutcome()!=null&&"issuer_declined".equals(charge.getOutcome().getType())&&receipt.declineCode().equals(charge.getOutcome().getReason()));
   }
   return new Read(new Material(credentials.resolve(lookup.account()).publishableKey(),graph.clientSecret()),pi);
  }catch(StripeException unsafe){throw StripeBillingOwnership.mismatch();}
 }
 public Outcome confirm(Lookup lookup,SubscriptionClientGrant root,SubscriptionConfirmationAttempt attempt,String token){
  if(attempt.state()!=SubscriptionConfirmationAttempt.State.DISPATCHING||!attempt.chain().equals(root.id())||!StripeBillingGrantTokens.hash(token).equals(attempt.tokenDigest()))throw StripeBillingOwnership.mismatch();
  var proof=ownership.acknowledgedPayment(lookup);require(ownership.confirmationActionAvailable(proof,root.id()));verification.verify(proof.account());
  try{
   var pi=clients.create(credentials.resolve(lookup.account())).v1().paymentIntents().confirm(root.binding().intentReference(),PaymentIntentConfirmParams.builder().setConfirmationToken(token).setUseStripeSdk(true).build(),RequestOptions.builder().setIdempotencyKey(attempt.providerKey()).setMaxNetworkRetries(0).build());
   if(!root.binding().intentReference().equals(pi.getId())||!root.binding().customerReference().equals(pi.getCustomer())||!Boolean.FALSE.equals(pi.getLivemode()))return unknown();
   return new Outcome("requires_action".equals(pi.getStatus())?SubscriptionConfirmationAttempt.State.REQUIRES_ACTION:SubscriptionConfirmationAttempt.State.OBSERVE,null);
  }catch(CardException original){
   var receipt=receipt(original,root.binding().intentReference());if(receipt==null)return unknown();
   try{var fresh=inspect(lookup,root,receipt);var embedded=original.getStripeError().getPaymentIntent();
    require(Boolean.FALSE.equals(embedded.getLivemode())&&root.binding().customerReference().equals(embedded.getCustomer())&&Objects.equals(fresh.intent().getCurrency(),embedded.getCurrency())&&Objects.equals(fresh.intent().getAmount(),embedded.getAmount())&&Objects.equals(embedded.getAmountReceived(),0L));
    return new Outcome(SubscriptionConfirmationAttempt.State.DECLINED,receipt);
   }catch(RuntimeException unsupported){return unknown();}
  }catch(StripeException uncertain){return unknown();}
 }
 public static SubscriptionConfirmationAttempt.Receipt receipt(CardException e,String intent){
  var error=e.getStripeError();if(!Objects.equals(e.getStatusCode(),402)||e.getRequestId()==null||!e.getRequestId().matches("req_[A-Za-z0-9_]{1,200}")||error==null||!"card_error".equals(error.getType())||!"card_declined".equals(error.getCode())||!"card_declined".equals(e.getCode())||!DECLINES.contains(error.getDeclineCode())||!Objects.equals(e.getDeclineCode(),error.getDeclineCode())||error.getCharge()==null||!error.getCharge().matches("ch_[A-Za-z0-9_]{1,200}")||!error.getCharge().equals(e.getCharge()))return null;
  var pi=error.getPaymentIntent();if(pi==null||!intent.equals(pi.getId())||!"payment_intent".equals(pi.getObject())||!"requires_payment_method".equals(pi.getStatus())||!error.getCharge().equals(pi.getLatestCharge()))return null;
  var last=pi.getLastPaymentError();if(last==null||!"card_error".equals(last.getType())||!error.getCode().equals(last.getCode())||!error.getDeclineCode().equals(last.getDeclineCode())||!error.getCharge().equals(last.getCharge()))return null;
  return new SubscriptionConfirmationAttempt.Receipt(e.getRequestId(),error.getCharge(),error.getDeclineCode());
 }
 private static Outcome unknown(){return new Outcome(SubscriptionConfirmationAttempt.State.UNKNOWN,null);}
 private static void require(boolean valid){if(!valid)throw StripeBillingOwnership.mismatch();}
}
