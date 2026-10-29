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
    enum ClaimDisposition { NEW, REPLAY }
    ClaimDisposition claim(SiteId site,CustomerId customer,PaymentAccountId account,UUID subscription,UUID operation,String purpose,String digest,
        String authority,String keyDigest,String fingerprint,Instant now);
    default java.util.Optional<dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant> current(PaymentAccountId account,UUID operation){throw new UnsupportedOperationException();}
    default java.util.Optional<dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant> billing(String digest){throw new UnsupportedOperationException();}
    default boolean serverConfirmationReserved(UUID grant){return false;}
    default void reserveOwnedInitialBilling(dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant grant,dev.genericpayments.platform.provider.application.model.OwnedClockSubscriptionReceipt expected){throw new UnsupportedOperationException();}
    default void reserveBilling(dev.genericpayments.platform.subscriptions.application.model.SubscriptionClientGrant grant){throw new UnsupportedOperationException();}
    default void bindSession(String digest,UUID session,String origin,Instant expires,Instant now){throw new UnsupportedOperationException();}
    default ClaimDisposition claimBilling(SiteId site,CustomerId customer,PaymentAccountId account,UUID subscription,UUID operation,String digest,UUID session,String origin,String authority,String key,String fingerprint,Instant now){throw new UnsupportedOperationException();}
}
