package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripePaymentProviderAdapter;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripePaymentProviderAdapterTest;
import dev.genericpayments.platform.support.StripeHttpFixture;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;

@SpringBootTest(properties="payment.telemetry.site-keys[0]=test-site")
@ActiveProfiles("test")
@TestPropertySource(properties={"payment.stripe.sandbox.enabled=true",
    "payment.stripe.sandbox.secret-key=sk_test_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    "payment.stripe.sandbox.publishable-key=pk_test_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
    "payment.stripe.sandbox.webhook-secret=whsec_CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"})
@Import(StripePaymentProviderAdapterTest.FixtureConfiguration.class)
abstract class StripeInboxFixture extends ProviderTestFixture {
    @Autowired ProviderInboxPort inbox;
    @Autowired ProviderReferenceInboxWorker worker;
    @Autowired StripePaymentProviderAdapter provider;
    @Autowired StripePaymentProviderAdapterTest.FixtureFactory factory;
    @Autowired LoadPaymentAccountPort accounts;
    @Autowired SavePaymentAttemptPort saveAttempts;
    @Autowired ClientPaymentAttemptPort baselines;
    @Autowired ProviderInboxResolutionPort resolution;
    @Autowired PaymentTransitionService transitions;
    @Autowired ProviderInboxService legacyInbox;
    @Autowired ProviderInboxWorker legacyWorker;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired SaveRefundPort saveRefunds;
    @Autowired LoadRefundPort refunds;
    @Autowired RefundTransitionService refundTransitions;
    StripeHttpFixture http;
    Payment original;
    @BeforeEach void stripe() throws Exception {
        http=new StripeHttpFixture();factory.http=http;
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_expected',credentials_ref='stripe-sandbox-default' where id=?",account.id().value());
        original=save.save(payment());
        identity();http.enqueue("POST","/v1/payment_intents",200,intent("requires_payment_method",null,2000));
        provider.preparePayment(new ProviderPaymentRequest(currentAccount(),original,ProviderOperationKeys.prepare(original.id())));
    }
    @AfterEach void closeHttp(){http.close();}
    PaymentAccount currentAccount(){return accounts.findById(account.id()).orElseThrow();}
    Payment mapped(){original=save.save(original.withProviderPaymentReference("pi_owned",INITIAL_TIME));return original;}
    void identity(){http.enqueue("GET","/v1/account",200,"{\"id\":\"acct_expected\",\"object\":\"account\"}");}
    String intent(String status,String charge,long amount){return "{\"id\":\"pi_owned\",\"object\":\"payment_intent\",\"livemode\":false,\"amount\":"+amount+",\"currency\":\"eur\",\"amount_received\":"+(status.equals("succeeded")?amount:0)+",\"capture_method\":\"automatic\",\"payment_method_types\":[\"card\"],\"status\":\""+status+"\",\"latest_charge\":"+(charge==null?"null":"\""+charge+"\"")+",\"metadata\":{\"payment_id\":\""+original.id().value()+"\",\"site_id\":\""+site.value()+"\",\"source\":\"DYNAMIC\"}}";}
    void snapshot(String status,String charge){
        identity();http.enqueue("GET","/v1/payment_intents/pi_owned",200,intent(status,charge,2000));
        if(charge!=null)charge(charge,status.equals("succeeded"));
    }
    void charge(String charge,boolean paid){http.enqueue("GET","/v1/charges/"+charge,200,"{\"id\":\""+charge+"\",\"object\":\"charge\",\"payment_intent\":\"pi_owned\",\"livemode\":false,\"amount\":2000,\"currency\":\"eur\",\"amount_captured\":"+(paid?2000:0)+",\"paid\":"+paid+",\"captured\":"+paid+",\"status\":\""+(paid?"succeeded":"failed")+"\",\"payment_method_details\":{\"type\":\"card\"}}");}
    ProviderReferenceReceipt receipt(String event,String type){return new ProviderReferenceReceipt(PaymentProviderType.STRIPE,account.id(),event,"pi_owned",ProviderReferenceReceipt.ObjectType.PAYMENT,type,"2026-09-30.endive",INITIAL_TIME,Instant.now());}
    void deliver(String event,String type){inbox.insertReference(receipt(event,type));worker.runOnce();}
    Payment current(){return load.findById(site,original.id()).orElseThrow();}
    String inboxStatus(){return jdbc.queryForObject("select status from provider_inbox order by received_at desc limit 1",String.class);}
    void callback(){var id=UUID.randomUUID();var now=java.sql.Timestamp.from(INITIAL_TIME);jdbc.update("insert into site_callback_secret_versions(id,site_id,secret_version,ciphertext,nonce,master_key_version,created_at) values (?,?,1,?,?,'test-v1',?)",id,site.value(),new byte[32],new byte[12],now);jdbc.update("insert into site_callback_configs(site_id,endpoint_url,active_secret_version_id,enabled,created_at,updated_at) values (?,'https://callback.test/inbox',?,true,?,?)",site.value(),id,now,now);}
    PaymentAttempt clientAttempt(int sequence,String watermark){var id=new PaymentAttemptId(UUID.randomUUID());var a=PaymentAttempt.start(id,original.id(),sequence,ProviderOperationKeys.confirm(id),"CARD",INITIAL_TIME).withProviderIntentReference("pi_owned",INITIAL_TIME).requiresPaymentMethod(INITIAL_TIME);saveAttempts.insert(a,INITIAL_TIME);baselines.record(new ClientPaymentAttemptPort.Baseline(id.value(),account.id(),original.id(),null,"stripe-sandbox-default","acct_expected",watermark));return a;}
}

class StripeInboxIntegrationTest extends StripeInboxFixture {
    @Test void unverifiedLegacyStripeEnvelopeCannotSettle(){
        mapped();legacyInbox.receive(new ProviderEventEnvelope(PaymentProviderType.STRIPE,account.id(),"legacy-success","pi_owned",PaymentEventType.PAYMENT_SUCCEEDED,INITIAL_TIME,INITIAL_TIME,Map.of()));
        legacyWorker.runOnce();assertThat(current().status()).isEqualTo(PaymentStatus.CREATED);assertThat(count("payment_events")).isZero();
    }
    @Test void duplicateAndDifferentSuccessEventsSettleExactlyOnce(){
        mapped();callback();var receipt=receipt("evt_success","payment_intent.succeeded");assertThat(inbox.insertReference(receipt)).isTrue();assertThat(inbox.insertReference(receipt)).isFalse();
        snapshot("succeeded","ch_paid");worker.runOnce();
        assertThat(current().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        snapshot("succeeded","ch_paid");deliver("evt_successagain","payment_intent.succeeded");
        assertThat(count("payment_events")).isEqualTo(1);assertThat(count("outbox_events")).isEqualTo(1);assertThat(count("provider_charge_receipts")).isEqualTo(1);
        assertThat(jdbc.queryForList("select status from provider_inbox",String.class)).containsOnly("PROCESSED");
    }
    @Test void oldFailureEventUsesCurrentSuccessAndCannotRegressSettlement(){
        mapped();snapshot("succeeded","ch_paid");deliver("evt_oldfailure","payment_intent.payment_failed");
        assertThat(current().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        snapshot("requires_payment_method","ch_late");deliver("evt_latefailure","payment_intent.payment_failed");
        snapshot("processing",null);deliver("evt_lateprocessing","payment_intent.processing");
        assertThat(current().status()).isEqualTo(PaymentStatus.SUCCEEDED);assertThat(count("payment_events")).isEqualTo(1);assertThat(count("provider_charge_receipts")).isEqualTo(1);
    }
    @Test void currentSuccessWithDistinctOldFailureUsesFourReadsAndConsumesOnlyPaidCharge(){
        mapped();int before=http.calls().size();identity();
        http.enqueue("GET","/v1/payment_intents/pi_owned",200,intent("succeeded","ch_paid",2000).replace("\"metadata\":","\"last_payment_error\":{\"charge\":\"ch_old\"},\"metadata\":"));
        charge("ch_paid",true);charge("ch_old",false);deliver("evt_fourreads","payment_intent.payment_failed");
        assertThat(current().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(http.calls().subList(before,http.calls().size())).extracting(StripeHttpFixture.Call::path)
            .containsExactly("/v1/account","/v1/payment_intents/pi_owned","/v1/charges/ch_paid","/v1/charges/ch_old");
        assertThat(jdbc.queryForList("select charge_reference from provider_charge_receipts",String.class)).containsExactly("ch_paid");assertThat(count("payment_attempts")).isZero();
    }
    @Test void concurrentSuccessClaimsShareOneFinancialTransactionOutcome() throws Exception {
        mapped();callback();inbox.insertReference(receipt("evt_concurrent1","payment_intent.succeeded"));inbox.insertReference(receipt("evt_concurrent2","payment_intent.succeeded"));
        var first=inbox.claimReference(Duration.ofSeconds(120)).orElseThrow();var second=inbox.claimReference(Duration.ofSeconds(120)).orElseThrow();
        snapshot("succeeded","ch_paid");var firstProof=resolution.resolve(inbox.reference(first).orElseThrow());
        snapshot("succeeded","ch_paid");var secondProof=resolution.resolve(inbox.reference(second).orElseThrow());
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
            var one=pool.submit(() -> {ready.countDown();start.await();return transitions.processVerified(first,firstProof);});
            var two=pool.submit(() -> {ready.countDown();start.await();return transitions.processVerified(second,secondProof);});
            boolean bothReady;
            try {bothReady=ready.await(2,java.util.concurrent.TimeUnit.SECONDS);}
            finally {start.countDown();}
            assertThat(bothReady).isTrue();
            var outcomes=List.of(one.get(3,java.util.concurrent.TimeUnit.SECONDS),two.get(3,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(PaymentTransitionService.Result::changed)).hasSize(1);
        }
        assertThat(count("payment_events")).isEqualTo(1);assertThat(count("outbox_events")).isEqualTo(1);assertThat(count("provider_charge_receipts")).isEqualTo(1);
        assertThat(jdbc.queryForList("select status from provider_inbox",String.class)).containsOnly("PROCESSED");
    }
    @Test void acknowledgedReceiptBeforeMappingRepairsOnlyOriginalPayment(){
        snapshot("succeeded","ch_paid");deliver("evt_beforemapping","payment_intent.succeeded");
        assertThat(current().providerPaymentReference()).isEqualTo("pi_owned");assertThat(current().status()).isEqualTo(PaymentStatus.SUCCEEDED);assertThat(count("payments")).isEqualTo(1);
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST"))).hasSize(1);
    }
    @Test void wrongAccountKeyStopsAtIdentityGetWithoutFinancialMutation(){
        mapped();int before=http.calls().size();
        http.enqueue("GET","/v1/account",200,"{\"id\":\"acct_foreign\",\"object\":\"account\"}");
        assertThatThrownBy(() -> resolution.resolve(receipt("evt_wrongkey","payment_intent.succeeded"))).isInstanceOf(RuntimeException.class);
        assertThat(http.calls().subList(before,http.calls().size())).singleElement().satisfies(c -> assertThat(c.path()).isEqualTo("/v1/account"));
        assertThat(current().status()).isEqualTo(PaymentStatus.CREATED);assertThat(count("provider_charge_receipts")).isZero();assertThat(count("payment_events")).isZero();
    }
    @Test void foreignAmountIsUnresolvedAndDoesNotFetchCharge(){
        mapped();int before=http.calls().size();identity();http.enqueue("GET","/v1/payment_intents/pi_owned",200,intent("succeeded","ch_paid",1999));
        deliver("evt_amount","payment_intent.succeeded");assertThat(current().status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(inboxStatus()).isEqualTo("FAILED");assertThat(count("payment_events")).isZero();assertThat(count("provider_charge_receipts")).isZero();
        assertThat(http.calls().subList(before,http.calls().size())).extracting(StripeHttpFixture.Call::path).containsExactly("/v1/account","/v1/payment_intents/pi_owned");
    }
    @Test void metadataCandidateWithoutOriginalAckCannotBindOrCreatePayment(){
        original=save.save(payment());snapshot("succeeded","ch_paid");deliver("evt_unproved","payment_intent.succeeded");
        assertThat(current().providerPaymentReference()).isNull();assertThat(inboxStatus()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select error_category from provider_inbox",String.class)).isEqualTo("UNRESOLVED_OBJECT");
        assertThat(count("payments")).isEqualTo(2);assertThat(count("provider_operations")).isEqualTo(1);assertThat(count("provider_charge_receipts")).isZero();
    }
    @Test void liveLocalLeaseCannotBeStolenForAckMappingRepair(){
        var now=Instant.now();load.tryAcquireReconciliationLease(site,original.id(),"local-owner",now,now.plusSeconds(90));
        snapshot("succeeded","ch_paid");deliver("evt_livelease","payment_intent.succeeded");
        assertThat(current().providerPaymentReference()).isNull();assertThat(inboxStatus()).isEqualTo("FAILED");assertThat(count("payment_events")).isZero();
        assertThat(jdbc.queryForObject("select reconciliation_lease_owner from payments",String.class)).isEqualTo("local-owner");
    }
    @Test void advancedMissingReferenceWaitsForRecoveryEvenWithAck(){
        original=save.save(original.processing(INITIAL_TIME));snapshot("succeeded","ch_paid");deliver("evt_advanced","payment_intent.succeeded");
        assertThat(current().providerPaymentReference()).isNull();assertThat(current().status()).isEqualTo(PaymentStatus.PROCESSING);assertThat(inboxStatus()).isEqualTo("FAILED");assertThat(count("payment_events")).isZero();
    }
    @Test void originalAccountRemainsAuthoritativeAfterSiteReassignment(){
        mapped();var other=UUID.randomUUID();jdbc.update("insert into payment_accounts values (?,'other-account','FAKE','SANDBOX','ACTIVE','fake-other','fake-ref',?,0)",other,java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update("update sites set payment_account_id=? where id=?",other,site.value());
        snapshot("succeeded","ch_paid");deliver("evt_rerouted","payment_intent.succeeded");
        assertThat(current().status()).isEqualTo(PaymentStatus.SUCCEEDED);assertThat(current().paymentAccountId()).isEqualTo(account.id());
        assertThat(jdbc.queryForObject("select payment_account_id from provider_charge_receipts",UUID.class)).isEqualTo(account.id().value());
    }
    @Test void crashAfterJournalAndOutboxBeforeCompleteRollsBackAllFinancialWrites(){
        mapped();callback();
        jdbc.execute("create function task9_reject_complete() returns trigger language plpgsql as $$ begin if new.status='PROCESSED' then raise exception 'injected completion failure' using errcode='23514'; end if; return new; end; $$");
        jdbc.execute("create trigger task9_reject_complete before update on provider_inbox for each row execute function task9_reject_complete()");
        try {snapshot("succeeded","ch_paid");deliver("evt_crash","payment_intent.succeeded");}
        finally {jdbc.execute("drop trigger task9_reject_complete on provider_inbox");jdbc.execute("drop function task9_reject_complete()");}
        assertThat(current().status()).isEqualTo(PaymentStatus.CREATED);assertThat(count("payment_events")).isZero();assertThat(count("outbox_events")).isZero();assertThat(count("provider_charge_receipts")).isZero();assertThat(inboxStatus()).isEqualTo("FAILED");
        jdbc.update("update provider_inbox set next_attempt_at=clock_timestamp()");snapshot("succeeded","ch_paid");worker.runOnce();
        assertThat(current().status()).isEqualTo(PaymentStatus.SUCCEEDED);assertThat(count("payment_events")).isEqualTo(1);assertThat(count("outbox_events")).isEqualTo(1);assertThat(count("provider_charge_receipts")).isEqualTo(1);
    }
    @Test void lostClaimCannotApplyAndNewOwnerCanContinue(){
        mapped();inbox.insertReference(receipt("evt_lost","payment_intent.succeeded"));var old=inbox.claimReference(Duration.ofSeconds(120)).orElseThrow();
        snapshot("succeeded","ch_paid");var proof=resolution.resolve(inbox.reference(old).orElseThrow());
        jdbc.update("update provider_inbox set lease_expires_at=clock_timestamp()-interval '1 second'");
        var next=inbox.claimReference(Duration.ofSeconds(120)).orElseThrow();assertThat(next.owner()).isNotEqualTo(old.owner());
        assertThatThrownBy(() -> transitions.processVerified(old,proof)).isInstanceOf(RuntimeException.class);
        assertThat(current().status()).isEqualTo(PaymentStatus.CREATED);assertThat(count("provider_charge_receipts")).isZero();
        snapshot("succeeded","ch_paid");transitions.processVerified(next,resolution.resolve(inbox.reference(next).orElseThrow()));
        assertThat(current().status()).isEqualTo(PaymentStatus.SUCCEEDED);assertThat(inboxStatus()).isEqualTo("PROCESSED");
    }
    @Test void changedAccountAfterRetrievalRejectsPreviouslyVerifiedProof(){
        mapped();inbox.insertReference(receipt("evt_binding","payment_intent.succeeded"));var claim=inbox.claimReference(Duration.ofSeconds(120)).orElseThrow();
        snapshot("succeeded","ch_paid");var proof=resolution.resolve(inbox.reference(claim).orElseThrow());
        jdbc.update("update payment_accounts set external_account_id='acct_changed' where id=?",account.id().value());
        assertThatThrownBy(() -> transitions.processVerified(claim,proof)).isInstanceOf(RuntimeException.class);
        assertThat(current().status()).isEqualTo(PaymentStatus.CREATED);assertThat(count("payment_events")).isZero();assertThat(count("provider_charge_receipts")).isZero();
    }
    @Test void changedFingerprintFactAfterRetrievalCannotSettle(){
        mapped();inbox.insertReference(receipt("evt_tax","payment_intent.succeeded"));var claim=inbox.claimReference(Duration.ofSeconds(120)).orElseThrow();
        snapshot("succeeded","ch_paid");var proof=resolution.resolve(inbox.reference(claim).orElseThrow());
        jdbc.update("update payments set tax_mode='INCLUSIVE' where id=?",original.id().value());
        assertThatThrownBy(() -> transitions.processVerified(claim,proof)).isInstanceOf(RuntimeException.class);
        assertThat(current().status()).isEqualTo(PaymentStatus.CREATED);assertThat(count("provider_charge_receipts")).isZero();
    }
    @Test void expiryDuringJournalRollsBackBeforeComplete(){
        mapped();callback();inbox.insertReference(receipt("evt_expiry","payment_intent.succeeded"));var claim=inbox.claimReference(Duration.ofSeconds(120)).orElseThrow();
        snapshot("succeeded","ch_paid");var proof=resolution.resolve(inbox.reference(claim).orElseThrow());
        jdbc.execute("create function task9_delay_journal() returns trigger language plpgsql as $$ begin perform pg_sleep(0.6); return new; end; $$");
        jdbc.execute("create trigger task9_delay_journal after insert on payment_events for each row execute function task9_delay_journal()");
        try {
            jdbc.update("update provider_inbox set lease_expires_at=clock_timestamp()+interval '0.3 seconds'");
            assertThatThrownBy(() -> transitions.processVerified(claim,proof)).isInstanceOf(RuntimeException.class);
        } finally {jdbc.execute("drop trigger task9_delay_journal on payment_events");jdbc.execute("drop function task9_delay_journal()");}
        assertThat(current().status()).isEqualTo(PaymentStatus.CREATED);assertThat(count("payment_events")).isZero();assertThat(count("outbox_events")).isZero();assertThat(count("provider_charge_receipts")).isZero();
        assertThat(inbox.failReference(claim,"PROCESSING_FAILED")).isFalse();
    }
    @Test void dedicatedReferenceLaneDoesNotBlockSharedSchedulerOrPreclaimNextReceipt() throws Exception {
        mapped();inbox.insertReference(receipt("evt_slow","payment_intent.processing"));inbox.insertReference(receipt("evt_next","payment_intent.processing"));
        identity();http.enqueue("GET","/v1/payment_intents/pi_owned",200,intent("processing",null,2000),Map.of(),1500);
        snapshot("processing",null);
        var scheduler=new dev.genericpayments.platform.payments.adapter.in.configuration.ProviderInboxScheduler(context.getBeanProvider(ProviderInboxWorker.class),context.getBeanProvider(ProviderReferenceInboxWorker.class),context.getBean(dev.genericpayments.platform.provider.adapter.out.stripe.StripeSandboxProperties.class),100);
        var shared=context.getBean(org.springframework.scheduling.TaskScheduler.class);
        // This is the application's actual default scheduler, which must remain available during slow Stripe I/O.
        assertThat(shared).isInstanceOf(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);
        var completed=new java.util.concurrent.CountDownLatch(1);
        try {
            scheduler.startReferences();scheduler.startReferences();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> http.calls().stream().anyMatch(c -> c.path().equals("/v1/payment_intents/pi_owned")&&c.method().equals("GET")));
            shared.schedule(() -> {scheduler.run();completed.countDown();},Instant.now());
            assertThat(completed.await(500,java.util.concurrent.TimeUnit.MILLISECONDS)).isTrue();
            assertThat(jdbc.queryForList("select status from provider_inbox order by received_at",String.class)).containsExactly("PROCESSING","PENDING");
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> assertThat(jdbc.queryForList("select status from provider_inbox",String.class)).containsOnly("PROCESSED"));
            assertThat(http.calls().stream().filter(c -> c.method().equals("GET")&&c.path().equals("/v1/payment_intents/pi_owned"))).hasSize(2);
        } finally {scheduler.stopReferences();}
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive()&&t.getName().equals("provider-reference-inbox")));
    }
    @Test void stripeDisabledSchedulerNeverClaimsReferenceWork() throws Exception {
        mapped();inbox.insertReference(receipt("evt_disabled","payment_intent.processing"));
        var scheduler=new dev.genericpayments.platform.payments.adapter.in.configuration.ProviderInboxScheduler(context.getBeanProvider(ProviderInboxWorker.class),context.getBeanProvider(ProviderReferenceInboxWorker.class),new dev.genericpayments.platform.provider.adapter.out.stripe.StripeSandboxProperties(false,"","",""),100);
        try {scheduler.startReferences();org.awaitility.Awaitility.await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(inboxStatus()).isEqualTo("PENDING"));}
        finally {scheduler.stopReferences();}
    }
    @Test void schedulerSurvivesAcquisitionFailureAndContinuesLaterTicks(){
        mapped();inbox.insertReference(receipt("evt_afterfailure","payment_intent.processing"));
        jdbc.execute("create function task9_reject_claim() returns trigger language plpgsql as $$ begin if new.status='PROCESSING' then raise exception 'injected claim failure' using errcode='23514'; end if; return new; end; $$");
        jdbc.execute("create trigger task9_reject_claim before update on provider_inbox for each row execute function task9_reject_claim()");
        var scheduler=new dev.genericpayments.platform.payments.adapter.in.configuration.ProviderInboxScheduler(context.getBeanProvider(ProviderInboxWorker.class),context.getBeanProvider(ProviderReferenceInboxWorker.class),context.getBean(dev.genericpayments.platform.provider.adapter.out.stripe.StripeSandboxProperties.class),100);
        try {
            scheduler.startReferences();
            org.awaitility.Awaitility.await().during(Duration.ofMillis(350)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(inboxStatus()).isEqualTo("PENDING"));
            snapshot("processing",null);jdbc.execute("drop trigger task9_reject_claim on provider_inbox");
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(inboxStatus()).isEqualTo("PROCESSED"));
        } finally {scheduler.stopReferences();jdbc.execute("drop trigger if exists task9_reject_claim on provider_inbox");jdbc.execute("drop function task9_reject_claim()");}
    }
    @Test void unsupportedSnapshotsRetryWithOriginalKeyUntilDeadWithoutConsumingCharge(){
        mapped();var originalReceipt=jdbc.queryForMap("select * from provider_operations");inbox.insertReference(receipt("evt_unsupported","payment_intent.processing"));
        for(int i=1;i<=12;i++) {
            snapshot("requires_capture",null);worker.runOnce();
            assertThat(inboxStatus()).isEqualTo(i==12?"DEAD":"FAILED");
            assertThat(jdbc.queryForObject("select attempt_count from provider_inbox",Integer.class)).isEqualTo(i);
            if(i<12)jdbc.update("update provider_inbox set next_attempt_at=clock_timestamp()");
        }
        worker.runOnce();assertThat(jdbc.queryForMap("select * from provider_operations")).isEqualTo(originalReceipt);
        assertThat(count("provider_charge_receipts")).isZero();assertThat(count("payment_events")).isZero();assertThat(count("payment_attempts")).isZero();
    }
    @Test void refundReceiptRemainsUnresolvedUntilRefundEvidenceExists(){
        mapped();int before=http.calls().size();inbox.insertReference(new ProviderReferenceReceipt(PaymentProviderType.STRIPE,account.id(),"evt_refund","re_owned",ProviderReferenceReceipt.ObjectType.REFUND,"refund.updated","2026-09-30.endive",INITIAL_TIME,Instant.now()));worker.runOnce();
        assertThat(inboxStatus()).isEqualTo("FAILED");assertThat(current().status()).isEqualTo(PaymentStatus.CREATED);assertThat(count("payment_events")).isZero();assertThat(http.calls()).hasSize(before);
    }
    @Test void unverifiedStripeRefundReconciliationCannotInventRefundMoney(){
        mapped();original=save.save(original.succeeded(INITIAL_TIME));var id=new RefundId(UUID.randomUUID());
        var refund=Refund.request(id,original,original.money(),"refund_"+id.value(),null,null,INITIAL_TIME).withProviderRefundReference("re_owned",INITIAL_TIME);
        saveRefunds.insert(refund);var lease=transitions.acquire(site,original.id(),"refund-unverified",Duration.ofSeconds(30));
        assertThatThrownBy(() -> refundTransitions.reconcile(lease,id,new ProviderState("re_owned",ProviderState.Status.SUCCEEDED),"refund-unverified")).isInstanceOf(RuntimeException.class);
        assertThat(current().status()).isEqualTo(PaymentStatus.SUCCEEDED);assertThat(current().refundedAmountMinor()).isZero();
        assertThat(refunds.findById(site,id).orElseThrow().value().status()).isEqualTo(RefundStatus.PENDING);assertThat(count("payment_events")).isZero();
    }
}
