package dev.genericpayments.platform.payments.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.in.RecordSubscriptionCashUseCase;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionProviderModels.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SubscriptionCashIntegrationTest extends SubscriptionCashFixture {
    @Autowired dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionReadPort reads;
    @Test void oversizedLinkedPaymentCollectionsRejectRatherThanSilentlyTruncate() {
        for(int i=0;i<101;i++)record("many-"+i,PaymentStatus.FAILED,"fact-"+i);
        assertThatThrownBy(()->reads.billingRecords(site,subscription.id(),20,null)).isInstanceOfSatisfying(dev.genericpayments.platform.shared.application.PaymentApplicationException.class,e->assertThat(e.status()).isEqualTo(413));
    }
    @Test void cashBridgePublishesOneOwnedObjectMappingPerActualPayment() {
        var first=record("mapped-cash",PaymentStatus.SUCCEEDED,"paid");
        record("mapped-cash",PaymentStatus.SUCCEEDED,"paid-replay");
        assertThat(jdbc.queryForObject("select count(*) from subscription_provider_objects where site_id=? and subscription_id=? and billing_record_id=? and payment_account_id=? and object_type='PAYMENT' and provider_reference='mapped-cash'",Long.class,site.value(),subscription.id(),bill.id(),account.id().value())).isEqualTo(1);
        assertThat(count("payments")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payment_id from subscription_provider_objects where object_type='PAYMENT'",UUID.class)).isEqualTo(first.paymentId().value());
    }
    @Autowired ProviderInboxService inbox;
    @Autowired ProviderInboxWorker worker;
    @Autowired ReconcilePaymentService reconcile;
    @Autowired RefundCoordinator refunds;
    @Test void delimitedObjectAndFactTuplesRemainDistinctAndReplayStable() {
        var first=record("a:b",PaymentStatus.SUCCEEDED,"c");
        var second=record("a",PaymentStatus.SUCCEEDED,"b:c");
        assertThat(first.newFact()).isTrue();
        assertThat(second.newFact()).isTrue();
        assertThat(second.paymentId()).isNotEqualTo(first.paymentId());
        assertThat(stored(first.paymentId()).providerPaymentReference()).isEqualTo("a:b");
        assertThat(stored(second.paymentId()).providerPaymentReference()).isEqualTo("a");
        assertThat(stored(first.paymentId()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(stored(second.paymentId()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        var eventIds=jdbc.queryForList("select id from payment_events order by id",UUID.class);
        assertThat(eventIds).hasSize(2).doesNotHaveDuplicates();
        assertThat(record("a:b",PaymentStatus.SUCCEEDED,"c")).isEqualTo(new RecordSubscriptionCashUseCase.Result(first.paymentId(),false));
        assertThat(record("a",PaymentStatus.SUCCEEDED,"b:c")).isEqualTo(new RecordSubscriptionCashUseCase.Result(second.paymentId(),false));
        assertThat(count("payments")).isEqualTo(2);
        assertThat(jdbc.queryForList("select id from payment_events order by id",UUID.class)).isEqualTo(eventIds);
        assertThat(jdbc.queryForList("select payment_id from subscription_provider_objects where object_type='PAYMENT'",UUID.class)).containsExactlyInAnyOrder(first.paymentId().value(),second.paymentId().value());
    }
    @Test void independentCashMustMatchPinnedSubscriptionInvoiceAmountCurrencyAndReference() {
        providerCash("owned-cash",PaymentStatus.SUCCEEDED);
        var p=stored(record("owned-cash",PaymentStatus.SUCCEEDED,"paid").paymentId());
        var lookup=ProviderPaymentLookup.forPayment(account,p.id(),"owned-cash");
        assertThat(fake.retrievePayment(lookup).status()).isEqualTo(ProviderState.Status.SUCCEEDED);
        assertThatThrownBy(()->fake.retrievePayment(ProviderPaymentLookup.forPayment(account,p.id(),"wrong-ref")))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class).hasRootCauseMessage("Provider object not found");
        jdbc.update("insert into fake_subscription_invoices(payment_account_id,subscription_id,provider_reference,ordinal,invoice) values (?,?,'other-invoice',2,'{}')",account.id().value(),subscription.id());
        var other=UUID.randomUUID();
        jdbc.update("insert into fake_subscription_objects(payment_account_id,subscription_id,provider_reference,state) values (?,?,'other-sub','{}')",account.id().value(),other);
        jdbc.update("insert into fake_subscription_invoices(payment_account_id,subscription_id,provider_reference,ordinal,invoice) values (?,?,'foreign-invoice',1,'{}')",account.id().value(),other);
        for(String mutation:List.of("amount_minor=600","currency='USD'","invoice_reference='other-invoice'","subscription_id='"+other+"',invoice_reference='foreign-invoice'")) {
            jdbc.update("update fake_subscription_cash_objects set "+mutation+" where payment_account_id=? and provider_reference='owned-cash'",account.id().value());
            assertThatThrownBy(()->fake.retrievePayment(lookup))
                    .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class).hasRootCauseMessage("Provider object not found");
            jdbc.update("update fake_subscription_cash_objects set amount_minor=700,currency='EUR',subscription_id=?,invoice_reference=? where payment_account_id=? and provider_reference='owned-cash'",subscription.id(),bill.providerInvoiceReference(),account.id().value());
        }
        jdbc.update("update fake_subscription_cash_objects set status='FAILED' where payment_account_id=? and provider_reference='owned-cash'",account.id().value());
        assertThat(stored(p.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(fake.retrievePayment(lookup).status()).isEqualTo(ProviderState.Status.FAILED);
    }
    @Test void sameObjectReferenceAcrossAccountsStaysIsolatedAfterSiteRouteChange() {
        providerCash("shared-object",PaymentStatus.SUCCEEDED);
        var first=record("shared-object",PaymentStatus.PROCESSING,"processing");
        var secondAccount=PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),"other-account",PaymentProviderType.FAKE,PaymentAccountMode.SANDBOX,"other-fake","config-ref");
        jdbc.update("insert into payment_accounts values (?, 'other-account','FAKE','SANDBOX','ACTIVE','other-fake','config-ref',?,0)",secondAccount.id().value(),java.sql.Timestamp.from(INITIAL_TIME));
        jdbc.update("update sites set payment_account_id=? where id=?",secondAccount.id().value(),site.value());
        var otherCustomer=customers.save(dev.genericpayments.platform.customers.domain.Customer.create(new dev.genericpayments.platform.customers.domain.CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        var otherSubscription=subscriptions.save(new Subscription(UUID.randomUUID(),site,otherCustomer.id(),terms.productId(),secondAccount.id(),terms,null,Subscription.Status.INCOMPLETE,null,null,null,null,null,null,null,null,0,INITIAL_TIME,INITIAL_TIME,null),-1);
        var otherBill=bills.upsert(new BillingRecord(UUID.randomUUID(),site,otherSubscription.id(),secondAccount.id(),"invoice-2",BillingRecord.Kind.RENEWAL,terms,null,bill.period(),700,"EUR",BillingRecord.Settlement.OPEN,0,INITIAL_TIME,INITIAL_TIME));
        var otherContext=new SubscriptionBillingContext(otherSubscription.id(),otherBill.id(),SubscriptionBillingContext.Kind.RENEWAL,terms.productId(),terms.priceId(),1,null,null,bill.period().start(),bill.period().end(),"invoice-2");
        var second=tx.required(()->cash.record(new RecordSubscriptionCashUseCase.Command(site,otherCustomer.id().value(),secondAccount.id(),secondAccount.provider(),otherContext,"shared-object",Money.of(700,"EUR"),TaxMode.NONE,PaymentStatus.FAILED,INITIAL_TIME,"failed")));
        assertThat(first.paymentId()).isNotEqualTo(second.paymentId());
        reconcile.reconcile(site,first.paymentId(),"historical-account");
        assertThat(stored(first.paymentId()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(stored(second.paymentId()).status()).isEqualTo(PaymentStatus.FAILED);
        assertThatThrownBy(()->fake.retrievePayment(ProviderPaymentLookup.forPayment(secondAccount,first.paymentId(),"shared-object")))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class).hasRootCauseMessage("Provider object not found");
        var r=refunds.create(new dev.genericpayments.platform.payments.application.port.in.CreateRefundCommand(site,first.paymentId(),UUID.randomUUID(),200,"EUR","REQUESTED_BY_CUSTOMER",null,"historical-refund","historical-refund"));
        assertThat(r.status()).isEqualTo(RefundStatus.PROCESSING);
        assertThatThrownBy(()->fake.retrieveRefund(ProviderRefundLookup.forRefund(secondAccount,first.paymentId(),r.id(),r.providerRefundReference())))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class).hasRootCauseMessage("Provider object not found");
        assertThat(count("payments")).isEqualTo(2);
        var legacy=save.save(payment().withProviderPaymentReference("legacy-object",INITIAL_TIME).succeeded(INITIAL_TIME));
        assertThatThrownBy(()->reconcile.reconcile(site,legacy.id(),"legacy-route")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->refunds.create(new dev.genericpayments.platform.payments.application.port.in.CreateRefundCommand(site,legacy.id(),UUID.randomUUID(),100,"EUR","REQUESTED_BY_CUSTOMER",null,"legacy-refund","legacy-refund"))).isInstanceOf(RuntimeException.class);
        jdbc.update("update payment_accounts set account_status='DISABLED' where id=?",account.id().value());
        assertThatThrownBy(()->reconcile.reconcile(site,first.paymentId(),"disabled-account")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->refunds.create(new dev.genericpayments.platform.payments.application.port.in.CreateRefundCommand(site,first.paymentId(),UUID.randomUUID(),100,"EUR","REQUESTED_BY_CUSTOMER",null,"disabled-refund","disabled-refund"))).isInstanceOf(RuntimeException.class);
        jdbc.update("update payment_accounts set account_status='ACTIVE' where id=?",account.id().value());
        jdbc.update("update sites set status='DISABLED' where id=?",site.value());
        assertThatThrownBy(()->reconcile.reconcile(site,first.paymentId(),"disabled-site")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->refunds.create(new dev.genericpayments.platform.payments.application.port.in.CreateRefundCommand(site,first.paymentId(),UUID.randomUUID(),100,"EUR","REQUESTED_BY_CUSTOMER",null,"disabled-site-refund","disabled-site-refund"))).isInstanceOf(RuntimeException.class);
    }
    @Test void invoicePaymentWebhookAndReconcileProduceOneSuccessFact() {
        providerCash("cash-1",PaymentStatus.SUCCEEDED);
        var first=record("cash-1",PaymentStatus.SUCCEEDED,"invoice-success");
        assertThat(first.newFact()).isTrue();
        assertThat(record("cash-1",PaymentStatus.SUCCEEDED,"invoice-success")).isEqualTo(new RecordSubscriptionCashUseCase.Result(first.paymentId(),false));
        assertThat(record("cash-1",PaymentStatus.SUCCEEDED,"payment-success").newFact()).isFalse();
        inbox.receive(new ProviderEventEnvelope(account.provider(),account.id(),"payment-webhook","cash-1",PaymentEventType.PAYMENT_SUCCEEDED,INITIAL_TIME,INITIAL_TIME,Map.of()));worker.runOnce();
        reconcile.reconcile(site,first.paymentId(),"cash-reconcile");
        assertThat(count("payments")).isEqualTo(1);assertThat(count("payment_events")).isEqualTo(1);
        assertThat(count("payment_attempts")).isZero();assertThat(count("fake_provider_objects")).isZero();
        assertThat(stored(first.paymentId()).money()).isEqualTo(Money.of(700,"EUR"));
        assertThat(subscriptions.find(site,subscription.id()).orElseThrow()).isEqualTo(subscription);
    }
    @Test void terminalDeclineKeepsItsObjectAndNewObjectGetsAnotherPaymentOnSameBill() {
        var declined=record("declined",PaymentStatus.FAILED,"decline");
        var paid=record("paid",PaymentStatus.SUCCEEDED,"paid");
        assertThat(paid.paymentId()).isNotEqualTo(declined.paymentId());
        assertThat(stored(declined.paymentId()).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(stored(paid.paymentId()).subscriptionContext()).isEqualTo(context());
        assertThat(record("declined",PaymentStatus.SUCCEEDED,"late-contradiction").newFact()).isFalse();
        subscriptions.save(subscription.cancel("CONFIRMED",INITIAL_TIME),0);
        record("delayed",PaymentStatus.SUCCEEDED,"late-cash");
        assertThat(subscriptions.find(site,subscription.id()).orElseThrow().billingStatus()).isEqualTo(Subscription.Status.CANCELLED);
    }
    @Test void mandatoryBoundaryAndOuterRollbackProtectAllCashWrites() {
        assertThatThrownBy(()->cash.record(command("cash",PaymentStatus.SUCCEEDED,"fact"))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(()->paymentsCash.record(command("cash",PaymentStatus.SUCCEEDED,"fact"))).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(()->tx.required(()->{cash.record(command("cash",PaymentStatus.SUCCEEDED,"fact"));throw new IllegalStateException("lifecycle rollback");})).hasMessage("lifecycle rollback");
        assertThat(count("payments")).isZero();assertThat(count("payment_events")).isZero();
        assertThat(count("subscription_provider_objects")).isZero();
    }
    @Test void ownershipAmountAndImmutableObjectTermsAreChecked() {
        var c=command("cash",PaymentStatus.SUCCEEDED,"fact");
        var wrongCustomer=new RecordSubscriptionCashUseCase.Command(site,UUID.randomUUID(),account.id(),account.provider(),context(),"cash",c.amount(),c.taxMode(),c.status(),INITIAL_TIME,"bad");
        assertThatThrownBy(()->tx.required(()->cash.record(wrongCustomer))).isInstanceOf(IllegalArgumentException.class);
        var tooMuch=new RecordSubscriptionCashUseCase.Command(site,c.customerId(),account.id(),account.provider(),context(),"cash",Money.of(2000,"EUR"),c.taxMode(),c.status(),INITIAL_TIME,"bad");
        assertThatThrownBy(()->tx.required(()->cash.record(tooMuch))).isInstanceOf(IllegalArgumentException.class);
        record("cash",PaymentStatus.PROCESSING,"processing");
        var changed=new RecordSubscriptionCashUseCase.Command(site,c.customerId(),account.id(),account.provider(),context(),"cash",Money.of(600,"EUR"),c.taxMode(),c.status(),INITIAL_TIME,"changed");
        assertThatThrownBy(()->tx.required(()->cash.record(changed))).isInstanceOf(IllegalArgumentException.class);
        assertThat(stored(load.findBySiteId(site).getFirst().id()).status()).isEqualTo(PaymentStatus.PROCESSING);
    }
    @Test void zeroInvoicesCannotBecomePositiveCashImports() {
        var zero=bills.upsert(new BillingRecord(UUID.randomUUID(),site,subscription.id(),account.id(),"zero",BillingRecord.Kind.RENEWAL,terms,null,bill.period(),0,"EUR",BillingRecord.Settlement.ZERO_AMOUNT,0,INITIAL_TIME,INITIAL_TIME));
        var context=new SubscriptionBillingContext(subscription.id(),zero.id(),SubscriptionBillingContext.Kind.RENEWAL,terms.productId(),terms.priceId(),1,null,null,bill.period().start(),bill.period().end(),"zero");
        var c=new RecordSubscriptionCashUseCase.Command(site,customer.id().value(),account.id(),account.provider(),context,"invented",Money.of(1,"EUR"),TaxMode.NONE,PaymentStatus.SUCCEEDED,INITIAL_TIME,"invented");
        assertThatThrownBy(()->tx.required(()->cash.record(c))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new CashObject("zero",0,"EUR",PaymentStatus.SUCCEEDED,INITIAL_TIME)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Invoice("trial-zero",BillingRecord.Kind.INITIAL,terms,null,bill.period(),0,"EUR",SettlementKind.ZERO,List.of()).cashObjects()).isEmpty();
        assertThat(count("payments")).isZero();
    }
}
