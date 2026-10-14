package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.payments.application.model.HandoffSession;
import java.time.Instant;
import java.util.*;
public interface HandoffSessionPort {
    void save(HandoffSession session);
    Optional<HandoffSession> findById(UUID id);
    Optional<HandoffSession> lockByHash(String hash);
    void consume(UUID id,Instant now);
}
