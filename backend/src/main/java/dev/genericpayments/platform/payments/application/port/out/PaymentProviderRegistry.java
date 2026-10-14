package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import java.util.*;
public final class PaymentProviderRegistry{
 private final Map<PaymentProviderType,PaymentProviderPort> providers;
 public PaymentProviderRegistry(Map<PaymentProviderType,PaymentProviderPort> providers){this.providers=Map.copyOf(providers);}
 public PaymentProviderPort select(PaymentAccount account){
  Objects.requireNonNull(account);var port=providers.get(account.provider());
  if(port==null)throw new IllegalStateException("PAYMENT_CAPABILITY_UNAVAILABLE");return port;
 }
}
