package dev.genericpayments.platform.provider.adapter.out.stripe;

import com.stripe.param.RefundCreateParams;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.payments.domain.Refund;

final class StripeRefundRequests {
    private StripeRefundRequests() {}
    static RefundCreateParams create(Payment payment,Refund refund) {
        if(!refund.paymentId().equals(payment.id())||!StripePaymentSnapshotMapper.validReference(payment.providerPaymentReference(),"pi_")
                ||!refund.money().currency().equals(payment.money().currency())||refund.money().minorUnits()>payment.money().minorUnits())throw StripePaymentSnapshotMapper.mismatch();
        var builder=RefundCreateParams.builder().setPaymentIntent(payment.providerPaymentReference()).setAmount(refund.money().minorUnits())
            .putMetadata("payment_id",payment.id().value().toString()).putMetadata("refund_id",refund.id().value().toString());
        var reason=switch(refund.reasonCode()) {
            case "REQUESTED_BY_CUSTOMER" -> RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER;
            case "DUPLICATE" -> RefundCreateParams.Reason.DUPLICATE;
            case "FRAUDULENT" -> RefundCreateParams.Reason.FRAUDULENT;
            case null, default -> null;
        };
        if(reason!=null)builder.setReason(reason);
        return builder.build();
    }
}
