package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionEvent;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.*;
public interface SubscriptionEventStore {
    /** Allocates sequence transactionally; input sequence is ignored. False means the fact was already appended. */
    boolean append(SubscriptionEvent event);
    List<SubscriptionEvent> list(SiteId site,UUID subscription,int limit,long afterSequence);
    boolean hasBillingFact(SiteId site,UUID subscription,UUID bill,SubscriptionEvent.Type type);
}
