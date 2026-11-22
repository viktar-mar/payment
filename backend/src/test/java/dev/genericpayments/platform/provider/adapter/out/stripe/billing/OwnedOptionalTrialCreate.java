package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.StripeCollection;
import com.stripe.model.Subscription;
import com.stripe.param.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingTestClockAdapter.options;

/** Private, explicit fixture entry point. The database commits the single POST budget before provider IO. */
public final class OwnedOptionalTrialCreate {
    // This native acceptance lane has one explicitly approved sandbox account.
    static final String APPROVED_ACCOUNT="acct_1UPiMpELswAd8tpR";
    public record Scope(UUID run, UUID clockIntent, UUID subscription, UUID createOperation) {}
    record Authority(UUID run, UUID clockIntent, UUID account, UUID site, UUID customer, UUID subscription,
                     UUID createOperation, UUID price, UUID product, String priceKey, String externalAccount,
                     String credentialsRef, String clockReference, String clockName, String customerReference,
                     String priceReference, String productReference, String operationKey, long frozen,
                     long clockCreated, long clockDeletesAfter, long trialEnd, String fingerprint) {
        Scope scope() { return new Scope(run, clockIntent, subscription, createOperation); }
        Map<String,String> metadata() {
            return Map.of("account_id",account.toString(),"site_id",site.toString(),"customer_id",customer.toString(),
                "subscription_id",subscription.toString(),"operation_id",createOperation.toString());
        }
        StripeBillingSnapshotMapper.PublishedPrice published() {
            var terms=new SubscriptionPriceTerms(new PriceId(price),priceKey,1,new ProductId(product),Money.of(2000,"EUR"),
                BillingInterval.MONTH,7,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.INCLUSIVE);
            return new StripeBillingSnapshotMapper.PublishedPrice(terms,priceReference,productReference);
        }
    }
    record Created(String subscription, String item, String invoice, long trialStart) {}
    interface Store {
        Authority admitted(Scope scope);
        boolean claim(Authority authority, UUID claim);
        boolean dispatchAuthorized(Authority authority, UUID claim);
        boolean finish(UUID claim, Created created);
    }
    private final Store store;
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final PaymentAccountVerificationPort verification;

    OwnedOptionalTrialCreate(Store store, StripeCredentialResolver credentials, StripeClientFactory clients,
                             PaymentAccountVerificationPort verification) {
        this.store=Objects.requireNonNull(store);
        this.credentials=Objects.requireNonNull(credentials);
        this.clients=Objects.requireNonNull(clients);
        this.verification=Objects.requireNonNull(verification);
    }

    public static OwnedOptionalTrialCreate withDatabase(JdbcTemplate jdbc, StripeCredentialResolver credentials,
                                                        StripeClientFactory clients, PaymentAccountVerificationPort verification) {
        return new OwnedOptionalTrialCreate(new JdbcOwnedOptionalTrialCreateStore(jdbc),credentials,clients,verification);
    }

    public void create(PaymentAccount account, Scope scope) {
        require(!TransactionSynchronizationManager.isActualTransactionActive());
        UUID claim=UUID.randomUUID();
        boolean claimed=false;
        try {
            var a=store.admitted(scope);
            require(a!=null && scope.equals(a.scope()) && account.id().value().equals(a.account())
                && account.provider()==PaymentProviderType.STRIPE && account.mode()==PaymentAccountMode.SANDBOX
                && account.status()==PaymentAccountStatus.ACTIVE && account.externalAccountId().equals(a.externalAccount())
                && APPROVED_ACCOUNT.equals(account.externalAccountId()) && "stripe-sandbox-default".equals(account.key())
                && "stripe-sandbox-default".equals(account.credentialsRef()) && account.credentialsRef().equals(a.credentialsRef())
                && a.trialEnd()==Math.addExact(a.frozen(),604800L));
            verification.verify(account);
            var client=clients.create(credentials.resolve(account));
            verifyPrerequisites(client,a);
            var params=StripeBillingOwnership.paidCreate(a.customerReference(),a.priceReference(),a.metadata())
                .setTrialEnd(a.trialEnd())
                .setTrialSettings(SubscriptionCreateParams.TrialSettings.builder().setEndBehavior(
                    SubscriptionCreateParams.TrialSettings.EndBehavior.builder()
                        .setMissingPaymentMethod(SubscriptionCreateParams.TrialSettings.EndBehavior.MissingPaymentMethod.PAUSE)
                        .build()).build()).build();
            require(store.claim(a,claim));
            claimed=true;
            require(store.dispatchAuthorized(a,claim));
            var remote=client.v1().subscriptions().create(params,options(a.operationKey()));
            var created=verifyCreated(remote,a);
            verifyZeroInvoice(client,a,created);
            require(store.finish(claim,created));
        } catch (StripeException | RuntimeException failure) {
            if (claimed) {
                try { store.finish(claim,null); }
                catch (RuntimeException unavailable) { /* The original durable claim still prevents redispatch. */ }
            }
            throw new IllegalStateException("Optional trial create could not be verified");
        }
    }

    private static void verifyPrerequisites(StripeClient client, Authority a) throws StripeException {
        verifyClockCustomerPrice(client, a);
        requireEmpty(client.v1().paymentMethods().list(PaymentMethodListParams.builder()
            .setCustomer(a.customerReference()).setLimit(1L).build(),options(null)));
        requireEmpty(client.v1().subscriptions().list(SubscriptionListParams.builder()
            .setCustomer(a.customerReference()).setStatus(SubscriptionListParams.Status.ALL).setLimit(1L).build(),options(null)));
    }

    private static void verifyClockCustomerPrice(StripeClient client, Authority a) throws StripeException {
        var read=options(null);
        verifyReady(client, a);
        var customer=client.v1().customers().retrieve(a.customerReference(),read);
        require(customer!=null && a.customerReference().equals(customer.getId()) && "customer".equals(customer.getObject())
            && a.clockReference().equals(customer.getTestClock()) && Boolean.FALSE.equals(customer.getLivemode())
            && !Boolean.TRUE.equals(customer.getDeleted()) && customer.getDefaultSource()==null
            && customer.getInvoiceSettings()!=null && customer.getInvoiceSettings().getDefaultPaymentMethod()==null
            && customer.getMetadata()!=null && a.account().toString().equals(customer.getMetadata().get("account_id"))
            && a.site().toString().equals(customer.getMetadata().get("site_id"))
            && a.customer().toString().equals(customer.getMetadata().get("customer_id")));
        var price=client.v1().prices().retrieve(a.priceReference(),read);
        StripeBillingOwnership.requirePublishedPrice(price,a.published());
        require(Boolean.TRUE.equals(price.getActive()));
    }

    private static void verifyReady(StripeClient client, Authority a) throws StripeException {
        var read = options(null);
        var clock=client.v1().testHelpers().testClocks().retrieve(a.clockReference(),read);
        require(clock!=null && a.clockReference().equals(clock.getId()) && "test_helpers.test_clock".equals(clock.getObject())
            && Boolean.FALSE.equals(clock.getLivemode()) && !Boolean.TRUE.equals(clock.getDeleted())
            && a.clockName().equals(clock.getName()) && "ready".equals(clock.getStatus())
            && (clock.getStatusDetails()==null || clock.getStatusDetails().getAdvancing()==null)
            && Objects.equals(a.frozen(),clock.getFrozenTime()) && Objects.equals(a.clockCreated(),clock.getCreated())
            && Objects.equals(a.clockDeletesAfter(),clock.getDeletesAfter()));
    }

    static void reread(StripeClient client, Authority a, Created original) throws StripeException {
        verifyClockCustomerPrice(client, a);
        var read = options(null);
        requireEmpty(client.v1().paymentMethods().list(PaymentMethodListParams.builder()
            .setCustomer(a.customerReference()).setLimit(1L).build(), read));
        var subs = client.v1().subscriptions().list(SubscriptionListParams.builder()
            .setCustomer(a.customerReference()).setStatus(SubscriptionListParams.Status.ALL).setLimit(2L).build(), read);
        require(subs != null && "list".equals(subs.getObject()) && Boolean.FALSE.equals(subs.getHasMore())
            && subs.getData() != null && subs.getData().size() == 1 && original.equals(verifyCreated(subs.getData().getFirst(), a)));
        var invoices = client.v1().invoices().list(InvoiceListParams.builder()
            .setSubscription(original.subscription()).setLimit(2L).build(), read);
        require(invoices != null && "list".equals(invoices.getObject()) && Boolean.FALSE.equals(invoices.getHasMore())
            && invoices.getData() != null && invoices.getData().size() == 1 && original.invoice().equals(invoices.getData().getFirst().getId()));
        verifyZeroInvoice(client, a, original);
        verifyReady(client, a);
    }

    private static Created verifyCreated(Subscription remote, Authority a) {
        StripeBillingOwnership.requireRemoteStructure(remote,a.customerReference(),a.published());
        require(a.clockReference().equals(remote.getTestClock()) && "trialing".equals(remote.getStatus())
            && remote.getCreated()!=null && remote.getCreated()>0 && remote.getMetadata()!=null
            && remote.getMetadata().entrySet().containsAll(a.metadata().entrySet())
            && remote.getDefaultPaymentMethod()==null && remote.getDefaultSource()==null
            && remote.getSchedule()==null && remote.getPendingUpdate()==null && remote.getCancelAt()==null
            && Boolean.FALSE.equals(remote.getCancelAtPeriodEnd()) && remote.getCanceledAt()==null && remote.getEndedAt()==null
            && Objects.equals(a.frozen(),remote.getTrialStart()) && Objects.equals(a.trialEnd(),remote.getTrialEnd())
            && remote.getTrialSettings()!=null && remote.getTrialSettings().getEndBehavior()!=null
            && "pause".equals(remote.getTrialSettings().getEndBehavior().getMissingPaymentMethod())
            && remote.getLatestInvoice()!=null && remote.getLatestInvoice().matches("in_[A-Za-z0-9]{1,252}"));
        var item=remote.getItems().getData().getFirst();
        require(Objects.equals(a.frozen(),item.getCurrentPeriodStart()) && Objects.equals(a.trialEnd(),item.getCurrentPeriodEnd()));
        return new Created(remote.getId(),item.getId(),remote.getLatestInvoice(),remote.getTrialStart());
    }

    private static void verifyZeroInvoice(StripeClient client, Authority a, Created created) throws StripeException {
        var read=options(null);
        var invoice=client.v1().invoices().retrieve(created.invoice(),read);
        require(invoice!=null && created.invoice().equals(invoice.getId()) && "invoice".equals(invoice.getObject())
            && Boolean.FALSE.equals(invoice.getLivemode()) && a.customerReference().equals(invoice.getCustomer())
            && a.clockReference().equals(invoice.getTestClock()) && "eur".equals(invoice.getCurrency())
            && "charge_automatically".equals(invoice.getCollectionMethod()) && "paid".equals(invoice.getStatus())
            && "subscription_create".equals(invoice.getBillingReason()) && invoice.getParent()!=null
            && "subscription_details".equals(invoice.getParent().getType()) && invoice.getParent().getSubscriptionDetails()!=null
            && created.subscription().equals(invoice.getParent().getSubscriptionDetails().getSubscription())
            && Objects.equals(0L,invoice.getAmountDue()) && Objects.equals(0L,invoice.getAmountPaid())
            && Objects.equals(0L,invoice.getAmountRemaining()) && Objects.equals(0L,invoice.getTotal())
            && Objects.equals(0L,invoice.getSubtotal()) && Objects.equals(0L,invoice.getStartingBalance())
            && Objects.equals(0L,invoice.getEndingBalance()) && Objects.equals(0L,invoice.getAmountOverpaid())
            && Objects.equals(0L,invoice.getPrePaymentCreditNotesAmount()) && Objects.equals(0L,invoice.getPostPaymentCreditNotesAmount())
            && (invoice.getAmountPaidOffStripe()==null || invoice.getAmountPaidOffStripe()==0L)
            && StripeBillingMutationAdapter.noNonAmountAdjustments(invoice));
        var page=client.v1().invoices().lineItems().list(created.invoice(),
            InvoiceLineItemListParams.builder().setLimit(2L).build(),read);
        require(page!=null && "list".equals(page.getObject()) && Boolean.FALSE.equals(page.getHasMore())
            && page.getData()!=null && page.getData().size()==1);
        var line=page.getData().getFirst();
        require(line!=null && line.getId()!=null && line.getId().matches("il_[A-Za-z0-9]{1,252}")
            && "line_item".equals(line.getObject()) && Boolean.FALSE.equals(line.getLivemode())
            && created.invoice().equals(line.getInvoice()) && "eur".equals(line.getCurrency())
            && (line.getSubscription()==null || created.subscription().equals(line.getSubscription()))
            && Objects.equals(1L,line.getQuantity()) && Objects.equals(0L,line.getAmount())
            && line.getParent()!=null && "subscription_item_details".equals(line.getParent().getType())
            && line.getParent().getSubscriptionItemDetails()!=null && line.getPricing()!=null
            && "price_details".equals(line.getPricing().getType()) && line.getPricing().getPriceDetails()!=null
            && line.getDiscountAmounts()!=null && line.getDiscountAmounts().isEmpty()
            && line.getPretaxCreditAmounts()!=null && line.getPretaxCreditAmounts().isEmpty()
            && (line.getTaxes()==null || line.getTaxes().isEmpty()) && line.getPeriod()!=null);
        var detail=line.getParent().getSubscriptionItemDetails();
        var pricing=line.getPricing().getPriceDetails();
        require(created.subscription().equals(detail.getSubscription()) && created.item().equals(detail.getSubscriptionItem())
            && Boolean.FALSE.equals(detail.getProration()) && a.priceReference().equals(pricing.getPrice())
            && a.productReference().equals(pricing.getProduct()) && Objects.equals(a.frozen(),line.getPeriod().getStart())
            && Objects.equals(a.trialEnd(),line.getPeriod().getEnd()));
        requireEmpty(client.v1().invoicePayments().list(InvoicePaymentListParams.builder()
            .setInvoice(created.invoice()).setLimit(1L).build(),read));
    }

    private static void requireEmpty(StripeCollection<?> result) {
        require(result!=null && "list".equals(result.getObject()) && Boolean.FALSE.equals(result.getHasMore())
            && result.getData()!=null && result.getData().isEmpty());
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("Optional trial evidence mismatch");
    }
}
