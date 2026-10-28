package dev.genericpayments.platform.support;

import java.util.List;

/** Explicit ordered current-schema contract, independent of discovered migrations and database state. */
public final class CurrentSchemaVersions {
    public static final List<String> EXPECTED = List.of(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13",
        "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26");

    private CurrentSchemaVersions() {}
}
