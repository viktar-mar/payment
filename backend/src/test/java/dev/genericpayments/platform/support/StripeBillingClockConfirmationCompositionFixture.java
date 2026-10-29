package dev.genericpayments.platform.support;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.stripe.net.StripeRequest;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.provider.application.model.OwnedClockSubscriptionReceipt;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

/** Hosted only: real create/action/claim/facts/cash/journal/outbox, inert official SDK transport.
 * No successful lifecycle, action, attempt or payment fixture row is inserted. */
@TestPropertySource(properties="payment.telemetry.site-keys[0]=test-site")
public abstract class StripeBillingClockConfirmationCompositionFixture extends StripeBillingClockSubscriptionCompositionFixture {
    @Autowired protected SubscriptionInteractionTransaction phases;
    @Autowired protected SubscriptionReadPreparation readPreparation;
    @Autowired protected SubscriptionActionStore roots;
    @Autowired protected SubscriptionConfirmationStore confirmations;
    @Autowired protected CreateSubscriptionClientSessionUseCase legacy;
    @Autowired protected ConfirmSubscriptionActionUseCase legacyConfirm;
    @Autowired protected SubscriptionConfirmationUseCase ordinary;
    @Autowired protected QuerySubscriptionsUseCase queries;
    @Autowired protected CallbackConfigurationPort callbacks;
    @Autowired protected Stage2TelemetryPort telemetry;
    @Autowired protected LoadSitePort sites;
    protected OwnedClockSubscriptionReceipt receipt;
    protected SubscriptionConfirmationUseCase.Scope scope;
    protected StripeBillingClockActionAdapter action;
    protected StripeBillingClockConfirmationAdapter confirmationProvider;
    protected SubscriptionConfirmationService confirmation;
    protected SubscriptionConfirmationUseCase.Session selected;
    protected String actionToken;
    protected static final String TOKEN="ctoken_INERT";
    protected final StripeBillingGrantTokens tokens=new StripeBillingGrantTokens(true,"v1",
        Map.of("v1",Base64.getEncoder().encodeToString(new byte[32])));

    protected void beginConfirmation() {
        var actualSite=sites.findById(site).orElseThrow();
        assertThatCode(()->telemetry.requireContext(new Stage2TelemetryPort.Context(actualSite.key().value(),Stage2TelemetryPort.Provider.STRIPE)))
            .as("The real cash publisher requires this fixture Site's configured telemetry identity")
            .doesNotThrowAnyException();
        createOwned();
        receipt=owned.requireReceipt(billing.id(),current().id());
        scope=scope("owned-pay");
        var utc=Clock.systemUTC();
        action=new StripeBillingClockActionAdapter(receipt,phases,roots,tokens,credentials,clients,verification,utc);
        confirmationProvider=new StripeBillingClockConfirmationAdapter(receipt,scope,phases,roots,confirmations,tokens,credentials,clients,verification,utc);
        confirmation=receiptBoundService(confirmationProvider);
        callbacks.configure(site.value(),"https://example.com/callback",true);
        graph.allocation.addProperty("status","open");
        graph.allocation.addProperty("is_default",true);
        graph.allocation.addProperty("amount_paid",0);
        graph.allocation.remove("status_transitions");
        graph.pi.addProperty("status","requires_payment_method");
        graph.pi.addProperty("amount_received",0);
        graph.pi.remove("latest_charge");
        graph.pi.addProperty("confirmation_method","automatic");
        graph.pi.addProperty("client_secret","pi_owned_secret_INERT");
    }
    protected SubscriptionConfirmationService receiptBoundService(SubscriptionConfirmationProvider port) {
        ObjectProvider<SubscriptionConfirmationProvider> provider=new ObjectProvider<>() {
            public SubscriptionConfirmationProvider getObject(Object... args){return port;}
            public SubscriptionConfirmationProvider getObject(){return port;}
            public SubscriptionConfirmationProvider getIfAvailable(){return port;}
            public SubscriptionConfirmationProvider getIfUnique(){return port;}
        };
        return new SubscriptionConfirmationService(phases,roots,confirmations,provider,legacy,Clock.systemUTC(),receipt);
    }
    protected SubscriptionConfirmationUseCase.Scope scope(String key) {
        var c=createCommand.context();
        return new SubscriptionConfirmationUseCase.Scope(new SubscriptionCommandContext(c.actor(),site,customer,c.origin(),key,c.requestId()),current().id(),currentOperation().id());
    }
    protected void openGraph() {
        http.reply(clockJson(owner));
        http.reply(customerJson("clock_owned"));http.reply(graph.remote.toString());
        http.reply(StripeBillingOwnedSubscriptionFixture.page(graph.invoice));
        http.reply(StripeBillingOwnedSubscriptionFixture.page(graph.line));
        http.reply(StripeBillingOwnedSubscriptionFixture.page(graph.allocation));
        http.reply(graph.pi.toString());http.reply(StripeBillingOwnedSubscriptionFixture.page(graph.allocation));
        http.reply(clockJson(owner));
    }
    protected void issueAndSelect() {
        openGraph();var next=action.nextAction(scope);
        assertThat(next.kind()).isEqualTo(ActionKind.PROVIDER_CLIENT);
        actionToken=next.secret();
        assertThat(actionToken).startsWith("bga1_");
        openGraph();selected=confirmation.select(scope,actionToken);
        assertThat(selected.protocol()).isEqualTo("SERVER_CONFIRM_V2");
        assertThat(selected.clientSecret()).isEqualTo("pi_owned_secret_INERT");
        assertThat(selected.attemptId()).isNotNull();
        assertThat(selected.expiresAt()).isAfter(began).isBeforeOrEqualTo(next.expiresAt());
        assertThat(count("subscription_action_capabilities")).isEqualTo(1);
        assertThat(count("subscription_confirmation_chains")).isEqualTo(1);
        assertThat(count("subscription_confirmation_attempts")).isEqualTo(1);
        assertThat(confirmation.status(scope,selected.attemptId()).state()).isEqualTo(SubscriptionConfirmationAttempt.State.READY);
        assertNoSuccess();
    }
    protected JsonObject response(String state) {
        var result=graph.pi.deepCopy();
        result.remove("client_secret");result.addProperty("status",state);
        result.addProperty("amount_received",state.equals("succeeded")?1000:0);
        result.add("next_action",state.equals("requires_action")
            ?StripeBillingOwnedSubscriptionFixture.json("{\"type\":\"use_stripe_sdk\",\"use_stripe_sdk\":{\"opaque\":\"inert\"}}")
            :JsonNull.INSTANCE);
        return result;
    }
    protected void queueConfirm(String result) {
        openGraph(); // Service preflight while READY, before durable claim.
        openGraph(); // Provider independently checks the exact persisted consumed attempt.
        if(result.equals("disconnect"))http.disconnect();
        else if(result.equals("402"))http.error(402);
        else {http.reply(response(result).toString());http.reply(clockJson(owner));}
    }
    protected SubscriptionConfirmationUseCase.Confirm confirm(String result) {
        queueConfirm(result);
        return confirmation.confirm(scope,selected.attemptId(),selected.capability(),TOKEN);
    }
    @Override protected ApplySubscriptionSnapshotUseCase.Fence fence() {
        var subscription=current();
        var operation=readPreparation.financial(subscription);
        return new ApplySubscriptionSnapshotUseCase.Fence(subscription.revision(),operation==null?null:operation.id(),
            operation==null?null:operation.version(),null,null,observations.captureFence(subscription));
    }
    protected Snapshot paidObservation() {
        graph.remote.addProperty("status","active");
        graph.invoice.addProperty("status","paid");graph.invoice.addProperty("amount_paid",1000);graph.invoice.addProperty("amount_remaining",0);
        graph.allocation.addProperty("status","paid");graph.allocation.addProperty("amount_paid",1000);
        graph.allocation.add("status_transitions",StripeBillingOwnedSubscriptionFixture.json("{\"paid_at\":1700000011}"));
        graph.pi.addProperty("status","succeeded");graph.pi.addProperty("amount_received",1000);graph.pi.addProperty("latest_charge","ch_owned");
        return retrievePaid();
    }
    protected Snapshot retrievePaid() {
        http.reply(clockJson(owner));http.reply(customerJson("clock_owned"));http.reply(graph.remote.toString());
        http.reply(StripeBillingOwnedSubscriptionFixture.page(graph.invoice));http.reply(StripeBillingOwnedSubscriptionFixture.page(graph.line));
        http.reply(StripeBillingOwnedSubscriptionFixture.page(graph.allocation));http.reply(graph.pi.toString());http.reply(graph.charge.toString());
        http.reply(StripeBillingOwnedSubscriptionFixture.page(graph.allocation));
        return ownedAdapter.retrieveOwned(lookup(),owner);
    }
    protected void inspectSdk(Consumer<StripeRequest> effect) {
        var audit=http.afterResponse;
        Runnable[] wrapped=new Runnable[1];
        wrapped[0]=()->{audit.run();effect.accept(http.calls.getLast());http.afterResponse=wrapped[0];};
        http.afterResponse=wrapped[0];
    }
    protected boolean confirmPost(StripeRequest request) {
        return request.method().name().equals("POST")&&request.url().getPath().equals("/v1/payment_intents/pi_owned/confirm");
    }
    protected long confirmPosts() {return http.calls.stream().filter(this::confirmPost).count();}
    protected void assertClaimBeforePost(StripeRequest request) {
        if(!confirmPost(request))return;
        var row=jdbc.queryForMap("select state,version,provider_key,token_digest,confirm_key,fingerprint,claimed_at from subscription_confirmation_attempts where id=?",selected.attemptId());
        assertThat(row.get("state")).isEqualTo("DISPATCHING");assertThat(row.get("version")).isEqualTo(1L);
        assertThat(row.get("token_digest")).isEqualTo(StripeBillingGrantTokens.hash(TOKEN));
        assertThat(request.headers().map().get("Idempotency-Key")).containsExactly((String)row.get("provider_key"));
        assertThat(request.options().getMaxNetworkRetries()).isZero();
        assertThat(request.params()).containsOnlyKeys("confirmation_token","use_stripe_sdk");
        assertThat(request.params()).containsEntry("confirmation_token",TOKEN).containsEntry("use_stripe_sdk",true);
        assertThat(((Timestamp)row.get("claimed_at")).toInstant()).isBetween(began,Instant.now());
        for(var table:List.of("subscription_action_capabilities","subscription_confirmation_chains","subscription_confirmation_attempts")) {
            assertThat(jdbc.queryForObject("select row_to_json(t)::text from "+table+" t",String.class))
                .doesNotContain(TOKEN,selected.capability(),selected.clientSecret(),actionToken);
        }
        assertThat(jdbc.queryForObject("select consumed_at from subscription_action_capabilities",Timestamp.class).toInstant()).isBetween(began,Instant.now());
        assertNoSuccess();
    }
    protected void assertPaid() {
        var s=current();
        var period=new Subscription.Period(Instant.ofEpochSecond(receipt.periodStart()),Instant.ofEpochSecond(receipt.periodEnd()));
        assertThat(s.billingStatus()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(s.currentPeriod()).isEqualTo(period);assertThat(s.paidThrough()).isEqualTo(period.end());assertThat(s.entitledPrice()).isEqualTo(s.billingPrice());
        assertThat(currentOperation().id()).isEqualTo(receipt.intent().createOperation());
        assertThat(currentOperation().status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(count("subscription_billing_records")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select settlement from subscription_billing_records",String.class)).isEqualTo("CONFIRMED_CASH");
        assertThat(count("payments")).isEqualTo(1);assertThat(count("payment_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select event_type from payment_events",String.class)).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(jdbc.queryForObject("select provider_payment_reference from payments",String.class)).isEqualTo("pi_owned");
        assertThat(jdbc.queryForObject("select source from payments",String.class)).isEqualTo("SUBSCRIPTION");
        assertThat(jdbc.queryForObject("select event_type from subscription_events",String.class)).isEqualTo("PERIOD_PAID");
        assertThat(count("subscription_events")).isEqualTo(1);
        var access=queries.get(scope.context().actor(),site,s.id()).access();
        assertThat(access.allowed()).isTrue();assertThat(access.evaluatedAt()).isEqualTo(Instant.ofEpochSecond(FROZEN));
        assertThat(access.validUntil()).isEqualTo(period.end());
        assertThat(owned.requireReceipt(billing.id(),s.id())).isEqualTo(receipt);
        assertThat(receipt.providerCreated()).isEqualTo(42).isNotEqualTo(FROZEN);
        assertThat(count("outbox_events")).isEqualTo(2);
        assertThat(jdbc.queryForList("select callback_type from outbox_events",String.class))
            .containsExactlyInAnyOrder("payment.succeeded","subscription.updated");
        var payment=jdbc.queryForMap("select payload->>'payment_id' as payment,payload->>'subscription_id' as subscription,payload->>'period_start' as start,payload->>'period_end' as finish,payload->>'amount_minor' as amount,payload->>'currency' as currency from outbox_events where source_kind='PAYMENT'");
        assertThat(payment).containsEntry("payment",jdbc.queryForObject("select id::text from payments",String.class))
            .containsEntry("subscription",s.id().toString()).containsEntry("start",period.start().toString()).containsEntry("finish",period.end().toString())
            .containsEntry("amount","1000").containsEntry("currency","EUR");
        var subscription=jdbc.queryForMap("select payload->>'event_id' as event,payload->>'subscription_id' as subscription,payload->>'state' as state,payload->>'access_allowed' as access,payload->>'access_valid_until' as until,payload->>'occurred_at' as occurred from outbox_events where source_kind='SUBSCRIPTION'");
        assertThat(subscription).containsEntry("event",jdbc.queryForObject("select id::text from subscription_events",String.class))
            .containsEntry("subscription",s.id().toString()).containsEntry("state","ACTIVE").containsEntry("access","true")
            .containsEntry("until",period.end().toString()).containsEntry("occurred",Instant.ofEpochSecond(FROZEN).toString());
        assertThat(jdbc.queryForList("select occurred_at from payment_events",Timestamp.class)).containsExactly(Timestamp.from(Instant.ofEpochSecond(1700000011)));
        for(var table:List.of("payments","subscription_billing_records","outbox_events"))
            assertThat(jdbc.queryForList("select created_at from "+table,Timestamp.class)).allSatisfy(t->assertThat(t.toInstant()).isBetween(began,Instant.now()));
        for(var table:List.of("payment_events","subscription_events"))
            assertThat(jdbc.queryForList("select received_at from "+table,Timestamp.class)).allSatisfy(t->assertThat(t.toInstant()).isBetween(began,Instant.now()));
        assertThat(jdbc.queryForList("select next_attempt_at from outbox_events",Timestamp.class)).allSatisfy(t->assertThat(t.toInstant()).isBetween(began,Instant.now()));
    }
    protected Map<String,Object> financialState() {
        var state=new TreeMap<String,Object>();
        for(var table:List.of("payments","payment_events","subscription_events","outbox_events","subscription_billing_records"))
            state.put(table,jdbc.queryForList("select row_to_json(t)::text as value from "+table+" t order by id"));
        state.put("subscription",current());state.put("operation",currentOperation());
        state.put("sequence",observations.captureFence(current()));
        return state;
    }
}
