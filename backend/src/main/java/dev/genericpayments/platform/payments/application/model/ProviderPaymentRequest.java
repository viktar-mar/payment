package dev.genericpayments.platform.payments.application.model;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.domain.Payment;
public record ProviderPaymentRequest(PaymentAccount account,Payment payment,String operationKey){
 public ProviderPaymentRequest{ProviderRequestRules.payment(account,payment);ProviderOperationKeys.require(operationKey,ProviderOperationKeys.prepare(payment.id()));}
 @Override public String toString(){return "ProviderPaymentRequest[redacted]";}
}
