package dev.genericpayments.platform.subscriptions.application.model;
import java.time.Instant;
public record SubscriptionClientSession(String provider,Flow flow,String publishableKey,String clientSecret,String actionToken,Instant expiresAt) {
 public enum Flow{PAYMENT,SETUP}
 public SubscriptionClientSession{if(!"stripe".equals(provider)||flow==null||publishableKey==null||clientSecret==null||actionToken==null||expiresAt==null)throw new IllegalArgumentException("Invalid client session");}
 @Override public String toString(){return "SubscriptionClientSession[redacted]";}
}
