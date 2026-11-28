package dev.genericpayments.platform.acceptance;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.InvoicePayment;
import com.stripe.net.RequestOptions;
import com.stripe.param.InvoiceLineItemListParams;
import com.stripe.param.InvoicePayParams;
import com.stripe.param.InvoicePaymentListParams;
import com.stripe.param.PaymentMethodAttachParams;
import com.stripe.param.SubscriptionUpdateParams;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.subscriptions.application.port.in.ChangeSubscriptionPlanUseCase;
import dev.genericpayments.platform.subscriptions.application.port.in.PreviewSubscriptionChangeUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionOperationStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPlanProofStore;
import dev.genericpayments.platform.subscriptions.domain.AccessDecision;
import dev.genericpayments.platform.subscriptions.domain.ChangeTiming;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionAccessPolicy;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import dev.genericpayments.platform.support.NativePaidPlanFixture;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Stripe's pending-update flow on a subscription created by the application in this run. */
@EnabledIfEnvironmentVariable(named="PAYMENT_STRIPE_BILLING_CASE", matches="plan_upgrade")
class StripeImmediateUpgradeNativeIT extends NativePaidPlanFixture {
    @Autowired PreviewSubscriptionChangeUseCase previews;
    @Autowired ChangeSubscriptionPlanUseCase changes;
    @Autowired SubscriptionPlanProofStore proofs;
    @Autowired SubscriptionOperationStore operations;
    @Autowired JdbcTemplate database;

    @Test void declinedProrationRetainsPaidAccessUntilItsOriginalInvoiceIsPaid() throws Exception {
        var client=sandboxClient();
        var plan=provisionPaidPlan(4000);
        verifyUpgrade(client,plan);
    }

    private void verifyUpgrade(StripeClient client,PaidPlan plan) throws Exception {
        var baseline=local(plan);
        var initialCash=initialCash(plan);
        String visa=setDecliningDefault(client,plan);
        var quote=previews.preview(new PreviewSubscriptionChangeUseCase.Query(
            context(plan,null),plan.subscriptionId(),plan.target(),ChangeTiming.IMMEDIATE));
        assertTrue(quote.dueNowMinor()>0 && quote.dueNowMinor()<=2000);
        assertEquals(plan.price(),quote.fromPriceId());
        assertEquals(plan.target(),quote.target().priceId());
        assertEquals("EUR",quote.currency());
        assertEquals(4000,quote.nextAmountMinor());

        var command=new ChangeSubscriptionPlanUseCase.Command(context(plan,UUID.randomUUID().toString()),plan.subscriptionId(),quote.id());
        var change=changes.change(command);
        assertEquals(SubscriptionOperation.Status.PROCESSING,change.status(),"upgrade acknowledgement");
        var result=proofs.result(plan.account().id(),change.operationId()).orElseThrow();
        var saved=proofs.quote(plan.account().id(),result.quote()).orElseThrow();
        assertEquals(quote.providerQuoteReference(),saved.reference());
        assertEquals(quote.effectiveAt(),saved.anchor());
        assertEquals(quote.dueNowMinor(),saved.dueNow());
        assertEquals(baseline.paidThrough(),saved.paidThrough());
        assertEquals(plan.remoteSubscription(),result.reference());
        assertNotNull(result.pendingExpiry());
        assertTrue(result.pendingExpiry()>Instant.now().getEpochSecond());

        var pending=client.v1().subscriptions().retrieve(plan.remoteSubscription());
        assertEquals(plan.remoteCustomer(),pending.getCustomer());
        assertEquals(Boolean.FALSE,pending.getLivemode());
        assertEquals(result.invoice(),pending.getLatestInvoice());
        assertEquals(plan.remotePrice(),pending.getItems().getData().getFirst().getPrice().getId());
        assertNotNull(pending.getPendingUpdate());
        assertEquals(result.pendingExpiry(),pending.getPendingUpdate().getExpiresAt());
        assertEquals(plan.remoteTarget(),pending.getPendingUpdate().getSubscriptionItems().getFirst().getPrice().getId());
        assertAccessAndCash(plan,plan.price(),1);
        assertEquals(change.operationId(),local(plan).pendingChange().operationId());

        var open=client.v1().invoices().retrieve(result.invoice());
        assertEquals(Boolean.FALSE,open.getLivemode());
        assertEquals(plan.remoteCustomer(),open.getCustomer());
        assertEquals(plan.remoteSubscription(),open.getParent().getSubscriptionDetails().getSubscription());
        assertEquals("subscription_update",open.getBillingReason());
        assertEquals("open",open.getStatus());
        assertEquals(quote.dueNowMinor(),open.getAmountRemaining());
        assertEquals(0L,open.getAmountPaid());
        var nativeLines=client.v1().invoices().lineItems().list(result.invoice(),
            InvoiceLineItemListParams.builder().setLimit(3L).build());
        assertEquals(Boolean.FALSE,nativeLines.getHasMore());
        assertEquals(2,nativeLines.getData().size());
        assertNotEquals(result.creditLine(),result.debitLine());
        for (var line:nativeLines.getData()) {
            boolean credit=result.creditLine().equals(line.getId());
            assertEquals(credit?result.creditLine():result.debitLine(),line.getId());
            var price=credit?plan.remotePrice():plan.remoteTarget();
            var quotedLine=saved.lines().stream().filter(q->q.price().equals(price)).findFirst().orElseThrow();
            assertEquals(result.invoice(),line.getInvoice());
            assertEquals(price,line.getPricing().getPriceDetails().getPrice());
            assertEquals(quotedLine.amount(),line.getAmount());
            assertEquals(quotedLine.item(),line.getParent().getSubscriptionItemDetails().getSubscriptionItem());
            assertEquals(saved.anchor().getEpochSecond(),line.getPeriod().getStart());
            assertEquals(saved.paidThrough().getEpochSecond(),line.getPeriod().getEnd());
        }
        var original=allocation(client,result.invoice());
        assertEquals("open",original.getStatus());
        assertEquals(quote.dueNowMinor(),original.getAmountRequested());
        var intent=client.v1().paymentIntents().retrieve(original.getPayment().getPaymentIntent());
        assertEquals(plan.remoteCustomer(),intent.getCustomer());
        assertEquals(Boolean.FALSE,intent.getLivemode());
        assertEquals("requires_payment_method",intent.getStatus());
        assertEquals("card_declined",intent.getLastPaymentError().getCode());
        assertEquals(0L,intent.getAmountReceived());
        assertEquals(quote.dueNowMinor(),intent.getAmount());
        assertEquals("eur",intent.getCurrency());
        String declinedCharge=intent.getLatestCharge();
        var failed=client.v1().charges().retrieve(declinedCharge);
        assertEquals(Boolean.FALSE,failed.getLivemode());
        assertEquals(plan.remoteCustomer(),failed.getCustomer());
        assertEquals(intent.getId(),failed.getPaymentIntent());
        assertEquals(pending.getDefaultPaymentMethod(),failed.getPaymentMethod());
        assertEquals("failed",failed.getStatus());
        assertEquals(Boolean.FALSE,failed.getPaid());
        assertEquals(0L,failed.getAmountCaptured());

        reconcile(plan);
        assertAccessAndCash(plan,plan.price(),1);
        assertEquals(change.operationId(),local(plan).pendingChange().operationId());
        assertEquals(baseline.paidThrough(),local(plan).paidThrough());
        assertEquals(1,count("""
            select count(*) from subscription_billing_records where subscription_id=? and provider_invoice_reference=?
              and settlement='OPEN' and kind='PRORATION' and amount_due_minor=? and billed_price_id=? and previous_price_id=?
            """,plan.subscriptionId(),result.invoice(),quote.dueNowMinor(),plan.target().value(),plan.price().value()));
        assertEquals(0,count("select count(*) from subscription_events where subscription_id=? and event_type='CHANGE_APPLIED'",plan.subscriptionId()));

        // A single explicit payment of the invoice created by the pending update.
        var paid=client.v1().invoices().pay(result.invoice(),InvoicePayParams.builder().setPaymentMethod(visa).build(),writeKey("pay"));
        assertEquals(result.invoice(),paid.getId());
        assertEquals(Boolean.FALSE,paid.getLivemode());
        assertEquals(plan.remoteCustomer(),paid.getCustomer());
        assertEquals("paid",paid.getStatus());
        assertEquals(quote.dueNowMinor(),paid.getAmountPaid());
        var settledAllocation=allocation(client,result.invoice());
        assertEquals(original.getId(),settledAllocation.getId());
        assertEquals(intent.getId(),settledAllocation.getPayment().getPaymentIntent());
        assertEquals("paid",settledAllocation.getStatus());
        var settled=client.v1().paymentIntents().retrieve(intent.getId());
        assertEquals(Boolean.FALSE,settled.getLivemode());
        assertEquals("succeeded",settled.getStatus());
        assertEquals(quote.dueNowMinor(),settled.getAmountReceived());
        assertNotEquals(declinedCharge,settled.getLatestCharge());
        var charge=client.v1().charges().retrieve(settled.getLatestCharge());
        assertEquals(Boolean.FALSE,charge.getLivemode());
        assertEquals(plan.remoteCustomer(),charge.getCustomer());
        assertEquals(intent.getId(),charge.getPaymentIntent());
        assertEquals(visa,charge.getPaymentMethod());
        assertEquals("succeeded",charge.getStatus());
        assertEquals(Boolean.TRUE,charge.getCaptured());
        assertEquals(quote.dueNowMinor(),charge.getAmountCaptured());
        // Stripe can post invoice settlement before applying the pending subscription update.
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(45).toNanos();
        var updated=client.v1().subscriptions().retrieve(plan.remoteSubscription());
        while (updated.getPendingUpdate()!=null && System.nanoTime()<deadline) {
            assertEquals(result.invoice(),updated.getLatestInvoice());
            assertEquals(result.pendingExpiry(),updated.getPendingUpdate().getExpiresAt());
            Thread.sleep(500);
            updated=client.v1().subscriptions().retrieve(plan.remoteSubscription());
        }
        assertNull(updated.getPendingUpdate());
        assertEquals(result.invoice(),updated.getLatestInvoice());
        assertEquals(plan.remoteTarget(),updated.getItems().getData().getFirst().getPrice().getId());
        assertAccessAndCash(plan,plan.price(),1);

        reconcile(plan);
        assertAccessAndCash(plan,plan.target(),2);
        assertNull(local(plan).pendingChange());
        assertEquals(baseline.paidThrough(),local(plan).paidThrough());
        assertEquals(baseline.currentPeriod(),local(plan).currentPeriod());
        assertEquals(SubscriptionOperation.Status.SUCCEEDED,
            operations.find(plan.site(),plan.subscriptionId(),change.operationId()).orElseThrow().status());
        assertEquals(change.operationId(),changes.change(command).operationId());
        reconcile(plan);
        assertAccessAndCash(plan,plan.target(),2);
        assertEquals(initialCash,initialCash(plan));
        assertEquals(result,proofs.result(plan.account().id(),change.operationId()).orElseThrow());
        assertEquals(saved,proofs.quote(plan.account().id(),saved.id()).orElseThrow());
        deliverCallbacks(plan.site(),Set.of(plan.subscriptionId()));
        assertEquals(1,count("""
            select count(*) from subscription_billing_records b
            join payments p on p.billing_record_id=b.id and p.subscription_id=b.subscription_id and p.site_id=b.site_id
              and p.subscription_invoice_reference=b.provider_invoice_reference and p.payment_account_id=b.payment_account_id
              and p.source='SUBSCRIPTION' and p.status='SUCCEEDED' and p.amount_minor=b.amount_due_minor and p.currency=b.currency
            join payment_events cash on cash.payment_id=p.id and cash.event_type='PAYMENT_SUCCEEDED'
            join outbox_events payment_callback on payment_callback.id=cash.id and payment_callback.callback_type='payment.succeeded'
              and payment_callback.status='DELIVERED' and payment_callback.attempt_count=1
            join subscription_events change on change.billing_record_id=b.id and change.subscription_id=b.subscription_id
              and change.event_type='CHANGE_APPLIED'
            join outbox_events change_callback on change_callback.id=change.id and change_callback.callback_type='subscription.updated'
              and change_callback.status='DELIVERED' and change_callback.attempt_count=1
            where b.site_id=? and b.subscription_id=? and b.payment_account_id=? and p.customer_id=?
              and b.provider_invoice_reference=? and p.provider_payment_reference=? and b.amount_due_minor=?
              and b.kind='PRORATION' and b.settlement='CONFIRMED_CASH' and b.billed_price_id=? and b.previous_price_id=?
              and b.period_start=? and b.period_end=?
            """,plan.site().value(),plan.subscriptionId(),plan.account().id().value(),plan.customer().id().value(),
            result.invoice(),intent.getId(),quote.dueNowMinor(),plan.target().value(),plan.price().value(),
            Timestamp.from(saved.anchor()),Timestamp.from(saved.paidThrough())));
    }

    private String setDecliningDefault(StripeClient client,PaidPlan plan) throws StripeException {
        var remote=client.v1().subscriptions().retrieve(plan.remoteSubscription());
        assertEquals(plan.remoteCustomer(),remote.getCustomer());
        assertEquals(Boolean.FALSE,remote.getLivemode());
        String visa=remote.getDefaultPaymentMethod();
        assertNotNull(visa,"initial paid subscription has a saved payment method");
        var original=client.v1().paymentMethods().retrieve(visa);
        assertEquals(plan.remoteCustomer(),original.getCustomer());
        assertEquals(Boolean.FALSE,original.getLivemode());
        var method=client.v1().paymentMethods().attach("pm_card_chargeCustomerFail",
            PaymentMethodAttachParams.builder().setCustomer(plan.remoteCustomer()).build(),writeKey("attach-decline"));
        assertEquals(Boolean.FALSE,method.getLivemode());
        assertEquals(plan.remoteCustomer(),method.getCustomer());
        var updated=client.v1().subscriptions().update(plan.remoteSubscription(),
            SubscriptionUpdateParams.builder().setDefaultPaymentMethod(method.getId()).build(),writeKey("declining-default"));
        assertEquals(plan.remoteCustomer(),updated.getCustomer());
        assertEquals(method.getId(),updated.getDefaultPaymentMethod());
        return visa;
    }

    private InvoicePayment allocation(StripeClient client,String invoice) throws StripeException {
        var allocations=client.v1().invoicePayments().list(InvoicePaymentListParams.builder().setInvoice(invoice).setLimit(2L).build());
        assertEquals(Boolean.FALSE,allocations.getHasMore());
        assertEquals(1,allocations.getData().size());
        var allocation=allocations.getData().getFirst();
        assertEquals(Boolean.FALSE,allocation.getLivemode());
        assertEquals(Boolean.TRUE,allocation.getIsDefault());
        assertEquals(invoice,allocation.getInvoice());
        assertEquals("payment_intent",allocation.getPayment().getType());
        return allocation;
    }

    private void assertAccessAndCash(PaidPlan plan,PriceId price,int payments) {
        var current=local(plan);
        var access=new SubscriptionAccessPolicy().evaluate(current,Instant.now());
        assertTrue(access.allowed());
        assertEquals(AccessDecision.Reason.PAID,access.reason());
        assertEquals(price,access.entitledPriceId());
        assertEquals(price,current.billingPrice().priceId());
        assertEquals(payments,count("select count(*) from payments where site_id=? and subscription_id=?",plan.site().value(),plan.subscriptionId()));
    }

    private record InitialCash(UUID payment,UUID bill,String invoice,String intent) {}
    private InitialCash initialCash(PaidPlan plan) {
        return database.queryForObject("""
            select p.id,b.id,b.provider_invoice_reference,p.provider_payment_reference
            from subscription_billing_records b join payments p on p.billing_record_id=b.id
              and p.subscription_id=b.subscription_id and p.site_id=b.site_id and p.payment_account_id=b.payment_account_id
              and p.subscription_invoice_reference=b.provider_invoice_reference and p.status='SUCCEEDED' and p.amount_minor=2000
            where b.subscription_id=? and b.kind='INITIAL' and b.settlement='CONFIRMED_CASH'
            """,(row,n)->new InitialCash(row.getObject(1,UUID.class),row.getObject(2,UUID.class),
                row.getString(3),row.getString(4)),plan.subscriptionId());
    }

    private long count(String sql,Object... args) { return database.queryForObject(sql,Long.class,args); }
    private RequestOptions writeKey(String action) {
        return RequestOptions.builder().setIdempotencyKey("plan-upgrade:"+runId()+":"+action).build();
    }
}
