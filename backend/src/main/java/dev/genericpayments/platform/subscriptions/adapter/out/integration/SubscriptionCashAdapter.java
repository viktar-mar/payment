package dev.genericpayments.platform.subscriptions.adapter.out.integration;

import dev.genericpayments.platform.payments.application.port.in.RecordSubscriptionCashUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import java.util.Objects;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class SubscriptionCashAdapter implements SubscriptionCashPort {
    private final SubscriptionStore subscriptions;
    private final BillingRecordStore bills;
    private final RecordSubscriptionCashUseCase payments;
    private final SubscriptionObjectPort objects;
    private final Clock clock;
    public SubscriptionCashAdapter(SubscriptionStore subscriptions,BillingRecordStore bills,RecordSubscriptionCashUseCase payments,SubscriptionObjectPort objects,Clock clock) {
        this.subscriptions=subscriptions;this.bills=bills;this.payments=payments;this.objects=objects;this.clock=Objects.requireNonNull(clock);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public RecordSubscriptionCashUseCase.Result record(RecordSubscriptionCashUseCase.Command c) {
        var x=c.context();var s=subscriptions.lock(c.siteId(),x.subscriptionId());var b=bills.lock(c.siteId(),x.billingRecordId());
        if(!s.customerId().value().equals(c.customerId())||!s.paymentAccountId().equals(c.accountId())||!s.productId().equals(x.productId())
            ||!b.subscriptionId().equals(s.id())||!b.accountId().equals(c.accountId())||!b.billedPrice().productId().equals(x.productId())
            ||!b.kind().name().equals(x.kind().name())||!b.billedPrice().priceId().equals(x.priceId())||b.billedPrice().version()!=x.priceVersion()
            ||!Objects.equals(b.previousPrice()==null?null:b.previousPrice().priceId(),x.previousPriceId())
            ||!Objects.equals(b.previousPrice()==null?null:b.previousPrice().version(),x.previousPriceVersion())
            ||!b.period().start().equals(x.periodStart())||!b.period().end().equals(x.periodEnd())||!b.providerInvoiceReference().equals(x.providerInvoiceReference())
            ||!b.currency().equals(c.amount().currency())||b.billedPrice().taxMode()!=c.taxMode()||c.amount().minorUnits()>b.amountDueMinor())
            throw new IllegalArgumentException("Cash ownership or billing terms mismatch");
        var recordedAt=clock.instant();
        var result=payments.record(c);
        objects.bindPayment(c.provider(),s,c.providerPaymentReference(),b.id(),result.paymentId(),recordedAt);
        return result;
    }
}
