package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.port.out.FakeSubscriptionControlPort.Scenario;
import java.util.UUID;import java.time.Instant;
public interface ControlFakeSubscriptionUseCase {
 enum Action {SCENARIO,COMPLETE_ACTION,ADVANCE}
 record Command(SubscriptionCommandContext context,UUID subscriptionId,Action action,Scenario scenario,Instant until){}
 record Result(UUID subscriptionId,UUID paymentAccountId,Action action,Scenario scenario,Instant until,boolean simulated){}
 Result control(Command command);
}
