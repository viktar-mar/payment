package dev.genericpayments.platform.subscriptions.application.service;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class SubscriptionDeadlineIntegrationTest extends SubscriptionRecoveryFixture {
    @Test void deadlineWritesOneSuspensionAndOutboxWithoutProviderMutation() throws Exception {
        var secret=java.util.UUID.randomUUID();
        jdbc.update("insert into site_callback_secret_versions(id,site_id,secret_version,ciphertext,nonce,master_key_version,created_at) values (?,?,1,?,?,'test-v1',?)",secret,site.value(),new byte[32],new byte[12],java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into site_callback_configs(site_id,endpoint_url,active_secret_version_id,enabled,created_at,updated_at) values (?,'https://callback.test/exact',?,true,?,?)",site.value(),secret,java.sql.Timestamp.from(INITIAL_TIME),java.sql.Timestamp.from(INITIAL_TIME));
        var id=paid().subscriptionId();
        controls.scenario(account.id(),id,dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.DECLINE);
        advance(sub(id).paidThrough());
        var deadline=sub(id).debt().graceDeadline();TestClock.set(deadline);
        int providerCommands=count("fake_subscription_operations"),payments=count("payments"),outbox=count("outbox_events");
        for(int i=0;i<2;i++)if(app.containsBean("subscriptionDeadlineWorker"))app.getBean("subscriptionDeadlineWorker").getClass().getMethod("runOnce").invoke(app.getBean("subscriptionDeadlineWorker"));
        assertThat(jdbc.queryForObject("select count(*) from subscription_events where event_type='ACCESS_SUSPENDED'",Integer.class)).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(outbox+1);
        assertThat(count("payments")).isEqualTo(payments);assertThat(count("fake_subscription_operations")).isEqualTo(providerCommands);
    }
    @Test void recoveredDebtCannotBeSuspendedByAnEarlierDeadlineCandidate() {
        var id=paid().subscriptionId();controls.scenario(account.id(),id,dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.DECLINE);advance(sub(id).paidThrough());
        TestClock.set(sub(id).debt().graceDeadline());freshActor();
        var claim=bean(dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionSweepPort.class).expiredGrace(20,bean(java.time.Clock.class).instant()).getFirst();
        controls.scenario(account.id(),id,dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario.SUCCESS);recovery.recover(recoverCommand(id,"recover-before-deadline"));drain();
        bean(SubscriptionDeadlineWorker.class).expire(claim);
        assertThat(sub(id).debt()).isNull();assertThat(jdbc.queryForObject("select count(*) from subscription_events where event_type='ACCESS_SUSPENDED'",Integer.class)).isZero();
    }
}
