package dev.genericpayments.platform.provider.adapter.out.fake;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import dev.genericpayments.platform.webhooks.application.port.in.ReceiveSubscriptionEventUseCase;
import dev.genericpayments.platform.webhooks.application.model.SubscriptionProviderEnvelope;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.UUID;
@Component
public class FakeSubscriptionQueueRelay {
    private final JdbcTemplate jdbc;private final ReceiveSubscriptionEventUseCase receiver;private final Clock clock;private final int batch;
    public FakeSubscriptionQueueRelay(JdbcTemplate jdbc,ReceiveSubscriptionEventUseCase receiver,Clock clock,@Value("${payment.inbox.batch-size:20}")int batch){if(batch<1||batch>100)throw new IllegalArgumentException("Invalid batch");this.jdbc=jdbc;this.receiver=receiver;this.clock=clock;this.batch=batch;}
    @Transactional public void runOnce(){
        var hints=jdbc.query("select id,payment_account_id,provider_reference,occurred_at from fake_subscription_event_queue where delivered_at is null order by occurred_at,id limit ? for update skip locked",(r,n)->new SubscriptionProviderEnvelope(PaymentProviderType.FAKE,new PaymentAccountId(r.getObject("payment_account_id",UUID.class)),r.getObject("id",UUID.class).toString(),r.getString("provider_reference"),"SUBSCRIPTION_CHANGED",r.getTimestamp("occurred_at").toInstant(),clock.instant()),batch);
        for(var hint:hints){receiver.receive(hint);jdbc.update("update fake_subscription_event_queue set delivered_at=? where id=?",Timestamp.from(clock.instant()),UUID.fromString(hint.eventId()));}
    }
}
