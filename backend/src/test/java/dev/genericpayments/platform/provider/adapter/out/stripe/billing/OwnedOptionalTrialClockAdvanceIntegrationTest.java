package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.google.gson.JsonParser;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialCreate.Authority;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialCreate.Scope;
import dev.genericpayments.platform.provider.application.model.ClockOwner;
import dev.genericpayments.platform.support.OwnedOptionalTrialFixture;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;

@Import(OwnedOptionalTrialPublication.Configuration.class)
class OwnedOptionalTrialClockAdvanceIntegrationTest extends OwnedOptionalTrialFixture {
    @Autowired OwnedOptionalTrialPublication publication;
    @Autowired StripeCredentialResolver credentials;
    @Autowired StripeClientFactory clients;
    private Long expiresAt;

    @Test void onlyPublishedOriginalTrialCanStartTheAdvance() {
        var a = create(false);
        assertThatThrownBy(() -> advance().advance(billing, a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).isEmpty();
        assertThat(count("stripe_billing_optional_trial_clock_advances")).isZero();
    }

    @Test void providerExpiryUsesOperationalUtc() {
        expiresAt = Instant.now().getEpochSecond()-3600L;
        var a = create(true);
        assertThatThrownBy(() -> advance().advance(billing,a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).isEmpty();
        assertThat(count("stripe_billing_optional_trial_clock_advances")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"extra clock customer","extra clock subscription"})
    void sharedRemoteClockCannotBeClaimed(String fault) {
        var a = create(true);
        OwnedOptionalTrialSdkReplies.clockScopedObservation(http,a,fault);
        assertThatThrownBy(() -> advance().advance(billing,a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).allMatch(request -> request.method().name().equals("GET"));
        assertThat(count("stripe_billing_optional_trial_clock_advances")).isZero();
    }

    @Test void claimIsCommittedBeforeOneExactPostAndReadyRequiresItsOwnGet() {
        var a = create(true);
        OwnedOptionalTrialClockAdvanceSdkReplies.preflight(http, a);
        http.reply(request -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(status()).isEqualTo("DISPATCHING");
            assertThatThrownBy(() -> advance().advance(billing,a.scope())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> advance().observeReady(billing,a.scope())).isInstanceOf(IllegalStateException.class);
            assertThat(request.url().getPath()).isEqualTo("/v1/test_helpers/test_clocks/clock_owned/advance");
            assertThat(request.params()).containsOnlyKeys("frozen_time").containsEntry("frozen_time",1700604800L);
            assertThat(request.headers().map().get("Stripe-Version")).containsExactly("2026-09-30.endive");
            assertThat(request.options().getMaxNetworkRetries()).isZero();
            assertThat(request.options().getIdempotencyKey()).isEqualTo("billing-optional-trial-advance-v1:"+a.clockIntent());
            return OwnedOptionalTrialClockAdvanceSdkReplies.advancing(a,a.trialEnd());
        });
        http.reply(request -> {
            assertThat(status()).isEqualTo("ACKNOWLEDGED");
            assertThat(OwnedOptionalTrialClockAdvance.ready(jdbc,a.scope(),a.trialEnd())).isFalse();
            assertThat(request.method().name()).isEqualTo("GET");
            return OwnedOptionalTrialClockAdvanceSdkReplies.ready(a,a.trialEnd());
        });

        assertThat(advance().advance(billing,a.scope())).isEqualTo(1700604800L);
        assertThat(OwnedOptionalTrialClockAdvance.ready(jdbc,a.scope(),a.trialEnd())).isTrue();
        assertThat(OwnedOptionalTrialClockAdvance.ready(jdbc,a.scope(),a.trialEnd()+1)).isFalse();
        assertThat(http.calls.stream().filter(request -> request.method().name().equals("POST"))).hasSize(1);
        for (var path : new String[]{"/v1/customers","/v1/subscriptions"}) {
            assertThat(http.calls.stream().filter(request -> request.url().getPath().equals(path)).toList())
                .singleElement().satisfies(request -> assertThat(request.params()).containsEntry("test_clock","clock_owned")
                    .doesNotContainKey("customer"));
        }
        assertThat(http.replies).isEmpty();
        int reads = http.calls.size();
        assertThatThrownBy(() -> advance().advance(billing,a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(advance().observeReady(billing,a.scope())).isEqualTo(a.trialEnd());
        assertThat(http.calls).hasSize(reads);
        assertThat(count("payments")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"lost reply", "foreign target"})
    void uncertainPostConsumesTheOnlyAdvance(String fault) {
        var a = create(true);
        OwnedOptionalTrialClockAdvanceSdkReplies.queue(http, a, fault);
        assertThatThrownBy(() -> advance().advance(billing,a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(status()).isEqualTo("UNKNOWN");
        int calls = http.calls.size();
        assertThat(http.calls.stream().filter(request -> request.method().name().equals("POST"))).hasSize(1);
        assertThatThrownBy(() -> advance().advance(billing,a.scope())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> advance().observeReady(billing,a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(calls);
        assertThat(OwnedOptionalTrialClockAdvance.ready(jdbc,a.scope(),a.trialEnd())).isFalse();
    }

    @Test void acknowledgementCanBeFollowedByAnIndependentReadOfTheOriginalTarget() {
        var a = create(true);
        OwnedOptionalTrialClockAdvanceSdkReplies.queue(http, a, "wrong ready");
        assertThatThrownBy(() -> advance().advance(billing,a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(status()).isEqualTo("ACKNOWLEDGED");
        assertThat(OwnedOptionalTrialClockAdvance.ready(jdbc,a.scope(),a.trialEnd())).isFalse();
        http.calls.clear();
        http.reply(OwnedOptionalTrialClockAdvanceSdkReplies.ready(a,a.trialEnd()));
        assertThat(advance().observeReady(billing,a.scope())).isEqualTo(a.trialEnd());
        assertThat(http.calls).hasSize(1).allMatch(request -> request.method().name().equals("GET"));
    }

    @Test void changedLocalObservationFenceDuringPrePostReadPreventsThePost() {
        var a = create(true);
        OwnedOptionalTrialSdkReplies.clockScopedObservation(http, a, "");
        http.reply(request -> {
            jdbc.update("update subscriptions set observation_sequence=observation_sequence+1 where id=?",a.subscription());
            return OwnedOptionalTrialClockAdvanceSdkReplies.ready(a,a.frozen());
        });
        assertThatThrownBy(() -> advance().advance(billing,a.scope())).isInstanceOf(IllegalStateException.class);
        assertThat(status()).isEqualTo("UNKNOWN");
        assertThat(http.calls).allMatch(request -> request.method().name().equals("GET"));
    }

    private String status() {
        return jdbc.queryForObject("select status from stripe_billing_optional_trial_clock_advances",String.class);
    }

    private OwnedOptionalTrialClockAdvance advance() {
        return OwnedOptionalTrialClockAdvance.withDatabase(jdbc,credentials,clients,verification);
    }

    @Override protected String clockJson(ClockOwner owner) {
        var ready = super.clockJson(owner);
        if (expiresAt == null) return ready;
        var clock = JsonParser.parseString(ready).getAsJsonObject();
        clock.addProperty("created",expiresAt-86_400L);
        clock.addProperty("deletes_after",expiresAt);
        return clock.toString();
    }

    private Authority create(boolean publish) {
        var r = reserveOptionalTrial();
        var scope = new Scope(r.owner().run(),r.owner().intent(),r.reserved().subscription().id(),r.reserved().operation().id());
        var a = new JdbcOwnedOptionalTrialCreateStore(jdbc).admitted(scope);
        OwnedOptionalTrialSdkReplies.queue(http,a,"");
        OwnedOptionalTrialCreate.withDatabase(jdbc,credentials,clients,verification).create(billing,scope);
        if (publish) {
            OwnedOptionalTrialSdkReplies.observation(http,a,"");
            publication.publish(billing,scope);
        }
        http.calls.clear();
        return a;
    }
}
