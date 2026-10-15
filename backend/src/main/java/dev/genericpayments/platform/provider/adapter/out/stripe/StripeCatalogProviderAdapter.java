package dev.genericpayments.platform.provider.adapter.out.stripe;

import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.PriceCreateParams;
import com.stripe.param.ProductCreateParams;
import dev.genericpayments.platform.catalog.application.model.ProviderPriceRef;
import dev.genericpayments.platform.catalog.application.model.ProviderProductRef;
import dev.genericpayments.platform.catalog.application.port.out.CatalogProviderPort;
import dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.service.ProviderOperationGuard;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Every public provider entry verifies the current key before reserving or dispatching work. */
@Transactional(propagation = Propagation.NEVER)
public class StripeCatalogProviderAdapter implements CatalogProviderPort {
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final ProviderOperationStore operations;
    private final ProviderCatalogMappingPort mappings;
    private final DigestPort digest;
    private final PaymentAccountVerificationPort verification;
    private final ProviderOperationGuard guard = new ProviderOperationGuard();

    public StripeCatalogProviderAdapter(StripeCredentialResolver credentials, StripeClientFactory clients,
            ProviderOperationStore operations, ProviderCatalogMappingPort mappings, DigestPort digest,
            PaymentAccountVerificationPort verification) {
        this.credentials = credentials; this.clients = clients; this.operations = operations;
        this.mappings = mappings; this.digest = digest; this.verification = verification;
    }

    @Override public ProviderProductRef ensureProduct(PaymentAccount account, Product product) {
        credentials.resolve(account);
        verification.verify(account);
        return ensureVerifiedProduct(account, product);
    }

    private ProviderProductRef ensureVerifiedProduct(PaymentAccount account, Product product) {
        String key = "catalog_product_" + account.id().value() + "_" + product.id().value();
        String fingerprint = productFingerprint(account,product);
        return new ProviderProductRef(dispatch(account, ProviderOperation.Kind.PRODUCT_CREATE, product.id().value(),
            key, fingerprint, options -> {
                var params = ProductCreateParams.builder().setName(product.name())
                    .putMetadata("local_product_id", product.id().value().toString());
                if (product.description() != null && !product.description().isBlank()) params.setDescription(product.description());
                var result = clients.create(credentials.resolve(account)).v1().products().create(params.build(), options);
                return reference(result.getId(), result.getLivemode(), "prod_");
            }));
    }

    @Override public ProviderPriceRef createPrice(PaymentAccount account, Product product, Price price) {
        credentials.resolve(account);
        verification.verify(account);
        if (price.taxMode() == TaxMode.EXCLUSIVE || !price.productId().equals(product.id())
                || !price.siteId().equals(product.siteId())) {
            throw new PaymentApplicationException("PAYMENT_CAPABILITY_UNAVAILABLE", 422, "Payment capability unavailable");
        }
        var productRef = mappings.findProduct(account.id(), product.id()).orElseGet(() -> ensureVerifiedProduct(account, product));
        var params = PriceCreateParams.builder().setProduct(productRef.value())
            .setUnitAmount(price.money().minorUnits()).setCurrency(price.money().currency().toLowerCase(Locale.ROOT))
            // NONE also fixes the displayed total; automatic tax is never enabled.
            .setTaxBehavior(PriceCreateParams.TaxBehavior.INCLUSIVE)
            .putMetadata("local_price_id", price.id().value().toString())
            .putMetadata("local_price_version", Integer.toString(price.version()));
        if (price.type() == PriceType.SUBSCRIPTION) {
            var recurring = PriceCreateParams.Recurring.builder()
                .setInterval(price.billingInterval() == BillingInterval.MONTH
                    ? PriceCreateParams.Recurring.Interval.MONTH : PriceCreateParams.Recurring.Interval.YEAR)
                .setIntervalCount(1L);
            if (price.trialDays() > 0) recurring.setTrialPeriodDays((long) price.trialDays());
            params.setRecurring(recurring.build());
        }
        String key = "catalog_price_" + account.id().value() + "_" + price.id().value() + "_v" + price.version();
        String fingerprint = priceFingerprint(account,product,price,productRef.value());
        return new ProviderPriceRef(dispatch(account, ProviderOperation.Kind.PRICE_CREATE, price.id().value(),
            key, fingerprint, options -> {
                var result = clients.create(credentials.resolve(account)).v1().prices().create(params.build(), options);
                return reference(result.getId(), result.getLivemode(), "price_");
            }));
    }

    @Transactional(propagation=Propagation.SUPPORTS,readOnly=true)
    String productFingerprint(PaymentAccount account,Product product) {
        return fingerprint(account,product.id().value().toString(),product.siteId().value().toString(),product.key().value(),product.name(),product.description());
    }
    @Transactional(propagation=Propagation.SUPPORTS,readOnly=true)
    String priceFingerprint(PaymentAccount account,Product product,Price price,String productReference) {
        return fingerprint(account, price.id().value().toString(), price.siteId().value().toString(),
            product.id().value().toString(), productReference, price.key().value(), Integer.toString(price.version()),
            price.type().name(), Long.toString(price.money().minorUnits()), price.money().currency(),
            price.billingInterval() == null ? null : price.billingInterval().name(), Integer.toString(price.trialDays()),
            price.trialPaymentMethodPolicy().name(), price.taxMode().name(), price.taxCode());
    }

    private String dispatch(PaymentAccount account, ProviderOperation.Kind kind, UUID resource, String key,
                            String fingerprint, Creation request) {
        var reserved = operations.reserve(account, kind, resource, key, fingerprint);
        if (reserved.status() == ProviderOperation.Status.ACKNOWLEDGED) return reserved.providerReference();
        String owner = UUID.randomUUID().toString();
        var claimed = operations.claim(reserved.id(), owner, Instant.now(), Duration.ofSeconds(60));
        if (claimed.isEmpty()) {
            var latest = operations.find(account.id(), key).orElseThrow(StripeCatalogProviderAdapter::unknown);
            if (latest.status() == ProviderOperation.Status.ACKNOWLEDGED) return latest.providerReference();
            throw unknown();
        }
        var operation = claimed.get();
        String operationKey = guard.requireDispatch(operation, account, fingerprint, Instant.now());
        String reference;
        try {
            reference = request.create(RequestOptions.builder().setIdempotencyKey(operationKey).build());
        } catch (StripeException failure) {
            // SDK failures may contain raw bodies or credentials. Neither retain nor log them.
            operations.unknown(operation.id(), owner, operation.version());
            throw unknown();
        }
        if (reference == null) {
            operations.unknown(operation.id(), owner, operation.version());
            throw unknown();
        }
        return operations.acknowledge(operation.id(), owner, operation.version(), reference).providerReference();
    }

    private String fingerprint(PaymentAccount account, String... facts) {
        return StripeRequestFingerprint.of(digest, account, facts);
    }

    private static String reference(String id, Boolean live, String prefix) {
        return Boolean.FALSE.equals(live) && id != null && id.matches(prefix + "[A-Za-z0-9_]{1,200}") ? id : null;
    }

    private static PaymentApplicationException unknown() {
        return new PaymentApplicationException("PROVIDER_RESULT_UNKNOWN", 409, "Provider result requires recovery");
    }

    @FunctionalInterface private interface Creation {
        String create(RequestOptions options) throws StripeException;
    }
}
