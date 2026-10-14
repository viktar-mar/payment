package dev.genericpayments.platform.payments.application.model;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.domain.*;
import java.util.Objects;
public record ProviderRefundRequest(PaymentAccount account,Payment payment,Refund refund,String operationKey){
 public ProviderRefundRequest{
  ProviderRequestRules.payment(account,payment);Objects.requireNonNull(refund);
  if(refund.providerRefundReference()!=null)ProviderRequestRules.reference(refund.providerRefundReference());
  if(!refund.paymentId().equals(payment.id())||!refund.money().currency().equals(payment.money().currency())||refund.money().minorUnits()>payment.money().minorUnits()||!Objects.equals(operationKey,refund.providerOperationKey()))throw new IllegalArgumentException("Refund identity or money mismatch");
  ProviderOperationKeys.require(operationKey,ProviderOperationKeys.refund(refund.id()));
 }
 @Override public String toString(){return "ProviderRefundRequest[redacted]";}
}
