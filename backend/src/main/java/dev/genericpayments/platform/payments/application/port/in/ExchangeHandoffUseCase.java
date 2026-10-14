package dev.genericpayments.platform.payments.application.port.in;
import dev.genericpayments.platform.payments.application.model.CheckoutCreation;
import java.net.URI;
public interface ExchangeHandoffUseCase { CheckoutCreation exchange(String handoffToken,URI origin,String remoteIp); }
