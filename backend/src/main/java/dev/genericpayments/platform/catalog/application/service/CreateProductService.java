package dev.genericpayments.platform.catalog.application.service;

import dev.genericpayments.platform.catalog.application.port.in.CreateProductCommand;
import dev.genericpayments.platform.catalog.application.port.in.CreateProductUseCase;
import dev.genericpayments.platform.catalog.application.port.out.SaveProductPort;
import dev.genericpayments.platform.catalog.domain.Product;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.catalog.domain.ProductKey;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.SiteStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class CreateProductService implements CreateProductUseCase {
    private final LoadSitePort loadSites;
    private final SaveProductPort saveProducts;
    private final Clock clock;

    public CreateProductService(LoadSitePort loadSites, SaveProductPort saveProducts, Clock clock) {
        this.loadSites = Objects.requireNonNull(loadSites);
        this.saveProducts = Objects.requireNonNull(saveProducts);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Product create(CreateProductCommand command) {
        Objects.requireNonNull(command, "command");
        var site = loadSites.findById(command.siteId())
            .orElseThrow(() -> new DomainValidationException("site does not exist"));
        if (site.status() != SiteStatus.ACTIVE) {
            throw new DomainValidationException("site is not active");
        }
        Instant timestamp = clock.instant();
        Product product = Product.create(new ProductId(UUID.randomUUID()), command.siteId(),
            ProductKey.of(command.key()), command.name(), command.description(), timestamp);
        return saveProducts.save(product);
    }
}
