package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import java.time.*;
import java.util.Set;

/** One just-in-time claim per pass; never preclaims a batch or creates client attempts. */
public final class ProviderReferenceInboxWorker {
    private final ProviderInboxPort inbox;
    private final ProviderInboxResolutionPort resolution;
    private final PaymentTransitionService transitions;
    private final Stage2TelemetryPort telemetry;
    private final Clock clock;
    public ProviderReferenceInboxWorker(ProviderInboxPort inbox,ProviderInboxResolutionPort resolution,
            PaymentTransitionService transitions,Stage2TelemetryPort telemetry,Clock clock) {
        this.inbox=inbox;this.resolution=resolution;this.transitions=transitions;this.telemetry=telemetry;this.clock=clock;
    }
    public void runOnce() {
        transitions.requireNoAmbientTransaction();
        // Four logical SDK reads, each with a 20s complete-response deadline (including transparent
        // JDK retries), zero SDK retries, and 40s headroom. Persistence rechecks real UTC after locks.
        var claimed=inbox.claimReference(Duration.ofSeconds(120));
        if(claimed.isEmpty())return;
        var claim=claimed.get();PaymentTransitionService.Result result;
        try {
            var receipt=inbox.reference(claim);
            if(receipt.isEmpty())return;
            result=transitions.processVerified(claim,resolution.resolve(receipt.get()));
        } catch(RuntimeException failure) {
            String category="PROCESSING_FAILED";
            if(Set.of("UNRESOLVED_OBJECT","IDENTITY_MISMATCH","UNSUPPORTED_EVENT").contains(String.valueOf(failure.getMessage())))category=failure.getMessage();
            if(failure instanceof PaymentApplicationException safe)category=switch(safe.code()) {
                case "PROVIDER_EVIDENCE_MISMATCH","PROVIDER_OPERATION_CONFLICT" -> "IDENTITY_MISMATCH";
                case "PAYMENT_CAPABILITY_UNAVAILABLE" -> "UNRESOLVED_OBJECT";
                default -> "PROCESSING_FAILED";
            };
            inbox.failReference(claim,category);return;
        }
        transitions.emit(result);telemetry.inbox(result.context(),Stage2TelemetryPort.InboxOutcome.PROCESSED);
        var lag=Duration.between(result.occurredAt(),clock.instant());telemetry.inboxLag(result.context(),lag.isNegative()?Duration.ZERO:lag);
    }
}
