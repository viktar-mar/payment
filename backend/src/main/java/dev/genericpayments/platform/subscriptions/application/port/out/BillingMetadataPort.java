package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.subscriptions.domain.BillingRecord;
import java.time.Instant;
public interface BillingMetadataPort {
    record Captured(long policyVersion,Instant graceDeadline) {}
    /** Owned record, ambient transaction. A captured null deadline is immutable too. */
    Captured captureOnce(BillingRecord bill,long policyVersion,Instant graceDeadline);
}
