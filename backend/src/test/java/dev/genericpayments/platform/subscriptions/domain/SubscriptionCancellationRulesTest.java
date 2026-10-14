package dev.genericpayments.platform.subscriptions.domain;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
class SubscriptionCancellationRulesTest {
    final Instant now=Instant.parse("2026-10-11T12:00:00Z");
    @Test void confirmedCancellationCannotBeResurrectedByPaidPeriod(){
        var s=SubscriptionFixtures.active(now,now.plusSeconds(86400));var terminal=s.cancel("CUSTOMER_REQUEST",now);
        var late=terminal.confirmPaid(s.billingPrice(),s.currentPeriod(),now.plusSeconds(10));
        assertThat(late.billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
        assertThat(new SubscriptionAccessPolicy().evaluate(late,now.plusSeconds(10)).allowed()).isFalse();
    }
    @Test void inactiveFinishIsNarrowAndLivePaidOrTrialStillNeedsPolicy(){
        var s=SubscriptionFixtures.active(now,now.plusSeconds(86400));var denied=SubscriptionPolicy.defaults(s.siteId());
        for(var status:Set.of(Subscription.Status.PAUSED,Subscription.Status.UNPAID,Subscription.Status.INCOMPLETE)){
            var inactive=SubscriptionFixtures.facts(s,status,s.billingPrice(),null,s.currentPeriod(),now,null,null,null,null);
            assertThat(SubscriptionCancellationRules.allowImmediate(inactive,denied,now)).as("Explicit finish of %s",status).isTrue();
            var livePaid=SubscriptionFixtures.facts(s,status,s.billingPrice(),s.entitledPrice(),s.currentPeriod(),now.plusSeconds(1),null,null,null,null);
            assertThat(SubscriptionCancellationRules.allowImmediate(livePaid,denied,now)).isFalse();
            var liveTrial=SubscriptionFixtures.facts(s,status,s.billingPrice(),s.entitledPrice(),s.currentPeriod(),null,new Subscription.Period(now,now.plusSeconds(1)),null,null,null);
            assertThat(SubscriptionCancellationRules.allowImmediate(liveTrial,denied,now)).isFalse();
        }
        var pastDue=SubscriptionFixtures.facts(s,Subscription.Status.PAST_DUE,s.billingPrice(),s.entitledPrice(),s.currentPeriod(),now,null,null,null,null);
        assertThat(SubscriptionCancellationRules.allowImmediate(pastDue,denied,now)).isFalse();
        var allowed=new SubscriptionPolicy(s.siteId(),0,true,3,true,false,false,Set.of());
        assertThat(SubscriptionCancellationRules.allowImmediate(s,allowed,now)).isTrue();
    }
    @Test void withdrawalRequiresExistingFutureScheduleAndNonterminalState(){
        var s=SubscriptionFixtures.active(now,now.plusSeconds(86400));var scheduled=s.scheduleCancellation(now.plusSeconds(86400),now);
        assertThatCode(()->SubscriptionCancellationRules.requireWithdrawable(scheduled,now)).doesNotThrowAnyException();
        assertThatThrownBy(()->SubscriptionCancellationRules.requireWithdrawable(scheduled,now.plusSeconds(86400))).isInstanceOf(SubscriptionRuleViolation.class);
        assertThatThrownBy(()->SubscriptionCancellationRules.requireWithdrawable(s,now)).isInstanceOf(SubscriptionRuleViolation.class);
        assertThatThrownBy(()->SubscriptionCancellationRules.requireWithdrawable(scheduled.cancel("CONFIRMED",now),now)).isInstanceOf(SubscriptionRuleViolation.class);
    }
}
