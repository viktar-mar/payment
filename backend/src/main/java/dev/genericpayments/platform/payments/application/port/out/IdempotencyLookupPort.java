package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord;
import java.util.Optional;
public interface IdempotencyLookupPort {
    /** Caller must hold the business serialization lock; this does not claim a new operation. */
    Optional<IdempotencyRecord> find(String scope,String key);
}
