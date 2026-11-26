package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialResumeGraph.Identity;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.json;
import static org.assertj.core.api.Assertions.*;

class OwnedOptionalTrialPaymentConfirmationSdkTest {
    private final StripeBillingInertSdkFixture f = new StripeBillingInertSdkFixture();

    @Test void paysOnlyTheAdmittedInvoiceAndReadsItsOriginalIntent() throws Exception {
        f.http.reply(paidInvoice());
        f.http.reply(succeeded());
        assertThat(pay()).isEqualTo("ch_resume");
        assertThat(f.http.calls).hasSize(2);
        var post = f.http.calls.getFirst();
        assertThat(post.method().name()).isEqualTo("POST");
        assertThat(post.url().getPath()).isEqualTo("/v1/invoices/in_resume/pay");
        assertThat(post.params()).containsExactlyInAnyOrderEntriesOf(Map.of("payment_method", "pm_scoped", "off_session", true));
        assertThat(post.options().getIdempotencyKey()).isEqualTo("owned-resume:CONFIRM");
        assertThat(post.options().getMaxNetworkRetries()).isZero();
        assertThat(post.options().getConnectTimeout()).isEqualTo(2000);
        assertThat(post.options().getReadTimeout()).isEqualTo(8000);
        assertThat(post.headers().firstValue("Stripe-Version")).contains("2026-09-30.endive");
        var read = f.http.calls.getLast();
        assertThat(read.method().name()).isEqualTo("GET");
        assertThat(read.url().getPath()).isEqualTo("/v1/payment_intents/pi_resume");
        assertThat(read.options().getMaxNetworkRetries()).isZero();
        assertThat(read.options().getReadTimeout()).isEqualTo(8000);
        assertThat(read.options().getIdempotencyKey()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"different intent", "different customer", "different method", "other currency",
        "incomplete capture", "capturable", "manual capture", "action", "decline", "processing", "wrong object",
        "live", "wrong types", "expanded charge", "malformed expanded charge", "string amount", "duplicate amount"})
    void refusesAResponseThatDoesNotProveTheExactScopedConfirmation(String fault) {
        var body = json(succeeded());
        switch (fault) {
            case "different intent" -> body.addProperty("id", "pi_other");
            case "different customer" -> body.addProperty("customer", "cus_other");
            case "different method" -> body.addProperty("payment_method", "pm_other");
            case "other currency" -> body.addProperty("currency", "usd");
            case "incomplete capture" -> body.addProperty("amount_received", 1999);
            case "capturable" -> body.addProperty("amount_capturable", 2000);
            case "manual capture" -> body.addProperty("capture_method", "manual");
            case "action" -> body.add("next_action", json("{\"type\":\"use_stripe_sdk\"}"));
            case "decline" -> body.add("last_payment_error", json("{\"code\":\"card_declined\"}"));
            case "processing" -> body.addProperty("status", "processing");
            case "wrong object" -> body.addProperty("object", "setup_intent");
            case "live" -> body.addProperty("livemode", true);
            case "wrong types" -> body.add("payment_method_types", com.google.gson.JsonParser.parseString("[\"card\",\"link\"]"));
            case "expanded charge" -> body.add("latest_charge", json("{\"id\":\"ch_resume\",\"object\":\"charge\"}"));
            case "malformed expanded charge" -> body.add("latest_charge", json("{\"id\":\"ch_resume\"}"));
            case "string amount" -> body.addProperty("amount", "2000");
            case "duplicate amount" -> { /* The duplicate is appended to the raw response below. */ }
            default -> throw new AssertionError(fault);
        }
        f.http.reply(paidInvoice());
        f.http.reply(fault.equals("duplicate amount") ? body.toString().replace("\"amount\":2000", "\"amount\":2001,\"amount\":2000")
            : body.toString());
        assertThatThrownBy(this::pay).isInstanceOf(IllegalStateException.class);
        assertThat(f.http.calls).hasSize(2);
    }

    @Test void aBadOpenIntentReportsTheReadStageWithoutPostingOrLeakingItsContents() {
        var body = json(succeeded());
        body.addProperty("status","requires_action");
        body.addProperty("client_secret","PRIVATE_RESPONSE");
        f.http.reply(body.toString());
        var client = f.clients.create(f.credentials.resolve(f.account));
        assertThatThrownBy(() -> OwnedOptionalTrialPaymentConfirmation.verifyOpenIntent(client,
                "cus_scoped","pi_resume","pm_scoped"))
            .hasMessage("OPTIONAL_TRIAL_PAY_OPEN_INTENT").hasNoCause();
        assertThat(f.http.calls).singleElement().satisfies(call -> {
            assertThat(call.method().name()).isEqualTo("GET");
            assertThat(call.url().getPath()).isEqualTo("/v1/payment_intents/pi_resume");
        });
    }

    @ParameterizedTest
    @ValueSource(strings={"id","customer","currency","status","amount_paid","amount_remaining","test_clock","subscription"})
    void stopsOnWrongInvoiceReplyWithoutAnotherRequest(String fault) {
        var invoice = json(paidInvoice());
        switch (fault) {
            case "amount_paid" -> invoice.addProperty(fault,1999);
            case "amount_remaining" -> invoice.addProperty(fault,1);
            case "subscription" -> invoice.getAsJsonObject("parent").getAsJsonObject("subscription_details")
                .addProperty("subscription","sub_other");
            default -> invoice.addProperty(fault,"other");
        }
        f.http.reply(invoice.toString());
        assertThatThrownBy(this::pay).isInstanceOf(IllegalStateException.class);
        assertThat(f.http.calls).hasSize(1);
    }

    private String pay() throws Exception {
        var client = f.clients.create(f.credentials.resolve(f.account));
        return OwnedOptionalTrialPaymentConfirmation.postPay(client, "cus_scoped", "sub_scoped", "clock_scoped",
            new Identity("in_resume","inpay_resume","pi_resume"), "pm_scoped", "owned-resume:CONFIRM", () -> {});
    }

    private static String paidInvoice() {
        return """
            {"id":"in_resume","object":"invoice","livemode":false,"status":"paid","customer":"cus_scoped",
             "test_clock":"clock_scoped","currency":"eur","amount_due":2000,"amount_paid":2000,"amount_remaining":0,
             "parent":{"type":"subscription_details","subscription_details":{"subscription":"sub_scoped"}}}
            """;
    }

    private static String succeeded() {
        return """
            {"id":"pi_resume","object":"payment_intent","livemode":false,"status":"succeeded",
             "customer":"cus_scoped","payment_method":"pm_scoped","currency":"eur","amount":2000,"amount_received":2000,
             "amount_capturable":0,"capture_method":"automatic","payment_method_types":["card"],"latest_charge":"ch_resume"}
            """;
    }
}
