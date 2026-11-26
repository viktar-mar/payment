package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.HasId;
import com.stripe.model.InvoicePayment;
import com.stripe.model.StripeCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.*;
import dev.genericpayments.platform.payments.domain.PaymentStatus;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import dev.genericpayments.platform.subscriptions.domain.BillingRecord;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialCreate.Authority;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialCreate.Created;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialResume.Stage.*;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingTestClockAdapter.options;

/** Closed, clocked Stripe graph for the single owned optional trial. */
final class OwnedOptionalTrialResumeGraph {
    // Finalize the invoice here. The separately claimed PAY phase confirms its exact intent.
    static final SubscriptionResumeParams.PaymentBehavior PAYMENT_BEHAVIOR =
        SubscriptionResumeParams.PaymentBehavior.RESUME_ON_PAYMENT_ATTEMPT;

    record Identity(String invoice, String allocation, String intent) {}
    record Settlement(Identity identity, Invoice invoice) {}

    private final StripeClient client;
    private final Authority authority;
    private final Created original;
    private final long frozen;
    private final Runnable current;
    private final RequestOptions read = options(null);

    OwnedOptionalTrialResumeGraph(StripeClient client, Authority authority, Created original, long frozen, Runnable current) {
        this.client = Objects.requireNonNull(client);
        this.authority = Objects.requireNonNull(authority);
        this.original = Objects.requireNonNull(original);
        this.current = Objects.requireNonNull(current);
        this.frozen = frozen;
        require(frozen == authority.trialEnd() && original.trialStart() == authority.frozen());
    }

    void verifyReadyClock() throws StripeException {
        var clock = call(() -> client.v1().testHelpers().testClocks().retrieve(authority.clockReference(), read));
        require(authority.clockReference().equals(clock.getId()) && "test_helpers.test_clock".equals(clock.getObject())
            && Boolean.FALSE.equals(clock.getLivemode()) && !Boolean.TRUE.equals(clock.getDeleted())
            && authority.clockName().equals(clock.getName()) && "ready".equals(clock.getStatus())
            && (clock.getStatusDetails() == null || clock.getStatusDetails().getAdvancing() == null)
            && equal(frozen, clock.getFrozenTime()) && equal(authority.clockCreated(), clock.getCreated())
            && equal(authority.clockDeletesAfter(), clock.getDeletesAfter()));
    }

    boolean verifySubscriptionAndSetup(UUID setupOperation, String setupReference, String method, String latest, Boolean expectedPaid)
            throws StripeException {
        var customer = call(() -> client.v1().customers().retrieve(authority.customerReference(), read));
        require(authority.customerReference().equals(customer.getId()) && "customer".equals(customer.getObject())
            && Boolean.FALSE.equals(customer.getLivemode()) && !Boolean.TRUE.equals(customer.getDeleted())
            && authority.clockReference().equals(customer.getTestClock()) && customer.getDefaultSource() == null
            && customer.getInvoiceSettings() != null && customer.getInvoiceSettings().getDefaultPaymentMethod() == null
            && customer.getMetadata() != null && authority.account().toString().equals(customer.getMetadata().get("account_id"))
            && authority.site().toString().equals(customer.getMetadata().get("site_id"))
            && authority.customer().toString().equals(customer.getMetadata().get("customer_id")));
        var subscriptions = call(() -> client.v1().subscriptions().list(SubscriptionListParams.builder()
            .setCustomer(authority.customerReference()).setStatus(SubscriptionListParams.Status.ALL).setLimit(2L).build(), read));
        var listed = only(subscriptions, 1).getFirst();
        var remote = call(() -> client.v1().subscriptions().retrieve(original.subscription(), read));
        boolean paid = "active".equals(remote.getStatus());
        require(expectedPaid == null || expectedPaid == paid);
        verifySubscription(listed, method, latest, paid);
        verifySubscription(remote, method, latest, paid);
        var setup = call(() -> client.v1().setupIntents().retrieve(setupReference, read));
        var metadata = new HashMap<>(authority.metadata());
        metadata.put("operation_id", setupOperation.toString());
        require(setupReference.equals(setup.getId()) && "setup_intent".equals(setup.getObject())
            && Boolean.FALSE.equals(setup.getLivemode()) && authority.customerReference().equals(setup.getCustomer())
            && "off_session".equals(setup.getUsage()) && "succeeded".equals(setup.getStatus())
            && List.of("card").equals(setup.getPaymentMethodTypes()) && method.equals(setup.getPaymentMethod())
            && setup.getMetadata() != null && setup.getMetadata().entrySet().containsAll(metadata.entrySet()));
        var methods = call(() -> client.v1().paymentMethods().list(PaymentMethodListParams.builder()
            .setCustomer(authority.customerReference()).setLimit(2L).build(), read));
        var card = only(methods, 1).getFirst();
        require(method.equals(card.getId()) && "payment_method".equals(card.getObject()) && Boolean.FALSE.equals(card.getLivemode())
            && authority.customerReference().equals(card.getCustomer()) && "card".equals(card.getType()) && card.getCard() != null);

        var invoices = call(() -> client.v1().invoices().list(InvoiceListParams.builder()
            .setCustomer(authority.customerReference()).setLimit(3L).build(), read));
        var expected = latest.equals(original.invoice()) ? Set.of(original.invoice()) : Set.of(original.invoice(), latest);
        var source = only(invoices, expected.size());
        require(new HashSet<>(source.stream().map(com.stripe.model.Invoice::getId).toList()).equals(expected));
        for (var invoice : source) {
            boolean trial = original.invoice().equals(invoice.getId());
            require(invoiceBelongs(invoice) && (trial ? "subscription_create" : "subscription_cycle").equals(invoice.getBillingReason())
                && (trial || paid ? "paid" : "open").equals(invoice.getStatus()));
        }
        current.run();
        OwnedOptionalTrialCreate.verifyOriginalZeroInvoice(client, authority, original);
        current.run();
        return paid;
    }

    private void verifySubscription(com.stripe.model.Subscription remote, String method, String latest, boolean paid) {
        try {
            StripeBillingOwnership.requireRemoteStructure(remote, authority.customerReference(), authority.published());
        } catch (RuntimeException failure) {
            throw new OwnedOptionalTrialResume.Failure(RESPONSE_STRUCTURE);
        }
        requireResponse((paid ? "active" : "paused").equals(remote.getStatus()), RESPONSE_STATUS);
        requireResponse(remote.getPendingUpdate() == null, RESPONSE_PENDING_UPDATE);
        requireResponse(original.subscription().equals(remote.getId()) && authority.clockReference().equals(remote.getTestClock())
            && latest.equals(remote.getLatestInvoice())
            && method.equals(remote.getDefaultPaymentMethod()) && remote.getDefaultSource() == null
            && remote.getSchedule() == null && remote.getCancelAt() == null
            && Boolean.FALSE.equals(remote.getCancelAtPeriodEnd()) && remote.getCanceledAt() == null && remote.getEndedAt() == null
            && (remote.getAutomaticTax() == null || Boolean.FALSE.equals(remote.getAutomaticTax().getEnabled()))
            && optionalEmpty(remote.getDefaultTaxRates()) && equal(authority.frozen(), remote.getTrialStart())
            && equal(frozen, remote.getTrialEnd()) && remote.getMetadata() != null
            && remote.getMetadata().entrySet().containsAll(authority.metadata().entrySet())
            && remote.getTrialSettings() != null && remote.getTrialSettings().getEndBehavior() != null
            && "pause".equals(remote.getTrialSettings().getEndBehavior().getMissingPaymentMethod()), RESPONSE_OWNER);
        var item = remote.getItems().getData().getFirst();
        boolean trialPeriod = equal(authority.frozen(), item.getCurrentPeriodStart()) && equal(frozen, item.getCurrentPeriodEnd());
        boolean resumedPeriod = equal(frozen, item.getCurrentPeriodStart()) && equal(periodEnd(), item.getCurrentPeriodEnd());
        requireResponse(original.item().equals(item.getId()) && Boolean.TRUE.equals(item.getPrice().getActive()), RESPONSE_OWNER);
        requireResponse(paid ? resumedPeriod : trialPeriod || !original.invoice().equals(latest) && resumedPeriod, RESPONSE_PERIOD);
    }

    void verifyNoPayment() throws StripeException {
        only(call(() -> client.v1().paymentIntents().list(PaymentIntentListParams.builder()
            .setCustomer(authority.customerReference()).setLimit(1L).build(), read)), 0);
        only(call(() -> client.v1().charges().list(ChargeListParams.builder()
            .setCustomer(authority.customerReference()).setLimit(1L).build(), read)), 0);
    }

    String dispatch(String method, String key) throws StripeException {
        var response = call(() -> client.v1().subscriptions().resume(original.subscription(), SubscriptionResumeParams.builder()
            .setBillingCycleAnchor(SubscriptionResumeParams.BillingCycleAnchor.builder()
                .setType(SubscriptionResumeParams.BillingCycleAnchor.Type.NOW).build())
            .setPaymentBehavior(PAYMENT_BEHAVIOR)
            .setProrationBehavior(SubscriptionResumeParams.ProrationBehavior.NONE).build(), options(key)));
        var invoice = response.getLatestInvoice();
        requireResponse(reference(invoice, "in"), RESPONSE_INVOICE_INVALID);
        requireResponse(!invoice.equals(original.invoice()), RESPONSE_INVOICE_ORIGINAL);
        verifySubscription(response, method, invoice, false);
        return invoice;
    }

    Settlement readInvoice(String invoiceId, String method, boolean paid, Identity expected) throws StripeException {
        return readInvoice(invoiceId, method, paid, expected, null);
    }

    Settlement readInvoice(String invoiceId, String method, boolean paid, Identity expected, String expectedCharge) throws StripeException {
        require(reference(invoiceId, "in") && !invoiceId.equals(original.invoice()) && reference(method, "pm")
            && (!paid || expected != null) && (expected == null || invoiceId.equals(expected.invoice())));
        var price = authority.published().terms();
        long amount = price.regularAmount().minorUnits();
        var invoice = call(() -> client.v1().invoices().retrieve(invoiceId,
            InvoiceRetrieveParams.builder().addExpand("amount_paid_off_stripe").build(), read));
        require(invoiceId.equals(invoice.getId()) && invoiceBelongs(invoice)
            && "eur".equals(invoice.getCurrency()) && "subscription_cycle".equals(invoice.getBillingReason())
            && "charge_automatically".equals(invoice.getCollectionMethod()) && equal(frozen, invoice.getCreated())
            && (paid ? "paid" : "open").equals(invoice.getStatus()) && equal(amount, invoice.getAmountDue())
            && equal(paid ? amount : 0, invoice.getAmountPaid()) && equal(paid ? 0 : amount, invoice.getAmountRemaining())
            && equal(amount, invoice.getTotal()) && equal(amount, invoice.getSubtotal())
            && StripeBillingMutationAdapter.noAdjustments(invoice));
        verifyLine(invoiceId, amount);

        var forward = only(call(() -> client.v1().invoicePayments().list(InvoicePaymentListParams.builder()
            .setInvoice(invoiceId).setLimit(2L).build(), read)), 1).getFirst();
        var identity = new Identity(invoiceId, forward.getId(), forward.getPayment() == null ? null : forward.getPayment().getPaymentIntent());
        require(reference(identity.allocation(), "inpay") && reference(identity.intent(), "pi")
            && (expected == null || expected.equals(identity)));
        var paidAt = verifyAllocation(forward, identity, amount, paid);
        var reverse = only(call(() -> client.v1().invoicePayments().list(InvoicePaymentListParams.builder()
            .setPayment(InvoicePaymentListParams.Payment.builder().setType(InvoicePaymentListParams.Payment.Type.PAYMENT_INTENT)
                .setPaymentIntent(identity.intent()).build()).setLimit(2L).build(), read)), 1).getFirst();
        require(Objects.equals(paidAt, verifyAllocation(reverse, identity, amount, paid)));
        verifyPayment(identity, method, amount, paid, expectedCharge);

        var cash = paid ? List.of(new CashObject(identity.intent(), amount, "EUR", PaymentStatus.SUCCEEDED,
            Instant.ofEpochSecond(paidAt))) : List.<CashObject>of();
        return new Settlement(identity, new Invoice(invoiceId, BillingRecord.Kind.RENEWAL, price, null,
            resumedPeriod(frozen), amount, "EUR", paid ? SettlementKind.CASH : SettlementKind.OPEN, cash));
    }

    private boolean invoiceBelongs(com.stripe.model.Invoice invoice) {
        return "invoice".equals(invoice.getObject()) && Boolean.FALSE.equals(invoice.getLivemode())
            && authority.customerReference().equals(invoice.getCustomer()) && authority.clockReference().equals(invoice.getTestClock())
            && invoice.getParent() != null && "subscription_details".equals(invoice.getParent().getType())
            && invoice.getParent().getSubscriptionDetails() != null
            && original.subscription().equals(invoice.getParent().getSubscriptionDetails().getSubscription());
    }

    private void verifyLine(String invoice, long amount) throws StripeException {
        var line = only(call(() -> client.v1().invoices().lineItems().list(invoice,
            InvoiceLineItemListParams.builder().setLimit(2L).build(), read)), 1).getFirst();
        require(reference(line.getId(), "il") && "line_item".equals(line.getObject()) && Boolean.FALSE.equals(line.getLivemode())
            && invoice.equals(line.getInvoice()) && (line.getSubscription() == null || original.subscription().equals(line.getSubscription()))
            && "eur".equals(line.getCurrency()) && equal(amount, line.getAmount()) && equal(1, line.getQuantity())
            && line.getPeriod() != null && equal(frozen, line.getPeriod().getStart()) && equal(periodEnd(), line.getPeriod().getEnd())
            && line.getParent() != null && "subscription_item_details".equals(line.getParent().getType())
            && line.getParent().getSubscriptionItemDetails() != null && line.getPricing() != null
            && "price_details".equals(line.getPricing().getType()) && line.getPricing().getPriceDetails() != null
            && List.of().equals(line.getDiscountAmounts()) && List.of().equals(line.getPretaxCreditAmounts())
            && optionalEmpty(line.getTaxes()) && optionalEmpty(line.getDiscounts()));
        var detail = line.getParent().getSubscriptionItemDetails();
        var pricing = line.getPricing().getPriceDetails();
        require(original.subscription().equals(detail.getSubscription()) && original.item().equals(detail.getSubscriptionItem())
            && Boolean.FALSE.equals(detail.getProration()) && authority.priceReference().equals(pricing.getPrice())
            && authority.productReference().equals(pricing.getProduct()));
    }

    private Long verifyAllocation(InvoicePayment allocation, Identity identity, long amount, boolean paid) {
        var transitions = allocation.getStatusTransitions();
        require(identity.allocation().equals(allocation.getId()) && "invoice_payment".equals(allocation.getObject())
            && Boolean.FALSE.equals(allocation.getLivemode()) && identity.invoice().equals(allocation.getInvoice())
            && "eur".equals(allocation.getCurrency()) && Boolean.TRUE.equals(allocation.getIsDefault())
            && equal(amount, allocation.getAmountRequested()) && allocation.getPayment() != null
            && "payment_intent".equals(allocation.getPayment().getType()) && identity.intent().equals(allocation.getPayment().getPaymentIntent())
            && (paid ? "paid" : "open").equals(allocation.getStatus()) && transitions != null && transitions.getCanceledAt() == null);
        var paidAt = transitions.getPaidAt();
        if (paid) require(equal(amount, allocation.getAmountPaid()) && paidAt != null && paidAt >= frozen && paidAt < periodEnd());
        else require(paidAt == null && (allocation.getAmountPaid() == null || equal(0, allocation.getAmountPaid())));
        return paidAt;
    }

    private void verifyPayment(Identity identity, String method, long amount, boolean paid, String expectedCharge) throws StripeException {
        var intent = only(call(() -> client.v1().paymentIntents().list(PaymentIntentListParams.builder()
            .setCustomer(authority.customerReference()).setLimit(2L).build(), read)), 1).getFirst();
        require(identity.intent().equals(intent.getId()) && "payment_intent".equals(intent.getObject())
            && Boolean.FALSE.equals(intent.getLivemode()) && authority.customerReference().equals(intent.getCustomer())
            && "eur".equals(intent.getCurrency()) && equal(amount, intent.getAmount()) && equal(paid ? amount : 0, intent.getAmountReceived())
            && "automatic".equals(intent.getCaptureMethod()) && List.of("card").equals(intent.getPaymentMethodTypes()));
        if (!paid) require(Set.of("requires_payment_method", "requires_confirmation", "requires_action").contains(intent.getStatus())
            && intent.getLatestCharge() == null && (intent.getPaymentMethod() == null || method.equals(intent.getPaymentMethod())));

        var charges = only(call(() -> client.v1().charges().list(ChargeListParams.builder()
            .setCustomer(authority.customerReference()).setLimit(2L).build(), read)), paid ? 1 : 0);
        if (paid) {
            require("succeeded".equals(intent.getStatus()) && method.equals(intent.getPaymentMethod())
                && reference(intent.getLatestCharge(), "ch") && (expectedCharge == null || expectedCharge.equals(intent.getLatestCharge())));
            var charge = charges.getFirst();
            require(intent.getLatestCharge().equals(charge.getId()) && "charge".equals(charge.getObject())
                && Boolean.FALSE.equals(charge.getLivemode()) && authority.customerReference().equals(charge.getCustomer())
                && identity.intent().equals(charge.getPaymentIntent()) && method.equals(charge.getPaymentMethod())
                && "eur".equals(charge.getCurrency()) && "succeeded".equals(charge.getStatus())
                && equal(amount, charge.getAmount()) && equal(amount, charge.getAmountCaptured()) && equal(0, charge.getAmountRefunded())
                && Boolean.TRUE.equals(charge.getPaid()) && Boolean.TRUE.equals(charge.getCaptured())
                && Boolean.FALSE.equals(charge.getRefunded()) && Boolean.FALSE.equals(charge.getDisputed())
                && charge.getPaymentMethodDetails() != null && "card".equals(charge.getPaymentMethodDetails().getType()));
            only(call(() -> client.v1().refunds().list(RefundListParams.builder().setCharge(charge.getId()).setLimit(1L).build(), read)), 0);
        }
    }

    static Subscription.Period resumedPeriod(long frozen) {
        var start = Instant.ofEpochSecond(frozen);
        return new Subscription.Period(start, start.atOffset(ZoneOffset.UTC).plusMonths(1).toInstant());
    }
    private long periodEnd() { return resumedPeriod(frozen).end().getEpochSecond(); }
    private static boolean reference(String value, String prefix) { return value != null && value.matches(prefix + "_[A-Za-z0-9]{1,248}"); }
    private static boolean equal(long value, Long actual) { return Objects.equals(value, actual); }
    private static boolean optionalEmpty(List<?> values) { return values == null || values.isEmpty(); }
    private static <T extends HasId> List<T> only(StripeCollection<T> page, int count) {
        require(page != null && "list".equals(page.getObject()) && Boolean.FALSE.equals(page.getHasMore())
            && page.getData() != null && page.getData().size() == count && page.getData().stream().noneMatch(Objects::isNull));
        return page.getData();
    }
    private <T> T call(Read<T> work) throws StripeException {
        current.run();
        var result = work.call();
        current.run();
        require(result != null);
        return result;
    }
    private interface Read<T> { T call() throws StripeException; }
    private static void requireResponse(boolean valid, OwnedOptionalTrialResume.Stage stage) {
        if (!valid) throw new OwnedOptionalTrialResume.Failure(stage);
    }

    static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("Owned optional trial resumption unavailable");
    }
}
