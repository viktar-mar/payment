package dev.genericpayments.platform.payments.adapter.out.persistence;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.payments.application.service.ProviderTestFixture;
import dev.genericpayments.platform.payments.application.service.ProviderInboxService;
import dev.genericpayments.platform.payments.application.model.ProviderEventEnvelope;
import dev.genericpayments.platform.payments.application.port.out.ProviderInboxPort;
import dev.genericpayments.platform.payments.domain.PaymentEventType;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProviderInboxClaimIntegrationTest extends ProviderTestFixture {
    @Autowired ProviderInboxPort inbox;@Autowired ProviderInboxService receive;
    void event(String id){receive.receive(new ProviderEventEnvelope(account.provider(),account.id(),id,"object",PaymentEventType.PAYMENT_PROCESSING,INITIAL_TIME,INITIAL_TIME,Map.of()));}
    @Test void concurrentWorkersNeverReceiveSameLiveClaim() throws Exception {
        for(int i=0;i<8;i++)event("evt-"+i);var barrier=new CyclicBarrier(4);var ids=new HashSet<UUID>();
        try(var pool=Executors.newFixedThreadPool(4)) { var futures=new ArrayList<Future<List<ProviderInboxPort.Claim>>>();for(int i=0;i<4;i++){String owner="worker-"+i;futures.add(pool.submit(() -> {barrier.await(5,TimeUnit.SECONDS);return inbox.claim(owner,2,INITIAL_TIME,Duration.ofSeconds(30));}));}for(var f:futures)for(var claim:f.get(10,TimeUnit.SECONDS))assertThat(ids.add(claim.id())).isTrue(); }
        assertThat(ids).hasSize(8);assertThat(jdbc.queryForList("select attempt_count from provider_inbox",Integer.class)).containsOnly(1);
    }
    @Test void leaseIsNotStealableUntilExpiryThenRecoveredOnce() {
        event("evt");var first=inbox.claim("first",1,INITIAL_TIME,Duration.ofSeconds(30)).getFirst();assertThat(inbox.claim("second",1,INITIAL_TIME.plusSeconds(29),Duration.ofSeconds(30))).isEmpty();
        assertThat(inbox.claim("second",1,INITIAL_TIME.plusSeconds(30),Duration.ofSeconds(30))).extracting(ProviderInboxPort.Claim::id).containsExactly(first.id());assertThat(jdbc.queryForObject("select attempt_count from provider_inbox",Integer.class)).isEqualTo(2);
    }
    @Test void invalidClaimBoundsCannotMutateRows() {
        event("evt");assertThatThrownBy(() -> inbox.claim("worker",0,INITIAL_TIME,Duration.ofSeconds(30))).isInstanceOf(RuntimeException.class);assertThatThrownBy(() -> inbox.claim("worker",1,INITIAL_TIME,Duration.ofDays(1))).isInstanceOf(RuntimeException.class);assertThat(jdbc.queryForObject("select status from provider_inbox",String.class)).isEqualTo("PENDING");
    }
}
