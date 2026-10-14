package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.shared.application.port.out.*;
public final class ProviderInboxService {
    private final ProviderInboxPort inbox;private final PaymentTransitionPort transitions;private final DigestPort digest;private final Stage2TelemetryPort telemetry;
    public ProviderInboxService(ProviderInboxPort inbox,PaymentTransitionPort transitions,DigestPort digest,Stage2TelemetryPort telemetry){this.inbox=inbox;this.transitions=transitions;this.digest=digest;this.telemetry=telemetry;}
    public boolean receive(ProviderEventEnvelope envelope) {
        if(envelope==null)throw SafeFactJson.invalid();String json=envelope.canonicalJson();
        transitions.requireProvider(envelope.accountId(),envelope.provider());
        var resolved=transitions.resolveEvent(envelope);resolved.ifPresent(i -> telemetry.requireContext(i.context()));
        // Outcome is chosen by the insert adapter under the uniqueness constraint.
        return inbox.insert(envelope,json,digest.sha256Hex(json),inserted -> resolved.ifPresent(i -> telemetry.inbox(i.context(),inserted?Stage2TelemetryPort.InboxOutcome.RECEIVED:Stage2TelemetryPort.InboxOutcome.DUPLICATE)));
    }
}
