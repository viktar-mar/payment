package dev.genericpayments.platform.payments.application.model;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.domain.PaymentEventType;
import java.time.Instant;
import java.util.*;
/** Only bounded flat safe facts; no raw provider response is accepted at this boundary. */
public record ProviderEventEnvelope(PaymentProviderType provider,PaymentAccountId accountId,String eventId,String objectId,
                                    PaymentEventType type,Instant occurredAt,Instant receivedAt,Map<String,?> payload) {
    public ProviderEventEnvelope {
        if(provider==null||accountId==null||type==null||occurredAt==null||receivedAt==null||occurredAt.isAfter(receivedAt)||occurredAt.isBefore(Instant.EPOCH))throw SafeFactJson.invalid();
        SafeFactJson.text(eventId,255);SafeFactJson.text(objectId,255);
        if(!Set.of(PaymentEventType.PAYMENT_METHOD_REQUIRED,PaymentEventType.CUSTOMER_ACTION_REQUIRED,PaymentEventType.PAYMENT_PROCESSING,PaymentEventType.PAYMENT_SUCCEEDED,PaymentEventType.PAYMENT_FAILED,PaymentEventType.PAYMENT_CANCELLED,PaymentEventType.REFUND_REQUESTED,PaymentEventType.REFUND_PROCESSING,PaymentEventType.REFUND_SUCCEEDED,PaymentEventType.REFUND_FAILED).contains(type))throw SafeFactJson.invalid();
        SafeFactJson.json(payload);
        payload.forEach((key,value) -> {
            if(value instanceof String s&&s.length()>500)throw SafeFactJson.invalid();
            switch(key) {
                case "payment_id","refund_id" -> {
                    if(key.equals("refund_id")&&!type.name().startsWith("REFUND_"))throw SafeFactJson.invalid();
                    if(!(value instanceof String s))throw SafeFactJson.invalid();
                    try { if(!UUID.fromString(s).toString().equals(s))throw SafeFactJson.invalid(); }
                    catch(IllegalArgumentException invalid){throw SafeFactJson.invalid();}
                }
                case "schema_version" -> { if(!(value instanceof Integer version)||version!=1)throw SafeFactJson.invalid(); }
                case "state" -> { if(!normalizedState(type).equals(value))throw SafeFactJson.invalid(); }
                default -> throw SafeFactJson.invalid();
            }
        });
        payload=Map.copyOf(payload);
    }
    public String canonicalJson(){return SafeFactJson.json(payload);}
    private static String normalizedState(PaymentEventType type){return switch(type){
        case PAYMENT_METHOD_REQUIRED -> "REQUIRES_PAYMENT_METHOD";
        case CUSTOMER_ACTION_REQUIRED -> "REQUIRES_ACTION";
        case PAYMENT_PROCESSING,REFUND_PROCESSING -> "PROCESSING";
        case PAYMENT_SUCCEEDED,REFUND_SUCCEEDED -> "SUCCEEDED";
        case PAYMENT_FAILED,REFUND_FAILED -> "FAILED";
        case PAYMENT_CANCELLED -> "CANCELLED";
        case REFUND_REQUESTED -> "REQUESTED";
        default -> throw SafeFactJson.invalid();
    };}
}
