package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.payments.application.model.ProviderEventEnvelope;
import dev.genericpayments.platform.payments.application.model.ProviderReferenceReceipt;
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
    boolean insertReference(ProviderReferenceReceipt receipt);
    /** Acquire one reference just before IO; adapter supplies real UTC and a fresh fencing owner. */
    Optional<Claim> claimReference(Duration lease);
    /** Short independent transaction; returns no receipt for a lost lease, releases its lock before IO. */
    Optional<ProviderReferenceReceipt> reference(Claim claim);
    /** Requires the financial apply transaction; checks the live claim after locking its row. */
    ProviderReferenceReceipt lockReference(Claim claim);
    /** Same apply transaction, revalidating the live claim so an expired apply rolls back. */
    void completeReference(Claim claim);
    /** Independent transaction; lost/expired claims do not mutate, attempt twelve ends DEAD. */
    boolean failReference(Claim claim,String category);
}
