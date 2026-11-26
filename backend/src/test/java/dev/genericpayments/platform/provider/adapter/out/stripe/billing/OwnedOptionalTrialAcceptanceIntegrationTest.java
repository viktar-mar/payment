package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.net.StripeResponse;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.port.in.QuerySubscriptionsUseCase;
import dev.genericpayments.platform.subscriptions.domain.AccessDecision;
import dev.genericpayments.platform.webhooks.application.port.out.CallbackConfigurationPort;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialAcceptance.Status.*;
import static org.assertj.core.api.Assertions.*;

@Import(OwnedOptionalTrialAcceptance.Configuration.class)
@TestPropertySource(properties="payment.telemetry.site-keys[0]=s5a-7f87e8d25b6b4ac599a070809e6ec87f")
class OwnedOptionalTrialAcceptanceIntegrationTest extends OwnedOptionalTrialAdmissionFixture {
    @Autowired OwnedOptionalTrialAcceptance acceptance;
    @Autowired QuerySubscriptionsUseCase subscriptionsQuery;
    @Autowired CallbackConfigurationPort callbacks;

    @Override protected UUID admissionRun() {
        return UUID.fromString("7f87e8d2-5b6b-4ac5-99a0-70809e6ec87f");
    }

    @Test void opensFreshTrialAndSettlesTheSameResumeInvoiceThroughTheAcceptanceSession() {
        assertThat(jdbc.queryForObject("show join_collapse_limit", String.class)).isEqualTo("1");
        assertThat(jdbc.queryForObject("show from_collapse_limit", String.class)).isEqualTo("1");
        assertThat(jdbc.queryForObject("show jit", String.class)).isEqualTo("off");
        callbacks.configure(site.value(), "http://callback-receiver:8080/callback", true);
        var authority = new AtomicReference<OwnedOptionalTrialCreate.Authority>();
        queueOpening();
        http.reply(request -> {
            var scope = jdbc.queryForObject("""
                select clock_intent,subscription_id,create_operation
                from stripe_billing_optional_trial_candidates where run_id=?
                """, (row, ignored) -> new OwnedOptionalTrialCreate.Scope(run, row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class), row.getObject(3, UUID.class)), run);
            var original = new JdbcOwnedOptionalTrialCreateStore(jdbc).admitted(scope);
            authority.set(original);
            OwnedOptionalTrialSdkReplies.queue(http, original, "");
            OwnedOptionalTrialSdkReplies.observation(http, original, "");
            return firstQueuedReply();
        });
        var session = acceptance.open(admissionRequest());
        var a = authority.get();
        var original = new OwnedOptionalTrialCreate.Created("sub_owned", "si_owned", "in_trial", a.frozen());
        var actor = new SubscriptionActor.Customer(sessions.resolveRequired(token, ORIGIN));
        assertThat(session.snapshot().status()).isEqualTo(TRIALING);
        assertThat(subscriptionsQuery.get(actor, site, a.subscription()).access().allowed()).isTrue();
        UUID originalBill = jdbc.queryForObject("select id from subscription_billing_records", UUID.class);

        OwnedOptionalTrialClockAdvanceSdkReplies.queue(http, a, "");
        OwnedOptionalTrialPauseSdkTest.queuePaused(http, a, "");
        session.advance();
        assertThat(session.snapshot().status()).isEqualTo(PAUSED);
        assertThat(subscriptionsQuery.get(actor, site, a.subscription()).access().allowed()).isFalse();

        OwnedOptionalTrialSetupSdkTest.queuePrepare(http, a);
        http.reply(request -> {
            var setup = jdbc.queryForObject("select id from subscription_operations where kind='SETUP'", UUID.class);
            OwnedOptionalTrialSetupSdkTest.queueBrowserPermit(http, a, setup, "seti_owned_secret_ephemeral");
            return firstQueuedReply();
        });
        var permit = session.prepareSetup(context(actor, "acceptance-setup"), true);
        assertThat(permit.reference()).isEqualTo("seti_owned");
        assertThat(session.snapshot().status()).isEqualTo(WAITING_FOR_SETUP);
        assertThat(count("payments")).isZero();

        var setup = session.snapshot().setupOperation();
        OwnedOptionalTrialSetupSdkTest.queueComplete(http, a, setup);
        session.completeSetup();
        var method = proofs.setup(billing.id(), setup).orElseThrow();
        OwnedOptionalTrialResumeSdkReplies.dispatch(http, a, original, setup, method.reference(), method.method(), "");
        OwnedOptionalTrialResumeSdkReplies.observation(http, a, original, setup, method.reference(), method.method(), false, "");
        session.resume(context(actor, "acceptance-resume"), true);
        assertThat(session.snapshot().status()).isEqualTo(OPEN);
        assertThat(subscriptionsQuery.get(actor, site, a.subscription()).access().allowed()).isFalse();
        UUID resumeBill = jdbc.queryForObject("""
            select id from subscription_billing_records where provider_invoice_reference='in_resume' and settlement='OPEN'
            """, UUID.class);
        assertThat(count("payments")).isZero();

        OwnedOptionalTrialResumeSdkReplies.confirmation(http, a, original, setup, method.reference(), method.method(), "");
        OwnedOptionalTrialResumeSdkReplies.observation(http, a, original, setup, method.reference(), method.method(), true, "");
        session.pay();
        assertThat(session.snapshot().status()).isEqualTo(PAID);
        assertThat(subscriptionsQuery.get(actor, site, a.subscription()).access().reason()).isEqualTo(AccessDecision.Reason.PAID);
        assertThat(jdbc.queryForList("select id from subscription_billing_records order by period_start", UUID.class))
            .containsExactly(originalBill, resumeBill);
        assertThat(jdbc.queryForList("select event_type from subscription_events order by sequence_number", String.class))
            .containsExactly("TRIAL_STARTED", "PAUSED", "PERIOD_PAID");
        assertThat(jdbc.queryForList("select amount_minor from payments", Long.class)).containsExactly(2000L);
        assertThat(jdbc.queryForList("""
            select callback_type from outbox_events where site_id=? and payment_account_id=?
              and endpoint_url='http://callback-receiver:8080/callback'
              and ((source_kind='SUBSCRIPTION' and subscription_id=? and payment_id is null)
                or (source_kind='PAYMENT' and subscription_id is null
                  and payment_id=(select id from payments where subscription_id=?)))
            """, String.class,site.value(),billing.id().value(),a.subscription(),a.subscription()))
            .containsExactlyInAnyOrder("subscription.updated","subscription.updated","subscription.updated","payment.succeeded");
        assertThat(http.calls.stream().filter(call -> call.method().name().equals("POST"))
            .map(call -> call.url().getPath())).containsExactly(
                "/v1/test_helpers/test_clocks", "/v1/customers", "/v1/subscriptions",
                "/v1/test_helpers/test_clocks/clock_owned/advance", "/v1/setup_intents", "/v1/subscriptions/sub_owned",
                "/v1/subscriptions/sub_owned/resume", "/v1/payment_intents/pi_resume/confirm");
        int calls = http.calls.size();
        assertThatThrownBy(session::pay).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> acceptance.open(admissionRequest())).isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).hasSize(calls);
        assertThat(http.replies).isEmpty();
    }

    private SubscriptionCommandContext context(SubscriptionActor.Customer actor, String key) {
        return new SubscriptionCommandContext(actor, site, customer, ORIGIN, key, key);
    }

    private String firstQueuedReply() {
        return ((StripeResponse) http.replies.removeFirst()).body();
    }
}
