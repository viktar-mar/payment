package dev.genericpayments.platform.webhooks.adapter.in.configuration;
import dev.genericpayments.platform.webhooks.adapter.out.http.CallbackUrlPolicy;
import dev.genericpayments.platform.webhooks.adapter.out.http.SafeCallbackHttpAdapter;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import dev.genericpayments.platform.webhooks.application.service.*;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import dev.genericpayments.platform.payments.application.service.IdempotencyService;
import java.time.*;
import java.net.InetAddress;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Value;
@Configuration(proxyBeanMethods=false)
public class CallbackConfigurationBeans {
    @Bean CallbackUrlPolicy callbackUrlPolicy(Environment environment){return new CallbackUrlPolicy(java.util.Arrays.asList(environment.getActiveProfiles()).contains("local"),host->List.of(InetAddress.getAllByName(host)));}
    @Bean CallbackHttpPort callbackHttpPort(CallbackUrlPolicy policy,@Value("${payment.callbacks.connect-timeout-ms:2000}")long connect,@Value("${payment.callbacks.request-timeout-ms:5000}")long request){return SafeCallbackHttpAdapter.create(policy,Duration.ofMillis(connect),Duration.ofMillis(request));}
    @Bean CallbackConfigurationService callbackConfigurationService(CallbackConfigurationPort port,CallbackTargetPolicyPort targets){return new CallbackConfigurationService(port,targets);}
    @Bean CallbackRetryService callbackRetryService(OutboxPort outbox,IdempotencyService idempotency,Clock clock){return new CallbackRetryService(outbox,idempotency,clock);}
    @Bean CallbackDeliveryService callbackDeliveryService(OutboxPort outbox,EncryptedSecretPort encryption,CallbackPayloadPort payload,CallbackHttpPort http,Clock clock){return new CallbackDeliveryService(outbox,encryption,payload,http,clock);}
    @Bean OutboxWorker outboxWorker(OutboxPort outbox,CallbackDeliveryService delivery,Clock clock,dev.genericpayments.platform.shared.application.port.out.PendingOutboxTelemetryPort pending,@Value("${payment.callbacks.batch-size:20}")int batch,@Value("${payment.callbacks.lease-seconds:120}")long seconds){if(batch<1||batch>100||seconds<1||seconds>600)throw new IllegalArgumentException("Invalid callback worker configuration");return new OutboxWorker(outbox,delivery,clock,batch,Duration.ofSeconds(seconds),pending);}
}
