package dev.genericpayments.platform.payments.application.model;

import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.time.Instant;
import java.util.*;

/** Payment-owned allowlist. No provider facts, financial aggregate, or donor text crosses this boundary. */
public final class DonationPaymentViews {
    private DonationPaymentViews() {}
    public record Item(UUID donationId,UUID paymentId,UUID siteId,UUID customerId,UUID campaignId,
            String campaignKey,long campaignVersion,long amountMinor,String currency,PaymentStatus status,
            Instant createdAt,Instant updatedAt) {}
    public record Query(UUID siteId,UUID campaignId,UUID customerId,UUID donationId,UUID paymentId,
            PaymentStatus status,Instant createdFrom,Instant createdTo,int page,int size) {
        public Query {
            if(page<0||page>100000||size<1||size>50||invalidTime(createdFrom)||invalidTime(createdTo)
                ||createdFrom!=null&&createdTo!=null&&!createdFrom.isBefore(createdTo))
                throw new DomainValidationException("Invalid Donation query");
        }
        private static boolean invalidTime(Instant value){return value!=null&&(value.isBefore(Instant.EPOCH)||value.isAfter(Instant.parse("9999-12-31T23:59:59Z")));}
    }
    public record Page(List<Item> items,long total,int page,int size){public Page{items=List.copyOf(items);}}
}
