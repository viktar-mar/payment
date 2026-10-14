package dev.genericpayments.platform.provider.adapter.out.fake;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.PaymentProviderPort;
public final class FakePaymentProviderAdapter implements PaymentProviderPort {
 private final FakeProviderStatePersistenceAdapter state;private final FakePaymentProperties properties;
 public FakePaymentProviderAdapter(FakeProviderStatePersistenceAdapter state,FakePaymentProperties properties){this.state=state;this.properties=properties;}
 public PaymentProviderCapabilities capabilities(PaymentAccount account){if(account.provider()!=PaymentProviderType.FAKE)throw new IllegalStateException("PAYMENT_CAPABILITY_UNAVAILABLE");return PaymentProviderCapabilities.fake();}
 public ProviderResult preparePayment(ProviderPaymentRequest request){capabilities(request.account()).require(request.payment().taxMode());return state.prepare(request);}
 public ProviderResult confirmPayment(ProviderConfirmationRequest request){capabilities(request.account()).require(request.method());return state.confirm(request,properties.autoSuccess());}
 public ProviderResult requestRefund(ProviderRefundRequest request){capabilities(request.account());return state.refund(request);}
 public ProviderState retrievePayment(ProviderPaymentLookup lookup){return state.lookupPayment(lookup);}
 public ProviderState retrieveRefund(ProviderRefundLookup lookup){return state.lookupRefund(lookup);}
}
