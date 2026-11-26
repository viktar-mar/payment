package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.ResumeSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionAuthorityPort;
import dev.genericpayments.platform.catalog.domain.PriceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import dev.genericpayments.platform.subscriptions.application.port.in.QuerySubscriptionsUseCase;
import dev.genericpayments.platform.subscriptions.domain.AccessDecision;
import dev.genericpayments.platform.support.OwnedOptionalTrialFixture;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;

@Import({OwnedOptionalTrialPublication.Configuration.class,OwnedOptionalTrialResume.Configuration.class,
    OwnedOptionalTrialPaymentConfirmation.Configuration.class})
@TestPropertySource(properties="payment.telemetry.site-keys[0]=s5a-7f87e8d25b6b4ac599a070809e6ec87f")
class OwnedOptionalTrialPublicationIntegrationTest extends OwnedOptionalTrialFixture {
    private static final UUID CASH_RUN=UUID.fromString("7f87e8d2-5b6b-4ac5-99a0-70809e6ec87f");
    @Autowired OwnedOptionalTrialPublication publication;
    @Autowired QuerySubscriptionsUseCase queries;
    @Autowired ApplySubscriptionSnapshotUseCase facts;
    @Autowired StripeCredentialResolver credentials;
    @Autowired StripeClientFactory clients;
    @Autowired SubscriptionAuthorityPort authority;
    @Autowired OwnedOptionalTrialResume resume;
    @Autowired OwnedOptionalTrialPaymentConfirmation paymentConfirmation;
    @Autowired Clock operationalClock;
    @Autowired CallbackConfigurationPort callbacks;

    private OwnedOptionalTrialCreate.Authority start(boolean acknowledge) {
        return start(acknowledge,UUID.randomUUID());
    }

    private OwnedOptionalTrialCreate.Authority start(boolean acknowledge, UUID run) {
        var r = reserveOptionalTrial(run);
        var scope = new OwnedOptionalTrialCreate.Scope(r.owner().run(), r.owner().intent(), r.reserved().subscription().id(),
            r.reserved().operation().id());
        var a = new JdbcOwnedOptionalTrialCreateStore(jdbc).admitted(scope);
        if (acknowledge) {
            OwnedOptionalTrialSdkReplies.queue(http, a, "");
            OwnedOptionalTrialCreate.withDatabase(jdbc, credentials, clients, verification).create(billing, scope);
        }
        http.calls.clear();
        return a;
    }

    private void assertUnpublished() {
        assertThat(jdbc.queryForList("select billing_status from subscriptions", String.class)).containsExactly("INCOMPLETE");
        assertThat(count("subscription_events")).isZero();
        assertThat(count("subscription_billing_records")).isZero();
        assertThat(count("payments")).isZero();
        assertThat(count("payment_events")).isZero();
    }

    @Test void aReservationWithoutAcknowledgementCannotReadOrPublish() {
        var a = start(false);
        assertThatThrownBy(() -> publication.publish(billing, a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).isEmpty();
        assertUnpublished();
    }

    @Test void independentlyReadSyntheticSdkGraphPublishesOneZeroBillAccessAndJournalThroughTheApplication() {
        var a = start(true);
        assertUnpublished();
        var session = sessions.issue(site, customer, "https://shop.test");
        var actor = new SubscriptionActor.Customer(sessions.resolveRequired(session.rawToken(), "https://shop.test"));
        var product = new dev.genericpayments.platform.catalog.domain.ProductId(a.product());
        assertThat(queries.access(actor, site, customer, product).allowed()).isFalse();
        OwnedOptionalTrialSdkReplies.observation(http, a, "");
        http.afterResponse = new Runnable() {
            public void run() {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                http.afterResponse = this;
            }
        };
        publication.publish(billing, a.scope());
        assertThat(http.calls).isNotEmpty().allMatch(call -> call.method().name().equals("GET"));
        assertThat(jdbc.queryForObject("""
            select count(*) from subscription_billing_records where subscription_id=? and provider_invoice_reference='in_trial'
              and settlement='ZERO_AMOUNT' and amount_due_minor=0 and extract(epoch from period_start)=?
              and extract(epoch from period_end)=?
            """, Integer.class, a.subscription(), FROZEN, FROZEN + 604800L)).isOne();
        assertThat(jdbc.queryForList("select event_type from subscription_events", String.class)).containsExactly("TRIAL_STARTED");
        assertThat(jdbc.queryForList("select status from subscription_operations", String.class)).containsExactly("SUCCEEDED");
        var view = queries.get(actor, site, a.subscription());
        assertThat(queries.access(actor, site, customer, product)).isEqualTo(view.access());
        assertThat(view.access().allowed()).isTrue();
        assertThat(view.access().reason()).isEqualTo(AccessDecision.Reason.TRIAL);
        assertThat(view.subscription().billingStatus().name()).isEqualTo("TRIALING");
        assertThat(view.subscription().paidThrough()).isNull();
        assertThat(count("payments")).isZero();
        assertThat(count("payment_events")).isZero();
        int reads = http.calls.size();
        assertThatThrownBy(() -> publication.publish(billing, a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(reads);
        assertThat(count("subscription_events")).isOne();
        assertThat(count("subscription_billing_records")).isOne();
    }

    @ParameterizedTest @ValueSource(strings={"foreign line", "allocation", "extra invoice", "wrong behavior", "advanced clock", "stopped", "stale fence"})
    void changedEvidenceCannotGrantAccessOrPostCash(String fault) {
        var a = start(true);
        OwnedOptionalTrialSdkReplies.observation(http, a, fault);
        if (fault.equals("stopped")) http.afterResponse = () -> jdbc.update("""
            update stripe_billing_clock_intents set stopped_at=clock_timestamp(), stop_reason='AUTHORITY_REVOKED' where id=?
            """, a.clockIntent());
        if (fault.equals("stale fence")) http.afterResponse = () -> jdbc.update(
            "update subscriptions set observation_sequence=observation_sequence+1 where id=?", a.subscription());
        assertThatThrownBy(() -> publication.publish(billing, a.scope())).isInstanceOf(IllegalStateException.class);
        assertUnpublished();
    }

    @Test void exactTestClockExpiryPublishesPauseOnceWithoutDebtOrCash() {
        var a = start(true);
        OwnedOptionalTrialSdkReplies.observation(http, a, "");
        publication.publish(billing, a.scope());
        var originalBill = jdbc.queryForObject("select id from subscription_billing_records", UUID.class);
        http.calls.clear();
        OwnedOptionalTrialPauseSdkTest.queuePaused(http, a, "");
        publication.pause(billing, a.scope());

        assertThat(http.calls).isNotEmpty().allMatch(call -> call.method().name().equals("GET"));
        assertThat(jdbc.queryForList("select billing_status from subscriptions", String.class)).containsExactly("PAUSED");
        assertThat(jdbc.queryForList("select id from subscription_billing_records", UUID.class)).containsExactly(originalBill);
        assertThat(jdbc.queryForList("select event_type from subscription_events order by sequence_number", String.class))
            .containsExactly("TRIAL_STARTED", "PAUSED");
        assertThat(jdbc.queryForObject("select extract(epoch from occurred_at)::bigint from subscription_events where event_type='PAUSED'", Long.class))
            .isEqualTo(a.trialEnd());
        assertThat(jdbc.queryForObject("select count(*) from subscriptions where debt_billing_record_id is not null or paid_through is not null", Integer.class)).isZero();
        assertThat(count("payments")).isZero();
        assertThat(count("payment_events")).isZero();
        var session = sessions.issue(site, customer, "https://shop.test");
        var actor = new SubscriptionActor.Customer(sessions.resolveRequired(session.rawToken(), "https://shop.test"));
        assertThat(queries.get(actor, site, a.subscription()).access().allowed()).isFalse();
        int reads = http.calls.size();
        assertThatThrownBy(() -> publication.pause(billing, a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(reads);
    }

    @Test void revokingClockOwnershipDuringPauseReadPreventsPublication() {
        var a=start(true);
        OwnedOptionalTrialSdkReplies.observation(http,a,"");
        publication.publish(billing,a.scope());
        OwnedOptionalTrialPauseSdkTest.queuePaused(http,a,"");
        http.afterResponse=()->jdbc.update("""
            update stripe_billing_clock_intents set stopped_at=clock_timestamp(),stop_reason='AUTHORITY_REVOKED' where id=?
            """,a.clockIntent());
        assertThatThrownBy(()->publication.pause(billing,a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(count("stripe_billing_optional_trial_pauses")).isZero();
        assertThat(jdbc.queryForList("select billing_status from subscriptions",String.class)).containsExactly("TRIALING");
        assertThat(jdbc.queryForList("select event_type from subscription_events",String.class)).containsExactly("TRIAL_STARTED");
        assertThat(count("payments")).isZero();
    }

    @Test void anIsolatedPauseReceiptCannotCommitWithoutTheMatchingPauseEvent() {
        var a=start(true);
        OwnedOptionalTrialSdkReplies.observation(http,a,"");
        publication.publish(billing,a.scope());
        var bill=jdbc.queryForObject("select id from subscription_billing_records",UUID.class);
        var started=jdbc.queryForObject("select id from subscription_events where event_type='TRIAL_STARTED'",UUID.class);
        assertThatThrownBy(()->jdbc.update("""
            insert into stripe_billing_optional_trial_pauses
              (clock_intent,subscription_id,create_operation,frozen_time,original_bill_id,trial_started_event_id)
            values (?,?,?,?,?,?)
            """,a.clockIntent(),a.subscription(),a.createOperation(),a.trialEnd(),bill,started))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("stripe_billing_optional_trial_pauses")).isZero();
        assertThat(jdbc.queryForList("select billing_status from subscriptions",String.class)).containsExactly("TRIALING");
    }

    @ParameterizedTest @ValueSource(strings={"","lost ack","late clock advance","confirmation lost ack",
        "confirmation wrong reply","confirmation wrong charge","confirmation late substituted allocation"})
    void setupAndExplicitResumeKeepTheOriginalLedgerAndNeverReplay(String fault) throws Exception {
        var a=start(true,CASH_RUN);
        callbacks.configure(site.value(),"http://127.0.0.1:8081/callback",true);
        var original=new OwnedOptionalTrialCreate.Created("sub_owned","si_owned","in_trial",a.frozen());
        OwnedOptionalTrialSdkReplies.observation(http,a,"");
        publication.publish(billing,a.scope());
        var clockAdvance=OwnedOptionalTrialClockAdvance.withDatabase(jdbc,credentials,clients,verification);
        int callsBeforeAdvance=http.calls.size();
        assertThatThrownBy(()->publication.publishAdvancedPause(billing,a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(callsBeforeAdvance);
        OwnedOptionalTrialClockAdvanceSdkReplies.queue(http,a,"");
        OwnedOptionalTrialPauseSdkTest.queuePaused(http,a,"");
        publication.advanceAndPause(billing,a.scope(),clockAdvance);
        int callsAfterAdvance=http.calls.size();
        assertThatThrownBy(()->publication.advanceAndPause(billing,a.scope(),clockAdvance))
            .isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(callsAfterAdvance);
        assertThat(http.calls.stream().filter(call->call.method().name().equals("POST")
            &&call.url().getPath().endsWith("/advance"))).hasSize(1);
        var originalBill=jdbc.queryForObject("select original_bill_id from stripe_billing_optional_trial_pauses",UUID.class);
        var originalEvent=jdbc.queryForObject("select trial_started_event_id from stripe_billing_optional_trial_pauses",UUID.class);
        var pausedEvent=jdbc.queryForObject("select id from subscription_events where event_type='PAUSED'",UUID.class);
        var originalParameters=List.of(a.site(),"s5a-"+a.run().toString().replace("-",""),a.customer(),a.account(),a.product(),
            a.price(),a.subscription(),a.createOperation(),originalBill,originalEvent,original.subscription(),original.invoice(),
            Timestamp.from(Instant.ofEpochSecond(a.frozen())),Timestamp.from(Instant.ofEpochSecond(a.trialEnd())));
        assertOneSqlProof("pause",originalParameters);
        var session=sessions.issue(site,customer,"https://shop.test");
        var actor=new SubscriptionActor.Customer(sessions.resolveRequired(session.rawToken(),"https://shop.test"));
        var setupContext=new SubscriptionCommandContext(actor,site,customer,"https://shop.test","owned-setup","setup-request");
        var resumeContext=new SubscriptionCommandContext(actor,site,customer,"https://shop.test","owned-resume","resume-request");
        var setup=new OwnedOptionalTrialSetup(jdbc,transaction,authority,lifecycleOperations,subscriptions,digest,
            credentials,clients,verification,publication,operationalClock);
        OwnedOptionalTrialSetupSdkTest.queuePrepare(http,a);
        var prepared=setup.prepare(billing,a.scope(),setupContext,true);
        OwnedOptionalTrialSetupSdkTest.queueComplete(http,a,prepared);
        setup.complete(billing,a.scope(),setupContext,prepared);
        int callsAfterSetup=http.calls.size();
        assertThatThrownBy(()->setup.complete(billing,a.scope(),setupContext,prepared)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->setup.prepare(billing,a.scope(),setupContext,true)).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(callsAfterSetup);
        var saved=proofs.setup(billing.id(),prepared.operation()).orElseThrow();
        assertThat(jdbc.queryForObject("select status from subscription_operations where id=?",String.class,prepared.operation()))
            .isEqualTo("SUCCEEDED");
        assertThat(queries.get(actor,site,a.subscription()).access().allowed()).isFalse();
        assertThat(count("subscription_billing_records")).isOne();
        assertThat(count("payments")).isZero();

        var command=new ResumeSubscriptionUseCase.Command(resumeContext,a.subscription(),true);
        var resumeFault=fault.startsWith("confirmation ")?"":fault;
        OwnedOptionalTrialResumeSdkReplies.dispatch(http,a,original,prepared.operation(),saved.reference(),saved.method(),resumeFault);
        var attempt=publication.startResume(billing,a.scope(),prepared.operation(),command,resume);
        var reserved=attempt.result();
        assertThat(reserved.status().name()).isEqualTo(resumeFault.isEmpty()?"PROCESSING":"UNKNOWN");
        int callsBeforePrematurePay=http.calls.size();
        assertThatThrownBy(()->publication.startResume(billing,a.scope(),prepared.operation(),command,resume))
            .isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(callsBeforePrematurePay);
        assertThatThrownBy(()->publication.observeResume(billing,a.scope(),prepared.operation(),reserved.operationId(),resume,true))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->publication.confirmResumePayment(billing,a.scope(),prepared.operation(),reserved.operationId(),
            paymentConfirmation)).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(callsBeforePrematurePay);
        if(!resumeFault.isEmpty()){
            assertThat(jdbc.queryForObject("""
                select status from provider_operations where resource_id=? and kind='SUBSCRIPTION_UPDATE'
                  and manual_review_required
                """,String.class,reserved.operationId())).isEqualTo("UNKNOWN");
            assertThat(count("stripe_billing_optional_trial_pending_resumptions")).isZero();
            assertThat(count("payments")).isZero();
            assertThat(count("subscription_billing_records")).isOne();
            assertThat(http.calls.stream().filter(call->call.method().name().equals("POST")
                &&call.url().getPath().endsWith("/resume"))).hasSize(resumeFault.equals("lost ack")?1:0);
            return;
        }

        OwnedOptionalTrialResumeSdkReplies.observation(http,a,original,prepared.operation(),saved.reference(),saved.method(),false,"");
        publication.observeResume(billing,a.scope(),prepared.operation(),reserved.operationId(),resume,false);
        var bill=jdbc.queryForObject("""
            select id from subscription_billing_records where provider_invoice_reference='in_resume' and settlement='OPEN'
            """,UUID.class);
        assertThat(bill).isNotNull();
        assertThat(queries.get(actor,site,a.subscription()).access().allowed()).isFalse();
        assertThat(count("payments")).isZero();
        var resumeProof=proofs.resume(billing.id(),reserved.operationId()).orElseThrow();
        var resumeParameters=new ArrayList<Object>(originalParameters);
        resumeParameters.addAll(List.of(pausedEvent,prepared.operation(),reserved.operationId(),resumeProof.invoice(),
            Timestamp.from(resumeProof.period().start()),Timestamp.from(resumeProof.period().end()),bill));
        resumeParameters.add(null);
        resumeParameters.add("PENDING");
        assertOneSqlProof("resume",resumeParameters);

        int callsBeforeConfirmation=http.calls.size();
        assertThatThrownBy(()->publication.observeResume(billing,a.scope(),prepared.operation(),reserved.operationId(),resume,true))
            .isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(callsBeforeConfirmation);
        var confirmationFault=fault.startsWith("confirmation ")?fault.substring("confirmation ".length()):"";
        OwnedOptionalTrialResumeSdkReplies.confirmation(http,a,original,prepared.operation(),saved.reference(),saved.method(),
            confirmationFault);
        if(!confirmationFault.isEmpty()&&!confirmationFault.equals("wrong charge")) {
            assertThatThrownBy(()->publication.confirmResumePayment(billing,a.scope(),prepared.operation(),reserved.operationId(),
                paymentConfirmation)).isInstanceOf(IllegalStateException.class);
            int callsAfterFailure=http.calls.size();
            assertThatThrownBy(()->publication.confirmResumePayment(billing,a.scope(),prepared.operation(),reserved.operationId(),
                paymentConfirmation)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->publication.observeResume(billing,a.scope(),prepared.operation(),reserved.operationId(),resume,true))
                .isInstanceOf(IllegalStateException.class);
            assertThat(http.calls).hasSize(callsAfterFailure);
            assertThat(http.calls.stream().filter(call->call.method().name().equals("POST")
                &&call.url().getPath().endsWith("/confirm")&&call.url().getPath().contains("/payment_intents/")))
                .hasSize(confirmationFault.equals("late substituted allocation")?0:1);
            assertThat(jdbc.queryForObject("select status from stripe_billing_owned_optional_trial_payment_confirmations",
                String.class)).isEqualTo("UNKNOWN");
            assertThat(count("payments")).isZero();
            assertOneSqlProof("resume",resumeParameters);
            return;
        }
        publication.confirmResumePayment(billing,a.scope(),prepared.operation(),reserved.operationId(),paymentConfirmation);
        int callsAfterConfirmation=http.calls.size();
        assertThatThrownBy(()->publication.confirmResumePayment(billing,a.scope(),prepared.operation(),reserved.operationId(),
            paymentConfirmation)).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(callsAfterConfirmation);
        assertThat(count("payments")).isZero();
        assertOneSqlProof("resume",resumeParameters);

        OwnedOptionalTrialResumeSdkReplies.observation(http,a,original,prepared.operation(),saved.reference(),saved.method(),true,"");
        if(confirmationFault.equals("wrong charge")) {
            assertThatThrownBy(()->publication.observeResume(billing,a.scope(),prepared.operation(),reserved.operationId(),resume,true))
                .isInstanceOf(IllegalStateException.class);
            assertThat(count("payments")).isZero();
            assertThat(queries.get(actor,site,a.subscription()).access().allowed()).isFalse();
            assertOneSqlProof("resume",resumeParameters);
            return;
        }
        publication.observeResume(billing,a.scope(),prepared.operation(),reserved.operationId(),resume,true);
        assertThat(queries.get(actor,site,a.subscription()).access().reason()).isEqualTo(AccessDecision.Reason.PAID);
        assertThat(jdbc.queryForObject("""
            select id from subscription_billing_records where provider_invoice_reference='in_resume' and settlement='CONFIRMED_CASH'
            """,UUID.class)).isEqualTo(bill);
        assertThat(jdbc.queryForList("select event_type from subscription_events order by sequence_number",String.class))
            .containsExactly("TRIAL_STARTED","PAUSED","PERIOD_PAID");
        assertThat(jdbc.queryForList("select amount_minor from payments",Long.class)).containsExactly(2000L);
        assertThat(jdbc.queryForList("select kind from subscription_billing_records order by period_start",String.class))
            .containsExactly("INITIAL","RENEWAL");
        assertThat(jdbc.queryForObject("select status from subscription_operations where id=?",String.class,reserved.operationId()))
            .isEqualTo("SUCCEEDED");
        resumeParameters.set(21,"pi_resume");
        resumeParameters.set(22,"PAID");
        assertOneSqlProof("resume",resumeParameters);
        assertThat(http.calls.stream().filter(call->call.method().name().equals("POST")
            &&call.url().getPath().endsWith("/resume"))).hasSize(1);
        assertThat(http.calls.stream().filter(call->call.method().name().equals("POST")
            &&call.url().getPath().endsWith("/confirm")&&call.url().getPath().contains("/payment_intents/"))).hasSize(1);
        int callsAfterPayment=http.calls.size();
        assertThatThrownBy(()->publication.observeResume(billing,a.scope(),prepared.operation(),reserved.operationId(),resume,true))
            .isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(callsAfterPayment);
    }

    @ParameterizedTest @ValueSource(strings={"lost reply","wrong ready"})
    void onlyDurablyObservedClockCanPublishPause(String fault) {
        var a=start(true);
        OwnedOptionalTrialSdkReplies.observation(http,a,"");
        publication.publish(billing,a.scope());
        var advance=OwnedOptionalTrialClockAdvance.withDatabase(jdbc,credentials,clients,verification);
        OwnedOptionalTrialClockAdvanceSdkReplies.queue(http,a,fault);
        assertThatThrownBy(()->publication.advanceAndPause(billing,a.scope(),advance)).isInstanceOf(IllegalStateException.class);
        int callsAfterFailure=http.calls.size();
        assertThatThrownBy(()->publication.advanceAndPause(billing,a.scope(),advance)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->publication.publishAdvancedPause(billing,a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(callsAfterFailure);
        assertThat(jdbc.queryForList("select billing_status from subscriptions",String.class)).containsExactly("TRIALING");
        assertThat(count("stripe_billing_optional_trial_pauses")).isZero();
        assertThat(count("payments")).isZero();

        if(fault.equals("wrong ready")) {
            http.reply(OwnedOptionalTrialClockAdvanceSdkReplies.ready(a,a.trialEnd()));
            assertThat(advance.observeReady(billing,a.scope())).isEqualTo(a.trialEnd());
            OwnedOptionalTrialPauseSdkTest.queuePaused(http,a,"");
            publication.publishAdvancedPause(billing,a.scope());
            assertThat(jdbc.queryForList("select billing_status from subscriptions",String.class)).containsExactly("PAUSED");
        } else {
            assertThatThrownBy(()->publication.pause(billing,a.scope())).isInstanceOf(IllegalStateException.class);
            assertThat(http.calls).hasSize(callsAfterFailure);
            assertThat(count("stripe_billing_optional_trial_pauses")).isZero();
            assertThat(jdbc.queryForList("select billing_status from subscriptions",String.class)).containsExactly("TRIALING");
        }
        assertThat(http.calls.stream().filter(call->call.method().name().equals("POST")
            &&call.url().getPath().endsWith("/advance"))).hasSize(1);
        assertThat(http.replies).isEmpty();
    }

    private void assertOneSqlProof(String phase, List<?> parameters) throws Exception {
        var sql=Files.readString(Path.of("../infrastructure/stripe-billing-optional-trial-"+phase+"-proof.sql"))
            .replaceAll("\\$[0-9]+","?");
        try(var connection=jdbc.getDataSource().getConnection()){
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement(sql)){
                for(int i=0;i<parameters.size();i++)statement.setObject(i+1,parameters.get(i));
                try(var rows=statement.executeQuery()){
                    assertThat(rows.next()).as("exact "+phase+" proof").isTrue();
                    assertThat(rows.next()).isFalse();
                }
            }
            connection.rollback();
        }
    }

    @Test void lostSyntheticSdkResponseCommitsUnknownAndRefusesAnyPublicationRead() {
        var a = start(false);
        OwnedOptionalTrialSdkReplies.queue(http, a, "lost ack");
        assertThatThrownBy(() -> OwnedOptionalTrialCreate.withDatabase(jdbc, credentials, clients, verification)
            .create(billing, a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select status from stripe_billing_owned_optional_trial_creates",String.class)).isEqualTo("UNKNOWN");
        http.calls.clear();
        assertThatThrownBy(() -> publication.publish(billing, a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).isEmpty();
        assertUnpublished();
    }

    @Test void identicalFactsWithoutAnIndependentlyObservedTransactionBindingCannotPublish() {
        var a = start(true);
        var command = forgedCommand(jdbc, billing, a);
        assertThatThrownBy(() -> transaction.required(() -> { facts.apply(command); return null; }))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Optional trial publication authority unavailable");
        assertUnpublished();
        assertThat(http.calls).isEmpty();
    }

    static ApplySubscriptionSnapshotUseCase.Command forgedCommand(org.springframework.jdbc.core.JdbcTemplate jdbc,
            dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount billing, OwnedOptionalTrialCreate.Authority a) {
        var receipt = jdbc.queryForObject("""
            select a.*,s.revision,s.observation_sequence,o.version from stripe_billing_optional_trial_acknowledgements a
            join subscriptions s on s.id=a.subscription_id join subscription_operations o on o.id=a.create_operation
            """, (r,n)->new OwnedOptionalTrialPublication.Receipt(a,
                new OwnedOptionalTrialCreate.Created(r.getString("subscription_reference"),r.getString("item_reference"),
                    r.getString("invoice_reference"),r.getLong("trial_start")),
                r.getObject("step_id",UUID.class),r.getLong("revision"),r.getLong("version"),r.getLong("observation_sequence")));
        return OwnedOptionalTrialPublication.observedCommand(billing, receipt, Instant.now());
    }

    private UUID createSibling(UUID originalPrice) {
        var product = UUID.randomUUID();
        var price = UUID.randomUUID();
        jdbc.update("""
            insert into products(id,site_id,product_key,name,status,created_at,updated_at)
            values (?,?,'second-plan','Sibling','ACTIVE',now(),now())
            """,product,site.value());
        jdbc.update("""
            insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,
              billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at)
            select ?,site_id,?,'sibling-monthly',1,price_type,amount_minor,currency,billing_interval,trial_days,
              trial_payment_method_policy,tax_mode,status,published_at,now(),now() from prices where id=?
            """,price,product,originalPrice);
        var session=sessions.issue(site,customer,"https://shop.test");
        var actor=new SubscriptionActor.Customer(sessions.resolveRequired(session.rawToken(),"https://shop.test"));
        var ctx=new SubscriptionCommandContext(actor,site,customer,"https://shop.test","sibling-create","sibling-request");
        return commands.reserveCreate(new CreateSubscriptionUseCase.Command(ctx,new PriceId(price),true)).subscription().id();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void sameCustomerSiblingCreatedAfterAckCannotBorrowTrialTimeOrPublish(boolean duringSdkRead) {
        var a = start(true);
        UUID[] sibling = new UUID[1];
        if (duringSdkRead) {
            OwnedOptionalTrialSdkReplies.observation(http,a,"");
            http.afterResponse = () -> sibling[0] = createSibling(a.price());
        } else {
            sibling[0] = createSibling(a.price());
            assertThatThrownBy(() -> transaction.required(() -> publication.sample(billing.id(), site, customer, Instant.now())))
                .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> publication.publish(billing, a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(sibling[0]).isNotNull();
        assertThatThrownBy(() -> transaction.required(() -> publication.sample(billing.id(), site, customer, Instant.now())))
            .isInstanceOf(IllegalStateException.class);
        var session = sessions.issue(site, customer, "https://shop.test");
        var actor = new SubscriptionActor.Customer(sessions.resolveRequired(session.rawToken(), "https://shop.test"));
        assertThatThrownBy(() -> queries.get(actor, site, sibling[0])).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForList("select billing_status from subscriptions",String.class)).containsExactly("INCOMPLETE","INCOMPLETE");
        assertThat(count("subscription_events")).isZero();
        assertThat(count("subscription_billing_records")).isZero();
        assertThat(count("payments")).isZero();
    }

    @Test void journalFailureRollsBackTheBillReservationAndAccessTogether() {
        var a = start(true);
        OwnedOptionalTrialSdkReplies.observation(http, a, "");
        jdbc.execute("""
            create function test_reject_trial_event() returns trigger language plpgsql as $$
            begin raise exception 'Injected trial journal failure' using errcode='23514'; end $$;
            create trigger test_reject_trial_event before insert on subscription_events
            for each row when (new.event_type='TRIAL_STARTED') execute function test_reject_trial_event()
            """);
        try {
            assertThatThrownBy(() -> publication.publish(billing, a.scope())).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Injected trial journal failure");
        } finally {
            jdbc.execute("drop trigger test_reject_trial_event on subscription_events");
            jdbc.execute("drop function test_reject_trial_event()");
        }
        assertUnpublished();
        assertThat(jdbc.queryForObject("select consumed_at is null from subscription_trial_eligibility", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select observation_sequence from subscriptions", Long.class)).isZero();
        OwnedOptionalTrialSdkReplies.observation(http, a, "");
        publication.publish(billing, a.scope());
        assertThat(count("subscription_events")).isOne();
        assertThat(count("subscription_billing_records")).isOne();
        assertThat(count("payments")).isZero();
    }
}
