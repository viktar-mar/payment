package dev.genericpayments.platform.subscriptions.application.port.in;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.domain.AccessDecision;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import java.util.*;
public interface QuerySubscriptionsUseCase {
 record Query(SubscriptionActor actor,SiteId siteId,CustomerId customerId,ProductId productId,String state,int limit,String cursor) {}
 record Page(List<SubscriptionView> items,String nextCursor) { public Page{items=List.copyOf(items);} }
 record BillingPage(List<SubscriptionBillingView> items,String nextCursor) {public BillingPage{items=List.copyOf(items);} }
 Page list(Query query);
 SubscriptionView get(SubscriptionActor actor,SiteId site,UUID id);
 SubscriptionHistory history(SubscriptionActor actor,SiteId site,UUID id,int limit,String cursor);
 BillingPage billingRecords(SubscriptionActor actor,SiteId site,UUID id,int limit,String cursor);
 List<SubscriptionOffer> offers(SubscriptionActor actor,SiteId site,CustomerId customer,ProductId product);
 AccessDecision access(SubscriptionActor actor,SiteId site,CustomerId customer,ProductId product);
 SubscriptionOperationResult operation(SubscriptionActor actor,SiteId site,UUID operationId);
}
