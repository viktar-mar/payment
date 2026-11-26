package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.google.gson.JsonObject;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.support.StripeBillingInertSdkFixture.Transport;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialCreate.Authority;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialCreate.Created;
import static dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.json;
import static dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.page;

final class OwnedOptionalTrialResumeSdkReplies {
    private static final String INVOICE = "in_resume";
    private static final String ALLOCATION = "inpay_resume";
    private static final String INTENT = "pi_resume";
    private static final String CHARGE = "ch_resume";

    static void dispatch(Transport http, Authority a, Created original, UUID setup, String setupReference,
            String method, String fault) {
        var data = new Responses(a, original, setup, setupReference, method, false);
        data.ownerGraph(http, original.invoice(), fault);
        if (fault.equals("unrelated customer invoice")) return;
        http.reply(page()); // No pre-existing intent
        http.reply(page()); // No pre-existing cash
        http.reply(data.clock.toString());
        var beforeDispatch = data.clock.deepCopy();
        if (fault.equals("late clock advance")) beforeDispatch.addProperty("frozen_time", a.trialEnd() + 1);
        http.reply(beforeDispatch.toString());
        if (fault.equals("late clock advance")) return;
        if (fault.equals("lost ack")) {
            http.disconnect();
            return;
        }
        http.reply(data.remote(INVOICE).toString());
        data.settlement(http, fault);
        if (!fault.isEmpty()) return;
        http.reply(data.clock.toString());
    }

    static void observation(Transport http, Authority a, Created original, UUID setup, String setupReference,
            String method, boolean paid, String fault) {
        var data = new Responses(a, original, setup, setupReference, method, paid);
        data.ownerGraph(http, INVOICE, fault);
        if (fault.equals("unrelated customer invoice")) return;
        data.settlement(http, fault);
        if (!fault.isEmpty()) return;
        http.reply(data.clock.toString());
    }

    static void confirmation(Transport http, Authority a, Created original, UUID setup, String setupReference,
            String method, String fault) {
        var data = new Responses(a, original, setup, setupReference, method, false);
        data.ownerGraph(http, INVOICE, fault);
        if (fault.equals("unrelated customer invoice")) return;
        data.settlement(http, fault);
        if (List.of("wrong anchor", "wrong period", "substituted allocation").contains(fault)) return;
        var originalIntent = data.paymentIntent(false);
        if (fault.equals("action required")) originalIntent.addProperty("status", "requires_action");
        http.reply(originalIntent.toString());
        if (fault.equals("action required")) return;
        http.reply(data.clock.toString());
        data.settlement(http, fault.equals("late substituted allocation") ? "substituted allocation" : "");
        if (fault.equals("late substituted allocation")) return;
        http.reply(originalIntent.toString());
        var dispatchClock = data.clock.deepCopy();
        if (fault.equals("late clock advance")) dispatchClock.addProperty("frozen_time", a.trialEnd() + 1);
        http.reply(dispatchClock.toString());
        if (fault.equals("late clock advance")) return;
        if (fault.equals("lost ack")) {
            http.disconnect();
            return;
        }
        var paidInvoice = data.invoice(INVOICE, "subscription_cycle", 2000, true, a.trialEnd());
        if (fault.equals("wrong reply")) paidInvoice.addProperty("id", "in_other");
        http.reply(paidInvoice.toString());
        if (fault.equals("wrong reply")) return;
        var succeededIntent = data.paymentIntent(true);
        if (fault.equals("wrong charge")) succeededIntent.addProperty("latest_charge", "ch_other");
        http.reply(succeededIntent.toString());
    }

    private static final class Responses {
        private final StripeBillingOwnedSubscriptionFixture f = new StripeBillingOwnedSubscriptionFixture(TaxMode.INCLUSIVE, 2000);
        private final Authority a;
        private final Created original;
        private final UUID setup;
        private final String setupReference;
        private final String method;
        private final boolean paid;
        private final long end;
        private final JsonObject clock;

        Responses(Authority a, Created original, UUID setup, String setupReference, String method, boolean paid) {
            this.a = a;
            this.original = original;
            this.setup = setup;
            this.setupReference = setupReference;
            this.method = method;
            this.paid = paid;
            this.end = Instant.ofEpochSecond(a.trialEnd()).atOffset(ZoneOffset.UTC).plusMonths(1).toEpochSecond();

            clock = json(f.f.clockJson());
            clock.addProperty("id", a.clockReference());
            clock.addProperty("name", a.clockName());
            clock.addProperty("frozen_time", a.trialEnd());
            clock.addProperty("created", a.clockCreated());
            clock.addProperty("deletes_after", a.clockDeletesAfter());
        }

        JsonObject remote(String latest) {
            var remote = f.remote.deepCopy();
            remote.addProperty("id", original.subscription());
            remote.addProperty("customer", a.customerReference());
            remote.addProperty("test_clock", a.clockReference());
            remote.addProperty("status", paid ? "active" : "paused");
            remote.addProperty("trial_start", a.frozen());
            remote.addProperty("trial_end", a.trialEnd());
            remote.addProperty("latest_invoice", latest);
            if (!paid && !original.invoice().equals(latest)) {
                var pending = new JsonObject();
                pending.addProperty("expires_at", a.trialEnd() + 23 * 60 * 60);
                pending.addProperty("billing_cycle_anchor", a.trialEnd());
                remote.add("pending_update", pending);
            }
            remote.addProperty("default_payment_method", method);
            remote.add("trial_settings", json("{\"end_behavior\":{\"missing_payment_method\":\"pause\"}}"));
            a.metadata().forEach((key, value) -> remote.getAsJsonObject("metadata").addProperty(key, value));
            var item = remote.getAsJsonObject("items").getAsJsonArray("data").get(0).getAsJsonObject();
            item.addProperty("id", original.item());
            item.addProperty("subscription", original.subscription());
            item.addProperty("current_period_start", paid ? a.trialEnd() : a.frozen());
            item.addProperty("current_period_end", paid ? end : a.trialEnd());
            var price = item.getAsJsonObject("price");
            price.addProperty("id", a.priceReference());
            price.addProperty("product", a.productReference());
            price.addProperty("active", true);
            price.getAsJsonObject("metadata").addProperty("local_price_id", a.price().toString());
            return remote;
        }

        void ownerGraph(Transport http, String latest, String fault) {
            var customer = f.customer.deepCopy();
            customer.addProperty("id", a.customerReference());
            customer.addProperty("test_clock", a.clockReference());
            customer.getAsJsonObject("metadata").addProperty("account_id", a.account().toString());
            customer.getAsJsonObject("metadata").addProperty("site_id", a.site().toString());
            customer.getAsJsonObject("metadata").addProperty("customer_id", a.customer().toString());
            customer.add("invoice_settings", new JsonObject());
            var remote = remote(latest);
            var setupIntent = json("{\"object\":\"setup_intent\",\"livemode\":false,\"status\":\"succeeded\","
                + "\"usage\":\"off_session\",\"payment_method_types\":[\"card\"],\"metadata\":{}}");
            setupIntent.addProperty("id", setupReference);
            setupIntent.addProperty("customer", a.customerReference());
            setupIntent.addProperty("payment_method", method);
            a.metadata().forEach((key,value) -> setupIntent.getAsJsonObject("metadata").addProperty(key, value));
            setupIntent.getAsJsonObject("metadata").addProperty("operation_id", setup.toString());
            var card = json("{\"object\":\"payment_method\",\"livemode\":false,\"type\":\"card\",\"card\":{}}");
            card.addProperty("id", method);
            card.addProperty("customer", a.customerReference());
            var trialInvoice = invoice(original.invoice(), "subscription_create", 0, true, a.frozen());
            var trialLine = line(original.invoice(), 0, a.frozen(), a.trialEnd());

            http.reply(clock.toString());
            http.reply(customer.toString());
            http.reply(page(remote));
            http.reply(remote.toString());
            http.reply(setupIntent.toString());
            http.reply(page(card));
            var renewal = invoice(INVOICE, "subscription_cycle", 2000, paid, a.trialEnd());
            if (fault.equals("unrelated customer invoice")) {
                var other = renewal.deepCopy();
                other.addProperty("id", "in_unrelated");
                other.getAsJsonObject("parent").getAsJsonObject("subscription_details").addProperty("subscription", "sub_other");
                http.reply(latest.equals(original.invoice()) ? page(trialInvoice, other) : page(trialInvoice, renewal, other));
                return;
            }
            http.reply(latest.equals(original.invoice()) ? page(trialInvoice) : page(trialInvoice, renewal));
            http.reply(trialInvoice.toString());
            http.reply(page(trialLine));
            http.reply(page());
        }

        private JsonObject invoice(String id, String reason, int amount, boolean isPaid, long created) {
            var invoice = f.invoice.deepCopy();
            invoice.addProperty("id", id);
            invoice.addProperty("customer", a.customerReference());
            invoice.addProperty("test_clock", a.clockReference());
            invoice.addProperty("billing_reason", reason);
            invoice.addProperty("created", created);
            invoice.addProperty("status", isPaid ? "paid" : "open");
            invoice.addProperty("amount_paid", isPaid ? amount : 0);
            invoice.addProperty("amount_remaining", isPaid ? 0 : amount);
            for (var field : List.of("amount_due", "total", "subtotal")) invoice.addProperty(field, amount);
            invoice.getAsJsonObject("parent").getAsJsonObject("subscription_details").addProperty("subscription", original.subscription());
            return invoice;
        }

        private JsonObject line(String invoice, int amount, long start, long end) {
            var line = f.line.deepCopy();
            line.addProperty("invoice", invoice);
            line.addProperty("subscription", original.subscription());
            line.addProperty("amount", amount);
            line.getAsJsonObject("period").addProperty("start", start);
            line.getAsJsonObject("period").addProperty("end", end);
            var owner = line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details");
            owner.addProperty("subscription", original.subscription());
            owner.addProperty("subscription_item", original.item());
            var price = line.getAsJsonObject("pricing").getAsJsonObject("price_details");
            price.addProperty("price", a.priceReference());
            price.addProperty("product", a.productReference());
            return line;
        }

        JsonObject paymentIntent(boolean succeeded) {
            var intent = f.pi.deepCopy();
            intent.addProperty("id", INTENT);
            intent.addProperty("customer", a.customerReference());
            intent.addProperty("status", succeeded ? "succeeded" : "requires_confirmation");
            intent.addProperty("amount_received", succeeded ? 2000 : 0);
            intent.addProperty("amount_capturable", 0);
            intent.addProperty("payment_method", method);
            if (succeeded) intent.addProperty("latest_charge", CHARGE); else intent.remove("latest_charge");
            return intent;
        }

        void settlement(Transport http, String fault) {
            var invoice = invoice(INVOICE, "subscription_cycle", 2000, paid, fault.equals("wrong anchor") ? a.trialEnd() - 1 : a.trialEnd());
            http.reply(invoice.toString());
            if (fault.equals("wrong anchor")) return;
            http.reply(page(line(INVOICE, 2000, a.trialEnd(), fault.equals("wrong period") ? end + 1 : end)));
            if (fault.equals("wrong period")) return;

            var allocation = f.allocation.deepCopy();
            allocation.addProperty("id", fault.equals("substituted allocation") ? "inpay_other" : ALLOCATION);
            allocation.addProperty("invoice", INVOICE);
            allocation.addProperty("status", paid ? "paid" : "open");
            allocation.addProperty("is_default", true);
            allocation.addProperty("amount_paid", paid ? 2000 : 0);
            allocation.getAsJsonObject("payment").addProperty("payment_intent", INTENT);
            allocation.add("status_transitions", json(paid ? "{\"paid_at\":" + a.trialEnd() + ",\"canceled_at\":null}"
                : "{\"paid_at\":null,\"canceled_at\":null}"));
            http.reply(page(allocation));
            if (fault.equals("substituted allocation")) return;
            http.reply(page(allocation));

            http.reply(page(paymentIntent(paid)));
            if (!paid) {
                http.reply(page());
                return;
            }
            var charge = f.charge.deepCopy();
            charge.addProperty("id", CHARGE);
            charge.addProperty("customer", a.customerReference());
            charge.addProperty("payment_intent", INTENT);
            charge.addProperty("payment_method", method);
            charge.addProperty("disputed", false);
            http.reply(page(charge));
            http.reply(page());
        }
    }

    private OwnedOptionalTrialResumeSdkReplies() {}
}
