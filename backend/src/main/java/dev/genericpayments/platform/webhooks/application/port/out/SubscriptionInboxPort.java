package dev.genericpayments.platform.webhooks.application.port.out;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import java.util.*;
import java.time.*;
public interface SubscriptionInboxPort {
    record Claim(UUID id,String owner) {}
    boolean insert(SubscriptionProviderEnvelope envelope);
    List<Claim> claim(String owner,int batch,Instant now,Duration lease);
    SubscriptionProviderEnvelope lock(Claim claim,Instant now);
    void complete(Claim claim);
    void fail(Claim claim,String category,Instant now);
}
