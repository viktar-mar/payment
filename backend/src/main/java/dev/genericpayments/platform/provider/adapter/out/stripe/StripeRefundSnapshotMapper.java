package dev.genericpayments.platform.provider.adapter.out.stripe;

import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.payments.domain.Refund;
import java.util.*;

/** Stripe Refund has no livemode: verified original Account and captured parent establish Sandbox mode. */
public final class StripeRefundSnapshotMapper {
    private StripeRefundSnapshotMapper() {}
    public static ProviderState.Status normalizedStatus(String status) {
        if(status==null)return ProviderState.Status.UNKNOWN;
        return switch(status) {
            case "pending","requires_action" -> ProviderState.Status.PROCESSING;
            case "succeeded" -> ProviderState.Status.SUCCEEDED;
            case "failed" -> ProviderState.Status.FAILED;
            case "canceled" -> ProviderState.Status.CANCELLED;
            default -> ProviderState.Status.UNKNOWN;
        };
    }
    static void requireParent(PaymentAccount account,Payment payment,PaymentIntent intent,Charge charge) {
        var parent=StripePaymentSnapshotMapper.payment(account,payment,intent,charge);
        if(parent.status()!=ProviderState.Status.SUCCEEDED||!parent.evidence().captured())throw StripePaymentSnapshotMapper.mismatch();
    }
    static void requireRefund(Payment payment,Refund local,com.stripe.model.Refund refund,String charge) {
        if(refund==null||!StripePaymentSnapshotMapper.validReference(refund.getId(),"re_")
                ||local.providerRefundReference()!=null&&!local.providerRefundReference().equals(refund.getId())
                ||!Objects.equals(refund.getAmount(),local.money().minorUnits())
                ||!local.money().currency().toLowerCase(Locale.ROOT).equals(refund.getCurrency())
                ||!payment.providerPaymentReference().equals(refund.getPaymentIntent())||!Objects.equals(charge,refund.getCharge())
                ||refund.getMetadata()==null||!local.id().value().toString().equals(refund.getMetadata().get("refund_id"))
                ||!payment.id().value().toString().equals(refund.getMetadata().get("payment_id")))throw StripePaymentSnapshotMapper.mismatch();
    }
    static ProviderState refund(PaymentAccount account,Payment payment,Refund local,com.stripe.model.Refund refund,
            PaymentIntent intent,Charge charge,String fingerprint) {
        requireParent(account,payment,intent,charge);requireRefund(payment,local,refund,charge.getId());
        return new ProviderState(refund.getId(),normalizedStatus(refund.getStatus()),new ProviderEvidence(account.provider(),account.id(),
            account.mode(),account.externalAccountId(),local.money(),payment.providerPaymentReference(),charge.getId(),null,true),
            new VerifiedRefundProof(account,payment,local,fingerprint));
    }
}
