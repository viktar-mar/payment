package dev.genericpayments.platform.payments.application.model;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.domain.*;
import java.util.*;
final class ProviderRequestRules {
 private ProviderRequestRules(){}
 static void account(PaymentAccount a){Objects.requireNonNull(a);if(a.status()!=PaymentAccountStatus.ACTIVE)throw new IllegalArgumentException("Payment account unavailable");text(a.externalAccountId(),255);text(a.credentialsRef(),255);}
 static void payment(PaymentAccount a,Payment p){account(a);Objects.requireNonNull(p);if(!a.id().equals(p.paymentAccountId()))throw new IllegalArgumentException("Payment account mismatch");if(p.providerPaymentReference()!=null)reference(p.providerPaymentReference());}
 static void text(String value,int max){if(value==null||value.isBlank()||value.length()>max||value.codePoints().anyMatch(c->Character.isISOControl(c)||(c>=0xd800&&c<=0xdfff)))throw new IllegalArgumentException("Invalid bounded text");}
 static void reference(String ref){if(ref==null||!ref.matches("[A-Za-z0-9_-]{1,255}"))throw new IllegalArgumentException("Invalid provider reference");}
}
