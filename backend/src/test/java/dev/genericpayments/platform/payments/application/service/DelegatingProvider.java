package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.PaymentProviderPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
public class DelegatingProvider implements PaymentProviderPort {
    final PaymentProviderPort delegate;
    public DelegatingProvider(PaymentProviderPort delegate){this.delegate=delegate;}
    public PaymentProviderCapabilities capabilities(PaymentAccount a){return delegate.capabilities(a);}
    public ProviderResult preparePayment(ProviderPaymentRequest r){return delegate.preparePayment(r);}
    public ProviderResult confirmPayment(ProviderConfirmationRequest r){return delegate.confirmPayment(r);}
    public ProviderResult requestRefund(ProviderRefundRequest r){return delegate.requestRefund(r);}
    public ProviderState retrievePayment(ProviderPaymentLookup r){return delegate.retrievePayment(r);}
    public ProviderState retrieveRefund(ProviderRefundLookup r){return delegate.retrieveRefund(r);}
}
