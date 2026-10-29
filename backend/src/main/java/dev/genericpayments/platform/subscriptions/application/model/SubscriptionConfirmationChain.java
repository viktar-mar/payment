package dev.genericpayments.platform.subscriptions.application.model;
import java.time.Instant;
import java.util.UUID;
/** Original grant preserves all provider/consent identity; this descriptor adds immutable v2 authority. */
public record SubscriptionConfirmationChain(UUID id,String rootDigest,UUID sessionId,String origin,Instant expiresAt,long policyVersion,long subscriptionRevision) {}
