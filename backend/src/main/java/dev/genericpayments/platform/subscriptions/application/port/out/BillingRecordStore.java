package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.subscriptions.domain.BillingRecord;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.UUID;
public interface BillingRecordStore {
    BillingRecord upsert(BillingRecord record);
    BillingRecord lock(SiteId site,UUID id);
    java.util.Optional<BillingRecord> find(SiteId site,UUID subscription,dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId account,String reference);
}
