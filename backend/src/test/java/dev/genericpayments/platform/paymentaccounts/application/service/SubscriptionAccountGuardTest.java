package dev.genericpayments.platform.paymentaccounts.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionFactFixture;
import dev.genericpayments.platform.paymentaccounts.application.port.out.SavePaymentAccountPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
class SubscriptionAccountGuardTest extends SubscriptionFactFixture {
    @Autowired SavePaymentAccountPort save;
    @Autowired dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort locks;
    @Autowired org.springframework.context.ApplicationContext app;
    @Test void disablingAccountWithLiveSubscriptionIsRejectedAtomically() {
        start();
        assertThatThrownBy(()->save.save(account.disable())).isInstanceOf(RuntimeException.class);
        assertThat(accounts.findById(account.id()).orElseThrow().status()).isEqualTo(account.status());
    }
    @Test void terminalProjectionStillBlocksUnresolvedCreateButFinishedAccountCanDisable() {
        var result=start();tx.required(()->{var s=subscriptions.lock(site,result.subscriptionId());subscriptions.save(s.cancel("CONFIRMED",INITIAL_TIME),s.revision());return null;});
        assertThatThrownBy(()->save.save(account.disable())).isInstanceOf(RuntimeException.class);
        jdbc.update("update subscription_operations set status='FAILED' where id=?",result.operationId());
        assertThat(save.save(account.disable()).status()).isEqualTo(dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountStatus.DISABLED);
    }
    @Test void routingSwitchKeepsOriginalAccountForRecoveryAndReconciliation() {
        var r=start();apply(r.subscriptionId(),retrieve(r),"initial");var originalAccount=account.id();
        var replacement=java.util.UUID.randomUUID();jdbc.update("insert into payment_accounts values (?,'replacement','FAKE','SANDBOX','ACTIVE','fake','replacement',now(),0)",replacement);
        jdbc.update("update sites set payment_account_id=? where id=?",replacement,site.value());
        var control=app.getBean(dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.class);
        control.scenario(originalAccount,r.subscriptionId(),dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.DECLINE);
        var boundary=sub(r.subscriptionId()).paidThrough();TestClock.set(boundary);control.advance(originalAccount,boundary);apply(r.subscriptionId(),retrieve(r),"renewal");
        actor=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site,customer.id(),"https://facts.test").rawToken(),"https://facts.test"));
        control.scenario(originalAccount,r.subscriptionId(),dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.SUCCESS);
        var context=new dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext(actor,site,customer.id(),"https://facts.test","recover-old-account","task12");
        app.getBean(dev.genericpayments.platform.subscriptions.application.port.in.RecoverSubscriptionUseCase.class).recover(new dev.genericpayments.platform.subscriptions.application.port.in.RecoverSubscriptionUseCase.Command(context,r.subscriptionId(),sub(r.subscriptionId()).debt().billingRecordId()));
        app.getBean(dev.genericpayments.platform.subscriptions.application.service.SubscriptionSweepWorker.class).runOnce();
        assertThat(sub(r.subscriptionId()).debt()).isNull();assertThat(sub(r.subscriptionId()).paymentAccountId()).isEqualTo(originalAccount);
        assertThat(jdbc.queryForObject("select count(*) from fake_subscription_operations where payment_account_id=?",Integer.class,replacement)).isZero();
        assertThatThrownBy(()->save.save(account.disable())).isInstanceOf(RuntimeException.class);
        price=price(0);var next=start();assertThat(sub(next.subscriptionId()).paymentAccountId().value()).isEqualTo(replacement);
    }
    @Test void accountDisableWaitsForWinningCreateAndThenRejects()throws Exception {
        var acquired=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var pid=new java.util.concurrent.atomic.AtomicInteger();
        var old=org.springframework.test.util.ReflectionTestUtils.getField(commands,"accounts");
        dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort gated=id->{var value=locks.findByIdForUpdate(id);acquired.countDown();await(release);return value;};
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            org.springframework.test.util.ReflectionTestUtils.setField(commands,"accounts",gated);
            var creation=pool.submit(()->commands.reserveCreate(command("create-wins")));assertThat(acquired.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var disabling=pool.submit(()->tx.required(()->{pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));return save.save(account.disable());}));
            try {awaitLock(pid);assertThat(disabling.isDone()).isFalse();}finally{release.countDown();}
            assertThat(creation.get(10,java.util.concurrent.TimeUnit.SECONDS).subscription()).isNotNull();
            assertThatThrownBy(()->disabling.get(10,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
            assertThat(accounts.findById(account.id()).orElseThrow().status()).isEqualTo(account.status());assertThat(count("subscriptions")).isEqualTo(1);
        }finally{release.countDown();org.springframework.test.util.ReflectionTestUtils.setField(commands,"accounts",old);}
    }
    @Test void createWaitsForWinningDisableAndThenRejects()throws Exception {
        var acquired=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var pid=new java.util.concurrent.atomic.AtomicInteger();
        var old=org.springframework.test.util.ReflectionTestUtils.getField(commands,"accounts");
        dev.genericpayments.platform.paymentaccounts.application.port.out.LockPaymentAccountPort tracked=id->{pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));return locks.findByIdForUpdate(id);};
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            org.springframework.test.util.ReflectionTestUtils.setField(commands,"accounts",tracked);
            var disabling=pool.submit(()->tx.required(()->{var value=save.save(account.disable());acquired.countDown();await(release);return value;}));assertThat(acquired.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var creation=pool.submit(()->commands.reserveCreate(command("disable-wins")));
            try{awaitLock(pid);assertThat(creation.isDone()).isFalse();}finally{release.countDown();}
            assertThat(disabling.get(10,java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountStatus.DISABLED);
            assertThatThrownBy(()->creation.get(10,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);assertThat(count("subscriptions")).isZero();
        }finally{release.countDown();org.springframework.test.util.ReflectionTestUtils.setField(commands,"accounts",old);}
    }
    private void await(java.util.concurrent.CountDownLatch latch){try{if(!latch.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Lock gate expired");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    private void awaitLock(java.util.concurrent.atomic.AtomicInteger pid){org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->pid.get()!=0&&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid.get())));}
}
