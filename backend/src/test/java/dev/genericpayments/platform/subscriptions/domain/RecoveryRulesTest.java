package dev.genericpayments.platform.subscriptions.domain;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class RecoveryRulesTest {
    final Instant end=Instant.parse("2026-10-18T12:00:00Z");
    @Test void cancelledSubscriptionCannotResume(){
        var cancelled=SubscriptionFixtures.active(end.minusSeconds(86400),end).cancel("CUSTOMER_REQUEST",end.minusSeconds(1));
        assertThatThrownBy(()->RecoveryRules.requireResumable(cancelled,true)).isInstanceOf(SubscriptionRuleViolation.class);
        assertThat(new SubscriptionAccessPolicy().evaluate(cancelled,end).allowed()).isFalse();
    }
    @Test void resumeNeedsPauseAndMethodAndNoPendingChange(){
        var active=SubscriptionFixtures.active(end.minusSeconds(86400),end);
        assertThatThrownBy(()->RecoveryRules.requireResumable(active,true)).isInstanceOf(SubscriptionRuleViolation.class);
        var paused=active.pause(end);
        assertThatThrownBy(()->RecoveryRules.requireResumable(paused,false)).isInstanceOf(SubscriptionRuleViolation.class);
        assertThatCode(()->RecoveryRules.requireResumable(paused,true)).doesNotThrowAnyException();
        var changing=SubscriptionFixtures.facts(paused,paused.billingStatus(),paused.billingPrice(),paused.entitledPrice(),paused.currentPeriod(),paused.paidThrough(),null,null,new Subscription.Change(UUID.randomUUID(),paused.billingPrice(),end,Subscription.ChangeState.UNKNOWN),null);
        assertThatThrownBy(()->RecoveryRules.requireResumable(changing,true)).isInstanceOf(SubscriptionRuleViolation.class);
    }
    @Test void newAttemptRequiresExactOpenDebtAndNoLiveAttempt(){
        var active=SubscriptionFixtures.active(end.minusSeconds(86400),end);
        var bill=new BillingRecord(UUID.randomUUID(),active.siteId(),active.id(),active.paymentAccountId(),"invoice",BillingRecord.Kind.RENEWAL,active.billingPrice(),null,new Subscription.Period(end,end.plusSeconds(86400)),1000,"EUR",BillingRecord.Settlement.OPEN,0,end,end);
        var debt=active.renewalFailed(bill.id(),bill.period(),3,end);
        assertThatThrownBy(()->RecoveryRules.requireRecoverable(debt,bill,true)).isInstanceOf(SubscriptionRuleViolation.class);
        assertThatThrownBy(()->RecoveryRules.requireRecoverable(active,bill,false)).isInstanceOf(SubscriptionRuleViolation.class);
        assertThatThrownBy(()->RecoveryRules.requireRecoverable(debt.cancel("DONE",end),bill,false)).isInstanceOf(SubscriptionRuleViolation.class);
        assertThatCode(()->RecoveryRules.requireRecoverable(debt,bill,false)).doesNotThrowAnyException();
    }
}
