package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord;
import dev.genericpayments.platform.payments.application.port.out.IdempotencyLookupPort;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class IdempotencyReceiptQuery {
    private final IdempotencyLookupPort records;
    public IdempotencyReceiptQuery(IdempotencyLookupPort records) { this.records=records; }
    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<IdempotencyRecord> find(IdempotencyRecord.Scope scope,String key) {
        IdempotencyRecord.validateText(key,255);return records.find(scope.value(),key);
    }
}
