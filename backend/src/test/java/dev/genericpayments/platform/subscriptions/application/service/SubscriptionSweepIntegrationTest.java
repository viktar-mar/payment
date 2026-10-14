package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.adapter.in.configuration.SubscriptionProperties;
import java.time.*;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;
class SubscriptionSweepIntegrationTest extends SubscriptionRecoveryFixture {
    SubscriptionSweepPort sweep(){return bean(SubscriptionSweepPort.class);}
    @Test void liveClaimsAreExclusiveAndSameOwnerReclaimFencesOldTokenAndFinish() {
        var id=start().subscriptionId();var old=sweep().claim("same",20,INITIAL_TIME,Duration.ofSeconds(1)).getFirst();
        assertThat(sweep().claim("other",20,INITIAL_TIME,Duration.ofSeconds(30))).isEmpty();
        TestClock.set(INITIAL_TIME.plusSeconds(1));var fresh=sweep().claim("same",20,INITIAL_TIME.plusSeconds(1),Duration.ofSeconds(30)).getFirst();
        assertThat(fresh.leaseToken()).isNotEqualTo(old.leaseToken());
        sweep().finish(old,INITIAL_TIME.plusSeconds(7200),false);
        assertThat(jdbc.queryForObject("select reconcile_lease_token from subscriptions where id=?",java.util.UUID.class,id)).isEqualTo(fresh.leaseToken());
        assertThat(jdbc.queryForObject("select reconcile_attempts from subscriptions where id=?",Integer.class,id)).isZero();
        assertThatThrownBy(()->tx.required(()->{sweep().requireCurrent(old,INITIAL_TIME.plusSeconds(1));return null;})).hasMessage("CLAIM_LOST");
        bean(SubscriptionReconciliationService.class).reconcileClaim(old);assertThat(count("payments")).isZero();
        bean(SubscriptionReconciliationService.class).reconcileClaim(fresh);assertThat(count("payments")).isEqualTo(1);
    }
    @Test void changedRevisionAndExpiredLeaseCannotApplyProviderResultOrAlterScheduling() {
        var id=start().subscriptionId();var claim=sweep().claim("worker",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst();
        var service=bean(SubscriptionReconciliationService.class);var original=org.springframework.test.util.ReflectionTestUtils.getField(service,"providers");
        var once=new java.util.concurrent.atomic.AtomicBoolean();
        SubscriptionProviderRegistry registry=a->{var provider=providers.forAccount(a);return (SubscriptionProviderPort)java.lang.reflect.Proxy.newProxyInstance(provider.getClass().getClassLoader(),new Class<?>[]{SubscriptionProviderPort.class},(p,m,args)->{
            assertThat(tx.active()).isFalse();Object result;try{result=m.invoke(provider,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            if(m.getName().equals("retrieve")&&once.compareAndSet(false,true))jdbc.update("update subscriptions set revision=revision+1 where id=?",id);return result;});};
        try{org.springframework.test.util.ReflectionTestUtils.setField(service,"providers",registry);service.reconcileClaim(claim);}finally{org.springframework.test.util.ReflectionTestUtils.setField(service,"providers",original);}
        assertThat(count("payments")).isZero();assertThat(jdbc.queryForObject("select reconcile_attempts from subscriptions where id=?",Integer.class,id)).isZero();
        TestClock.set(INITIAL_TIME.plusSeconds(30));var expired=sweep().claim("worker",1,INITIAL_TIME.plusSeconds(30),Duration.ofSeconds(1)).getFirst();TestClock.set(INITIAL_TIME.plusSeconds(31));
        service.reconcileClaim(expired);assertThat(count("payments")).isZero();
    }
    @Test void unknownReadUsesCappedDurableBackoffAndFreshWorkersShareSchedule() {
        var reserved=commands.reserveCreate(command("lost-before-provider"));commands.recordReply(reserved.operation().id(),new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Reply(dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Outcome.UNKNOWN,null,null));
        var now=INITIAL_TIME;long[] delays={60,300,1800,7200,7200};
        for(long delay:delays){TestClock.set(now);new SubscriptionSweepWorker(sweep(),bean(SubscriptionReconciliationService.class),bean(SubscriptionProperties.class),bean(Clock.class)).runOnce();
            var next=jdbc.queryForObject("select next_reconcile_at from subscriptions where id=?",Timestamp.class,reserved.subscription().id()).toInstant();assertThat(next).isEqualTo(now.plusSeconds(delay));now=next;}
        assertThat(count("fake_subscription_operations")).isZero();assertThat(count("payments")).isZero();
    }
    @Test void boundedClaimsPrioritizeUnknownAndRejectInvalidConfiguration() {
        var first=paid();price=price(0);var pending=commands.reserveCreate(command("unknown"));
        commands.recordReply(pending.operation().id(),new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Reply(dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Outcome.UNKNOWN,null,null));
        assertThat(sweep().claim("priority",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst().subscriptionId()).isEqualTo(pending.subscription().id());
        assertThat(sweep().claim("other",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst().subscriptionId()).isEqualTo(first.subscriptionId());
        for(int batch:new int[]{0,101})assertThatThrownBy(()->new SubscriptionProperties(batch,30L,30000L)).isInstanceOf(IllegalArgumentException.class);
        for(long seconds:new long[]{0,601})assertThatThrownBy(()->new SubscriptionProperties(20,seconds,30000L)).isInstanceOf(IllegalArgumentException.class);
        var defaults=bean(SubscriptionProperties.class);assertThat(defaults.batchSize()).isEqualTo(20);assertThat(defaults.lease()).isEqualTo(Duration.ofSeconds(30));assertThat(defaults.delay()).isEqualTo(Duration.ofSeconds(30));
    }
}
