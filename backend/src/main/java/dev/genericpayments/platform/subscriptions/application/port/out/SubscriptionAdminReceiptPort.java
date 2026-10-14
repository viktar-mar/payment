package dev.genericpayments.platform.subscriptions.application.port.out;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.UUID;
import java.util.function.Supplier;
public interface SubscriptionAdminReceiptPort {
 /** Runs under the caller's Site lock and transaction; durable replay is scoped to the Admin and operation. */
 <T> T once(SiteId site,UUID admin,String kind,String key,String fingerprint,Class<T> type,Supplier<T> mutation);
}
