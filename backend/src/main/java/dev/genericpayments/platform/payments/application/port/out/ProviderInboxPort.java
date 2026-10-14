package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.payments.application.model.ProviderEventEnvelope;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
public interface ProviderInboxPort {
    record Claim(UUID id,String owner) {}
    record Fact(UUID id,ProviderEventEnvelope envelope,String payloadHash) {}
    boolean insert(ProviderEventEnvelope envelope,String canonicalJson,String payloadHash,Consumer<Boolean> afterCommit);
    Optional<Fact> find(UUID id);
    List<Claim> claim(String owner,int batch,Instant now,Duration lease);
    Fact lock(Claim claim,Instant now);
    void complete(Claim claim);
    boolean fail(Claim claim,String category,Instant now);
}
