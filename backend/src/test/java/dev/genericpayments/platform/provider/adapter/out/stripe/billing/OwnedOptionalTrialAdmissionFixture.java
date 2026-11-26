package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.google.gson.JsonParser;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPolicy;
import dev.genericpayments.platform.support.OwnedOptionalTrialFixture;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.annotation.Import;

/** Provisioned local catalog and active customer session, before any provider clock or Customer write. */
@Import(OwnedOptionalTrialAdmission.Configuration.class)
public abstract class OwnedOptionalTrialAdmissionFixture extends OwnedOptionalTrialFixture {
    protected static final String ORIGIN = "https://shop.test";
    protected UUID run;
    protected final PriceId price = new PriceId(UUID.randomUUID());
    protected String token;

    protected UUID admissionRun() {
        return UUID.randomUUID();
    }

    @BeforeEach void provisionMappedTrial() {
        run = admissionRun();
        TestClock.set(Instant.now());
        jdbc.update("""
            update payment_accounts set account_key='stripe-sandbox-default', external_account_id=? where id=?
            """,OwnedOptionalTrialCreate.APPROVED_ACCOUNT,account.id().value());
        billing = accounts.findById(account.id()).orElseThrow();
        jdbc.update("update sites set site_key=? where id=?","s5a-"+run.toString().replace("-",""),site.value());
        policies.save(new SubscriptionPolicy(site,0,true,3,false,false,false,Set.of()),0);
        var product = UUID.randomUUID();
        jdbc.update("""
            insert into products(id,site_id,product_key,name,status,created_at,updated_at)
            values (?,?,'owned-plan','Optional trial','ACTIVE',now(),now())
            """,product,site.value());
        jdbc.update("""
            insert into provider_product_mappings(payment_account_id,product_id,provider_product_id,created_at)
            values (?,?,'prod_owned',now())
            """,billing.id().value(),product);
        provisionPrice(price, product, "owned-monthly", 2000, "price_owned");
        token = sessions.issue(site,customer,ORIGIN).rawToken();
    }

    protected void provisionPrice(PriceId id, UUID product, String key, int amount, String providerReference) {
        jdbc.update("""
            insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,
                billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at)
            values (?,?,?,?,1,'SUBSCRIPTION',?,'EUR','MONTH',7,'OPTIONAL_DURING_TRIAL',
              'INCLUSIVE','PUBLISHED',now(),now(),now())
            """, id.value(), site.value(), product, key, amount);
        jdbc.update("""
            insert into provider_price_mappings(payment_account_id,price_id,provider_price_id,created_at)
            values (?,?,?,now())
            """, billing.id().value(), id.value(), providerReference);
    }

    protected OwnedOptionalTrialAdmission.Request admissionRequest() {
        return admissionRequest(run,ORIGIN,true);
    }

    protected OwnedOptionalTrialAdmission.Request admissionRequest(UUID owner, String origin, boolean consent) {
        return admissionRequest(owner, origin, consent, price);
    }

    protected OwnedOptionalTrialAdmission.Request admissionRequest(UUID owner, String origin, boolean consent, PriceId requestedPrice) {
        return new OwnedOptionalTrialAdmission.Request(owner, billing.id(), site, customer, requestedPrice, origin, token,
            "original-trial-create", consent);
    }

    protected void queueOpening() {
        queueOpening(() -> {});
    }

    protected void queueOpening(Runnable onClockPost) {
        http.reply(post -> {
            onClockPost.run();
            return clockReply();
        });
        http.reply(get -> clockReply());
        http.reply(customerJson("clock_owned"));
    }

    protected String clockReply() {
        var owner = clocks.owner(billing.id(),site,customer).orElseThrow();
        var clock = JsonParser.parseString(clockJson(owner)).getAsJsonObject();
        clock.addProperty("frozen_time",owner.initialFrozenTime());
        return clock.toString();
    }
}
