package dev.genericpayments.platform.payments.application.model;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.domain.*;
import java.util.Objects;
/** The key identifies a read request; it does not create a mutation receipt. */
public record ProviderRefundLookup(PaymentAccount account,PaymentId paymentId,RefundId refundId,String reference,String operationKey){
 public ProviderRefundLookup{ProviderRequestRules.account(account);Objects.requireNonNull(paymentId);Objects.requireNonNull(refundId);ProviderRequestRules.reference(reference);ProviderOperationKeys.require(operationKey,ProviderOperationKeys.lookupRefund(refundId));}
 public static ProviderRefundLookup forRefund(PaymentAccount account,PaymentId payment,RefundId id,String reference){return new ProviderRefundLookup(account,payment,id,reference,ProviderOperationKeys.lookupRefund(id));}
 @Override public String toString(){return "ProviderRefundLookup[redacted]";}
}
