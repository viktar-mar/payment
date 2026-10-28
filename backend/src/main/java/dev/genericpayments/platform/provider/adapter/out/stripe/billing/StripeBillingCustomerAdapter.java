package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.provider.application.service.ProviderOperationGuard;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** Customer creation belongs to the Account/Site/Customer, independent of any subscription command. */
@Component
@Transactional(propagation = Propagation.NEVER)
public class StripeBillingCustomerAdapter {
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final ProviderOperationStore operations;
    private final StripeBillingBindingPort bindings;
    private final DigestPort digest;
    private final PaymentAccountVerificationPort verification;
    private final LoadPaymentAccountPort accounts;
    private final LoadSitePort sites;
    private final CustomerStore customers;
    private final ProviderOperationGuard guard = new ProviderOperationGuard();
    public StripeBillingCustomerAdapter(StripeCredentialResolver credentials, StripeClientFactory clients,
            ProviderOperationStore operations, StripeBillingBindingPort bindings, DigestPort digest,
            PaymentAccountVerificationPort verification, LoadPaymentAccountPort accounts,
            LoadSitePort sites, CustomerStore customers) {
        this.credentials = credentials; this.clients = clients; this.operations = operations; this.bindings = bindings;
        this.digest = digest; this.verification = verification; this.accounts = accounts; this.sites = sites; this.customers = customers;
    }
    public CustomerBinding ensureCustomer(PaymentAccount account, SiteId site, CustomerId customer) {
        requireOwner(account, site, customer);
        verification.verify(account);
        String key = key(account, site, customer), fingerprint = fingerprint(account, site, customer);
        var reserved = operations.reserve(account, ProviderOperation.Kind.CUSTOMER_CREATE, customer.value(), key, fingerprint);
        requireOperation(reserved, account, site, customer);
        if (reserved.status() == ProviderOperation.Status.ACKNOWLEDGED) return bind(account, site, customer, reserved);
        String owner = UUID.randomUUID().toString();
        var claimed = operations.claim(reserved.id(), owner, Instant.now(), Duration.ofSeconds(60));
        if (claimed.isEmpty()) {
            var latest = operations.findById(reserved.id()).orElseThrow(StripeBillingCustomerAdapter::unknown);
            requireOperation(latest, account, site, customer);
            if (latest.status() == ProviderOperation.Status.ACKNOWLEDGED) return bind(account, site, customer, latest);
            throw unknown();
        }
        var operation = claimed.get();
        requireOperation(operation, account, site, customer);
        requireOwner(account, site, customer);
        String dispatchKey = guard.requireDispatch(operation, account, fingerprint, Instant.now());
        com.stripe.model.Customer remote;
        try {
            remote = clients.create(credentials.resolve(account)).v1().customers().create(CustomerCreateParams.builder()
                .putAllMetadata(metadata(account, site, customer)).build(),
                RequestOptions.builder().setIdempotencyKey(dispatchKey).build());
        } catch (StripeException unsafe) {
            operations.unknown(operation.id(), owner, operation.version());
            throw unknown();
        }
        try {
            requireCustomer(remote, account, site, customer);
        } catch (PaymentApplicationException invalidResponse) {
            operations.unknown(operation.id(), owner, operation.version());
            throw unknown();
        }
        requireOwner(account, site, customer);
        var acknowledged = operations.acknowledge(operation.id(), owner, operation.version(), remote.getId());
        return bind(account, site, customer, acknowledged);
    }

    /** Trusted recovery entry: exact stored operation and authoritative original creation event; never search by email/metadata. */
    public CustomerBinding recoverCustomer(PaymentAccount account, SiteId site, CustomerId customer,
                                           UUID operationId, String eventId) {
        requireOwner(account, site, customer);
        if (operationId == null || eventId == null || !eventId.matches("evt_[A-Za-z0-9]{1,251}")) throw mismatch();
        var operation = operations.findById(operationId).orElseThrow(StripeBillingCustomerAdapter::mismatch);
        requireOperation(operation, account, site, customer);
        if (operation.firstDispatchedAt() == null || operation.retryBefore() == null
            || operation.status() == ProviderOperation.Status.FAILED
            || operation.leaseExpiresAt() != null && Instant.now().isBefore(operation.leaseExpiresAt())) throw mismatch();
        verification.verify(account);
        String reference;
        try {
            var client = clients.create(credentials.resolve(account));
            var event = client.v1().events().retrieve(eventId);
            if (event == null || !eventId.equals(event.getId()) || !"customer.created".equals(event.getType())
                || !"2026-09-30.endive".equals(event.getApiVersion()) || !Boolean.FALSE.equals(event.getLivemode())
                || event.getAccount() != null || event.getContext() != null || event.getCreated() == null
                || event.getCreated() < operation.firstDispatchedAt().getEpochSecond()
                || event.getCreated() >= operation.retryBefore().getEpochSecond()
                || event.getRequest() == null || !operation.operationKey().equals(event.getRequest().getIdempotencyKey())) throw mismatch();
            var object = event.getDataObjectDeserializer().getObject().orElseThrow(StripeBillingCustomerAdapter::mismatch);
            if (!(object instanceof com.stripe.model.Customer created)) throw mismatch();
            requireCustomer(created, account, site, customer);
            if (created.getCreated() == null || created.getCreated() < operation.firstDispatchedAt().getEpochSecond()
                || created.getCreated() >= operation.retryBefore().getEpochSecond()) throw mismatch();
            reference = created.getId();
            var remote = client.v1().customers().retrieve(reference);
            requireCustomer(remote, account, site, customer);
            if (!reference.equals(remote.getId()) || !Objects.equals(created.getCreated(), remote.getCreated())) throw mismatch();
        } catch (PaymentApplicationException safe) { throw safe; }
        catch (StripeException | RuntimeException unsafe) { throw mismatch(); }
        requireOwner(account, site, customer);
        var acknowledged = operations.bindRecovered(operation, reference, operation.fingerprint());
        return bind(account, site, customer, acknowledged);
    }
    private CustomerBinding bind(PaymentAccount account, SiteId site, CustomerId customer, ProviderOperation operation) {
        requireOwner(account, site, customer);
        requireOperation(operation, account, site, customer);
        if (operation.status() != ProviderOperation.Status.ACKNOWLEDGED || !validCustomerReference(operation.providerReference())) throw mismatch();
        return bindings.bindCustomer(account.id(), site, customer, operation.providerReference(), operation.id());
    }
    private void requireOwner(PaymentAccount account, SiteId siteId, CustomerId customerId) {
        if (account == null || siteId == null || customerId == null) throw conflict();
        var current = accounts.findById(account.id()).orElseThrow(StripeBillingCustomerAdapter::conflict);
        var site = sites.findById(siteId).orElseThrow(StripeBillingCustomerAdapter::conflict);
        var customer = customers.findById(siteId, customerId).orElseThrow(StripeBillingCustomerAdapter::conflict);
        if (!account.equals(current) || current.status() != PaymentAccountStatus.ACTIVE
            || current.provider() != PaymentProviderType.STRIPE || current.mode() != PaymentAccountMode.SANDBOX
            || !siteId.equals(site.id()) || site.status() != SiteStatus.ACTIVE || !account.id().equals(site.paymentAccountId())
            || !customerId.equals(customer.id()) || !siteId.equals(customer.siteId()) || customer.status() != CustomerStatus.ACTIVE) throw conflict();
        credentials.resolve(current);
    }
    private void requireOperation(ProviderOperation op, PaymentAccount a, SiteId s, CustomerId c) {
        if (op.kind() != ProviderOperation.Kind.CUSTOMER_CREATE || !a.id().equals(op.accountId())
            || !a.externalAccountId().equals(op.externalAccountId()) || !a.credentialsRef().equals(op.credentialsRef())
            || !c.value().equals(op.resourceId()) || !key(a,s,c).equals(op.operationKey())
            || !fingerprint(a,s,c).equals(op.fingerprint())) throw conflict();
    }
    private static void requireCustomer(com.stripe.model.Customer remote, PaymentAccount a, SiteId s, CustomerId c) {
        if (remote == null || !"customer".equals(remote.getObject())
            || !validCustomerReference(remote.getId()) || !Boolean.FALSE.equals(remote.getLivemode())
            || Boolean.TRUE.equals(remote.getDeleted()) || remote.getMetadata() == null
            || !remote.getMetadata().entrySet().containsAll(metadata(a,s,c).entrySet())) throw mismatch();
    }
    private static Map<String,String> metadata(PaymentAccount a, SiteId s, CustomerId c) {
        return Map.of("account_id", a.id().value().toString(), "site_id", s.value().toString(), "customer_id", c.value().toString());
    }
    private static String key(PaymentAccount a, SiteId s, CustomerId c) {
        return "billing_customer_" + a.id().value() + "_" + s.value() + "_" + c.value();
    }
    private String fingerprint(PaymentAccount a, SiteId s, CustomerId c) {
        return StripeRequestFingerprint.of(digest, a, "CUSTOMER_CREATE", s.value().toString(), c.value().toString());
    }
    private static boolean validCustomerReference(String ref) { return ref != null && ref.matches("cus_[A-Za-z0-9]{1,251}"); }
    private static PaymentApplicationException conflict() { return new PaymentApplicationException("PROVIDER_OPERATION_CONFLICT",409,"Provider operation conflicts with the request"); }
    private static PaymentApplicationException unknown() { return new PaymentApplicationException("PROVIDER_RESULT_UNKNOWN",409,"Provider result requires recovery"); }
    private static PaymentApplicationException mismatch() { return new PaymentApplicationException("PROVIDER_EVIDENCE_MISMATCH",409,"Provider recovery proof does not match"); }
}
