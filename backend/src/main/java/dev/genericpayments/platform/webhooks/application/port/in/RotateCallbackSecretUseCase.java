package dev.genericpayments.platform.webhooks.application.port.in;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort.Reveal;
import java.util.UUID;
public interface RotateCallbackSecretUseCase {Reveal rotate(UUID site);}
