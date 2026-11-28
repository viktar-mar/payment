package dev.genericpayments.platform.support;

import com.google.gson.JsonParser;
import dev.genericpayments.acceptance.runtime.OwnedBillingOptionalTrialRuntime;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.customers.application.service.CustomerSessionService;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingTestClockAdapter;
import dev.genericpayments.platform.provider.application.model.ClockOwner;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPolicyPort;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionCommandTransaction;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPolicy;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Installs the optional-trial controls only in this fixture's fresh test database. */
@Import(OwnedOptionalTrialFixture.Schema.class)
@TestPropertySource(properties="spring.datasource.hikari.connection-init-sql="+OwnedBillingOptionalTrialRuntime.CONNECTION_INIT_SQL)
public abstract class OwnedOptionalTrialFixture extends StripeBillingClockCustomerFixture {
    private final long clockCreated = Instant.now().getEpochSecond();

    @TestConfiguration(proxyBeanMethods = false)
    static class Schema {
        @Bean
        FlywayMigrationStrategy optionalTrialMigrations() {
            return main -> {
                main.migrate();
                Flyway.configure()
                    .dataSource(main.getConfiguration().getDataSource())
                    .locations("classpath:db/owned-optional-trial")
                    .table("owned_optional_trial_schema_history")
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load().migrate();
            };
        }
    }

    @Autowired protected SubscriptionCommandTransaction commands;
    @Autowired protected CustomerSessionService sessions;
    @Autowired protected SubscriptionPolicyPort policies;
    @Autowired protected SubscriptionBillingProofStore proofs;

    protected record Reservation(ClockOwner owner, SubscriptionCommandTransaction.ReservedCreate reserved, UUID price, UUID product) {}
    protected Reservation reserveOptionalTrial() {
        return reserveTrialProfile(7, "OPTIONAL_DURING_TRIAL", 2000, true);
    }

    protected Reservation reserveOptionalTrial(UUID run) {
        return reserveTrialProfile(run,7,"OPTIONAL_DURING_TRIAL",2000,true,"acct_1UPiMpELswAd8tpR");
    }

    @Override protected String clockJson(ClockOwner owner) {
        var clock = JsonParser.parseString(super.clockJson(owner)).getAsJsonObject();
        clock.addProperty("created",clockCreated);
        clock.addProperty("deletes_after",clockCreated+86_400L);
        return clock.toString();
    }

    protected Reservation reserveTrialProfile(int days, String policy, int amount, boolean reserveProof) {
        return reserveTrialProfile(days, policy, amount, reserveProof, "acct_1UPiMpELswAd8tpR");
    }

    protected Reservation reserveTrialProfile(int days, String policy, int amount, boolean reserveProof, String externalAccount) {
        return reserveTrialProfile(UUID.randomUUID(),days,policy,amount,reserveProof,externalAccount);
    }

    private Reservation reserveTrialProfile(UUID run, int days, String policy, int amount, boolean reserveProof, String externalAccount) {
        TestClock.set(Instant.now());
        jdbc.update("update payment_accounts set account_key='stripe-sandbox-default', external_account_id=? where id=?",
            externalAccount, account.id().value());
        billing = accounts.findById(account.id()).orElseThrow();
        jdbc.update("update sites set site_key=? where id=?", "s5a-" + run.toString().replace("-", ""), site.value());
        policies.save(new SubscriptionPolicy(site, 0, true, 3, false, false, false, Set.of()), 0);
        var owner = clocks.admitFresh(billing, site, customer, run, StripeBillingTestClockAdapter.API_VERSION, FROZEN, Instant.now());
        createReady(owner);
        createCustomer(owner);

        var product = UUID.randomUUID();
        var price = UUID.randomUUID();
        jdbc.update("""
            insert into products(id,site_id,product_key,name,status,created_at,updated_at)
            values (?,?,'owned-plan','Optional trial','ACTIVE',now(),now())
            """, product, site.value());
        jdbc.update("""
            insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,
                billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at)
            values (?,?,?,'owned-monthly',1,'SUBSCRIPTION',?,'EUR','MONTH',?,?,'INCLUSIVE','PUBLISHED',now(),now(),now())
            """, price, site.value(), product, amount, days, policy);
        jdbc.update("insert into provider_product_mappings(payment_account_id,product_id,provider_product_id,created_at) values (?,?,'prod_owned',now())",
            billing.id().value(), product);
        jdbc.update("insert into provider_price_mappings(payment_account_id,price_id,provider_price_id,created_at) values (?,?,'price_owned',now())",
            billing.id().value(), price);
        var reserved = reserveFor(customer, price);
        if (reserveProof) proofs.reserveTrial(reserved.subscription(), reserved.operation(), FROZEN + days * 86_400L);
        return new Reservation(owner, reserved, price, product);
    }

    protected SubscriptionCommandTransaction.ReservedCreate reserveFor(CustomerId customer, UUID price) {
        var session = sessions.issue(site, customer, "https://shop.test");
        var actor = new SubscriptionActor.Customer(sessions.resolveRequired(session.rawToken(), "https://shop.test"));
        var context = new SubscriptionCommandContext(actor, site, customer, "https://shop.test", "trial-create", "trial-request");
        var reserved = commands.reserveCreate(new CreateSubscriptionUseCase.Command(context, new PriceId(price), true));
        assertThat(reserved.owner()).isTrue();
        return reserved;
    }
}
