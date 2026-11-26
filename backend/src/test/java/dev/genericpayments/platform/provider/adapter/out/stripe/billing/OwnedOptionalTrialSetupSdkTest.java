package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.google.gson.JsonObject;
import com.stripe.StripeClient;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture.Transport;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.json;
import static org.assertj.core.api.Assertions.*;

class OwnedOptionalTrialSetupSdkTest {
    private static final UUID OPERATION = UUID.fromString("10000000-0000-0000-0000-000000000222");
    private final OwnedOptionalTrialCreate.Authority authority = new OwnedOptionalTrialCreateTest().a;
    private final Transport http = new Transport();
    private final StripeClient client = StripeClient.builder().setApiKey("sk_test_INERT").setMaxNetworkRetries(0)
        .setHttpClient(http).build();
    private final OwnedOptionalTrialCreate.Created original =
        new OwnedOptionalTrialCreate.Created("sub_owned", "si_owned", "in_trial", authority.frozen());

    @Test
    void createsOneUnconfirmedCardIntentForTheOriginalCustomer() throws Exception {
        http.reply(setupIntent(authority, OPERATION, null).toString());

        var intent = OwnedOptionalTrialSetup.createIntent(client, authority, OPERATION, "owned:SETUP");

        assertThat(intent.getId()).isEqualTo("seti_owned");
        assertThat(http.calls).singleElement().satisfies(call -> {
            assertThat(call.url().getPath()).isEqualTo("/v1/setup_intents");
            assertThat(call.method().name()).isEqualTo("POST");
            assertThat(call.options().getIdempotencyKey()).isEqualTo("owned:SETUP");
            assertThat(call.options().getMaxNetworkRetries()).isZero();
            assertThat(call.params()).containsOnlyKeys("customer", "usage", "allowed_payment_method_types", "metadata")
                .containsEntry("customer", authority.customerReference()).containsEntry("usage", "off_session")
                .containsEntry("allowed_payment_method_types", List.of("card"))
                .containsEntry("metadata", OwnedOptionalTrialSetup.metadata(authority, OPERATION));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong intent", "old operation", "wrong customer", "live intent", "wrong usage",
        "pending intent", "wrong type", "different creation", "foreign card", "wrong card type"})
    void aChangedIntentOrCardCannotSupplyTheMethod(String fault) {
        var intent = setupIntent(authority, OPERATION, "pm_owned");
        var card = card(authority, "pm_owned");
        switch (fault) {
            case "wrong intent" -> intent.addProperty("id", "seti_foreign");
            case "old operation" -> intent.getAsJsonObject("metadata").addProperty("operation_id", authority.createOperation().toString());
            case "wrong customer" -> intent.addProperty("customer", "cus_foreign");
            case "live intent" -> intent.addProperty("livemode", true);
            case "wrong usage" -> intent.addProperty("usage", "on_session");
            case "pending intent" -> intent.addProperty("status", "requires_action");
            case "wrong type" -> intent.getAsJsonArray("payment_method_types").set(0, new com.google.gson.JsonPrimitive("sepa_debit"));
            case "different creation" -> intent.addProperty("created", authority.trialEnd() + 1);
            case "foreign card" -> card.addProperty("customer", "cus_foreign");
            case "wrong card type" -> card.addProperty("type", "us_bank_account");
            default -> throw new AssertionError(fault);
        }
        http.reply(intent.toString());
        http.reply(card.toString());
        assertThatThrownBy(() -> OwnedOptionalTrialSetup.confirmedCard(client, authority, OPERATION, "seti_owned", authority.trialEnd()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(http.calls).allMatch(call -> call.method().name().equals("GET"));
    }

    @Test
    void updatesOnlyTheSubscriptionDefaultWithTheConfirmedCard() throws Exception {
        http.reply(setupIntent(authority, OPERATION, "pm_owned").toString());
        http.reply(card(authority, "pm_owned").toString());
        var subscription = new StripeBillingOwnedSubscriptionFixture(TaxMode.INCLUSIVE, 2000).remote;
        subscription.addProperty("status", "paused");
        subscription.addProperty("default_payment_method", "pm_owned");
        subscription.addProperty("test_clock", authority.clockReference());
        http.reply(subscription.toString());

        var method = OwnedOptionalTrialSetup.confirmedCard(client, authority, OPERATION, "seti_owned", authority.trialEnd());
        OwnedOptionalTrialSetup.installDefault(client, authority, original, method, "owned:METHOD");

        assertThat(http.calls).extracting(call -> call.url().getPath())
            .containsExactly("/v1/setup_intents/seti_owned", "/v1/payment_methods/pm_owned", "/v1/subscriptions/sub_owned");
        assertThat(http.calls.getLast().params()).isEqualTo(Map.of("default_payment_method", "pm_owned"));
        assertThat(http.calls.getLast().options().getIdempotencyKey()).isEqualTo("owned:METHOD");
        assertThat(http.calls.getLast().options().getMaxNetworkRetries()).isZero();
    }

    static void queuePrepare(Transport http, OwnedOptionalTrialCreate.Authority a) {
        OwnedOptionalTrialPauseSdkTest.queuePaused(http, a, "");
        queueReady(http, a);
        http.reply(request -> {
            assertThat(request.method().name()).isEqualTo("POST");
            assertThat(request.params().get("metadata")).isInstanceOf(Map.class);
            var metadata = (Map<?,?>) request.params().get("metadata");
            return setupIntent(a, UUID.fromString((String) metadata.get("operation_id")), null).toString();
        });
        queueReady(http, a);
    }

    static void queueBrowserPermit(Transport http, OwnedOptionalTrialCreate.Authority a,
            OwnedOptionalTrialSetup.Prepared prepared, String clientSecret) {
        queueBrowserPermit(http, a, prepared.operation(), clientSecret);
    }

    static void queueBrowserPermit(Transport http, OwnedOptionalTrialCreate.Authority a, UUID operation, String clientSecret) {
        OwnedOptionalTrialPauseSdkTest.queuePaused(http, a, "");
        var intent = setupIntent(a, operation, null);
        intent.addProperty("client_secret", clientSecret);
        http.reply(intent.toString());
        if (clientSecret.startsWith("seti_owned_secret_")) queueReady(http, a);
    }

    static void queueComplete(Transport http, OwnedOptionalTrialCreate.Authority a, OwnedOptionalTrialSetup.Prepared prepared) {
        queueComplete(http, a, prepared.operation());
    }

    static void queueComplete(Transport http, OwnedOptionalTrialCreate.Authority a, UUID operation) {
        http.reply(setupIntent(a, operation, "pm_owned").toString());
        http.reply(card(a, "pm_owned").toString());
        OwnedOptionalTrialPauseSdkTest.queuePaused(http, a, "", "pm_owned", null);
        queueReady(http, a);
        var reply = new StripeBillingOwnedSubscriptionFixture(TaxMode.INCLUSIVE, 2000).remote;
        reply.addProperty("test_clock", a.clockReference());
        reply.addProperty("status", "paused");
        reply.addProperty("default_payment_method", "pm_owned");
        http.reply(reply.toString());
        OwnedOptionalTrialPauseSdkTest.queuePaused(http, a, "", "pm_owned", "pm_owned");
    }

    private static void queueReady(Transport http, OwnedOptionalTrialCreate.Authority a) {
        var clock = json("""
            {"object":"test_helpers.test_clock","livemode":false,"status":"ready","status_details":{}}
            """);
        clock.addProperty("id", a.clockReference());
        clock.addProperty("name", a.clockName());
        clock.addProperty("frozen_time", a.trialEnd());
        clock.addProperty("created", a.clockCreated());
        clock.addProperty("deletes_after", a.clockDeletesAfter());
        http.reply(clock.toString());
    }

    static JsonObject setupIntent(OwnedOptionalTrialCreate.Authority authority, UUID operation, String method) {
        var intent = json("""
            {"id":"seti_owned","object":"setup_intent","livemode":false,"usage":"off_session","payment_method_types":["card"],
             "metadata":{},"status":"requires_payment_method"}
            """);
        intent.addProperty("created", authority.trialEnd());
        intent.addProperty("customer", authority.customerReference());
        OwnedOptionalTrialSetup.metadata(authority, operation).forEach(intent.getAsJsonObject("metadata")::addProperty);
        if (method != null) {
            intent.addProperty("status", "succeeded");
            intent.addProperty("payment_method", method);
        }
        return intent;
    }

    static JsonObject card(OwnedOptionalTrialCreate.Authority authority, String method) {
        var card = json("{\"object\":\"payment_method\",\"livemode\":false,\"type\":\"card\",\"card\":{}}");
        card.addProperty("id", method);
        card.addProperty("customer", authority.customerReference());
        return card;
    }
}
