package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import dev.genericpayments.platform.provider.application.service.OwnedFirstRenewalAdmissionService;
import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionLifecycleLockPort;
import dev.genericpayments.platform.support.StripeBillingClockConfirmationCompositionFixture;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

/** Hosted only. Genuine initial payment and real persistence; official SDK with inert transport. */
class StripeBillingFirstRenewalAdvanceIntegrationTest extends StripeBillingClockConfirmationCompositionFixture {
    @Autowired OwnedFirstRenewalStore renewals;
    @Autowired LoadPaymentAccountPort accounts;
    @Autowired LockPaymentAccountPort accountLocks;
    @Autowired SubscriptionLifecycleLockPort lifecycleLocks;
    @Autowired dev.genericpayments.platform.provider.application.port.out.ProviderRecoveryProofPort recovery;
    @Autowired dev.genericpayments.platform.provider.adapter.out.stripe.StripeInboxResolutionAdapter resolution;
    private Admission paidAdmission() {
        beginConfirmation();issueAndSelect();confirm("succeeded");
        var captured=fence();apply(paidObservation(),captured);assertPaid();
        return new OwnedFirstRenewalAdmissionService(renewals,transaction,lifecycleOperations,lifecycleLocks,accountLocks,subscriptions,Clock.systemUTC())
            .admit(receipt,"a".repeat(40));
    }
    private StripeBillingFirstRenewalAdvanceAdapter adapter() {
        return new StripeBillingFirstRenewalAdvanceAdapter(credentials,clients,accounts,operations,renewals,Clock.systemUTC());
    }
    private String clock(long frozen,String status,Long target) {
        var value=com.google.gson.JsonParser.parseString(clockJson(owner)).getAsJsonObject();
        value.addProperty("frozen_time",frozen);value.addProperty("status",status);
        if(target!=null){var advancing=new com.google.gson.JsonObject();advancing.addProperty("target_frozen_time",target);
            var details=new com.google.gson.JsonObject();details.add("advancing",advancing);value.add("status_details",details);}
        return value.toString();
    }
    @Test void postsOncePerStepAndRequiresGetReady() {
        var admission=paidAdmission();var before=financialState();int start=http.calls.size();
        var original=receipt.intent().clock();long target=receipt.periodEnd();
        http.reply(clock(FROZEN,"ready",null));
        http.reply(clock(FROZEN,"advancing",null));
        http.reply(clock(FROZEN,"advancing",target));
        http.reply(clock(target,"ready",null));
        inspectSdk(request->{
            assertThat(request.options().getMaxNetworkRetries()).isZero();
            assertThat(request.options().getReadTimeout()).isBetween(1,2000);
            assertThat(request.options().getConnectTimeout()).isBetween(1,2000);
            assertThat(request.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");
            if(request.method().name().equals("POST")) {
                var row=jdbc.queryForMap("select status,operation_key from provider_operations where kind='TEST_CLOCK_ADVANCE'");
                assertThat(row.get("status")).isEqualTo("DISPATCHING");
                assertThat(request.url().getPath()).isEqualTo("/v1/test_helpers/test_clocks/clock_owned/advance");
                assertThat(request.headers().map().get("Idempotency-Key")).containsExactly("billing-renewal-advance-v1:"+admission.id()+":1");
                assertThat(request.params()).containsOnlyKeys("frozen_time").containsEntry("frozen_time",target);
                assertThat(count("stripe_billing_first_renewal_ready")).isZero();
            }
        });
        var ready=new AtomicReference<LaterReady>();
        assertThatCode(()->ready.set(adapter().advance(admission,Step.BOUNDARY)))
            .as("The original paid owner dispatches one advance and persists only a separate GET READY")
            .doesNotThrowAnyException();
        assertThat(ready.get()).isNotNull();assertThat(ready.get().observation().frozenTime()).isEqualTo(target);
        assertThat(ready.get().ack().observation().frozenTime()).isEqualTo(FROZEN);
        assertThat(http.calls.subList(start,http.calls.size())).hasSize(4);
        assertThat(count("stripe_billing_first_renewal_acks")).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_ready")).isEqualTo(1);
        assertThat(receipt.intent().clock()).isEqualTo(original);
        assertThat(financialState()).isEqualTo(before);
        int completed=http.calls.size();assertThat(adapter().advance(admission,Step.BOUNDARY)).isEqualTo(ready.get());
        assertThat(http.calls).hasSize(completed);
    }
    @Test void manuallyConstructedAdapterRejectsAmbientTransaction() {
        var admission=paidAdmission();int calls=http.calls.size();
        assertThatThrownBy(()->transaction.required(()->adapter().advance(admission,Step.BOUNDARY))).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_first_renewal_steps")).isZero();
    }
    @Test void authenticatedPreclaimCredentialDriftStopsEvenIfLaterRepaired() {
        var admission=paidAdmission();int calls=http.calls.size();
        jdbc.update("update payment_accounts set credentials_ref='inert-drift' where id=?",billing.id().value());
        assertThatThrownBy(()->renewals.requireCurrent(admission))
            .as("The real repository proxy must preserve authenticated AuthorityDrift without JPA exception translation")
            .isExactlyInstanceOf(AuthorityDrift.class);
        assertThatThrownBy(()->adapter().advance(admission,Step.BOUNDARY)).isExactlyInstanceOf(AuthorityDrift.class);
        assertThat(jdbc.queryForObject("select stopped_at from stripe_billing_first_renewals",java.sql.Timestamp.class)).isNotNull();
        jdbc.update("update payment_accounts set credentials_ref=? where id=?",billing.credentialsRef(),billing.id().value());
        assertThatThrownBy(()->adapter().advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_first_renewal_steps")).isZero();
    }
    @Test void forgedAdmissionCannotStopOriginalBeforeClaim() {
        var a=paidAdmission();int calls=http.calls.size();
        var forged=new Admission(a.id(),a.receipt(),"b".repeat(40),a.root(),a.attempt(),a.attemptVersion(),a.bill(),a.billVersion(),a.payment(),a.paymentVersion(),
            a.invoice(),a.allocation(),a.paymentIntent(),a.subscriptionRevision(),a.policyVersion(),a.fingerprint(),a.admittedAt());
        assertThatThrownBy(()->adapter().advance(forged,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select stopped_at from stripe_billing_first_renewals",java.sql.Timestamp.class)).isNull();
        assertThat(renewals.requireCurrent(a)).isEqualTo(a);assertThat(http.calls).hasSize(calls);
    }
    @Test void neverRecoversAdvance() {
        var admission=paidAdmission();http.reply(clock(FROZEN,"ready",null));
        http.reply(clock(FROZEN,"advancing",null));http.reply(clock(receipt.periodEnd(),"ready",null));
        var ready=adapter().advance(admission,Step.BOUNDARY);var operation=ready.ack().operation();int calls=http.calls.size();
        assertThatThrownBy(()->transaction.required(()->operations.lockRecovery(operation))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->operations.bindRecovered(operation,"clock_owned",operation.fingerprint())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->transaction.required(()->operations.bindRecoveredInTransaction(operation,"clock_owned",operation.fingerprint()))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->recovery.request(operation)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->recovery.replay(operation)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->recovery.retrieve(operation)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->recovery.verify(operation,"clock_owned","evt_owned")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->resolution.resolveRecovery(operation)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->operations.reserve(billing,operation.kind(),operation.resourceId(),operation.operationKey(),operation.fingerprint())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->operations.acknowledge(operation.id(),ready.ack().claimant(),operation.version(),"clock_owned")).isInstanceOf(RuntimeException.class);
        for(var mutation:java.util.List.of("kind='PRODUCT_CREATE'","resource_id=gen_random_uuid()","operation_key='foreign'","fingerprint='foreign'",
                "provider_reference='clock_foreign'","first_dispatched_at=first_dispatched_at+interval '1 second'",
                "status='RESERVED',provider_reference=null,first_dispatched_at=null,retry_before=null"))
            assertThatThrownBy(()->jdbc.update("update provider_operations set "+mutation+" where id=?",operation.id()))
                .as("Consumed advance rejects "+mutation).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("delete from provider_operations where id=?",operation.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("delete from stripe_billing_first_renewal_acks where provider_operation=?",operation.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(http.calls).hasSize(calls);
    }
    @Test void twoAdvancesShareClockButNotDispatchIdentity() {
        var admission=paidAdmission();var before=financialState();long b=receipt.periodEnd(),t=b+3600;
        http.reply(clock(FROZEN,"ready",null));http.reply(clock(FROZEN,"advancing",null));http.reply(clock(b,"ready",null));
        var first=adapter().advance(admission,Step.BOUNDARY);
        // Isolated Task2 downstream evidence fixture only, after genuine confirmation/CASH and actual READY1.
        // Full boundary graph producer and lifecycle transaction compose in Task3/5.
        var started=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var boundaryId=java.util.UUID.randomUUID();
        assertThatThrownBy(()->insertPrivateReadClaim(admission,first,started.plusSeconds(3600)))
            .as("A future original start cannot manufacture a fresh read budget").isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        insertPrivateReadClaim(admission,first,started);
        assertThatThrownBy(()->jdbc.update("update stripe_billing_first_renewal_read_claims set deadline_at=deadline_at+interval '30 seconds'"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("delete from stripe_billing_first_renewal_read_claims"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("""
            insert into stripe_billing_first_renewal_boundary(id,admission_id,ready_id,item_reference,invoice_reference,period_start,period_end,fingerprint,observed_at)
             values (?,?,?,?,?,?,?,?,?)
            """,boundaryId,admission.id(),first.id(),receipt.itemReference(),"in_renewal",java.sql.Timestamp.from(Instant.ofEpochSecond(b)),
            java.sql.Timestamp.from(Instant.ofEpochSecond(b+86400)),"b".repeat(64),java.sql.Timestamp.from(started));
        http.reply(clock(b,"ready",null));http.reply(clock(b,"advancing",null));http.reply(clock(t,"ready",null));
        var second=new AtomicReference<LaterReady>();
        assertThatCode(()->second.set(adapter().advance(admission,Step.FINALIZATION)))
            .as("Persisted READY1 and provider boundary authorize exactly T=B+3600 outside the old initial period")
            .doesNotThrowAnyException();
        assertThat(second.get().observation().frozenTime()).isEqualTo(t);
        assertThat(second.get().ack().advance().predecessorReady()).isEqualTo(first.id());
        assertThat(second.get().ack().advance().boundary()).isEqualTo(boundaryId);
        assertThat(second.get().ack().operation().id()).isNotEqualTo(first.ack().operation().id());
        assertThat(second.get().ack().operation().resourceId()).isNotEqualTo(first.ack().operation().resourceId());
        assertThat(second.get().ack().operation().operationKey()).isEqualTo("billing-renewal-advance-v1:"+admission.id()+":2");
        assertThat(jdbc.queryForList("select provider_reference from provider_operations where kind='TEST_CLOCK_ADVANCE'",String.class))
            .containsExactlyInAnyOrder("clock_owned","clock_owned");
        assertThatThrownBy(()->jdbc.update("insert into stripe_billing_first_renewal_steps select gen_random_uuid(),admission_id,ordinal,target,predecessor_ready,boundary_id,operation_key,fingerprint,prepared_at from stripe_billing_first_renewal_steps where ordinal=2"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(financialState()).isEqualTo(before);
    }
    private void insertPrivateReadClaim(Admission admission,LaterReady ready,Instant started) {
        var capture=fence();
        jdbc.update("""
            insert into stripe_billing_first_renewal_read_claims(ready_id,step_id,admission_id,invocation,started_at,deadline_at,
             captured_revision,selected_operation,selected_version,observation_sequence) values (?,?,?,?,?,?,?,?,?,?)
            """,ready.id(),ready.ack().advance().id(),admission.id(),java.util.UUID.randomUUID(),java.sql.Timestamp.from(started),java.sql.Timestamp.from(started.plusSeconds(30)),
            capture.revision(),capture.operationId(),capture.operationVersion(),capture.observationSequence());
    }
    @Test void concurrentClaimLoserCannotStopOriginalWinner() throws Exception {
        var admission=paidAdmission();var claimed=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);int calls=http.calls.size();
        var observed=(dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore)java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),new Class<?>[]{dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore.class},(proxy,method,args)->{
                Object result;
                try{result=method.invoke(operations,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                if(method.getName().equals("claim")&&((java.util.Optional<?>)result).isPresent()){
                    claimed.countDown();assertThat(release.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                }
                return result;
            });
        http.reply(clock(FROZEN,"ready",null));http.reply(clock(FROZEN,"advancing",null));http.reply(clock(receipt.periodEnd(),"ready",null));
        var winner=new StripeBillingFirstRenewalAdvanceAdapter(credentials,clients,accounts,observed,renewals,Clock.systemUTC());
        try(var workers=new BoundedWorkers()) {
            var result=workers.submit(()->winner.advance(admission,Step.BOUNDARY));
            try {
                assertThat(claimed.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->adapter().advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
                assertThat(jdbc.queryForObject("select stopped_at from stripe_billing_first_renewals",java.sql.Timestamp.class)).isNull();
                assertThat(http.calls).hasSize(calls);
            } finally {release.countDown();}
            assertThat(result.get(15,java.util.concurrent.TimeUnit.SECONDS).observation().frozenTime()).isEqualTo(receipt.periodEnd());
        }
        assertThat(http.calls).hasSize(calls+3);
        assertThat(count("stripe_billing_first_renewal_acks")).isEqualTo(1);
    }
    @Test void dispatchClaimTakesSiteBeforeAccountAgainstLifecyclePublisher() throws Exception {
        var admission=paidAdmission();var step=renewals.prepare(admission,Step.BOUNDARY,null,Instant.now());
        var reserved=operations.reserveOwnedFirstRenewalAdvance(billing,step);
        var siteHeld=new java.util.concurrent.CountDownLatch(1);var reachedSite=new java.util.concurrent.CountDownLatch(1);
        var observedJdbc=new org.springframework.jdbc.core.JdbcTemplate(jdbc.getDataSource()) {
            @Override public <T>java.util.List<T> queryForList(String sql,Class<T> type,Object... args) {
                if(sql.contains("from sites")&&sql.contains("for share"))reachedSite.countDown();
                return super.queryForList(sql,type,args);
            }
        };
        var observedStore=new dev.genericpayments.platform.provider.adapter.out.persistence.JdbcProviderOperationStore(observedJdbc);
        try(var workers=new BoundedWorkers()) {
            var lifecycle=workers.submit(()->transaction.required(()->{
                jdbc.execute("set local statement_timeout='10s'");jdbc.execute("set local lock_timeout='5s'");
                jdbc.queryForList("select id from sites where id=? for update",site.value());siteHeld.countDown();
                try{assertThat(reachedSite.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                accountLocks.findByIdForUpdate(billing.id()).orElseThrow();return null;
            }));
            assertThat(siteHeld.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var claim=workers.submit(()->transaction.required(()->{
                jdbc.execute("set local statement_timeout='10s'");jdbc.execute("set local lock_timeout='5s'");
                return observedStore.claim(reserved.id(),"race-original",Instant.now(),Duration.ofSeconds(60));
            }));
            lifecycle.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(claim.get(15,java.util.concurrent.TimeUnit.SECONDS)).isPresent();
        }
    }
    @ParameterizedTest @ValueSource(strings={"disconnect","internal_failure","wrong_clock","wrong_target","wrong_name","wrong_created","twelve_advancing","stop_during_get"})
    void stopsWithoutReplay(String failure) {
        var admission=paidAdmission();var before=financialState();int start=http.calls.size();long target=receipt.periodEnd();
        http.reply(clock(FROZEN,"ready",null));
        if(failure.equals("disconnect"))http.disconnect();
        else {
            http.reply(clock(FROZEN,"advancing",null));
            if(failure.equals("twelve_advancing"))for(int i=0;i<12;i++)http.reply(clock(FROZEN,"advancing",target));
            else {
                var value=com.google.gson.JsonParser.parseString(clock(target,"ready",null)).getAsJsonObject();
                switch(failure) {
                    case "internal_failure"->value.addProperty("status","internal_failure");
                    case "wrong_clock"->value.addProperty("id","clock_foreign");
                    case "wrong_target"->value.addProperty("frozen_time",target+1);
                    case "wrong_name"->value.addProperty("name","foreign");
                    case "wrong_created"->value.addProperty("created",1);
                }
                http.reply(value.toString());
            }
        }
        if(failure.equals("stop_during_get"))inspectSdk(request->{
            if(request.method().name().equals("GET")&&http.calls.size()==start+3)
                renewals.stop(admission,"AUTHORITY_DRIFT",Instant.now());
        });
        assertThatThrownBy(()->adapter().advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select first_dispatched_at from provider_operations where kind='TEST_CLOCK_ADVANCE'",java.sql.Timestamp.class)).isNotNull();
        assertThat(jdbc.queryForObject("select stopped_at from stripe_billing_first_renewals",java.sql.Timestamp.class)).isNotNull();
        assertThat(count("stripe_billing_first_renewal_ready")).isZero();
        int stopped=http.calls.size();
        assertThatThrownBy(()->adapter().advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(stopped);
        assertThat(http.calls.subList(start,stopped).stream().filter(r->r.method().name().equals("POST")).count()).isEqualTo(1);
        if(failure.equals("twelve_advancing"))assertThat(stopped-start).isEqualTo(14);
        assertThat(financialState()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"lost_ack_write","lost_ack_response","lost_ready_write","lost_ready_response"})
    void receiptFailureDoesNotTransferOriginalContinuation(String failure) {
        var admission=paidAdmission();var before=financialState();int calls=http.calls.size();
        http.reply(clock(FROZEN,"ready",null));http.reply(clock(FROZEN,"advancing",null));http.reply(clock(receipt.periodEnd(),"ready",null));
        var stepPort=failingPort(dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore.class,operations,
            "acknowledgeOwnedFirstRenewalAdvance",failure.startsWith("lost_ack"),failure.endsWith("response"));
        var readyPort=failingPort(OwnedFirstRenewalStore.class,renewals,"recordReady",failure.startsWith("lost_ready"),failure.endsWith("response"));
        var failing=new StripeBillingFirstRenewalAdvanceAdapter(credentials,clients,accounts,stepPort,readyPort,Clock.systemUTC());
        assertThatThrownBy(()->failing.advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select stopped_at from stripe_billing_first_renewals",java.sql.Timestamp.class)).isNotNull();
        int ended=http.calls.size();assertThatThrownBy(()->adapter().advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(ended);
        assertThat(http.calls.subList(calls,ended).stream().filter(r->r.method().name().equals("POST")).count()).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_ready")).isEqualTo(failure.equals("lost_ready_response")?1:0);
        assertThat(financialState()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"before_post","after_post","bare_ack"})
    void abandonedUnstoppedOriginalInvocationCannotResume(String crash) {
        var admission=paidAdmission();var before=financialState();int started=http.calls.size();
        http.reply(clock(FROZEN,"ready",null));http.reply(clock(FROZEN,"advancing",null));http.reply(clock(receipt.periodEnd(),"ready",null));
        var stepPort=(dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore)java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),new Class<?>[]{dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore.class},(proxy,method,args)->{
                boolean site=method.getName().equals(crash.equals("before_post")?"claim":"acknowledgeOwnedFirstRenewalAdvance");
                if(site&&crash.equals("after_post"))throw new SimulatedProcessExit();
                Object result;
                try{result=method.invoke(operations,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                if(site)throw new SimulatedProcessExit();
                return result;
            });
        var dying=new StripeBillingFirstRenewalAdvanceAdapter(credentials,clients,accounts,stepPort,renewals,Clock.systemUTC());
        // Error deliberately bypasses normal RuntimeException cleanup, as process loss does.
        assertThatThrownBy(()->dying.advance(admission,Step.BOUNDARY)).isInstanceOf(SimulatedProcessExit.class);
        var consumed=operations.find(billing.id(),"billing-renewal-advance-v1:"+admission.id()+":1").orElseThrow();
        assertThat(consumed.status().name()).isEqualTo(crash.equals("bare_ack")?"ACKNOWLEDGED":"DISPATCHING");
        assertThat(consumed.firstDispatchedAt()).isNotNull();
        assertThat(jdbc.queryForObject("select stopped_at from stripe_billing_first_renewals",java.sql.Timestamp.class)).isNull();
        assertThat(http.calls.size()-started).isEqualTo(crash.equals("before_post")?0:2);
        int abandoned=http.calls.size();
        assertThatThrownBy(()->adapter().advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(abandoned);
        assertThat(operations.findById(consumed.id())).contains(consumed);
        assertThat(jdbc.queryForObject("select stopped_at from stripe_billing_first_renewals",java.sql.Timestamp.class)).isNull();
        assertThat(count("stripe_billing_first_renewal_ready")).isZero();assertThat(financialState()).isEqualTo(before);
    }
    private static final class SimulatedProcessExit extends Error {}

    @ParameterizedTest @ValueSource(strings={"ACK","READY"})
    void originalUtcLeaseExpiryRejectsLateEvidenceWithoutChangingClaim(String phase) {
        var admission=paidAdmission();var before=financialState();int started=http.calls.size();
        http.reply(clock(FROZEN,"ready",null));http.reply(clock(FROZEN,"advancing",null));http.reply(clock(receipt.periodEnd(),"ready",null));
        var deadline=new AtomicReference<Instant>();
        var methodName=phase.equals("ACK")?"acknowledgeOwnedFirstRenewalAdvance":"recordReady";
        Object delegate=phase.equals("ACK")?operations:renewals;
        Class<?> type=phase.equals("ACK")?dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore.class:OwnedFirstRenewalStore.class;
        var held=java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            if(method.getName().equals(methodName)) {
                var original=phase.equals("ACK")?((dev.genericpayments.platform.provider.application.model.ProviderOperation)args[1]).leaseExpiresAt():((Ack)args[0]).deadline();
                deadline.set(original);
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                // Keep the original pre-expiry utcAt argument. The actual DB clock must reject the write.
                assertThat((Instant)args[phase.equals("ACK")?3:2]).isBefore(original);
                awaitRealUtcExpiry(original);
            }
            try{return method.invoke(delegate,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
        var delayed=new StripeBillingFirstRenewalAdvanceAdapter(credentials,clients,accounts,
            phase.equals("ACK")?(dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore)held:operations,
            phase.equals("READY")?(OwnedFirstRenewalStore)held:renewals,Clock.systemUTC());
        assertThatThrownBy(()->delayed.advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(deadline.get()).isNotNull();
        var consumed=operations.find(billing.id(),"billing-renewal-advance-v1:"+admission.id()+":1").orElseThrow();
        assertThat(consumed.firstDispatchedAt().plusSeconds(60)).isEqualTo(deadline.get());
        assertThat(consumed.status().name()).isEqualTo(phase.equals("ACK")?"DISPATCHING":"ACKNOWLEDGED");
        if(phase.equals("ACK"))assertThat(consumed.leaseExpiresAt()).isEqualTo(deadline.get());
        else assertThat(jdbc.queryForObject("select deadline from stripe_billing_first_renewal_acks",java.sql.Timestamp.class).toInstant()).isEqualTo(deadline.get());
        assertThat(count("stripe_billing_first_renewal_acks")).isEqualTo(phase.equals("ACK")?0:1);
        assertThat(count("stripe_billing_first_renewal_ready")).isZero();
        assertThat(jdbc.queryForObject("select stopped_at from stripe_billing_first_renewals",java.sql.Timestamp.class)).isNotNull();
        assertThat(http.calls.size()-started).isEqualTo(phase.equals("ACK")?2:3);
        int expired=http.calls.size();assertThatThrownBy(()->adapter().advance(admission,Step.BOUNDARY)).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(expired);assertThat(financialState()).isEqualTo(before);
    }
    /** Real hosted wall time only. Never alters a consumed lease, history or the database clock. */
    private void awaitRealUtcExpiry(Instant deadline)throws InterruptedException {
        long began=System.nanoTime();
        while(Instant.now().isBefore(deadline.plusMillis(50))) {
            assertThat(Duration.ofNanos(System.nanoTime()-began)).isLessThan(Duration.ofSeconds(65));
            Thread.sleep(100);
        }
        assertThat(jdbc.queryForObject("select clock_timestamp()>=?",Boolean.class,java.sql.Timestamp.from(deadline))).isTrue();
    }
    private <T>T failingPort(Class<T> type,T delegate,String methodName,boolean enabled,boolean after) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            if(enabled&&method.getName().equals(methodName)&&!after)throw new IllegalStateException("inert lost receipt write");
            Object result;
            try{result=method.invoke(delegate,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            if(enabled&&method.getName().equals(methodName)&&after)throw new IllegalStateException("inert lost committed receipt response");
            return result;
        }));
    }
}
