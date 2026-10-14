package dev.genericpayments.platform.payments.adapter.in.web;
import dev.genericpayments.platform.payments.application.port.out.RateLimitPort;
import java.time.*;
import java.util.*;
/** Local per-process limit. Access-ordered eviction strictly bounds memory. */
public final class InMemoryTokenBucketRateLimitAdapter implements RateLimitPort {
    private final Clock clock;
    private final int create, confirm, read, maxKeys;
    private final LinkedHashMap<Key, Bucket> buckets = new LinkedHashMap<>(16, 0.75f, true);
    private record Bucket(double tokens, Instant lastRefill) {}
    public InMemoryTokenBucketRateLimitAdapter(Clock clock, int create, int confirm, int read, int maxKeys) {
        if (create < 1 || confirm < 1 || read < 1 || maxKeys < 1) throw new IllegalArgumentException("Invalid rate limit");
        this.clock = clock; this.create = create; this.confirm = confirm; this.read = read; this.maxKeys = maxKeys;
    }
    @Override public synchronized Decision acquire(Key key) {
        var now = clock.instant();
        int capacity = switch (key.operation()) { case CREATE -> create; case CONFIRM, HANDOFF -> confirm; case READ -> read; };
        var bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() == maxKeys) buckets.remove(buckets.keySet().iterator().next());
            bucket = new Bucket(capacity, now);
        }
        var timestamp = now.isAfter(bucket.lastRefill()) ? now : bucket.lastRefill();
        double seconds = Math.max(0, Duration.between(bucket.lastRefill(), timestamp).toNanos() / 1_000_000_000.0);
        double tokens = Math.min(capacity, bucket.tokens() + seconds * capacity / 60.0);
        boolean allowed = tokens >= 1;
        buckets.put(key, new Bucket(allowed ? tokens - 1 : tokens, timestamp));
        return new Decision(allowed, allowed ? 0 : Math.max(1, (int) Math.ceil((1 - tokens) * 60.0 / capacity)));
    }
    int keyCount() { return buckets.size(); }
}
