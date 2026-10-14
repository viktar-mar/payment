package dev.genericpayments.platform.subscriptions.domain;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
class PlanChangeRulesTest {
    final Instant now=Instant.parse("2026-10-11T12:00:00Z");
    @Test void expiredQuoteAndChangedPolicyOrRevisionAreRejected(){
        var s=SubscriptionFixtures.active(now,now.plusSeconds(86400));
        var q=new SubscriptionQuote(UUID.randomUUID(),s.siteId(),s.id(),"test-authority-digest",s.billingPrice().priceId(),s.billingPrice().version(),s.billingPrice(),ChangeTiming.IMMEDIATE,100,2000,"EUR",now,now.plusSeconds(60),false,s.revision(),4,"fake_quote_1");
        assertThatThrownBy(()->PlanChangeRules.requireQuoteCurrent(q,s.revision(),5,now)).isInstanceOf(SubscriptionRuleViolation.class);
        assertThatThrownBy(()->PlanChangeRules.requireQuoteCurrent(q,s.revision()+1,4,now)).isInstanceOf(SubscriptionRuleViolation.class);
        assertThatThrownBy(()->PlanChangeRules.requireQuoteCurrent(q,s.revision(),4,now.plusSeconds(60))).isInstanceOf(SubscriptionRuleViolation.class);
        assertThatCode(()->PlanChangeRules.requireQuoteCurrent(q,s.revision(),4,now)).doesNotThrowAnyException();
    }
    @Test void onlyPaidActiveWithoutAnotherMutationCanChange(){
        var s=SubscriptionFixtures.active(now,now.plusSeconds(86400));
        for(var status:Subscription.Status.values())if(status!=Subscription.Status.ACTIVE){
            var other=SubscriptionFixtures.facts(s,status,s.billingPrice(),s.entitledPrice(),s.currentPeriod(),s.paidThrough(),null,null,null,null);
            assertThatThrownBy(()->PlanChangeRules.requireChangeable(other,false)).isInstanceOf(SubscriptionRuleViolation.class);
        }
        assertThatThrownBy(()->PlanChangeRules.requireChangeable(s,true)).isInstanceOf(SubscriptionRuleViolation.class);
        assertThatThrownBy(()->PlanChangeRules.requireChangeable(s.scheduleCancellation(s.paidThrough(),now),false)).isInstanceOf(SubscriptionRuleViolation.class);
    }
    @Test void directionIsExplicitAndIntervalsCapabilitiesAndPolicyRestrictImmediate(){
        var s=SubscriptionFixtures.active(now,now.plusSeconds(86400));var current=s.billingPrice();
        var target=SubscriptionFixtures.terms(s.productId(),"pro",500);
        var policy=new SubscriptionPolicy(s.siteId(),1,true,3,false,false,false,Set.of());
        var upgrade=new PlanTransition(s.siteId(),s.productId(),current.priceId(),target.priceId(),PlanTransition.Direction.UPGRADE,true,1);
        assertThat(PlanChangeRules.allowedTiming(upgrade,current,target,policy,true)).containsExactlyInAnyOrder(ChangeTiming.IMMEDIATE,ChangeTiming.NEXT_PERIOD);
        assertThat(PlanChangeRules.allowedTiming(upgrade,current,target,policy,false)).containsExactly(ChangeTiming.NEXT_PERIOD);
        var yearly=new SubscriptionPriceTerms(target.priceId(),target.priceKey(),target.version(),target.productId(),target.regularAmount(),BillingInterval.YEAR,0,target.trialPolicy(),target.taxMode());
        assertThat(PlanChangeRules.allowedTiming(upgrade,current,yearly,policy,true)).containsExactly(ChangeTiming.NEXT_PERIOD);
        var downgrade=new PlanTransition(s.siteId(),s.productId(),current.priceId(),target.priceId(),PlanTransition.Direction.DOWNGRADE,true,1);
        assertThat(PlanChangeRules.allowedTiming(downgrade,current,target,policy,true)).containsExactly(ChangeTiming.NEXT_PERIOD);
    }
}
