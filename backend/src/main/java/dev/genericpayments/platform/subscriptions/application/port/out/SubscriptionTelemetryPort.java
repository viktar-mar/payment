package dev.genericpayments.platform.subscriptions.application.port.out;
import java.time.Duration;
public interface SubscriptionTelemetryPort {
    enum Operation { CREATE, CHANGE, CANCEL, SETUP, RECOVER, RESUME, RECONCILE, DEADLINE, CALLBACK }
    enum Outcome { SUCCESS, DECLINED, UNKNOWN, REJECTED, NO_CHANGE }
    void record(Operation operation,Outcome outcome);
    void observeUnknownAge(Duration age);
    void observeInboxLag(Duration lag);
}
