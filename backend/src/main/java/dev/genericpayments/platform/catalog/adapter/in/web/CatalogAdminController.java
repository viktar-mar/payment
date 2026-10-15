package dev.genericpayments.platform.catalog.adapter.in.web;

import dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin;
import dev.genericpayments.platform.admin.domain.AuditAction;
import dev.genericpayments.platform.catalog.application.port.in.*;
import dev.genericpayments.platform.catalog.application.port.out.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.shared.adapter.in.web.AdminAuditFacade;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.SiteId;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin/v1")
public class CatalogAdminController {
    private final CreateProductUseCase createProduct;
    private final CreatePriceUseCase createPrice;
    private final PublishPriceUseCase publishPrice;
    private final ListCatalogUseCase catalog;
    private final LoadProductPort products;
    private final LoadSitePort sites;
    private final ProviderCatalogMappingPort mappings;
    private final AdminAuditFacade audit;
    public CatalogAdminController(CreateProductUseCase createProduct, CreatePriceUseCase createPrice,
                                  PublishPriceUseCase publishPrice, ListCatalogUseCase catalog,
                                  LoadProductPort products, LoadSitePort sites, ProviderCatalogMappingPort mappings,
                                  AdminAuditFacade audit) {
        this.createProduct = createProduct; this.createPrice = createPrice; this.publishPrice = publishPrice;
        this.catalog = catalog; this.products = products; this.sites = sites; this.mappings = mappings; this.audit = audit;
    }
    @GetMapping("/sites/{siteId}/products") @Operation(operationId = "listProducts")
    public List<ProductResponse> products(@PathVariable UUID siteId) {
        return catalog.products(new SiteId(siteId)).stream().map(ProductResponse::from).toList();
    }
    @PostMapping("/sites/{siteId}/products") @ResponseStatus(HttpStatus.CREATED) @Transactional
    @Operation(operationId = "createProduct")
    public ProductResponse createProduct(@PathVariable UUID siteId, @Valid @RequestBody CreateProductRequest body,
                                         @AuthenticationPrincipal AuthenticatedAdmin actor, HttpServletRequest request) {
        var product = createProduct.create(new CreateProductCommand(new SiteId(siteId), body.key(), body.name(), body.description()));
        audit.record(AuditAction.PRODUCT_CREATED, "product", product.id().value(), actor, request,
            Map.of("key", product.key().value(), "site_id", siteId.toString()));
        return ProductResponse.from(product);
    }
    @PostMapping("/products/{productId}/prices") @ResponseStatus(HttpStatus.CREATED) @Transactional
    @Operation(operationId = "createPrice")
    public PriceResponse createPrice(@PathVariable UUID productId, @Valid @RequestBody CreatePriceRequest body,
                                     @AuthenticationPrincipal AuthenticatedAdmin actor, HttpServletRequest request) {
        var product = products.findById(new ProductId(productId))
            .orElseThrow(() -> new DomainValidationException("product does not exist"));
        var price = createPrice.create(new CreatePriceCommand(product.siteId(), product.id(), body.priceKey(), body.type(),
            Money.of(body.amountMinor(), body.currency()), body.billingInterval(), body.trialDays(),
            body.trialPaymentMethodPolicy(), body.taxMode(), body.taxCode()));
        audit.record(AuditAction.PRICE_CREATED, "price", price.id().value(), actor, request,
            Map.of("price_key", price.key().value(), "version", Integer.toString(price.version())));
        return PriceResponse.from(price, null);
    }
    @PostMapping("/prices/{priceId}/publish") @Operation(operationId = "publishPrice")
    public PriceResponse publishPrice(@PathVariable UUID priceId, @AuthenticationPrincipal AuthenticatedAdmin actor,
                                      HttpServletRequest request) {
        var price = publishPrice.publish(new PriceId(priceId), new PricePublicationAudit(actor.id().value(),
            dev.genericpayments.platform.shared.adapter.in.web.RequestIdFilter.requestId(request), request.getRemoteAddr()));
        return response(price);
    }
    @GetMapping("/sites/{siteId}/catalog") @Transactional(readOnly = true) @Operation(operationId = "getCatalog")
    public CatalogResponse catalog(@PathVariable UUID siteId) {
        return new CatalogResponse(products(siteId), catalog.prices(new SiteId(siteId)).stream().map(this::response).toList());
    }
    private PriceResponse response(Price price) {
        var site = sites.findById(price.siteId()).orElseThrow(() -> new DomainValidationException("site does not exist"));
        String providerId = mappings.findPrice(site.paymentAccountId(), price.id()).map(ref -> ref.value()).orElse(null);
        return PriceResponse.from(price, providerId);
    }
    public record CatalogResponse(List<ProductResponse> products, List<PriceResponse> prices) {}
}
