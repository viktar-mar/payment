package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.support.StripeBillingOwnedSubscriptionFixture;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.SettlementKind;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    @ParameterizedTest
    @CsvSource({
        "billing_cycle_anchor,STRIPE_BILLING_CYCLE_ANCHOR",
        "billing_cycle_anchor[type],STRIPE_BILLING_CYCLE_ANCHOR",
        "payment_behavior,STRIPE_PAYMENT_BEHAVIOR",
        "proration_behavior,STRIPE_PRORATION_BEHAVIOR",
        "private_parameter,STRIPE_INVALID_REQUEST",
        ",STRIPE_INVALID_REQUEST"
    })
    void rejectedResumeParametersAreLimitedToKnownFieldNames(String parameter, OwnedOptionalTrialResume.Stage stage) {
        var failure = new InvalidRequestException("PRIVATE_MESSAGE",parameter,"req_PRIVATE","PRIVATE_CODE",400,null);
        assertThat(OwnedOptionalTrialResume.rejectedResumeParameter(failure)).isEqualTo(stage);
    }

    @Test void resumeWireLeavesTheInvoiceForTheSeparatePaymentStep() {
        f.f.http.disconnect();
        var client = f.f.clients.create(f.f.credentials.resolve(f.f.account));
        var graph = new OwnedOptionalTrialResumeGraph(client, authority, original, frozen, () -> {});
        assertThatThrownBy(() -> graph.dispatch("pm_trial", "owned-resume-key")).isInstanceOf(StripeException.class);
        assertThat(f.f.http.calls).singleElement().satisfies(call -> {
            assertThat(call.method().name()).isEqualTo("POST");
            assertThat(call.url().getPath()).isEqualTo("/v1/subscriptions/sub_owned/resume");
            assertThat(call.params()).containsOnlyKeys("billing_cycle_anchor","payment_behavior","proration_behavior")
                .containsEntry("payment_behavior","resume_on_payment_attempt")
                .containsEntry("proration_behavior","none");
            assertThat(call.params().get("billing_cycle_anchor")).isEqualTo(java.util.Map.of("type","now"));
            assertThat(call.options().getIdempotencyKey()).isEqualTo("owned-resume-key");
            assertThat(call.options().getMaxNetworkRetries()).isZero();
        });
    }

    @ParameterizedTest
    @CsvSource({
        "missing invoice,INVOICE_INVALID",
        "old invoice,INVOICE_ORIGINAL",
        "foreign customer,STRUCTURE",
        "unexpected status,STATUS",
        "foreign method,OWNER",
        "pending update,PENDING_UPDATE",
        "wrong period,PERIOD",
    })
    void dispatchRefusesChangedResponseWithOnlyTheExactCheckpoint(String fault, String checkpoint) {
        var response = f.remote;
        response.addProperty("status","paused");
        response.addProperty("latest_invoice","in_new");
        response.addProperty("default_payment_method","pm_trial");
        response.addProperty("trial_start",authority.frozen());
        response.addProperty("trial_end",frozen);
        response.add("trial_settings",StripeBillingOwnedSubscriptionFixture.json(
            "{\"end_behavior\":{\"missing_payment_method\":\"pause\"}}"));
        authority.metadata().forEach(response.getAsJsonObject("metadata")::addProperty);
        f.item().addProperty("current_period_start",authority.frozen());
        f.item().addProperty("current_period_end",frozen);
        f.price().addProperty("active",true);
        switch (fault) {
            case "missing invoice" -> response.remove("latest_invoice");
            case "old invoice" -> response.addProperty("latest_invoice",original.invoice());
            case "foreign customer" -> response.addProperty("customer","cus_PRIVATE");
            case "unexpected status" -> response.addProperty("status","past_due");
            case "foreign method" -> response.addProperty("default_payment_method","pm_PRIVATE");
            case "pending update" -> response.add("pending_update",new com.google.gson.JsonObject());
            case "wrong period" -> f.item().addProperty("current_period_end",frozen+1);
            default -> throw new AssertionError(fault);
        }
        f.f.http.reply(response.toString());
        var client = f.f.clients.create(f.f.credentials.resolve(f.f.account));
        var graph = new OwnedOptionalTrialResumeGraph(client,authority,original,frozen,() -> {});
        assertThatThrownBy(() -> graph.dispatch("pm_trial","owned-resume-key"))
            .hasMessage("OPTIONAL_TRIAL_RESUME_RESPONSE_" + checkpoint).hasNoCause();
        assertThat(f.f.http.calls).hasSize(1);
    }

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
