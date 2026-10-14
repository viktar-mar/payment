package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.support.PostgresIntegrationTest;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.provider.adapter.out.fake.*;
import dev.genericpayments.platform.sites.domain.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

public abstract class ProviderTestFixture extends PostgresIntegrationTest {
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected SavePaymentPort save;
    @Autowired protected LoadPaymentPort load;
    @Autowired protected LoadPaymentAttemptPort attempts;
    @Autowired protected LoadPaymentEventPort events;
    @Autowired protected FakePaymentProviderAdapter fake;
    @Autowired protected FakeProviderStatePersistenceAdapter state;
    @Autowired protected PaymentCreationTransaction creation;
    @Autowired protected PaymentAttemptTransaction attemptPhase;
    protected final SiteId site = new SiteId(UUID.fromString("10000000-0000-0000-0000-000000000001"));
    protected final PaymentAccount account = PaymentAccount.create(new PaymentAccountId(UUID.fromString("20000000-0000-0000-0000-000000000001")),"test-account",PaymentProviderType.FAKE,PaymentAccountMode.SANDBOX,"fake-account","config-ref");
    @BeforeEach void setup() {
        clearDatabase();
        jdbc.update("insert into payment_accounts values (?, 'test-account', 'FAKE', 'SANDBOX', 'ACTIVE', 'fake-account', 'config-ref', ?, 0)",account.id().value(),java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into sites(id,site_key,display_name,status,payment_account_id,payment_method_policy,created_at,updated_at,version) values (?,'test-site','Test','ACTIVE',?,'AUTOMATIC',?,?,0)",site.value(),account.id().value(),java.sql.Timestamp.from(INITIAL_TIME),java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,'https://shop.test')",site.value());
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'SITE_TOKEN')",site.value());
    }
    protected Payment payment() { return Payment.dynamic(new PaymentId(UUID.randomUUID()),site,account.id(),"order-"+UUID.randomUUID(),Money.of(2000,"EUR"),TaxMode.NONE,Map.of("channel","test"),INITIAL_TIME); }
    protected PreparePaymentCommand prepare(Payment p) { return new PreparePaymentCommand(p,account,"test-site","request-1","create-1"); }
    protected ConfirmPaymentCommand confirm(Payment p) { return new ConfirmPaymentCommand(site,p.id(),account,new PaymentAttemptId(UUID.randomUUID()),PaymentMethod.CARD,"test-site","request-2","confirm-1"); }
    protected ProviderPaymentRequest request(Payment p) { return new ProviderPaymentRequest(account,p,ProviderOperationKeys.prepare(p.id())); }
    protected int count(String table) { return jdbc.queryForObject("select count(*) from "+table,Integer.class); }
    protected void rejectJournal(PaymentEventType type) {
        jdbc.execute("create or replace function task4_reject_journal() returns trigger language plpgsql as $$ begin if new.event_type='"+type.name()+"' then raise exception 'injected journal failure' using errcode='23514'; end if; return new; end; $$");
        jdbc.execute("create trigger task4_reject_journal before insert on payment_events for each row execute function task4_reject_journal()");
    }
    @AfterEach void removeJournalFailure() {
        jdbc.execute("drop trigger if exists task4_reject_journal on payment_events");
        jdbc.execute("drop function if exists task4_reject_journal()");
    }
}
