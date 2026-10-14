package dev.genericpayments.platform.payments.application.model;
import java.util.Objects;
public record ProviderState(String reference,Status status){
 public enum Status {CREATED,PENDING,REQUIRES_PAYMENT_METHOD,REQUIRES_ACTION,PROCESSING,SUCCEEDED,FAILED,UNKNOWN,CANCELLED}
 public ProviderState{ProviderRequestRules.reference(reference);Objects.requireNonNull(status);}
}
