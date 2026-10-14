package dev.genericpayments.platform.payments.application.model;
import dev.genericpayments.platform.payments.domain.*;
public final class ProviderOperationKeys {
 private ProviderOperationKeys() {}
 public static String prepare(PaymentId id){return "prepare_"+id.value();}
 public static String confirm(PaymentAttemptId id){return "confirm_"+id.value();}
 public static String refund(RefundId id){return "refund_"+id.value();}
 public static String lookupPayment(PaymentId id){return "lookup_payment_"+id.value();}
 public static String lookupRefund(RefundId id){return "lookup_refund_"+id.value();}
 static void require(String actual,String expected){if(!expected.equals(actual))throw new IllegalArgumentException("Invalid provider operation identity");}
}
