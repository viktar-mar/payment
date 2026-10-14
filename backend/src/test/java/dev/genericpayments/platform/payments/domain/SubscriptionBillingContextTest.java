package dev.genericpayments.platform.payments.domain;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import org.junit.jupiter.api.Test;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

class SubscriptionBillingContextTest {
    @Test void subscriptionSourceIsExplicit() {
        assertThat(PaymentSource.valueOf("SUBSCRIPTION")).isEqualTo(PaymentSource.SUBSCRIPTION);
    }
    @Test void cashMoneyStillRejectsZero() {
        assertThatThrownBy(() -> Money.of(0,"EUR")).isInstanceOf(DomainValidationException.class);
    }
    final Instant now=Instant.parse("2026-10-01T00:00:00Z");
    SubscriptionBillingContext context() {return new SubscriptionBillingContext(UUID.randomUUID(),UUID.randomUUID(),SubscriptionBillingContext.Kind.PRORATION,new ProductId(UUID.randomUUID()),new PriceId(UUID.randomUUID()),1,null,null,now,now.plusSeconds(86400),"invoice-1");}
    Payment payment(SubscriptionBillingContext c,UUID customer){return Payment.subscription(new PaymentId(UUID.randomUUID()),new SiteId(UUID.randomUUID()),customer,new PaymentAccountId(UUID.randomUUID()),c,Money.of(700,"EUR"),TaxMode.NONE,Map.of(),now);}
    @Test void proratedCashRetainsContextThroughEveryMutationAndRefund() {
        var c=context();var p=payment(c,UUID.randomUUID());assertThat(p.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(p.priceSnapshot()).isNull();assertThat(p.externalOrderId()).isNull();assertThat(p.donationId()).isNull();
        p=p.withProviderPaymentReference("cash",now).requiresPaymentMethod(now).requiresAction(now).processing(now).succeeded(now);
        assertThat(p.subscriptionContext()).isEqualTo(c);assertThat(p.refundableAmountMinor()).isEqualTo(700);
        p=p.refundSucceeded(200,now);assertThat(p.refundableAmountMinor()).isEqualTo(500);assertThat(p.subscriptionContext()).isEqualTo(c);
        p=p.refundSucceeded(700,now);assertThat(p.status()).isEqualTo(PaymentStatus.REFUNDED);assertThat(p.subscriptionContext()).isEqualTo(c);
        var terminal=p;assertThatThrownBy(()->terminal.withProviderPaymentReference("replacement",now)).isInstanceOf(DomainValidationException.class);
    }
    @Test void contextRequiresCustomerValidPeriodAndCompletePreviousVersion() {
        assertThatThrownBy(()->payment(context(),null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(()->payment(null,UUID.randomUUID())).isInstanceOf(DomainValidationException.class);
        var c=context();
        assertThatThrownBy(()->new SubscriptionBillingContext(c.subscriptionId(),c.billingRecordId(),c.kind(),c.productId(),c.priceId(),0,null,null,now,now.plusSeconds(1),"invoice")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(()->new SubscriptionBillingContext(c.subscriptionId(),c.billingRecordId(),c.kind(),c.productId(),c.priceId(),1,c.priceId(),null,now,now.plusSeconds(1),"invoice")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(()->new SubscriptionBillingContext(c.subscriptionId(),c.billingRecordId(),c.kind(),c.productId(),c.priceId(),1,null,null,now,now,"invoice")).isInstanceOf(DomainValidationException.class);
    }
}
