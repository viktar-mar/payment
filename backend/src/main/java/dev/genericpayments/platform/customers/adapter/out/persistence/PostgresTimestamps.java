package dev.genericpayments.platform.customers.adapter.out.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Persistence uses microsecond precision, explicitly truncating rather than relying on JDBC rounding.
 * Apply before every timestamp comparison/write and on mapping back to the domain: values returned
 * by a save must equal values read in another transaction. Domain validation remains nanosecond-aware;
 * persisted immutable and monotonic facts are compared at the database's microsecond resolution.
 */
final class PostgresTimestamps {
    private PostgresTimestamps() {}

    static Instant canonical(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }
}
