package dev.genericpayments.platform.webhooks.application.model;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import java.time.Instant;
import java.util.*;
public record SubscriptionProviderEnvelope(PaymentProviderType provider,PaymentAccountId accountId,String eventId,String objectReference,String eventType,Instant occurredAt,Instant receivedAt) {
    public SubscriptionProviderEnvelope {
        Objects.requireNonNull(provider);Objects.requireNonNull(accountId);Objects.requireNonNull(occurredAt);Objects.requireNonNull(receivedAt);
        if(eventType==null||!Set.of("SUBSCRIPTION_CHANGED","SUBSCRIPTION_INVOICE_CHANGED","SUBSCRIPTION_SETUP_CHANGED").contains(eventType))throw new IllegalArgumentException("Invalid subscription hint type");
        for(var reference:new String[]{eventId,objectReference})if(reference==null||!reference.matches("[A-Za-z0-9_.:/-]{1,255}"))throw new IllegalArgumentException("Invalid safe hint reference");
    }
}
