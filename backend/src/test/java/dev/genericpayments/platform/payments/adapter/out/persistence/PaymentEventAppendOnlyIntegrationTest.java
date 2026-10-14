package dev.genericpayments.platform.payments.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class PaymentEventAppendOnlyIntegrationTest extends PaymentDatabaseFixture {
    @Test void ordinaryUpdatesAndDeletesFailButExplicitTestTruncateWorks() {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into payment_events(id,payment_id,site_id,payment_account_id,sequence_number,event_type,source,occurred_at,received_at,request_id,payload_hash) values (?,?,?,?,1,'PAYMENT_CREATED','API',now(),now(),'request',?)",id,payment,site,account,"a".repeat(64));
        assertThatThrownBy(() -> jdbc.update("update payment_events set event_type='PAYMENT_FAILED' where id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from payment_events where id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.execute("truncate table payment_events cascade");
        assertThat(jdbc.queryForObject("select count(*) from payment_events",Long.class)).isZero();
    }
}
