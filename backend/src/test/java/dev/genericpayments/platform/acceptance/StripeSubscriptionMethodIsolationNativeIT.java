package dev.genericpayments.platform.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.*;
import dev.genericpayments.platform.catalog.application.port.in.PublishPriceUseCase;
import dev.genericpayments.platform.catalog.application.port.out.SavePricePort;
import dev.genericpayments.platform.catalog.application.port.out.SaveProductPort;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.application.service.CustomerSessionService;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.support.NativeBillingSandboxSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** Explicitly selected end-to-end test. All provider IDs originate in this invocation. */
@EnabledIfEnvironmentVariable(named="PAYMENT_STRIPE_BILLING_CASE", matches="method_isolation")
@TestPropertySource(properties="payment.telemetry.site-keys[0]=method-isolation")
class StripeSubscriptionMethodIsolationNativeIT extends NativeBillingSandboxSupport {
    private static final String ORIGIN = "https://method-isolation.test";
    private final PaymentAccountId account = new PaymentAccountId(UUID.randomUUID());
    private final SiteId site = new SiteId(UUID.randomUUID());
    private final CustomerId customer = new CustomerId(UUID.randomUUID());
    @Autowired private ApplicationContext app;
    @Autowired private JdbcTemplate jdbc;
    private StripeClient stripe;
    private SubscriptionActor actor;
    private String stripeCustomer;
    private final Map<UUID,String> ownedTrials = new LinkedHashMap<>();
    private final Map<String,PriceId> createKeys = new LinkedHashMap<>();
    private boolean verifyAfterCleanup;

    @Test void replacingOneTrialMethodPreservesTheSiblingAndCustomerDefault() throws Exception {
        stripe = sandboxClient();
        provisionSite();
        configureCallbacks(site);
        var optional = publishTrialPrice("sibling", TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL);
        var required = publishTrialPrice("target", TrialPaymentMethodPolicy.REQUIRED_BEFORE_TRIAL);
        try {
            var sibling = create(optional);
            stripeCustomer = jdbc.queryForObject("""
                select provider_reference from stripe_billing_customers
                where site_id=? and customer_id=? and payment_account_id=?
                """, String.class, site.value(), customer.value(), account.value());
            var customerObject = stripe.v1().customers().retrieve(stripeCustomer);
            assertThat(customerObject.getLivemode()).isFalse();
            assertThat(customerObject.getMetadata()).containsEntry("customer_id",customer.value().toString())
                .containsEntry("account_id",account.value().toString()).containsEntry("site_id",site.value().toString());
            rememberOwnedTrial(sibling);
            reconcile(sibling);

            var target = create(required);
            var originalCard = confirm(target, "pm_card_visa", "visa");
            rememberOwnedTrial(target);
            reconcile(target);
            var siblingSetup = setup(sibling.subscriptionId());
            var siblingCard = confirm(siblingSetup, "pm_card_mastercard", "mastercard");
            reconcile(siblingSetup);
            assertThat(siblingCard).isNotEqualTo(originalCard);
            var originalDefault = customerDefault();
            if (originalDefault == null) {
                var params = CustomerUpdateParams.builder().setInvoiceSettings(CustomerUpdateParams.InvoiceSettings.builder()
                    .setDefaultPaymentMethod(originalCard).build()).build();
                stripe.v1().customers().update(stripeCustomer,params,options("customer-default"));
            }
            assertThat(customerDefault()).isEqualTo(originalCard);

            var siblingBefore = providerTrial(sibling);
            var targetBefore = providerTrial(target);
            assertThat(siblingBefore.method()).isEqualTo(siblingCard);
            assertThat(targetBefore.method()).isEqualTo(originalCard);
            var billsBefore = localBills();
            assertThat(billsBefore).containsOnlyKeys(sibling.subscriptionId(), target.subscriptionId());
            assertSameInvoice(billsBefore.get(sibling.subscriptionId()),siblingBefore);
            assertSameInvoice(billsBefore.get(target.subscriptionId()),targetBefore);
            var siblingLocal = bean(SubscriptionStore.class).find(site,sibling.subscriptionId()).orElseThrow();
            var siblingProof = proof(siblingSetup);

            var replacement = setup(target.subscriptionId());
            var replacementCard = confirm(replacement, "pm_card_visa_debit", "visa");
            reconcile(replacement);
            assertThat(replacementCard).isNotIn(originalCard,siblingCard);
            var targetAfter = providerTrial(target);
            assertThat(targetAfter).isEqualTo(new TrialState(targetBefore.subscription(),replacementCard,
                targetBefore.invoice(),targetBefore.start(),targetBefore.end()));
            assertThat(providerTrial(sibling)).isEqualTo(siblingBefore);
            assertThat(customerDefault()).isEqualTo(originalCard);
            assertThat(localBills()).isEqualTo(billsBefore);
            assertThat(bean(SubscriptionStore.class).find(site,sibling.subscriptionId())).contains(siblingLocal);
            assertThat(proof(siblingSetup)).isEqualTo(siblingProof);
            var proofs = bean(SubscriptionBillingProofStore.class);
            assertThat(proofs.method(account,target.subscriptionId(),replacementCard)).contains(proof(replacement));
            assertThat(proofs.method(account,sibling.subscriptionId(),replacementCard)).isEmpty();
            assertThat(proofs.method(account,target.subscriptionId(),siblingCard)).isEmpty();
            assertMethodUpdateReceipt(siblingSetup, siblingBefore.subscription());
            assertMethodUpdateReceipt(replacement, targetBefore.subscription());
            assertNoCash();
            verifyTrialCallbacks(Set.of(sibling.subscriptionId(),target.subscriptionId()));
            verifyAfterCleanup = true;
        } catch (StripeException failure) {
            throw safeFailure("provider verification",failure);
        }
    }

    private <T> T bean(Class<T> type) { return app.getBean(type); }

    private void provisionSite() {
        jdbc.update("""
            insert into payment_accounts values (?, 'stripe-sandbox-default','STRIPE','SANDBOX','ACTIVE',
                'acct_1UPiMpELswAd8tpR','stripe-sandbox-default',now(),0)
            """,account.value());
        jdbc.update("insert into sites values (?,'method-isolation','Method isolation',?,'ACTIVE','AUTOMATIC',now(),now(),0)",
            site.value(),account.value());
        jdbc.update("insert into site_allowed_origins values (?,?)",site.value(),ORIGIN);
        jdbc.update("insert into site_authentication_modes values (?,'SITE_TOKEN')",site.value());
        bean(CustomerStore.class).save(Customer.create(customer,site,null,null,Instant.now()));
        bean(SubscriptionPolicyPort.class).save(new SubscriptionPolicy(site,0,true,3,true,false,false,Set.of()),0);
        var sessions = bean(CustomerSessionService.class);
        actor = new SubscriptionActor.Customer(sessions.resolveRequired(sessions.issue(site,customer,ORIGIN).rawToken(),ORIGIN));
    }

    private PriceId publishTrialPrice(String key, TrialPaymentMethodPolicy policy) {
        var product = Product.create(new ProductId(UUID.randomUUID()),site,new ProductKey(key),
            "Method isolation "+key+" "+runId(),null,Instant.now());
        bean(SaveProductPort.class).save(product);
        var price = new PriceId(UUID.randomUUID());
        var draft = Price.draft(price,site,product.id(),new PriceKey(key+"-monthly"),1,PriceType.SUBSCRIPTION,
            Money.of(2000,"EUR"),BillingInterval.MONTH,7,policy,TaxMode.INCLUSIVE,null,Instant.now());
        bean(SavePricePort.class).save(draft);
        assertThat(bean(PublishPriceUseCase.class).publish(price).status()).isEqualTo(PriceStatus.PUBLISHED);
        return price;
    }

    private SubscriptionCommandContext context() {
        return new SubscriptionCommandContext(actor,site,customer,ORIGIN,UUID.randomUUID().toString(),"method-isolation");
    }

    private SubscriptionOperationResult create(PriceId price) {
        var command = context();
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(command.idempotencyKey().getBytes(StandardCharsets.UTF_8));
            createKeys.put(HexFormat.of().formatHex(digest),price);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        var result = bean(CreateSubscriptionUseCase.class).create(new CreateSubscriptionUseCase.Command(command,price,true));
        assertThat(result.status()).as("fresh trial admission").isEqualTo(SubscriptionOperation.Status.PROCESSING);
        return result;
    }

    private SubscriptionOperationResult setup(UUID subscription) {
        var result = bean(SetupSubscriptionMethodUseCase.class).setup(new SetupSubscriptionMethodUseCase.Command(context(),subscription,true));
        assertThat(result.status()).as("fresh method admission").isEqualTo(SubscriptionOperation.Status.PROCESSING);
        assertThat(result.subscriptionId()).isEqualTo(subscription);
        return result;
    }

    private SubscriptionBillingProofStore.Setup proof(SubscriptionOperationResult operation) {
        return bean(SubscriptionBillingProofStore.class).setup(account,operation.operationId()).orElseThrow();
    }

    private String confirm(SubscriptionOperationResult operation, String testMethod, String brand) throws StripeException {
        var setup = proof(operation);
        assertThat(setup.subscription()).isEqualTo(operation.subscriptionId());
        assertThat(setup.method()).isNull();
        var initial = stripe.v1().setupIntents().retrieve(setup.reference());
        assertThat(initial.getLivemode()).isFalse();
        assertThat(initial.getStatus()).isEqualTo("requires_payment_method");
        assertThat(initial.getUsage()).isEqualTo("off_session");
        assertThat(initial.getCustomer()).isEqualTo(stripeCustomer);
        assertThat(initial.getMetadata()).containsEntry("operation_id",operation.operationId().toString())
            .containsEntry("subscription_id",operation.subscriptionId().toString());
        var confirmed = stripe.v1().setupIntents().confirm(setup.reference(),
            SetupIntentConfirmParams.builder().setPaymentMethod(testMethod).setReturnUrl(ORIGIN+"/setup-return").build(),
            options(operation.operationId().toString()));
        assertThat(confirmed.getStatus()).isEqualTo("succeeded");
        var pm = stripe.v1().paymentMethods().retrieve(confirmed.getPaymentMethod());
        assertThat(pm.getLivemode()).isFalse();
        assertThat(pm.getCustomer()).isEqualTo(stripeCustomer);
        assertThat(pm.getCard().getBrand()).isEqualTo(brand);
        var continued = bean(ContinueOriginalSubscriptionSetupUseCase.class).api(context(),operation.subscriptionId(),operation.operationId());
        assertThat(continued.status()).as("application method continuation").isEqualTo(SubscriptionOperation.Status.PROCESSING);
        assertThat(proof(operation).method()).isEqualTo(pm.getId());
        return pm.getId();
    }

    private RequestOptions options(String action) {
        return RequestOptions.builder().setIdempotencyKey("method-isolation-"+runId()+"-"+action).build();
    }

    private void reconcile(SubscriptionOperationResult pending) {
        var result = bean(ReconcileSubscriptionUseCase.class).reconcile(new ReconcileSubscriptionUseCase.Command(context(),pending.subscriptionId()));
        assertThat(result.status()).isEqualTo(SubscriptionOperation.Status.SUCCEEDED);
        assertThat(jdbc.queryForObject("select status from subscription_operations where id=? and site_id=? and subscription_id=?",
            String.class,pending.operationId(),site.value(),pending.subscriptionId())).isEqualTo("SUCCEEDED");
        var view = bean(QuerySubscriptionsUseCase.class).get(actor,site,pending.subscriptionId());
        assertThat(view.access().reason()).isEqualTo(AccessDecision.Reason.TRIAL);
    }

    private void rememberOwnedTrial(SubscriptionOperationResult created) throws StripeException {
        var local = bean(SubscriptionStore.class).find(site,created.subscriptionId()).orElseThrow();
        var remote = stripe.v1().subscriptions().retrieve(local.providerReference());
        requireOwned(remote,created.subscriptionId(),created.operationId(),stripeCustomer);
        assertThat(remote.getStatus()).isEqualTo("trialing");
        ownedTrials.put(created.subscriptionId(),remote.getId());
    }

    private void requireOwned(com.stripe.model.Subscription remote, UUID subscription, UUID create, String customerReference) {
        assertThat(remote.getLivemode()).isFalse();
        assertThat(remote.getCustomer()).isEqualTo(customerReference);
        assertThat(remote.getMetadata()).containsEntry("site_id",site.value().toString())
            .containsEntry("customer_id",customer.value().toString()).containsEntry("account_id",account.value().toString())
            .containsEntry("subscription_id",subscription.toString()).containsEntry("operation_id",create.toString());
    }

    private String customerDefault() throws StripeException {
        return stripe.v1().customers().retrieve(stripeCustomer).getInvoiceSettings().getDefaultPaymentMethod();
    }

    private record TrialState(String subscription,String method,String invoice,Long start,Long end) {}

    private TrialState providerTrial(SubscriptionOperationResult created) throws StripeException {
        var subscription = stripe.v1().subscriptions().retrieve(ownedTrials.get(created.subscriptionId()));
        assertThat(subscription.getStatus()).isEqualTo("trialing");
        assertThat(subscription.getCustomer()).isEqualTo(stripeCustomer);
        var invoices = stripe.v1().invoices().list(InvoiceListParams.builder().setCustomer(stripeCustomer)
            .setSubscription(subscription.getId()).setLimit(2L).build());
        assertThat(invoices.getHasMore()).isFalse();
        assertThat(invoices.getData().size()).as("one exact invoice").isOne();
        var invoice = invoices.getData().getFirst();
        assertThat(invoice.getLivemode()).isFalse();
        assertThat(invoice.getId()).isEqualTo(subscription.getLatestInvoice());
        assertThat(invoice.getParent().getSubscriptionDetails().getSubscription()).isEqualTo(subscription.getId());
        assertThat(invoice.getCustomer()).isEqualTo(stripeCustomer);
        assertThat(invoice.getCurrency()).isEqualTo("eur");
        assertThat(invoice.getBillingReason()).isEqualTo("subscription_create");
        assertThat(invoice.getStatus()).isEqualTo("paid");
        assertThat(invoice.getAmountDue()).isZero();
        assertThat(invoice.getAmountPaid()).isZero();
        assertThat(invoice.getAmountRemaining()).isZero();
        var allocations = stripe.v1().invoicePayments().list(InvoicePaymentListParams.builder().setInvoice(invoice.getId()).setLimit(1L).build());
        assertThat(allocations.getData().size()).as("zero trial invoice allocations").isZero();
        return new TrialState(subscription.getId(),subscription.getDefaultPaymentMethod(),invoice.getId(),
            subscription.getTrialStart(),subscription.getTrialEnd());
    }

    private record Bill(UUID id,String invoice,Instant start,Instant end,UUID event) {}

    private static void assertSameInvoice(Bill local, TrialState provider) {
        assertThat(local.invoice()).isEqualTo(provider.invoice());
        assertThat(local.start()).isEqualTo(Instant.ofEpochSecond(provider.start()));
        assertThat(local.end()).isEqualTo(Instant.ofEpochSecond(provider.end()));
    }

    private Map<UUID,Bill> localBills() {
        var result = new HashMap<UUID,Bill>();
        jdbc.query("""
            select s.id subscription, b.id bill, p.invoice_reference, b.period_start, b.period_end, e.id event
            from subscriptions s join stripe_subscription_trial_proofs p on p.subscription_id=s.id and p.subscription_reference=s.provider_reference
            join subscription_billing_records b on b.subscription_id=s.id and b.provider_invoice_reference=p.invoice_reference
            join subscription_events e on e.subscription_id=s.id and e.event_type='TRIAL_STARTED' and e.payment_id is null
            join subscription_provider_objects o on o.billing_record_id=b.id and o.object_type='INVOICE' and o.provider_reference=p.invoice_reference
            where s.site_id=? and s.customer_id=? and s.payment_account_id=? and s.billing_status='TRIALING'
                and s.paid_through is null and s.debt_billing_record_id is null and b.kind='INITIAL'
                and b.settlement='ZERO_AMOUNT' and b.amount_due_minor=0 and b.currency='EUR'
                and b.period_start=s.trial_start and b.period_end=s.trial_end
            """, row -> {
                var bill = new Bill(row.getObject("bill",UUID.class),row.getString("invoice_reference"),
                    row.getTimestamp("period_start").toInstant(),row.getTimestamp("period_end").toInstant(),row.getObject("event",UUID.class));
                assertThat(result.put(row.getObject("subscription",UUID.class),bill)).isNull();
            },site.value(),customer.value(),account.value());
        assertThat(jdbc.queryForObject("select count(*) from subscription_billing_records where site_id=?",Long.class,site.value()))
            .isEqualTo((long)result.size());
        return result;
    }

    private void assertMethodUpdateReceipt(SubscriptionOperationResult op,String subscriptionReference) {
        assertThat(jdbc.queryForObject("""
            select count(*) from subscription_operations o
            join stripe_subscription_setup_proofs p on p.operation_id=o.id and p.subscription_id=o.subscription_id and p.site_id=o.site_id
            join provider_operations setup on setup.id=p.step_id and setup.operation_key=o.provider_operation_key||':SETUP'
              and setup.provider_reference=p.setup_reference and setup.kind='SETUP_INTENT_CREATE' and setup.status='ACKNOWLEDGED'
            join provider_operations method on method.resource_id=o.id and method.payment_account_id=p.payment_account_id
              and method.operation_key=o.provider_operation_key||':METHOD' and method.kind='SUBSCRIPTION_UPDATE'
              and method.status='ACKNOWLEDGED' and method.provider_reference=?
            where o.site_id=? and o.subscription_id=? and o.id=? and o.kind='SETUP' and o.status='SUCCEEDED'
            """,Long.class,subscriptionReference,site.value(),op.subscriptionId(),op.operationId())).isOne();
    }

    private void assertNoCash() throws StripeException {
        assertThat(stripe.v1().paymentIntents().list(PaymentIntentListParams.builder().setCustomer(stripeCustomer)
            .setLimit(1L).build()).getData().size()).as("customer PaymentIntents").isZero();
        assertThat(stripe.v1().charges().list(ChargeListParams.builder().setCustomer(stripeCustomer)
            .setLimit(1L).build()).getData().size()).as("customer charges").isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments where site_id=?",Long.class,site.value())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment_events where site_id=?",Long.class,site.value())).isZero();
    }

    private void verifyTrialCallbacks(Set<UUID> trials) throws Exception {
        var expected = jdbc.queryForList("""
            select e.subscription_id from subscription_events e join outbox_events o on o.id=e.id and o.subscription_event_id=e.id
            where e.site_id=? and e.event_type='TRIAL_STARTED' and e.payment_id is null
              and o.callback_type='subscription.updated' and o.subscription_id=e.subscription_id
              and o.payload->>'event_id'=e.id::text and o.payload->>'subscription_id'=e.subscription_id::text
              and o.payload->>'state'='TRIALING' and o.payload->'access_allowed'='true'::jsonb
            """,UUID.class,site.value());
        assertThat(expected).containsExactlyInAnyOrderElementsOf(trials);
        deliverCallbacks(site,trials);
    }

    private record CleanupReceipt(UUID subscription,UUID operation,String customer,String reference,String status,boolean dispatched) {}

    @AfterEach void cancelOwnedTrials() throws StripeException {
        var cancel = SubscriptionCancelParams.builder().setInvoiceNow(false).setProrate(false).build();
        var failures = new ArrayList<Throwable>();
        var canceledTrials = new HashSet<String>();
        for (var entry : createKeys.entrySet()) {
            try {
                // Recover an exact committed create ACK even if the application threw before returning its result.
                var receipts = jdbc.query("""
                    select s.id subscription, o.id operation, c.provider_reference customer, p.provider_reference,
                        p.status, p.first_dispatched_at is not null dispatched
                    from subscription_operations o join subscriptions s on s.id=o.subscription_id and s.site_id=o.site_id
                    join provider_operations p on p.kind='SUBSCRIPTION_CREATE' and p.resource_id=o.id
                        and p.payment_account_id=s.payment_account_id and p.operation_key=o.provider_operation_key
                    join stripe_billing_customers c on c.site_id=s.site_id and c.customer_id=s.customer_id
                        and c.payment_account_id=s.payment_account_id
                    where s.site_id=? and s.customer_id=? and s.payment_account_id=? and s.billing_price_id=?
                        and o.customer_id=s.customer_id and o.kind='CREATE' and o.key_digest=?
                    """,(r,n)->new CleanupReceipt(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),
                        r.getString(4),r.getString(5),r.getBoolean(6)),site.value(),customer.value(),account.value(),
                        entry.getValue().value(),entry.getKey());
                assertThat(receipts.size()).isLessThanOrEqualTo(1);
                if (receipts.isEmpty() || !receipts.getFirst().dispatched()) continue;
                var receipt = receipts.getFirst();
                assertThat(receipt.status()).as("original creation outcome required for cleanup").isEqualTo("ACKNOWLEDGED");
                var remote = stripe.v1().subscriptions().retrieve(receipt.reference());
                requireOwned(remote,receipt.subscription(),receipt.operation(),receipt.customer());
                assertThat(remote.getId()).isEqualTo(receipt.reference());
                assertThat(remote.getStatus()).isEqualTo("trialing");
                var canceled = stripe.v1().subscriptions().cancel(receipt.reference(),cancel);
                assertThat(canceled.getId()).isEqualTo(receipt.reference());
                assertThat(canceled.getStatus()).isEqualTo("canceled");
                canceledTrials.add(canceled.getId());
            } catch (AssertionError | RuntimeException | StripeException failure) {
                failures.add(failure);
            }
        }
        if (!failures.isEmpty()) throw new AssertionError("Fresh trial cleanup incomplete",failures.getFirst());
        assertThat(canceledTrials).containsAll(ownedTrials.values());
        if (verifyAfterCleanup) assertNoCash();
    }

    private static IllegalStateException safeFailure(String phase,StripeException e) {
        var safe = new IllegalStateException("Stripe method isolation "+phase+" failed, HTTP "+e.getStatusCode());
        safe.setStackTrace(e.getStackTrace());
        return safe;
    }
}
