package dev.genericpayments.acceptance.runtime;

import com.google.gson.*;
import com.stripe.net.*;
import dev.genericpayments.platform.PaymentPlatformApplication;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.support.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.*;

/** Hosted-only genuine constructor/transaction regression; only provider HTTP replies are inert. */
@SpringBootTest(classes=PaymentPlatformApplication.class)
@Import(StripeBillingFirstRenewalCompositionFixture.RenewalTimeConfiguration.class)
class OwnedBillingFailedRenewalScenarioIntegrationTest extends OwnedBillingFirstPaymentScenarioFixture {
    private final Deque<String> boundaryReplies=new ArrayDeque<>();
    private boolean boundaryStage;

    @Override protected OwnedBillingFirstPaymentRuntime.Operations createScenario(OwnedBillingFirstPaymentRuntime.Admission admission) {
        return new OwnedBillingFailedRenewalSandboxScenario(admission,this::runtimeBean,Clock.systemUTC(),Instant.now().plusSeconds(120));
    }

    @Override protected Object runtimeBean(Class<?> type) {
        if(type==ApplySubscriptionSnapshotUseCase.class){
            var actual=application.getBean(ApplySubscriptionSnapshotUseCase.class);
            return (ApplySubscriptionSnapshotUseCase)command->{refreshObservationClock();actual.apply(command);};
        }
        return super.runtimeBean(type);
    }

    @Override protected StripeResponse renewalResponse(StripeRequest request) {
        if(!boundaryStage)return null;
        assertThat(transaction.active()).as("No caller transaction may span genuine SDK work").isFalse();
        assertThat(boundaryReplies).as("Every BOUNDARY SDK reply is planned in memory").isNotEmpty();
        return new StripeResponse(200,HttpHeaders.of(Map.of()),boundaryReplies.removeFirst());
    }

    @Test void actualComponentsPreserveTheManagedRenewalStoreAcrossInitialSettlementAndBoundary() {
        // This is the actual factory constructor, with real application stores/EMF/proofs.
        // Assertions follow the actual scenario path so an unproxied store cannot be hidden
        // by testing only the managed bean in isolation.
        var components=new OwnedBillingFailedRenewalComponents(this::runtimeBean,Clock.systemUTC());
        prepare();noMoney();scenario.confirm(confirmRequest());noMoney();paidGraph();
        var initial=observe();assertThat(initial.get("phase").getAsString()).isEqualTo("SETTLED_LOCAL");
        long end=Instant.parse(initial.get("paid_through").getAsString()).getEpochSecond();
        var moneyBefore=financialRows();int before=requests.size();
        queueBoundary(end);refreshObservationClock();
        assertThat(transaction.active()).isFalse();
        var result=((OwnedBillingFailedRenewalSandboxScenario)scenario).boundary();
        assertThat(transaction.active()).as("Short renewal transactions have returned before caller continuation").isFalse();
        assertThat(result.get("phase").getAsString()).isEqualTo("RENEWAL_BOUNDARY");
        assertThat(result.get("boundary_time").getAsString()).isEqualTo(Instant.ofEpochSecond(end).toString());
        assertThat(boundaryReplies).isEmpty();
        assertThat(components.renewals).as("Actual Components must preserve the original transaction-enabled store")
            .isSameAs(application.getBean(OwnedFirstRenewalStore.class));
        assertThat(financialRows()).as("BOUNDARY creates no renewal bill, cash, event or callback").isEqualTo(moneyBefore);
        assertThat(count("stripe_billing_first_renewals")).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_steps")).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_acks")).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_ready")).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_read_claims")).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_boundary")).isEqualTo(1);
        assertThat(count("stripe_billing_first_renewal_publications")).isZero();
        assertThat(count("stripe_billing_failed_renewals")).isZero();
        assertThat(jdbc.queryForList("select target from stripe_billing_first_renewal_steps",Long.class)).containsExactly(end);
        assertThat(requests.subList(before,requests.size()).stream().filter(r->r.method().name().equals("POST")))
            .singleElement().satisfies(post->{
                assertThat(post.url().getPath()).isEqualTo("/v1/test_helpers/test_clocks/clock_owned/advance");
                assertThat(post.params()).containsOnlyKeys("frozen_time").containsEntry("frozen_time",end);
            });
        // Scenario cleanup exercises the same store's independently committed stop.
        scenario.abort();
        assertThat(jdbc.queryForObject("select stopped_at is not null from stripe_billing_first_renewals",Boolean.class)).isTrue();
        assertThat(transaction.active()).isFalse();
    }

    private Map<String,List<Map<String,Object>>> financialRows(){
        var result=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("payments","payment_events","subscription_billing_records","subscription_events","outbox_events","outbox_deliveries"))
            result.put(table,jdbc.queryForList("select row_to_json(e)::text as row from "+table+" e order by row_to_json(e)::text"));
        return result;
    }

    private String clock(long at,String status){
        var value=JsonParser.parseString(clockJson(owner)).getAsJsonObject();value.addProperty("frozen_time",at);value.addProperty("status",status);return value.toString();
    }

    /** Wire-only pending graph; all accepted READY/boundary/business rows come from real producers. */
    private void queueBoundary(long end){
        boundaryStage=true;long next=end+31*86400;
        Collections.addAll(boundaryReplies,clock(FROZEN,"ready"),clock(FROZEN,"advancing"),clock(end,"ready"));
        var renewal=graph.invoice.deepCopy();renewal.addProperty("id","in_renewal");renewal.addProperty("billing_reason","subscription_cycle");
        renewal.addProperty("status","draft");renewal.addProperty("amount_paid",0);renewal.addProperty("amount_remaining",2000);
        renewal.addProperty("amount_paid_off_stripe",0);renewal.add("ending_balance",JsonNull.INSTANCE);
        var line=graph.line.deepCopy();line.addProperty("id","il_renewal");line.addProperty("invoice","in_renewal");
        line.remove("subscription");line.remove("subscription_item");
        line.getAsJsonObject("period").addProperty("start",end);line.getAsJsonObject("period").addProperty("end",next);
        graph.item().addProperty("current_period_start",end);graph.item().addProperty("current_period_end",next);
        Collections.addAll(boundaryReplies,clock(end,"ready"),customerJson("clock_owned"),graph.remote.toString(),
            StripeBillingOwnedSubscriptionFixture.page(graph.invoice,renewal),StripeBillingOwnedSubscriptionFixture.page(graph.line),
            StripeBillingOwnedSubscriptionFixture.page(graph.allocation),graph.pi.toString(),graph.charge.toString(),
            StripeBillingOwnedSubscriptionFixture.page(graph.allocation),StripeBillingOwnedSubscriptionFixture.page(line),
            StripeBillingOwnedSubscriptionFixture.page(),clock(end,"ready"));
    }
}
