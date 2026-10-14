package dev.genericpayments.platform.payments.application.model;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.sites.domain.PaymentMethod;
import java.util.*;
public record PaymentProviderCapabilities(Set<TaxMode> taxModes,Set<PaymentMethod> methods,ConfirmationMode confirmationMode){
 public enum ConfirmationMode {SERVER,CLIENT_PROVIDER}
 public PaymentProviderCapabilities{taxModes=Set.copyOf(taxModes);methods=Set.copyOf(methods);Objects.requireNonNull(confirmationMode);if(taxModes.isEmpty()||methods.isEmpty())throw new IllegalArgumentException("Empty provider capabilities");}
 public static PaymentProviderCapabilities fake(){return new PaymentProviderCapabilities(Set.of(TaxMode.NONE,TaxMode.INCLUSIVE),Set.of(PaymentMethod.CARD,PaymentMethod.APPLE_PAY,PaymentMethod.GOOGLE_PAY,PaymentMethod.PAYPAL),ConfirmationMode.SERVER);}
 public void require(TaxMode taxMode){if(!taxModes.contains(taxMode))throw new IllegalStateException("PAYMENT_CAPABILITY_UNAVAILABLE");}
 public void require(PaymentMethod method){if(!methods.contains(method)||confirmationMode!=ConfirmationMode.SERVER)throw new IllegalStateException("PAYMENT_CAPABILITY_UNAVAILABLE");}
}
