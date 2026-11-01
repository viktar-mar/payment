package dev.genericpayments.platform.subscriptions.application.service;

import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import dev.genericpayments.platform.provider.application.service.OwnedFirstRenewalPublicationService;
import dev.genericpayments.platform.subscriptions.application.port.in.QuerySubscriptionsUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import dev.genericpayments.platform.support.OwnedFirstRenewalBillingTime;
import dev.genericpayments.platform.support.OwnedSubscriptionBillingTime;
import dev.genericpayments.platform.support.StripeBillingFirstRenewalCompositionFixture;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import java.sql.Timestamp;
import java.lang.reflect.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.*;
import static org.assertj.core.api.Assertions.*;

/** Hosted only: real initial confirmation, claim/READY/graph producers, FactService, cash and outbox. */
@Import(OwnedFirstRenewalPublicationIntegrationTest.RenewalTimeConfiguration.class)
class OwnedFirstRenewalPublicationIntegrationTest extends StripeBillingFirstRenewalCompositionFixture {
    @Autowired OwnedFirstRenewalBillingTime renewalTime;
    @Autowired dev.genericpayments.platform.subscriptions.application.port.out.TrialEligibilityStore trialEligibility;

    @TestConfiguration(proxyBeanMethods=false)
    static class RenewalTimeConfiguration {
        @Bean static BeanFactoryPostProcessor selectRenewalTime() {
            return factory->factory.getBeanDefinition("ownedSubscriptionTime").setPrimary(false);
        }
        @Bean @Primary OwnedFirstRenewalBillingTime ownedFirstRenewalTime(JdbcTemplate jdbc,OwnedSubscriptionBillingTime original) {
            return new OwnedFirstRenewalBillingTime(jdbc,original);
        }
    }

    private OwnedFirstRenewalPublicationService publisher() {
        return publisher(renewals,facts);
    }
    private OwnedFirstRenewalPublicationService publisher(OwnedFirstRenewalStore store,ApplySubscriptionSnapshotUseCase apply) {
        return new OwnedFirstRenewalPublicationService(store,transaction,lifecycleOperations,lifecycleLocks,accountLocks,
            subscriptions,readPreparation,observations,atCurrentOperationalUtc(apply),trialEligibility,Clock.systemUTC());
    }
    private SubscriptionBillingTimePort.Sample sample() {
        return transaction.required(()->{
            var holder=TransactionSynchronizationManager.getResource(jdbc.getDataSource());
            var xact=jdbc.queryForObject("select pg_current_xact_id()::text",String.class);
            var result=renewalTime.sample(billing.id(),site,customer,Instant.now());
            assertThat(renewalTime.sampledConnectionHolder).isSameAs(holder);assertThat(renewalTime.sampledTransaction).isEqualTo(xact);return result;
        });
    }

    @Test void publishesOnlyFreshPaidOnce() {
        var admission=paidAdmission();var before=financialState();var paid=paid(admission);
        assertThat(financialState()).isEqualTo(before);
        var initialBill=jdbc.queryForObject("select row_to_json(b)::text from subscription_billing_records b where id=?",String.class,admission.bill());
        var initialPayment=jdbc.queryForObject("select row_to_json(p)::text from payments p where id=?",String.class,admission.payment());
        var originalCreate=currentOperation();var published=new AtomicReference<Publication>();
        assertThatCode(()->published.set(publisher().publish(admission,paid.ready(),paid)))
            .as("Genuine original confirmation and private READY2 CASH publish the first renewal atomically")
            .doesNotThrowAnyException();

        var expected=new Subscription.Period(Instant.ofEpochSecond(receipt.periodEnd()),Instant.ofEpochSecond(receipt.periodEnd()+31*86400));
        assertThat(current().currentPeriod()).isEqualTo(expected);assertThat(current().paidThrough()).isEqualTo(expected.end());
        assertThat(currentOperation()).isEqualTo(originalCreate);assertThat(currentOperation().status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(jdbc.queryForObject("select row_to_json(b)::text from subscription_billing_records b where id=?",String.class,admission.bill())).isEqualTo(initialBill);
        assertThat(jdbc.queryForObject("select row_to_json(p)::text from payments p where id=?",String.class,admission.payment())).isEqualTo(initialPayment);
        assertThat(count("subscription_billing_records")).isEqualTo(2);assertThat(count("payments")).isEqualTo(2);
        assertThat(jdbc.queryForList("select settlement from subscription_billing_records",String.class)).containsOnly("CONFIRMED_CASH");
        var bill=jdbc.queryForObject("select id from subscription_billing_records where kind='RENEWAL' and provider_invoice_reference='in_renewal'",UUID.class);
        var payment=jdbc.queryForMap("""
            select id,status,source,subscription_billing_kind,subscription_id,billing_record_id,subscription_invoice_reference,
             subscription_period_start,subscription_period_end,provider_payment_reference,amount_minor,currency
            from payments where subscription_billing_kind='RENEWAL'
            """);
        assertThat(payment).containsEntry("status","SUCCEEDED").containsEntry("source","SUBSCRIPTION")
            .containsEntry("subscription_id",current().id()).containsEntry("billing_record_id",bill)
            .containsEntry("subscription_invoice_reference","in_renewal").containsEntry("provider_payment_reference","pi_renewal")
            .containsEntry("amount_minor",1000L).containsEntry("currency","EUR")
            .containsEntry("subscription_period_start",java.sql.Timestamp.from(expected.start()))
            .containsEntry("subscription_period_end",java.sql.Timestamp.from(expected.end()));
        assertThat(published.get().bill()).isEqualTo(bill);assertThat(published.get().payment()).isEqualTo(payment.get("id"));
        assertThat(published.get().projectedRevision()).isEqualTo(current().revision());
        assertThat(current().revision()).isEqualTo(admission.subscriptionRevision()+1);
        assertThat(observations.captureFence(current())).isEqualTo(paid.fence().observationSequence()+1);
        assertThat(sample().billingAt()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()+3600));
        assertThat(queries.get(scope.context().actor(),site,current().id()).access().allowed()).isTrue();
        assertThat(jdbc.queryForObject("select graph_accepted_at from stripe_billing_first_renewal_publications",Timestamp.class).toInstant()).isEqualTo(paid.graphAcceptedAt());
        assertThat(jdbc.queryForList("select event_type from subscription_events",String.class)).containsExactly("PERIOD_PAID","PERIOD_PAID");
        assertThat(count("payment_events")).isEqualTo(2);assertThat(count("outbox_events")).isEqualTo(4);
        assertThat(jdbc.queryForList("select callback_type from outbox_events",String.class))
            .containsExactlyInAnyOrder("payment.succeeded","subscription.updated","payment.succeeded","subscription.updated");
        assertThat(count("stripe_billing_first_renewal_publications")).isEqualTo(1);
        var committed=financialState();int calls=http.calls.size();
        assertThat(publisher().replayPublished(admission)).isEqualTo(published.get());
        assertThat(financialState()).isEqualTo(committed);assertThat(http.calls).hasSize(calls);
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(2);
        assertThatThrownBy(()->publisher().publish(admission,paid.ready(),paid)).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(committed);
    }

    @Test void publicationRefreshesOperationalUtcAfterInitialBillRoundsUpInPostgres() {
        var admission=paidAdmission();var paid=paid(admission);var before=financialState();
        var initialBill=jdbc.queryForObject("select row_to_json(b)::text from subscription_billing_records b where id=?",String.class,admission.bill());
        var initialPayment=jdbc.queryForObject("select row_to_json(p)::text from payments p where id=?",String.class,admission.payment());
        var claims=jdbc.queryForList("select row_to_json(c)::text from stripe_billing_first_renewal_read_claims c order by ready_id",String.class);
        int calls=http.calls.size();
        var created=jdbc.queryForObject("select created_at from subscription_billing_records where id=?",Timestamp.class,admission.bill()).toInstant();
        // Artificial fixture-clock input derived from the genuinely persisted UTC bill. No row is changed.
        // PostgreSQL rounds this input up by211ns, reproducing the original frozen nanosecond clock.
        var unrounded=created.minusNanos(211);
        assertThat(jdbc.queryForObject("select cast(? as timestamptz)",Timestamp.class,Timestamp.from(unrounded)).toInstant()).isEqualTo(created);
        assertThat(unrounded).isBefore(created);TestClock.set(unrounded);
        var unrefreshed=new OwnedFirstRenewalPublicationService(renewals,transaction,lifecycleOperations,lifecycleLocks,accountLocks,
            subscriptions,readPreparation,observations,facts,trialEligibility,Clock.systemUTC());
        assertThatThrownBy(()->unrefreshed.publish(admission,paid.ready(),paid))
            .as("The unchanged domain guard rejects a stale fixture clock before the rounded historical createdAt")
            .isInstanceOf(dev.genericpayments.platform.shared.domain.DomainValidationException.class).hasMessage("Invalid billing record");
        assertThat(financialState()).isEqualTo(before);assertThat(count("stripe_billing_first_renewal_publications")).isZero();

        ApplySubscriptionSnapshotUseCase originalAuthority=command->{
            assertThat(command.fence()).isEqualTo(paid.fence());assertThat(command.snapshot()).isEqualTo(paid.snapshot());
            assertThat(observations.captureFence(current())).isEqualTo(paid.fence().observationSequence());
            assertThat(http.calls).hasSize(calls);
            assertThat(jdbc.queryForList("select row_to_json(c)::text from stripe_billing_first_renewal_read_claims c order by ready_id",String.class)).isEqualTo(claims);
            facts.apply(command);
        };
        assertThatCode(()->publisher(renewals,originalAuthority).publish(admission,paid.ready(),paid))
            .as("The fixture refreshes operational UTC before the same genuine FactService call")
            .doesNotThrowAnyException();
        assertThat(count("payments")).isEqualTo(2);assertThat(count("outbox_events")).isEqualTo(4);
        assertThat(count("stripe_billing_first_renewal_publications")).isEqualTo(1);
        assertThat(current().paidThrough()).isEqualTo(paid.boundary().period().end());
        assertThat(jdbc.queryForObject("select created_at from subscription_billing_records where id=?",Timestamp.class,admission.bill()).toInstant()).isEqualTo(created);
        assertThat(jdbc.queryForObject("select row_to_json(b)::text from subscription_billing_records b where id=?",String.class,admission.bill())).isEqualTo(initialBill);
        assertThat(jdbc.queryForObject("select row_to_json(p)::text from payments p where id=?",String.class,admission.payment())).isEqualTo(initialPayment);
        assertThat(currentOperation()).isEqualTo(before.get("operation"));assertThat(http.calls).hasSize(calls);
        assertThat(jdbc.queryForList("select row_to_json(c)::text from stripe_billing_first_renewal_read_claims c order by ready_id",String.class)).isEqualTo(claims);
        assertThat(jdbc.queryForObject("select graph_accepted_at from stripe_billing_first_renewal_publications",Timestamp.class).toInstant()).isEqualTo(paid.graphAcceptedAt());
    }

    @ParameterizedTest
    @ValueSource(strings={"claim-invocation","claim-sequence","claim-revision","wrong-ready-argument","command","open","period","initial-pi","shared-pi"})
    void rejectsOtherwiseValidPaidWithChangedProvenanceOrCashShape(String mutation) {
        var admission=paidAdmission();var original=paid(admission);var before=financialState();
        var claim=original.claim();var snapshot=original.snapshot();var ready=original.ready();
        if(mutation.startsWith("claim-")) {
            var f=claim.fence();
            var forged=new ApplySubscriptionSnapshotUseCase.Fence(f.revision()+(mutation.equals("claim-revision")?1:0),null,null,null,null,
                f.observationSequence()+(mutation.equals("claim-sequence")?1:0));
            claim=new ReadClaim(claim.ready(),mutation.equals("claim-invocation")?UUID.randomUUID():claim.invocation(),claim.startedAt(),claim.deadlineAt(),forged);
        } else if(mutation.equals("wrong-ready-argument"))ready=original.boundary().ready();
        else {
            var invoices=new ArrayList<>(snapshot.invoices());
            if(mutation.endsWith("pi")) {
                var index=mutation.equals("initial-pi")?0:1;var old=invoices.get(index);var cash=old.cashObjects().getFirst();
                invoices.set(index,new Invoice(old.reference(),old.kind(),old.price(),null,old.period(),old.amountDueMinor(),old.currency(),old.settlement(),
                    List.of(new CashObject(mutation.equals("initial-pi")?"pi_foreign":admission.paymentIntent(),cash.amountMinor(),cash.currency(),cash.status(),cash.occurredAt()))));
            }
            if(mutation.equals("open")) {
                var i=invoices.getLast();invoices.set(invoices.size()-1,new Invoice(i.reference(),i.kind(),i.price(),null,i.period(),i.amountDueMinor(),i.currency(),SettlementKind.OPEN,List.of()));
            }
            snapshot=new Snapshot(snapshot.subscriptionReference(),snapshot.status(),snapshot.billingPrice(),
                mutation.equals("period")?new Subscription.Period(snapshot.period().start(),snapshot.period().end().plusSeconds(1)):snapshot.period(),
                null,null,invoices,mutation.equals("command")?currentOperation().providerOperationKey():null,snapshot.paymentMethodReady(),0,snapshot.observedAt());
        }
        var invalid=new Paid(admission,original.ready(),original.boundary(),claim.fence(),snapshot,claim,original.graphAcceptedAt());
        var argument=ready;
        assertThatThrownBy(()->publisher().publish(admission,argument,invalid)).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(before);assertThat(count("stripe_billing_first_renewal_publications")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"revision","sequence","policy","credentials","revoke","stop"})
    void driftAfterGraphRefusesPublicationAndTimeWithoutFurtherFinancialWrites(String drift) {
        var admission=paidAdmission();var paid=paid(admission);
        switch(drift) {
            case "revision"->jdbc.update("update subscriptions set revision=revision+1 where id=?",current().id());
            case "sequence"->jdbc.update("update subscriptions set observation_sequence=observation_sequence+1 where id=?",current().id());
            case "policy"->jdbc.update("update site_subscription_policies set version=version+1 where site_id=?",site.value());
            case "credentials"->jdbc.update("update payment_accounts set credentials_ref='inert-drift' where id=?",billing.id().value());
            case "revoke"->clocks.stop(owner,dev.genericpayments.platform.provider.application.port.out.StripeBillingClockStore.StopReason.AUTHORITY_REVOKED,Instant.now());
            case "stop"->renewals.stop(admission,"PAYMENT_NOT_OBSERVED",Instant.now());
        }
        var before=financialState();
        assertThatThrownBy(()->publisher().publish(admission,paid.ready(),paid)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(this::sample).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(before);assertThat(count("stripe_billing_first_renewal_publications")).isZero();
    }

    @Test void nullOrForeignEvidenceCannotInvokeFactService() {
        var admission=paidAdmission();var paid=paid(admission);var before=financialState();
        assertThatThrownBy(()->publisher().publish(admission,paid.ready(),null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->publisher().publish(admission,null,paid)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->publisher().publish(null,paid.ready(),paid)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->transaction.required(()->renewalTime.sample(new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(UUID.randomUUID()),site,customer,Instant.now())))
            .isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(before);
    }

    private OwnedFirstRenewalStore interceptReceipt(java.util.function.Function<Object[],Publication> action) {
        return (OwnedFirstRenewalStore)Proxy.newProxyInstance(OwnedFirstRenewalStore.class.getClassLoader(),new Class<?>[]{OwnedFirstRenewalStore.class},(proxy,method,args)->{
            if(method.getName().equals("recordPublicationInTransaction"))return action.apply(args);
            try{return method.invoke(renewals,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        });
    }

    @Test void driftInsideFactApplicationRollsBackProjectionAndPolicyTogether() {
        var admission=paidAdmission();var paid=paid(admission);var before=financialState();
        var policy=jdbc.queryForObject("select version from site_subscription_policies where site_id=?",Long.class,site.value());
        ApplySubscriptionSnapshotUseCase drift=command->{facts.apply(command);
            jdbc.update("update site_subscription_policies set version=version+1 where site_id=?",site.value());};
        assertThatThrownBy(()->publisher(renewals,drift).publish(admission,paid.ready(),paid)).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(before);assertThat(count("stripe_billing_first_renewal_publications")).isZero();
        assertThat(jdbc.queryForObject("select version from site_subscription_policies where site_id=?",Long.class,site.value())).isEqualTo(policy);
    }

    @Test void samplerUsesCallerTransactionWithoutWaitingForProviderStepLock() throws Exception {
        var admission=paidAdmission();var first=advance(admission,Step.BOUNDARY);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var workers=new BoundedWorkers()) {
            var lock=workers.submit(()->transaction.required(()->{
                jdbc.queryForList("select id from stripe_billing_first_renewal_steps where id=? for update",UUID.class,first.ack().advance().id());locked.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Step release timed out");}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}return true;
            }));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                var sampling=workers.submit(this::sample);
                assertThat(sampling.get(2,TimeUnit.SECONDS).billingAt()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()));
            } finally {release.countDown();}
            assertThat(lock.get(5,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("stripe_billing_first_renewal_read_claims")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"bill","payment","revision","ready","boundary","before-start","at-deadline","future-publication"})
    void sqlReceiptFailureRollsBackRealFactCashEventsAndFence(String mutation) {
        var admission=paidAdmission();var paid=paid(admission);var before=financialState();
        var holder=new AtomicReference<Object>();var xact=new AtomicReference<String>();
        var writer=interceptReceipt(args->{
            assertThat(TransactionSynchronizationManager.getResource(jdbc.getDataSource())).isSameAs(holder.get());
            assertThat(jdbc.queryForObject("select pg_current_xact_id()::text",String.class)).isEqualTo(xact.get());
            assertThat(count("payments")).isEqualTo(2);assertThat(count("outbox_events")).isEqualTo(4);
            assertThat(observations.captureFence(current())).isEqualTo(paid.fence().observationSequence()+1);
            var row=jdbc.queryForMap("select * from owned_first_renewal_projected(?)",admission.id());
            var accepted=mutation.equals("before-start")?paid.claim().startedAt().minusNanos(1000):mutation.equals("at-deadline")?paid.claim().deadlineAt():paid.graphAcceptedAt();
            jdbc.update("""
                insert into stripe_billing_first_renewal_publications(admission_id,ready_id,boundary_id,renewal_bill,renewal_payment,projected_revision,published_at,graph_accepted_at)
                values(?,?,?,?,?,?,?,?)
                """,admission.id(),mutation.equals("ready")?paid.boundary().ready().id():paid.ready().id(),mutation.equals("boundary")?UUID.randomUUID():paid.boundary().id(),
                mutation.equals("bill")?admission.bill():row.get("renewal_bill"),mutation.equals("payment")?admission.payment():row.get("renewal_payment"),
                ((Number)row.get("projected_revision")).longValue()+(mutation.equals("revision")?1:0),
                Timestamp.from(Instant.now().plusSeconds(mutation.equals("future-publication")?60:0)),Timestamp.from(accepted));
            throw new AssertionError("Malformed receipt INSERT must fail");
        });
        ApplySubscriptionSnapshotUseCase instrumented=command->{
            holder.set(TransactionSynchronizationManager.getResource(jdbc.getDataSource()));xact.set(jdbc.queryForObject("select pg_current_xact_id()::text",String.class));
            facts.apply(command);
            assertThat(renewalTime.sampledConnectionHolder).isSameAs(holder.get());assertThat(renewalTime.sampledTransaction).isEqualTo(xact.get());
        };
        assertThatThrownBy(()->publisher(writer,instrumented).publish(admission,paid.ready(),paid)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(financialState()).isEqualTo(before);assertThat(count("stripe_billing_first_renewal_publications")).isZero();
        int calls=http.calls.size();assertThatThrownBy(()->observer().observe(admission,paid.ready())).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(calls);assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(2);
    }

    @Test void publicationAndReceiptBecomeVisibleTogetherAndRevokeWaitsForAccount() throws Exception {
        var admission=paidAdmission();var paid=paid(admission);var applied=new CountDownLatch(1);var release=new CountDownLatch(1);var revoking=new CountDownLatch(1);
        ApplySubscriptionSnapshotUseCase pause=command->{facts.apply(command);applied.countDown();
            try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Publication release timed out");}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}};
        try(var workers=new BoundedWorkers()) {
            var publication=workers.submit(()->publisher(renewals,pause).publish(admission,paid.ready(),paid));
            assertThat(applied.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(count("payments")).isEqualTo(1);assertThat(count("stripe_billing_first_renewal_publications")).isZero();
                var revoke=workers.submit(()->{revoking.countDown();clocks.stop(owner,dev.genericpayments.platform.provider.application.port.out.StripeBillingClockStore.StopReason.AUTHORITY_REVOKED,Instant.now());return true;});
                assertThat(revoking.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->revoke.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();assertThat(publication.get(10,TimeUnit.SECONDS)).isNotNull();assertThat(revoke.get(10,TimeUnit.SECONDS)).isTrue();
            } finally {release.countDown();}
        }
        assertThat(count("payments")).isEqualTo(2);assertThat(count("stripe_billing_first_renewal_publications")).isEqualTo(1);
        var committed=financialState();assertThatThrownBy(this::sample).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->publisher().replayPublished(admission)).isInstanceOf(RuntimeException.class);assertThat(financialState()).isEqualTo(committed);
    }

    @Test void concurrentPaidPublicationHasOneCommitAndOneStaleLoser() throws Exception {
        var admission=paidAdmission();var paid=paid(admission);var start=new CountDownLatch(1);
        try(var workers=new BoundedWorkers()) {
            Callable<Boolean> publish=()->{if(!start.await(5,TimeUnit.SECONDS))throw new AssertionError("Start timed out");
                try{publisher().publish(admission,paid.ready(),paid);return true;}catch(IllegalStateException expected){return false;}};
            var first=workers.submit(publish);var second=workers.submit(publish);start.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(count("payments")).isEqualTo(2);assertThat(count("outbox_events")).isEqualTo(4);assertThat(count("stripe_billing_first_renewal_publications")).isEqualTo(1);
    }

    @Test void acceptedGraphCanPublishAfterOriginalReadDeadlineWithoutNewBudget() throws InterruptedException {
        var admission=paidAdmission();var paid=paid(admission);int calls=http.calls.size();long started=System.nanoTime();
        while(Instant.now().isBefore(paid.claim().deadlineAt().plusMillis(25))) {
            if(System.nanoTime()-started>Duration.ofSeconds(35).toNanos())throw new AssertionError("Original deadline wait exceeded35 seconds");
            Thread.sleep(100);
        }
        var result=publisher().publish(admission,paid.ready(),paid);
        assertThat(result.publishedAt()).isAfter(paid.claim().deadlineAt());assertThat(http.calls).hasSize(calls);
        assertThat(jdbc.queryForObject("select graph_accepted_at from stripe_billing_first_renewal_publications",Timestamp.class).toInstant()).isEqualTo(paid.graphAcceptedAt());
        assertThat(publisher().replayPublished(admission)).isEqualTo(result);assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings={"revision","sequence","policy","refund"})
    void committedReceiptRefusesLaterDrift(String drift) {
        var admission=paidAdmission();var paid=paid(admission);var receipt=publisher().publish(admission,paid.ready(),paid);
        switch(drift) {
            case "revision"->jdbc.update("update subscriptions set revision=revision+1 where id=?",current().id());
            case "sequence"->jdbc.update("update subscriptions set observation_sequence=observation_sequence+1 where id=?",current().id());
            case "policy"->jdbc.update("update site_subscription_policies set version=version+1 where site_id=?",site.value());
            case "refund"->jdbc.update("update payments set refunded_amount_minor=1 where id=?",receipt.payment());
        }
        var before=financialState();
        assertThatThrownBy(()->publisher().replayPublished(admission)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(this::sample).isInstanceOf(RuntimeException.class);assertThat(financialState()).isEqualTo(before);
    }

    @Test void dispatchingSecondStepBlocksOldTimeAndImmutablePredecessorCannotBeChanged() {
        var admission=paidAdmission();var first=advance(admission,Step.BOUNDARY);var pending=boundary(admission,first);
        for(var table:List.of("stripe_billing_first_renewal_boundary","stripe_billing_first_renewal_steps")) {
            assertThatThrownBy(()->jdbc.update("update "+table+" set fingerprint=?","0".repeat(64))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThatThrownBy(()->jdbc.update("delete from stripe_billing_first_renewal_ready where id=?",first.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        inspectSdk(request->{
            if(request.method().name().equals("POST")) {
                assertThat(jdbc.queryForObject("select status from provider_operations where resource_id=(select id from stripe_billing_first_renewal_steps where admission_id=? and ordinal=2)",String.class,admission.id())).isEqualTo("DISPATCHING");
                assertThatThrownBy(this::sample).isInstanceOf(RuntimeException.class);
            }
        });
        advance(admission,Step.FINALIZATION);assertThat(sample().billingAt()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()+3600));
        assertThat(underLifecycle(admission,()->renewals.boundaryInTransaction(admission))).isEqualTo(pending.boundary());
    }

    @Test void laterBillingTimePreservesUtcOperationalTimesLeasesAndWebhookSignatures() throws Exception {
        var admission=paidAdmission();var attempt=jdbc.queryForObject("select row_to_json(t)::text from subscription_confirmation_attempts t",String.class);
        var paid=paid(admission);publisher().publish(admission,paid.ready(),paid);var now=Instant.now();
        for(var table:List.of("payments","subscription_billing_records","outbox_events"))
            assertThat(jdbc.queryForList("select created_at from "+table,Timestamp.class)).allSatisfy(t->assertThat(t.toInstant()).isBetween(began,now));
        for(var table:List.of("payment_events","subscription_events"))
            assertThat(jdbc.queryForList("select received_at from "+table,Timestamp.class)).allSatisfy(t->assertThat(t.toInstant()).isBetween(began,now));
        assertThat(jdbc.queryForList("select next_attempt_at from outbox_events",Timestamp.class)).allSatisfy(t->assertThat(t.toInstant()).isBetween(began,now));
        assertThat(jdbc.queryForObject("select row_to_json(t)::text from subscription_confirmation_attempts t",String.class)).isEqualTo(attempt);
        assertThat(jdbc.queryForObject("select occurred_at from subscription_events where revision=?",Timestamp.class,current().revision()).toInstant())
            .isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()+3600));
        assertThat(jdbc.queryForObject("select occurred_at from payment_events where payment_id=(select id from payments where provider_payment_reference='pi_renewal')",Timestamp.class).toInstant())
            .isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()+11));
        var raw="{\"id\":\"evt_renewal\",\"object\":\"event\",\"type\":\"invoice.paid\",\"livemode\":false,\"api_version\":\"2026-09-30.endive\",\"created\":42,\"data\":{\"object\":{\"id\":\"in_renewal\",\"object\":\"invoice\",\"livemode\":false}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var verifier=new dev.genericpayments.platform.provider.adapter.out.stripe.StripeWebhookVerifier(credentials);
        assertThat(verifier.verifyAny(raw,signature(raw,now.getEpochSecond()),billing,now)).isPresent();
        assertThatThrownBy(()->verifier.verifyAny(raw,signature(raw,receipt.periodEnd()+3600),billing,now)).isInstanceOf(IllegalArgumentException.class);
        assertThat(sample().billingAt()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()+3600));
    }

    private static String signature(byte[] raw,long timestamp) throws Exception {
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec("whsec_INERT".getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
        mac.update((timestamp+".").getBytes(java.nio.charset.StandardCharsets.US_ASCII));return "t="+timestamp+",v1="+HexFormat.of().formatHex(mac.doFinal(raw));
    }

    @Test void neverFallsBackAfterRenewalAdmission() {
        genuineInitialCash();assertThat(sample().billingAt()).isEqualTo(Instant.ofEpochSecond(FROZEN));var admission=admitRenewal();
        assertThatThrownBy(this::sample).as("An admitted renewal without READY cannot reuse original frozen time").isInstanceOf(RuntimeException.class);
        var first=advance(admission,Step.BOUNDARY);
        assertThat(sample().billingAt()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()));
        var pending=boundary(admission,first);renewals.prepare(admission,Step.FINALIZATION,pending.boundary(),Instant.now());
        assertThatThrownBy(this::sample).as("Any prepared step2 immediately supersedes READY1").isInstanceOf(RuntimeException.class);
        advance(admission,Step.FINALIZATION);
        assertThat(sample().billingAt()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()+3600));
        assertThatThrownBy(()->queries.list(new QuerySubscriptionsUseCase.Query(scope.context().actor(),site,customer,null,null,10,null))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->queries.offers(scope.context().actor(),site,customer,current().productId())).isInstanceOf(RuntimeException.class);
        renewals.stop(admission,"PAYMENT_NOT_OBSERVED",Instant.now());
        assertThatThrownBy(this::sample).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->renewalTime.requireUnscopedAllowed(site,customer)).isInstanceOf(RuntimeException.class);
    }

    @Test void laterTimeDoesNotPay() {
        var admission=paidAdmission();var before=financialState();var first=advance(admission,Step.BOUNDARY);
        var boundaryAccess=queries.get(scope.context().actor(),site,current().id()).access();
        assertThat(boundaryAccess.evaluatedAt()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()));
        assertThat(boundaryAccess.allowed()).as("Reaching E with only original cash must deny access").isFalse();
        boundary(admission,first);assertThat(financialState()).isEqualTo(before);
        var second=advance(admission,Step.FINALIZATION);
        var laterAccess=queries.get(scope.context().actor(),site,current().id()).access();
        assertThat(laterAccess.evaluatedAt()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()+3600));assertThat(laterAccess.allowed()).isFalse();
        graph(second,true);assertThat(observer().observe(admission,second)).isInstanceOf(Paid.class);
        assertThat(current().paidThrough()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()));
        assertThat(current().debt()).isNull();assertThat(financialState()).isEqualTo(before);
    }
}
