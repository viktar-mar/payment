package dev.genericpayments.platform.provider.adapter.out.persistence;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

/** Explicit socket-free test: no DataSource, context or provider calls. */
class OwnedFailedRenewalMethodStoreTransactionTest {
    @Test void everyStoreEntryRequiresTheCallerTransactionBeforeAnySql() {
        var s=new JdbcOwnedFailedRenewalMethodStore(new JdbcTemplate());
        List<Runnable> calls=List.of(
            ()->s.reserveInTransaction(null,null,null,null,null,null),()->s.requireReservationInTransaction(null),
            ()->s.requirePreparedMethodInTransaction(null),
            ()->s.claimReadInTransaction(null,null,null,null,null,null),()->s.requireReadInTransaction(null,null),
            ()->s.requireMethodContextInTransaction(null,null),()->s.recordCompletionInTransaction(null,null,null,null),
            ()->s.requireCompletionInTransaction(null,null),()->s.associateRecoveryInTransaction(null,null,null,null),
            ()->s.requireRecoveryInTransaction(null,null),()->s.requireRecoveredContextInTransaction(null,null),
            ()->s.requireBrowserRecoveryEnrollmentInTransaction(null,null,null),
            ()->s.requireBrowserRecoveryIdentityInTransaction(null,null),
            ()->s.recordPublicationInTransaction(null,null,null),()->s.publicationInTransaction(null),
            ()->s.requireMethodFactInTransaction(null,null,null,null,null),()->s.requireRecoveryFactInTransaction(null,null,null,null,null));
        for(var call:calls)assertThatThrownBy(call::run).isInstanceOf(IllegalStateException.class)
            .hasMessage("Owned method transition requires caller transaction");
    }

}
