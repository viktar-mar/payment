package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingClockStore.StopReason;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionObjectPort;
import dev.genericpayments.platform.support.StripeBillingClockSubscriptionCompositionFixture;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

/** Authored/compiled offline ONLY. Runtime proof requires the complete hosted PostgreSQL/Spring gate. */
class StripeBillingClockSubscriptionCompositionIntegrationTest extends StripeBillingClockSubscriptionCompositionFixture {
    @Autowired QuerySubscriptionsUseCase queries;

    @Test void actualReservationClockCustomerCreateReceiptFenceAndOpenProjectionCompose() {
        var reply=createOwned();
        assertThat(count("subscriptions")).isEqualTo(1); assertThat(count("subscription_operations")).isEqualTo(1);
        assertThat(commands.reserveCreate(createCommand).owner()).isFalse();
        var receipt=owned.requireReceipt(billing.id(),current().id());
        assertThat(receipt.intent().createOperation()).isEqualTo(reserved.operation().id());
        assertThat(receipt.intent().subscription()).isEqualTo(reserved.subscription().id());
        assertThat(receipt.intent().clock().owner()).isEqualTo(owner);
        assertThat(receipt.intent().customerCreationOperation()).isEqualTo(customerOperation(owner).id());
        assertThat(receipt.intent().customerReference()).isEqualTo("cus_owned");
        assertThat(receipt.providerCreated()).isEqualTo(42).isNotEqualTo(FROZEN);
        assertThat(receipt.periodStart()).isLessThan(FROZEN); assertThat(receipt.periodEnd()).isGreaterThan(FROZEN);
        assertThat(receipt.acknowledgedAt()).isBetween(began,Instant.now());
        assertThat(current().providerReference()).isEqualTo(receipt.reference());
        assertThat(currentOperation().providerReference()).isEqualTo(receipt.reference());
        assertThat(current().revision()).isGreaterThan(reserved.subscription().revision());
        var step=operations.findById(receipt.providerOperation()).orElseThrow();
        assertThat(step.status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
        assertThat(step.firstDispatchedAt()).isBetween(began,Instant.now());
        assertThat(step.retryBefore()).isEqualTo(step.firstDispatchedAt().plus(Duration.ofHours(23)));
        int calls=http.calls.size();
        assertThat(ownedAdapter.createOwned(new Command(billing,currentOperation(),current().billingPrice(),null,null,null,null,true,false),owner)).isEqualTo(reply);
        assertThat(http.calls).hasSize(calls);
        var fence=fence();var snapshot=retrieve();
        assertThat(snapshot.observedAt()).isBetween(began,Instant.now());
        assertThat(snapshot.observedAt().getEpochSecond()).isNotIn(FROZEN,42L,PROVIDER_CREATED);
        assertThat(snapshot.providerRevision()).isZero();
        assertThat(snapshot.operationObservation().state()).isEqualTo(OperationState.PENDING);
        assertThat(snapshot.invoices()).singleElement().satisfies(i->{assertThat(i.settlement()).isEqualTo(SettlementKind.OPEN);assertThat(i.cashObjects()).isEmpty();});
        apply(snapshot,fence);
        assertThat(billingTime.lastSample.isolated()).isTrue();
        assertThat(billingTime.lastSample.billingAt()).isEqualTo(Instant.ofEpochSecond(FROZEN));
        assertThat(jdbc.queryForList("select settlement from subscription_billing_records",String.class)).containsExactly("OPEN");
        assertThat(objects.resolve(billing.provider(),billing.id(),SubscriptionObjectPort.Kind.SUBSCRIPTION,"sub_owned")).contains(current().id());
        assertThat(objects.resolve(billing.provider(),billing.id(),SubscriptionObjectPort.Kind.INVOICE,"in_owned")).contains(current().id());
        assertThat(jdbc.queryForList("select created_at from subscription_billing_records",java.sql.Timestamp.class)).allSatisfy(t->assertThat(t.toInstant()).isBetween(began,Instant.now()));
        assertThat(jdbc.queryForList("select created_at from subscription_provider_objects",java.sql.Timestamp.class)).allSatisfy(t->assertThat(t.toInstant()).isBetween(began,Instant.now()));
        assertNoSuccess();
        var view=queries.get(createCommand.context().actor(),site,current().id());
        assertThat(view.access().allowed()).isFalse();
        assertThat(view.access().reason()).isEqualTo(dev.genericpayments.platform.subscriptions.domain.AccessDecision.Reason.INITIAL_PAYMENT_REQUIRED);
        assertThat(view.access().evaluatedAt()).isEqualTo(Instant.ofEpochSecond(FROZEN));
        assertThat(view.permittedActions()).isEmpty();
        var repeatedFence=fence();apply(retrieve(),repeatedFence);

        assertThat(count("subscription_billing_records")).isEqualTo(1);
        assertThat(count("subscription_provider_objects")).isEqualTo(2);
        assertNoSuccess();
        assertThatThrownBy(()->apply(snapshot,repeatedFence)).isInstanceOf(IllegalStateException.class).hasMessage("OBSERVATION_STALE");
        assertThat(sdkCallbacks).isEqualTo(http.calls.size());
        assertThat(http.calls.stream().filter(r->r.method().name().equals("POST")).map(r->r.url().getPath()).toList())
            .containsExactly("/v1/test_helpers/test_clocks","/v1/customers","/v1/subscriptions");
        for(var request:http.calls){assertThat(request.options().getMaxNetworkRetries()).isZero();assertThat(request.headers().map().get("Stripe-Version")).containsExactly(StripeBillingTestClockAdapter.API_VERSION);}
    }

    @ParameterizedTest @ValueSource(strings={"localRevision","operationVersion","providerRevision","consumedFence"})
    void stalePublicationCannotWriteBillCashOrJournal(String changed) {
        createOwned();var fence=fence();var snapshot=retrieve();
        switch(changed){
            case "localRevision"->jdbc.update("update subscriptions set revision=revision+1 where id=?",current().id());
            case "operationVersion"->commands.recordReply(currentOperation().id(),new Reply(Outcome.ACCEPTED,"sub_owned",null));
            case "consumedFence"->transaction.required(()->{observations.consumeFence(current(),fence.observationSequence());return null;});
            case "providerRevision"->snapshot=new Snapshot(snapshot.subscriptionReference(),snapshot.status(),snapshot.billingPrice(),snapshot.period(),null,null,snapshot.invoices(),snapshot.operationKey(),false,1,snapshot.observedAt(),snapshot.operationObservation());
        }
        var stale=snapshot;
        assertThatThrownBy(()->apply(stale,fence)).isInstanceOf(IllegalStateException.class).hasMessage("OBSERVATION_STALE");
        assertThat(count("subscription_billing_records")).isZero();assertNoSuccess();
    }

    @ParameterizedTest @ValueSource(strings={"clock","customer","period","item"})
    void wrongOriginalGraphCannotReachProjection(String broken) {
        createOwned();
        switch(broken){case "clock"->graph.invoice.addProperty("test_clock","clock_foreign");case "customer"->graph.invoice.addProperty("customer","cus_foreign");
            case "period"->graph.item().addProperty("current_period_end",1702678401L);case "item"->graph.item().addProperty("id","si_foreign");}
        assertThatThrownBy(this::retrieve).isInstanceOf(PaymentApplicationException.class);
        assertThat(count("subscription_billing_records")).isZero();assertNoSuccess();
    }

    @ParameterizedTest @ValueSource(strings={"intentOnly","readyOnly","customerOnly","receiptBeforePublication"})
    void admittedMissingLineageNeverFallsBackToOperationalTime(String gap) {
        var command=reserve();owner=admit();
        if(!gap.equals("intentOnly"))createReady(owner);
        if(gap.equals("customerOnly")||gap.equals("receiptBeforePublication"))createCustomer(owner);
        if(gap.equals("receiptBeforePublication")){
            http.reply(clockJson(owner));http.reply(graph.remote.toString());
            assertThat(ownedAdapter.createOwned(command,owner).outcome()).isEqualTo(Outcome.ACCEPTED);
        }
        assertThatThrownBy(()->transaction.required(()->billingTime.sample(billing.id(),site,customer,Instant.now())))
            .isInstanceOf(IllegalStateException.class).hasMessage("OWNED_TIME_AUTHORITY_MISSING");
        assertThatThrownBy(()->billingTime.requireUnscopedAllowed(site,customer)).hasMessage("OWNED_TIME_UNSCOPED");
    }

    @ParameterizedTest @ValueSource(strings={"stopped","accountIdentity","foreignAccount","accountDisabled","siteDisabled"})
    void exactOwnerTimeRejectsStoppedOrForeignAuthorityAndRollsBackFence(String invalid) {
        createOwned();var fence=fence();var snapshot=retrieve();
        switch(invalid){
            case "stopped"->clocks.stop(owner,StopReason.AUTHORITY_REVOKED,Instant.now());
            case "accountIdentity"->jdbc.update("update payment_accounts set external_account_id='acct_foreign' where id=?",billing.id().value());
            case "accountDisabled"->jdbc.update("update payment_accounts set account_status='DISABLED' where id=?",billing.id().value());
            case "siteDisabled"->jdbc.update("update sites set status='DISABLED' where id=?",site.value());
            case "foreignAccount"->{
                assertThatThrownBy(()->transaction.required(()->billingTime.sample(new PaymentAccountId(UUID.randomUUID()),site,customer,Instant.now())))
                    .hasMessage("OWNED_TIME_AUTHORITY_MISSING");return;
            }
        }
        assertThatThrownBy(()->apply(snapshot,fence)).isInstanceOf(IllegalStateException.class).hasMessage("OWNED_TIME_AUTHORITY_MISSING");
        assertThat(observations.captureFence(current())).isEqualTo(fence.observationSequence());
        assertThat(count("subscription_billing_records")).isZero();assertNoSuccess();
    }

    @Test void customerSessionExpiryUsesOperationalTimeWhileOwnedEvaluationKeepsFrozenTime() {
        createOwned();
        var actor=(dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer)createCommand.context().actor();
        assertThat(actor.session().expiresAt()).isAfter(began).isAfter(Instant.ofEpochSecond(FROZEN));
        TestClock.set(actor.session().expiresAt().plusSeconds(1));
        assertThatThrownBy(()->queries.get(actor,site,current().id())).isInstanceOf(PaymentApplicationException.class);
        assertThat(transaction.required(()->billingTime.sample(billing.id(),site,customer,actor.session().expiresAt().plusSeconds(1))).billingAt())
            .isEqualTo(Instant.ofEpochSecond(FROZEN));
        assertNoSuccess();
    }

    @Test void explicitAdapterRefusesRealAmbientTransactionBeforeAnySdkRequest() {
        createOwned();int calls=http.calls.size();
        assertThatThrownBy(()->transaction.required(()->ownedAdapter.retrieveOwned(lookup(),owner))).isInstanceOf(PaymentApplicationException.class);
        assertThat(http.calls).hasSize(calls);
        assertNoSuccess();
    }

    @Test void ordinaryAndPublicActionInboxPathsCannotAdoptOwnedSubscription() {

        createOwned();var observationFence=fence();apply(retrieve(),observationFence);var command=new Command(billing,currentOperation(),current().billingPrice(),null,null,null,null,true,false);
        int calls=http.calls.size();
        assertThatThrownBy(()->provider.prepareCreate(command)).isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->provider.create(command)).isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->provider.retrieve(lookup())).isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->actions.confirmAction(command,"bga1_"+"A".repeat(43))).isInstanceOf(PaymentApplicationException.class);
        assertThatThrownBy(()->inbox.resolve(new SubscriptionProviderEnvelope(billing.provider(),billing.id(),"evt_owned","sub_owned","SUBSCRIPTION_CHANGED",Instant.now(),Instant.now())))
            .isInstanceOf(PaymentApplicationException.class);
        assertThat(http.calls).hasSize(calls);
        assertThatThrownBy(()->queries.list(new QuerySubscriptionsUseCase.Query(createCommand.context().actor(),site,customer,null,null,20,null)))
            .isInstanceOf(IllegalStateException.class).hasMessage("OWNED_TIME_UNSCOPED");
        for(var scope:List.of(new Object[]{null,null},new Object[]{site,null},new Object[]{site,customer}))
            assertThatThrownBy(()->billingTime.requireUnscopedAllowed((SiteId)scope[0],(CustomerId)scope[1])).hasMessage("OWNED_TIME_UNSCOPED");
        assertThatCode(()->billingTime.requireUnscopedAllowed(new SiteId(UUID.randomUUID()),null)).doesNotThrowAnyException();
        var at=Instant.now();
        assertThat(transaction.required(()->billingTime.sample(billing.id(),site,new CustomerId(UUID.randomUUID()),at)))
            .satisfies(sample->{assertThat(sample.isolated()).isFalse();assertThat(sample.billingAt()).isEqualTo(at);});
        assertNoSuccess();
    }
}
