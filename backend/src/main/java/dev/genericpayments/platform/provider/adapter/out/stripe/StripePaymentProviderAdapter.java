package dev.genericpayments.platform.provider.adapter.out.stripe;

import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.Payment;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.service.ProviderOperationGuard;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.*;

/** A committed receipt pins every preparation to its original account and immutable request. */
@Transactional(propagation = Propagation.NEVER)
public class StripePaymentProviderAdapter implements PaymentProviderPort {
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final PaymentAccountVerificationPort verification;
    private final ProviderOperationStore operations;
    private final DigestPort digest;
    private final LoadSitePort sites;
    private final LoadPaymentPort payments;
    private final ProviderOperationGuard guard = new ProviderOperationGuard();

    public StripePaymentProviderAdapter(StripeCredentialResolver credentials, StripeClientFactory clients,
            PaymentAccountVerificationPort verification, ProviderOperationStore operations, DigestPort digest,
            LoadSitePort sites, LoadPaymentPort payments) {
        this.credentials = credentials; this.clients = clients; this.verification = verification;
        this.operations = operations; this.digest = digest; this.sites = sites; this.payments = payments;
    }

    @Override public PaymentProviderCapabilities capabilities(PaymentAccount account) {
        credentials.resolve(account);
        return new PaymentProviderCapabilities(Set.of(TaxMode.NONE, TaxMode.INCLUSIVE), Set.of(PaymentMethod.CARD),
            PaymentProviderCapabilities.ConfirmationMode.CLIENT_PROVIDER);
    }

    @Override public ProviderResult preparePayment(ProviderPaymentRequest request) {
        var account = request.account();
        var binding = credentials.resolve(account);
        verification.verify(account);
        var payment = payments.findById(request.payment().siteId(), request.payment().id()).orElseThrow(StripePaymentProviderAdapter::conflict);
        String fingerprint = fingerprint(account, payment);
        if (!payment.paymentAccountId().equals(account.id())
                || !fingerprint.equals(fingerprint(account, request.payment()))) throw conflict();
        var params = StripePaymentRequests.prepare(payment);
        // Reuse an acknowledged original receipt before looking at today's Site policy/routing.
        var existing = operations.find(account.id(), request.operationKey());
        if (existing.filter(op -> op.status() == ProviderOperation.Status.ACKNOWLEDGED).isPresent()) {
            return accepted(reserve(request, fingerprint), payment);
        }
        // A prior local binding without acknowledged proof requires recovery, never another create.
        if (payment.providerPaymentReference() != null) throw conflict();
        requireCardPolicy(payment);
        var reserved = reserve(request, fingerprint);
        if (reserved.status() == ProviderOperation.Status.ACKNOWLEDGED) return accepted(reserved, payment);
        String owner = UUID.randomUUID().toString();
        var claim = operations.claim(reserved.id(), owner, Instant.now(), Duration.ofSeconds(60));
        if (claim.isEmpty()) {
            var latest = operations.find(account.id(), request.operationKey()).orElseThrow(StripePaymentProviderAdapter::conflict);
            return latest.status() == ProviderOperation.Status.ACKNOWLEDGED ? accepted(latest, payment) : new ProviderResult.Unknown(null);
        }
        var operation = claim.get();
        String key = guard.requireDispatch(operation, account, fingerprint, Instant.now());
        String reference;
        try {
            var intent = clients.create(binding).v1().paymentIntents().create(params,
                RequestOptions.builder().setIdempotencyKey(key).build());
            reference = Boolean.FALSE.equals(intent.getLivemode()) && validReference(intent.getId()) ? intent.getId() : null;
        } catch (StripeException failure) {
            operations.unknown(operation.id(), owner, operation.version());
            return StripePaymentErrorMapper.map(failure);
        }
        if (reference == null) {
            operations.unknown(operation.id(), owner, operation.version());
            return new ProviderResult.Unknown(null);
        }
        return accepted(operations.acknowledge(operation.id(), owner, operation.version(), reference), payment);
    }

    private ProviderOperation reserve(ProviderPaymentRequest request, String fingerprint) {
        return operations.reserve(request.account(), ProviderOperation.Kind.PAYMENT_PREPARE,
            request.payment().id().value(), request.operationKey(), fingerprint);
    }

    /** Stable handoff for recovery: account prefix, then payment/site/source/amount/currency/tax. */
    String fingerprint(PaymentAccount account, Payment payment) {
        return StripeRequestFingerprint.of(digest, account, payment.id().value().toString(), payment.siteId().value().toString(),
            payment.source().name(), Long.toString(payment.money().minorUnits()), payment.money().currency(), payment.taxMode().name());
    }

    private void requireCardPolicy(Payment payment) {
        var site = sites.findById(payment.siteId()).orElseThrow(StripePaymentRequests::unavailable);
        if (site.status() != SiteStatus.ACTIVE || site.paymentMethodPolicy().mode() == PaymentMethodPolicyMode.ALLOWLIST
                && !site.paymentMethodPolicy().allowedMethods().contains(PaymentMethod.CARD)) throw StripePaymentRequests.unavailable();
    }

    private static ProviderResult accepted(ProviderOperation operation, Payment payment) {
        if (!validReference(operation.providerReference()) || payment.providerPaymentReference() != null
                && !payment.providerPaymentReference().equals(operation.providerReference())) throw conflict();
        return new ProviderResult.Accepted(operation.providerReference(), false);
    }

    private static boolean validReference(String reference) {
        return reference != null && reference.matches("pi_[A-Za-z0-9_]{1,200}");
    }

    private static PaymentApplicationException conflict() {
        return new PaymentApplicationException("PROVIDER_OPERATION_CONFLICT", 409, "Provider operation conflicts with the request");
    }

    @Override public ProviderResult confirmPayment(ProviderConfirmationRequest request) { throw StripePaymentRequests.unavailable(); }
    @Override public ProviderResult requestRefund(ProviderRefundRequest request) { throw StripePaymentRequests.unavailable(); }
    @Override public ProviderState retrievePayment(ProviderPaymentLookup lookup) { throw StripePaymentRequests.unavailable(); }
    @Override public ProviderState retrieveRefund(ProviderRefundLookup lookup) { throw StripePaymentRequests.unavailable(); }
}
