package dev.genericpayments.platform.support;

import com.stripe.StripeClient;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.service.ProviderTestFixture;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.Command;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.webhooks.application.port.out.SubscriptionInboxResolutionPort;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Hosted-only orchestration. No main, runtime switch, control route or production registration. */
@Import(StripeBillingClockCustomerFixture.InertConfiguration.class)
public abstract class StripeBillingClockCustomerFixture extends ProviderTestFixture {
    protected static final long FROZEN = 1700000000L;
    protected static final long PROVIDER_CREATED = 1800000000L;
    protected static final long PROVIDER_DELETES = 1801000000L;
    @Autowired protected StripeBillingClockStore clocks;
    @Autowired protected ProviderOperationStore operations;
    @Autowired protected StripeBillingBindingPort bindings;
    @Autowired protected StripeBillingCustomerAdapter customers;
    @Autowired protected StripeBillingTestClockAdapter clockAdapter;
    @Autowired protected StripeBillingOwnership ownership;
    @Autowired protected StripeSubscriptionProviderAdapter provider;
    @Autowired protected StripeBillingActionAdapter actions;
    @Autowired protected SubscriptionInboxResolutionPort inbox;
    @Autowired protected SubscriptionStore subscriptions;
    @Autowired protected SubscriptionOperationStore lifecycleOperations;
    @Autowired protected SubscriptionObjectPort objects;
    @Autowired protected SubscriptionTransactionPort transaction;
    @Autowired protected LoadPaymentAccountPort accounts;
    @Autowired protected DigestPort digest;
    @Autowired protected StripeBillingInertSdkFixture.Transport http;
    @Autowired protected Verification verification;
    protected final CustomerId customer = new CustomerId(UUID.randomUUID());
    protected PaymentAccount billing;

    @BeforeEach void prepareClockCustomer() {
        http.calls.clear(); http.replies.clear(); http.afterResponse = null; verification.accounts.clear();
        jdbc.update("update payment_accounts set provider='STRIPE',external_account_id='acct_clock',credentials_ref='stripe-sandbox-default' where id=?", account.id().value());
        billing = accounts.findById(account.id()).orElseThrow();
        jdbc.update("insert into customers(id,site_id,status,email_ciphertext,email_nonce,email_key_version,email_digest,email_digest_key_version,created_at,updated_at) values (?,?,'ACTIVE',decode(repeat('00',16),'hex'),decode(repeat('00',12),'hex'),'test-only',?,'v1',now(),now())",
            customer.value(), site.value(), "digest-" + customer.value());
    }
    protected ClockOwner admit() {
        return clocks.admitFresh(billing, site, customer, UUID.randomUUID(), StripeBillingTestClockAdapter.API_VERSION, FROZEN, Instant.now());
    }
    protected ReadyClock createReady(ClockOwner owner) {
        http.reply(clockJson(owner)); http.reply(clockJson(owner));
        return clockAdapter.createFresh(billing, owner);
    }
    protected StripeBillingBindingPort.CustomerBinding createCustomer(ClockOwner owner) {
        http.reply(customerJson("clock_owned"));
        return customers.ensureOwnedCustomer(billing, owner);
    }
    protected ProviderOperation clockOperation(ClockOwner owner) {
        return operations.find(billing.id(), owner.createKey()).orElseThrow();
    }
    protected ProviderOperation customerOperation(ClockOwner owner) {
        return operations.find(billing.id(), owner.customerKey()).orElseThrow();
    }
    protected ProviderOperation ack(ProviderOperation operation, String reference) {
        var claim = operations.claim(operation.id(), "original", Instant.now(), Duration.ofSeconds(60)).orElseThrow();
        return operations.acknowledge(claim.id(), "original", claim.version(), reference);
    }
    protected String stopped(ClockOwner owner) {
        return jdbc.queryForObject("select stop_reason from stripe_billing_clock_intents where id=?", String.class, owner.intent());
    }
    protected String clockJson(ClockOwner owner) {
        return "{\"id\":\"clock_owned\",\"object\":\"test_helpers.test_clock\",\"livemode\":false,\"name\":\"" + owner.name()
            + "\",\"frozen_time\":" + FROZEN + ",\"created\":" + PROVIDER_CREATED + ",\"deletes_after\":" + PROVIDER_DELETES + ",\"status\":\"ready\",\"status_details\":{}}";
    }
    protected String customerJson(String clock) {
        return "{\"id\":\"cus_owned\",\"object\":\"customer\",\"livemode\":false,\"created\":1600000000,\"test_clock\":"
            + (clock == null ? "null" : "\"" + clock + "\"") + ",\"metadata\":{\"account_id\":\"" + billing.id().value()
            + "\",\"site_id\":\"" + site.value() + "\",\"customer_id\":\"" + customer.value() + "\"}}";
    }
    /** Real local rows created only after admission, to exercise the downstream admission fence. */
    protected Command lifecycle(boolean trial) {
        UUID product = UUID.randomUUID(), price = UUID.randomUUID(), subscription = UUID.randomUUID(), operation = UUID.randomUUID();
        int days = trial ? 7 : 0;
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,'clock-product','Clock fixture','ACTIVE',now(),now())", product, site.value());
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,'clock-price',1,'SUBSCRIPTION',1000,'EUR','MONTH',?,'REQUIRED_BEFORE_TRIAL','NONE','PUBLISHED',now(),now(),now())", price, site.value(), product, days);
        jdbc.update("insert into subscriptions(id,site_id,customer_id,product_id,payment_account_id,currency,billing_price_id,billing_price_key,billing_price_version,billing_amount_minor,billing_interval,billing_trial_days,billing_trial_policy,billing_tax_mode,billing_status,created_at,updated_at) values (?,?,?,?,?,'EUR',?,'clock-price',1,1000,'MONTH',?,'REQUIRED_BEFORE_TRIAL','NONE','INCOMPLETE',now(),now())", subscription, site.value(), customer.value(), product, billing.id().value(), price, days);
        String key = "subscription:" + billing.id().value() + ":" + operation + ":CREATE";
        jdbc.update("insert into subscription_operations(id,site_id,customer_id,subscription_id,kind,status,authority_scope,key_digest,fingerprint,provider_operation_key,expected_revision,policy_version,created_at,updated_at) values (?,?,?,?,'CREATE','RESERVED','customer:clock','clock-key',?,?,0,1,now(),now())", operation, site.value(), customer.value(), subscription, "v1." + (trial ? "trial" : "paid") + "." + "a".repeat(64), key);
        var s = subscriptions.findOwned(billing.id(), subscription).orElseThrow();
        var o = lifecycleOperations.find(site, subscription, operation).orElseThrow();
        return new Command(billing, o, s.billingPrice(), null, null, null, null, true, trial);
    }

    public static final class Verification implements PaymentAccountVerificationPort {
        public final List<PaymentAccount> accounts = new ArrayList<>();
        @Override public void verify(PaymentAccount account) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) throw new AssertionError("Verification inside transaction");
            accounts.add(account);
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    public static class InertConfiguration {
        @Bean StripeBillingInertSdkFixture.Transport clockCustomerTransport() { return new StripeBillingInertSdkFixture.Transport(); }
        @Bean @Primary StripeCredentialResolver clockCustomerCredentials() {
            return new StripeCredentialResolver(new StripeSandboxProperties(true, "sk_test_INERT", "pk_test_INERT", "whsec_INERT"));
        }
        @Bean @Primary Verification clockCustomerVerification() { return new Verification(); }
        @Bean @Primary StripeClientFactory clockCustomerClients(StripeBillingInertSdkFixture.Transport http) {
            // Never call super.builder: every SDK request is handled in memory, without a socket transport.
            return new StripeClientFactory() {
                @Override public StripeClient create(StripeCredentials credentials) {
                    return StripeClient.builder().setApiKey("sk_test_INERT").setApiBase("https://api.stripe.com")
                        .setMaxNetworkRetries(0).setHttpClient(http).build();
                }
            };
        }
        @Bean StripeBillingTestClockAdapter privateClockAdapter(StripeCredentialResolver credentials, StripeClientFactory clients,
                PaymentAccountVerificationPort verification, ProviderOperationStore operations, StripeBillingClockStore clocks, DigestPort digest) {
            return new StripeBillingTestClockAdapter(credentials, clients, verification, operations, clocks, digest);
        }
    }
}
