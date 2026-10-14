package dev.genericpayments.platform.webhooks.application.port.out;

/** Resolve and normalize the target before entering a durable mutation transaction. */
@FunctionalInterface
public interface CallbackTargetPolicyPort {
    String normalize(String endpoint);
}
