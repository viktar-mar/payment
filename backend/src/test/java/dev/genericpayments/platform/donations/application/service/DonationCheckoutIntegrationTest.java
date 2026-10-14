package dev.genericpayments.platform.donations.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.application.port.out.*;
import dev.genericpayments.platform.customers.application.service.CustomerSessionService;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.donations.application.model.CreateDonationCheckoutCommand;
import dev.genericpayments.platform.donations.application.port.in.CreateDonationCheckoutUseCase;
import dev.genericpayments.platform.donations.application.port.out.*;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.sites.application.port.out.LockSitePort;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.support.PostgresIntegrationTest;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@org.springframework.test.context.TestPropertySource(properties="payment.telemetry.site-keys[0]=donation-site")
class DonationCheckoutIntegrationTest extends PostgresIntegrationTest {
    @Autowired(required=false) CreateDonationCheckoutUseCase checkout;
    @Autowired DonationCheckoutTransaction creationTransaction;
    @Autowired DonationCampaignStore campaigns;
    @Autowired DonationStore donations;
    @Autowired CustomerStore customers;
    @Autowired CustomerSessionService sessions;
    @Autowired CustomerSessionResolver resolver;
    @Autowired LockSitePort siteLocks;
    @Autowired EncryptedSecretPort encryption;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    @Autowired PlatformTransactionManager manager;
    @Autowired dev.genericpayments.platform.payments.application.port.in.CreateDonationPaymentUseCase paymentCreation;
    @Autowired dev.genericpayments.platform.payments.application.port.in.ConfirmCheckoutUseCase confirmation;
    @Autowired dev.genericpayments.platform.customers.application.port.in.RevalidateCustomerSessionUseCase revalidation;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    dev.genericpayments.platform.provider.adapter.out.fake.FakePaymentProviderAdapter provider;
    SiteId site;
    DonationCampaign campaign;
    static final URI ORIGIN=URI.create("https://donations.test");
    @BeforeEach void fixture() {
        meters.find("payment.created").meters().forEach(meters::remove);
        clearDatabase();site=new SiteId(UUID.randomUUID());var account=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'donation-account','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",account);
        jdbc.update("insert into sites values (?, 'donation-site','Donations',?,'ACTIVE','AUTOMATIC',now(),now(),0)",site.value(),account);
        jdbc.update("insert into site_allowed_origins(site_id,origin) values (?,?)",site.value(),ORIGIN.toString());
        jdbc.update("insert into site_authentication_modes(site_id,authentication_mode) values (?,'SITE_TOKEN')",site.value());
        campaign=campaigns.insert(DonationCampaign.draft(new DonationCampaignId(UUID.randomUUID()),site,new DonationCampaignKey("help-now"),
            "Help",null,"EUR",List.of(100L,500L),100,1000,true,true,INITIAL_TIME));
        campaign=campaigns.update(campaign.activate(INITIAL_TIME),0);
    }
    CreateDonationCheckoutCommand command(String key,CustomerSessionContext session,long amount,String message) {
        return new CreateDonationCheckoutCommand("donation-site","help-now",ORIGIN,session,amount,message,key,"donation-tests");
    }
    DonationPaymentCreationPort.Checkout create(CreateDonationCheckoutCommand command) {
        assertThat(checkout).as("Donation checkout use case must exist").isNotNull();return checkout.create(command);
    }
    CustomerSessionContext identity() {
        var c=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        var issued=sessions.issue(site,c.id(),ORIGIN.toString());
        return resolver.resolveOptional("Bearer "+issued.rawToken(),site,ORIGIN.toString(),CustomerSessionScope.CHECKOUT_CREATE).orElseThrow();
    }
    @Test void identityIsOwnedOnlyByPaymentAndNfcMessageIsEncrypted() {
        var identity=identity();var result=create(command("identity",identity,1000,"cafe\u0301"));
        assertThat(result.customerId()).isEqualTo(identity.customerId().value());
        assertThat(jdbc.queryForObject("select customer_id from payments",UUID.class)).isEqualTo(identity.customerId().value());
        var stored=donations.findEncryptedMessage(site,result.donationId()).orElseThrow();
        assertThat(encryption.decrypt(stored)).isEqualTo("\u0001café");
        assertThat(jdbc.queryForObject("select row_to_json(t)::text from donations t",String.class)).doesNotContain("café","cafe",identity.customerId().value().toString());
        assertThat(jdbc.queryForObject("select row_to_json(t)::text from payment_events t",String.class)).doesNotContain("café","cafe","message");
        assertThatThrownBy(()->create(command("identity",null,1000,"café"))).isInstanceOf(PaymentApplicationException.class)
            .extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
    }
    @Test void paymentAndSessionBoundariesRequireTheCallersTransactionAndCreationNeverCallsProvider() {
        assertThatThrownBy(()->paymentCreation.preflight(null)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(()->paymentCreation.create(null)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(()->revalidation.revalidate(null,site,ORIGIN.toString(),CustomerSessionScope.CHECKOUT_CREATE)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        create(command("provider-zero",null,500,null));verifyNoInteractions(provider);
    }
    @Test void publicCreationCoordinatorRejectsAmbientTransactionBeforeAnyWrite() {
        assertThatThrownBy(()->new TransactionTemplate(manager).execute(tx->create(command("ambient",null,500,null))))
            .isInstanceOf(IllegalStateException.class).hasMessage("Checkout coordinator requires no ambient transaction");
        assertNoCheckoutFacts();verifyNoInteractions(provider);
    }
    @ParameterizedTest @ValueSource(strings={"invalid","origin","expired"})
    void unauthorizedConfirmCannotPrepareOrCallAnyProviderMethod(String failure) {
        var c=create(command("confirm",null,500,null)).checkout();
        if(failure.equals("expired"))TestClock.set(INITIAL_TIME.plusSeconds(86400));
        var cmd=new dev.genericpayments.platform.payments.application.port.in.ConfirmCheckoutCommand(c.view().checkoutId(),
            failure.equals("origin")?URI.create("https://other.test"):ORIGIN,failure.equals("invalid")?"bad":c.checkoutToken(),
            dev.genericpayments.platform.sites.domain.PaymentMethod.CARD,"confirm","test");
        assertThatThrownBy(()->confirmation.confirm(cmd)).isInstanceOf(PaymentApplicationException.class);
        verifyNoInteractions(provider);assertThat(jdbc.queryForObject("select count(*) from payment_attempts",Long.class)).isZero();
    }
    @Test void sequentialConfirmPreparesCommittedFactsOutsideTransactionAndKeepsOneAttempt() {
        var created=create(command("confirm",null,500,"private"));verifyNoInteractions(provider);
        doAnswer(invocation->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from donations",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from idempotency_records where status='COMPLETED'",Long.class)).isEqualTo(1);
            var request=(dev.genericpayments.platform.payments.application.model.ProviderPaymentRequest)invocation.getArgument(0);
            assertThat(request.payment().donationId()).isEqualTo(created.donationId());
            assertThat(request.payment().donationSnapshot().version()).isEqualTo(1);
            return invocation.callRealMethod();
        }).when(provider).preparePayment(any());
        doAnswer(invocation->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(provider).confirmPayment(any());
        var c=created.checkout();var cmd=confirmCommand(c);
        var first=confirmation.confirm(cmd);assertThat(confirmation.confirm(cmd)).isEqualTo(first);
        verify(provider,times(1)).preparePayment(any());verify(provider,times(1)).confirmPayment(any());
        assertConfirmationFacts();
    }
    @Test void concurrentConfirmsUseStableProviderKeysAndOneAttempt() throws Exception {
        var created=create(command("concurrent-confirm",null,500,null));var barrier=new CyclicBarrier(2);
        doAnswer(invocation->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            barrier.await(5,TimeUnit.SECONDS);return invocation.callRealMethod();
        }).when(provider).preparePayment(any());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->confirmation.confirm(confirmCommand(created.checkout())));
            var second=pool.submit(()->confirmation.confirm(confirmCommand(created.checkout())));
            first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
        }
        assertConfirmationFacts();
        assertThat(jdbc.queryForObject("select count(*) from fake_provider_operations",Long.class)).isEqualTo(2);
    }
    @Test void unknownPreparationCannotCreateAttemptOrConfirmUntilPrepared() {
        var created=create(command("unknown-prepare",null,500,null));
        doReturn(new dev.genericpayments.platform.payments.application.model.ProviderResult.Unknown(null)).when(provider).preparePayment(any());
        assertThatThrownBy(()->confirmation.confirm(confirmCommand(created.checkout()))).isInstanceOf(PaymentApplicationException.class)
            .extracting("code").isEqualTo("PROVIDER_RESULT_UNKNOWN");
        assertThat(jdbc.queryForObject("select count(*) from payment_attempts",Long.class)).isZero();
        verify(provider,never()).confirmPayment(any());
    }
    dev.genericpayments.platform.payments.application.port.in.ConfirmCheckoutCommand confirmCommand(dev.genericpayments.platform.payments.application.model.CheckoutCreation c) {
        return new dev.genericpayments.platform.payments.application.port.in.ConfirmCheckoutCommand(c.view().checkoutId(),ORIGIN,c.checkoutToken(),
            dev.genericpayments.platform.sites.domain.PaymentMethod.CARD,"confirm","test");
    }
    void assertConfirmationFacts() {
        assertThat(jdbc.queryForObject("select count(*) from payment_attempts",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment_events where event_type='PAYMENT_CREATED'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select snapshot_campaign_version from payments",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select donation_id from payments",UUID.class)).isNotNull();
    }
    @ParameterizedTest @ValueSource(strings={"revoke","anonymize","scope","origin","customer","expiry"})
    void verifiedContextCannotOutliveCommittedSessionFacts(String mutation) {
        var identity=identity();
        switch(mutation) {
            case "revoke" -> sessions.logout(identity);
            case "anonymize" -> customers.save(customers.findById(site,identity.customerId()).orElseThrow().anonymize(INITIAL_TIME));
            case "scope" -> jdbc.update("update customer_sessions set scopes='[\"CUSTOMER_READ\"]' where id=?",identity.sessionId().value());
            case "origin" -> jdbc.update("update customer_sessions set origin='https://other.test' where id=?",identity.sessionId().value());
            case "customer" -> {
                var other=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
                jdbc.update("update customer_sessions set customer_id=? where id=?",other.id().value(),identity.sessionId().value());
            }
            case "expiry" -> TestClock.set(INITIAL_TIME.plusSeconds(1800));
        }
        assertThatThrownBy(()->create(command("stale",identity,500,null))).isInstanceOf(PaymentApplicationException.class)
            .extracting("code").isEqualTo("AUTHENTICATION_FAILED");
        assertNoCheckoutFacts();
    }
    @ParameterizedTest @ValueSource(longs={100,501,1000})
    void customAmountIncludesBothBounds(long amount) { assertThat(create(command("custom",null,amount,null)).checkout().view().amountMinor()).isEqualTo(amount); }
    @Test void anonymousPolicyAndCampaignLifecycleApplyOnlyToNewIntent() {
        var first=create(command("first",null,500,null));
        campaign=campaigns.update(campaign.updateConfiguration("Changed",null,"EUR",List.of(100L),100,100,false,false,INITIAL_TIME),1);
        assertThat(create(command("first",null,500,null))).isEqualTo(first);
        assertThatThrownBy(()->create(command("new",null,100,null))).isInstanceOf(PaymentApplicationException.class)
            .extracting("code").isEqualTo("ANONYMOUS_DONATION_NOT_ALLOWED");
        campaign=campaigns.update(campaign.pause(INITIAL_TIME),2);
        assertThatThrownBy(()->create(command("paused",identity(),100,null))).isInstanceOf(PaymentApplicationException.class)
            .extracting("code").isEqualTo("CAMPAIGN_NOT_AVAILABLE");
    }
    @Test void expiredReplayShredsSecretButKeepsOriginalDonationAndPayment() {
        var first=create(command("expires",null,500,null));TestClock.set(INITIAL_TIME.plusSeconds(86400));
        assertThatThrownBy(()->create(command("expires",null,500,null))).isInstanceOf(PaymentApplicationException.class)
            .satisfies(e->{var failure=(PaymentApplicationException)e;assertThat(failure.code()).isEqualTo("CHECKOUT_EXPIRED");
                assertThat(failure.extensions()).containsEntry("payment_id",first.checkout().view().paymentId());});
        assertThat(jdbc.queryForObject("select response_secret_state from idempotency_records",String.class)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select id from donations",UUID.class)).isEqualTo(first.donationId().value());
        assertThat(jdbc.queryForObject("select id from payments",UUID.class)).isEqualTo(first.checkout().view().paymentId());
        verifyNoInteractions(provider);
    }
    @Test void replayRevalidatesIdentityAndDoesNotLeakStoredTokenAfterRevocation() {
        var identity=identity();var first=create(command("replay-auth",identity,500,null));sessions.logout(identity);
        assertThatThrownBy(()->create(command("replay-auth",identity,500,null))).isInstanceOf(PaymentApplicationException.class)
            .extracting("code").isEqualTo("AUTHENTICATION_FAILED");
        assertThat(jdbc.queryForObject("select id from donations",UUID.class)).isEqualTo(first.donationId().value());
    }
    @Test void absentEmptyAndChangedCampaignRemainDistinctIntents() {
        create(command("empty",null,500,null));
        assertThatThrownBy(()->create(command("empty",null,500,""))).isInstanceOf(PaymentApplicationException.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->create(new CreateDonationCheckoutCommand("donation-site","different-campaign",ORIGIN,null,500,null,"empty","test")))
            .isInstanceOf(PaymentApplicationException.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject("select count(*) from donations",Long.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"donations","payments","payment_events","checkout_sessions","idempotency_records"})
    void failureAfterEveryLocalWriteRollsBackWholeTransaction(String table) {
        jdbc.execute("create function task4_fail_write() returns trigger language plpgsql as $$ begin raise exception 'injected rollback'; end $$");
        String event=table.equals("idempotency_records")?"update":"insert";
        jdbc.execute("create trigger task4_failure after "+event+" on "+table+" for each row execute function task4_fail_write()");
        try {
            assertThatThrownBy(()->create(command("rollback",null,500,"private"))).isInstanceOf(RuntimeException.class);
            assertNoCheckoutFacts();
            assertThat(createdMeter()).isZero();
        } finally {
            jdbc.execute("drop trigger task4_failure on "+table);jdbc.execute("drop function task4_fail_write()");
        }
    }
    @Test void concurrentSameKeyWaitsForCommitThenReplaysOneCompleteCreation() throws Exception {
        var created=new CountDownLatch(1);var commit=new CountDownLatch(1);var pid=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->new TransactionTemplate(manager).execute(tx->{
                var result=creationTransaction.create(command("concurrent",null,500,"same"));created.countDown();await(commit);return result;
            }));
            assertThat(created.await(10,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->new TransactionTemplate(manager).execute(tx->{
                pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));return creationTransaction.create(command("concurrent",null,500,"same"));
            }));
            try { waitForDatabaseLock(pid); } finally { commit.countDown(); }
            var original=first.get(10,TimeUnit.SECONDS);
            var replay=second.get(10,TimeUnit.SECONDS);
            assertThat(replay.checkout()).isEqualTo(original.checkout());
            assertThat(replay.expiredPaymentId()).isEqualTo(original.expiredPaymentId());
            assertThat(original.replayed()).isFalse();
            assertThat(replay.replayed()).isTrue();
        } finally { commit.countDown(); }
        assertThat(jdbc.queryForObject("select count(*) from donations",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payments",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment_events",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from checkout_sessions",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records where status='COMPLETED'",Long.class)).isEqualTo(1);
    }
    @Test void siteLockWaitSeesCommittedRevocationBeforeAnyWrite() throws Exception {
        var identity=identity();var locked=new CountDownLatch(1);var commit=new CountDownLatch(1);var pid=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var revoker=pool.submit(()->new TransactionTemplate(manager).execute(tx->{
                siteLocks.findByIdForUpdate(site);sessions.logout(identity);locked.countDown();await(commit);return null;
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var creator=pool.submit(()->new TransactionTemplate(manager).execute(tx->{
                pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));return creationTransaction.create(command("revoked",identity,500,null));
            }));
            try { waitForDatabaseLock(pid); } finally { commit.countDown(); }
            revoker.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(()->creator.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(PaymentApplicationException.class)
                .satisfies(f->assertThat(((PaymentApplicationException)f.getCause()).code()).isEqualTo("AUTHENTICATION_FAILED"));
        } finally { commit.countDown(); }
        assertNoCheckoutFacts();
    }
    void waitForDatabaseLock(AtomicInteger pid) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end) {
            if(pid.get()!=0 && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid.get())))return;
            Thread.sleep(10);
        }
        throw new AssertionError("Expected bounded PostgreSQL lock wait");
    }
    static void await(CountDownLatch latch) { try { if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("Latch timeout"); } catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);} }
    void assertNoCheckoutFacts() {
        for(String table:List.of("donations","payments","payment_events","checkout_sessions","idempotency_records","fake_provider_operations"))
            assertThat(jdbc.queryForObject("select count(*) from "+table,Long.class)).as(table).isZero();
    }
    double createdMeter() {
        return meters.find("payment.created").counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }
    @Test void createdMeterEmitsOnlyAfterCommitAndNeverAgainForReplayConflictOrPrepare() {
        var request=command("meter",null,500,null);
        var result=new TransactionTemplate(manager).execute(tx->{
            var local=creationTransaction.create(request);
            assertThat(jdbc.queryForObject("select count(*) from idempotency_records where status='COMPLETED'",Long.class)).isEqualTo(1);
            assertThat(createdMeter()).as("not before the owning commit").isZero();
            verifyNoInteractions(provider);
            return local.checkout();
        });
        assertThat(createdMeter()).as("one newly committed Payment").isEqualTo(1);
        assertThat(meters.get("payment.created").tags("site","donation-site","provider","fake").counter().count()).isEqualTo(1);
        assertThat(create(request)).isEqualTo(result);
        assertThatThrownBy(()->create(command("meter",null,100,null))).isInstanceOf(PaymentApplicationException.class)
            .extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(createdMeter()).isEqualTo(1);
        confirmation.confirm(confirmCommand(result.checkout()));
        confirmation.confirm(confirmCommand(result.checkout()));
        assertThat(createdMeter()).isEqualTo(1);
        assertConfirmationFacts();
    }
    @Test void unconfiguredSiteRejectsBeforeAnyCreationOrReceiptWrite() {
        jdbc.update("update sites set site_key='unconfigured' where id=?",site.value());
        // PostgreSQL sequence increments survive rollback, unlike final row counts.
        jdbc.execute("create sequence task4_preflight_writes");
        jdbc.execute("create function task4_probe_write() returns trigger language plpgsql as $$ begin perform nextval('task4_preflight_writes'); return NEW; end $$");
        var tables=List.of("donations","payments","payment_events","checkout_sessions","idempotency_records");
        for(var table:tables) jdbc.execute("create trigger task4_probe before insert or update on "+table+" for each row execute function task4_probe_write()");
        try {
            assertThatThrownBy(()->create(new CreateDonationCheckoutCommand("unconfigured","help-now",ORIGIN,null,500,null,"preflight","test")))
                .isInstanceOf(PaymentApplicationException.class).satisfies(e->{
                    var failure=(PaymentApplicationException)e;
                    assertThat(failure.code()).isEqualTo("SITE_NOT_AVAILABLE");assertThat(failure.status()).isEqualTo(503);
                });
            assertThat(jdbc.queryForObject("select is_called from task4_preflight_writes",Boolean.class)).as("no attempted insert or update, even one later rolled back").isFalse();
            assertNoCheckoutFacts();assertThat(createdMeter()).isZero();verifyNoInteractions(provider);
            jdbc.update("update sites set site_key='donation-site' where id=?",site.value());
            create(command("configured-control",null,500,null));
            assertThat(jdbc.queryForObject("select is_called from task4_preflight_writes",Boolean.class)).as("positive control proves write probes are active").isTrue();
            assertThat(createdMeter()).isEqualTo(1);verifyNoInteractions(provider);
        } finally {
            for(var table:tables) jdbc.execute("drop trigger task4_probe on "+table);
            jdbc.execute("drop function task4_probe_write()");jdbc.execute("drop sequence task4_preflight_writes");
        }
    }
}
