package dev.genericpayments.platform.subscriptions.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.jayway.jsonpath.JsonPath;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.subscriptions.application.port.in.QuerySubscriptionsUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionQueryService;
import java.time.*;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CustomerSubscriptionHttpIntegrationTest extends SubscriptionApiFixture {
    @Test void oneEvaluationInstantIsSharedByEveryRowInThePage() {
        for(int i=0;i<2;i++){price=price(0);var made=start();apply(made.subscriptionId(),retrieve(made),"clock-"+i);}
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        Clock ticking=new Clock(){public java.time.ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(java.time.ZoneId zone){return this;}
            public Instant instant(){return INITIAL_TIME.plusSeconds(calls.getAndIncrement());}};
        var query=new SubscriptionQueryService(tx,web.getBean(SubscriptionAuthorityPort.class),subscriptions,web.getBean(SubscriptionReadPort.class),policies,
            web.getBean(SubscriptionCatalogPort.class),trials,accounts,providers,ticking);
        var page=query.list(new QuerySubscriptionsUseCase.Query(actor,site,customer.id(),null,"ACTIVE",20,null));
        assertThat(page.items()).hasSize(2).allSatisfy(v->assertThat(v.access().evaluatedAt()).isEqualTo(INITIAL_TIME));
        assertThat(calls.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(ints={0,3})
    void displayedStateFiltersBeforeKeysetLimitAndReevaluatesElapsedPaidOrTrialBoundary(int trialDays) throws Exception {
        var ids=new ArrayList<UUID>();
        for(int i=0;i<4;i++){
            price=price(trialDays);var made=start();apply(made.subscriptionId(),retrieve(made),"state-"+i);ids.add(made.subscriptionId());
        }
        ids.sort(Comparator.comparing(UUID::toString));
        for(UUID id:List.of(ids.getFirst(),ids.getLast())){
            var s=sub(id);var end=trialDays==0?s.paidThrough():s.trial().end();
            tx.required(()->subscriptions.save(s.scheduleCancellation(end,INITIAL_TIME),s.revision()));
        }
        String underlying=trialDays==0?"ACTIVE":"TRIALING";
        assertPage(underlying,ids.subList(1,3));
        assertPage("CANCEL_AT_PERIOD_END",List.of(ids.getFirst(),ids.getLast()));
        var boundary=trialDays==0?sub(ids.getFirst()).paidThrough():sub(ids.getFirst()).trial().end();
        TestClock.set(boundary);token=sessions.issue(site,customer.id(),"https://facts.test").rawToken();
        read(BASE+"?state=CANCEL_AT_PERIOD_END&limit=1").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.next_cursor").isEmpty());
        read(BASE+"?state="+underlying).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(4))
            .andExpect(jsonPath("$.items[0].state").value(underlying)).andExpect(jsonPath("$.items[0].access.evaluated_at").value(boundary.toString()));
    }
    private void assertPage(String state,List<UUID> expected) throws Exception {
        String first=read(BASE+"?state="+state+"&limit=1").andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].subscription_id").value(expected.getFirst().toString()))
            .andExpect(jsonPath("$.items[0].state").value(state)).andReturn().getResponse().getContentAsString();
        String cursor=JsonPath.read(first,"$.next_cursor");assertThat(cursor).isNotBlank();
        read(BASE+"?state="+state+"&limit=1&cursor="+cursor).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].subscription_id").value(expected.getLast().toString())).andExpect(jsonPath("$.items[0].state").value(state)).andExpect(jsonPath("$.next_cursor").isEmpty());
        read(BASE+"?state=INCOMPLETE&limit=1&cursor="+cursor).andExpect(status().isBadRequest());
    }
    @Test void unpaidInitialDebtRetainsAnExplicitNullDeadlineInSafeView() throws Exception {
        var made=start();var snapshot=retrieve(made);var invoice=snapshot.invoices().getFirst();
        var failed=new Snapshot(snapshot.subscriptionReference(),Subscription.Status.PAST_DUE,snapshot.billingPrice(),snapshot.period(),null,null,
            List.of(new Invoice("initial-no-entitlement",BillingRecord.Kind.INITIAL,snapshot.billingPrice(),null,invoice.period(),2000,"EUR",SettlementKind.OPEN,
                List.of(new CashObject("initial-failed",2000,"EUR",PaymentStatus.FAILED,INITIAL_TIME)))),snapshot.operationKey(),true,10,INITIAL_TIME);
        apply(made.subscriptionId(),failed,"initial-debt");
        String json=read(BASE+"/"+made.subscriptionId()).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.debt.amount_due_minor").value(2000)).andExpect(jsonPath("$.access.allowed").value(false)).andReturn().getResponse().getContentAsString();
        Map<String,Object> debt=JsonPath.read(json,"$.debt");assertThat(debt).containsEntry("grace_deadline",null);
        assertThat(json).doesNotContain("initial-no-entitlement","initial-failed","provider_reference","secret");
    }
}
