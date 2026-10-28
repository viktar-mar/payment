package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;

import com.stripe.exception.ApiConnectionException;
import com.stripe.net.RequestOptions;
import com.stripe.param.*;
import com.stripe.param.billingportal.SessionCreateParams;
import org.junit.jupiter.api.Test;

class StripeBillingSdkContractTest {
    // The pinned resume API uses an object anchor and success-only resume, not the old scalar shape.
    @Test void resumesOnlyOnPaymentSuccessWithNewAnchorAndNoProration()throws Exception{
        try(var http=new StripeBillingHttpFixture()){
            http.respond("POST","/v1/subscriptions/sub_resume/resume","{\"id\":\"sub_resume\",\"object\":\"subscription\",\"status\":\"paused\",\"latest_invoice\":\"in_resume\"}");
            var result=http.client().v1().subscriptions().resume("sub_resume",SubscriptionResumeParams.builder()
                .setBillingCycleAnchor(SubscriptionResumeParams.BillingCycleAnchor.builder().setType(SubscriptionResumeParams.BillingCycleAnchor.Type.NOW).build())
                .setPaymentBehavior(SubscriptionResumeParams.PaymentBehavior.RESUME_ON_PAYMENT_SUCCESS)
                .setProrationBehavior(SubscriptionResumeParams.ProrationBehavior.NONE).build(),RequestOptions.builder().setIdempotencyKey("original-resume").build());
            assertThat(result.getLatestInvoice()).isEqualTo("in_resume");assertThat(result.getStatus()).isEqualTo("paused");
            assertThat(http.calls()).singleElement().satisfies(c->{assertThat(c.form()).containsEntry("billing_cycle_anchor[type]","now").containsEntry("payment_behavior","resume_on_payment_success").containsEntry("proration_behavior","none");assertThat(c.header("Idempotency-Key")).isEqualTo("original-resume");assertThat(c.header("Stripe-Version")).isEqualTo("2026-09-30.endive");});
        }
    }

    @Test void updatesOnlyExplicitSubscriptionDefaultAndDecodesSucceededSetupMethod()throws Exception{
        try(var http=new StripeBillingHttpFixture()){
            http.respond("GET","/v1/setup_intents/seti_owned","{\"id\":\"seti_owned\",\"object\":\"setup_intent\",\"livemode\":false,\"customer\":\"cus_owned\",\"usage\":\"off_session\",\"status\":\"succeeded\",\"payment_method\":\"pm_owned\",\"payment_method_types\":[\"card\"]}");
            var setup=http.client().v1().setupIntents().retrieve("seti_owned");assertThat(setup.getPaymentMethod()).isEqualTo("pm_owned");assertThat(setup.getUsage()).isEqualTo("off_session");
            http.respond("POST","/v1/subscriptions/sub_owned","{\"id\":\"sub_owned\",\"object\":\"subscription\",\"default_payment_method\":\"pm_owned\"}");
            var result=http.client().v1().subscriptions().update("sub_owned",SubscriptionUpdateParams.builder().setDefaultPaymentMethod("pm_owned").build(),RequestOptions.builder().setIdempotencyKey("original-method").build());
            assertThat(result.getDefaultPaymentMethod()).isEqualTo("pm_owned");assertThat(http.calls().getLast().form()).containsExactlyEntriesOf(java.util.Map.of("default_payment_method","pm_owned"));
        }
    }
    // Catches a fixture that drops requests or counts unrelated method/path pairs.
    @Test void countsOnlyMatchingBillingRequests() throws Exception {
        try (var http = new StripeBillingHttpFixture()) {
            http.respond("POST", "/v1/customers", "{\"id\":\"cus_billing\",\"object\":\"customer\"}");
            http.respond("GET", "/v1/invoices/in_billing", "{\"id\":\"in_billing\",\"object\":\"invoice\"}");
            assertThat(http.client().v1().customers().create(CustomerCreateParams.builder().build()).getId())
                .isEqualTo("cus_billing");
            assertThat(http.client().v1().invoices().retrieve("in_billing").getId()).isEqualTo("in_billing");
            http.respond("POST", "/v1/customers", "{\"id\":\"cus_second\",\"object\":\"customer\"}");
            http.client().v1().customers().create(CustomerCreateParams.builder().build());
            assertThat(http.requestCount("POST", "/v1/customers")).isEqualTo(2);
            assertThat(http.requestCount("GET", "/v1/invoices/in_billing")).isEqualTo(1);
            assertThat(http.requestCount("GET", "/v1/customers")).isZero();
            assertThat(http.requestCount("POST", "/v1/invoices/in_billing")).isZero();
        }
    }

    // Catches pin drift and an SDK/transport retry that could replay a Billing write.
    @Test void pinsVersionAndDisablesRetries() throws Exception {
        try (var http = new StripeBillingHttpFixture()) {
            http.respond("POST", "/v1/customers", "{\"id\":\"cus_billing\",\"object\":\"customer\"}");
            http.client().v1().customers().create(CustomerCreateParams.builder().build());
            http.disconnectNext("POST", "/v1/subscriptions");
            assertThatThrownBy(() -> http.client().v1().subscriptions().create(createParams(),
                RequestOptions.builder().setIdempotencyKey("billing-fixture-create").build()))
                .isInstanceOf(ApiConnectionException.class);
            assertThat(http.requestCount("POST", "/v1/subscriptions")).isEqualTo(1);
            assertThat(http.calls()).hasSize(2).allSatisfy(call -> {
                assertThat(call.header("Stripe-Version")).isEqualTo("2026-09-30.endive");
                assertThat(call.header("User-Agent")).contains("JavaBindings/34.0.0");
                assertThat(call.header("Stripe-Account")).isNull();
            });
            assertThat(http.calls().get(1).header("Idempotency-Key")).isEqualTo("billing-fixture-create");
        }
    }

    // Catches loss of the invoice/subscription/payment join or partial-allocation amounts.
    @Test void decodesInvoicePaymentAllocation() throws Exception {
        try (var http = new StripeBillingHttpFixture()) {
            http.respond("GET", "/v1/invoices/in_billing", """
                {"id":"in_billing","object":"invoice","customer":"cus_billing","currency":"eur",
                 "status":"paid","amount_due":2000,"amount_paid":2000,"amount_remaining":0,
                 "amount_paid_off_stripe":0,
                 "parent":{"type":"subscription_details","subscription_details":{"subscription":"sub_billing"}},
                 "payments":{"object":"list","url":"/v1/invoice_payments","has_more":true,"data":[
                   {"id":"inpay_card","object":"invoice_payment","invoice":"in_billing","currency":"eur",
                    "amount_requested":1200,"amount_paid":1200,"is_default":true,"status":"paid",
                    "payment":{"type":"payment_intent","payment_intent":{
                      "id":"pi_billing","object":"payment_intent","customer":"cus_billing","currency":"eur",
                      "amount":2000,"amount_received":2000,"status":"succeeded","capture_method":"automatic",
                      "latest_charge":{"id":"ch_billing","object":"charge","payment_intent":"pi_billing",
                                       "amount":2000,"amount_captured":2000,"currency":"eur","paid":true,"captured":true}}}}]}}
                """);
            var invoice = http.client().v1().invoices().retrieve("in_billing");
            assertThat(invoice.getParent().getSubscriptionDetails().getSubscription()).isEqualTo("sub_billing");
            assertThat(invoice.getCustomer()).isEqualTo("cus_billing");
            assertThat(invoice.getAmountPaid()).isEqualTo(2000L);
            assertThat(invoice.getAmountPaidOffStripe()).isZero();
            assertThat(invoice.getPayments().getHasMore()).isTrue();
            var allocation = invoice.getPayments().getData().getFirst();
            assertThat(allocation.getInvoice()).isEqualTo("in_billing");
            assertThat(allocation.getCurrency()).isEqualTo("eur");
            assertThat(allocation.getAmountRequested()).isEqualTo(1200L);
            assertThat(allocation.getAmountPaid()).isEqualTo(1200L);
            assertThat(allocation.getPayment().getType()).isEqualTo("payment_intent");
            assertThat(allocation.getPayment().getPaymentIntent()).isEqualTo("pi_billing");
            var intent = allocation.getPayment().getPaymentIntentObject();
            assertThat(intent.getAmountReceived()).isEqualTo(2000L);
            assertThat(intent.getLatestChargeObject().getCaptured()).isTrue();
            assertThat(intent.getLatestChargeObject().getAmountCaptured()).isEqualTo(2000L);

            http.respond("GET", "/v1/invoice_payments", """
                {"object":"list","url":"/v1/invoice_payments","has_more":false,"data":[
                  {"id":"inpay_other","object":"invoice_payment","invoice":"in_billing","currency":"eur",
                   "amount_requested":800,"amount_paid":800,"status":"paid",
                   "payment":{"type":"payment_record","payment_record":"pyr_fixture"}}]}
                """);
            var page = http.client().v1().invoicePayments().list(InvoicePaymentListParams.builder()
                .setInvoice("in_billing").setStartingAfter("inpay_card").setLimit(10L).build());
            assertThat(page.getHasMore()).isFalse();
            var other = page.getData().getFirst();
            assertThat(other.getAmountPaid()).isEqualTo(800L);
            assertThat(other.getPayment().getType()).isEqualTo("payment_record");
            assertThat(other.getPayment().getPaymentRecord()).isEqualTo("pyr_fixture");
            assertThat(other.getPayment().getPaymentIntent()).isNull();
            assertThat(http.requestCount("GET", "/v1/invoice_payments")).isEqualTo(1);
        }
    }

    // Catches dropped confirmation-secret or SetupIntent/PaymentIntent next-action branches.
    @Test void decodesPaymentAndSetupActions() throws Exception {
        try (var http = new StripeBillingHttpFixture()) {
            http.respond("GET", "/v1/subscriptions/sub_billing", """
                {"id":"sub_billing","object":"subscription","customer":"cus_billing","status":"incomplete",
                 "latest_invoice":{"id":"in_billing","object":"invoice","customer":"cus_billing",
                   "confirmation_secret":{"type":"payment_intent","client_secret":"pi_billing_secret_fixture"}},
                 "pending_setup_intent":{"id":"seti_billing","object":"setup_intent","customer":"cus_billing",
                   "status":"requires_action","client_secret":"seti_billing_secret_fixture",
                   "next_action":{"type":"redirect_to_url","redirect_to_url":{
                     "url":"https://example.invalid/setup","return_url":"https://example.invalid/return"}}}}
                """);
            var subscription = http.client().v1().subscriptions().retrieve("sub_billing");
            var secret = subscription.getLatestInvoiceObject().getConfirmationSecret();
            assertThat(secret.getType()).isEqualTo("payment_intent");
            assertThat(secret.getClientSecret()).isEqualTo("pi_billing_secret_fixture");
            var setup = subscription.getPendingSetupIntentObject();
            assertThat(setup.getStatus()).isEqualTo("requires_action");
            assertThat(setup.getClientSecret()).isEqualTo("seti_billing_secret_fixture");
            assertThat(setup.getNextAction().getType()).isEqualTo("redirect_to_url");
            assertThat(setup.getNextAction().getRedirectToUrl().getReturnUrl()).isEqualTo("https://example.invalid/return");

            http.respond("GET", "/v1/payment_intents/pi_billing", """
                {"id":"pi_billing","object":"payment_intent","status":"requires_action",
                 "client_secret":"pi_billing_secret_fixture","next_action":{"type":"use_stripe_sdk",
                   "use_stripe_sdk":{"type":"stripe_3ds2_fingerprint"}}}
                """);
            var payment = http.client().v1().paymentIntents().retrieve("pi_billing");
            assertThat(payment.getStatus()).isEqualTo("requires_action");
            assertThat(payment.getNextAction().getType()).isEqualTo("use_stripe_sdk");
            assertThat(payment.getNextAction().getUseStripeSdk()).containsEntry("type", "stripe_3ds2_fingerprint");

            http.respond("POST", "/v1/setup_intents", """
                {"id":"seti_created","object":"setup_intent","customer":"cus_billing",
                 "usage":"off_session","status":"requires_payment_method","next_action":null,"client_secret":null}
                """);
            var created = http.client().v1().setupIntents().create(SetupIntentCreateParams.builder()
                .setCustomer("cus_billing").setUsage(SetupIntentCreateParams.Usage.OFF_SESSION)
                .addAllowedPaymentMethodType(SetupIntentCreateParams.AllowedPaymentMethodType.CARD).build());
            assertThat(created.getNextAction()).isNull();
            assertThat(created.getClientSecret()).isNull();
            assertThat(http.calls().getLast().form()).containsEntry("allowed_payment_method_types[0]", "card")
                .containsEntry("customer", "cus_billing").containsEntry("usage", "off_session");
        }
    }

    // Catches future/absent shapes being silently replaced with a known actionable branch.
    @Test void preservesNullAndUnknownBillingShapes() throws Exception {
        try (var http = new StripeBillingHttpFixture()) {
            http.respond("GET", "/v1/invoices/in_unknown", """
                {"id":"in_unknown","object":"invoice","status":"future_status","parent":null,
                 "confirmation_secret":null,"payments":{"object":"list","has_more":false,"data":[]}}
                """);
            var invoice = http.client().v1().invoices().retrieve("in_unknown");
            assertThat(invoice.getStatus()).isEqualTo("future_status");
            assertThat(invoice.getParent()).isNull();
            assertThat(invoice.getConfirmationSecret()).isNull();
            assertThat(invoice.getPayments().getData()).isEmpty();
            http.respond("GET", "/v1/invoice_payments/inpay_unknown", """
                {"id":"inpay_unknown","object":"invoice_payment","amount_paid":null,
                 "payment":{"type":"future_payment","future_payment":{"id":"future_fixture"}}}
                """);
            var allocation = http.client().v1().invoicePayments().retrieve("inpay_unknown");
            assertThat(allocation.getAmountPaid()).isNull();
            assertThat(allocation.getPayment().getType()).isEqualTo("future_payment");
            assertThat(allocation.getPayment().getPaymentIntent()).isNull();
            http.respond("GET", "/v1/setup_intents/seti_unknown", """
                {"id":"seti_unknown","object":"setup_intent","status":"future_status","client_secret":null,
                 "next_action":{"type":"future_action","future_action":{}}}
                """);
            var setup = http.client().v1().setupIntents().retrieve("seti_unknown");
            assertThat(setup.getStatus()).isEqualTo("future_status");
            assertThat(setup.getClientSecret()).isNull();
            assertThat(setup.getNextAction().getType()).isEqualTo("future_action");
            assertThat(setup.getNextAction().getRedirectToUrl()).isNull();
            http.respond("GET", "/v1/subscriptions/sub_absent", """
                {"id":"sub_absent","object":"subscription","latest_invoice":null,"pending_setup_intent":null,
                 "pending_update":null,"schedule":null,"items":{"object":"list","has_more":false,"data":[
                   {"id":"si_absent","object":"subscription_item","current_period_start":null,"current_period_end":null}]}}
                """);
            var absent = http.client().v1().subscriptions().retrieve("sub_absent");
            assertThat(absent.getLatestInvoiceObject()).isNull();
            assertThat(absent.getPendingSetupIntentObject()).isNull();
            assertThat(absent.getPendingUpdate()).isNull();
            assertThat(absent.getSchedule()).isNull();
            assertThat(absent.getItems().getData().getFirst().getCurrentPeriodStart()).isNull();
            assertThat(absent.getItems().getData().getFirst().getCurrentPeriodEnd()).isNull();
        }
    }

    // Catches reading removed subscription-wide periods instead of the pinned item periods.
    @Test void retainsSubscriptionItemPeriods() throws Exception {
        try (var http = new StripeBillingHttpFixture()) {
            http.respond("GET", "/v1/subscriptions/sub_billing", subscriptionJson());
            var subscription = http.client().v1().subscriptions().retrieve("sub_billing");
            assertThat(subscription.getBillingMode().getType()).isEqualTo("classic");
            assertThat(subscription.getTrialEnd()).isEqualTo(1800000000L);
            assertThat(subscription.getItems().getHasMore()).isFalse();
            assertThat(subscription.getItems().getData()).singleElement().satisfies(item -> {
                assertThat(item.getId()).isEqualTo("si_billing");
                assertThat(item.getQuantity()).isEqualTo(1L);
                assertThat(item.getPrice().getId()).isEqualTo("price_billing");
                assertThat(item.getCurrentPeriodStart()).isEqualTo(1800000000L);
                assertThat(item.getCurrentPeriodEnd()).isEqualTo(1802678400L);
            });
            assertThat(subscription.getPendingUpdate().getExpiresAt()).isEqualTo(1800082800L);
            assertThat(subscription.getPendingUpdate().getTrialEnd()).isEqualTo(1800000000L);
            assertThat(subscription.getPendingUpdate().getSubscriptionItems()).singleElement().satisfies(item ->
                assertThat(item.getPrice().getId()).isEqualTo("price_next"));
            assertThat(subscription.getSchedule()).isEqualTo("sub_sched_billing");
        }
    }

    // Catches generated params no longer serializing the approved Billing/trial/pending-update policy.
    @Test void supportsClassicTrialAndPendingUpdateParameters() throws Exception {
        try (var http = new StripeBillingHttpFixture()) {
            http.respond("POST", "/v1/subscriptions", subscriptionJson());
            http.client().v1().subscriptions().create(createParams());
            assertThat(http.calls().getLast().form()).containsEntry("billing_mode[type]", "classic")
                .containsEntry("customer", "cus_billing").containsEntry("payment_behavior", "default_incomplete")
                .containsEntry("payment_settings[payment_method_types][0]", "card")
                .containsEntry("payment_settings[save_default_payment_method]", "on_subscription")
                .containsEntry("items[0][price]", "price_billing").containsEntry("items[0][quantity]", "1")
                .containsEntry("trial_end", "1800000000")
                .containsEntry("trial_settings[end_behavior][missing_payment_method]", "pause")
                .containsEntry("expand[0]", "latest_invoice.confirmation_secret")
                .containsEntry("expand[1]", "pending_setup_intent")
                .doesNotContainKeys("automatic_payment_methods[enabled]", "items[1][price]", "items[0][trial_end]");
            http.respond("POST", "/v1/subscriptions/sub_billing", subscriptionJson());
            http.client().v1().subscriptions().update("sub_billing", SubscriptionUpdateParams.builder()
                .setPaymentBehavior(SubscriptionUpdateParams.PaymentBehavior.PENDING_IF_INCOMPLETE)
                .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE)
                .setProrationDate(1800000100L)
                .addItem(SubscriptionUpdateParams.Item.builder().setId("si_billing").setPrice("price_next").setQuantity(1L).build())
                .build());
            assertThat(http.calls().getLast().form()).containsEntry("payment_behavior", "pending_if_incomplete")
                .containsEntry("proration_behavior", "always_invoice").containsEntry("proration_date", "1800000100")
                .containsEntry("items[0][id]", "si_billing").containsEntry("items[0][price]", "price_next")
                .containsEntry("items[0][quantity]", "1");
        }
    }

    // Catches schedule and Portal SDK endpoints or owned-resource params drifting at the wire boundary.
    @Test void supportsSchedulesAndPortalParameters() throws Exception {
        try (var http = new StripeBillingHttpFixture()) {
            String scheduleJson = """
                {"id":"sub_sched_billing","object":"subscription_schedule","customer":"cus_billing",
                 "subscription":"sub_billing","status":"active","end_behavior":"release",
                 "phases":[{"start_date":1800000000,"end_date":1802678400,
                   "items":[{"price":"price_billing","quantity":1}]}]}
                """;
            http.respond("POST", "/v1/subscription_schedules", scheduleJson);
            var schedule = http.client().v1().subscriptionSchedules().create(SubscriptionScheduleCreateParams.builder()
                .setFromSubscription("sub_billing").build());
            assertThat(schedule.getSubscription()).isEqualTo("sub_billing");
            assertThat(schedule.getPhases().getFirst().getEndDate()).isEqualTo(1802678400L);
            assertThat(http.calls().getLast().form()).containsEntry("from_subscription", "sub_billing");
            http.respond("POST", "/v1/subscription_schedules/sub_sched_billing", scheduleJson);
            http.client().v1().subscriptionSchedules().update("sub_sched_billing", SubscriptionScheduleUpdateParams.builder()
                .setEndBehavior(SubscriptionScheduleUpdateParams.EndBehavior.RELEASE)
                .setProrationBehavior(SubscriptionScheduleUpdateParams.ProrationBehavior.NONE)
                .addPhase(SubscriptionScheduleUpdateParams.Phase.builder().setStartDate(1800000000L).setEndDate(1802678400L)
                    .addItem(SubscriptionScheduleUpdateParams.Phase.Item.builder().setPrice("price_billing").setQuantity(1L).build()).build())
                .addPhase(SubscriptionScheduleUpdateParams.Phase.builder().setStartDate(1802678400L).setEndDate(1805270400L)
                    .addItem(SubscriptionScheduleUpdateParams.Phase.Item.builder().setPrice("price_next").setQuantity(1L).build()).build())
                .build());
            assertThat(http.calls().getLast().form()).containsEntry("end_behavior", "release")
                .containsEntry("proration_behavior", "none").containsEntry("phases[0][start_date]", "1800000000")
                .containsEntry("phases[0][end_date]", "1802678400").containsEntry("phases[0][items][0][price]", "price_billing")
                .containsEntry("phases[1][start_date]", "1802678400").containsEntry("phases[1][items][0][price]", "price_next")
                .containsEntry("phases[1][items][0][quantity]", "1");
            http.respond("POST", "/v1/subscription_schedules/sub_sched_billing/release", """
                {"id":"sub_sched_billing","object":"subscription_schedule","status":"released",
                 "subscription":null,"released_subscription":"sub_billing"}
                """);
            var released = http.client().v1().subscriptionSchedules().release("sub_sched_billing");
            assertThat(released.getStatus()).isEqualTo("released");
            assertThat(released.getSubscription()).isNull();
            assertThat(released.getReleasedSubscription()).isEqualTo("sub_billing");
            http.respond("POST", "/v1/billing_portal/sessions", """
                {"id":"bps_billing","object":"billing_portal.session","customer":"cus_billing",
                 "configuration":"bpc_billing","url":"https://example.invalid/portal",
                 "return_url":"https://example.invalid/return"}
                """);
            var portal = http.client().v1().billingPortal().sessions().create(SessionCreateParams.builder()
                .setCustomer("cus_billing").setConfiguration("bpc_billing").setReturnUrl("https://example.invalid/return").build());
            assertThat(portal.getCustomer()).isEqualTo("cus_billing");
            assertThat(portal.getConfiguration()).isEqualTo("bpc_billing");
            assertThat(portal.getUrl()).isEqualTo("https://example.invalid/portal");
            assertThat(http.calls().getLast().form()).containsEntry("customer", "cus_billing")
                .containsEntry("configuration", "bpc_billing").containsEntry("return_url", "https://example.invalid/return");
            assertThat(http.requestCount("POST", "/v1/subscription_schedules/sub_sched_billing/release")).isEqualTo(1);
            assertThat(http.requestCount("POST", "/v1/billing_portal/sessions")).isEqualTo(1);
        }
    }

    private static SubscriptionCreateParams createParams() {
        return SubscriptionCreateParams.builder().setCustomer("cus_billing")
            .setBillingMode(SubscriptionCreateParams.BillingMode.builder().setType(SubscriptionCreateParams.BillingMode.Type.CLASSIC).build())
            .setPaymentBehavior(SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
            .setPaymentSettings(SubscriptionCreateParams.PaymentSettings.builder()
                .addPaymentMethodType(SubscriptionCreateParams.PaymentSettings.PaymentMethodType.CARD)
                .setSaveDefaultPaymentMethod(SubscriptionCreateParams.PaymentSettings.SaveDefaultPaymentMethod.ON_SUBSCRIPTION).build())
            .addItem(SubscriptionCreateParams.Item.builder().setPrice("price_billing").setQuantity(1L).build())
            .setTrialEnd(1800000000L)
            .setTrialSettings(SubscriptionCreateParams.TrialSettings.builder().setEndBehavior(
                SubscriptionCreateParams.TrialSettings.EndBehavior.builder().setMissingPaymentMethod(
                    SubscriptionCreateParams.TrialSettings.EndBehavior.MissingPaymentMethod.PAUSE).build()).build())
            .addExpand("latest_invoice.confirmation_secret").addExpand("pending_setup_intent").build();
    }

    private static String subscriptionJson() {
        return """
            {"id":"sub_billing","object":"subscription","customer":"cus_billing","status":"active",
             "billing_mode":{"type":"classic"},"trial_end":1800000000,"schedule":"sub_sched_billing",
             "items":{"object":"list","has_more":false,"data":[
               {"id":"si_billing","object":"subscription_item","subscription":"sub_billing","quantity":1,
                "current_period_start":1800000000,"current_period_end":1802678400,
                "price":{"id":"price_billing","object":"price","currency":"eur","unit_amount":2000,
                  "recurring":{"interval":"month","interval_count":1}}}]},
             "pending_update":{"expires_at":1800082800,"trial_end":1800000000,"subscription_items":[
               {"id":"si_billing","object":"subscription_item","quantity":1,
                "price":{"id":"price_next","object":"price","currency":"eur","unit_amount":3000,
                  "recurring":{"interval":"month","interval_count":1}}}]}}
            """;
    }
}
