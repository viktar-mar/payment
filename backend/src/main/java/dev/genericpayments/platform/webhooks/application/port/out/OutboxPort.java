package dev.genericpayments.platform.webhooks.application.port.out;
import dev.genericpayments.platform.shared.application.port.out.*;
import java.time.*;
import java.util.*;
public interface OutboxPort {
    enum Mode { AUTOMATIC, SNAPSHOT_RETRY, CURRENT_CONFIG_RETRY }
    record Claim(UUID eventId,UUID requestId,UUID siteId,String endpoint,int secretVersion,EncryptedSecretPort.EncryptedValue secret,String payload,int attempt,String owner,Instant startedAt,Instant createdAt,Mode mode,Stage2TelemetryPort.Context context) {
        @Override public String toString(){return "Claim[eventId="+eventId+", attempt="+attempt+", mode="+mode+", secret=redacted]";}
    }
    record Retry(UUID id,UUID eventId,UUID siteId,Mode mode,String status) {}
    List<Claim> claimAutomatic(String owner,int batch,Instant now,Duration lease);
    List<Claim> claimManual(String owner,int batch,Instant now,Duration lease);
    boolean complete(Claim claim,CallbackHttpPort.Result result,Instant now);
    UUID siteFor(UUID event);
    Retry enqueue(UUID event,Mode mode,Instant now);
    Retry retry(UUID request);
    long pending(UUID site);
    boolean ambientTransaction();
}
