package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionUseCase;
import dev.genericpayments.platform.catalog.domain.PriceId;
import java.time.Instant;
import java.util.UUID;
import dev.genericpayments.platform.subscriptions.application.port.in.QuerySubscriptionsUseCase;
import dev.genericpayments.platform.subscriptions.domain.AccessDecision;
import dev.genericpayments.platform.support.OwnedOptionalTrialFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;

@Import(OwnedOptionalTrialPublication.Configuration.class)
class OwnedOptionalTrialPublicationIntegrationTest extends OwnedOptionalTrialFixture {
    @Autowired OwnedOptionalTrialPublication publication;
    @Autowired QuerySubscriptionsUseCase queries;
    @Autowired ApplySubscriptionSnapshotUseCase facts;
    @Autowired StripeCredentialResolver credentials;
    @Autowired StripeClientFactory clients;

    private OwnedOptionalTrialCreate.Authority start(boolean acknowledge) {
        var r = reserveOptionalTrial();
        var scope = new OwnedOptionalTrialCreate.Scope(r.owner().run(), r.owner().intent(), r.reserved().subscription().id(),
            r.reserved().operation().id());
        var a = new JdbcOwnedOptionalTrialCreateStore(jdbc).admitted(scope);
        if (acknowledge) {
            OwnedOptionalTrialSdkReplies.queue(http, a, "");
            OwnedOptionalTrialCreate.withDatabase(jdbc, credentials, clients, verification).create(billing, scope);
        }
        http.calls.clear();
        return a;
    }

    private void assertUnpublished() {
        assertThat(jdbc.queryForList("select billing_status from subscriptions", String.class)).containsExactly("INCOMPLETE");
        assertThat(count("subscription_events")).isZero();
        assertThat(count("subscription_billing_records")).isZero();
        assertThat(count("payments")).isZero();
        assertThat(count("payment_events")).isZero();
    }

    @Test void aReservationWithoutAcknowledgementCannotReadOrPublish() {
        var a = start(false);
        assertThatThrownBy(() -> publication.publish(billing, a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).isEmpty();
        assertUnpublished();
    }

    @Test void independentlyReadSyntheticSdkGraphPublishesOneZeroBillAccessAndJournalThroughTheApplication() {
        var a = start(true);
        assertUnpublished();
        var session = sessions.issue(site, customer, "https://shop.test");
        var actor = new SubscriptionActor.Customer(sessions.resolveRequired(session.rawToken(), "https://shop.test"));
        var product = new dev.genericpayments.platform.catalog.domain.ProductId(a.product());
        assertThat(queries.access(actor, site, customer, product).allowed()).isFalse();
        OwnedOptionalTrialSdkReplies.observation(http, a, "");
        http.afterResponse = new Runnable() {
            public void run() {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                http.afterResponse = this;
            }
        };
        publication.publish(billing, a.scope());
        assertThat(http.calls).isNotEmpty().allMatch(call -> call.method().name().equals("GET"));
        assertThat(jdbc.queryForObject("""
            select count(*) from subscription_billing_records where subscription_id=? and provider_invoice_reference='in_trial'
              and settlement='ZERO_AMOUNT' and amount_due_minor=0 and extract(epoch from period_start)=?
              and extract(epoch from period_end)=?
            """, Integer.class, a.subscription(), FROZEN, FROZEN + 604800L)).isOne();
        assertThat(jdbc.queryForList("select event_type from subscription_events", String.class)).containsExactly("TRIAL_STARTED");
        assertThat(jdbc.queryForList("select status from subscription_operations", String.class)).containsExactly("SUCCEEDED");
        var view = queries.get(actor, site, a.subscription());
        assertThat(queries.access(actor, site, customer, product)).isEqualTo(view.access());
        assertThat(view.access().allowed()).isTrue();
        assertThat(view.access().reason()).isEqualTo(AccessDecision.Reason.TRIAL);
        assertThat(view.subscription().billingStatus().name()).isEqualTo("TRIALING");
        assertThat(view.subscription().paidThrough()).isNull();
        assertThat(count("payments")).isZero();
        assertThat(count("payment_events")).isZero();
        int reads = http.calls.size();
        assertThatThrownBy(() -> publication.publish(billing, a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(reads);
        assertThat(count("subscription_events")).isOne();
        assertThat(count("subscription_billing_records")).isOne();
    }

    @ParameterizedTest @ValueSource(strings={"foreign line", "allocation", "extra invoice", "wrong behavior", "advanced clock", "stopped", "stale fence"})
    void changedEvidenceCannotGrantAccessOrPostCash(String fault) {
        var a = start(true);
        OwnedOptionalTrialSdkReplies.observation(http, a, fault);
        if (fault.equals("stopped")) http.afterResponse = () -> jdbc.update("""
            update stripe_billing_clock_intents set stopped_at=clock_timestamp(), stop_reason='AUTHORITY_REVOKED' where id=?
            """, a.clockIntent());
        if (fault.equals("stale fence")) http.afterResponse = () -> jdbc.update(
            "update subscriptions set observation_sequence=observation_sequence+1 where id=?", a.subscription());
        assertThatThrownBy(() -> publication.publish(billing, a.scope())).isInstanceOf(IllegalStateException.class);
        assertUnpublished();
    }

    @Test void lostSyntheticSdkResponseCommitsUnknownAndRefusesAnyPublicationRead() {
        var a = start(false);
        OwnedOptionalTrialSdkReplies.queue(http, a, "lost ack");
        assertThatThrownBy(() -> OwnedOptionalTrialCreate.withDatabase(jdbc, credentials, clients, verification)
            .create(billing, a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select status from stripe_billing_owned_optional_trial_creates",String.class)).isEqualTo("UNKNOWN");
        http.calls.clear();
        assertThatThrownBy(() -> publication.publish(billing, a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).isEmpty();
        assertUnpublished();
    }

    @Test void identicalFactsWithoutAnIndependentlyObservedTransactionBindingCannotPublish() {
        var a = start(true);
        var command = forgedCommand(jdbc, billing, a);
        assertThatThrownBy(() -> transaction.required(() -> { facts.apply(command); return null; }))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Optional trial publication authority unavailable");
        assertUnpublished();
        assertThat(http.calls).isEmpty();
    }

    static ApplySubscriptionSnapshotUseCase.Command forgedCommand(org.springframework.jdbc.core.JdbcTemplate jdbc,
            dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount billing, OwnedOptionalTrialCreate.Authority a) {
        var receipt = jdbc.queryForObject("""
            select a.*,s.revision,s.observation_sequence,o.version from stripe_billing_optional_trial_acknowledgements a
            join subscriptions s on s.id=a.subscription_id join subscription_operations o on o.id=a.create_operation
            """, (r,n)->new OwnedOptionalTrialPublication.Receipt(a,
                new OwnedOptionalTrialCreate.Created(r.getString("subscription_reference"),r.getString("item_reference"),
                    r.getString("invoice_reference"),r.getLong("trial_start")),
                r.getObject("step_id",UUID.class),r.getLong("revision"),r.getLong("version"),r.getLong("observation_sequence")));
        return OwnedOptionalTrialPublication.observedCommand(billing, receipt, Instant.now());
    }

    private UUID createSibling(UUID originalPrice) {
        var product = UUID.randomUUID();
        var price = UUID.randomUUID();
        jdbc.update("""
            insert into products(id,site_id,product_key,name,status,created_at,updated_at)
            values (?,?,'second-plan','Sibling','ACTIVE',now(),now())
            """,product,site.value());
        jdbc.update("""
            insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,
              billing_interval,trial_days,trial_payment_method_policy,tax_mode,status,published_at,created_at,updated_at)
            select ?,site_id,?,'sibling-monthly',1,price_type,amount_minor,currency,billing_interval,trial_days,
              trial_payment_method_policy,tax_mode,status,published_at,now(),now() from prices where id=?
            """,price,product,originalPrice);
        var session=sessions.issue(site,customer,"https://shop.test");
        var actor=new SubscriptionActor.Customer(sessions.resolveRequired(session.rawToken(),"https://shop.test"));
        var ctx=new SubscriptionCommandContext(actor,site,customer,"https://shop.test","sibling-create","sibling-request");
        return commands.reserveCreate(new CreateSubscriptionUseCase.Command(ctx,new PriceId(price),true)).subscription().id();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void sameCustomerSiblingCreatedAfterAckCannotBorrowTrialTimeOrPublish(boolean duringSdkRead) {
        var a = start(true);
        UUID[] sibling = new UUID[1];
        if (duringSdkRead) {
            OwnedOptionalTrialSdkReplies.observation(http,a,"");
            http.afterResponse = () -> sibling[0] = createSibling(a.price());
        } else {
            sibling[0] = createSibling(a.price());
            assertThatThrownBy(() -> transaction.required(() -> publication.sample(billing.id(), site, customer, Instant.now())))
                .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> publication.publish(billing, a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(sibling[0]).isNotNull();
        assertThatThrownBy(() -> transaction.required(() -> publication.sample(billing.id(), site, customer, Instant.now())))
            .isInstanceOf(IllegalStateException.class);
        var session = sessions.issue(site, customer, "https://shop.test");
        var actor = new SubscriptionActor.Customer(sessions.resolveRequired(session.rawToken(), "https://shop.test"));
        assertThatThrownBy(() -> queries.get(actor, site, sibling[0])).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForList("select billing_status from subscriptions",String.class)).containsExactly("INCOMPLETE","INCOMPLETE");
        assertThat(count("subscription_events")).isZero();
        assertThat(count("subscription_billing_records")).isZero();
        assertThat(count("payments")).isZero();
    }

    @Test void journalFailureRollsBackTheBillReservationAndAccessTogether() {
        var a = start(true);
        OwnedOptionalTrialSdkReplies.observation(http, a, "");
        jdbc.execute("""
            create function test_reject_trial_event() returns trigger language plpgsql as $$
            begin raise exception 'Injected trial journal failure' using errcode='23514'; end $$;
            create trigger test_reject_trial_event before insert on subscription_events
            for each row when (new.event_type='TRIAL_STARTED') execute function test_reject_trial_event()
            """);
        try {
            assertThatThrownBy(() -> publication.publish(billing, a.scope())).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Injected trial journal failure");
        } finally {
            jdbc.execute("drop trigger test_reject_trial_event on subscription_events");
            jdbc.execute("drop function test_reject_trial_event()");
        }
        assertUnpublished();
        assertThat(jdbc.queryForObject("select consumed_at is null from subscription_trial_eligibility", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select observation_sequence from subscriptions", Long.class)).isZero();
        OwnedOptionalTrialSdkReplies.observation(http, a, "");
        publication.publish(billing, a.scope());
        assertThat(count("subscription_events")).isOne();
        assertThat(count("subscription_billing_records")).isOne();
        assertThat(count("payments")).isZero();
    }
}
