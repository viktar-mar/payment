package dev.genericpayments.platform.subscriptions.application.model;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import java.time.Instant;
import java.util.UUID;
/** Safe persisted capability descriptor. Contains neither grant nor provider client secret. */
public record SubscriptionClientGrant(UUID id,SiteId site,CustomerId customer,PaymentAccountId account,UUID subscription,UUID operation,
 String keyVersion,Binding binding,String tokenDigest,Instant issuedAt,Instant expiresAt,Instant consumedAt,Instant supersededAt,
 UUID sessionId,String origin,Instant clientExpiresAt) {
 public record Binding(UUID stepId,String operationKey,String proof,String subscriptionReference,String customerReference,String invoiceReference,String allocationReference,String intentReference,String schema,String setupReference){
  public Binding(UUID step,String key,String proof,String sub,String customer,String invoice,String allocation,String intent){this(step,key,proof,sub,customer,invoice,allocation,intent,"payment-v1",null);}
  public String stepKind(){return switch(schema){case "setup-v1"->"SETUP_INTENT_CREATE";case "resume-v1","change-v1"->"SUBSCRIPTION_UPDATE";case "payment-v1","recovery-v1"->"SUBSCRIPTION_CREATE";default->throw new IllegalArgumentException("Invalid billing descriptor");};}
  public String stepReference(){return schema.equals("setup-v1")?setupReference:subscriptionReference;}
  public SubscriptionClientSession.Flow flow(){return schema.equals("setup-v1")?SubscriptionClientSession.Flow.SETUP:SubscriptionClientSession.Flow.PAYMENT;}
 }
 public String descriptor(){
  String legacy=canonical(id,site.value(),customer.value(),account.value(),subscription,operation,keyVersion,binding.stepId(),binding.operationKey(),binding.proof(),binding.subscriptionReference(),binding.customerReference(),binding.invoiceReference(),binding.allocationReference(),binding.intentReference(),issuedAt,expiresAt);
  return binding.schema().equals("payment-v1")?legacy:canonical("gp:stripe-billing-descriptor:"+binding.schema(),legacy,binding.setupReference());
 }
 public static String canonical(Object... fields){var b=new StringBuilder();for(Object field:fields){String s=field==null?null:field.toString();b.append(s==null?-1:s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).append(':');if(s!=null)b.append(s);}return b.toString();}
 public boolean available(Instant now){return consumedAt==null&&supersededAt==null&&!now.isBefore(issuedAt)&&now.isBefore(expiresAt);}
 public SubscriptionClientGrant digest(String hash){return new SubscriptionClientGrant(id,site,customer,account,subscription,operation,keyVersion,binding,hash,issuedAt,expiresAt,consumedAt,supersededAt,sessionId,origin,clientExpiresAt);}
 @Override public String toString(){return "SubscriptionClientGrant[id="+id+", redacted]";}
}
