package dev.genericpayments.platform.webhooks.application.service;
import dev.genericpayments.platform.webhooks.application.port.in.RetryCallbackUseCase;
import dev.genericpayments.platform.webhooks.application.port.out.OutboxPort;
import dev.genericpayments.platform.payments.application.service.IdempotencyService;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort.Operation;
import java.time.Clock;
import java.util.*;
public final class CallbackRetryService implements RetryCallbackUseCase {
    private final OutboxPort outbox;private final IdempotencyService idempotency;private final Clock clock;
    public CallbackRetryService(OutboxPort outbox,IdempotencyService idempotency,Clock clock){this.outbox=outbox;this.idempotency=idempotency;this.clock=clock;}
    @Override public Accepted retry(UUID event,UUID admin,Mode mode,String key) {
        if(!outbox.ambientTransaction())throw new IllegalStateException("Callback retry requires mutation transaction");
        if(mode==null||admin==null||key==null||key.isBlank()||key.length()>255||key.chars().anyMatch(Character::isISOControl)||key.indexOf(',')>=0)throw new PaymentApplicationException("CALLBACK_CONFIG_INVALID",400,"Invalid callback retry request");
        UUID site=outbox.siteFor(event);var scope=Scope.admin(site,admin,Operation.CALLBACK_RETRY);
        String fingerprint=idempotency.fingerprint(Map.of("site_id",site,"event_id",event,"retry_mode",mode.name()));var outcome=idempotency.begin(scope,key,fingerprint);
        if(outcome instanceof IdempotencyService.Conflict)throw new PaymentApplicationException("IDEMPOTENCY_CONFLICT",409,"Idempotency key conflicts with request");
        if(outcome instanceof IdempotencyService.Completed completed){if(completed.resource().type()!=ResourceType.OUTBOX_RETRY_REQUEST)throw new IllegalStateException("Invalid callback retry resource");return new Accepted(outbox.retry(completed.resource().id()),false);}
        if(!(outcome instanceof IdempotencyService.Owner owner))throw new PaymentApplicationException("PAYMENT_STATE_CONFLICT",409,"Callback retry is already processing");
        var retry=outbox.enqueue(event,mode==Mode.SNAPSHOT?OutboxPort.Mode.SNAPSHOT_RETRY:OutboxPort.Mode.CURRENT_CONFIG_RETRY,clock.instant());idempotency.complete(owner,new Resource(ResourceType.OUTBOX_RETRY_REQUEST,retry.id()),202,null);return new Accepted(retry,true);
    }
}
