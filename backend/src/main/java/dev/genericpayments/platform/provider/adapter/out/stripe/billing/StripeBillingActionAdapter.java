package dev.genericpayments.platform.provider.adapter.out.stripe.billing;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.time.*;import java.time.temporal.ChronoUnit;import java.util.*;
import org.springframework.transaction.annotation.*;
/** Ephemeral Billing client secrets are released only from the exact original owned provider graph. */
@Transactional(propagation=Propagation.NEVER)
public class StripeBillingActionAdapter implements SubscriptionClientSessionPort {
 private final StripeBillingOwnership ownership;private final StripeBillingGrantTokens tokens;private final StripeCredentialResolver credentials;
 private final StripeClientFactory clients;private final PaymentAccountVerificationPort verification;private final SubscriptionActionStore actions;
 private final SubscriptionOperationStore operations;private final SubscriptionTransactionPort tx;private final Clock securityClock;
 public StripeBillingActionAdapter(StripeBillingOwnership ownership,StripeBillingGrantTokens tokens,StripeCredentialResolver credentials,StripeClientFactory clients,
 PaymentAccountVerificationPort verification,SubscriptionActionStore actions,SubscriptionOperationStore operations,SubscriptionTransactionPort tx,Clock securityClock){
  this.ownership=ownership;this.tokens=tokens;this.credentials=credentials;this.clients=clients;this.verification=verification;this.actions=actions;this.operations=operations;this.tx=tx;this.securityClock=securityClock;
 }
 @Transactional(propagation=Propagation.SUPPORTS,readOnly=true)
 public boolean enabled(){return tokens.enabled();}
 public NextAction nextAction(Lookup lookup){
  outside();if(!tokens.enabled()||lookup.providerOperationKey()==null)return null;
  var operation=ownership.lookupOperation(lookup);
  if(operation.kind()==SubscriptionOperation.Kind.CREATE&&dev.genericpayments.platform.subscriptions.application.service.CreateSubscriptionFingerprint.startsTrial(operation.fingerprint())
      &&(lookup.reference()!=null||!ownership.setupPurpose(lookup)))return null;
  if(ownership.setupPurpose(lookup))return setupAction(lookup);
  var proof=ownership.acknowledgedPayment(lookup);if(terminal(proof.operation()))return null;
  var old=tx.requiresNew(()->actions.current(lookup.account().id(),proof.operation().id()).orElse(null));
  if(old!=null&&old.consumedAt()!=null)return null;
  var graph=read(proof);
  return tx.requiresNew(()->{
   operations.lock(proof.operation().id());var fresh=ownership.acknowledgedPayment(lookup);same(proof,fresh);
   var now=securityClock.instant().truncatedTo(ChronoUnit.MICROS);var current=actions.current(lookup.account().id(),proof.operation().id()).orElse(null);
   if(current!=null){if(current.consumedAt()!=null)return null;if(!current.binding().equals(graph.binding()))throw StripeBillingOwnership.mismatch();
    if(current.available(now))return action(current);if(now.isBefore(current.issuedAt()))throw StripeBillingOwnership.mismatch();}
   var expires=now.plusSeconds(600);
   if(fresh instanceof StripeBillingOwnership.OriginalChange change){var pending=change.result().result().pendingExpiry();if(pending==null)throw StripeBillingOwnership.mismatch();var deadline=Instant.ofEpochSecond(pending);if(deadline.isBefore(expires))expires=deadline;}
   if(!expires.isAfter(now))throw StripeBillingOwnership.mismatch();
   var s=fresh.subscription();var grant=new SubscriptionClientGrant(UUID.randomUUID(),s.siteId(),s.customerId(),s.paymentAccountId(),s.id(),fresh.operation().id(),tokens.activeVersion(),graph.binding(),null,now,expires,null,null,null,null,null);
   String token=tokens.token(grant.keyVersion(),grant.descriptor());grant=grant.digest(StripeBillingGrantTokens.hash(token));actions.reserveBilling(grant);
   return new NextAction(ActionKind.PROVIDER_CLIENT,token,grant.expiresAt());
  });
 }
 public Reply confirmAction(Command command,String token){
  outside();return tx.requiresNew(()->{
   var grant=actions.billing(StripeBillingGrantTokens.hash(token)).orElseThrow(StripeBillingOwnership::mismatch);
   if(grant.consumedAt()==null||!StripeBillingGrantTokens.matches(token,grant.tokenDigest())||!grant.account().equals(command.account().id())||!grant.operation().equals(command.operation().id())
    ||!grant.subscription().equals(command.operation().subscriptionId())||!grant.site().equals(command.operation().siteId())||!grant.customer().equals(command.operation().customerId())||!grant.binding().operationKey().equals(command.operation().providerOperationKey())||!Objects.equals(grant.binding().subscriptionReference(),command.providerSubscriptionReference()))throw StripeBillingOwnership.mismatch();
   return new Reply(Outcome.ACCEPTED,grant.binding().subscriptionReference(),null);
  });
 }
 public SubscriptionClientSession retrieve(Lookup lookup,SubscriptionClientGrant grant,String token){
  outside();if(!tokens.enabled()||!grant.available(securityClock.instant())||!StripeBillingGrantTokens.matches(token,grant.tokenDigest()))throw StripeBillingOwnership.unsupported();
  if(grant.binding().flow()==SubscriptionClientSession.Flow.SETUP)return setupSession(lookup,grant,token);
  var proof=ownership.acknowledgedPayment(lookup);if(terminal(proof.operation())||!grant.operation().equals(proof.operation().id())||!grant.account().equals(lookup.account().id())||!grant.subscription().equals(lookup.subscriptionId()))throw StripeBillingOwnership.mismatch();
  if(!java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),tokens.token(grant.keyVersion(),grant.descriptor()).getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw StripeBillingOwnership.mismatch();
  var graph=read(proof);same(proof,ownership.acknowledgedPayment(lookup));if(!graph.binding().equals(grant.binding())||!grant.available(securityClock.instant()))throw StripeBillingOwnership.mismatch();
  return new SubscriptionClientSession("stripe",SubscriptionClientSession.Flow.PAYMENT,credentials.resolve(lookup.account()).publishableKey(),graph.clientSecret(),token,grant.expiresAt());
 }
 public OwnedSetupReceipt confirmPreparedSetup(Command command,String token){
  outside();return tx.requiresNew(()->{
   var g=actions.billing(StripeBillingGrantTokens.hash(token)).orElseThrow(StripeBillingOwnership::mismatch);
   if(g.consumedAt()==null||g.binding().flow()!=SubscriptionClientSession.Flow.SETUP||!StripeBillingGrantTokens.matches(token,g.tokenDigest())||!g.account().equals(command.account().id())||!g.operation().equals(command.operation().id())||!g.subscription().equals(command.operation().subscriptionId())||!g.site().equals(command.operation().siteId())||!g.customer().equals(command.operation().customerId())||g.binding().subscriptionReference()!=null||command.providerSubscriptionReference()!=null||!g.binding().operationKey().equals(command.operation().providerOperationKey()))throw StripeBillingOwnership.mismatch();
   return new OwnedSetupReceipt(g.operation(),g.binding().stepId(),g.binding().setupReference());
  });
 }
 private NextAction setupAction(Lookup lookup){
  var proof=ownership.acknowledgedSetup(lookup);if(terminal(proof.operation()))return null;
  var old=tx.requiresNew(()->actions.current(lookup.account().id(),proof.operation().id()).orElse(null));if(old!=null&&old.consumedAt()!=null)return null;
  var graph=readSetup(proof);if(graph==null)return null;
  return tx.requiresNew(()->{
   operations.lock(proof.operation().id());var fresh=ownership.acknowledgedSetup(lookup);if(!proof.equals(fresh))throw StripeBillingOwnership.mismatch();
   var now=securityClock.instant().truncatedTo(ChronoUnit.MICROS);var current=actions.current(lookup.account().id(),proof.operation().id()).orElse(null);
   if(current!=null){if(current.consumedAt()!=null)return null;if(!current.binding().equals(graph.binding()))throw StripeBillingOwnership.mismatch();if(current.available(now))return action(current);if(now.isBefore(current.issuedAt()))throw StripeBillingOwnership.mismatch();}
   var s=fresh.subscription();var g=new SubscriptionClientGrant(UUID.randomUUID(),s.siteId(),s.customerId(),s.paymentAccountId(),s.id(),fresh.operation().id(),tokens.activeVersion(),graph.binding(),null,now,now.plusSeconds(600),null,null,null,null,null);
   String token=tokens.token(g.keyVersion(),g.descriptor());g=g.digest(StripeBillingGrantTokens.hash(token));actions.reserveBilling(g);return new NextAction(ActionKind.PROVIDER_CLIENT,token,g.expiresAt());
  });
 }
 private SubscriptionClientSession setupSession(Lookup lookup,SubscriptionClientGrant g,String token){
  var proof=ownership.acknowledgedSetup(lookup);
  if(terminal(proof.operation())||!g.operation().equals(proof.operation().id())||!g.account().equals(lookup.account().id())||!g.subscription().equals(lookup.subscriptionId())||!java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),tokens.token(g.keyVersion(),g.descriptor()).getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw StripeBillingOwnership.mismatch();
  var graph=readSetup(proof);if(graph==null||!proof.equals(ownership.acknowledgedSetup(lookup))||!graph.binding().equals(g.binding())||!g.available(securityClock.instant()))throw StripeBillingOwnership.mismatch();
  return new SubscriptionClientSession("stripe",SubscriptionClientSession.Flow.SETUP,credentials.resolve(lookup.account()).publishableKey(),graph.clientSecret(),token,g.expiresAt());
 }
 private StripeBillingSnapshotMapper.PaymentAction readSetup(StripeBillingOwnership.OriginalSetup p){
  verification.verify(p.account());
  try{
   var remote=clients.create(credentials.resolve(p.account())).v1().setupIntents().retrieve(p.proof().reference());StripeSubscriptionProviderAdapter.requireSetupRemote(remote,p.subscription(),p.operation(),p.customer(),p.step());
   if(!Set.of("requires_payment_method","requires_action").contains(remote.getStatus()))return null;
   String secret=remote.getClientSecret();if(secret==null||!secret.startsWith(remote.getId()+"_secret_")||secret.length()>1024||!secret.matches("[A-Za-z0-9_-]+"))throw StripeBillingOwnership.mismatch();
   var b=new SubscriptionClientGrant.Binding(p.step().id(),p.operation().providerOperationKey(),StripeBillingGrantTokens.hash(SubscriptionClientGrant.canonical(p.step().fingerprint(),p.operation().fingerprint(),remote.getId())),p.subscription().providerReference(),p.customer().providerReference(),null,null,null,"setup-v1",remote.getId());
   return new StripeBillingSnapshotMapper.PaymentAction(b,secret);
  }catch(com.stripe.exception.StripeException invalid){throw StripeBillingOwnership.mismatch();}
 }
 private StripeBillingSnapshotMapper.PaymentAction read(StripeBillingOwnership.PaymentProof proof){verification.verify(proof.account());return StripeBillingSnapshotMapper.retrievePaymentAction(clients.create(credentials.resolve(proof.account())),proof,securityClock.instant());}
 private NextAction action(SubscriptionClientGrant grant){String token=tokens.token(grant.keyVersion(),grant.descriptor());if(!StripeBillingGrantTokens.matches(token,grant.tokenDigest()))throw StripeBillingOwnership.mismatch();return new NextAction(ActionKind.PROVIDER_CLIENT,token,grant.expiresAt());}
 private static boolean terminal(SubscriptionOperation o){return o.status()==SubscriptionOperation.Status.SUCCEEDED||o.status()==SubscriptionOperation.Status.FAILED;}
 private static void same(StripeBillingOwnership.PaymentProof a,StripeBillingOwnership.PaymentProof b){if(!a.equals(b))throw StripeBillingOwnership.mismatch();}
 private void outside(){if(tx.active())throw new IllegalStateException("Provider workflow requires no ambient transaction");}
}
