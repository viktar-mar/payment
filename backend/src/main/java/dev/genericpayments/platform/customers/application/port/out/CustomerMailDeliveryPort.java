package dev.genericpayments.platform.customers.application.port.out;

public interface CustomerMailDeliveryPort {
    enum FailureCategory { TRANSIENT,PERMANENT,CONFIGURATION }
    record Message(String senderRef,String recipient,String subject,String body) {
        @Override public String toString(){return "CustomerMailMessage[redacted]";}
    }
    final class DeliveryException extends RuntimeException {
        private final FailureCategory category;
        public DeliveryException(FailureCategory category){super("Customer mail delivery failed");this.category=category;}
        public FailureCategory category(){return category;}
    }
    void deliver(Message message);
}
