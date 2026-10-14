package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.*;
public interface SubscriptionQuoteStore {
    void save(SubscriptionQuote quote);
    SubscriptionQuote lock(SiteId site,UUID quote);
    /** Immutable, owned CHANGE/CLEAR_CHANGE intent, bound in the reservation transaction. */
    void bind(SubscriptionOperation operation,SubscriptionQuote quote);
    Optional<SubscriptionQuote> forOperation(SiteId site,UUID subscription,UUID operation);
}
