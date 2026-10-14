package dev.genericpayments.platform.payments.application.model;
import dev.genericpayments.platform.payments.domain.Payment;
import java.time.Instant;
import java.util.UUID;
public record HandoffCreation(UUID paymentId,UUID customerId,String externalOrderId,long amountMinor,String currency,String status,String handoffToken,Instant handoffExpiresAt) {
    public static HandoffCreation of(Payment payment,String token,Instant expiry) { return new HandoffCreation(payment.id().value(),payment.customerId(),payment.externalOrderId(),payment.money().minorUnits(),payment.money().currency(),payment.status().name(),token,expiry); }
    @Override public String toString() { return "HandoffCreation[paymentId="+paymentId+", secret=redacted]"; }
}
