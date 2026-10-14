package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms;
import java.util.List;
public interface SubscriptionCatalogPort {
    SubscriptionPriceTerms published(SiteId site,PriceId price);
    List<SubscriptionPriceTerms> publishedForProduct(SiteId site,ProductId product);
    /** Exact immutable originally-published terms, including archived Prices, for durable replay only. */
    SubscriptionPriceTerms terms(SiteId site,PriceId price);
}
