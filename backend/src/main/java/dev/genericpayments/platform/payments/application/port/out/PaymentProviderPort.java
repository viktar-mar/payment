package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
public interface PaymentProviderPort{
 PaymentProviderCapabilities capabilities(PaymentAccount account);
 ProviderResult preparePayment(ProviderPaymentRequest request);
 ProviderResult confirmPayment(ProviderConfirmationRequest request);
 ProviderResult requestRefund(ProviderRefundRequest request);
 ProviderState retrievePayment(ProviderPaymentLookup lookup);
 ProviderState retrieveRefund(ProviderRefundLookup lookup);
}
