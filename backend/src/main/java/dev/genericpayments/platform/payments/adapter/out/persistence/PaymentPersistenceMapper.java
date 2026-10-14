package dev.genericpayments.platform.payments.adapter.out.persistence;

import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class PaymentPersistenceMapper {
    private final ObjectMapper json;
    PaymentPersistenceMapper(ObjectMapper json) { this.json=json; }

    String metadata(Map<String,String> values) { return json.writeValueAsString(values); }
    Map<String,String> metadata(String value) {
        var node=json.readTree(value);
        if (!node.isObject()) throw new IllegalArgumentException("technical metadata must be an object");
        Map<String,String> result=new HashMap<>();
        for (var property:node.properties()) {
            if (!property.getValue().isString()) throw new IllegalArgumentException("technical metadata must contain only strings");
            result.put(property.getKey(),property.getValue().asString());
        }
        // Aggregate constructors enforce the bounded safe-metadata policy after JSON decoding.
        return result;
    }
    void copy(Payment p, PaymentJpaEntity e) {
        e.id=p.id().value(); e.siteId=p.siteId().value(); e.customerId=p.customerId();
        e.paymentAccountId=p.paymentAccountId().value(); e.source=p.source().name();
        e.priceId=p.priceId()==null?null:p.priceId().value();
        PriceSnapshot s=p.priceSnapshot();
        e.snapshotPriceKey=s==null?null:s.priceKey(); e.snapshotVersion=s==null?null:s.version();
        e.snapshotAmountMinor=s==null?null:s.money().minorUnits(); e.snapshotCurrency=s==null?null:s.money().currency();
        e.snapshotTaxMode=s==null?null:s.taxMode().name(); e.externalOrderId=p.externalOrderId();
        e.donationId=p.donationId()==null?null:p.donationId().value();
        DonationSnapshot donation=p.donationSnapshot();
        e.donationCampaignId=donation==null?null:donation.campaignId().value();
        e.snapshotCampaignKey=donation==null?null:donation.campaignKey().value();
        e.snapshotCampaignVersion=donation==null?null:donation.version();
        e.snapshotCampaignCurrency=donation==null?null:donation.currency();
        var subscription=p.subscriptionContext();
        e.subscriptionId=subscription==null?null:subscription.subscriptionId();
        e.billingRecordId=subscription==null?null:subscription.billingRecordId();
        e.subscriptionBillingKind=subscription==null?null:subscription.kind().name();
        e.subscriptionProductId=subscription==null?null:subscription.productId().value();
        e.subscriptionPriceId=subscription==null?null:subscription.priceId().value();
        e.subscriptionPriceVersion=subscription==null?null:subscription.priceVersion();
        e.subscriptionPreviousPriceId=subscription==null||subscription.previousPriceId()==null?null:subscription.previousPriceId().value();
        e.subscriptionPreviousPriceVersion=subscription==null?null:subscription.previousPriceVersion();
        e.subscriptionPeriodStart=subscription==null?null:subscription.periodStart();
        e.subscriptionPeriodEnd=subscription==null?null:subscription.periodEnd();
        e.subscriptionInvoiceReference=subscription==null?null:subscription.providerInvoiceReference();
        e.amountMinor=p.money().minorUnits(); e.currency=p.money().currency(); e.taxMode=p.taxMode().name();
        e.taxAmountMinor=p.taxAmountMinor(); e.technicalMetadata=metadata(p.technicalMetadata());
        e.status=p.status().name(); e.providerPaymentReference=p.providerPaymentReference();
        e.refundedAmountMinor=p.refundedAmountMinor(); e.createdAt=p.createdAt(); e.updatedAt=p.updatedAt();
    }
    Payment payment(PaymentJpaEntity e) {
        validateShape(e);
        PriceId price=e.priceId==null?null:new PriceId(e.priceId);
        PriceSnapshot snapshot=price==null?null:new PriceSnapshot(price,e.snapshotPriceKey,e.snapshotVersion,
            Money.of(e.snapshotAmountMinor,e.snapshotCurrency),TaxMode.valueOf(e.snapshotTaxMode));
        return new Payment(new PaymentId(e.id),new SiteId(e.siteId),e.customerId,new PaymentAccountId(e.paymentAccountId),
            PaymentSource.valueOf(e.source),price,snapshot,e.externalOrderId,
            e.donationId==null?null:new DonationId(e.donationId),
            e.donationCampaignId==null?null:new DonationSnapshot(new DonationCampaignId(e.donationCampaignId),
                new DonationCampaignKey(e.snapshotCampaignKey),e.snapshotCampaignVersion,e.snapshotCampaignCurrency),
            e.subscriptionId==null?null:new SubscriptionBillingContext(e.subscriptionId,e.billingRecordId,SubscriptionBillingContext.Kind.valueOf(e.subscriptionBillingKind),
                new ProductId(e.subscriptionProductId),new PriceId(e.subscriptionPriceId),e.subscriptionPriceVersion,
                e.subscriptionPreviousPriceId==null?null:new PriceId(e.subscriptionPreviousPriceId),e.subscriptionPreviousPriceVersion,
                e.subscriptionPeriodStart,e.subscriptionPeriodEnd,e.subscriptionInvoiceReference),Money.of(e.amountMinor,e.currency),
            TaxMode.valueOf(e.taxMode),e.taxAmountMinor,PaymentStatus.valueOf(e.status),e.providerPaymentReference,
            e.refundedAmountMinor,e.version,e.createdAt,e.updatedAt,metadata(e.technicalMetadata));
    }
    private void validateShape(PaymentJpaEntity e) {
        boolean anyPrice=e.priceId!=null || e.snapshotPriceKey!=null || e.snapshotVersion!=null
            || e.snapshotAmountMinor!=null || e.snapshotCurrency!=null || e.snapshotTaxMode!=null;
        boolean completePrice=e.priceId!=null && e.snapshotPriceKey!=null && e.snapshotVersion!=null
            && e.snapshotAmountMinor!=null && e.snapshotCurrency!=null && e.snapshotTaxMode!=null;
        boolean anyDonation=e.donationId!=null || e.donationCampaignId!=null || e.snapshotCampaignKey!=null
            || e.snapshotCampaignVersion!=null || e.snapshotCampaignCurrency!=null;
        boolean completeDonation=e.donationId!=null && e.donationCampaignId!=null && e.snapshotCampaignKey!=null
            && e.snapshotCampaignVersion!=null && e.snapshotCampaignCurrency!=null;
        boolean anySubscription=e.subscriptionId!=null||e.billingRecordId!=null||e.subscriptionBillingKind!=null||e.subscriptionProductId!=null
            ||e.subscriptionPriceId!=null||e.subscriptionPriceVersion!=null||e.subscriptionPreviousPriceId!=null||e.subscriptionPreviousPriceVersion!=null
            ||e.subscriptionPeriodStart!=null||e.subscriptionPeriodEnd!=null||e.subscriptionInvoiceReference!=null;
        boolean completeSubscription=e.subscriptionId!=null&&e.billingRecordId!=null&&e.subscriptionBillingKind!=null&&e.subscriptionProductId!=null
            &&e.subscriptionPriceId!=null&&e.subscriptionPriceVersion!=null&&e.subscriptionPeriodStart!=null&&e.subscriptionPeriodEnd!=null&&e.subscriptionInvoiceReference!=null
            &&(e.subscriptionPreviousPriceId==null)==(e.subscriptionPreviousPriceVersion==null);
        boolean valid=switch(PaymentSource.valueOf(e.source)) {
            case CATALOG -> completePrice && e.externalOrderId==null && !anyDonation && !anySubscription;
            case DYNAMIC -> !anyPrice && !anyDonation && e.externalOrderId!=null && !anySubscription;
            case DONATION -> !anyPrice && e.externalOrderId==null && completeDonation && !anySubscription;
            case SUBSCRIPTION -> !anyPrice && !anyDonation && e.externalOrderId==null && e.customerId!=null && completeSubscription;
        };
        if(!valid) throw new IllegalArgumentException("Invalid persisted payment source shape");
    }
    void copy(PaymentAttempt a, PaymentAttemptJpaEntity e) {
        e.id=a.id().value(); e.paymentId=a.paymentId().value(); e.attemptNumber=a.sequence();
        e.providerOperationKey=a.providerOperationKey(); e.providerIntentReference=a.providerIntentReference();
        e.paymentMethodFamily=a.paymentMethodFamily(); e.resultCode=a.resultCode(); e.status=a.status().name();
        e.createdAt=a.createdAt(); e.updatedAt=a.updatedAt();
    }
    PaymentAttempt attempt(PaymentAttemptJpaEntity e) {
        return new PaymentAttempt(new PaymentAttemptId(e.id),new PaymentId(e.paymentId),e.attemptNumber,
            e.providerOperationKey,e.providerIntentReference,e.paymentMethodFamily,PaymentAttemptStatus.valueOf(e.status),
            e.resultCode,e.createdAt,e.updatedAt);
    }
    void copy(Refund r, RefundJpaEntity e) {
        e.id=r.id().value(); e.paymentId=r.paymentId().value(); e.amountMinor=r.money().minorUnits();
        e.currency=r.money().currency(); e.status=r.status().name(); e.providerOperationKey=r.providerOperationKey();
        e.providerRefundReference=r.providerRefundReference(); e.reasonCode=r.reasonCode(); e.technicalNote=r.technicalNote();
        e.createdAt=r.createdAt(); e.updatedAt=r.updatedAt();
    }
    Refund refund(RefundJpaEntity e) {
        return new Refund(new RefundId(e.id),new PaymentId(e.paymentId),Money.of(e.amountMinor,e.currency),
            RefundStatus.valueOf(e.status),e.providerRefundReference,e.providerOperationKey,e.reasonCode,e.technicalNote,e.createdAt,e.updatedAt);
    }
}
