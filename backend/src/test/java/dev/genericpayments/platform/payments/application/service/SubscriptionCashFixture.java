package dev.genericpayments.platform.payments.application.service;

import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.payments.application.port.in.RecordSubscriptionCashUseCase;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

@org.springframework.test.context.TestPropertySource(properties="payment.telemetry.site-keys[0]=test-site")
public abstract class SubscriptionCashFixture extends ProviderTestFixture {
    @Autowired protected SubscriptionStore subscriptions;
    @Autowired protected BillingRecordStore bills;
    @Autowired protected SubscriptionTransactionPort tx;
    @Autowired protected SubscriptionCashPort cash;
    @Autowired protected RecordSubscriptionCashUseCase paymentsCash;
    @Autowired protected CustomerStore customers;
    protected Customer customer;
    protected Subscription subscription;
    protected SubscriptionPriceTerms terms;
    protected BillingRecord bill;
    @BeforeEach void cashFixture() {
        customer=customers.save(Customer.create(new CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        var product=new ProductId(UUID.randomUUID());var price=new PriceId(UUID.randomUUID());
        jdbc.update("insert into products(id,site_id,product_key,name,status,created_at,updated_at) values (?,?,?,'Plan','ACTIVE',?,?)",product.value(),site.value(),"product-"+product.value(),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME));
        jdbc.update("insert into prices(id,site_id,product_id,price_key,price_version,price_type,amount_minor,currency,billing_interval,trial_days,tax_mode,status,published_at,created_at,updated_at) values (?,?,?,'monthly',1,'SUBSCRIPTION',2000,'EUR','MONTH',0,'NONE','PUBLISHED',?,?,?)",price.value(),site.value(),product.value(),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME),Timestamp.from(INITIAL_TIME));
        terms=new SubscriptionPriceTerms(price,"monthly",1,product,Money.of(2000,"EUR"),BillingInterval.MONTH,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
        subscription=subscriptions.save(new Subscription(UUID.randomUUID(),site,customer.id(),product,account.id(),terms,null,Subscription.Status.INCOMPLETE,null,null,null,null,null,null,null,null,0,INITIAL_TIME,INITIAL_TIME,null),-1);
        bill=bills.upsert(new BillingRecord(UUID.randomUUID(),site,subscription.id(),account.id(),"invoice-1",BillingRecord.Kind.PRORATION,terms,terms,new Subscription.Period(INITIAL_TIME,INITIAL_TIME.plusSeconds(86400)),700,"EUR",BillingRecord.Settlement.OPEN,0,INITIAL_TIME,INITIAL_TIME));
    }
    protected SubscriptionBillingContext context() {
        return new SubscriptionBillingContext(subscription.id(),bill.id(),SubscriptionBillingContext.Kind.PRORATION,terms.productId(),terms.priceId(),1,terms.priceId(),1,bill.period().start(),bill.period().end(),bill.providerInvoiceReference());
    }
    protected RecordSubscriptionCashUseCase.Command command(String reference,PaymentStatus status,String fact) {
        return new RecordSubscriptionCashUseCase.Command(site,customer.id().value(),account.id(),account.provider(),context(),reference,Money.of(700,"EUR"),TaxMode.NONE,status,INITIAL_TIME,fact);
    }
    protected RecordSubscriptionCashUseCase.Result record(String reference,PaymentStatus status,String fact) {
        return tx.required(()->cash.record(command(reference,status,fact)));
    }
    protected Payment stored(PaymentId id){return load.findById(site,id).orElseThrow();}
    /** Independent simulator truth, deliberately no legacy fake PAYMENT row. */
    protected void providerCash(String reference,PaymentStatus status) {
        jdbc.update("insert into fake_subscription_objects(payment_account_id,subscription_id,provider_reference,state) values (?,?,?,'{}') on conflict do nothing",account.id().value(),subscription.id(),"fake-sub-1");
        jdbc.update("insert into fake_subscription_invoices(payment_account_id,subscription_id,provider_reference,ordinal,invoice) values (?,?,?,1,'{}') on conflict do nothing",account.id().value(),subscription.id(),bill.providerInvoiceReference());
        jdbc.update("insert into fake_subscription_cash_objects(payment_account_id,subscription_id,invoice_reference,provider_reference,amount_minor,currency,status,occurred_at,cash_object) values (?,?,?,?,700,'EUR',?,?,'{}')",account.id().value(),subscription.id(),bill.providerInvoiceReference(),reference,status.name(),Timestamp.from(INITIAL_TIME));
    }
}
