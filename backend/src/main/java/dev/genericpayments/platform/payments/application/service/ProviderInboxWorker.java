package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import java.time.*;
import java.util.*;
public final class ProviderInboxWorker {
    private final ProviderInboxPort inbox;private final PaymentTransitionService transitions;private final PaymentTransitionPort tx;private final Stage2TelemetryPort telemetry;private final Clock clock;private final int batch;private final Duration lease;
    public ProviderInboxWorker(ProviderInboxPort inbox,PaymentTransitionService transitions,PaymentTransitionPort tx,Stage2TelemetryPort telemetry,Clock clock,int batch,Duration lease){this.inbox=inbox;this.transitions=transitions;this.tx=tx;this.telemetry=telemetry;this.clock=clock;this.batch=batch;this.lease=lease;}
    public void runOnce() {
        for(var claim:inbox.claim(UUID.randomUUID().toString(),batch,clock.instant(),lease)) {
            PaymentTransitionService.Result result;
            try { result=transitions.process(claim); }
            catch(RuntimeException failure) {
                String message=Objects.toString(failure.getMessage(),"");String category=Set.of("UNSUPPORTED_EVENT","UNRESOLVED_OBJECT","IDENTITY_MISMATCH").contains(message)?message:"PROCESSING_FAILED";
                if(inbox.fail(claim,category,clock.instant())) {
                    // A failed unresolved/ambiguous object cannot safely label a Site.
                    try { inbox.find(claim.id()).flatMap(f -> tx.resolveEvent(f.envelope())).ifPresent(i -> {telemetry.requireContext(i.context());telemetry.inbox(i.context(),Stage2TelemetryPort.InboxOutcome.FAILED);}); }
                    catch(RuntimeException ignored) { }
                }
                continue;
            }
            transitions.emit(result);telemetry.inbox(result.context(),Stage2TelemetryPort.InboxOutcome.PROCESSED);
            Duration lag=Duration.between(result.occurredAt(),clock.instant());telemetry.inboxLag(result.context(),lag.isNegative()?Duration.ZERO:lag);
        }
    }
}
