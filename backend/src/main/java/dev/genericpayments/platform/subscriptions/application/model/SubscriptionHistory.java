package dev.genericpayments.platform.subscriptions.application.model;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionEvent;
import java.util.List;
public record SubscriptionHistory(List<SubscriptionEvent> items,String nextCursor) { public SubscriptionHistory {items=List.copyOf(items);} }
