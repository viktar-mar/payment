package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.catalog.application.port.out.ProviderCatalogMappingPort;
import dev.genericpayments.platform.catalog.domain.BillingInterval;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.catalog.domain.TrialPaymentMethodPolicy;
import dev.genericpayments.platform.customers.application.port.out.CustomerSessionResolver;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountMode;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountStatus;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.provider.application.model.ClockOwner;
import dev.genericpayments.platform.provider.application.port.out.ProviderOperationStore;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingClockStore;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.sites.domain.SiteStatus;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionAuthorityPort;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionCatalogPort;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPolicyPort;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionCommandTransaction;
import java.net.URI;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit test-classpath admission for one fresh, provisioned optional-trial site and customer. */
public final class OwnedOptionalTrialAdmission {
    private static final Clock UTC = Clock.systemUTC();

    public record Request(UUID run, PaymentAccountId account, SiteId site, CustomerId customer, PriceId price,
                          String origin, String customerToken, String createKey, boolean recurringConsent) {
        @Override public String toString() { return "OwnedOptionalTrialAdmission.Request[redacted]"; }
    }

    public record Admitted(PaymentAccount account, OwnedOptionalTrialCreate.Scope scope, long frozen, long trialEnd) {
        @Override public String toString() { return "OwnedOptionalTrialAdmission.Admitted[redacted]"; }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate admissionTransaction;
    private final SubscriptionTransactionPort transactions;
    private final LoadPaymentAccountPort accounts;
    private final LoadSitePort sites;
    private final CustomerSessionResolver sessions;
    private final SubscriptionAuthorityPort authority;
    private final SubscriptionCatalogPort catalog;
    private final SubscriptionPolicyPort policies;
    private final ProviderCatalogMappingPort mappings;
    private final StripeCredentialResolver credentials;
    private final StripeBillingClockStore clocks;
    private final StripeBillingTestClockAdapter clockCreator;
    private final StripeBillingCustomerAdapter customers;
    private final SubscriptionCommandTransaction commands;
    private final SubscriptionBillingProofStore proofs;

    OwnedOptionalTrialAdmission(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            SubscriptionTransactionPort transactions, LoadPaymentAccountPort accounts, LoadSitePort sites,
            CustomerSessionResolver sessions, SubscriptionAuthorityPort authority, SubscriptionCatalogPort catalog,
            SubscriptionPolicyPort policies, ProviderCatalogMappingPort mappings, StripeCredentialResolver credentials,
            StripeBillingClockStore clocks, StripeBillingTestClockAdapter clockCreator, StripeBillingCustomerAdapter customers,
            SubscriptionCommandTransaction commands, SubscriptionBillingProofStore proofs) {
        this.jdbc = jdbc;
        this.admissionTransaction = new TransactionTemplate(transactionManager);
        admissionTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        admissionTransaction.setTimeout(5);
        this.transactions = transactions;
        this.accounts = accounts;
        this.sites = sites;
        this.sessions = sessions;
        this.authority = authority;
        this.catalog = catalog;
        this.policies = policies;
        this.mappings = mappings;
        this.credentials = credentials;
        this.clocks = clocks;
        this.clockCreator = clockCreator;
        this.customers = customers;
        this.commands = commands;
        this.proofs = proofs;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {
        @Bean OwnedOptionalTrialAdmission ownedOptionalTrialAdmission(JdbcTemplate jdbc,
                PlatformTransactionManager transactionManager, SubscriptionTransactionPort transactions,
                LoadPaymentAccountPort accounts, LoadSitePort sites, CustomerSessionResolver sessions,
                SubscriptionAuthorityPort authority, SubscriptionCatalogPort catalog, SubscriptionPolicyPort policies,
                ProviderCatalogMappingPort mappings, StripeCredentialResolver credentials, StripeClientFactory clients,
                PaymentAccountVerificationPort verification, ProviderOperationStore operations, StripeBillingClockStore clocks,
                DigestPort digest, StripeBillingCustomerAdapter customers, SubscriptionCommandTransaction commands,
                SubscriptionBillingProofStore proofs) {
            var clockCreator = new StripeBillingTestClockAdapter(credentials, clients, verification, operations, clocks, digest);
            return new OwnedOptionalTrialAdmission(jdbc, transactionManager, transactions, accounts, sites, sessions, authority,
                catalog, policies, mappings, credentials, clocks, clockCreator, customers, commands, proofs);
        }
    }

    public Admitted admit(Request request) {
        require(!transactions.active() && request != null && request.run() != null && request.account() != null
            && request.site() != null && request.customer() != null && request.price() != null && request.recurringConsent()
            && request.origin() != null && request.customerToken() != null && request.createKey() != null
            && !request.createKey().isBlank() && request.createKey().length() <= 255
            && request.createKey().codePoints().noneMatch(Character::isISOControl));
        try {
            var account = accounts.findById(request.account()).orElseThrow(OwnedOptionalTrialAdmission::unavailable);
            require(account.provider() == PaymentProviderType.STRIPE && account.mode() == PaymentAccountMode.SANDBOX
                && account.status() == PaymentAccountStatus.ACTIVE && "stripe-sandbox-default".equals(account.key())
                && OwnedOptionalTrialCreate.APPROVED_ACCOUNT.equals(account.externalAccountId())
                && "stripe-sandbox-default".equals(account.credentialsRef()));
            requireScope(request, account);
            var session = sessions.resolveRequired(request.customerToken(), request.origin());
            var context = new SubscriptionCommandContext(new SubscriptionActor.Customer(session), request.site(), request.customer(),
                request.origin(), request.createKey(), request.run().toString());
            transactions.requiresNew(() -> {
                authority.revalidate(context, true);
                return null;
            });
            credentials.resolve(account);

            var owner = admissionTransaction.execute(status -> claimFreshClock(request, account));
            require(owner != null);
            requireScope(request, account);
            var ready = clockCreator.createFresh(account, owner);
            require(ready.owner().equals(owner) && ready.frozenTime() == owner.initialFrozenTime()
                && ready.providerDeletesAfter() > UTC.instant().getEpochSecond());
            customers.ensureOwnedCustomer(account, owner);

            var reserved = commands.reserveCreate(new CreateSubscriptionUseCase.Command(context, request.price(), true));
            require(reserved.owner() && reserved.account().equals(account));
            long trialEnd = Math.addExact(ready.frozenTime(), 604_800L);
            var proof = proofs.reserveTrial(reserved.subscription(), reserved.operation(), trialEnd);
            require(proof.requestedEnd() == trialEnd && proof.step() == null);
            var scope = new OwnedOptionalTrialCreate.Scope(owner.run(),owner.intent(),reserved.subscription().id(),reserved.operation().id());
            require(new JdbcOwnedOptionalTrialCreateStore(jdbc).admitted(scope) != null);
            return new Admitted(account,scope,ready.frozenTime(),trialEnd);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private ClockOwner claimFreshClock(Request request, PaymentAccount account) {
        // Serialize callers across processes without holding account/site/customer row locks across REQUIRES_NEW.
        require(Boolean.TRUE.equals(jdbc.queryForObject("""
            select pg_try_advisory_xact_lock(hashtextextended(?,0))
            """, Boolean.class, "owned-optional-trial-admission:"+request.site().value())));
        requireSchema();
        var site = request.site().value();
        require(Boolean.TRUE.equals(jdbc.queryForObject("""
            select not exists(select 1 from stripe_billing_clock_intents where run_id=? or site_id=?)
                and not exists(select 1 from stripe_billing_customers where site_id=?)
                and not exists(select 1 from subscription_trial_eligibility where site_id=?)
                and not exists(select 1 from subscriptions where site_id=?)
                and not exists(select 1 from subscription_operations where site_id=?)
                and not exists(select 1 from payments where site_id=?)
            """, Boolean.class, request.run(),site,site,site,site,site,site)));
        var now = UTC.instant().truncatedTo(ChronoUnit.MICROS);
        var owner = clocks.admitFresh(account, request.site(), request.customer(), request.run(),
            StripeBillingTestClockAdapter.API_VERSION, now.getEpochSecond(), now);
        require(owner.admittedAt().equals(now) && owner.initialFrozenTime() == now.getEpochSecond());
        return owner;
    }

    private void requireSchema() {
        require(Boolean.TRUE.equals(jdbc.queryForObject("""
            select bool_and(to_regclass(relation_name) is not null)
            from (values ('owned_optional_trial_schema_history'),
                         ('stripe_billing_optional_trial_candidates'),
                         ('stripe_billing_optional_trial_acknowledgements'),
                         ('stripe_billing_optional_trial_pauses'),
                         ('stripe_billing_owned_optional_trial_resumes'),
                         ('stripe_billing_optional_trial_setup_steps'),
                         ('stripe_billing_optional_trial_clock_advances'),
                         ('stripe_billing_owned_optional_trial_payment_confirmations')) required(relation_name)
            """, Boolean.class)));
        require(Boolean.TRUE.equals(jdbc.queryForObject("""
            select array_agg(version order by installed_rank)=array['1','2','3','4','5','6','7']::varchar[]
               and bool_and(success)
            from owned_optional_trial_schema_history where version in ('1','2','3','4','5','6','7')
            """, Boolean.class)));
    }

    private void requireScope(Request request, PaymentAccount account) {
        var site = sites.findById(request.site()).orElseThrow(OwnedOptionalTrialAdmission::unavailable);
        require(site.status() == SiteStatus.ACTIVE && site.paymentAccountId().equals(account.id())
            && site.key().value().equals("s5a-"+request.run().toString().replace("-",""))
            && site.allowedOrigins().contains(URI.create(request.origin())) && policies.load(request.site()).enabled());
        var terms = catalog.published(request.site(), request.price());
        require(terms.version() == 1 && terms.regularAmount().minorUnits() == 2000 && terms.regularAmount().currency().equals("EUR")
            && terms.interval() == BillingInterval.MONTH && terms.taxMode() == TaxMode.INCLUSIVE && terms.trialDays() == 7
            && terms.trialPolicy() == TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL
            && mappings.findProduct(account.id(),terms.productId()).filter(ref -> ref.value().matches("prod_[A-Za-z0-9]{1,250}")).isPresent()
            && mappings.findPrice(account.id(),request.price()).filter(ref -> ref.value().matches("price_[A-Za-z0-9]{1,249}")).isPresent());
    }

    private static void require(boolean allowed) {
        if (!allowed) throw unavailable();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Owned optional trial admission unavailable");
    }
}
