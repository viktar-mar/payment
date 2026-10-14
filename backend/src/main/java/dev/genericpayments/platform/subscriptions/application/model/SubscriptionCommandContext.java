package dev.genericpayments.platform.subscriptions.application.model;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
public record SubscriptionCommandContext(SubscriptionActor actor,SiteId siteId,CustomerId customerId,String origin,String idempotencyKey,String requestId) {
    @Override public String toString(){return "SubscriptionCommandContext[siteId="+siteId+", customerId="+customerId+", credentials=[REDACTED]]";}
}
