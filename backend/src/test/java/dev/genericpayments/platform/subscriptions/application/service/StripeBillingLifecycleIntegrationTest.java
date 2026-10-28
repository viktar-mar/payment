package dev.genericpayments.platform.subscriptions.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.ProviderReferenceReceipt;
import dev.genericpayments.platform.payments.application.port.out.ProviderInboxPort;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import dev.genericpayments.platform.webhooks.application.service.SubscriptionInboxWorker;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.convention.TestBean;

/** HOSTED ONLY: real inbox/facts/cash/SQL/outbox; provider observations are explicit synthetic snapshots.
 * Fresh Stripe graph/ownership proof is covered separately by owned-loopback adapter/mapper tests. */
class StripeBillingLifecycleIntegrationTest extends SubscriptionFactFixture {
    @Autowired ProviderInboxPort references;
    @Autowired SubscriptionInboxPort inbox;
    @Autowired SubscriptionInboxWorker worker;
    @Autowired SubscriptionObservationPort observations;
    @Autowired SubscriptionCatalogPort catalog;
    @Autowired CallbackConfigurationPort callbacks;
    @Autowired OutboxPort outbox;
    @MockitoBean SubscriptionProviderRegistry registry;
    @MockitoBean SubscriptionInboxResolutionPort resolution;
    @TestBean(name="providerReferenceClock",methodName="referenceClock") Clock referenceClock;
    static final AtomicReference<Instant> referenceTime=new AtomicReference<>(INITIAL_TIME);
    static final AtomicReference<Supplier<Instant>> clockReads=new AtomicReference<>(referenceTime::get);
    static Clock referenceClock(){return new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return clockReads.get().get();}};}
    final SubscriptionProviderPort provider=mock(SubscriptionProviderPort.class);
    Subscription original;
    Subscription.Period period;
    @BeforeEach void stripeFixture(){
        referenceTime.set(INITIAL_TIME);
        clockReads.set(referenceTime::get);
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_fixture',credentials_ref='stripe-sandbox-default' where id=?",account.id().value());
        account=accounts.findById(account.id()).orElseThrow();var terms=catalog.terms(site,price);
        period=new Subscription.Period(INITIAL_TIME.minusSeconds(10),INITIAL_TIME.plusSeconds(86400));
        original=tx.required(()->subscriptions.save(new Subscription(UUID.randomUUID(),site,customer.id(),terms.productId(),account.id(),terms,null,Subscription.Status.INCOMPLETE,"sub_fixture",null,null,null,null,null,null,null,0,INITIAL_TIME,INITIAL_TIME,null),-1));
        when(registry.forAccount(any())).thenReturn(provider);when(resolution.resolve(any())).thenReturn(Optional.of(original.id()));
    }
    Invoice invoice(String ref,Subscription.Period p,SettlementKind settlement,String pi){return new Invoice(ref,BillingRecord.Kind.RENEWAL,original.billingPrice(),null,p,settlement==SettlementKind.ZERO?0:2000,"EUR",settlement,settlement==SettlementKind.CASH?List.of(new CashObject(pi,2000,"EUR",PaymentStatus.SUCCEEDED,INITIAL_TIME)):List.of());}
    Snapshot snapshot(Subscription.Status status,Subscription.Period p,Invoice... invoices){return new Snapshot("sub_fixture",status,original.billingPrice(),p,null,null,List.of(invoices),null,false,0,INITIAL_TIME);}
    Snapshot paid(){return snapshot(Subscription.Status.ACTIVE,period,invoice("in_initial",period,SettlementKind.CASH,"pi_initial"));}
    void apply(Snapshot snapshot){var s=sub(original.id());long sequence=observations.captureFence(s);tx.required(()->{facts.apply(new ApplySubscriptionSnapshotUseCase.Command(account,s.id(),snapshot,SubscriptionEvent.Source.RECONCILIATION,UUID.randomUUID().toString(),"task6",new ApplySubscriptionSnapshotUseCase.Fence(s.revision(),null,null,null,null,sequence)));return null;});}
    void hint(String id,Snapshot fresh){when(provider.retrieve(any())).thenReturn(fresh);assertThat(inbox.insert(new SubscriptionProviderEnvelope(account.provider(),account.id(),id,"sub_fixture","SUBSCRIPTION_CHANGED",INITIAL_TIME,INITIAL_TIME))).isTrue();worker.runOnce();}
    ProviderReferenceReceipt receipt(String event){return new ProviderReferenceReceipt(PaymentProviderType.STRIPE,account.id(),event,"pi_initial",ProviderReferenceReceipt.ObjectType.PAYMENT,"payment_intent.succeeded","2026-09-30.endive",INITIAL_TIME.minusSeconds(1),INITIAL_TIME);}
    ProviderInboxPort.Claim claim(String event){references.insertReference(receipt(event));return references.claimReference(Duration.ofSeconds(30)).orElseThrow();}
    int financialCount(){return count("payments")+count("payment_events")+count("subscription_events")+count("outbox_events");}
    @Test void initialCashAndDuplicateOutOfOrderHintsUseOneExistingFinancialSource(){
        callbacks.configure(site.value(),"https://example.com/callback",true);hint("evt_paid",paid());var before=sub(original.id());int count=financialCount();hint("evt_old_failed",paid());hint("evt_old_processing",paid());
        assertThat(sub(original.id())).isEqualTo(before);assertThat(financialCount()).isEqualTo(count);assertThat(count("payments")).isEqualTo(1);assertThat(jdbc.queryForObject("select source from payments",String.class)).isEqualTo("SUBSCRIPTION");assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='PAYMENT_PREPARE'",Integer.class)).isZero();assertThat(before.paidThrough()).isEqualTo(period.end());
    }
    @Test void renewalAddsDistinctCashAndAdvancesPaidThrough(){apply(paid());var next=new Subscription.Period(period.end(),period.end().plusSeconds(86400));TestClock.set(next.start());hint("evt_renewal",snapshot(Subscription.Status.ACTIVE,next,invoice("in_renewal",next,SettlementKind.CASH,"pi_renewal")));assertThat(count("payments")).isEqualTo(2);assertThat(sub(original.id()).paidThrough()).isEqualTo(next.end());}
    @ParameterizedTest @ValueSource(strings={"UNSUPPORTED","ZERO"})
    void signedPaidWithoutCapturedCashCannotGrantPositiveEntitlement(String kind){hint("evt_unproven",snapshot(Subscription.Status.ACTIVE,period,invoice("in_unproven",period,SettlementKind.valueOf(kind),null)));assertThat(count("payments")).isZero();assertThat(sub(original.id()).paidThrough()).isNull();}
    @Test void repeatedFailedRenewalDoesNotExtendCapturedGrace(){apply(paid());var next=new Subscription.Period(period.end(),period.end().plusSeconds(86400));TestClock.set(next.start());var failed=snapshot(Subscription.Status.PAST_DUE,next,invoice("in_failed",next,SettlementKind.OPEN,null));hint("evt_failed1",failed);var debt=sub(original.id()).debt();assertThat(debt).isNotNull();TestClock.set(next.start().plusSeconds(100));hint("evt_failed2",failed);assertThat(sub(original.id()).debt()).isEqualTo(debt);assertThat(sub(original.id()).paidThrough()).isEqualTo(period.end());}
    @ParameterizedTest @ValueSource(strings={"PARTIALLY_REFUNDED","REFUNDED"})
    void historicalGrossCashPromotionPreservesRefundAndFutureRenewal(String status){apply(paid());jdbc.update("update payments set status=?,refunded_amount_minor=? where provider_payment_reference='pi_initial'",status,status.equals("REFUNDED")?2000:500);var claim=claim("evt_refunded");assertThat(references.promoteBillingPaymentReference(claim,receipt("evt_refunded"))).isTrue();when(provider.retrieve(any())).thenReturn(paid());worker.runOnce();assertThat(jdbc.queryForObject("select status from payments where provider_payment_reference='pi_initial'",String.class)).isEqualTo(status);var next=new Subscription.Period(period.end(),period.end().plusSeconds(86400));TestClock.set(next.start());hint("evt_after_refund",snapshot(Subscription.Status.ACTIVE,next,invoice("in_next",next,SettlementKind.CASH,"pi_next")));assertThat(count("payments")).isEqualTo(2);assertThat(sub(original.id()).paidThrough()).isEqualTo(next.end());}
    @Test void piBeforeMappingRetriesThenPromotesSameRowWithOriginalProvenance(){
        var receipt=receipt("evt_before_mapping");var first=claim(receipt.eventId());assertThat(references.promoteBillingPaymentReference(first,receipt)).isFalse();assertThat(references.failReference(first,"UNRESOLVED_OBJECT")).isTrue();
        apply(paid());referenceTime.set(INITIAL_TIME.plusSeconds(5));var next=references.claimReference(Duration.ofSeconds(30)).orElseThrow();var before=jdbc.queryForMap("select * from provider_inbox where id=?",next.id());int financial=financialCount();
        assertThat(references.promoteBillingPaymentReference(next,receipt)).isTrue();var after=jdbc.queryForMap("select * from provider_inbox where id=?",next.id());
        for(String column:List.of("id","provider_type","payment_account_id","provider_event_id","occurred_at","received_at","payload_hash","safe_payload","attempt_count"))assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
        assertThat(after).containsEntry("event_family","SUBSCRIPTION").containsEntry("event_type","SUBSCRIPTION_CHANGED").containsEntry("provider_object_id","sub_fixture").containsEntry("status","PENDING");
        for(String column:List.of("reference_object_type","provider_event_type","provider_api_version","lease_owner","lease_expires_at","error_category"))assertThat(after.get(column)).as(column).isNull();
        assertThat(after.get("next_attempt_at")).isEqualTo(before.get("received_at"));assertThat(financialCount()).isEqualTo(financial);assertThat(after.get("attempt_count")).isEqualTo(2);
        when(provider.retrieve(any())).thenReturn(paid());worker.runOnce();assertThat(jdbc.queryForObject("select status from provider_inbox where id=?",String.class,next.id())).isEqualTo("PROCESSED");assertThat(count("payments")).isEqualTo(1);
    }
    @Test void exactReceiptOwnerAndLeaseFencePromotion(){apply(paid());var r=receipt("evt_fenced");var c=claim(r.eventId());assertThatThrownBy(()->references.promoteBillingPaymentReference(new ProviderInboxPort.Claim(c.id(),"foreign"),r)).hasStackTraceContaining("CLAIM_LOST");var changed=new ProviderReferenceReceipt(r.provider(),r.accountId(),r.eventId(),"pi_foreign",r.objectType(),r.providerEventType(),r.apiVersion(),r.occurredAt(),r.receivedAt());assertThatThrownBy(()->references.promoteBillingPaymentReference(c,changed)).hasStackTraceContaining("CLAIM_LOST");referenceTime.set(INITIAL_TIME.plusSeconds(30));assertThatThrownBy(()->references.promoteBillingPaymentReference(c,r)).hasStackTraceContaining("CLAIM_LOST");assertThat(jdbc.queryForObject("select event_family from provider_inbox",String.class)).isEqualTo("PROVIDER_REFERENCE");}
    @Test void promotionCannotCreateMissingRootProof(){apply(paid());jdbc.update("delete from subscription_provider_objects where object_type='SUBSCRIPTION'");var c=claim("evt_no_root");assertThat(references.promoteBillingPaymentReference(c,receipt("evt_no_root"))).isFalse();assertThat(references.reference(c)).isPresent();}
    @Test void originalAccountRemainsRoutingIdentityAfterSiteSwitch(){apply(paid());var aid=UUID.randomUUID();jdbc.update("insert into payment_accounts values (?,'other-account','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",aid);jdbc.update("update sites set payment_account_id=? where id=?",aid,site.value());var c=claim("evt_original_account");assertThat(references.promoteBillingPaymentReference(c,receipt("evt_original_account"))).isTrue();}
    @Test void twelfthLiveAttemptPromotesAndSubscriptionClaimRetainsCumulativeHistory(){apply(paid());var c=claim("evt_twelfth");jdbc.update("update provider_inbox set attempt_count=12 where id=?",c.id());assertThat(references.promoteBillingPaymentReference(c,receipt("evt_twelfth"))).isTrue();var claims=inbox.claim("subscription",1,INITIAL_TIME,Duration.ofSeconds(30));assertThat(claims).hasSize(1);assertThat(jdbc.queryForObject("select attempt_count from provider_inbox",Integer.class)).isEqualTo(13);}
    @Test void staleReferenceCompletionAndFailureCannotMutatePromotedRow(){apply(paid());var c=claim("evt_stale");assertThat(references.promoteBillingPaymentReference(c,receipt("evt_stale"))).isTrue();assertThat(references.failReference(c,"PROCESSING_FAILED")).isFalse();assertThatThrownBy(()->tx.required(()->{references.completeReference(c);return null;})).hasStackTraceContaining("CLAIM_LOST");assertThat(jdbc.queryForObject("select status from provider_inbox",String.class)).isEqualTo("PENDING");}
    @Test void crossFamilyDuplicateNeverOverwritesOriginalEventOrAttempts(){apply(paid());var r=receipt("evt_duplicate");var c=claim(r.eventId());assertThat(inbox.insert(new SubscriptionProviderEnvelope(r.provider(),r.accountId(),r.eventId(),"sub_foreign","SUBSCRIPTION_CHANGED",INITIAL_TIME.plusSeconds(5),INITIAL_TIME.plusSeconds(5)))).isFalse();assertThat(references.promoteBillingPaymentReference(c,r)).isTrue();assertThat(references.insertReference(r)).isFalse();assertThat(inbox.insert(new SubscriptionProviderEnvelope(r.provider(),r.accountId(),r.eventId(),"sub_foreign","SUBSCRIPTION_CHANGED",INITIAL_TIME,INITIAL_TIME))).isFalse();assertThat(count("provider_inbox")).isEqualTo(1);assertThat(jdbc.queryForObject("select provider_object_id from provider_inbox",String.class)).isEqualTo("sub_fixture");}
    @Test void deadReferenceCannotBeRevivedByLateMapping(){apply(paid());var c=claim("evt_dead");jdbc.update("update provider_inbox set attempt_count=12 where id=?",c.id());assertThat(references.failReference(c,"UNRESOLVED_OBJECT")).isTrue();assertThatThrownBy(()->references.promoteBillingPaymentReference(c,receipt("evt_dead"))).hasStackTraceContaining("CLAIM_LOST");assertThat(jdbc.queryForObject("select status from provider_inbox",String.class)).isEqualTo("DEAD");}
    @Test void promotionRollbackRestoresOriginalClaimAndProvenance(){apply(paid());var c=claim("evt_rollback");var before=jdbc.queryForMap("select * from provider_inbox");jdbc.execute("create function task6_fail_promotion() returns trigger language plpgsql as $$ begin if new.event_family='SUBSCRIPTION' then raise exception 'injected-promotion-failure'; end if; return new; end; $$");jdbc.execute("create trigger task6_fail_promotion after update on provider_inbox for each row execute function task6_fail_promotion()");try{assertThatThrownBy(()->references.promoteBillingPaymentReference(c,receipt("evt_rollback"))).isInstanceOf(RuntimeException.class);assertThat(jdbc.queryForMap("select * from provider_inbox")).isEqualTo(before);assertThat(references.reference(c)).isPresent();}finally{jdbc.execute("drop trigger task6_fail_promotion on provider_inbox");jdbc.execute("drop function task6_fail_promotion()");}}
    @Test void callbackFailureRollsBackInboxFactsCashJournalAndOutbox(){callbacks.configure(site.value(),"https://example.com/callback",true);jdbc.execute("create function task6_fail_callback() returns trigger language plpgsql as $$ begin raise exception 'injected-callback-failure'; end; $$");jdbc.execute("create trigger task6_fail_callback before insert on outbox_events for each row execute function task6_fail_callback()");try{hint("evt_atomic",paid());for(String table:List.of("payments","payment_events","subscription_events","outbox_events","subscription_billing_records","subscription_provider_objects"))assertThat(count(table)).as(table).isZero();assertThat(sub(original.id())).isEqualTo(original);assertThat(jdbc.queryForObject("select status from provider_inbox",String.class)).isEqualTo("FAILED");}finally{jdbc.execute("drop trigger task6_fail_callback on outbox_events");jdbc.execute("drop function task6_fail_callback()");}}
    @Test void samePaymentCannotBeReattachedToAnotherInvoice(){apply(paid());int before=financialCount();var next=new Subscription.Period(period.end(),period.end().plusSeconds(86400));assertThatThrownBy(()->apply(snapshot(Subscription.Status.ACTIVE,next,invoice("in_wrong_cash",next,SettlementKind.CASH,"pi_initial")))).isInstanceOf(RuntimeException.class);assertThat(financialCount()).isEqualTo(before);assertThat(count("payments")).isEqualTo(1);assertThat(sub(original.id()).paidThrough()).isEqualTo(period.end());}

    @ParameterizedTest @ValueSource(ints={2,3})
    void crossingOriginalLeaseBeforeOrAfterUpdateRollsBackPromotion(int expireAtRead){
        apply(paid());var c=claim("evt_cross_expiry");var before=jdbc.queryForMap("select * from provider_inbox");
        var reads=new AtomicInteger();clockReads.set(()->reads.incrementAndGet()>=expireAtRead?INITIAL_TIME.plusSeconds(30):INITIAL_TIME);
        try{assertThatThrownBy(()->references.promoteBillingPaymentReference(c,receipt("evt_cross_expiry"))).hasStackTraceContaining("CLAIM_LOST");assertThat(jdbc.queryForMap("select * from provider_inbox")).isEqualTo(before);}
        finally{clockReads.set(referenceTime::get);}
    }
    @Test void missingProofCannotResumeOriginalResolutionAfterItsClaimExpires(){
        var c=claim("evt_unmapped_expiry");var before=jdbc.queryForMap("select * from provider_inbox");var reads=new AtomicInteger();
        clockReads.set(()->reads.incrementAndGet()>1?INITIAL_TIME.plusSeconds(30):INITIAL_TIME);
        try{assertThatThrownBy(()->references.promoteBillingPaymentReference(c,receipt("evt_unmapped_expiry"))).hasStackTraceContaining("CLAIM_LOST");assertThat(jdbc.queryForMap("select * from provider_inbox")).isEqualTo(before);}
        finally{clockReads.set(referenceTime::get);}
    }
    @Test void timeIsReadAfterWaitingForInboxRowLock()throws Exception{
        apply(paid());var c=claim("evt_lock_expiry");var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var starting=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var holder=pool.submit(()->tx.required(()->{jdbc.queryForObject("select id from provider_inbox where id=? for update",UUID.class,c.id());locked.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("lock holder timed out");}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            var waiting=pool.submit(()->{starting.countDown();return catchThrowable(()->references.promoteBillingPaymentReference(c,receipt("evt_lock_expiry")));});
            assertThat(starting.await(5,TimeUnit.SECONDS)).isTrue();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
            while(System.nanoTime()<until){blocked=jdbc.queryForObject("select exists(select 1 from pg_stat_activity where wait_event_type='Lock' and query like '%provider_inbox%' and pid<>pg_backend_pid())",Boolean.class);if(blocked)break;Thread.sleep(10);}
            assertThat(blocked).isTrue();referenceTime.set(INITIAL_TIME.plusSeconds(30));release.countDown();holder.get(5,TimeUnit.SECONDS);
            assertThat(waiting.get(5,TimeUnit.SECONDS)).hasStackTraceContaining("CLAIM_LOST");assertThat(jdbc.queryForObject("select event_family from provider_inbox",String.class)).isEqualTo("PROVIDER_REFERENCE");
        }finally{release.countDown();}
    }
    @Test void twoContendersCanPromoteExactlyOnce()throws Exception{
        apply(paid());var c=claim("evt_contenders");var barrier=new CyclicBarrier(2);int before=financialCount();
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Boolean> contender=()->{barrier.await(5,TimeUnit.SECONDS);try{return references.promoteBillingPaymentReference(c,receipt("evt_contenders"));}catch(RuntimeException lost){assertThat(lost).hasStackTraceContaining("CLAIM_LOST");return false;}};
            var one=pool.submit(contender);var two=pool.submit(contender);assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(count("provider_inbox")).isEqualTo(1);assertThat(financialCount()).isEqualTo(before);assertThat(jdbc.queryForObject("select status from provider_inbox",String.class)).isEqualTo("PENDING");
    }
    @Test void reclaimedOwnerFencesDelayedPromoter(){apply(paid());var old=claim("evt_reclaimed");referenceTime.set(INITIAL_TIME.plusSeconds(30));var fresh=references.claimReference(Duration.ofSeconds(30)).orElseThrow();assertThat(fresh.id()).isEqualTo(old.id());assertThat(fresh.owner()).isNotEqualTo(old.owner());assertThatThrownBy(()->references.promoteBillingPaymentReference(old,receipt("evt_reclaimed"))).hasStackTraceContaining("CLAIM_LOST");assertThat(references.promoteBillingPaymentReference(fresh,receipt("evt_reclaimed"))).isTrue();}
    @Test void onlyCommittedCompleteCashGraphCanPromote()throws Exception{
        var c=claim("evt_graph_commit");var applied=new CountDownLatch(1);var release=new CountDownLatch(1);var s=sub(original.id());long sequence=observations.captureFence(s);
        try(var pool=Executors.newSingleThreadExecutor()){
            var writer=pool.submit(()->tx.required(()->{facts.apply(new ApplySubscriptionSnapshotUseCase.Command(account,s.id(),paid(),SubscriptionEvent.Source.RECONCILIATION,"graph-commit","task6",new ApplySubscriptionSnapshotUseCase.Fence(s.revision(),null,null,null,null,sequence)));applied.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("facts commit timed out");}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));
            assertThat(applied.await(5,TimeUnit.SECONDS)).isTrue();assertThat(references.promoteBillingPaymentReference(c,receipt("evt_graph_commit"))).isFalse();release.countDown();writer.get(5,TimeUnit.SECONDS);assertThat(references.promoteBillingPaymentReference(c,receipt("evt_graph_commit"))).isTrue();
        }finally{release.countDown();}
        assertThat(count("payments")).isEqualTo(1);
    }
    @Test void concurrentReferenceAndDirectHintAdmissionKeepsOneDurableEvent()throws Exception{
        apply(paid());var r=receipt("evt_admission_race");var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)){
            var reference=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return references.insertReference(r);});
            var direct=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return inbox.insert(new SubscriptionProviderEnvelope(r.provider(),r.accountId(),r.eventId(),"sub_fixture","SUBSCRIPTION_CHANGED",r.occurredAt(),r.receivedAt()));});
            assertThat(List.of(reference.get(10,TimeUnit.SECONDS),direct.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        var claim=references.claimReference(Duration.ofSeconds(30));if(claim.isPresent())assertThat(references.promoteBillingPaymentReference(claim.get(),r)).isTrue();
        when(provider.retrieve(any())).thenReturn(paid());worker.runOnce();assertThat(count("provider_inbox")).isEqualTo(1);assertThat(count("payments")).isEqualTo(1);assertThat(jdbc.queryForObject("select status from provider_inbox",String.class)).isEqualTo("PROCESSED");
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","FAILED","PROCESSED"})
    void unownedOrCompletedRowsCannotPromote(String status){apply(paid());var c=claim("evt_ineligible");jdbc.update("update provider_inbox set status=?,lease_owner=null,lease_expires_at=null where id=?",status,c.id());assertThatThrownBy(()->references.promoteBillingPaymentReference(c,receipt("evt_ineligible"))).hasStackTraceContaining("CLAIM_LOST");assertThat(jdbc.queryForObject("select event_family from provider_inbox",String.class)).isEqualTo("PROVIDER_REFERENCE");}
    @Test void refundReferenceCannotPromoteEvenWithHistoricalBillingCash(){apply(paid());var r=new ProviderReferenceReceipt(account.provider(),account.id(),"evt_refund","re_owned",ProviderReferenceReceipt.ObjectType.REFUND,"refund.created","2026-09-30.endive",INITIAL_TIME,INITIAL_TIME);references.insertReference(r);var c=references.claimReference(Duration.ofSeconds(30)).orElseThrow();var before=jdbc.queryForMap("select * from provider_inbox");assertThat(references.promoteBillingPaymentReference(c,r)).isFalse();assertThat(jdbc.queryForMap("select * from provider_inbox")).isEqualTo(before);}
    @Test void missingPaymentMappingAndForeignOriginalAccountRefuseWithoutFinancialWrites(){apply(paid());jdbc.update("delete from subscription_provider_objects where object_type='PAYMENT'");var c=claim("evt_no_payment");int before=financialCount();assertThat(references.promoteBillingPaymentReference(c,receipt("evt_no_payment"))).isFalse();assertThat(financialCount()).isEqualTo(before);assertThat(references.failReference(c,"UNRESOLVED_OBJECT")).isTrue();var id=UUID.randomUUID();jdbc.update("insert into payment_accounts values (?,'foreign','STRIPE','SANDBOX','ACTIVE','acct_foreign','stripe-sandbox-default',now(),0)",id);var r=new ProviderReferenceReceipt(account.provider(),new PaymentAccountId(id),"evt_foreign","pi_initial",ProviderReferenceReceipt.ObjectType.PAYMENT,"payment_intent.succeeded","2026-09-30.endive",INITIAL_TIME,INITIAL_TIME);references.insertReference(r);var foreign=references.claimReference(Duration.ofSeconds(30)).orElseThrow();assertThat(references.promoteBillingPaymentReference(foreign,r)).isFalse();}
    @Test void foreignAccountCannotBorrowACompleteCommittedPaymentMapping(){apply(paid());var id=UUID.randomUUID();jdbc.update("insert into payment_accounts values (?,'foreign-complete','STRIPE','SANDBOX','ACTIVE','acct_foreign','stripe-sandbox-default',now(),0)",id);var r=new ProviderReferenceReceipt(account.provider(),new PaymentAccountId(id),"evt_foreign_complete","pi_initial",ProviderReferenceReceipt.ObjectType.PAYMENT,"payment_intent.succeeded","2026-09-30.endive",INITIAL_TIME,INITIAL_TIME);references.insertReference(r);var claim=references.claimReference(Duration.ofSeconds(30)).orElseThrow();int before=financialCount();assertThat(references.promoteBillingPaymentReference(claim,r)).isFalse();assertThat(financialCount()).isEqualTo(before);assertThat(references.reference(claim)).contains(r);}
    @ParameterizedTest @ValueSource(strings={"payment_id=null","billing_record_id=null","object_type='INVOICE'","provider_reference='pi_foreign'","payment_account_id=gen_random_uuid()","site_id=gen_random_uuid()","subscription_id=gen_random_uuid()"})
    void immutableMappingSchemaRejectsUnreachablePromotionCandidates(String mutation){apply(paid());assertThatThrownBy(()->jdbc.update("update subscription_provider_objects set "+mutation+" where object_type='PAYMENT'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);var c=claim("evt_immutable");assertThat(references.promoteBillingPaymentReference(c,receipt("evt_immutable"))).isTrue();}
    @Test void signedCallbacksAreDeliveredFromTheSameCommittedCashFacts(){
        callbacks.configure(site.value(),"https://example.com/callback",true);hint("evt_delivery",paid());var claims=outbox.claimAutomatic("task6-delivery",20,INITIAL_TIME,Duration.ofSeconds(30));assertThat(claims).isNotEmpty();var delivered=new AtomicInteger();
        for(var claim:claims){byte[] secret=Base64.getUrlDecoder().decode(encryption.decrypt(claim.secret()));try{var delivery=new dev.genericpayments.platform.webhooks.application.service.CallbackDeliveryService(outbox,encryption,payload,(endpoint,headers,body)->{long at=Long.parseLong(headers.get("Payment-Timestamp"));assertThat(headers.get("Payment-Signature")).isEqualTo("v1="+new dev.genericpayments.platform.webhooks.application.service.CallbackSigner().sign(secret,at,body));assertThat(new String(body,java.nio.charset.StandardCharsets.UTF_8)).contains(original.id().toString()).doesNotContain("client_secret");delivered.incrementAndGet();return CallbackHttpPort.Result.success(204);},Clock.fixed(INITIAL_TIME,ZoneOffset.UTC));delivery.deliver(claim);}finally{Arrays.fill(secret,(byte)0);}}
        assertThat(delivered.get()).isEqualTo(claims.size());assertThat(jdbc.queryForList("select status from outbox_events",String.class)).containsOnly("DELIVERED");assertThat(count("payments")).isEqualTo(1);
    }
    @Autowired dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort encryption;
    @Autowired CallbackPayloadPort payload;
    @Autowired dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore providerSteps;
    @Autowired dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort bindings;
    @Autowired dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort mappings;
    @Autowired dev.genericpayments.platform.sites.application.port.out.LoadSitePort siteLoader;
    @Autowired dev.genericpayments.platform.shared.application.port.out.DigestPort digest;
    @Autowired SubscriptionObjectPort objects;
    @Autowired SubscriptionTelemetryPort telemetry;
    @Autowired SubscriptionReadPreparation readPreparation;
    @Autowired Clock businessClock;

    @Test void realStripeSdkLoopbackInitialCreateThenInvoiceInboxSettlesAndDeliversSignedCallbacks()throws Exception{
        // Real SQL ports and original paid CREATE orchestration; only the remote HTTP peer is synthetic.
        var live=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);TestClock.set(live);
        actor=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site,customer.id(),"https://facts.test").rawToken(),"https://facts.test"));
        period=new Subscription.Period(live.minusSeconds(2),live.plusSeconds(86400));
        jdbc.update("delete from subscriptions where id=?",original.id());
        var terms=catalog.terms(site,price);
        mappings.save(new dev.genericpayments.platform.catalog.application.model.ProviderCatalogMapping(account.id(),terms.productId(),new dev.genericpayments.platform.catalog.application.model.ProviderProductRef("prod_owned"),price,new dev.genericpayments.platform.catalog.application.model.ProviderPriceRef("price_owned"),live));
        var reserved=providerSteps.reserve(account,dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind.CUSTOMER_CREATE,customer.id().value(),"task6:customer:"+customer.id().value(),"0".repeat(64));
        var customerClaim=providerSteps.claim(reserved.id(),"task6-customer",Instant.now(),Duration.ofSeconds(60)).orElseThrow();
        var acknowledged=providerSteps.acknowledge(customerClaim.id(),customerClaim.leaseOwner(),customerClaim.version(),"cus_owned");
        bindings.bindCustomer(account.id(),site,customer.id(),"cus_owned",acknowledged.id());
        var graph=connectedGraph(terms,period);
        try(var http=new dev.genericpayments.platform.support.StripeHttpFixture()){
            var credentials=new dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver(new dev.genericpayments.platform.provider.adapter.out.stripe.StripeSandboxProperties(true,"sk_test_"+"A".repeat(32),"pk_test_"+"B".repeat(32),"whsec_"+"C".repeat(32)));
            var factory=new dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory(){
                @Override protected com.stripe.StripeClient.StripeClientBuilder builder(dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentials c){return super.builder(c).setApiBase(http.baseUri().toString());}
            };
            var verification=new dev.genericpayments.platform.provider.adapter.out.stripe.StripeAccountVerificationAdapter(credentials,factory);
            var customerAdapter=new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingCustomerAdapter(credentials,factory,providerSteps,bindings,digest,verification,accounts,siteLoader,customers);
            var realProvider=new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeSubscriptionProviderAdapter(credentials,factory,verification,providerSteps,bindings,customerAdapter,digest,accounts,siteLoader,customers,subscriptions,operations,catalog,mappings,businessClock);
            var ownership=new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingOwnership(credentials,providerSteps,bindings,digest,accounts,siteLoader,customers,subscriptions,operations,catalog,mappings);
            var realResolution=new dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingInboxAdapter(accounts,objects,ownership,bindings,operations,credentials,factory,verification,realProvider,commands);
            callbacks.configure(site.value(),"https://example.com/callback",true);
            http.enqueue("GET","/v1/account",200,"{\"id\":\"acct_fixture\",\"object\":\"account\"}");
            http.enqueue("POST","/v1/subscriptions",200,call->{
                var remote=graph.get("subscription");remote.addProperty("created",Instant.now().getEpochSecond());
                var metadata=new com.google.gson.JsonObject();for(String key:List.of("account_id","site_id","customer_id","subscription_id","operation_id"))metadata.addProperty(key,call.form().get("metadata["+key+"]"));
                remote.add("metadata",metadata);remote.addProperty("status","incomplete");return remote.toString();
            });
            var result=new CreateSubscriptionService(commands,a->realProvider).create(command("connected-initial"));
            original=sub(result.subscriptionId());assertThat(original.providerReference()).isEqualTo("sub_fixture");assertThat(original.paidThrough()).isNull();assertThat(count("payments")).isZero();
            graph.get("subscription").addProperty("status","active");
            // Fresh resolver invoice parent read precedes the normal retrieve/fence/facts pipeline.
            http.enqueue("GET","/v1/account",200,"{\"id\":\"acct_fixture\",\"object\":\"account\"}");http.enqueue("GET","/v1/invoices/in_initial",200,graph.get("invoice").toString());
            http.enqueue("GET","/v1/account",200,"{\"id\":\"acct_fixture\",\"object\":\"account\"}");
            http.enqueue("GET","/v1/customers/cus_owned",200,graph.get("customer").toString());http.enqueue("GET","/v1/subscriptions/sub_fixture",200,graph.get("subscription").toString());
            http.enqueue("GET","/v1/invoices",200,page(graph.get("invoice")));http.enqueue("GET","/v1/invoices/in_initial/lines",200,page(graph.get("line")));
            http.enqueue("GET","/v1/invoice_payments",200,page(graph.get("allocation")));http.enqueue("GET","/v1/payment_intents/pi_initial",200,graph.get("intent").toString());http.enqueue("GET","/v1/charges/ch_initial",200,graph.get("charge").toString());http.enqueue("GET","/v1/invoice_payments",200,page(graph.get("allocation")));
            inbox.insert(new SubscriptionProviderEnvelope(account.provider(),account.id(),"evt_connected","in_initial","SUBSCRIPTION_INVOICE_CHANGED",live,live));
            new SubscriptionInboxWorker(inbox,tx,objects,subscriptions,operations,accounts,a->realProvider,facts,businessClock,20,30,readPreparation,telemetry,observations,realResolution).runOnce();
            assertThat(jdbc.queryForObject("select status from provider_inbox where provider_event_id='evt_connected'",String.class)).isEqualTo("PROCESSED");assertThat(sub(original.id()).paidThrough()).isEqualTo(period.end());assertThat(op(result.operationId()).status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
            assertThat(count("payments")).isEqualTo(1);assertThat(jdbc.queryForObject("select source from payments",String.class)).isEqualTo("SUBSCRIPTION");assertThat(count("payment_events")).isPositive();assertThat(count("subscription_events")).isPositive();assertThat(jdbc.queryForObject("select count(*) from provider_operations where kind='PAYMENT_PREPARE'",Integer.class)).isZero();
            assertThat(http.calls().stream().filter(c->c.method().equals("POST"))).singleElement().satisfies(c->{assertThat(c.path()).isEqualTo("/v1/subscriptions");assertThat(c.header("Idempotency-Key")).isEqualTo(op(result.operationId()).providerOperationKey());});
            var claims=outbox.claimAutomatic("connected-delivery",20,live,Duration.ofSeconds(30));assertThat(claims).isNotEmpty();
            for(var claim:claims){byte[] secret=Base64.getUrlDecoder().decode(encryption.decrypt(claim.secret()));try{new dev.genericpayments.platform.webhooks.application.service.CallbackDeliveryService(outbox,encryption,payload,(endpoint,headers,body)->{long at=Long.parseLong(headers.get("Payment-Timestamp"));assertThat(headers.get("Payment-Signature")).isEqualTo("v1="+new dev.genericpayments.platform.webhooks.application.service.CallbackSigner().sign(secret,at,body));return CallbackHttpPort.Result.success(204);},Clock.fixed(live,ZoneOffset.UTC)).deliver(claim);}finally{Arrays.fill(secret,(byte)0);}}
            assertThat(jdbc.queryForList("select status from outbox_events",String.class)).containsOnly("DELIVERED");
        }
    }
    private static String page(com.google.gson.JsonObject object){return "{\"object\":\"list\",\"has_more\":false,\"data\":["+object+"]}";}
    private static Map<String,com.google.gson.JsonObject> connectedGraph(SubscriptionPriceTerms terms,Subscription.Period period){
        long start=period.start().getEpochSecond(),end=period.end().getEpochSecond();
        var graph=new HashMap<String,com.google.gson.JsonObject>();
        graph.put("customer",json("{\"id\":\"cus_owned\",\"object\":\"customer\",\"livemode\":false}"));
        graph.put("subscription",json("""
            {"id":"sub_fixture","object":"subscription","livemode":false,"customer":"cus_owned","status":"active","currency":"eur",
             "collection_method":"charge_automatically","billing_mode":{"type":"classic"},"payment_settings":{"payment_method_types":["card"],"save_default_payment_method":"on_subscription"},
             "items":{"object":"list","has_more":false,"data":[{"id":"si_owned","object":"subscription_item","subscription":"sub_fixture","quantity":1,
              "current_period_start":%d,"current_period_end":%d,"price":{"id":"price_owned","object":"price","livemode":false,"product":"prod_owned","currency":"eur","unit_amount":2000,
              "tax_behavior":"inclusive","type":"recurring","recurring":{"interval":"month","interval_count":1,"usage_type":"licensed"},"metadata":{"local_price_id":"%s","local_price_version":"1"}}}]}}
            """.formatted(start,end,terms.priceId().value())));
        graph.put("invoice",json("""
            {"id":"in_initial","object":"invoice","livemode":false,"customer":"cus_owned","currency":"eur","status":"paid","billing_reason":"subscription_create",
             "collection_method":"charge_automatically","amount_due":2000,"amount_paid":2000,"amount_remaining":0,"amount_paid_off_stripe":0,"amount_overpaid":0,"total":2000,"subtotal":2000,
             "starting_balance":0,"ending_balance":0,"pre_payment_credit_notes_amount":0,"post_payment_credit_notes_amount":0,"discounts":[],"total_discount_amounts":[],"total_pretax_credit_amounts":[],"total_taxes":[],
             "parent":{"type":"subscription_details","subscription_details":{"subscription":"sub_fixture"}}}
            """));
        graph.put("line",json("""
            {"id":"il_owned","object":"line_item","livemode":false,"invoice":"in_initial","subscription":"sub_fixture","currency":"eur","quantity":1,"amount":2000,"discount_amounts":[],"pretax_credit_amounts":[],
             "period":{"start":%d,"end":%d},"parent":{"type":"subscription_item_details","subscription_item_details":{"subscription":"sub_fixture","subscription_item":"si_owned","proration":false}},
             "pricing":{"type":"price_details","price_details":{"price":"price_owned","product":"prod_owned"}}}
            """.formatted(start,end)));
        graph.put("allocation",json("{\"id\":\"inpay_owned\",\"object\":\"invoice_payment\",\"livemode\":false,\"invoice\":\"in_initial\",\"currency\":\"eur\",\"status\":\"paid\",\"amount_paid\":2000,\"amount_requested\":2000,\"payment\":{\"type\":\"payment_intent\",\"payment_intent\":\"pi_initial\"},\"status_transitions\":{\"paid_at\":"+start+"}}"));
        graph.put("intent",json("{\"id\":\"pi_initial\",\"object\":\"payment_intent\",\"livemode\":false,\"customer\":\"cus_owned\",\"currency\":\"eur\",\"amount\":2000,\"amount_received\":2000,\"status\":\"succeeded\",\"capture_method\":\"automatic\",\"payment_method_types\":[\"card\"],\"latest_charge\":\"ch_initial\"}"));
        graph.put("charge",json("{\"id\":\"ch_initial\",\"object\":\"charge\",\"livemode\":false,\"customer\":\"cus_owned\",\"payment_intent\":\"pi_initial\",\"currency\":\"eur\",\"amount\":2000,\"amount_captured\":2000,\"paid\":true,\"captured\":true,\"status\":\"succeeded\",\"created\":"+start+",\"payment_method_details\":{\"type\":\"card\"},\"amount_refunded\":0,\"refunded\":false}"));
        return graph;
    }
    private static com.google.gson.JsonObject json(String text){return com.google.gson.JsonParser.parseString(text).getAsJsonObject();}
}
