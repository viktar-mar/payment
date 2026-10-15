package dev.genericpayments.platform.provider.adapter.in.configuration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.genericpayments.platform.payments.application.service.StripeRecoveryService;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeSandboxProperties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class StripeRecoverySchedulerTest {
    StripeRecoveryScheduler scheduler(StripeRecoveryService service,boolean enabled){var beans=new StaticListableBeanFactory();beans.addBean("recovery",service);return new StripeRecoveryScheduler(beans.getBeanProvider(StripeRecoveryService.class),new StripeSandboxProperties(enabled,"sk_test_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","pk_test_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB","whsec_CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"),100);}
    @Test void slowRecoveryNeverOverlapsAndShutdownInterruptsItsOwnedLane() throws Exception {
        var service=mock(StripeRecoveryService.class);var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);var calls=new AtomicInteger();
        doAnswer(invocation->{calls.incrementAndGet();entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){interrupted.countDown();Thread.currentThread().interrupt();}return null;}).when(service).runOnce();
        var scheduler=scheduler(service,true);
        try{scheduler.start();scheduler.start();assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();Thread.sleep(250);assertThat(calls.get()).isEqualTo(1);}
        finally{scheduler.stop();}
        assertThat(interrupted.await(2,TimeUnit.SECONDS)).isTrue();
    }
    @Test void failedAcquisitionDoesNotCancelFutureTicks() throws Exception {
        var service=mock(StripeRecoveryService.class);var continued=new CountDownLatch(1);var count=new AtomicInteger();
        doAnswer(invocation->{if(count.incrementAndGet()==1)throw new IllegalStateException("storage unavailable");continued.countDown();return null;}).when(service).runOnce();
        var scheduler=scheduler(service,true);try{scheduler.start();assertThat(continued.await(2,TimeUnit.SECONDS)).isTrue();}finally{scheduler.stop();}
    }
    @Test void disabledStripeNeverStartsRecovery() throws Exception {
        var service=mock(StripeRecoveryService.class);var called=new CountDownLatch(1);doAnswer(i->{called.countDown();return null;}).when(service).runOnce();
        var scheduler=scheduler(service,false);try{scheduler.start();assertThat(called.await(300,TimeUnit.MILLISECONDS)).isFalse();}finally{scheduler.stop();}
    }
}
