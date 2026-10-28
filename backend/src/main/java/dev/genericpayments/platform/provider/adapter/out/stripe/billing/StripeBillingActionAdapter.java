package dev.genericpayments.platform.provider.adapter.out.stripe.billing;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.time.*;import java.time.temporal.ChronoUnit;import java.util.*;
import org.springframework.transaction.annotation.*;
/** Only original paid Billing CREATE is implemented. No Stripe confirm/write is performed here. */
@Transactional(propagation=Propagation.NEVER)
public class StripeBillingActionAdapter implements SubscriptionClientSessionPort {
 private final StripeBillingOwnership ownership;private final StripeBillingGrantTokens tokens;private final StripeCredentialResolver credentials;
 private final StripeClientFactory clients;private final PaymentAccountVerificationPort verification;private final SubscriptionActionStore actions;
 private final SubscriptionOperationStore operations;private final SubscriptionTransactionPort tx;private final Clock securityClock;
 public StripeBillingActionAdapter(StripeBillingOwnership ownership,StripeBillingGrantTokens tokens,StripeCredentialResolver credentials,StripeClientFactory clients,
 PaymentAccountVerificationPort verification,SubscriptionActionStore actions,SubscriptionOperationStore operations,SubscriptionTransactionPort tx,Clock securityClock){
  this.ownership=ownership;this.tokens=tokens;this.credentials=credentials;this.clients=clients;this.verification=verification;this.actions=actions;this.operations=operations;this.tx=tx;this.securityClock=securityClock;
 }
 public NextAction nextAction(Lookup lookup){
  outside();if(!tokens.enabled()||lookup.providerOperationKey()==null)return null;
  var proof=ownership.acknowledgedCreate(lookup);if(terminal(proof.operation()))return null;
  var old=tx.requiresNew(()->actions.current(lookup.account().id(),proof.operation().id()).orElse(null));
  if(old!=null&&old.consumedAt()!=null)return null;
  var graph=read(proof);
  return tx.requiresNew(()->{
   operations.lock(proof.operation().id());var fresh=ownership.acknowledgedCreate(lookup);same(proof,fresh);
   var now=securityClock.instant().truncatedTo(ChronoUnit.MICROS);var current=actions.current(lookup.account().id(),proof.operation().id()).orElse(null);
   if(current!=null){if(current.consumedAt()!=null)return null;if(!current.binding().equals(graph.binding()))throw StripeBillingOwnership.mismatch();
    if(current.available(now))return action(current);if(now.isBefore(current.issuedAt()))throw StripeBillingOwnership.mismatch();}
   var s=fresh.subscription();var grant=new SubscriptionClientGrant(UUID.randomUUID(),s.siteId(),s.customerId(),s.paymentAccountId(),s.id(),fresh.operation().id(),tokens.activeVersion(),graph.binding(),null,now,now.plusSeconds(600),null,null,null,null,null);
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
  var proof=ownership.acknowledgedCreate(lookup);if(terminal(proof.operation())||!grant.operation().equals(proof.operation().id())||!grant.account().equals(lookup.account().id())||!grant.subscription().equals(lookup.subscriptionId()))throw StripeBillingOwnership.mismatch();
  if(!java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),tokens.token(grant.keyVersion(),grant.descriptor()).getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw StripeBillingOwnership.mismatch();
  var graph=read(proof);same(proof,ownership.acknowledgedCreate(lookup));if(!graph.binding().equals(grant.binding())||!grant.available(securityClock.instant()))throw StripeBillingOwnership.mismatch();
  return new SubscriptionClientSession("stripe",SubscriptionClientSession.Flow.PAYMENT,credentials.resolve(lookup.account()).publishableKey(),graph.clientSecret(),token,grant.expiresAt());
 }
 private StripeBillingSnapshotMapper.PaymentAction read(StripeBillingOwnership.OriginalCreate proof){verification.verify(proof.account());return StripeBillingSnapshotMapper.retrievePaymentAction(clients.create(credentials.resolve(proof.account())),proof,securityClock.instant());}
 private NextAction action(SubscriptionClientGrant grant){String token=tokens.token(grant.keyVersion(),grant.descriptor());if(!StripeBillingGrantTokens.matches(token,grant.tokenDigest()))throw StripeBillingOwnership.mismatch();return new NextAction(ActionKind.PROVIDER_CLIENT,token,grant.expiresAt());}
 private static boolean terminal(SubscriptionOperation o){return o.status()==SubscriptionOperation.Status.SUCCEEDED||o.status()==SubscriptionOperation.Status.FAILED;}
 private static void same(StripeBillingOwnership.OriginalCreate a,StripeBillingOwnership.OriginalCreate b){if(!a.operation().equals(b.operation())||!a.subscription().equals(b.subscription())||!a.account().equals(b.account())||!a.step().equals(b.step())||!a.customer().equals(b.customer())||!a.price().equals(b.price()))throw StripeBillingOwnership.mismatch();}
 private void outside(){if(tx.active())throw new IllegalStateException("Provider workflow requires no ambient transaction");}
}
