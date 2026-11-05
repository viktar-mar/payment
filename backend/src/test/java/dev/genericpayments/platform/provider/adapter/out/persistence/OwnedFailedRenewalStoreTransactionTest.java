package dev.genericpayments.platform.provider.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** No DataSource, Spring context, SQL, mock engine, provider or runtime bootstrap. */
class OwnedFailedRenewalStoreTransactionTest {
    @Test void everyPublicOperationRequiresTheCallerTransactionBeforeReadingOrWriting() {
        var store=new JdbcOwnedFailedRenewalStore(new JdbcTemplate());
        List<Runnable> entrypoints=List.of(
            ()->store.admitInTransaction(null,null,null,null),
            ()->store.requireCurrentInTransaction(null),
            ()->store.claimReadInTransaction(null,null,null,null,null),
            ()->store.requireReadInTransaction(null,null),
            ()->store.recordFailureInTransaction(null,null,null),
            ()->store.requireFailureInTransaction(null),
            ()->store.associateInTransaction(null,null,null,null,null),
            ()->store.requireRecoveryInTransaction(null),
            ()->store.recordPublicationInTransaction(null,null,null),
            ()->store.publicationInTransaction(null),
            ()->store.stopInTransaction(null,null,null),
            ()->store.requireRecoveryFactInTransaction(null,null,null,null,null));
        for(var operation:entrypoints)assertThatThrownBy(operation::run)
            .isInstanceOf(IllegalStateException.class).hasMessage("Owned failed renewal requires caller transaction");
    }
}
