package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.google.gson.JsonObject;
import com.stripe.StripeClient;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture.Transport;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import dev.genericpayments.platform.support.StripeHttpFixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.json;
import static dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnedOptionalTrialPauseSdkTest {
    private final OwnedOptionalTrialCreate.Authority authority = authority();
    private final OwnedOptionalTrialCreate.Created original =
        new OwnedOptionalTrialCreate.Created("sub_owned", "si_owned", "in_trial", authority.frozen());

    @Test
    void readsOnlyTheOriginalPausedGraphThroughTheSdk() throws Exception {
        try (var http = new StripeHttpFixture()) {
            pausedReplies(authority, "").forEach(reply -> http.enqueue("GET", reply.path(), 200, reply.body()));

            OwnedOptionalTrialCreate.rereadPaused(client(http), authority, original, authority.trialEnd());

            assertThat(http.calls()).hasSize(13).allSatisfy(call -> {
                assertThat(call.method()).isEqualTo("GET");
                assertThat(call.form()).isEmpty();
                assertThat(call.header("Idempotency-Key")).isNull();
                assertThat(call.header("Stripe-Version")).isEqualTo("2026-09-30.endive");
            });
            assertThat(http.calls().get(3).query()).isEqualTo(Map.of("customer", "cus_owned", "status", "all", "limit", "2"));
            assertThat(http.calls().get(5).query()).isEqualTo(Map.of("customer", "cus_owned", "limit", "1"));
            assertThat(http.calls().get(6).query()).isEqualTo(Map.of("customer", "cus_owned", "limit", "2"));
            assertThat(http.calls().get(7).query()).isEqualTo(Map.of("expand[0]", "amount_paid_off_stripe"));
            assertThat(http.calls().get(9).query()).isEqualTo(Map.of("invoice", "in_trial", "limit", "1"));
            assertThat(http.calls().get(10).query()).isEqualTo(Map.of("customer", "cus_owned", "limit", "1"));
            assertThat(http.calls().get(11).query()).isEqualTo(Map.of("customer", "cus_owned", "limit", "1"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign clock", "wrong frozen time", "clock changed", "active clock advance", "foreign customer",
        "foreign listed subscription", "foreign retrieved subscription", "foreign item", "different trial period", "still trialing",
        "pause collection", "default method", "attached method", "extra invoice", "foreign invoice", "paid invoice", "foreign line",
        "unknown off-stripe payment", "allocation", "payment intent", "charge", "paginated charges"})
    void rejectsDisagreementWithTheAcknowledgedNoPaymentTrial(String fault) throws Exception {
        try (var http = new StripeHttpFixture()) {
            pausedReplies(authority, fault).forEach(reply -> http.enqueue("GET", reply.path(), 200, reply.body()));

            assertThatThrownBy(() -> OwnedOptionalTrialCreate.rereadPaused(client(http), authority, original, authority.trialEnd()))
                .isInstanceOfAny(IllegalStateException.class, PaymentApplicationException.class);
            assertThat(http.calls()).isNotEmpty().allMatch(call -> call.method().equals("GET"));
        }
    }

    @Test
    void anUnownedTimeNeverStartsAProviderRead() throws Exception {
        try (var http = new StripeHttpFixture()) {
            assertThatThrownBy(() -> OwnedOptionalTrialCreate.rereadPaused(client(http), authority, original, authority.trialEnd() - 1))
                .isInstanceOf(IllegalStateException.class);
            assertThat(http.calls()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsOneExactAttachedCardWithAnExplicitlyExpectedDefault(boolean selected) throws Exception {
        try (var http = new StripeHttpFixture()) {
            String method = "pm_expected";
            String expectedDefault = selected ? method : null;
            pausedReplies(authority, "", method, expectedDefault)
                .forEach(reply -> http.enqueue("GET", reply.path(), 200, reply.body()));

            OwnedOptionalTrialCreate.rereadPaused(client(http), authority, original, authority.trialEnd(), method, expectedDefault);

            assertThat(http.calls()).hasSize(13).allMatch(call -> call.method().equals("GET"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign attached method", "foreign method owner", "non-card method", "default method"})
    void refusesAnotherCardOrUnexpectedSelection(String fault) throws Exception {
        try (var http = new StripeHttpFixture()) {
            pausedReplies(authority, fault, "pm_expected", null)
                .forEach(reply -> http.enqueue("GET", reply.path(), 200, reply.body()));

            assertThatThrownBy(() -> OwnedOptionalTrialCreate.rereadPaused(client(http), authority, original,
                authority.trialEnd(), "pm_expected", null)).isInstanceOf(IllegalStateException.class);
        }
    }

    private static StripeClient client(StripeHttpFixture http) {
        return StripeClient.builder().setApiKey("sk_test_LOOPBACK").setApiBase(http.baseUri().toString())
            .setMaxNetworkRetries(0).build();
    }

    private static OwnedOptionalTrialCreate.Authority authority() {
        var fixture = new StripeBillingOwnedSubscriptionFixture(TaxMode.INCLUSIVE, 2000);
        var owner = fixture.f.owner;
        var ready = fixture.f.ready;
        return new OwnedOptionalTrialCreate.Authority(owner.run(), owner.intent(), owner.account().value(),
            owner.site().value(), owner.customer().value(), fixture.subscriptionId, fixture.operationId,
            fixture.terms.priceId().value(), fixture.terms.productId().value(), fixture.terms.priceKey(),
            OwnedOptionalTrialCreate.APPROVED_ACCOUNT, "stripe-sandbox-default", ready.reference(), owner.name(),
            "cus_owned", "price_owned", "prod_owned", fixture.operation.providerOperationKey(), ready.frozenTime(),
            ready.providerCreated(), ready.providerDeletesAfter(), ready.frozenTime() + 604_800, "a".repeat(64));
    }

    static void queuePaused(Transport http, OwnedOptionalTrialCreate.Authority authority, String fault) {
        queuePaused(http, authority, fault, null, null);
    }

    static void queuePaused(Transport http, OwnedOptionalTrialCreate.Authority authority, String fault,
                            String attachedMethod, String subscriptionDefault) {
        pausedReplies(authority, fault, attachedMethod, subscriptionDefault).forEach(reply -> http.reply(reply.body()));
    }

    private record Reply(String path, String body) {
        Reply(String path, JsonObject object) { this(path, object.toString()); }
    }

    private static List<Reply> pausedReplies(OwnedOptionalTrialCreate.Authority a, String fault) {
        return pausedReplies(a, fault, null, null);
    }

    private static List<Reply> pausedReplies(OwnedOptionalTrialCreate.Authority a, String fault,
                                             String attachedMethod, String subscriptionDefault) {
        var f = new StripeBillingOwnedSubscriptionFixture(TaxMode.INCLUSIVE, 2000);
        var clock = json(OwnedOptionalTrialClockAdvanceSdkReplies.ready(a,a.trialEnd()));
        var customer = f.customer;
        customer.add("invoice_settings", new JsonObject());
        customer.getAsJsonObject("metadata").addProperty("account_id", a.account().toString());
        customer.getAsJsonObject("metadata").addProperty("site_id", a.site().toString());
        customer.getAsJsonObject("metadata").addProperty("customer_id", a.customer().toString());
        var subscription = f.remote;
        a.metadata().forEach((key, value) -> subscription.getAsJsonObject("metadata").addProperty(key, value));
        subscription.addProperty("status", "paused");
        subscription.addProperty("created", a.frozen());
        subscription.addProperty("trial_start", a.frozen());
        subscription.addProperty("trial_end", a.trialEnd());
        subscription.addProperty("latest_invoice", "in_trial");
        subscription.addProperty("default_payment_method", subscriptionDefault);
        subscription.add("trial_settings", json("{\"end_behavior\":{\"missing_payment_method\":\"pause\"}}"));
        var item = f.item();
        item.addProperty("current_period_start", a.frozen());
        item.addProperty("current_period_end", a.trialEnd());
        var price = f.price();
        price.addProperty("active", true);
        price.getAsJsonObject("metadata").addProperty("local_price_id", a.price().toString());
        var listedSubscription = subscription.deepCopy();
        var invoice = f.invoice;
        invoice.addProperty("id", "in_trial");
        invoice.addProperty("status", "paid");
        for (var field : List.of("amount_due", "amount_remaining", "total", "subtotal")) invoice.addProperty(field, 0);
        var listedInvoice = invoice.deepCopy();
        var line = f.line;
        line.addProperty("invoice", "in_trial");
        line.addProperty("amount", 0);
        line.getAsJsonObject("period").addProperty("start", a.frozen());
        line.getAsJsonObject("period").addProperty("end", a.trialEnd());

        var card = json("{\"object\":\"payment_method\",\"livemode\":false,\"type\":\"card\",\"card\":{}}");
        card.addProperty("id", attachedMethod);
        card.addProperty("customer", a.customerReference());
        var finalClock = clock.deepCopy();
        switch (fault) {
            case "foreign clock" -> clock.addProperty("id", "clock_foreign");
            case "wrong frozen time" -> clock.addProperty("frozen_time", a.trialEnd() - 1);
            case "clock changed" -> finalClock.addProperty("frozen_time", a.trialEnd() + 1);
            case "active clock advance" -> finalClock.getAsJsonObject("status_details")
                .add("advancing", json("{\"target_frozen_time\":1700604801}"));
            case "foreign customer" -> customer.addProperty("test_clock", "clock_foreign");
            case "foreign listed subscription" -> {
                listedSubscription.addProperty("id", "sub_foreign");
                listedSubscription.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject()
                    .addProperty("subscription", "sub_foreign");
            }
            case "foreign retrieved subscription" -> {
                subscription.addProperty("id", "sub_foreign");
                item.addProperty("subscription", "sub_foreign");
            }
            case "foreign item" -> item.addProperty("id", "si_foreign");
            case "different trial period" -> item.addProperty("current_period_end", a.trialEnd() + 1);
            case "still trialing" -> subscription.addProperty("status", "trialing");
            case "pause collection" -> subscription.add("pause_collection", json("{\"behavior\":\"void\"}"));
            case "default method" -> subscription.addProperty("default_payment_method", "pm_card");
            case "foreign attached method" -> card.addProperty("id", "pm_foreign");
            case "foreign method owner" -> card.addProperty("customer", "cus_foreign");
            case "non-card method" -> card.addProperty("type", "us_bank_account");
            case "foreign invoice" -> listedInvoice.addProperty("test_clock", "clock_foreign");
            case "paid invoice" -> invoice.addProperty("amount_paid", 2000);
            case "unknown off-stripe payment" -> invoice.remove("amount_paid_off_stripe");
            case "foreign line" -> line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details")
                .addProperty("subscription_item", "si_foreign");
            default -> { }
        }
        var clockPath = "/v1/test_helpers/test_clocks/" + a.clockReference();
        var methods = attachedMethod != null || fault.equals("attached method") ? page(card) : page();
        return List.of(
            new Reply(clockPath, clock),
            new Reply("/v1/customers/" + a.customerReference(), customer),
            new Reply("/v1/prices/" + a.priceReference(), price),
            new Reply("/v1/subscriptions", page(listedSubscription)),
            new Reply("/v1/subscriptions/sub_owned", subscription),
            new Reply("/v1/payment_methods", methods),
            new Reply("/v1/invoices", fault.equals("extra invoice") ? page(listedInvoice, json("{\"id\":\"in_extra\"}"))
                : page(listedInvoice)),
            new Reply("/v1/invoices/in_trial", invoice),
            new Reply("/v1/invoices/in_trial/lines", page(line)),
            new Reply("/v1/invoice_payments", fault.equals("allocation") ? page(f.allocation) : page()),
            new Reply("/v1/payment_intents", fault.equals("payment intent") ? page(f.pi) : page()),
            new Reply("/v1/charges", fault.equals("paginated charges") ? "{\"object\":\"list\",\"has_more\":true,\"data\":[]}"
                : fault.equals("charge") ? page(f.charge) : page()),
            new Reply(clockPath, finalClock));
    }
}
