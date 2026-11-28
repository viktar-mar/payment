package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.subscriptions.application.model.*;
import java.time.Instant;
import java.util.UUID;
public interface SubscriptionConfirmationUseCase {
 record Scope(SubscriptionCommandContext context,UUID subscription,UUID operation) {}
 record Session(String protocol,String provider,SubscriptionClientSession.Flow flow,String publishableKey,String clientSecret,String actionToken,UUID attemptId,String capability,Instant expiresAt){@Override public String toString(){return "ConfirmationSession[redacted]";}}
 record Status(UUID attemptId,SubscriptionConfirmationAttempt.State state,boolean retryAllowed,UUID successorAttemptId,Instant expiresAt) {}
 record Confirm(Status status,boolean nextActionAllowed) {}
 Session select(Scope scope,String actionToken);
 Session session(Scope scope,UUID attempt);
 Status status(Scope scope,UUID attempt);
 Status current(Scope scope);
 Confirm confirm(Scope scope,UUID attempt,String capability,String confirmationToken);
 Status retry(Scope scope,UUID attempt);
}
