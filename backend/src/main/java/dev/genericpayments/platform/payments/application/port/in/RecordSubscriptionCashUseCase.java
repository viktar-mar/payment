package dev.genericpayments.platform.payments.application.port.in;

import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.*;

public interface RecordSubscriptionCashUseCase {
    record Command(SiteId siteId,UUID customerId,PaymentAccountId accountId,PaymentProviderType provider,
                   SubscriptionBillingContext context,String providerPaymentReference,Money amount,TaxMode taxMode,
                   PaymentStatus status,Instant occurredAt,String factKey) {
        public Command {
            Objects.requireNonNull(siteId);Objects.requireNonNull(customerId);Objects.requireNonNull(accountId);
            Objects.requireNonNull(provider);Objects.requireNonNull(context);Objects.requireNonNull(amount);
            Objects.requireNonNull(taxMode);Objects.requireNonNull(status);Objects.requireNonNull(occurredAt);
            for(String value:new String[]{providerPaymentReference,factKey})
                if(value==null||value.length()>255||!value.matches("[A-Za-z0-9_.:/-]+"))throw new IllegalArgumentException("Invalid cash identity");
            if(status==PaymentStatus.PARTIALLY_REFUNDED||status==PaymentStatus.REFUNDED)throw new IllegalArgumentException("Refunds require the refund fact flow");
        }
    }
    record Result(PaymentId paymentId,boolean newFact) {}
    Result record(Command command);
}
