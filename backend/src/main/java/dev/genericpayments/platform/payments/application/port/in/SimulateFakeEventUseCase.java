package dev.genericpayments.platform.payments.application.port.in;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.payments.application.model.SafeFactJson;
import java.time.Instant;
import java.util.*;
public interface SimulateFakeEventUseCase {
    enum Target { PAYMENT,REFUND }
    record Command(PaymentId paymentId,UUID adminId,Target target,PaymentEventType eventType,long sequence,RefundId refundId,Instant occurredAt,String key,String requestId){
        public Command{Objects.requireNonNull(paymentId);Objects.requireNonNull(adminId);if(target==null||eventType==null||sequence<1||sequence>Integer.MAX_VALUE||(target==Target.REFUND)!=(refundId!=null)||!(target==Target.PAYMENT?Set.of(PaymentEventType.PAYMENT_METHOD_REQUIRED,PaymentEventType.CUSTOMER_ACTION_REQUIRED,PaymentEventType.PAYMENT_PROCESSING,PaymentEventType.PAYMENT_SUCCEEDED,PaymentEventType.PAYMENT_FAILED,PaymentEventType.PAYMENT_CANCELLED):Set.of(PaymentEventType.REFUND_PROCESSING,PaymentEventType.REFUND_SUCCEEDED,PaymentEventType.REFUND_FAILED)).contains(eventType))throw new PaymentApplicationException("FAKE_EVENT_INVALID",400,"Invalid fake event");SafeFactJson.text(key,255);SafeFactJson.text(requestId,128);}
    }
    record Accepted(String eventId,String status){}
    Accepted simulate(Command command);
}
