package dev.genericpayments.platform.payments.application.port.out;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
public interface PaymentTransitionPort {
    record Identity(SiteId siteId,PaymentId paymentId,Stage2TelemetryPort.Context context) {}
    record RefundIdentity(Identity payment,RefundId refundId) {}
    boolean hasAmbientTransaction();
    <T> T transaction(Supplier<T> work);
    Optional<Identity> resolve(PaymentAccountId account,String reference);
    Optional<RefundIdentity> resolveRefund(PaymentAccountId account,String reference);
    default Optional<Identity> resolveEvent(dev.genericpayments.platform.payments.application.model.ProviderEventEnvelope e){return e.type().name().startsWith("REFUND_")?resolveRefund(e.accountId(),e.objectId()).map(RefundIdentity::payment):resolve(e.accountId(),e.objectId());}
    void requireProvider(PaymentAccountId account,PaymentProviderType provider);
    Payment lock(Identity identity);
    void verifyLease(Payment payment,String owner,Instant now);
}
