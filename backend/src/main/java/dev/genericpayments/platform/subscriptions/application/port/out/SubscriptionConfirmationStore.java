package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.subscriptions.application.model.*;
import java.util.*;
public interface SubscriptionConfirmationStore {
 Optional<SubscriptionConfirmationChain> chain(UUID root);
 Optional<SubscriptionConfirmationChain> current(UUID operation);
 SubscriptionConfirmationAttempt attempt(UUID id);
 SubscriptionConfirmationAttempt latest(UUID chain);
 void reserve(SubscriptionClientGrant root,SubscriptionConfirmationChain chain,SubscriptionConfirmationAttempt initial);
 void insert(SubscriptionConfirmationAttempt attempt);
 void save(SubscriptionConfirmationAttempt attempt,long previousVersion,java.time.Instant at);
}
