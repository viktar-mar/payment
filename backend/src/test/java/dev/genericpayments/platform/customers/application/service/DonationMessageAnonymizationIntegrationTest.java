package dev.genericpayments.platform.customers.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.donations.application.service.DonationFinancialTestFixture;
import dev.genericpayments.platform.customers.application.port.in.AnonymizeCustomerUseCase;
import dev.genericpayments.platform.customers.application.port.out.CustomerDataRedactionPort;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.payments.domain.Payment;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;

class DonationMessageAnonymizationIntegrationTest extends DonationFinancialTestFixture {
    @Autowired AnonymizeCustomerUseCase anonymizer;
    @Autowired CustomerDataRedactionPort redaction;
    @Autowired LockSitePort locks;
    @Autowired PlatformTransactionManager manager;
    @Autowired dev.genericpayments.platform.donations.application.service.DonationCheckoutTransaction creationTransaction;
    private AnonymizeCustomerUseCase.Command command(Customer c,String key) {
        return new AnonymizeCustomerUseCase.Command(site,c.id(),c.id().value(),key,"ADMIN","safe-actor","task5-erase","127.0.0.1");
    }
    @Test void erasesOnlyLinkedPresentMessagesAndRepeatPreservesFinancialHistory() {
        var c=customer();var own=donation(c,MESSAGE);var second=donation(c,"another private message");
        var absent=donation(c,null);var already=donation(c,"already erased");
        donations.save(donations.findById(site,already.donationId()).orElseThrow().redactMessage(INITIAL_TIME));
        var anonymous=donation(null,MESSAGE);var other=donation(customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME)),MESSAGE);
        var before=jdbc.queryForList("select row_to_json(p)::text from payments p");
        var eventsBefore=jdbc.queryForList("select row_to_json(e)::text from payment_events e");
        var unchanged=jdbc.queryForList("select row_to_json(d)::text from donations d where id in (?,?,?,?) order by id",absent.donationId().value(),already.donationId().value(),anonymous.donationId().value(),other.donationId().value());
        anonymizer.anonymize(command(c,"erase"));
        erased(own);erased(second);
        assertThat(jdbc.queryForList("select row_to_json(d)::text from donations d where id in (?,?,?,?) order by id",absent.donationId().value(),already.donationId().value(),anonymous.donationId().value(),other.donationId().value())).isEqualTo(unchanged);
        assertThat(jdbc.queryForList("select row_to_json(p)::text from payments p")).isEqualTo(before);
        assertThat(jdbc.queryForList("select row_to_json(e)::text from payment_events e")).isEqualTo(eventsBefore);
        assertThat(customers.findById(site,c.id()).orElseThrow().status()).isEqualTo(CustomerStatus.ANONYMIZED);
        assertThat(customers.findEncryptedEmail(site,c.id())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from customer_sessions where customer_id=? and revoked_at is null",Integer.class,c.id().value())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer_identities where customer_id=?",Integer.class,c.id().value())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_log",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from customer_idempotency_records where status='COMPLETED'",Integer.class)).isEqualTo(1);
        var done=jdbc.queryForList("select row_to_json(d)::text from donations d order by id");
        anonymizer.anonymize(command(c,"erase"));anonymizer.anonymize(command(c,"repeat"));
        assertThat(jdbc.queryForList("select row_to_json(d)::text from donations d order by id")).isEqualTo(done);
    }
    @Test void auditFailureRollsBackEveryMessageAndAllCustomerChanges() {
        var c=customer();var one=donation(c,MESSAGE);var two=donation(c,"second");
        var before=jdbc.queryForList("select row_to_json(d)::text from donations d order by id");
        jdbc.execute("create function task5_reject_audit() returns trigger language plpgsql as $$ begin raise exception 'injected rollback'; end $$");
        jdbc.execute("create trigger task5_reject_audit before insert on audit_log for each row execute function task5_reject_audit()");
        try {
            assertThatThrownBy(()->anonymizer.anonymize(command(c,"rollback"))).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForList("select row_to_json(d)::text from donations d order by id")).isEqualTo(before);
            assertThat(customers.findById(site,c.id()).orElseThrow().status()).isEqualTo(CustomerStatus.ACTIVE);
            assertThat(customers.findEncryptedEmail(site,c.id())).isPresent();
            assertThat(jdbc.queryForObject("select count(*) from customer_sessions where customer_id=? and revoked_at is null",Integer.class,c.id().value())).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from customer_identities where customer_id=?",Integer.class,c.id().value())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from customer_idempotency_records",Integer.class)).isZero();
        } finally {jdbc.execute("drop trigger task5_reject_audit on audit_log");jdbc.execute("drop function task5_reject_audit()");}
        anonymizer.anonymize(command(c,"rollback"));erased(one);erased(two);
    }
    @Test void bulkRedactionInvalidatesLoadedMessageAndRejectsStaleResurrection() {
        var c=customer();var p=donation(c,MESSAGE);
        var stale=new java.util.concurrent.atomic.AtomicReference<Donation>();
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            locks.findByIdForUpdate(site);customers.findByIdForUpdate(site,c.id());
            stale.set(donations.findById(site,p.donationId()).orElseThrow());
            assertThat(donations.findEncryptedMessage(site,p.donationId())).isPresent();
            redaction.redactCustomerData(site,c.id(),INITIAL_TIME);
            assertThat(donations.findEncryptedMessage(site,p.donationId())).isEmpty();
            assertThat(donations.findById(site,p.donationId()).orElseThrow().messageState()).isEqualTo(DonorMessageState.REDACTED);
        });
        assertThatThrownBy(()->donations.save(stale.get(),encryption.encrypt(MESSAGE)))
            .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalArgumentException.class);
        erased(p);
    }
    @Test void redactionRequiresCallerTransactionAndEmptyOrWrongSiteLinkageDoesNothing() {
        var c=customer();var p=donation(c,MESSAGE);
        assertThatThrownBy(()->redaction.redactCustomerData(site,c.id(),INITIAL_TIME)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            redaction.redactCustomerData(new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()),c.id(),INITIAL_TIME);
            redaction.redactCustomerData(site,new CustomerId(UUID.randomUUID()),INITIAL_TIME);
        });
        assertThat(donations.findEncryptedMessage(site,p.donationId())).isPresent();
    }
    @Test void anonymizationWaitsForSiteLockAndIncludesDonationCommittedAheadOfIt() throws Exception {
        var c=customer();var p=donation(c,MESSAGE);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pid=new AtomicInteger();
        var token=sessions.issue(site,c.id(),"https://shop.test");
        var context=resolver.resolveOptional("Bearer "+token.rawToken(),site,"https://shop.test",CustomerSessionScope.CHECKOUT_CREATE).orElseThrow();
        var createCommand=new dev.genericpayments.platform.donations.application.model.CreateDonationCheckoutCommand("test-site","help-now",java.net.URI.create("https://shop.test"),context,2000,"committed ahead","task5-concurrent-create","task5-create");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var holder=pool.submit(()->new TransactionTemplate(manager).execute(tx->{
                var created=creationTransaction.create(createCommand);entered.countDown();await(release);return created;
            }));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var eraser=pool.submit(()->new TransactionTemplate(manager).execute(tx->{
                jdbc.execute("set local lock_timeout='10s'");pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));return anonymizer.anonymize(command(c,"concurrent"));
            }));
            try {org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(pid.get()!=0&&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid.get()))).isTrue());}
            finally {release.countDown();}
            var created=holder.get(10,TimeUnit.SECONDS);eraser.get(10,TimeUnit.SECONDS);erased(p);
            assertThat(jdbc.queryForObject("select message_state from donations where id=?",String.class,created.checkout().donationId().value())).isEqualTo("REDACTED");
            assertThatThrownBy(()->donationCheckout.create(createCommand)).isInstanceOf(dev.genericpayments.platform.shared.application.PaymentApplicationException.class)
                .extracting("code").isEqualTo("AUTHENTICATION_FAILED");
        } finally {release.countDown();}
    }
    @Test void uuidArrayRedactsMultipleMessagesInOneStatementAndKeepsAnotherSiteUntouched() {
        var c=customer();for(int i=0;i<16;i++)donation(c,MESSAGE);
        var otherSite=UUID.randomUUID();var otherCampaign=UUID.randomUUID();var otherDonation=UUID.randomUUID();
        jdbc.update("insert into sites(id,site_key,display_name,status,payment_account_id,payment_method_policy,created_at,updated_at,version) values (?,'other-site','Other','ACTIVE',?,'AUTOMATIC',?,?,0)",otherSite,account.id().value(),java.sql.Timestamp.from(INITIAL_TIME),java.sql.Timestamp.from(INITIAL_TIME));
        var other=new dev.genericpayments.platform.sites.domain.SiteId(otherSite);
        campaigns.insert(DonationCampaign.draft(new DonationCampaignId(otherCampaign),other,new DonationCampaignKey("help-now"),"Help",null,"EUR",List.of(2000L),100,10000,true,true,INITIAL_TIME));
        var id=new DonationId(otherDonation);
        donations.save(Donation.create(id,other,new DonationCampaignId(otherCampaign),dev.genericpayments.platform.donations.application.port.out.DonationStore.messageReference(id),INITIAL_TIME),encryption.encrypt(MESSAGE));
        jdbc.execute("create sequence task5_redaction_statements");
        jdbc.execute("create function task5_count_redaction() returns trigger language plpgsql as $$ begin perform nextval('task5_redaction_statements'); return null; end $$");
        jdbc.execute("create trigger task5_count_redaction after update on donations for each statement execute function task5_count_redaction()");
        try {
            anonymizer.anonymize(command(c,"array"));
            assertThat(jdbc.queryForObject("select last_value from task5_redaction_statements",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from donations where site_id=? and message_state='REDACTED'",Long.class,site.value())).isEqualTo(16);
            assertThat(donations.findEncryptedMessage(other,id)).isPresent();
        } finally {jdbc.execute("drop trigger task5_count_redaction on donations");jdbc.execute("drop function task5_count_redaction()");jdbc.execute("drop sequence task5_redaction_statements");}
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("Latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    private void erased(Payment p) {
        var row=jdbc.queryForMap("select message_state,message_ciphertext,message_nonce,message_key_version from donations where id=?",p.donationId().value());
        assertThat(row).containsEntry("message_state","REDACTED").containsEntry("message_ciphertext",null).containsEntry("message_nonce",null).containsEntry("message_key_version",null);
    }
}
