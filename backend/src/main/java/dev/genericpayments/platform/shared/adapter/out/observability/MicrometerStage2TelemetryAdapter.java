package dev.genericpayments.platform.shared.adapter.out.observability;

import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import io.micrometer.core.instrument.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class MicrometerStage2TelemetryAdapter implements Stage2TelemetryPort {
    private final MeterRegistry registry;
    private final Set<String> configuredSites;
    private final Map<Context, AtomicLong> pending = new ConcurrentHashMap<>();

    public MicrometerStage2TelemetryAdapter(MeterRegistry registry, Set<String> configuredSites) {
        this.registry = Objects.requireNonNull(registry);
        this.configuredSites = Set.copyOf(configuredSites);
        this.configuredSites.forEach(site -> new Context(site, Provider.FAKE));
    }
    private Tags tags(Context context) {
        requireContext(context);
        return Tags.of("site", context.siteKey(), "provider", label(context.provider()));
    }
    @Override public void requireContext(Context context) {
        Objects.requireNonNull(context);
        if (!configuredSites.contains(context.siteKey())) throw new IllegalArgumentException("Unconfigured metric Site");
    }
    private static String label(Enum<?> value) { return Objects.requireNonNull(value).name().toLowerCase(Locale.ROOT); }
    private static Duration duration(Duration value) {
        if (value == null || value.isNegative()) throw new IllegalArgumentException("Metric duration must be nonnegative");
        return value;
    }
    @Override public void paymentCreated(Context context) { registry.counter("payment.created", tags(context)).increment(); }
    @Override public void paymentTransition(Context context, PaymentState from, PaymentState to) {
        registry.counter("payment.transition", tags(context).and("from", label(from), "to", label(to))).increment();
    }
    @Override public void providerCall(Context context, ProviderOperation operation, ProviderResult result, Duration elapsed) {
        duration(elapsed);
        Tags tags = tags(context).and("operation", label(operation), "result", label(result));
        registry.timer("payment.provider.call", tags).record(elapsed);
        if (result == ProviderResult.UNKNOWN) registry.counter("payment.provider.unknown", tags).increment();
    }
    @Override public void inbox(Context context, InboxOutcome outcome) {
        registry.counter(switch (outcome) {
            case RECEIVED -> "payment.inbox.received";
            case DUPLICATE -> "payment.inbox.duplicate";
            case PROCESSED -> "payment.inbox.processed";
            case FAILED -> "payment.inbox.failed";
        }, tags(context)).increment();
    }
    @Override public void inboxLag(Context context, Duration lag) { registry.timer("payment.inbox.lag", tags(context)).record(duration(lag)); }
    @Override public void outboxPending(Context context, long count) {
        if (count < 0) throw new IllegalArgumentException("Metric count must be nonnegative");
        Tags labels = tags(context);
        pending.computeIfAbsent(context, ignored -> {
            var value = new AtomicLong();
            Gauge.builder("payment.outbox.pending", value, AtomicLong::doubleValue).tags(labels).register(registry);
            return value;
        }).set(count);
    }
    @Override public void outbox(Context context, OutboxOutcome outcome) {
        registry.counter(switch (outcome) {
            case DELIVERED -> "payment.outbox.delivered";
            case RETRIED -> "payment.outbox.retried";
            case DEAD -> "payment.outbox.dead";
        }, tags(context)).increment();
    }
    @Override public void outboxLag(Context context, Duration lag) { registry.timer("payment.outbox.lag", tags(context)).record(duration(lag)); }
    @Override public void refundRequested(Context context) { registry.counter("payment.refund.requested", tags(context)).increment(); }
    @Override public void refundResult(Context context, RefundResult result) {
        registry.counter("payment.refund.result", tags(context).and("result", label(result))).increment();
    }
    @Override public void idempotencyReplay(Operation operation, ReplayState state) {
        registry.counter("payment.idempotency.replay", "operation", label(operation), "result", label(state)).increment();
    }
    @Override public void idempotencyConflict(Operation operation) {
        registry.counter("payment.idempotency.conflict", "operation", label(operation)).increment();
    }
}
