package dev.genericpayments.platform.payments.application.model;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.sites.domain.*;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import java.util.Objects;
public record ConfirmPaymentCommand(SiteId siteId,PaymentId paymentId,PaymentAccount account,PaymentAttemptId attemptId,PaymentMethod method,String siteKey,String requestId,String idempotencyKey){
 public ConfirmPaymentCommand{
  Objects.requireNonNull(siteId);Objects.requireNonNull(paymentId);ProviderRequestRules.account(account);Objects.requireNonNull(attemptId);Objects.requireNonNull(method);
  new Stage2TelemetryPort.Context(siteKey,Stage2TelemetryPort.Provider.valueOf(account.provider().name()));
  ProviderRequestRules.text(requestId,128);ProviderRequestRules.text(idempotencyKey,255);
 }
 @Override public String toString(){return "ConfirmPaymentCommand[redacted]";}
}
