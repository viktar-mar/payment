package dev.genericpayments.platform.webhooks.application.service;
import dev.genericpayments.platform.webhooks.application.port.in.*;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import java.util.UUID;
public final class CallbackConfigurationService implements ConfigureCallbackUseCase,RotateCallbackSecretUseCase {
    private final CallbackConfigurationPort configurations;private final CallbackTargetPolicyPort targets;
    public CallbackConfigurationService(CallbackConfigurationPort configurations,CallbackTargetPolicyPort targets){this.configurations=configurations;this.targets=targets;}
    @Override public CallbackConfigurationPort.Reveal configure(UUID site,String endpoint,boolean enabled){String normalized=targets.normalize(endpoint);return configurations.configure(site,normalized,enabled);}
    @Override public CallbackConfigurationPort.Reveal rotate(UUID site){return configurations.rotate(site);}
}
