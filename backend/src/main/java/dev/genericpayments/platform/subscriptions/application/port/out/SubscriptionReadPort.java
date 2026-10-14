package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.QuerySubscriptionsUseCase;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import java.util.*;
public interface SubscriptionReadPort {
 record RowPage(List<Subscription> items,String nextCursor) {public RowPage{items=List.copyOf(items);} }
 RowPage list(SiteId site,CustomerId customer,ProductId product,String state,int limit,String cursor,java.time.Instant evaluatedAt);
 Optional<Subscription> current(SiteId site,CustomerId customer,ProductId product);
 SubscriptionHistory events(SiteId site,UUID id,int limit,String cursor);
 QuerySubscriptionsUseCase.BillingPage billingRecords(SiteId site,UUID id,int limit,String cursor);
 Optional<SubscriptionOperation> unresolvedOperation(SiteId site,UUID id);
 Optional<SubscriptionOperation> operation(SiteId site,UUID operationId);
 Optional<BillingRecord> bill(SiteId site,UUID subscriptionId,UUID billId);
}
