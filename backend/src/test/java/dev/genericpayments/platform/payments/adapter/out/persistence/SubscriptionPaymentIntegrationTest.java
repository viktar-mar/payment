package dev.genericpayments.platform.payments.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import dev.genericpayments.platform.payments.application.service.SubscriptionCashFixture;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.catalog.domain.*;
import java.util.*;

class SubscriptionPaymentIntegrationTest extends SubscriptionCashFixture {
    @Test void cashStorageStillRejectsZero() {
        var p=record("cash",PaymentStatus.SUCCEEDED,"fact");
        assertThatThrownBy(() -> jdbc.update("update payments set amount_minor=0 where id=?",p.paymentId().value()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void proratedCashRoundTripsAndDatabaseProtectsContextAndReference() {
        var p=Payment.subscription(new PaymentId(UUID.randomUUID()),site,customer.id().value(),account.id(),context(),Money.of(700,"EUR"),TaxMode.NONE,Map.of(),INITIAL_TIME);
        save.save(p);assertThat(stored(p.id())).isEqualTo(p);
        var linked=save.save(p.withProviderPaymentReference("cash",INITIAL_TIME));
        assertThat(save.save(linked.succeeded(INITIAL_TIME)).subscriptionContext()).isEqualTo(context());
        for(String assignment:List.of("subscription_id=null","billing_record_id=null","subscription_price_version=2","customer_id=null","subscription_period_end=subscription_period_start","source='DYNAMIC'","provider_payment_reference='replacement'","amount_minor=600","subscription_invoice_reference='different'"))
            assertThatThrownBy(()->jdbc.update("update payments set "+assignment+" where id=?",p.id().value())).isInstanceOf(DataIntegrityViolationException.class);
        var duplicate=Payment.subscription(new PaymentId(UUID.randomUUID()),site,customer.id().value(),account.id(),context(),Money.of(700,"EUR"),TaxMode.NONE,Map.of(),INITIAL_TIME).withProviderPaymentReference("cash",INITIAL_TIME);
        assertThatThrownBy(()->save.save(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void foreignBillAndCustomerCannotBeLinkedEvenByDirectPersistence() {
        var bad=new SubscriptionBillingContext(subscription.id(),UUID.randomUUID(),context().kind(),terms.productId(),terms.priceId(),1,terms.priceId(),1,bill.period().start(),bill.period().end(),"invoice-1");
        assertThatThrownBy(()->save.save(Payment.subscription(new PaymentId(UUID.randomUUID()),site,customer.id().value(),account.id(),bad,Money.of(700,"EUR"),TaxMode.NONE,Map.of(),INITIAL_TIME))).isInstanceOf(DataIntegrityViolationException.class);
        var other=customers.save(dev.genericpayments.platform.customers.domain.Customer.create(new dev.genericpayments.platform.customers.domain.CustomerId(UUID.randomUUID()),site,null,null,INITIAL_TIME));
        assertThatThrownBy(()->save.save(Payment.subscription(new PaymentId(UUID.randomUUID()),site,other.id().value(),account.id(),context(),Money.of(700,"EUR"),TaxMode.NONE,Map.of(),INITIAL_TIME))).isInstanceOf(DataIntegrityViolationException.class);
    }
}
