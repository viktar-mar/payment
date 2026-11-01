package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingClockStore.StopReason;
import dev.genericpayments.platform.provider.application.service.OwnedFirstRenewalPublicationService;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.TrialEligibilityStore;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.StripeBillingFirstRenewalCompositionFixture;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.*;

/** Hosted only: genuine original confirmation through two advances to cash, authenticated access and callbacks. */
@Import(StripeBillingFirstRenewalCompositionFixture.RenewalTimeConfiguration.class)
class StripeBillingFirstRenewalCompositionIntegrationTest extends StripeBillingFirstRenewalCompositionFixture {
    @Autowired TrialEligibilityStore trialEligibility;

    private OwnedFirstRenewalPublicationService publisher() { return publisher(facts); }
    private OwnedFirstRenewalPublicationService publisher(ApplySubscriptionSnapshotUseCase apply) {
        return new OwnedFirstRenewalPublicationService(renewals,transaction,lifecycleOperations,lifecycleLocks,accountLocks,
            subscriptions,readPreparation,observations,atCurrentOperationalUtc(apply),trialEligibility,Clock.systemUTC());
    }

    // Catches money/access published before fresh READY2 cash, missing callback payloads, or changed original evidence.
    @Test void genuineInitialThroughTwoAdvancesToRenewalCash() {
        genuineInitialCash();
        assertThat(confirmPosts()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state from subscription_confirmation_attempts",String.class)).isEqualTo("OBSERVE");
        var original=originalEvidence();var before=financialState();var originalReceipt=receipt;
        var admission=admitRenewal();
        assertThatThrownBy(()->queries.get(scope.context().actor(),site,current().id())).isInstanceOf(RuntimeException.class);
        int firstRenewalCall=http.calls.size();
        var first=advance(admission,Step.BOUNDARY);
        assertThat(first.observation().frozenTime()).isEqualTo(receipt.periodEnd());
        assertDeniedAt(receipt.periodEnd());assertThat(financialState()).isEqualTo(before);assertOriginal(original);
        var pending=boundary(admission,first);
        assertThat(pending.boundary().ready()).isEqualTo(first);
        assertThat(pending.boundary().renewalInvoice()).isEqualTo("in_renewal");
        var expected=new Subscription.Period(Instant.ofEpochSecond(receipt.periodEnd()),Instant.ofEpochSecond(receipt.periodEnd()+31*86400));
        assertThat(pending.boundary().period()).isEqualTo(expected);
        assertThat(pending.claim().fence()).isEqualTo(fence());
        assertThat(financialState()).isEqualTo(before);assertOriginal(original);

        var second=advance(admission,Step.FINALIZATION);
        assertThat(second.observation().frozenTime()).isEqualTo(receipt.periodEnd()+3600);
        assertDeniedAt(receipt.periodEnd()+3600);assertThat(financialState()).isEqualTo(before);assertOriginal(original);
        var captured=fence();graph(second,true);var paid=(Paid)observer().observe(admission,second);
        assertThat(paid.boundary()).isEqualTo(pending.boundary());
        assertThat(paid.fence()).isEqualTo(captured).isEqualTo(paid.claim().fence());
        assertThat(paid.claim().ready()).isEqualTo(second);
        assertThat(paid.graphAcceptedAt()).isAfterOrEqualTo(paid.claim().startedAt()).isBefore(paid.claim().deadlineAt());
        assertThat(financialState()).isEqualTo(before);assertOriginal(original);
        assertThat(count("stripe_billing_first_renewal_publications")).isZero();
        int completedRead=http.calls.size();
        var published=publisher().publish(admission,second,paid);
        assertRenewalPublished(published,expected);
        assertThat(http.calls).hasSize(completedRead);assertOriginal(original);assertThat(receipt).isEqualTo(originalReceipt);
        assertThat(owned.requireReceipt(billing.id(),current().id())).isEqualTo(originalReceipt);
        assertThat(receipt.intent().clock().frozenTime()).isEqualTo(FROZEN);
        assertThat(jdbc.queryForObject("select graph_accepted_at from stripe_billing_first_renewal_publications",Timestamp.class).toInstant())
            .isEqualTo(paid.graphAcceptedAt());
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(2);
        assertBoundedRequests(admission,firstRenewalCall);
        assertLocalReplay(admission,paid,published);
    }

    // Catches treating a later READY/draft as payment or restarting a burned graph invocation.
    @Test void pendingAndDriftCannotPublish() {
        var admission=paidAdmission();var original=originalEvidence();var before=financialState();
        int firstRenewalCall=http.calls.size();
        var first=advance(admission,Step.BOUNDARY);boundary(admission,first);
        var second=advance(admission,Step.FINALIZATION);assertDeniedAt(receipt.periodEnd()+3600);
        int beforeRead=http.calls.size();for(int attempt=0;attempt<3;attempt++)graph(second,false);
        assertThatThrownBy(()->observer().observe(admission,second)).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(beforeRead+36);assertThat(http.replies).isEmpty();
        assertThat(jdbc.queryForObject("select stop_reason from stripe_billing_first_renewals",String.class)).isEqualTo("PAYMENT_NOT_OBSERVED");
        assertThat(financialState()).isEqualTo(before);assertOriginal(original);
        assertThat(count("stripe_billing_first_renewal_publications")).isZero();
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(2);
        int exhausted=http.calls.size();
        assertThatThrownBy(()->observer().observe(admission,second)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->publisher().replayPublished(admission)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->queries.get(scope.context().actor(),site,current().id())).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(exhausted);assertThat(financialState()).isEqualTo(before);assertOriginal(original);
        assertBoundedRequests(admission,firstRenewalCall);
    }

    // Catches publishing unchanged local NONE terms when the fresh provider renewal contains inclusive tax.
    @Test void inclusiveTaxWithUnchangedGrossCannotPublishRenewal(){
        var admission=paidAdmission();var before=financialState();var original=originalEvidence();
        var first=advance(admission,Step.BOUNDARY);boundary(admission,first);
        var second=advance(admission,Step.FINALIZATION);assertDeniedAt(receipt.periodEnd()+3600);
        graph(second,true);
        // Change only the fresh renewal's tax evidence; the complete 1000 EUR minor cash graph stays intact.
        var replies=new ArrayList<>(http.replies);
        for(int index:new int[]{3,9}){
            var response=(com.stripe.net.StripeResponse)replies.get(index);
            var json=com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
            var value=json.getAsJsonArray("data").get(index==3?1:0).getAsJsonObject();
            value.add(index==3?"total_taxes":"taxes",com.google.gson.JsonParser.parseString("[{\"amount\":5,\"tax_behavior\":\"inclusive\",\"taxable_amount\":995,\"type\":\"tax_rate_details\",\"tax_rate_details\":{\"tax_rate\":\"txr_renewal\"}}]"));
            if(index==3){value.addProperty("total_excluding_tax",995);value.addProperty("subtotal_excluding_tax",995);}
            replies.set(index,new com.stripe.net.StripeResponse(200,com.stripe.net.HttpHeaders.of(Map.of()),json.toString()));
        }
        http.replies.clear();http.replies.addAll(replies);
        int beforeRead=http.calls.size();
        assertThatThrownBy(()->observer().observe(admission,second)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("select stop_reason from stripe_billing_first_renewals",String.class)).isEqualTo("GRAPH_UNSUPPORTED");
        assertThat(http.calls.subList(beforeRead,http.calls.size())).allSatisfy(request->assertThat(request.method().name()).isEqualTo("GET"));
        assertThat(financialState()).isEqualTo(before);assertOriginal(original);
        assertThat(current().paidThrough()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()));assertThat(current().debt()).isNull();
        assertThat(count("stripe_billing_first_renewal_publications")).isZero();
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(2);
        int stopped=http.calls.size();
        assertThatThrownBy(()->observer().observe(admission,second)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->publisher().replayPublished(admission)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->queries.get(scope.context().actor(),site,current().id())).isInstanceOf(RuntimeException.class);
        assertThat(http.calls).hasSize(stopped);assertThat(financialState()).isEqualTo(before);assertOriginal(original);
    }

    // Catches accepting caller source identity or stale live authority/fence after an otherwise genuine Paid.
    @ParameterizedTest @ValueSource(strings={"source","owner","policy","revision","sequence"})
    void currentSourceOwnerPolicyAndFenceDriftCannotPublish(String drift) {
        var admission=paidAdmission();int firstRenewalCall=http.calls.size();var paid=paid(admission);
        var supplied=admission;
        if(drift.equals("source")) {
            supplied=new Admission(admission.id(),admission.receipt(),"b".repeat(40),admission.root(),admission.attempt(),admission.attemptVersion(),
                admission.bill(),admission.billVersion(),admission.payment(),admission.paymentVersion(),admission.invoice(),admission.allocation(),
                admission.paymentIntent(),admission.subscriptionRevision(),admission.policyVersion(),admission.fingerprint(),admission.admittedAt());
        } else drift(drift);
        // The deliberate drift is fixture input, not an effect of the refused publication.
        var before=financialState();var original=originalEvidence();int calls=http.calls.size();var request=supplied;
        assertThatThrownBy(()->publisher().publish(request,paid.ready(),paid)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->publisher().replayPublished(request)).isInstanceOf(RuntimeException.class);
        if(!drift.equals("source"))assertThatThrownBy(()->queries.get(scope.context().actor(),site,current().id())).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(before);assertOriginal(original);assertThat(http.calls).hasSize(calls);
        assertThat(count("stripe_billing_first_renewal_publications")).isZero();
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(2);
        assertBoundedRequests(admission,firstRenewalCall);
    }

    // Catches validation before, rather than after, contended lifecycle acquisition.
    @ParameterizedTest @ValueSource(strings={"policy","revision","sequence"})
    void driftCommittedWhilePublicationWaitsCannotPublish(String changed) throws Exception {
        var admission=paidAdmission();var original=originalEvidence();var paid=paid(admission);int calls=http.calls.size();
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var publishing=new CountDownLatch(1);
        try(var workers=new BoundedWorkers()) {
            var mutation=workers.submit(()->transaction.required(()->{
                lifecycleOperations.lock(admission.receipt().intent().createOperation());locked.countDown();
                await(release);drift(changed);return financialState();
            }));
            try {
                assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
                var publication=workers.submit(()->{publishing.countDown();
                    assertThatThrownBy(()->publisher().publish(admission,paid.ready(),paid)).isInstanceOf(RuntimeException.class);return true;});
                assertThat(publishing.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->publication.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();var changedState=mutation.get(10,TimeUnit.SECONDS);
                assertThat(publication.get(10,TimeUnit.SECONDS)).isTrue();assertThat(financialState()).isEqualTo(changedState);
            } finally {release.countDown();}
        }
        assertThat(count("payments")).isEqualTo(1);assertThat(count("outbox_events")).isEqualTo(2);
        assertThat(count("stripe_billing_first_renewal_publications")).isZero();assertThat(http.calls).hasSize(calls);assertOriginal(original);
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(2);
        assertThatThrownBy(()->queries.get(scope.context().actor(),site,current().id())).isInstanceOf(RuntimeException.class);
    }

    // Catches duplicate money/events despite serialization, and confusing local receipt replay with fresh graph authority.
    @Test void concurrentPublicationIsOnce() throws Exception {
        var admission=paidAdmission();var original=originalEvidence();var paid=paid(admission);int calls=http.calls.size();
        var applied=new CountDownLatch(1);var release=new CountDownLatch(1);var competing=new CountDownLatch(1);
        ApplySubscriptionSnapshotUseCase paused=command->{facts.apply(command);applied.countDown();await(release);};
        Publication published;
        try(var workers=new BoundedWorkers()) {
            var winner=workers.submit(()->publisher(paused).publish(admission,paid.ready(),paid));
            try {
                assertThat(applied.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(count("payments")).isEqualTo(1);assertThat(count("outbox_events")).isEqualTo(2);
                assertThat(count("stripe_billing_first_renewal_publications")).isZero();
                var loser=workers.submit(()->{competing.countDown();
                    assertThatThrownBy(()->publisher().publish(admission,paid.ready(),paid)).isInstanceOf(RuntimeException.class);return true;});
                assertThat(competing.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->loser.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();published=winner.get(10,TimeUnit.SECONDS);assertThat(loser.get(10,TimeUnit.SECONDS)).isTrue();
            } finally {release.countDown();}
        }
        assertRenewalPublished(published,new Subscription.Period(Instant.ofEpochSecond(receipt.periodEnd()),Instant.ofEpochSecond(receipt.periodEnd()+31*86400)));
        assertOriginal(original);assertThat(http.calls).hasSize(calls);assertLocalReplay(admission,paid,published);
    }

    private void assertLocalReplay(Admission admission,Paid paid,Publication published) {
        var committed=financialState();int calls=http.calls.size();
        assertThat(publisher().replayPublished(admission)).isEqualTo(published);
        assertThat(publisher().replayPublished(admission)).isEqualTo(published);
        assertThatThrownBy(()->publisher().publish(admission,paid.ready(),paid)).isInstanceOf(RuntimeException.class);
        assertThat(financialState()).isEqualTo(committed);assertThat(http.calls).hasSize(calls);
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(2);
        assertThat(count("stripe_billing_first_renewal_publications")).isEqualTo(1);
    }

    private void assertDeniedAt(long seconds) {
        var access=queries.get(scope.context().actor(),site,current().id()).access();
        assertThat(access.allowed()).isFalse();assertThat(access.evaluatedAt()).isEqualTo(Instant.ofEpochSecond(seconds));
        assertThat(current().paidThrough()).isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()));assertThat(current().debt()).isNull();
    }

    private void assertRenewalPublished(Publication published,Subscription.Period period) {
        assertThat(current().currentPeriod()).isEqualTo(period);assertThat(current().paidThrough()).isEqualTo(period.end());
        assertThat(current().billingStatus()).isEqualTo(Subscription.Status.ACTIVE);assertThat(current().entitledPrice()).isEqualTo(current().billingPrice());
        assertThat(currentOperation().status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(count("subscription_billing_records")).isEqualTo(2);assertThat(count("payments")).isEqualTo(2);
        assertThat(jdbc.queryForList("select settlement from subscription_billing_records",String.class)).containsExactlyInAnyOrder("CONFIRMED_CASH","CONFIRMED_CASH");
        assertThat(jdbc.queryForList("select subscription_billing_kind from payments",String.class)).containsExactlyInAnyOrder("INITIAL","RENEWAL");
        assertThat(jdbc.queryForList("select status from payments",String.class)).containsExactlyInAnyOrder("SUCCEEDED","SUCCEEDED");
        assertThat(jdbc.queryForList("select source from payments",String.class)).containsOnly("SUBSCRIPTION");
        assertThat(jdbc.queryForList("select event_type from payment_events",String.class)).containsExactlyInAnyOrder("PAYMENT_SUCCEEDED","PAYMENT_SUCCEEDED");
        assertThat(jdbc.queryForList("select event_type from subscription_events",String.class)).containsExactlyInAnyOrder("PERIOD_PAID","PERIOD_PAID");
        assertThat(count("outbox_events")).isEqualTo(4);assertThat(count("stripe_billing_first_renewal_publications")).isEqualTo(1);
        assertThat(published.bill()).isEqualTo(jdbc.queryForObject("select id from subscription_billing_records where kind='RENEWAL' and provider_invoice_reference='in_renewal'",UUID.class));
        assertThat(published.payment()).isEqualTo(jdbc.queryForObject("select id from payments where provider_payment_reference='pi_renewal'",UUID.class));
        assertThat(published.projectedRevision()).isEqualTo(current().revision());
        assertThat(jdbc.queryForMap("""
            select subscription_id,billing_record_id,subscription_invoice_reference,subscription_period_start,subscription_period_end,amount_minor,currency
            from payments where id=?
            """,published.payment())).containsEntry("subscription_id",current().id()).containsEntry("billing_record_id",published.bill())
            .containsEntry("subscription_invoice_reference","in_renewal").containsEntry("subscription_period_start",Timestamp.from(period.start()))
            .containsEntry("subscription_period_end",Timestamp.from(period.end())).containsEntry("amount_minor",1000L).containsEntry("currency","EUR");
        assertThat(jdbc.queryForList("select callback_type from outbox_events",String.class))
            .containsExactlyInAnyOrder("payment.succeeded","subscription.updated","payment.succeeded","subscription.updated");
        var access=queries.get(scope.context().actor(),site,current().id()).access();var t=Instant.ofEpochSecond(receipt.periodEnd()+3600);
        assertThat(access.allowed()).isTrue();assertThat(access.reason()).isEqualTo(AccessDecision.Reason.PAID);
        assertThat(access.evaluatedAt()).isEqualTo(t);assertThat(access.validUntil()).isEqualTo(t.plusSeconds(300));
        var payment=jdbc.queryForMap("""
            select payload->>'payment_id' as payment,payload->>'subscription_id' as subscription,payload->>'period_start' as start,
             payload->>'period_end' as finish,payload->>'amount_minor' as amount,payload->>'currency' as currency
            from outbox_events where source_kind='PAYMENT' and payload->>'payment_id'=?
            """,published.payment().toString());
        assertThat(payment).containsEntry("payment",published.payment().toString()).containsEntry("subscription",current().id().toString())
            .containsEntry("start",period.start().toString()).containsEntry("finish",period.end().toString()).containsEntry("amount","1000").containsEntry("currency","EUR");
        var event=jdbc.queryForObject("select id::text from subscription_events where revision=?",String.class,current().revision());
        var subscription=jdbc.queryForMap("""
            select payload->>'event_id' as event,payload->>'subscription_id' as subscription,payload->>'state' as state,
             payload->>'access_allowed' as access,payload->>'access_valid_until' as until,payload->>'occurred_at' as occurred
            from outbox_events where source_kind='SUBSCRIPTION' and payload->>'event_id'=?
            """,event);
        assertThat(subscription).containsEntry("event",event).containsEntry("subscription",current().id().toString()).containsEntry("state","ACTIVE")
            .containsEntry("access","true").containsEntry("until",t.plusSeconds(300).toString()).containsEntry("occurred",t.toString());
        assertThat(jdbc.queryForObject("select occurred_at from payment_events where payment_id=?",Timestamp.class,published.payment()).toInstant())
            .isEqualTo(Instant.ofEpochSecond(receipt.periodEnd()+11));
        var now=Instant.now();
        for(var table:List.of("payments","subscription_billing_records","outbox_events"))
            assertThat(jdbc.queryForList("select created_at from "+table,Timestamp.class)).allSatisfy(at->assertThat(at.toInstant()).isBetween(began,now));
        for(var table:List.of("payment_events","subscription_events"))
            assertThat(jdbc.queryForList("select received_at from "+table,Timestamp.class)).allSatisfy(at->assertThat(at.toInstant()).isBetween(began,now));
        assertThat(jdbc.queryForList("select next_attempt_at from outbox_events",Timestamp.class)).allSatisfy(at->assertThat(at.toInstant()).isBetween(began,now));
    }

    private void assertBoundedRequests(Admission admission,int firstRenewalCall) {
        var posts=http.calls.stream().filter(r->r.method().name().equals("POST")).toList();
        assertThat(posts.stream().map(r->r.url().getPath()).toList()).containsExactly("/v1/test_helpers/test_clocks","/v1/customers",
            "/v1/subscriptions","/v1/payment_intents/pi_owned/confirm","/v1/test_helpers/test_clocks/clock_owned/advance","/v1/test_helpers/test_clocks/clock_owned/advance");
        for(int index=0;index<2;index++) {
            var request=posts.get(index+4);
            assertThat(request.headers().map().get("Idempotency-Key")).containsExactly("billing-renewal-advance-v1:"+admission.id()+":"+(index+1));
            assertThat(request.params()).containsOnlyKeys("frozen_time").containsEntry("frozen_time",receipt.periodEnd()+index*3600L);
        }
        for(var request:http.calls.subList(firstRenewalCall,http.calls.size())) {
            assertThat(request.method().name()).isIn("GET","POST");
            assertThat(request.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");
            assertThat(request.options().getMaxNetworkRetries()).isZero();
            assertThat(request.options().getConnectTimeout()).isBetween(1,2000);assertThat(request.options().getReadTimeout()).isBetween(1,2000);
        }
        assertThat(sdkCallbacks).isEqualTo(http.calls.size());assertThat(http.replies).isEmpty();
    }

    private record OriginalEvidence(Map<String,List<String>> fixed,Map<String,Map<UUID,String>> financial) {}
    private OriginalEvidence originalEvidence() {
        var fixed=new TreeMap<String,List<String>>();
        for(var table:List.of("stripe_billing_clock_intents","stripe_billing_clock_ready","stripe_billing_owned_subscriptions",
                "stripe_billing_owned_subscription_receipts","subscription_operations","subscription_action_capabilities","subscription_confirmation_chains","subscription_confirmation_attempts"))
            fixed.put(table,rows(table,""));
        fixed.put("provider_operations",rows("provider_operations"," where kind<>'TEST_CLOCK_ADVANCE'"));
        var financial=new TreeMap<String,Map<UUID,String>>();
        for(var table:List.of("subscription_billing_records","payments","payment_events","subscription_events","outbox_events")) {
            var values=new TreeMap<UUID,String>();
            jdbc.query("select id,row_to_json(t)::text as value from "+table+" t",r->{values.put(r.getObject("id",UUID.class),r.getString("value"));});
            financial.put(table,values);
        }
        return new OriginalEvidence(fixed,financial);
    }
    private List<String> rows(String table,String where) {
        return jdbc.queryForList("select row_to_json(t)::text from "+table+" t"+where+" order by row_to_json(t)::text",String.class);
    }
    private void assertOriginal(OriginalEvidence evidence) {
        evidence.fixed().forEach((table,expected)->assertThat(rows(table,table.equals("provider_operations")?" where kind<>'TEST_CLOCK_ADVANCE'":"")).isEqualTo(expected));
        evidence.financial().forEach((table,values)->values.forEach((id,expected)->assertThat(jdbc.queryForObject("select row_to_json(t)::text from "+table+" t where id=?",String.class,id)).isEqualTo(expected)));
    }
    private void drift(String changed) {
        switch(changed) {
            case "owner"->clocks.stop(owner,StopReason.AUTHORITY_REVOKED,Instant.now());
            case "policy"->{var policy=policies.load(site);policies.save(new SubscriptionPolicy(site,policy.version(),false,3,false,false,false,Set.of()),policy.version());}
            case "revision"->jdbc.update("update subscriptions set revision=revision+1 where id=?",current().id());
            case "sequence"->jdbc.update("update subscriptions set observation_sequence=observation_sequence+1 where id=?",current().id());
            default->throw new AssertionError("Unknown test drift");
        }
    }
    private static void await(CountDownLatch latch) {
        try {if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("Composition race release timed out");}
        catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new AssertionError(failure);}
    }
}
