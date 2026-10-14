package dev.genericpayments.platform.subscriptions.adapter.out.integration;
import dev.genericpayments.platform.catalog.application.port.out.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCatalogPort;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.util.List;
import org.springframework.stereotype.Component;
@Component
public class SubscriptionCatalogAdapter implements SubscriptionCatalogPort {
    private final LoadPricePort prices;
    private final LoadProductPort products;
    public SubscriptionCatalogAdapter(LoadPricePort prices,LoadProductPort products){this.prices=prices;this.products=products;}
    public SubscriptionPriceTerms terms(SiteId site,PriceId id){return terms(owned(site,id));}
    public SubscriptionPriceTerms published(SiteId site,PriceId id) {
        var price=owned(site,id);
        products.findById(price.productId()).filter(p->p.siteId().equals(site)&&p.status()==ProductStatus.ACTIVE).orElseThrow(SubscriptionCatalogAdapter::unavailable);
        if(price.status()!=PriceStatus.PUBLISHED)throw unavailable();
        return terms(price);
    }
    public List<SubscriptionPriceTerms> publishedForProduct(SiteId site,ProductId product) {
        products.findById(product).filter(p->p.siteId().equals(site)&&p.status()==ProductStatus.ACTIVE).orElseThrow(SubscriptionCatalogAdapter::unavailable);
        return prices.findBySiteId(site).stream().filter(p->p.siteId().equals(site)&&p.productId().equals(product)&&p.status()==PriceStatus.PUBLISHED&&p.type()==PriceType.SUBSCRIPTION).map(SubscriptionCatalogAdapter::terms).toList();
    }
    private Price owned(SiteId site,PriceId id) {
        if(id==null)throw unavailable();
        return prices.findById(id).filter(p->p.siteId().equals(site)&&p.type()==PriceType.SUBSCRIPTION&&p.publishedAt()!=null&&(p.status()==PriceStatus.PUBLISHED||p.status()==PriceStatus.ARCHIVED)).orElseThrow(SubscriptionCatalogAdapter::unavailable);
    }
    private static SubscriptionPriceTerms terms(Price p){return new SubscriptionPriceTerms(p.id(),p.key().value(),p.version(),p.productId(),p.money(),p.billingInterval(),p.trialDays(),p.trialPaymentMethodPolicy(),p.taxMode());}
    private static PaymentApplicationException unavailable(){return new PaymentApplicationException("PRICE_NOT_AVAILABLE",404,"Price unavailable");}
}
