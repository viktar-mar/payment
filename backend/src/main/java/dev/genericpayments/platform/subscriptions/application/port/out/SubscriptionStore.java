package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import java.util.*;
public interface SubscriptionStore {
    Optional<Subscription> find(SiteId site,UUID id);
    Optional<Subscription> findOwned(PaymentAccountId account,UUID id);
    Subscription lock(SiteId site,UUID id);
    /** -1 inserts revision 0 only; updates require submitted revision expectedRevision+1. Skip no-op domain writes. */
    Subscription save(Subscription subscription,long expectedRevision);
    boolean hasBlockingCustomerSubscriptions(SiteId site,CustomerId customer);
    boolean hasBlockingSiteSubscriptions(SiteId site);
    boolean hasNonTerminalAccountSubscriptions(PaymentAccountId account);
    boolean hasNonTerminalProduct(SiteId site,CustomerId customer,dev.genericpayments.platform.catalog.domain.ProductId product);
}
