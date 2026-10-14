package dev.genericpayments.platform.provider.adapter.out.fake;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.service.ProviderTestFixture;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class FakeProviderReceiptConstraintsTest extends ProviderTestFixture {
    @Test void databaseRejectsIncompleteOrUnsafeReceiptsAndMismatchedIdentity() {
        var p=save.save(payment());fake.preparePayment(request(p));
        String insert="insert into fake_provider_operations(operation_key,fingerprint,provider_reference,payment_account_id,object_type,internal_object_id,result_kind,result_reference,result_code,action_kind,action_payload,created_at) select ?, ";
        String from=" from fake_provider_objects";
        String identity="provider_reference,payment_account_id,object_type,internal_object_id,";
        for(String fields:java.util.List.of(
            "'"+"A".repeat(64)+"',"+identity+"'ACCEPTED',provider_reference,'ACCEPTED',null,null,created_at",
            "request_fingerprint,"+identity+"'ACCEPTED',null,'ACCEPTED',null,null,created_at",
            "request_fingerprint,"+identity+"'REQUIRES_ACTION',provider_reference,'ACTION_REQUIRED',null,'verify',created_at",
            "request_fingerprint,"+identity+"'REQUIRES_ACTION',provider_reference,'ACTION_REQUIRED','COMPLETE_VERIFICATION','unsafe payload',created_at",
            "request_fingerprint,"+identity+"'ACCEPTED',provider_reference,'SECRET',null,null,created_at",
            "request_fingerprint,provider_reference,payment_account_id,'REFUND',internal_object_id,'ACCEPTED',provider_reference,'ACCEPTED',null,null,created_at")) {
            assertThatThrownBy(() -> jdbc.update(insert+fields+from,"confirm_"+UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
    @Test void originalReceiptCannotBeUpdatedOrDeleted() {
        var p=save.save(payment());fake.preparePayment(request(p));
        assertThatThrownBy(() -> jdbc.update("update fake_provider_operations set result_kind='UNKNOWN',result_code='UNKNOWN'")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from fake_provider_operations")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("fake_provider_operations")).isEqualTo(1);
    }
}
