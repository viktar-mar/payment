package dev.genericpayments.platform.payments.application.model;
import java.util.Objects;
public record ProviderState(String reference,Status status,ProviderEvidence evidence){
 public ProviderState(String reference,Status status){this(reference,status,null);}
 public enum Status {CREATED,PENDING,REQUIRES_PAYMENT_METHOD,REQUIRES_ACTION,PROCESSING,SUCCEEDED,FAILED,UNKNOWN,CANCELLED}
 public ProviderState{ProviderRequestRules.reference(reference);Objects.requireNonNull(status);}
}
