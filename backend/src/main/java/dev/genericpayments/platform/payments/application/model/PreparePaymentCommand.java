package dev.genericpayments.platform.payments.application.model;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
public record PreparePaymentCommand(Payment payment,PaymentAccount account,String siteKey,String requestId,String idempotencyKey){
 public PreparePaymentCommand{
  ProviderRequestRules.payment(account,payment);new Stage2TelemetryPort.Context(siteKey,Stage2TelemetryPort.Provider.valueOf(account.provider().name()));
  ProviderRequestRules.text(requestId,128);ProviderRequestRules.text(idempotencyKey,255);
  if(payment.status()!=PaymentStatus.CREATED||payment.providerPaymentReference()!=null||payment.version()!=0)throw new IllegalArgumentException("Creation requires original payment facts");
 }
 @Override public String toString(){return "PreparePaymentCommand[redacted]";}
}
