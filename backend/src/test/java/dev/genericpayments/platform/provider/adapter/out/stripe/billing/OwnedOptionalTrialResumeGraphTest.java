package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialCreate.*;
import static dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture.page;
import static org.assertj.core.api.Assertions.*;

class OwnedOptionalTrialResumeGraphTest {
    private final StripeBillingOwnedSubscriptionFixture f = new StripeBillingOwnedSubscriptionFixture(TaxMode.INCLUSIVE, 2000);
    private final long frozen = f.f.ready.frozenTime() + 604800L;
    private final long periodEnd = Instant.ofEpochSecond(frozen).atOffset(ZoneOffset.UTC).plusMonths(1).toEpochSecond();
    private final Authority authority = new Authority(f.f.owner.run(), f.f.owner.intent(), f.f.account.id().value(),
        f.f.site.value(), f.f.customer.value(), f.subscriptionId, f.operationId, f.terms.priceId().value(),
        f.terms.productId().value(), f.terms.priceKey(), APPROVED_ACCOUNT, f.f.account.credentialsRef(),
        f.f.ready.reference(), f.f.owner.name(), "cus_owned", "price_owned", "prod_owned", f.operation.providerOperationKey(),
        f.f.ready.frozenTime(), f.f.ready.providerCreated(), f.f.ready.providerDeletesAfter(), frozen, "a".repeat(64));
    private final Created original = new Created("sub_owned", "si_owned", "in_trial", authority.frozen());
    private final OwnedOptionalTrialResumeGraph.Identity identity =
        new OwnedOptionalTrialResumeGraph.Identity("in_owned", "inpay_owned", "pi_owned");

    @Test void paidEvidenceKeepsTheSameFrozenPeriodAndCaptureIdentity() throws Exception {
        queue(false, "");
        var pending = read(false, null);
        assertThat(pending.identity()).isEqualTo(identity);
        assertThat(pending.invoice().settlement()).isEqualTo(SettlementKind.OPEN);
        assertThat(pending.invoice().cashObjects()).isEmpty();

        queue(true, "");
        var paid = read(true, pending.identity());
        assertThat(paid.identity()).isEqualTo(identity);
        assertThat(paid.invoice().period()).isEqualTo(pending.invoice().period());
        assertThat(paid.invoice().settlement()).isEqualTo(SettlementKind.CASH);
        assertThat(paid.invoice().cashObjects()).singleElement().satisfies(cash -> {
            assertThat(cash.reference()).isEqualTo("pi_owned");
            assertThat(cash.amountMinor()).isEqualTo(2000);
            assertThat(cash.occurredAt()).isEqualTo(Instant.ofEpochSecond(frozen));
        });
        assertThat(f.f.http.calls).allMatch(call -> call.method().name().equals("GET")
            && call.options().getMaxNetworkRetries() == 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong clock", "wrong anchor", "wrong period", "wrong item", "other allocation",
        "other intent", "preperiod capture", "wrong charge method", "refunded charge"})
    void refusesDriftFromTheOriginalScopedGraph(String fault) {
        queue(true, fault);
        assertThatThrownBy(() -> read(true, identity)).isInstanceOf(IllegalStateException.class);
    }

    @Test void scopedCustomerInvoiceListRefusesAnUnrelatedInvoiceWithNoCash() throws Exception {
        var setup = UUID.randomUUID();
        OwnedOptionalTrialResumeSdkReplies.dispatch(f.f.http, authority, original, setup, "seti_trial", "pm_trial",
            "unrelated customer invoice");
        var client = f.f.clients.create(f.f.credentials.resolve(f.f.account));
        var graph = new OwnedOptionalTrialResumeGraph(client, authority, original, frozen, () -> {});
        graph.verifyReadyClock();
        assertThatThrownBy(() -> graph.verifySubscriptionAndSetup(setup, "seti_trial", "pm_trial", original.invoice(), false))
            .isInstanceOf(IllegalStateException.class);
        assertThat(f.f.http.replies).isEmpty();
        assertThat(f.f.http.calls.getLast().params()).containsEntry("customer", authority.customerReference())
            .doesNotContainKey("subscription");
        assertThat(f.f.http.calls).noneMatch(call -> call.method().name().equals("POST"));
    }

    private OwnedOptionalTrialResumeGraph.Settlement read(boolean paid, OwnedOptionalTrialResumeGraph.Identity expected)
            throws Exception {
        var client = f.f.clients.create(f.f.credentials.resolve(f.f.account));
        return new OwnedOptionalTrialResumeGraph(client, authority, original, frozen, () -> {})
            .readInvoice("in_owned", "pm_trial", paid, expected);
    }

    private void queue(boolean paid, String fault) {
        f.invoice.addProperty("billing_reason", "subscription_cycle");
        f.invoice.addProperty("created", fault.equals("wrong anchor") ? frozen - 1 : frozen);
        f.invoice.addProperty("test_clock", fault.equals("wrong clock") ? "clock_other" : authority.clockReference());
        f.invoice.addProperty("status", paid ? "paid" : "open");
        f.invoice.addProperty("amount_paid", paid ? 2000 : 0);
        f.invoice.addProperty("amount_remaining", paid ? 0 : 2000);
        f.line.getAsJsonObject("period").addProperty("start", frozen);
        f.line.getAsJsonObject("period").addProperty("end", fault.equals("wrong period") ? periodEnd + 1 : periodEnd);
        if (fault.equals("wrong item")) {
            f.line.getAsJsonObject("parent").getAsJsonObject("subscription_item_details").addProperty("subscription_item", "si_other");
        }
        f.allocation.addProperty("is_default", true);
        f.allocation.addProperty("status", paid ? "paid" : "open");
        f.allocation.addProperty("amount_paid", paid ? 2000 : 0);
        f.allocation.add("status_transitions", StripeBillingOwnedSubscriptionFixture.json(paid
            ? "{\"paid_at\":" + (fault.equals("preperiod capture") ? frozen - 1 : frozen) + ",\"canceled_at\":null}"
            : "{\"paid_at\":null,\"canceled_at\":null}"));
        if (fault.equals("other allocation")) f.allocation.addProperty("id", "inpay_other");
        if (fault.equals("other intent")) f.allocation.getAsJsonObject("payment").addProperty("payment_intent", "pi_other");
        f.pi.addProperty("status", paid ? "succeeded" : "requires_confirmation");
        f.pi.addProperty("amount_received", paid ? 2000 : 0);
        f.pi.addProperty("payment_method", "pm_trial");
        if (paid) f.pi.addProperty("latest_charge", "ch_owned"); else f.pi.remove("latest_charge");
        f.charge.addProperty("disputed", false);
        f.charge.addProperty("refunded", fault.equals("refunded charge"));
        f.charge.addProperty("payment_method", fault.equals("wrong charge method") ? "pm_other" : "pm_trial");

        f.f.http.reply(f.invoice.toString());
        f.f.http.reply(page(f.line));
        f.f.http.reply(page(f.allocation));
        f.f.http.reply(page(f.allocation));
        f.f.http.reply(page(f.pi));
        f.f.http.reply(paid ? page(f.charge) : page());
        if (paid) f.f.http.reply(page());
    }
}
