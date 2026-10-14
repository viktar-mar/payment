package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import java.time.Instant;
import java.util.UUID;
public interface SubscriptionActionStore {
    void issue(SiteId site,CustomerId customer,PaymentAccountId account,UUID subscription,UUID operation,String purpose,String digest,Instant expiresAt);
    boolean consume(SiteId site,CustomerId customer,PaymentAccountId account,UUID subscription,UUID operation,String purpose,String digest,Instant now);
    void consent(String digest,String purpose,boolean accepted,long policy,Instant at);
    /** Exact authority-scoped retry; a changed request conflicts. Requires an enclosing transaction. */
    boolean replay(SiteId site,String authority,String keyDigest,String fingerprint);
    void claim(SiteId site,CustomerId customer,PaymentAccountId account,UUID subscription,UUID operation,String purpose,String digest,
        String authority,String keyDigest,String fingerprint,Instant now);
}
