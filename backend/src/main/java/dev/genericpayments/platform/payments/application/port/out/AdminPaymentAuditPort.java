package dev.genericpayments.platform.payments.application.port.out;
import java.util.*;
public interface AdminPaymentAuditPort {
    enum Action { PAYMENT_REFUND_REQUESTED,PAYMENT_RECONCILIATION_REQUESTED,FAKE_PROVIDER_EVENT_SIMULATED }
    void record(Action action,UUID actor,UUID payment,UUID resource,String requestId,Map<String,String> facts);
}
