package dev.genericpayments.platform.subscriptions.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionEventStore;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SubscriptionJournalIntegrationTest extends SubscriptionLedgerFixture {
    @Autowired SubscriptionEventStore events;
    SubscriptionEvent event(UUID subscription,String fact){return new SubscriptionEvent(UUID.randomUUID(),site,subscription,account.id(),0,0,SubscriptionEvent.Type.CREATED,SubscriptionEvent.Source.API,INITIAL_TIME,INITIAL_TIME,null,null,null,null,fact,"request-1");}
    @Test void concurrentAppendDeduplicatesFactsAndAllocatesGapFreeSequence() throws Exception {
        var s=store.save(subscription(),-1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);var tasks=new ArrayList<Future<Boolean>>();
            for(int i=0;i<12;i++){String fact="fact-"+(i%6);tasks.add(pool.submit(()->{start.await();return events.append(event(s.id(),fact));}));}
            start.countDown();int owners=0;for(var task:tasks)if(task.get(15,TimeUnit.SECONDS))owners++;
            assertThat(owners).isEqualTo(6);
        }
        assertThat(events.list(site,s.id(),20,0)).extracting(SubscriptionEvent::sequence).containsExactly(1L,2L,3L,4L,5L,6L);
        assertThat(events.list(site,s.id(),2,2)).extracting(SubscriptionEvent::sequence).containsExactly(3L,4L);
        assertThatThrownBy(()->jdbc.update("update subscription_events set request_id='changed' where subscription_id=?",s.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("delete from subscription_events where subscription_id=?",s.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void rolledBackEventDoesNotConsumeSequence() {
        var s=store.save(subscription(),-1);
        assertThatThrownBy(()->tx.required(()->{events.append(event(s.id(),"rollback"));throw new IllegalStateException("rollback");})).hasMessage("rollback");
        events.append(event(s.id(),"committed"));
        assertThat(events.list(site,s.id(),10,0)).extracting(SubscriptionEvent::sequence).containsExactly(1L);
    }
    @Test void eventCannotAttachCashFromAnotherPinnedAccount() {
        var s=store.save(subscription(),-1);var payment=save.save(payment());UUID other=UUID.randomUUID();
        jdbc.update("insert into payment_accounts values (?,'journal-other','FAKE','SANDBOX','ACTIVE','fake','ref',?,0)",other,java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update("update payments set payment_account_id=? where id=?",other,payment.id().value());
        var event=new SubscriptionEvent(UUID.randomUUID(),site,s.id(),account.id(),0,0,SubscriptionEvent.Type.PERIOD_PAID,SubscriptionEvent.Source.API,INITIAL_TIME,INITIAL_TIME,null,null,payment.id(),null,"wrong-payment","request");
        assertThatThrownBy(()->events.append(event)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
