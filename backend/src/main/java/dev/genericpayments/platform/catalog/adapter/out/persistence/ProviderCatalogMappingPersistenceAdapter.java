package dev.genericpayments.platform.catalog.adapter.out.persistence;

import dev.genericpayments.platform.catalog.application.model.ProviderCatalogMapping;
import dev.genericpayments.platform.catalog.application.model.ProviderPriceRef;
import dev.genericpayments.platform.catalog.application.model.ProviderProductRef;
import dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort;
import dev.genericpayments.platform.catalog.application.port.out.CatalogPublicationLockPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.ProductId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ProviderCatalogMappingPersistenceAdapter implements ProviderCatalogMappingPort, CatalogPublicationLockPort {
    @PersistenceContext
    private EntityManager entityManager;
    private final JdbcTemplate jdbc;

    public ProviderCatalogMappingPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Versions versions(PaymentAccountId account, SiteId site, ProductId product, PriceId price) {
        var rows = jdbc.query("""
            select a.version,s.version,p.version,r.version from payment_accounts a
            join sites s on s.payment_account_id=a.id
            join products p on p.site_id=s.id
            join prices r on r.product_id=p.id and r.site_id=s.id
            where a.id=? and s.id=? and p.id=? and r.id=?
            """, (row, index) -> new Versions(row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4)),
            account.value(), site.value(), product.value(), price.value());
        if (rows.size() != 1) throw conflict();
        return rows.getFirst();
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Versions lock(PaymentAccountId account, SiteId site, ProductId product, PriceId price) {
        // Deliberately separate statements: the database must acquire these locks in this order.
        lockRow("payment_accounts", account.value());
        lockRow("sites", site.value());
        lockRow("products", product.value());
        lockRow("prices", price.value());
        return versions(account, site, product, price);
    }

    private void lockRow(String table, java.util.UUID id) {
        if (jdbc.queryForList("select id from " + table + " where id=? for update", java.util.UUID.class, id).size() != 1) throw conflict();
    }

    @Override
    public Optional<ProviderProductRef> findProduct(PaymentAccountId accountId, ProductId productId) {
        return entityManager.createQuery("""
                select mapping from ProviderProductMappingJpaEntity mapping
                where mapping.paymentAccountId = :accountId and mapping.productId = :productId
                """, ProviderProductMappingJpaEntity.class)
            .setParameter("accountId", accountId.value())
            .setParameter("productId", productId.value())
            .getResultList()
            .stream()
            .findFirst()
            .map(ProviderProductMappingJpaEntity::toReference);
    }

    @Override
    public Optional<ProviderPriceRef> findPrice(PaymentAccountId accountId, PriceId priceId) {
        return entityManager.createQuery("""
                select mapping from ProviderPriceMappingJpaEntity mapping
                where mapping.paymentAccountId = :accountId and mapping.priceId = :priceId
                """, ProviderPriceMappingJpaEntity.class)
            .setParameter("accountId", accountId.value())
            .setParameter("priceId", priceId.value())
            .getResultList()
            .stream()
            .findFirst()
            .map(ProviderPriceMappingJpaEntity::toReference);
    }

    @Override
    @Transactional(readOnly=true)
    public List<ProviderCatalogMapping> findPricesForProduct(PaymentAccountId accountId,SiteId siteId,ProductId productId) {
        if(accountId==null||siteId==null||productId==null)throw mappingUnavailable();
        // Include immutable archived versions. Today's Site Account must not relocate old subscriptions.
        var rows=jdbc.query("""
            select p.id,pm.provider_price_id,product.provider_product_id,pm.created_at
            from provider_price_mappings pm join prices p on p.id=pm.price_id
            join products owned on owned.id=p.product_id and owned.site_id=p.site_id
            join provider_product_mappings product on product.payment_account_id=pm.payment_account_id and product.product_id=owned.id
            where pm.payment_account_id=? and p.site_id=? and p.product_id=?
              and p.price_type='SUBSCRIPTION' and p.published_at is not null and p.status in ('PUBLISHED','ARCHIVED')
            order by p.price_version,p.id limit 101
            """,(r,n)->new ProviderCatalogMapping(accountId,productId,new ProviderProductRef(r.getString("provider_product_id")),
                new PriceId(r.getObject("id",java.util.UUID.class)),new ProviderPriceRef(r.getString("provider_price_id")),r.getTimestamp("created_at").toInstant()),
            accountId.value(),siteId.value(),productId.value());
        if(rows.size()>100)throw mappingUnavailable();
        return List.copyOf(rows);
    }

    private static PaymentApplicationException mappingUnavailable(){return new PaymentApplicationException("PROVIDER_EVIDENCE_MISMATCH",409,"Provider catalog evidence unavailable");}

    @Override
    @Transactional
    public ProviderCatalogMapping save(ProviderCatalogMapping mapping) {
        var product = findProduct(mapping.paymentAccountId(), mapping.productId());
        var price = findPrice(mapping.paymentAccountId(), mapping.priceId());
        if (product.filter(ref -> !ref.equals(mapping.productRef())).isPresent()
                || price.filter(ref -> !ref.equals(mapping.priceRef())).isPresent()) throw conflict();
        if (product.isEmpty()) {
            entityManager.persist(new ProviderProductMappingJpaEntity(mapping.paymentAccountId(), mapping.productId(),
                mapping.productRef(), mapping.createdAt()));
        }
        if (price.isEmpty()) {
            entityManager.persist(new ProviderPriceMappingJpaEntity(mapping.paymentAccountId(), mapping.priceId(),
                mapping.priceRef(), mapping.createdAt()));
        }
        return mapping;
    }

    private static PaymentApplicationException conflict() {
        return new PaymentApplicationException("PRICE_PUBLICATION_CONFLICT", 409, "Price publication conflicts with current catalog");
    }
}
