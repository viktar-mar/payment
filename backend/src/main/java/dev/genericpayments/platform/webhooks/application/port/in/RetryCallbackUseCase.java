package dev.genericpayments.platform.webhooks.application.port.in;
import dev.genericpayments.platform.webhooks.application.port.out.OutboxPort;
import java.util.UUID;
public interface RetryCallbackUseCase {
    enum Mode {SNAPSHOT,CURRENT}
    record Accepted(OutboxPort.Retry request,boolean created) {}
    Accepted retry(UUID event,UUID admin,Mode mode,String key);
}
