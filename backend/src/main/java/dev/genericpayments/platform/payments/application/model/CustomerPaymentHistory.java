package dev.genericpayments.platform.payments.application.model;

import dev.genericpayments.platform.payments.domain.PaymentSource;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CustomerPaymentHistory(List<Item> items,String nextCursor) {
    public CustomerPaymentHistory { items=List.copyOf(items); }
    public record Item(UUID paymentId,PaymentSource source,long amountMinor,String currency,PaymentStatus status,
                       Instant createdAt,Instant updatedAt,Catalog catalog) {
        public Item { if(source==PaymentSource.SUBSCRIPTION&&catalog!=null)throw new IllegalArgumentException("Subscription history has no catalog snapshot"); }
    }
    public record Catalog(UUID priceId,String priceKey,int version) {}
}
