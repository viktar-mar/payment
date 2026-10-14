package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.time.*;
import java.util.*;
public interface SubscriptionSweepPort {
    record Claim(SiteId siteId,UUID subscriptionId,String owner,UUID leaseToken,long revision) {}
    List<Claim> claim(String owner,int batch,Instant now,Duration lease);
    void finish(Claim claim,Instant nextCheck,boolean succeeded);
    List<Claim> expiredGrace(int batch,Instant now);
    /** Mandatory transaction, after the standard operation/Site/Customer/account/Subscription locks. */
    void requireCurrent(Claim claim,Instant now);
    /** Advance the lease fence only in the same transaction as an accepted fact apply. */
    Claim advanceRevision(Claim claim,long revision,Instant now);
    int attempts(Claim claim);
}
