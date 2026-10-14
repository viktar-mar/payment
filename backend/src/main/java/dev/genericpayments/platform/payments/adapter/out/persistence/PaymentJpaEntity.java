package dev.genericpayments.platform.payments.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name="payments")
class PaymentJpaEntity {
    @Id
    @Column(name="id")
    UUID id;
    @Column(name="site_id")
    UUID siteId;
    @Column(name="customer_id")
    UUID customerId;
    @Column(name="payment_account_id")
    UUID paymentAccountId;
    @Column(name="source")
    String source;
    @Column(name="subscription_id")
    UUID subscriptionId;
    @Column(name="billing_record_id")
    UUID billingRecordId;
    @Column(name="subscription_billing_kind")
    String subscriptionBillingKind;
    @Column(name="subscription_product_id")
    UUID subscriptionProductId;
    @Column(name="subscription_price_id")
    UUID subscriptionPriceId;
    @Column(name="subscription_price_version")
    Integer subscriptionPriceVersion;
    @Column(name="subscription_previous_price_id")
    UUID subscriptionPreviousPriceId;
    @Column(name="subscription_previous_price_version")
    Integer subscriptionPreviousPriceVersion;
    @Column(name="subscription_period_start")
    Instant subscriptionPeriodStart;
    @Column(name="subscription_period_end")
    Instant subscriptionPeriodEnd;
    @Column(name="subscription_invoice_reference")
    String subscriptionInvoiceReference;
    @Column(name="price_id")
    UUID priceId;
    @Column(name="snapshot_price_key")
    String snapshotPriceKey;
    @Column(name="snapshot_version")
    Integer snapshotVersion;
    @Column(name="snapshot_amount_minor")
    Long snapshotAmountMinor;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name="snapshot_currency", length=3)
    String snapshotCurrency;
    @Column(name="snapshot_tax_mode")
    String snapshotTaxMode;
    @Column(name="external_order_id")
    String externalOrderId;
    @Column(name="donation_id")
    UUID donationId;
    @Column(name="donation_campaign_id")
    UUID donationCampaignId;
    @Column(name="snapshot_campaign_key")
    String snapshotCampaignKey;
    @Column(name="snapshot_campaign_version")
    Long snapshotCampaignVersion;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name="snapshot_campaign_currency",length=3)
    String snapshotCampaignCurrency;
    @Column(name="return_url", length=2048)
    String returnUrl;
    @Column(name="amount_minor")
    long amountMinor;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name="currency", length=3)
    String currency;
    @Column(name="tax_mode")
    String taxMode;
    @Column(name="tax_amount_minor")
    Long taxAmountMinor;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name="technical_metadata")
    String technicalMetadata;
    @Column(name="status")
    String status;
    @Column(name="provider_payment_reference")
    String providerPaymentReference;
    @Column(name="refunded_amount_minor")
    long refundedAmountMinor;
    @Column(name="reconciliation_lease_owner")
    String reconciliationLeaseOwner;
    @Column(name="reconciliation_lease_expires_at")
    Instant reconciliationLeaseExpiresAt;
    @Column(name="created_at")
    Instant createdAt;
    @Column(name="updated_at")
    Instant updatedAt;
    @Version
    @Column(name="version")
    long version;
    protected PaymentJpaEntity() {}
}
