package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.application.port.out.StripeBillingClockStore.StopReason;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.StripeBillingClockConfirmationCompositionFixture;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** All runtime checks are hosted PostgreSQL/Spring checks. Offline compilation is not acceptance. */
class StripeBillingClockConfirmationCompositionIntegrationTest extends StripeBillingClockConfirmationCompositionFixture {
    @ParameterizedTest @ValueSource(strings={"succeeded","requires_action"})
    void realOriginalCreateActionSelectionDurableConfirmAndFreshCashCompose(String result) {
        beginConfirmation();issueAndSelect();
        inspectSdk(this::assertClaimBeforePost);
        var original=receipt;
        var confirmed=confirm(result);
        assertThat(confirmed.status().state()).isEqualTo(result.equals("succeeded")?SubscriptionConfirmationAttempt.State.OBSERVE:SubscriptionConfirmationAttempt.State.REQUIRES_ACTION);
        assertThat(confirmed.nextActionAllowed()).isEqualTo(result.equals("requires_action"));
        assertThat(confirmed.status().retryAllowed()).isFalse();
        assertThat(confirmed.status().successorAttemptId()).isNull();
        assertThat(confirmPosts()).isEqualTo(1);
        assertNoSuccess();
        assertThat(queries.get(scope.context().actor(),site,current().id()).access().allowed()).isFalse();
        int requests=http.calls.size();
        var replay=confirmation.confirm(scope,selected.attemptId(),selected.capability(),TOKEN);
        assertThat(replay.status()).isEqualTo(confirmed.status());assertThat(replay.nextActionAllowed()).isFalse();
        assertThat(action.nextAction(scope)).isNull();
        assertThat(http.calls).hasSize(requests);
        assertThatThrownBy(()->confirmation.retry(scope,selected.attemptId())).isInstanceOf(RuntimeException.class);
        // Only a separately captured fence and freshly read owned graph may settle CASH.
        var firstFence=fence();var paid=paidObservation();
        assertThat(paid.invoices()).singleElement().satisfies(i->{
            assertThat(i.kind()).isEqualTo(BillingRecord.Kind.INITIAL);assertThat(i.settlement()).isEqualTo(SettlementKind.CASH);
            assertThat(i.cashObjects()).singleElement().satisfies(c->assertThat(c.reference()).isEqualTo("pi_owned"));
        });
        apply(paid,firstFence);assertPaid();
        assertThat(receipt).isEqualTo(original);
        var financial=jdbc.queryForList("select id,payload::text,created_at from outbox_events order by id");
        var journal=jdbc.queryForList("select * from payment_events order by id");
        var nextFence=fence();apply(retrievePaid(),nextFence);assertPaid();
        assertThat(jdbc.queryForList("select id,payload::text,created_at from outbox_events order by id")).isEqualTo(financial);
        assertThat(jdbc.queryForList("select * from payment_events order by id")).isEqualTo(journal);
        var before=financialState();
        assertThatThrownBy(()->apply(paid,nextFence)).hasMessage("OBSERVATION_STALE");
        assertThat(financialState()).isEqualTo(before);
        assertThat(sdkCallbacks).isEqualTo(http.calls.size());
        assertThat(http.replies).isEmpty();
        assertThat(http.calls.stream().filter(r->r.method().name().equals("POST")).map(r->r.url().getPath()).toList())
            .containsExactly("/v1/test_helpers/test_clocks","/v1/customers","/v1/subscriptions","/v1/payment_intents/pi_owned/confirm");
    }

    @ParameterizedTest @ValueSource(strings={"processing","requires_payment_method","requires_confirmation","402","disconnect"})
    void uncertainDeclineOrLostResponseConsumesOriginalAndNeverRetries(String result) {
        beginConfirmation();issueAndSelect();inspectSdk(this::assertClaimBeforePost);
        var outcome=confirm(result);
        assertThat(outcome.status().state()).isEqualTo(SubscriptionConfirmationAttempt.State.UNKNOWN);
        assertThat(outcome.nextActionAllowed()).isFalse();assertThat(outcome.status().retryAllowed()).isFalse();
        assertNoSuccess();assertThat(confirmPosts()).isEqualTo(1);
        int requests=http.calls.size();
        assertThat(confirmation.confirm(scope,selected.attemptId(),selected.capability(),TOKEN).status()).isEqualTo(outcome.status());
        assertThatThrownBy(()->confirmation.retry(scope,selected.attemptId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->confirmation.session(scope,selected.attemptId())).isInstanceOf(RuntimeException.class);
        assertThat(action.nextAction(scope)).isNull();
        assertThat(http.calls).hasSize(requests);
        assertThat(count("subscription_confirmation_attempts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select successor from subscription_confirmation_attempts",UUID.class)).isNull();
        assertThat(jdbc.queryForObject("select request_id from subscription_confirmation_attempts",String.class)).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"key","token","capability"})
    void invalidOrChangedConfirmationCannotAcquireAnotherDispatch(String changed) {
        beginConfirmation();issueAndSelect();
        if(changed.equals("capability")) {
            int calls=http.calls.size();
            assertThatThrownBy(()->confirmation.confirm(scope,selected.attemptId(),"bca2_"+"A".repeat(43),TOKEN)).isInstanceOf(RuntimeException.class);
            assertThat(http.calls).hasSize(calls);
            assertThat(confirmation.status(scope,selected.attemptId()).state()).isEqualTo(SubscriptionConfirmationAttempt.State.READY);
        }
        confirm("succeeded");int calls=http.calls.size();
        var other=changed.equals("key")?scope("other-pay"):scope;
        var token=changed.equals("token")?"ctoken_OTHER":TOKEN;
        var capability=changed.equals("capability")?"bad":selected.capability();
        assertThatThrownBy(()->confirmation.confirm(other,selected.attemptId(),capability,token)).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(calls);assertThat(confirmPosts()).isEqualTo(1);assertNoSuccess();
    }

    @ParameterizedTest @ValueSource(strings={"stopped","session","policy","revision","account"})
    void currentAuthorityDriftBeforeConfirmRejectsWithoutIOOrConsumption(String drift) {
        beginConfirmation();issueAndSelect();
        drift(drift);int calls=http.calls.size();
        assertThatThrownBy(()->confirmation.confirm(scope,selected.attemptId(),selected.capability(),TOKEN)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->confirmation.current(scope)).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(calls);assertThat(confirmPosts()).isZero();
        assertThat(jdbc.queryForObject("select state from subscription_confirmation_attempts",String.class)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("select consumed_at from subscription_action_capabilities",java.sql.Timestamp.class)).isNull();
        assertNoSuccess();
    }

    @ParameterizedTest @ValueSource(strings={"stopped","session","policy","revision"})
    void driftAfterPostKeepsConsumedHistoryAndCannotPublishOrRedispatch(String drift) {
        beginConfirmation();issueAndSelect();
        inspectSdk(request->{assertClaimBeforePost(request);if(confirmPost(request))drift(drift);});
        queueConfirm("succeeded");
        assertThatThrownBy(()->confirmation.confirm(scope,selected.attemptId(),selected.capability(),TOKEN)).isInstanceOf(RuntimeException.class);
        assertThat(confirmPosts()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state from subscription_confirmation_attempts",String.class)).isEqualTo("DISPATCHING");
        assertThat(jdbc.queryForObject("select consumed_at from subscription_action_capabilities",java.sql.Timestamp.class)).isNotNull();
        int calls=http.calls.size();
        assertThatThrownBy(()->confirmation.confirm(scope,selected.attemptId(),selected.capability(),TOKEN)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->confirmation.retry(scope,selected.attemptId())).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(calls);assertNoSuccess();
    }

    @ParameterizedTest @ValueSource(strings={"clock","customer","period","item","chargeCustomer"})
    void corruptFreshGraphCannotAlterAlreadyProjectedCashAndOriginalPeriod(String broken) {
        beginConfirmation();issueAndSelect();confirm("succeeded");
        var initialFence=fence();apply(paidObservation(),initialFence);assertPaid();
        var before=financialState();
        switch(broken) {
            case "clock"->graph.invoice.addProperty("test_clock","clock_foreign");
            case "customer"->graph.invoice.addProperty("customer","cus_foreign");
            case "period"->graph.item().addProperty("current_period_end",receipt.periodEnd()+1);
            case "item"->graph.item().addProperty("id","si_foreign");
            case "chargeCustomer"->graph.charge.addProperty("customer","cus_foreign");
        }
        var fresh=fence();
        assertThatThrownBy(()->apply(retrievePaid(),fresh)).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(before);assertThat(confirmPosts()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"revision","operationVersion","consumedFence"})
    void stalePaidPublicationCannotCreateAnyCashOrCallback(String drift) {
        beginConfirmation();issueAndSelect();confirm("succeeded");
        var old=fence();var paid=paidObservation();
        switch(drift) {
            case "revision"->jdbc.update("update subscriptions set revision=revision+1 where id=?",current().id());
            case "operationVersion"->commands.recordReply(currentOperation().id(),new Reply(Outcome.ACCEPTED,"sub_owned",null));
            case "consumedFence"->transaction.required(()->{observations.consumeFence(current(),old.observationSequence());return null;});
        }
        var before=financialState();
        assertThatThrownBy(()->apply(paid,old)).hasMessage("OBSERVATION_STALE");
        assertThat(financialState()).isEqualTo(before);assertNoSuccess();
    }

    @Test void lostLocalOutcomeAcknowledgementRetainsDurableClaimWithoutAnotherPost() {
        beginConfirmation();issueAndSelect();
        jdbc.execute("create function task3_reject_confirmation_ack() returns trigger language plpgsql as $$ begin if new.state='OBSERVE' then raise exception 'synthetic-confirmation-ack-loss'; end if; return new; end; $$");
        jdbc.execute("create trigger task3_reject_confirmation_ack before update on subscription_confirmation_attempts for each row execute function task3_reject_confirmation_ack()");
        try {
            queueConfirm("succeeded");
            assertThatThrownBy(()->confirmation.confirm(scope,selected.attemptId(),selected.capability(),TOKEN)).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("drop trigger task3_reject_confirmation_ack on subscription_confirmation_attempts");
            jdbc.execute("drop function task3_reject_confirmation_ack()");
        }
        assertThat(jdbc.queryForObject("select state from subscription_confirmation_attempts",String.class)).isEqualTo("DISPATCHING");
        assertThat(jdbc.queryForObject("select version from subscription_confirmation_attempts",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select consumed_at from subscription_action_capabilities",java.sql.Timestamp.class)).isNotNull();
        int calls=http.calls.size();
        var replay=confirmation.confirm(scope,selected.attemptId(),selected.capability(),TOKEN);
        assertThat(replay.status().state()).isEqualTo(SubscriptionConfirmationAttempt.State.DISPATCHING);
        assertThat(replay.status().retryAllowed()).isFalse();assertThat(replay.nextActionAllowed()).isFalse();
        assertThatThrownBy(()->confirmation.retry(scope,selected.attemptId())).isInstanceOf(RuntimeException.class);
        assertThat(action.nextAction(scope)).isNull();assertThat(http.calls).hasSize(calls);
        assertThat(confirmPosts()).isEqualTo(1);assertNoSuccess();
    }

    @Test void publicDefaultAndV1CannotAdoptOwnedRootEvenBeforeChainExists() {
        beginConfirmation();
        assertThatThrownBy(()->ordinary.current(scope)).isInstanceOf(RuntimeException.class);
        openGraph();actionToken=action.nextAction(scope).secret();int calls=http.calls.size();
        assertThatThrownBy(()->ordinary.select(scope,actionToken)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->legacy.issue(new CreateSubscriptionClientSessionUseCase.Command(scope.context(),scope.subscription(),scope.operation(),actionToken))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->legacyConfirm.confirm(new ConfirmSubscriptionActionUseCase.Command(scope.context(),scope.subscription(),scope.operation(),actionToken))).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(calls);
        assertThat(count("subscription_confirmation_chains")).isZero();assertThat(count("subscription_confirmation_attempts")).isZero();
        assertNoSuccess();
    }

    @Test void noActionCanBeReissuedAfterUtcExpiryAndNoClockTimeSubstitution() {
        beginConfirmation();issueAndSelect();
        var root=transaction.required(()->roots.billing(StripeBillingGrantTokens.hash(actionToken)).orElseThrow());
        assertThat(root.issuedAt()).isBetween(began,Instant.now()).isAfter(receipt.acknowledgedAt());
        assertThat(root.expiresAt()).isEqualTo(root.issuedAt().plusSeconds(600));
        assertThat(root.issuedAt().getEpochSecond()).isNotIn(FROZEN,42L,PROVIDER_CREATED);
        var expired=new StripeBillingClockActionAdapter(receipt,phases,roots,tokens,credentials,clients,verification,Clock.fixed(root.expiresAt().plusSeconds(1),ZoneOffset.UTC));
        int calls=http.calls.size();assertThat(expired.nextAction(scope)).isNull();assertThat(http.calls).hasSize(calls);
        assertThat(count("subscription_action_capabilities")).isEqualTo(1);assertNoSuccess();
    }

    private void drift(String drift) {
        switch(drift) {
            case "stopped"->clocks.stop(owner,StopReason.AUTHORITY_REVOKED,Instant.now());
            case "session"->sessions.logout(((SubscriptionActor.Customer)scope.context().actor()).session());
            case "policy"->{var p=policies.load(site);policies.save(new SubscriptionPolicy(site,p.version(),false,3,false,false,false,Set.of()),p.version());}
            case "revision"->jdbc.update("update subscriptions set revision=revision+1 where id=?",current().id());
            case "account"->jdbc.update("update payment_accounts set account_status='DISABLED' where id=?",billing.id().value());
        }
    }
}
