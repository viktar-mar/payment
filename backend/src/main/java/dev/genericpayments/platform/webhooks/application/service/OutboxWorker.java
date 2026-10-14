package dev.genericpayments.platform.webhooks.application.service;
import dev.genericpayments.platform.webhooks.application.port.out.OutboxPort;
import dev.genericpayments.platform.shared.application.port.out.PendingOutboxTelemetryPort;
import java.time.*;
import java.util.UUID;
public final class OutboxWorker {
    private final OutboxPort outbox;private final CallbackDeliveryService delivery;private final Clock clock;private final int batch;private final Duration lease;private final PendingOutboxTelemetryPort pending;
    public OutboxWorker(OutboxPort outbox,CallbackDeliveryService delivery,Clock clock,int batch,Duration lease,PendingOutboxTelemetryPort pending){this.outbox=outbox;this.delivery=delivery;this.clock=clock;this.batch=batch;this.lease=lease;this.pending=pending;}
    public void runOnce(){delivery.requireOutsideTransaction();pending.refreshPending();String owner=UUID.randomUUID().toString();for(var c:outbox.claimAutomatic(owner,batch,clock.instant(),lease))delivery.deliver(c);for(var c:outbox.claimManual(owner,batch,clock.instant(),lease))delivery.deliver(c);pending.refreshPending();}
}
