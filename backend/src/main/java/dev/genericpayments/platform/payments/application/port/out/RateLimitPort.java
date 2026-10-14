package dev.genericpayments.platform.payments.application.port.out;
import java.util.*;
public interface RateLimitPort {
    enum Operation { CREATE, CONFIRM, HANDOFF, READ }
    record Key(UUID siteId, String ip, Operation operation) {
        public Key { Objects.requireNonNull(siteId); Objects.requireNonNull(ip); Objects.requireNonNull(operation); }
    }
    record Decision(boolean allowed, int retryAfterSeconds) {}
    Decision acquire(Key key);
}
