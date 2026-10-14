package dev.genericpayments.platform.partners;

import dev.genericpayments.platform.payments.application.service.CheckoutTestFixture;
import dev.genericpayments.platform.partners.application.service.SiteApiKeyService;
import dev.genericpayments.platform.partners.domain.PartnerScope;
import dev.genericpayments.platform.payments.application.port.in.CreateDynamicPaymentCommand;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties = {"payment.telemetry.site-keys[0]=test-site", "payment.telemetry.site-keys[1]=other-site",
    "payment.admin.bootstrap.username=admin", "payment.admin.bootstrap.password=local-password"})
public abstract class PartnerTestFixture extends CheckoutTestFixture {
    @Autowired protected SiteApiKeyService keys;
    protected String key(PartnerScope... scopes) { return keys.create(site, Set.of(scopes), null).apiKey(); }
    protected CreateDynamicPaymentCommand dynamic(String order, String idem) {
        return new CreateDynamicPaymentCommand(site, order, 2300, "EUR", URI.create("https://shop.test"),
            "https://shop.test/done?order=1", Map.of("channel", "partner"), idem, "request-6");
    }
    protected void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        var error = catchThrowable(call);
        assertThat(error).isInstanceOf(PaymentApplicationException.class);
        assertThat(((PaymentApplicationException) error).code()).isEqualTo(code);
    }
    protected void rejectWrite(String table, String operation, String predicate) {
        jdbc.execute("create or replace function task6_reject_write() returns trigger language plpgsql as $$ begin if " + predicate + " then raise exception 'injected failure' using errcode='23514'; end if; return new; end; $$");
        jdbc.execute("create trigger task6_reject_write before " + operation + " on " + table + " for each row execute function task6_reject_write()");
    }
    protected void removeJournalFailure() {
        jdbc.execute("drop trigger if exists task4_reject_journal on payment_events");
        jdbc.execute("drop function if exists task4_reject_journal()");
    }
    @org.junit.jupiter.api.AfterEach protected void clearWriteFailure() {
        for (var table : List.of("site_api_keys", "audit_log", "checkout_sessions", "idempotency_records")) jdbc.execute("drop trigger if exists task6_reject_write on " + table);
        jdbc.execute("drop function if exists task6_reject_write()");
    }
}
