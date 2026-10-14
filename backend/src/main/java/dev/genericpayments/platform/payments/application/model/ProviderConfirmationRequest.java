package dev.genericpayments.platform.payments.application.model;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.PaymentMethod;
import java.util.Objects;
public record ProviderConfirmationRequest(PaymentAccount account,Payment payment,PaymentAttempt attempt,PaymentMethod method,String operationKey){
 public ProviderConfirmationRequest{
  ProviderRequestRules.payment(account,payment);Objects.requireNonNull(attempt);Objects.requireNonNull(method);
  if(attempt.providerIntentReference()!=null)ProviderRequestRules.reference(attempt.providerIntentReference());
  if(!attempt.paymentId().equals(payment.id())||!method.name().equals(attempt.paymentMethodFamily())||!Objects.equals(operationKey,attempt.providerOperationKey()))throw new IllegalArgumentException("Attempt identity mismatch");
  if(payment.providerPaymentReference()!=null && attempt.providerIntentReference()!=null && !payment.providerPaymentReference().equals(attempt.providerIntentReference()))throw new IllegalArgumentException("Attempt reference mismatch");
  ProviderOperationKeys.require(operationKey,ProviderOperationKeys.confirm(attempt.id()));
 }
 @Override public String toString(){return "ProviderConfirmationRequest[redacted]";}
}
