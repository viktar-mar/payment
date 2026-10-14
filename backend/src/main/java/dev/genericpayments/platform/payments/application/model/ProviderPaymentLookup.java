package dev.genericpayments.platform.payments.application.model;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.domain.PaymentId;
import java.util.Objects;
/** The key identifies a read request; it does not create a mutation receipt. */
public record ProviderPaymentLookup(PaymentAccount account,PaymentId paymentId,String reference,String operationKey){
 public ProviderPaymentLookup{ProviderRequestRules.account(account);Objects.requireNonNull(paymentId);ProviderRequestRules.reference(reference);ProviderOperationKeys.require(operationKey,ProviderOperationKeys.lookupPayment(paymentId));}
 public static ProviderPaymentLookup forPayment(PaymentAccount account,PaymentId id,String reference){return new ProviderPaymentLookup(account,id,reference,ProviderOperationKeys.lookupPayment(id));}
 @Override public String toString(){return "ProviderPaymentLookup[redacted]";}
}
