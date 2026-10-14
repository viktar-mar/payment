package dev.genericpayments.platform.catalog.application.service;

import dev.genericpayments.platform.catalog.application.port.in.CreatePriceCommand;
import dev.genericpayments.platform.catalog.application.port.in.CreatePriceUseCase;
import dev.genericpayments.platform.catalog.application.port.out.LoadPricePort;
import dev.genericpayments.platform.catalog.application.port.out.LoadProductPort;
import dev.genericpayments.platform.catalog.application.port.out.SavePricePort;
import dev.genericpayments.platform.catalog.domain.Price;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.PriceKey;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class CreatePriceService implements CreatePriceUseCase {
    private final LoadProductPort loadProducts;
    private final LoadPricePort loadPrices;
    private final SavePricePort savePrices;
    private final Clock clock;

    public CreatePriceService(LoadProductPort loadProducts, LoadPricePort loadPrices,
                              SavePricePort savePrices, Clock clock) {
        this.loadProducts = Objects.requireNonNull(loadProducts);
        this.loadPrices = Objects.requireNonNull(loadPrices);
        this.savePrices = Objects.requireNonNull(savePrices);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public Price create(CreatePriceCommand command) {
        Objects.requireNonNull(command, "command");
        var product = loadProducts.findById(command.productId())
            .orElseThrow(() -> new DomainValidationException("product does not exist"));
        if (!product.siteId().equals(command.siteId())) {
            throw new DomainValidationException("product does not belong to site");
        }
        PriceKey key = PriceKey.of(command.key());
        int version = loadPrices.nextVersion(command.siteId(), key);
        Instant timestamp = clock.instant();
        Price price = Price.draft(new PriceId(UUID.randomUUID()), command.siteId(), command.productId(), key,
            version, command.type(), command.money(), command.billingInterval(), command.trialDays(),
            command.trialPaymentMethodPolicy(), command.taxMode(), command.taxCode(), timestamp);
        return savePrices.save(price);
    }
}
