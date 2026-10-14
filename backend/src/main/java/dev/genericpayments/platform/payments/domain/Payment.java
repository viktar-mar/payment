package dev.genericpayments.platform.payments.domain;

import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.catalog.domain.TaxMode;
import dev.genericpayments.platform.donations.domain.DonationId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.shared.domain.DomainValidationException;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Payment(PaymentId id, SiteId siteId, UUID customerId, PaymentAccountId paymentAccountId,
                      PaymentSource source, PriceId priceId, PriceSnapshot priceSnapshot, String externalOrderId,
                      DonationId donationId, DonationSnapshot donationSnapshot, SubscriptionBillingContext subscriptionContext,
                      Money money, TaxMode taxMode, Long taxAmountMinor, PaymentStatus status,
                      String providerPaymentReference, long refundedAmountMinor, long version,
                      Instant createdAt, Instant updatedAt, Map<String, String> technicalMetadata) {
    public Payment {
        PaymentDomainRules.required(id, "payment id");
        PaymentDomainRules.required(siteId, "site id");
        PaymentDomainRules.required(paymentAccountId, "payment account id");
        PaymentDomainRules.required(source, "payment source");
        PaymentDomainRules.required(money, "payment money");
        PaymentDomainRules.required(taxMode, "payment tax mode");
        PaymentDomainRules.required(status, "payment status");
        switch(source) {
            case CATALOG -> validateCatalog(priceId,priceSnapshot,money,taxMode,externalOrderId,donationId,donationSnapshot,subscriptionContext);
            case DYNAMIC -> validateDynamic(priceId,priceSnapshot,externalOrderId,donationId,donationSnapshot,subscriptionContext);
            case DONATION -> validateDonation(priceId,priceSnapshot,money,taxMode,externalOrderId,donationId,donationSnapshot,subscriptionContext);
            case SUBSCRIPTION -> validateSubscription(customerId,subscriptionContext,priceId,priceSnapshot,externalOrderId,donationId,donationSnapshot);
        }
        if (taxAmountMinor != null && (taxAmountMinor < 0 || taxAmountMinor > money.minorUnits())) {
            throw new DomainValidationException("tax amount must be within payment amount");
        }
        if (version < 0) throw new DomainValidationException("payment version must not be negative");
        if (refundedAmountMinor < 0 || refundedAmountMinor > money.minorUnits()
                || (status == PaymentStatus.PARTIALLY_REFUNDED && (refundedAmountMinor == 0 || refundedAmountMinor == money.minorUnits()))
                || (status == PaymentStatus.REFUNDED && refundedAmountMinor != money.minorUnits())
                || (status != PaymentStatus.PARTIALLY_REFUNDED && status != PaymentStatus.REFUNDED && refundedAmountMinor != 0)) {
            throw new DomainValidationException("confirmed refund amount must match payment status");
        }
        PaymentDomainRules.optionalText(providerPaymentReference, "provider payment reference", 255);
        PaymentDomainRules.timestamps(createdAt, updatedAt);
        technicalMetadata = PaymentDomainRules.metadata(technicalMetadata);
    }

    private static void validateCatalog(PriceId price,PriceSnapshot snapshot,Money money,TaxMode tax,String order,DonationId donation,DonationSnapshot donationSnapshot,SubscriptionBillingContext subscription) {
        if(price==null||snapshot==null||!price.equals(snapshot.priceId())||!money.equals(snapshot.money())||tax!=snapshot.taxMode()||order!=null||donation!=null||donationSnapshot!=null||subscription!=null)
            throw new DomainValidationException("catalog payment must match its price snapshot and omit external order");
    }
    private static void validateDynamic(PriceId price,PriceSnapshot snapshot,String order,DonationId donation,DonationSnapshot donationSnapshot,SubscriptionBillingContext subscription) {
        if(price!=null||snapshot!=null||donation!=null||donationSnapshot!=null||subscription!=null)throw new DomainValidationException("dynamic payment must omit catalog fields");
        PaymentDomainRules.text(order,"external order id",255);
    }
    private static void validateDonation(PriceId price,PriceSnapshot snapshot,Money money,TaxMode tax,String order,DonationId donation,DonationSnapshot donationSnapshot,SubscriptionBillingContext subscription) {
        if(donation==null||donationSnapshot==null||price!=null||snapshot!=null||order!=null||tax!=TaxMode.NONE||!money.currency().equals(donationSnapshot.currency())||subscription!=null)
            throw new DomainValidationException("donation payment requires matching campaign context, no tax and no catalog or order fields");
    }
    private static void validateSubscription(UUID customer,SubscriptionBillingContext subscription,PriceId price,PriceSnapshot snapshot,String order,DonationId donation,DonationSnapshot donationSnapshot) {
        if(customer==null||subscription==null||price!=null||snapshot!=null||order!=null||donation!=null||donationSnapshot!=null)
            throw new DomainValidationException("subscription payment requires customer and billing context only");
    }
    /** Existing donation reconstruction contract. */
    public Payment(PaymentId id, SiteId siteId, UUID customerId, PaymentAccountId paymentAccountId,
                   PaymentSource source, PriceId priceId, PriceSnapshot priceSnapshot, String externalOrderId,
                   DonationId donationId, DonationSnapshot donationSnapshot,
                   Money money, TaxMode taxMode, Long taxAmountMinor, PaymentStatus status,
                   String providerPaymentReference, long refundedAmountMinor, long version,
                   Instant createdAt, Instant updatedAt, Map<String,String> technicalMetadata) {
        this(id,siteId,customerId,paymentAccountId,source,priceId,priceSnapshot,externalOrderId,donationId,donationSnapshot,null,
            money,taxMode,taxAmountMinor,status,providerPaymentReference,refundedAmountMinor,version,createdAt,updatedAt,technicalMetadata);
    }
    public static Payment subscription(PaymentId id,SiteId siteId,UUID customerId,PaymentAccountId accountId,
                                       SubscriptionBillingContext context,Money money,TaxMode taxMode,Map<String,String> technicalMetadata,Instant now) {
        return new Payment(id,siteId,customerId,accountId,PaymentSource.SUBSCRIPTION,null,null,null,null,null,context,
            money,taxMode,null,PaymentStatus.CREATED,null,0,0,now,now,technicalMetadata);
    }
    /** Existing catalog/dynamic reconstruction contract. */
    public Payment(PaymentId id, SiteId siteId, UUID customerId, PaymentAccountId paymentAccountId,
                   PaymentSource source, PriceId priceId, PriceSnapshot priceSnapshot, String externalOrderId,
                   Money money, TaxMode taxMode, Long taxAmountMinor, PaymentStatus status,
                   String providerPaymentReference, long refundedAmountMinor, long version,
                   Instant createdAt, Instant updatedAt, Map<String,String> technicalMetadata) {
        this(id,siteId,customerId,paymentAccountId,source,priceId,priceSnapshot,externalOrderId,null,null,
            money,taxMode,taxAmountMinor,status,providerPaymentReference,refundedAmountMinor,version,createdAt,updatedAt,technicalMetadata);
    }

    public static Payment donation(PaymentId id, SiteId siteId, UUID customerId, PaymentAccountId accountId,
                                   DonationId donationId, DonationSnapshot snapshot, Money money, TaxMode taxMode,
                                   Map<String,String> metadata, Instant now) {
        return new Payment(id,siteId,customerId,accountId,PaymentSource.DONATION,null,null,null,donationId,snapshot,
            money,taxMode,null,PaymentStatus.CREATED,null,0,0,now,now,metadata);
    }

    public static Payment catalog(PaymentId id, SiteId siteId, PaymentAccountId accountId,
                                  PriceSnapshot snapshot, Map<String, String> metadata, Instant now) {
        return catalog(id, siteId, null, accountId, snapshot, metadata, now);
    }

    public static Payment catalog(PaymentId id, SiteId siteId, UUID customerId, PaymentAccountId accountId,
                                  PriceSnapshot snapshot, Map<String, String> metadata, Instant now) {
        PaymentDomainRules.required(snapshot, "price snapshot");
        return new Payment(id, siteId, customerId, accountId, PaymentSource.CATALOG, snapshot.priceId(), snapshot,
            null, snapshot.money(), snapshot.taxMode(), null, PaymentStatus.CREATED, null, 0, 0, now, now, metadata);
    }

    public static Payment dynamic(PaymentId id, SiteId siteId, PaymentAccountId accountId,
                                  String externalOrderId, Money money, TaxMode taxMode,
                                  Map<String, String> metadata, Instant now) {
        return dynamic(id, siteId, null, accountId, externalOrderId, money, taxMode, metadata, now);
    }

    public static Payment dynamic(PaymentId id, SiteId siteId, UUID customerId, PaymentAccountId accountId,
                                  String externalOrderId, Money money, TaxMode taxMode,
                                  Map<String, String> metadata, Instant now) {
        return new Payment(id, siteId, customerId, accountId, PaymentSource.DYNAMIC, null, null, externalOrderId,
            money, taxMode, null, PaymentStatus.CREATED, null, 0, 0, now, now, metadata);
    }

    public boolean canStartAttempt() {
        return status == PaymentStatus.CREATED || status == PaymentStatus.REQUIRES_PAYMENT_METHOD;
    }

    public boolean canRefund() {
        return status == PaymentStatus.SUCCEEDED || status == PaymentStatus.PARTIALLY_REFUNDED;
    }

    public long refundableAmountMinor() {
        return canRefund() ? money.minorUnits() - refundedAmountMinor : 0;
    }

    public Payment requiresPaymentMethod(Instant now) { return transition(PaymentStatus.REQUIRES_PAYMENT_METHOD, now); }
    public Payment requiresAction(Instant now) { return transition(PaymentStatus.REQUIRES_ACTION, now); }
    public Payment processing(Instant now) { return transition(PaymentStatus.PROCESSING, now); }
    public Payment succeeded(Instant now) { return transition(PaymentStatus.SUCCEEDED, now); }
    public Payment terminalDecline(Instant now) { return transition(PaymentStatus.FAILED, now); }
    public Payment cancelled(Instant now) { return transition(PaymentStatus.CANCELLED, now); }

    public Payment recoverableDecline(Instant now) {
        PaymentDomainRules.time(now, updatedAt);
        if (!isUnconfirmed()) throw cannotTransition(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        return status == PaymentStatus.REQUIRES_PAYMENT_METHOD ? this : copy(PaymentStatus.REQUIRES_PAYMENT_METHOD, providerPaymentReference, 0, now);
    }

    /** Applies the cumulative confirmed refund total, never a requested or pending amount. */
    public Payment refundSucceeded(long confirmedTotalMinor, Instant now) {
        PaymentDomainRules.time(now, updatedAt);
        if ((!canRefund() && status != PaymentStatus.REFUNDED) || confirmedTotalMinor <= 0
                || confirmedTotalMinor < refundedAmountMinor || confirmedTotalMinor > money.minorUnits()) {
            throw new DomainValidationException("cannot transition payment with invalid confirmed refund total");
        }
        if (confirmedTotalMinor == refundedAmountMinor) return this;
        return copy(confirmedTotalMinor == money.minorUnits() ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED,
            providerPaymentReference, confirmedTotalMinor, now);
    }

    public Payment withProviderPaymentReference(String reference, Instant now) {
        PaymentDomainRules.text(reference, "provider payment reference", 255);
        PaymentDomainRules.time(now, updatedAt);
        if (providerPaymentReference != null && !providerPaymentReference.equals(reference)) {
            throw new DomainValidationException("provider payment reference cannot be replaced");
        }
        return reference.equals(providerPaymentReference) ? this : copy(status, reference, refundedAmountMinor, now);
    }

    private Payment transition(PaymentStatus target, Instant now) {
        PaymentDomainRules.time(now, updatedAt);
        if (status == target) return this;
        boolean allowed = switch (target) {
            case REQUIRES_PAYMENT_METHOD -> status == PaymentStatus.CREATED;
            case REQUIRES_ACTION -> status == PaymentStatus.CREATED || status == PaymentStatus.REQUIRES_PAYMENT_METHOD;
            case PROCESSING -> status == PaymentStatus.CREATED || status == PaymentStatus.REQUIRES_PAYMENT_METHOD || status == PaymentStatus.REQUIRES_ACTION;
            case SUCCEEDED, FAILED, CANCELLED -> isUnconfirmed();
            default -> false;
        };
        if (!allowed) throw cannotTransition(target);
        return copy(target, providerPaymentReference, refundedAmountMinor, now);
    }

    private boolean isUnconfirmed() {
        return switch (status) {
            case CREATED, REQUIRES_PAYMENT_METHOD, REQUIRES_ACTION, PROCESSING -> true;
            default -> false;
        };
    }

    private DomainValidationException cannotTransition(PaymentStatus target) {
        return new DomainValidationException("payment cannot transition from " + status + " to " + target);
    }

    private Payment copy(PaymentStatus nextStatus, String reference, long refunded, Instant now) {
        return new Payment(id, siteId, customerId, paymentAccountId, source, priceId, priceSnapshot, externalOrderId,
            donationId, donationSnapshot, subscriptionContext,
            money, taxMode, taxAmountMinor, nextStatus, reference, refunded, version, createdAt, now, technicalMetadata);
    }
}
