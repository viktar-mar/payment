package dev.genericpayments.platform.provider.adapter.out.stripe;

import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.util.*;

public final class StripePaymentSnapshotMapper {
    private StripePaymentSnapshotMapper() {}
    public static ProviderState.Status normalizedStatus(String status) {
        if(status==null)return ProviderState.Status.UNKNOWN;
        return switch(status) {
            case "requires_confirmation","requires_payment_method" -> ProviderState.Status.REQUIRES_PAYMENT_METHOD;
            case "requires_action" -> ProviderState.Status.REQUIRES_ACTION;
            case "processing" -> ProviderState.Status.PROCESSING;
            case "succeeded" -> ProviderState.Status.SUCCEEDED;
            case "canceled" -> ProviderState.Status.CANCELLED;
            default -> ProviderState.Status.UNKNOWN;
        };
    }
    /** The supplied Charge is latest_charge, or the last failed Charge when latest_charge is absent. */
    public static ProviderState payment(PaymentAccount account, Payment payment, PaymentIntent intent, Charge charge) {
        requireIntent(account,payment,intent);
        var status=normalizedStatus(intent.getStatus());
        if(status==ProviderState.Status.UNKNOWN)throw StripePaymentRequests.unavailable();
        String expected=intent.getLatestCharge()!=null?intent.getLatestCharge():failedReference(intent);
        if(expected!=null) {
            requireCharge(payment,intent,charge,expected);
        } else if(charge!=null)throw mismatch();
        boolean captured=charge!=null&&Boolean.TRUE.equals(charge.getCaptured());
        if(status==ProviderState.Status.SUCCEEDED && (charge==null||!captured||!Boolean.TRUE.equals(charge.getPaid())
                ||!"succeeded".equals(charge.getStatus())||!Objects.equals(charge.getAmountCaptured(),payment.money().minorUnits())
                ||!Objects.equals(intent.getAmountReceived(),payment.money().minorUnits())||intent.getLatestCharge()==null))throw mismatch();
        String failed=null;
        if(charge!=null&&charge.getId().equals(failedReference(intent))) {
            requireFailedCharge(payment,intent,charge);failed=charge.getId();
        }
        if(failed==null&&charge!=null&&Boolean.FALSE.equals(charge.getPaid())&&"failed".equals(charge.getStatus()))failed=charge.getId();
        return new ProviderState(intent.getId(),status,new ProviderEvidence(account.provider(),account.id(),account.mode(),
            account.externalAccountId(),payment.money(),intent.getId(),charge==null?null:charge.getId(),failed,captured));
    }
    static void requireIntent(PaymentAccount account,Payment payment,PaymentIntent intent) {
        if(account.provider()!=PaymentProviderType.STRIPE||account.mode()!=PaymentAccountMode.SANDBOX
                ||!payment.paymentAccountId().equals(account.id())||intent==null||!validReference(intent.getId(),"pi_")
                ||!Objects.equals(payment.providerPaymentReference(),intent.getId())||!Boolean.FALSE.equals(intent.getLivemode())
                ||!Objects.equals(intent.getAmount(),payment.money().minorUnits())||!currency(payment,intent.getCurrency()))throw mismatch();
        // Same immutable money/tax/source restrictions as original creation; no current Site routing involved.
        StripePaymentRequests.prepare(payment);
        if(!"automatic".equals(intent.getCaptureMethod())||!List.of("card").equals(intent.getPaymentMethodTypes()))throw StripePaymentRequests.unavailable();
        if(intent.getLatestCharge()!=null&&!validReference(intent.getLatestCharge(),"ch_")
                ||failedReference(intent)!=null&&!validReference(failedReference(intent),"ch_"))throw mismatch();
    }
    static String failedReference(PaymentIntent intent) {
        return intent.getLastPaymentError()==null?null:intent.getLastPaymentError().getCharge();
    }
    static void requireFailedCharge(Payment payment,PaymentIntent intent,Charge charge) {
        String failed=failedReference(intent);
        if(failed==null)throw mismatch();
        requireCharge(payment,intent,charge,failed);
        if(!Boolean.FALSE.equals(charge.getPaid())||!"failed".equals(charge.getStatus()))throw mismatch();
    }
    private static void requireCharge(Payment payment,PaymentIntent intent,Charge charge,String expected) {
        if(charge==null||!validReference(charge.getId(),"ch_")||!expected.equals(charge.getId())
                ||!Boolean.FALSE.equals(charge.getLivemode())||!intent.getId().equals(charge.getPaymentIntent())
                ||!Objects.equals(charge.getAmount(),payment.money().minorUnits())||!currency(payment,charge.getCurrency()))throw mismatch();
        if(charge.getPaymentMethodDetails()==null||!"card".equals(charge.getPaymentMethodDetails().getType()))throw StripePaymentRequests.unavailable();
    }
    static boolean validReference(String value,String prefix) { return value!=null&&value.matches(prefix+"[A-Za-z0-9_]{1,200}"); }
    private static boolean currency(Payment payment,String currency) {
        return payment.money().currency().toLowerCase(Locale.ROOT).equals(currency);
    }
    static PaymentApplicationException mismatch() {
        return new PaymentApplicationException("PROVIDER_EVIDENCE_MISMATCH",409,"Provider evidence does not match the payment");
    }
}
