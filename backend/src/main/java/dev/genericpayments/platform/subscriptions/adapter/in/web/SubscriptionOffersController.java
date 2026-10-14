package dev.genericpayments.platform.subscriptions.adapter.in.web;
import static dev.genericpayments.platform.subscriptions.adapter.in.web.SubscriptionTransport.*;
import dev.genericpayments.platform.subscriptions.application.port.in.QuerySubscriptionsUseCase;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.partners.adapter.in.web.AuthenticatedSite;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
@RestController
public class SubscriptionOffersController {
 private final QuerySubscriptionsUseCase query;
 public SubscriptionOffersController(QuerySubscriptionsUseCase query){this.query=query;}
 @GetMapping("/api/v1/customer/subscription-products/{productId}/offers") public SubscriptionOffers customer(@AuthenticationPrincipal CustomerSessionContext c,@PathVariable UUID productId){return offers(query.offers(new SubscriptionActor.Customer(c),c.siteId(),c.customerId(),new ProductId(productId)));}
 @GetMapping("/api/v1/partner/customers/{customerId}/products/{productId}/subscription-offers") public SubscriptionOffers partner(@AuthenticationPrincipal AuthenticatedSite p,@PathVariable UUID customerId,@PathVariable UUID productId){return offers(query.offers(actor(p),p.siteId(),new CustomerId(customerId),new ProductId(productId)));}
 @GetMapping("/api/v1/partner/customers/{customerId}/products/{productId}/access") public SubscriptionAccessResponse access(@AuthenticationPrincipal AuthenticatedSite p,@PathVariable UUID customerId,@PathVariable UUID productId){return SubscriptionTransport.access(query.access(actor(p),p.siteId(),new CustomerId(customerId),new ProductId(productId)));}
 @GetMapping("/api/v1/admin/sites/{siteId}/customers/{customerId}/products/{productId}/subscription-offers") public SubscriptionOffers admin(@AuthenticationPrincipal AuthenticatedAdmin a,@PathVariable UUID siteId,@PathVariable UUID customerId,@PathVariable UUID productId){return offers(query.offers(new SubscriptionActor.Admin(a),new SiteId(siteId),new CustomerId(customerId),new ProductId(productId)));}
 private SubscriptionActor actor(AuthenticatedSite p){return new SubscriptionActor.Partner(new dev.genericpayments.platform.partners.application.port.in.AuthenticatedSite(p.siteId(),p.keyId(),p.scopes()));}
}
