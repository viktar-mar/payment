package dev.genericpayments.platform.provider.adapter.out.stripe;

import dev.genericpayments.platform.catalog.application.port.out.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.model.RecoveryRequestSnapshot.*;
import dev.genericpayments.platform.provider.application.port.out.*;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Component
@Transactional(propagation = Propagation.NEVER)
public class StripeRecoveryProofAdapter implements ProviderRecoveryProofPort {
    private final LoadPaymentAccountPort accounts;
    private final LoadPaymentPort payments;
    private final LoadRefundPort refunds;
    private final LoadRefundOwnershipPort ownership;
    private final LoadProductPort products;
    private final LoadPricePort prices;
    private final ProviderOperationStore operations;
    private final StripePaymentProviderAdapter provider;
    private final StripeCatalogProviderAdapter catalog;
    private final StripeInboxResolutionAdapter resolution;
    private final StripeCredentialResolver credentials;
    private final PaymentAccountVerificationPort verification;
    private final StripeClientFactory clients;

    public StripeRecoveryProofAdapter(
            LoadPaymentAccountPort accounts,
            LoadPaymentPort payments,
            LoadRefundPort refunds,
            LoadRefundOwnershipPort ownership,
            LoadProductPort products,
            LoadPricePort prices,
            ProviderOperationStore operations,
            StripePaymentProviderAdapter provider,
            StripeCatalogProviderAdapter catalog,
            StripeInboxResolutionAdapter resolution,
            StripeCredentialResolver credentials,
            PaymentAccountVerificationPort verification,
            StripeClientFactory clients) {
        this.accounts = accounts;
        this.payments = payments;
        this.refunds = refunds;
        this.ownership = ownership;
        this.products = products;
        this.prices = prices;
        this.operations = operations;
        this.provider = provider;
        this.catalog = catalog;
        this.resolution = resolution;
        this.credentials = credentials;
        this.verification = verification;
        this.clients = clients;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public RecoveryRequestSnapshot request(ProviderOperation operation) {
        var account = accounts.findById(operation.accountId())
                .orElseThrow(StripeRecoveryProofAdapter::mismatch);
        credentials.resolve(account);
        if (account.status() != PaymentAccountStatus.ACTIVE
                || !account.externalAccountId().equals(operation.externalAccountId())
                || !account.credentialsRef().equals(operation.credentialsRef())) throw mismatch();
        RecoveryRequestSnapshot snapshot;
        String key;
        String fingerprint;
        switch (operation.kind()) {
            case PAYMENT_PREPARE -> {
                var p = payments.findByAccountAndId(
                                account.id(), new PaymentId(operation.resourceId()))
                        .orElseThrow(StripeRecoveryProofAdapter::mismatch);
                if (operation.status() == ProviderOperation.Status.ACKNOWLEDGED
                        && p.providerPaymentReference() != null
                        && !p.providerPaymentReference().equals(operation.providerReference()))
                    throw mismatch();
                snapshot = new PaymentRequest(account, p);
                key = ProviderOperationKeys.prepare(p.id());
                fingerprint = provider.fingerprint(account, p);
            }
            case REFUND_CREATE -> {
                var id = new RefundId(operation.resourceId());
                var owner = ownership
                        .findOwnership(id)
                        .orElseThrow(StripeRecoveryProofAdapter::mismatch);
                if (!owner.paymentAccountId().equals(account.id())) throw mismatch();
                var p = payments.findByAccountAndId(account.id(), owner.paymentId())
                        .orElseThrow(StripeRecoveryProofAdapter::mismatch);
                var r = refunds.findById(owner.siteId(), id)
                        .orElseThrow(StripeRecoveryProofAdapter::mismatch)
                        .value();
                if (operation.status() == ProviderOperation.Status.ACKNOWLEDGED
                        && r.providerRefundReference() != null
                        && !r.providerRefundReference().equals(operation.providerReference()))
                    throw mismatch();
                snapshot = new RefundRequest(account, p, r);
                key = ProviderOperationKeys.refund(id);
                fingerprint = provider.refundFingerprint(account, p, r);
            }
            case PRODUCT_CREATE -> {
                var p = products.findById(new ProductId(operation.resourceId()))
                        .orElseThrow(StripeRecoveryProofAdapter::mismatch);
                snapshot = new ProductRequest(account, p);
                key = "catalog_product_" + account.id().value() + "_" + p.id().value();
                fingerprint = catalog.productFingerprint(account, p);
            }
            case PRICE_CREATE -> {
                var price = prices.findById(new PriceId(operation.resourceId()))
                        .orElseThrow(StripeRecoveryProofAdapter::mismatch);
                var product = products.findById(price.productId())
                        .orElseThrow(StripeRecoveryProofAdapter::mismatch);
                var productOp = operations
                        .find(
                                account.id(),
                                "catalog_product_" + account.id().value() + "_"
                                        + product.id().value())
                        .orElseThrow(StripeRecoveryProofAdapter::mismatch);
                if (productOp.status() != ProviderOperation.Status.ACKNOWLEDGED
                        || productOp.kind() != ProviderOperation.Kind.PRODUCT_CREATE
                        || !productOp.resourceId().equals(product.id().value())
                        || !catalog.productFingerprint(account, product)
                                .equals(productOp.fingerprint())
                        || !productOp.externalAccountId().equals(account.externalAccountId())
                        || !productOp.credentialsRef().equals(account.credentialsRef()))
                    throw mismatch();
                snapshot = new PriceRequest(account, product, price, productOp.providerReference());
                key = "catalog_price_" + account.id().value() + "_" + price.id().value() + "_v"
                        + price.version();
                fingerprint = catalog.priceFingerprint(
                        account, product, price, productOp.providerReference());
            }
            default -> throw mismatch();
        }
        if (!key.equals(operation.operationKey()) || !fingerprint.equals(operation.fingerprint()))
            throw mismatch();
        return snapshot;
    }

    @Override
    public void replay(ProviderOperation operation) {
        var request = request(operation);
        switch (request) {
            case PaymentRequest r ->
                provider.preparePayment(new ProviderPaymentRequest(
                        r.account(), r.payment(), operation.operationKey()));
            case RefundRequest r ->
                provider.requestRefund(new ProviderRefundRequest(
                        r.account(), r.payment(), r.refund(), operation.operationKey()));
            case ProductRequest r -> catalog.ensureProduct(r.account(), r.product());
            case PriceRequest r -> catalog.createPrice(r.account(), r.product(), r.price());
        }
    }

    @Override
    public ResolvedProviderRecovery retrieve(ProviderOperation operation) {
        request(operation);
        return resolution.resolveRecovery(operation);
    }

    @Override
    public RecoveredProviderProof verify(
            ProviderOperation operation, String reference, String eventId) {
        var snapshot = request(operation);
        var account = snapshot.account();
        var binding = credentials.resolve(account);
        String prefix =
                switch (operation.kind()) {
                    case PAYMENT_PREPARE -> "pi_";
                    case REFUND_CREATE -> "re_";
                    case PRODUCT_CREATE -> "prod_";
                    case PRICE_CREATE -> "price_";
                };
        if (!StripePaymentSnapshotMapper.validReference(reference, prefix)
                || !StripePaymentSnapshotMapper.validReference(eventId, "evt_")) throw mismatch();
        verification.verify(account);
        try {
            var client = clients.create(binding);
            var event = client.v1().events().retrieve(eventId);
            String type =
                    switch (operation.kind()) {
                        case PAYMENT_PREPARE -> "payment_intent.created";
                        case REFUND_CREATE -> "refund.created";
                        case PRODUCT_CREATE -> "product.created";
                        case PRICE_CREATE -> "price.created";
                    };
            if (event == null
                    || !eventId.equals(event.getId())
                    || !Boolean.FALSE.equals(event.getLivemode())
                    || !type.equals(event.getType())
                    || operation.firstDispatchedAt() == null
                    || operation.retryBefore() == null
                    || event.getCreated() == null
                    || event.getCreated() < operation.firstDispatchedAt().getEpochSecond()
                    || event.getCreated() >= operation.retryBefore().getEpochSecond()
                    || event.getAccount() != null
                    || event.getContext() != null
                    || event.getRequest() == null
                    || !operation.operationKey().equals(event.getRequest().getIdempotencyKey()))
                throw mismatch();
            var object = event.getDataObjectDeserializer()
                    .getObject()
                    .orElseThrow(StripeRecoveryProofAdapter::mismatch);
            if (!(object instanceof com.stripe.model.HasId identified)
                    || !reference.equals(identified.getId())) throw mismatch();
            boolean kind =
                    switch (operation.kind()) {
                        case PAYMENT_PREPARE -> object instanceof com.stripe.model.PaymentIntent;
                        case REFUND_CREATE -> object instanceof com.stripe.model.Refund;
                        case PRODUCT_CREATE -> object instanceof com.stripe.model.Product;
                        case PRICE_CREATE -> object instanceof com.stripe.model.Price;
                    };
            if (!kind) throw mismatch();
            switch (snapshot) {
                case PaymentRequest r -> {
                    var remote = client.v1().paymentIntents().retrieve(reference);
                    var bound = r.payment()
                            .withProviderPaymentReference(reference, r.payment().updatedAt());
                    StripePaymentSnapshotMapper.requireIntent(account, bound, remote);
                    if (remote.getMetadata() == null
                            || !r.payment()
                                    .id()
                                    .value()
                                    .toString()
                                    .equals(remote.getMetadata().get("payment_id"))
                            || !r.siteId()
                                    .value()
                                    .toString()
                                    .equals(remote.getMetadata().get("site_id"))
                            || !r.payment()
                                    .source()
                                    .name()
                                    .equals(remote.getMetadata().get("source"))) throw mismatch();
                }
                case RefundRequest r -> {
                    var remote = client.v1().refunds().retrieve(reference);
                    if (remote == null || !reference.equals(remote.getId())) throw mismatch();
                    var intent = client.v1()
                            .paymentIntents()
                            .retrieve(r.payment().providerPaymentReference());
                    StripePaymentSnapshotMapper.requireIntent(account, r.payment(), intent);
                    if (intent.getLatestCharge() == null
                            || !intent.getLatestCharge().equals(remote.getCharge()))
                        throw mismatch();
                    var charge = client.v1().charges().retrieve(intent.getLatestCharge());
                    StripeRefundSnapshotMapper.requireParent(account, r.payment(), intent, charge);
                    StripeRefundSnapshotMapper.requireRefund(
                            r.payment(), r.refund(), remote, charge.getId());
                    String reason =
                            switch (Objects.toString(r.refund().reasonCode(), "")) {
                                case "REQUESTED_BY_CUSTOMER" -> "requested_by_customer";
                                case "DUPLICATE" -> "duplicate";
                                case "FRAUDULENT" -> "fraudulent";
                                default -> null;
                            };
                    if (!Objects.equals(reason, remote.getReason())) throw mismatch();
                }
                case ProductRequest r -> {
                    var remote = client.v1().products().retrieve(reference);
                    var description = r.product().description();
                    if (description != null && description.isBlank()) description = null;
                    if (remote == null
                            || !reference.equals(remote.getId())
                            || !Boolean.FALSE.equals(remote.getLivemode())
                            || !r.product().name().equals(remote.getName())
                            || !Objects.equals(description, remote.getDescription())
                            || remote.getMetadata() == null
                            || !r.product()
                                    .id()
                                    .value()
                                    .toString()
                                    .equals(remote.getMetadata().get("local_product_id")))
                        throw mismatch();
                }
                case PriceRequest r -> {
                    var remote = client.v1().prices().retrieve(reference);
                    var price = r.price();
                    if (remote == null
                            || !reference.equals(remote.getId())
                            || !Boolean.FALSE.equals(remote.getLivemode())
                            || !r.providerProductReference().equals(remote.getProduct())
                            || !Objects.equals(price.money().minorUnits(), remote.getUnitAmount())
                            || !price.money()
                                    .currency()
                                    .toLowerCase(Locale.ROOT)
                                    .equals(remote.getCurrency())
                            || !"inclusive".equals(remote.getTaxBehavior())
                            || remote.getMetadata() == null
                            || !price.id()
                                    .value()
                                    .toString()
                                    .equals(remote.getMetadata().get("local_price_id"))
                            || !Integer.toString(price.version())
                                    .equals(remote.getMetadata().get("local_price_version")))
                        throw mismatch();
                    if (price.type() == PriceType.ONE_TIME) {
                        if (!"one_time".equals(remote.getType()) || remote.getRecurring() != null)
                            throw mismatch();
                    } else {
                        var recurring = remote.getRecurring();
                        if (!"recurring".equals(remote.getType())
                                || recurring == null
                                || !Objects.equals(1L, recurring.getIntervalCount())
                                || !(price.billingInterval() == BillingInterval.MONTH
                                                ? "month"
                                                : "year")
                                        .equals(recurring.getInterval())
                                || !Objects.equals(
                                        price.trialDays() == 0 ? null : (long) price.trialDays(),
                                        recurring.getTrialPeriodDays())) throw mismatch();
                    }
                }
            }
            return new RecoveredProviderProof(
                    operation.id(),
                    account.id(),
                    reference,
                    operation.fingerprint(),
                    eventId,
                    snapshot);
        } catch (dev.genericpayments.platform.shared.application.PaymentApplicationException safe) {
            throw safe;
        } catch (Exception unsafe) {
            throw mismatch();
        }
    }

    private static RuntimeException mismatch() {
        return new dev.genericpayments.platform.shared.application.PaymentApplicationException(
                "PROVIDER_EVIDENCE_MISMATCH", 409, "Provider recovery proof does not match");
    }
}
