package dev.genericpayments.platform.payments.application.model;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.Instant;
import java.util.*;
/** Explicit allowlisted read projections, never persistence entities. */
public final class AdminPaymentViews {
    private static final Instant LATEST_QUERY_TIME=Instant.parse("9999-12-31T23:59:59.999999999Z");
    private AdminPaymentViews(){}
    public enum Sort { CREATED_DESC,CREATED_ASC }
    public record Query(UUID siteId,PaymentStatus status,String externalOrderId,UUID paymentId,PaymentProviderType provider,Instant createdFrom,Instant createdTo,int page,int size,Sort sort,UUID customerId,UUID campaignId,UUID donationId){
        public Query(UUID siteId,PaymentStatus status,String externalOrderId,UUID paymentId,PaymentProviderType provider,Instant createdFrom,Instant createdTo,int page,int size,Sort sort){this(siteId,status,externalOrderId,paymentId,provider,createdFrom,createdTo,page,size,sort,null,null,null);}
        public Query{if(page<0||page>100000||size<1||size>100||sort==null||createdFrom!=null&&createdTo!=null&&!createdFrom.isBefore(createdTo))throw invalid();if(externalOrderId!=null)try{SafeFactJson.text(externalOrderId,255);}catch(IllegalArgumentException bad){throw invalid();}if(createdFrom!=null&&(createdFrom.isBefore(Instant.EPOCH)||createdFrom.isAfter(LATEST_QUERY_TIME))||createdTo!=null&&(createdTo.isBefore(Instant.EPOCH)||createdTo.isAfter(LATEST_QUERY_TIME)))throw invalid();}
    }
    public record Projection(UUID paymentId,UUID siteId,UUID paymentAccountId,String source,UUID priceId,String priceKey,Integer priceVersion,String externalOrderId,long amountMinor,String currency,String taxMode,Long taxAmountMinor,String status,String provider,long refundableAmountMinor,long refundedAmountMinor,Instant createdAt,Instant updatedAt,UUID customerId,UUID donationId,UUID campaignId,String campaignKey,Long campaignVersion,UUID subscriptionId,UUID billingRecordId,dev.genericpayments.platform.payments.domain.SubscriptionBillingContext.Kind subscriptionBillingKind,Instant subscriptionPeriodStart,Instant subscriptionPeriodEnd){
        public Projection(UUID paymentId,UUID siteId,UUID paymentAccountId,String source,UUID priceId,String priceKey,Integer priceVersion,String externalOrderId,long amountMinor,String currency,String taxMode,Long taxAmountMinor,String status,String provider,long refundableAmountMinor,long refundedAmountMinor,Instant createdAt,Instant updatedAt,UUID customerId,UUID donationId,UUID campaignId,String campaignKey,Long campaignVersion){this(paymentId,siteId,paymentAccountId,source,priceId,priceKey,priceVersion,externalOrderId,amountMinor,currency,taxMode,taxAmountMinor,status,provider,refundableAmountMinor,refundedAmountMinor,createdAt,updatedAt,customerId,donationId,campaignId,campaignKey,campaignVersion,null,null,null,null,null);}
    }
    public record Page(List<Projection> items,long total,int page,int size,Sort sort){public Page{items=List.copyOf(items);}}
    public record Attempt(UUID id,int attemptNumber,String status,String providerIntentReference,String paymentMethodFamily,String resultCode,Instant createdAt,Instant updatedAt){}
    public record Event(UUID id,long sequence,String eventType,String source,Long amountMinor,String currency,String providerEventId,String providerObjectId,String requestId,Instant occurredAt,Instant receivedAt){}
    public record RefundView(UUID id,long amountMinor,String currency,String status,String providerRefundReference,String reasonCode,Instant createdAt,Instant updatedAt){}
    public record Outbox(UUID id,String callbackType,String status,int attemptCount,Instant nextAttemptAt,Instant createdAt,Instant updatedAt){}
    public record Delivery(UUID id,UUID outboxEventId,int attemptNumber,String deliveryMode,String status,Integer httpStatus,String errorCategory,Instant startedAt,Instant finishedAt){}
    public record Details(Projection payment,List<Attempt> attempts,List<Event> events,List<RefundView> refunds,List<Outbox> outboxEvents,List<Delivery> deliveries){public Details{attempts=List.copyOf(attempts);events=List.copyOf(events);refunds=List.copyOf(refunds);outboxEvents=List.copyOf(outboxEvents);deliveries=List.copyOf(deliveries);}}
    private static PaymentApplicationException invalid(){return new PaymentApplicationException("PAYMENT_QUERY_INVALID",400,"Invalid payment query");}
}
