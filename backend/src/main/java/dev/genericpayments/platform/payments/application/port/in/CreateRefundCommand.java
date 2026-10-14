package dev.genericpayments.platform.payments.application.port.in;

import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.payments.application.model.SafeFactJson;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.util.*;

/** Trusted routing/actor facts are not supplied by the request body. */
public record CreateRefundCommand(SiteId siteId,PaymentId paymentId,UUID adminId,long amountMinor,
    String currency,String reasonCode,String technicalNote,String idempotencyKey,String requestId) {
    public CreateRefundCommand {
        Objects.requireNonNull(siteId);Objects.requireNonNull(paymentId);Objects.requireNonNull(adminId);
        if(amountMinor<=0)throw new PaymentApplicationException("REFUND_EXCEEDS_AVAILABLE",409,"Refund exceeds available amount");
        currency=Money.of(amountMinor,currency).currency();
        if(!Set.of("REQUESTED_BY_CUSTOMER","DUPLICATE","FRAUDULENT","OTHER").contains(reasonCode))throw SafeFactJson.invalid();
        SafeFactJson.text(idempotencyKey,255);SafeFactJson.text(requestId,128);
        if(technicalNote!=null){SafeFactJson.text(technicalNote,500);
            if(technicalNote.matches("(?i).*(?:\\bBasic\\s+\\S+|\\b(?:password|secret|token|authorization|credentials|api[_ -]?key|cvv|cvc)\\b|\\b[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}\\b).*"))throw SafeFactJson.invalid();
            // Reuse the aggregate's closed technical-value guard, not arbitrary metadata.
            new Refund(new RefundId(UUID.randomUUID()),paymentId,Money.of(amountMinor,currency),RefundStatus.PENDING,null,"validation",reasonCode,technicalNote,java.time.Instant.EPOCH,java.time.Instant.EPOCH);
        }
    }
    @Override public String toString(){return "CreateRefundCommand[paymentId="+paymentId+", amountMinor="+amountMinor+"]";}
}
