package dev.genericpayments.platform.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.InvoicePaymentListParams;
import com.stripe.param.PaymentIntentConfirmParams;
import com.stripe.param.SubscriptionCancelParams;
import dev.genericpayments.platform.catalog.application.port.in.PublishPriceUseCase;
import dev.genericpayments.platform.catalog.application.port.out.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.application.service.CustomerSessionService;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Creates a fresh ordinary subscription through the application on a disposable local database. */
public abstract class NativePaidPlanFixture extends NativeBillingSandboxSupport {
    protected static final String PLAN_ORIGIN = "https://subscription-acceptance.test";
    private final Map<SiteId, SubscriptionActor> actors = new HashMap<>();
    private final List<PaidPlan> cleanup = new ArrayList<>();
    @Autowired private ApplicationContext planApplication;
    @Autowired private JdbcTemplate planJdbc;

    public record PaidPlan(SiteId site, PaymentAccount account, Customer customer, ProductId product,
            PriceId price, PriceId target, UUID subscriptionId, UUID createOperationId,
            String remoteProduct, String remotePrice, String remoteTarget, String remoteCustomer,
            String remoteSubscription) {}

    protected <T> T planPort(Class<T> type) { return planApplication.getBean(type); }

    protected PaidPlan provisionPaidPlan(long targetAmount) throws Exception {
        assertThat(targetAmount).isPositive().isNotEqualTo(2000L);
        return provision(targetAmount);
    }

    protected PaidPlan provisionPortalPlan() throws Exception { return provision(null); }

    private PaidPlan provision(Long targetAmount) throws Exception {
        StripeClient client = sandboxClient();
        var accountId = new PaymentAccountId(UUID.randomUUID());
        planJdbc.update("""
            insert into payment_accounts values (?, 'stripe-sandbox-default','STRIPE','SANDBOX','ACTIVE',
                'acct_1UPiMpELswAd8tpR','stripe-sandbox-default',now(),0)
            """,accountId.value());
        var account = planPort(LoadPaymentAccountPort.class).findById(accountId).orElseThrow();
        var site = new SiteId(UUID.randomUUID());
        planJdbc.update("insert into sites values (?,?,'Plan acceptance',?,'ACTIVE','AUTOMATIC',now(),now(),0)",
            site.value(), "s5a-" + runId().toString().replace("-", ""), accountId.value());
        planJdbc.update("insert into site_allowed_origins values (?,?)", site.value(), PLAN_ORIGIN);
        planJdbc.update("insert into site_authentication_modes values (?,'SITE_TOKEN')", site.value());
        configureCallbacks(site);
        var product = Product.create(new ProductId(UUID.randomUUID()), site, new ProductKey("membership"),
            "Sandbox membership " + runId(), null, Instant.now());
        planPort(SaveProductPort.class).save(product);
        var price = publish(product, "monthly", 2000);
        var target = targetAmount == null ? null : publish(product, "target", targetAmount);
        var policies = planPort(SubscriptionPolicyPort.class);
        policies.save(new SubscriptionPolicy(site, 0, true, 3, true, false, false, Set.of()), 0);
        if (target != null) {
            var direction = targetAmount > 2000 ? PlanTransition.Direction.UPGRADE : PlanTransition.Direction.DOWNGRADE;
            policies.saveTransition(new PlanTransition(site,product.id(),price,target,direction,targetAmount > 2000,policies.load(site).version()));
        }

        var customer = planPort(CustomerStore.class).save(Customer.create(new CustomerId(UUID.randomUUID()), site, null, null, Instant.now()));
        var sessions = planPort(CustomerSessionService.class);
        var token = sessions.issue(site, customer.id(), PLAN_ORIGIN).rawToken();
        actors.put(site, new SubscriptionActor.Customer(sessions.resolveRequired(token, PLAN_ORIGIN)));
        var create = createFreshSubscription(account,site,customer,new CreateSubscriptionUseCase.Command(
            context(site,customer,UUID.randomUUID().toString()),price,true));
        assertThat(create.status()).isEqualTo(SubscriptionOperation.Status.PROCESSING);
        var subscription = planPort(SubscriptionStore.class).find(site,create.subscriptionId()).orElseThrow();
        var binding = planPort(StripeBillingBindingPort.class).findCustomer(accountId,site,customer.id()).orElseThrow();
        var mappings = planPort(ProviderCatalogMappingPort.class);
        var plan = new PaidPlan(site,account,customer,product.id(),price,target,create.subscriptionId(),create.operationId(),
            mappings.findProduct(accountId,product.id()).orElseThrow().value(),
            mappings.findPrice(accountId,price).orElseThrow().value(), target == null ? null : mappings.findPrice(accountId,target).orElseThrow().value(),
            binding.providerReference(),subscription.providerReference());
        cleanup.add(plan);
        confirmInitial(plan, client);
        publishInitial(plan);
        assertThat(local(plan).billingStatus()).isEqualTo(Subscription.Status.ACTIVE);
        assertThat(local(plan).entitledPrice().priceId()).isEqualTo(price);
        assertThat(planJdbc.queryForObject("""
            select count(*) from payments p join subscription_billing_records b on b.id=p.billing_record_id
             where p.subscription_id=? and p.status='SUCCEEDED' and p.amount_minor=2000
               and b.kind='INITIAL' and b.settlement='CONFIRMED_CASH'
            """, Long.class,plan.subscriptionId())).isOne();
        return plan;
    }

    protected SubscriptionOperationResult createFreshSubscription(PaymentAccount account, SiteId site, Customer customer,
            CreateSubscriptionUseCase.Command command) {
        return planPort(CreateSubscriptionUseCase.class).create(command);
    }

    protected void publishInitial(PaidPlan plan) {
        planPort(dev.genericpayments.platform.subscriptions.application.service.SubscriptionSweepWorker.class).runOnce();
    }

    private PriceId publish(Product product, String key, long amount) {
        var price = Price.draft(new PriceId(UUID.randomUUID()), product.siteId(), product.id(), new PriceKey(key),
            1,PriceType.SUBSCRIPTION,Money.of(amount,"EUR"),BillingInterval.MONTH,0,TaxMode.INCLUSIVE,null,Instant.now());
        planPort(SavePricePort.class).save(price);
        return planPort(PublishPriceUseCase.class).publish(price.id()).id();
    }

    protected SubscriptionCommandContext context(PaidPlan plan, String key) { return context(plan.site(),plan.customer(),key); }
    private SubscriptionCommandContext context(SiteId site, Customer customer, String key) {
        return new SubscriptionCommandContext(Objects.requireNonNull(actors.get(site)), site, customer.id(), PLAN_ORIGIN, key, "native-plan");
    }
    protected Subscription local(PaidPlan plan) {
        return planPort(SubscriptionStore.class).find(plan.site(),plan.subscriptionId()).orElseThrow();
    }
    protected void reconcile(PaidPlan plan) {
        planPort(ReconcileSubscriptionUseCase.class).reconcile(new ReconcileSubscriptionUseCase.Command(
            context(plan,UUID.randomUUID().toString()), plan.subscriptionId()));
    }
    private static void confirmInitial(PaidPlan plan, StripeClient client) {
        try {
            var subscription = client.v1().subscriptions().retrieve(plan.remoteSubscription());
            requireOwned(plan,subscription);
            var invoice = client.v1().invoices().retrieve(subscription.getLatestInvoice());
            assertThat(invoice.getLivemode()).isFalse();
            assertThat(invoice.getCustomer()).isEqualTo(plan.remoteCustomer());
            assertThat(invoice.getParent().getSubscriptionDetails().getSubscription()).isEqualTo(plan.remoteSubscription());
            assertThat(invoice.getAmountDue()).isEqualTo(2000);
            assertThat(invoice.getBillingReason()).isEqualTo("subscription_create");
            var allocations = client.v1().invoicePayments().list(InvoicePaymentListParams.builder().setInvoice(invoice.getId()).setLimit(2L).build());
            assertThat(allocations.getHasMore()).isFalse();
            assertThat(allocations.getData().size()).isOne();
            var allocation=allocations.getData().getFirst();
            assertThat(allocation.getInvoice()).isEqualTo(invoice.getId());
            assertThat(allocation.getPayment().getType()).isEqualTo("payment_intent");
            var intent = client.v1().paymentIntents().retrieve(allocation.getPayment().getPaymentIntent());
            assertThat(intent.getLivemode()).isFalse();
            assertThat(intent.getCustomer()).isEqualTo(plan.remoteCustomer());
            assertThat(intent.getAmount()).isEqualTo(2000);
            assertThat(intent.getCurrency()).isEqualTo("eur");
            assertThat(intent.getStatus()).isEqualTo("requires_payment_method");
            var confirmed = client.v1().paymentIntents().confirm(intent.getId(),
                PaymentIntentConfirmParams.builder().setPaymentMethod("pm_card_visa").build(),
                RequestOptions.builder().setIdempotencyKey("fixture-confirm-" + plan.createOperationId()).build());
            assertThat(confirmed.getId()).isEqualTo(intent.getId());
            assertThat(confirmed.getStatus()).isEqualTo("succeeded");
            var charge=client.v1().charges().retrieve(confirmed.getLatestCharge());
            assertThat(charge.getLivemode()).isFalse();
            assertThat(charge.getCustomer()).isEqualTo(plan.remoteCustomer());
            assertThat(charge.getPaymentIntent()).isEqualTo(intent.getId());
            assertThat(charge.getAmountCaptured()).isEqualTo(2000);
            assertThat(charge.getCaptured()).isTrue();
            var deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
            while (true) {
                var settled = client.v1().invoices().retrieve(invoice.getId());
                if ("paid".equals(settled.getStatus())) {
                    assertThat(settled.getAmountPaid()).isEqualTo(2000L);
                    break;
                }
                assertThat(System.nanoTime()).as("initial invoice settlement deadline").isLessThan(deadline);
                try { Thread.sleep(500); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("NATIVE_FIXTURE_INITIAL_INTERRUPTED");
                }
            }
        } catch (StripeException failure) {
            throw new AssertionError("NATIVE_FIXTURE_INITIAL_CONFIRM_" + failure.getStatusCode());
        }
    }
    private static void requireOwned(PaidPlan plan, com.stripe.model.Subscription remote) {
        assertThat(remote.getId()).isEqualTo(plan.remoteSubscription());
        assertThat(remote.getLivemode()).isFalse();
        assertThat(remote.getCustomer()).isEqualTo(plan.remoteCustomer());
        assertThat(remote.getMetadata()).containsAllEntriesOf(Map.of(
            "account_id",plan.account().id().value().toString(), "site_id", plan.site().value().toString(),
            "customer_id",plan.customer().id().value().toString(), "subscription_id",plan.subscriptionId().toString(),
            "operation_id",plan.createOperationId().toString()));
    }

    @AfterEach void stopFreshRenewals() throws Exception {
        for (var plan : cleanup) {
            var client=sandboxClient();
            try {
                var current=client.v1().subscriptions().retrieve(plan.remoteSubscription());
                requireOwned(plan,current);
                if (!"canceled".equals(current.getStatus())) {
                    if (current.getSchedule()!=null) {
                        var schedule=client.v1().subscriptionSchedules().retrieve(current.getSchedule());
                        assertThat(schedule.getLivemode()).isFalse();
                        assertThat(schedule.getCustomer()).isEqualTo(plan.remoteCustomer());
                        assertThat(schedule.getSubscription()).isEqualTo(plan.remoteSubscription());
                        client.v1().subscriptionSchedules().cancel(schedule.getId(),
                            com.stripe.param.SubscriptionScheduleCancelParams.builder().setProrate(false).setInvoiceNow(false).build());
                    } else {
                        client.v1().subscriptions().cancel(plan.remoteSubscription(),
                            SubscriptionCancelParams.builder().setProrate(false).setInvoiceNow(false).build());
                    }
                    var stopped=client.v1().subscriptions().retrieve(plan.remoteSubscription());
                    requireOwned(plan,stopped);
                    assertThat(stopped.getStatus()).isEqualTo("canceled");
                }
            } catch (StripeException failed) {
                throw new AssertionError("NATIVE_FIXTURE_STOP_RENEWAL_" + failed.getStatusCode());
            }
        }
    }
}
