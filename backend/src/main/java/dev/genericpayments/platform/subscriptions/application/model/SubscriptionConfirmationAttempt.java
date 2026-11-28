package dev.genericpayments.platform.subscriptions.application.model;
import java.time.Instant;
import java.util.UUID;
/** Immutable attempt; raw capabilities, tokens, secrets and provider errors never belong here. */
public record SubscriptionConfirmationAttempt(UUID id,UUID chain,UUID predecessor,String keyVersion,Instant issuedAt,Instant expiresAt,
 State state,String confirmKey,String tokenDigest,String fingerprint,Receipt receipt,UUID successor,String retryKey,long version) {
 public enum State { READY, DISPATCHING, REQUIRES_ACTION, OBSERVE, DECLINED, UNKNOWN }
 public record Receipt(String requestId,String charge,String declineCode) {}
 public static SubscriptionConfirmationAttempt ready(UUID id,UUID chain,UUID predecessor,String keyVersion,Instant at,Instant expires){return new SubscriptionConfirmationAttempt(id,chain,predecessor,keyVersion,at,expires,State.READY,null,null,null,null,null,null,0);}
 public String providerKey(){return "billing-confirm-v2:"+id;}
 public SubscriptionConfirmationAttempt claim(String key,String token,String fingerprint){
  if(state!=State.READY)throw conflict();
  return new SubscriptionConfirmationAttempt(id,chain,predecessor,keyVersion,issuedAt,expiresAt,State.DISPATCHING,key,token,fingerprint,null,null,null,version+1);
 }
 public SubscriptionConfirmationAttempt finish(State outcome,Receipt receipt){
  if(state!=State.DISPATCHING||outcome==State.READY||outcome==State.DISPATCHING||(outcome==State.DECLINED)!=(receipt!=null))throw conflict();
  return new SubscriptionConfirmationAttempt(id,chain,predecessor,keyVersion,issuedAt,expiresAt,outcome,confirmKey,tokenDigest,fingerprint,receipt,null,null,version+1);
 }
 public SubscriptionConfirmationAttempt successor(UUID next,String key){
  if(state!=State.DECLINED||receipt==null||successor!=null||next==null)throw conflict();
  return new SubscriptionConfirmationAttempt(id,chain,predecessor,keyVersion,issuedAt,expiresAt,state,confirmKey,tokenDigest,fingerprint,receipt,next,key,version+1);
 }
 public boolean retryAllowed(Instant now){return state==State.DECLINED&&receipt!=null&&successor==null&&!now.isBefore(issuedAt)&&now.isBefore(expiresAt);}
 private static RuntimeException conflict(){return new dev.genericpayments.platform.shared.application.PaymentApplicationException("SUBSCRIPTION_CONFLICT",409,"Subscription conflict");}
 @Override public String toString(){return "SubscriptionConfirmationAttempt[id="+id+", state="+state+"]";}
}
