package dev.genericpayments.platform.catalog.application.service;

import dev.genericpayments.platform.catalog.application.port.in.*;
import dev.genericpayments.platform.catalog.application.port.out.*;
import dev.genericpayments.platform.catalog.domain.Price;
import dev.genericpayments.platform.catalog.domain.PriceId;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reject an ambient transaction: even a suspended caller could retain financial row locks. */
@Transactional(propagation = Propagation.NEVER)
public class PublishPriceService implements PublishPriceUseCase {
    private final PricePublicationTransaction transaction;
    private final CatalogProviderRegistry providers;
    private final ProviderCatalogMappingPort mappings;

    public PublishPriceService(PricePublicationTransaction transaction, CatalogProviderRegistry providers,
                               ProviderCatalogMappingPort mappings) {
        this.transaction = transaction; this.providers = providers; this.mappings = mappings;
    }

    @Override public Price publish(PriceId id) { return publish(id, null); }

    @Override public Price publish(PriceId id, PricePublicationAudit audit) {
        var publication = transaction.prepare(id);
        var account = publication.account();
        var provider = providers.select(account);
        var productRef = mappings.findProduct(account.id(), publication.product().id())
            .orElseGet(() -> provider.ensureProduct(account, publication.product()));
        var priceRef = mappings.findPrice(account.id(), id)
            .orElseGet(() -> provider.createPrice(account, publication.product(), publication.draft()));
        return transaction.complete(publication, productRef, priceRef, audit);
    }
}
