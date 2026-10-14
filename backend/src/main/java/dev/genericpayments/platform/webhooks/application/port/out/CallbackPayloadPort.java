package dev.genericpayments.platform.webhooks.application.port.out;
public interface CallbackPayloadPort { byte[] bytes(OutboxPort.Claim claim); }
