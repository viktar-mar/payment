package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionAuthorityPort;
import dev.genericpayments.platform.support.OwnedOptionalTrialFixture;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.*;

@Import(OwnedOptionalTrialPublication.Configuration.class)
class OwnedOptionalTrialSetupBrowserIntegrationTest extends OwnedOptionalTrialFixture {
    @Autowired OwnedOptionalTrialPublication publication;
    @Autowired StripeClientFactory clients;
    @Autowired StripeCredentialResolver credentials;
    @Autowired SubscriptionAuthorityPort authority;
    @Autowired Clock operationalClock;

    @Test void onlyTheOriginalPausedSetupCanYieldOneBrowserPermit() {
        var s = prepare();
        var otherContext = new SubscriptionCommandContext(s.context().actor(), s.context().siteId(),
            s.context().customerId(), s.context().origin(), s.context().idempotencyKey(), "unrelated-request");
        assertThatThrownBy(() -> s.setup().claimBrowserPermit(billing, s.a().scope(), otherContext, s.prepared()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).isEmpty();

        OwnedOptionalTrialSetupSdkTest.queueBrowserPermit(http, s.a(), s.prepared(), "seti_owned_secret_ephemeral");
        var permit = s.setup().claimBrowserPermit(billing, s.a().scope(), s.context(), s.prepared());

        assertThat(permit.reference()).isEqualTo("seti_owned");
        assertThat(permit.clientSecret()).isEqualTo("seti_owned_secret_ephemeral");
        assertThat(permit.publishableKey()).startsWith("pk_test_");
        assertThat(permit.expiresAt()).isAfter(operationalClock.instant());
        assertThat(permit.toString()).doesNotContain(permit.reference(), permit.clientSecret(), permit.publishableKey());
        assertThat(http.calls).allSatisfy(call -> {
            assertThat(call.method().name()).isEqualTo("GET");
            assertThat(call.headers().firstValue("Stripe-Version")).contains("2026-09-30.endive");
            assertThat(call.options().getMaxNetworkRetries()).isZero();
        });
        int reads = http.calls.size();
        assertThatThrownBy(() -> s.setup().claimBrowserPermit(billing, s.a().scope(), s.context(), s.prepared()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(reads);
        assertThat(proofs.setup(billing.id(), s.prepared().operation()).orElseThrow().method()).isNull();

        OwnedOptionalTrialSetupSdkTest.queueComplete(http, s.a(), s.prepared());
        s.setup().complete(billing, s.a().scope(), s.context(), s.prepared());
        assertThat(proofs.setup(billing.id(), s.prepared().operation()).orElseThrow().method()).isEqualTo("pm_owned");
        assertThat(jdbc.queryForList("select billing_status from subscriptions", String.class)).containsExactly("PAUSED");
        assertThat(jdbc.queryForObject("select count(*) from payments", Integer.class)).isZero();
        assertThat(http.replies).isEmpty();
    }

    @Test void aDifferentIntentSecretConsumesTheAttemptWithoutReturningAnyPermit() {
        var s = prepare();
        OwnedOptionalTrialSetupSdkTest.queueBrowserPermit(http, s.a(), s.prepared(), "seti_other_secret_ephemeral");
        assertThatThrownBy(() -> s.setup().claimBrowserPermit(billing, s.a().scope(), s.context(), s.prepared()))
            .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret");
        int reads = http.calls.size();
        assertThatThrownBy(() -> s.setup().claimBrowserPermit(billing, s.a().scope(), s.context(), s.prepared()))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> s.setup().complete(billing, s.a().scope(), s.context(), s.prepared()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(reads).allMatch(call -> call.method().name().equals("GET"));
    }

    @Test void leaseExpiresOnOperationalTimeWithoutAdvancingTheProviderClock() {
        var s = prepare();
        TestClock.set(operationalClock.instant().plusSeconds(240));
        assertThatThrownBy(() -> s.setup().claimBrowserPermit(billing, s.a().scope(), s.context(), s.prepared()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).isEmpty();
    }

    private record Started(OwnedOptionalTrialCreate.Authority a, OwnedOptionalTrialSetup setup,
            SubscriptionCommandContext context, OwnedOptionalTrialSetup.Prepared prepared) {}

    private Started prepare() {
        var r = reserveOptionalTrial();
        var scope = new OwnedOptionalTrialCreate.Scope(r.owner().run(), r.owner().intent(),
            r.reserved().subscription().id(), r.reserved().operation().id());
        var a = new JdbcOwnedOptionalTrialCreateStore(jdbc).admitted(scope);
        OwnedOptionalTrialSdkReplies.queue(http, a, "");
        OwnedOptionalTrialCreate.withDatabase(jdbc, credentials, clients, verification).create(billing, scope);
        OwnedOptionalTrialSdkReplies.observation(http, a, "");
        publication.publish(billing, scope);
        OwnedOptionalTrialPauseSdkTest.queuePaused(http, a, "");
        publication.pause(billing, scope);
        var session = sessions.issue(site, customer, "https://shop.test");
        var actor = new SubscriptionActor.Customer(sessions.resolveRequired(session.rawToken(), "https://shop.test"));
        var context = new SubscriptionCommandContext(actor, site, customer, "https://shop.test", "browser-setup", "original-request");
        var setup = new OwnedOptionalTrialSetup(jdbc, transaction, authority, lifecycleOperations, subscriptions, digest,
            credentials, clients, verification, publication, operationalClock);
        OwnedOptionalTrialSetupSdkTest.queuePrepare(http, a);
        var prepared = setup.prepare(billing, scope, context, true);
        http.calls.clear();
        return new Started(a, setup, context, prepared);
    }
}
