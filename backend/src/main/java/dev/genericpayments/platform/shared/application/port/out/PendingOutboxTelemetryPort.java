package dev.genericpayments.platform.shared.application.port.out;

/** Refreshes current committed backlog independently of due-row claims. */
public interface PendingOutboxTelemetryPort {
    void refreshPending();
    /** Nonblocking committed-work signal; telemetry failures never escape to the business caller. */
    void requestRefresh();
}
