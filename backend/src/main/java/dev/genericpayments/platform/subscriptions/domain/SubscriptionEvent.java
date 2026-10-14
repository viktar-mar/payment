package dev.genericpayments.platform.subscriptions.domain;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.time.Instant;
import java.util.*;
public record SubscriptionEvent(UUID id,SiteId siteId,UUID subscriptionId,PaymentAccountId accountId,long sequence,long revision,
    Type type,Source source,Instant occurredAt,Instant receivedAt,UUID operationId,UUID billingRecordId,PaymentId paymentId,String providerReference,String factKey,String requestId) {
    public enum Source { API, PROVIDER_WEBHOOK, RECONCILIATION, DEADLINE }
    public enum Type { CREATED, CONFIRMED, TRIAL_STARTED, TRIAL_ENDED, PAUSED, PERIOD_PAID, RENEWAL_FAILED, GRACE_STARTED, ACCESS_SUSPENDED, RECOVERED, CHANGE_REQUESTED, CHANGE_SCHEDULED, CHANGE_APPLIED, CHANGE_CLEARED, CANCEL_SCHEDULED, CANCEL_WITHDRAWN, CANCELLED, METHOD_UPDATED, RECONCILIATION_REQUIRED }
    public SubscriptionEvent {
        Objects.requireNonNull(id);Objects.requireNonNull(siteId);Objects.requireNonNull(subscriptionId);Objects.requireNonNull(accountId);Objects.requireNonNull(type);Objects.requireNonNull(source);Objects.requireNonNull(occurredAt);Objects.requireNonNull(receivedAt);Objects.requireNonNull(factKey);
        if(sequence<0||revision<0)throw new DomainValidationException("Invalid event counters");
        safe(providerReference);safe(factKey);safe(requestId);
    }
    private static void safe(String value){if(value!=null&&(value.length()>255||!value.matches("[A-Za-z0-9_.:/-]+")))throw new DomainValidationException("Invalid safe event reference");}
}
