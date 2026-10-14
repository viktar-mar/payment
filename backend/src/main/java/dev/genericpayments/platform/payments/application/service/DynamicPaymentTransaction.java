package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord.*;
import dev.genericpayments.platform.payments.application.port.in.CreateDynamicPaymentCommand;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.shared.application.port.out.*;
import dev.genericpayments.platform.sites.domain.Site;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
/** Initial claim, money facts, routing, handoff and encrypted replay complete together. */
public class DynamicPaymentTransaction {
    private final IdempotencyService idempotency; private final PaymentCreationTransaction creation;
    private final SavePaymentPort payments; private final HandoffSessionPort sessions;
    private final RandomTokenPort tokens; private final DigestPort digest; private final Clock clock;
    public DynamicPaymentTransaction(IdempotencyService idempotency,PaymentCreationTransaction creation,SavePaymentPort payments,HandoffSessionPort sessions,RandomTokenPort tokens,DigestPort digest,Clock clock) {
        this.idempotency=idempotency; this.creation=creation; this.payments=payments; this.sessions=sessions; this.tokens=tokens; this.digest=digest; this.clock=clock;
    }
    public record Phase(IdempotencyService.Outcome outcome,Payment payment,HandoffSession handoff,String token) {
        @Override public String toString() { return "Phase[secret=redacted]"; }
    }
    @Transactional public Phase create(CreateDynamicPaymentCommand command,Site site,PaymentAccount account,URI origin) {
        var business=new LinkedHashMap<String,Object>();
        business.put("external_order_id",command.externalOrderId()); business.put("amount_minor",command.amountMinor()); business.put("currency",command.currency());
        business.put("checkout_origin",origin.toString()); business.put("return_url_present",command.returnUrl()!=null);
        business.put("return_url",command.returnUrl()==null ? "" : command.returnUrl()); business.put("metadata",command.metadata()==null ? Map.of() : command.metadata());
        var legacyGuestFingerprint=idempotency.fingerprint(business);
        business.put("customer_ownership",command.customerId()==null ? "guest" : "customer:" + command.customerId());
        var compatible=command.customerId()==null ? Set.of(legacyGuestFingerprint) : Set.<String>of();
        var outcome=idempotency.beginCompatibleReplay(Scope.partner(site.id().value(),Stage2TelemetryPort.Operation.PAYMENT_CREATE),command.idempotencyKey(),idempotency.fingerprint(business),compatible);
        if (!(outcome instanceof IdempotencyService.Owner owner)) return new Phase(outcome,null,null,null);
        var now=clock.instant();
        var payment=Payment.dynamic(new PaymentId(UUID.randomUUID()),site.id(),command.customerId(),account.id(),command.externalOrderId(),Money.of(command.amountMinor(),command.currency()),TaxMode.NONE,
            command.metadata()==null ? Map.of() : command.metadata(),now);
        var local=creation.createLocal(new PreparePaymentCommand(payment,account,site.key().value(),command.requestId(),command.idempotencyKey()));
        payment=payments.save(new LoadPaymentPort.StoredPayment(local.payment(),command.returnUrl())).value();
        var token=tokens.issue(32);
        var handoff=new HandoffSession(owner.id(),payment.id(),site.id(),digest.sha256Hex(token),origin,now.plusSeconds(600),now,null);
        sessions.save(handoff);
        idempotency.complete(owner,new Resource(ResourceType.HANDOFF,handoff.id()),201,new IdempotencyService.SecretFragment(token,handoff.expiresAt()));
        return new Phase(outcome,payment,handoff,token);
    }
}
