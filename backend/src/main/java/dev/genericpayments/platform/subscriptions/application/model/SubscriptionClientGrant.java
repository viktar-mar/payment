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
 public record Binding(UUID stepId,String operationKey,String proof,String subscriptionReference,String customerReference,String invoiceReference,String allocationReference,String intentReference){}
 public String descriptor(){return canonical(id,site.value(),customer.value(),account.value(),subscription,operation,keyVersion,binding.stepId(),binding.operationKey(),binding.proof(),binding.subscriptionReference(),binding.customerReference(),binding.invoiceReference(),binding.allocationReference(),binding.intentReference(),issuedAt,expiresAt);}
 public static String canonical(Object... fields){var b=new StringBuilder();for(Object field:fields){String s=field==null?null:field.toString();b.append(s==null?-1:s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).append(':');if(s!=null)b.append(s);}return b.toString();}
 public boolean available(Instant now){return consumedAt==null&&supersededAt==null&&!now.isBefore(issuedAt)&&now.isBefore(expiresAt);}
 public SubscriptionClientGrant digest(String hash){return new SubscriptionClientGrant(id,site,customer,account,subscription,operation,keyVersion,binding,hash,issuedAt,expiresAt,consumedAt,supersededAt,sessionId,origin,clientExpiresAt);}
 @Override public String toString(){return "SubscriptionClientGrant[id="+id+", redacted]";}
}
