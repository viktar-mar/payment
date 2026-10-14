package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import dev.genericpayments.platform.webhooks.application.service.*;
import dev.genericpayments.platform.webhooks.application.port.out.SubscriptionInboxPort;
import dev.genericpayments.platform.provider.adapter.out.fake.FakeSubscriptionQueueRelay;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
class SubscriptionInboxIntegrationTest extends SubscriptionFactFixture {
    @Autowired SubscriptionInboxService inbox;@Autowired SubscriptionInboxWorker worker;@Autowired SubscriptionInboxPort store;
    @Autowired FakeSubscriptionQueueRelay relay;
    @Autowired dev.genericpayments.platform.payments.application.port.out.ProviderInboxPort paymentInbox;
    @Autowired dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionObjectPort objects;
    @Test void relayLeavesRowsBeyondItsExplicitBatchPending() {
        for(int i=0;i<3;i++){price=price(0);start();}
        var bounded=new FakeSubscriptionQueueRelay(jdbc,inbox,java.time.Clock.fixed(INITIAL_TIME,java.time.ZoneOffset.UTC),2);
        tx.required(()->{bounded.runOnce();return null;});
        assertThat(count("provider_inbox")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from fake_subscription_event_queue where delivered_at is null",Integer.class)).isEqualTo(1);
        tx.required(()->{bounded.runOnce();return null;});
        assertThat(count("provider_inbox")).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from fake_subscription_event_queue where delivered_at is null",Integer.class)).isZero();
    }
    @Test void identicalReferencesAcrossAccountsStayIsolatedAndSwappedSnapshotRollsBack(){
        var first=start();var firstAccount=account;var firstSub=sub(first.subscriptionId());
        var aid=java.util.UUID.randomUUID();jdbc.update("insert into payment_accounts values (?,'facts-second','FAKE','SANDBOX','ACTIVE','fake','ref',now(),0)",aid);
        jdbc.update("update sites set payment_account_id=? where id=?",aid,site.value());
        account=accounts.findById(new dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId(aid)).orElseThrow();price=price(0);
        var second=start();var secondSub=sub(second.subscriptionId());
        tx.required(()->{objects.bind(firstAccount.provider(),firstSub,dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionObjectPort.Kind.SETUP,"same-reference",null,INITIAL_TIME);objects.bind(account.provider(),secondSub,dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionObjectPort.Kind.SETUP,"same-reference",null,INITIAL_TIME);return null;});
        assertThat(objects.resolve(firstAccount.provider(),firstAccount.id(),dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionObjectPort.Kind.SETUP,"same-reference")).contains(firstSub.id());
        assertThat(objects.resolve(account.provider(),account.id(),dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionObjectPort.Kind.SETUP,"same-reference")).contains(secondSub.id());
        var foreign=retrieve(second);assertThatThrownBy(()->tx.required(()->{facts.apply(new dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Command(firstAccount,firstSub.id(),foreign,dev.genericpayments.platform.subscriptions.domain.SubscriptionEvent.Source.RECONCILIATION,"swapped","test"));return null;})).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("payments")).isZero();assertThat(count("subscription_events")).isZero();
        assertThat(jdbc.queryForObject("select observed_provider_revision from subscriptions where id=?",Long.class,firstSub.id())).isNull();
    }
    @Test void scheduledProviderRelayAndInboxDrainActuallyProjectFacts(){
        var r=start();
        new dev.genericpayments.platform.provider.adapter.in.configuration.FakeSubscriptionQueueScheduler(relay,1000).run();
        new dev.genericpayments.platform.webhooks.adapter.in.configuration.SubscriptionInboxScheduler(worker,1000).run();
        assertThat(sub(r.subscriptionId()).billingStatus()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(count("payments")).isEqualTo(1);
    }
    @Test void unsafeOrWrongFamilyHintsAreRejectedBeforePersistence(){
        assertThatThrownBy(()->new SubscriptionProviderEnvelope(account.provider(),account.id(),"event","object","PAYMENT_SUCCEEDED",INITIAL_TIME,INITIAL_TIME)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SubscriptionProviderEnvelope(account.provider(),account.id(),"secret\nvalue","object","SUBSCRIPTION_CHANGED",INITIAL_TIME,INITIAL_TIME)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SubscriptionProviderEnvelope(account.provider(),account.id(),"event","object","SUBSCRIPTION_CHANGED",null,INITIAL_TIME)).isInstanceOf(NullPointerException.class);
        assertThat(count("provider_inbox")).isZero();
    }
    @Test void relayRollbackAndAcceptedDuplicateKeepQueueAndInboxAtomic(){
        start();assertThatThrownBy(()->tx.required(()->{relay.runOnce();assertThat(count("provider_inbox")).isEqualTo(1);throw new IllegalStateException("rollback");})).hasMessage("rollback");
        assertThat(count("provider_inbox")).isZero();assertThat(jdbc.queryForObject("select count(*) from fake_subscription_event_queue where delivered_at is null",Integer.class)).isEqualTo(1);
        relay.runOnce();jdbc.update("update fake_subscription_event_queue set delivered_at=null");relay.runOnce();
        assertThat(count("provider_inbox")).isEqualTo(1);assertThat(jdbc.queryForObject("select count(*) from fake_subscription_event_queue where delivered_at is null",Integer.class)).isZero();
    }
    @Test void unmappedUnknownHintRemainsRetryableWithoutFakeReferenceParsing(){
        var reserved=commands.reserveCreate(command("unknown-hint"));commands.recordReply(reserved.operation().id(),new dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Reply(dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Outcome.UNKNOWN,null,null));
        inbox.receive(new SubscriptionProviderEnvelope(account.provider(),account.id(),"unknown-event","fake-subscription-"+reserved.subscription().id(),"SUBSCRIPTION_CHANGED",INITIAL_TIME,INITIAL_TIME));
        worker.runOnce();assertThat(jdbc.queryForObject("select status from provider_inbox",String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select error_category from provider_inbox",String.class)).isEqualTo("UNRESOLVED_OBJECT");assertThat(sub(reserved.subscription().id()).providerReference()).isNull();assertThat(count("payments")).isZero();
    }
    @Test void fakeQueueUsesSharedInboxAndPaymentWorkerNeverDecodesSubscriptionFamily(){
        var r=start();relay.runOnce();assertThat(count("provider_inbox")).isEqualTo(1);
        assertThat(paymentInbox.claim("money",20,INITIAL_TIME,Duration.ofSeconds(30))).isEmpty();
        worker.runOnce();assertThat(sub(r.subscriptionId()).billingStatus()).isEqualTo(Subscription.Status.ACTIVE);
        relay.runOnce();worker.runOnce();assertThat(count("payments")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from provider_inbox",String.class)).isEqualTo("PROCESSED");
    }
    @Test void lostLeaseCannotCommitAndDuplicateEnvelopeKeepsOneRow(){
        var r=start();var e=new SubscriptionProviderEnvelope(account.provider(),account.id(),"event",sub(r.subscriptionId()).providerReference(),"SUBSCRIPTION_CHANGED",INITIAL_TIME,INITIAL_TIME);
        assertThat(inbox.receive(e)).isTrue();assertThat(inbox.receive(e)).isFalse();
        var old=store.claim("old",1,INITIAL_TIME,Duration.ofSeconds(1)).getFirst();var fresh=store.claim("new",1,INITIAL_TIME.plusSeconds(2),Duration.ofSeconds(30)).getFirst();
        assertThatThrownBy(()->tx.required(()->store.lock(old,INITIAL_TIME.plusSeconds(2)))).hasMessage("CLAIM_LOST");
        tx.required(()->{assertThat(store.lock(fresh,INITIAL_TIME.plusSeconds(2))).isEqualTo(e);store.complete(fresh);return null;});
    }
}
