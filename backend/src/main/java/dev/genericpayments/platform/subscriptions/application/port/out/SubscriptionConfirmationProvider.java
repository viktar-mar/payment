package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Lookup;
public interface SubscriptionConfirmationProvider {
 record Material(String publishableKey,String clientSecret){@Override public String toString(){return "ConfirmationMaterial[redacted]";}}
 record Outcome(SubscriptionConfirmationAttempt.State state,SubscriptionConfirmationAttempt.Receipt receipt) {}
 String capability(SubscriptionClientGrant root,SubscriptionConfirmationChain chain,SubscriptionConfirmationAttempt attempt);
 Material read(Lookup lookup,SubscriptionClientGrant root,SubscriptionConfirmationAttempt.Receipt receipt);
 Outcome confirm(Lookup lookup,SubscriptionClientGrant root,SubscriptionConfirmationAttempt attempt,String token);
}
