package dev.genericpayments.platform.payments.application.service;

import static dev.genericpayments.platform.payments.application.port.out.PendingProviderRecoveryPort.Outcome.*;

import dev.genericpayments.platform.admin.application.port.out.AuditLogPort;
import dev.genericpayments.platform.admin.domain.*;
import dev.genericpayments.platform.payments.application.model.IdempotencyRecord;
import dev.genericpayments.platform.payments.application.model.ProviderState;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.application.port.out.PendingProviderRecoveryPort;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.model.RecoveryRequestSnapshot.*;
import dev.genericpayments.platform.provider.application.port.in.RecoverProviderOperationUseCase;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.provider.application.service.ProviderOperationGuard;
import dev.genericpayments.platform.shared.application.port.out.Stage2TelemetryPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.SiteStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

public final class StripeRecoveryService implements RecoverProviderOperationUseCase {
    private final PendingProviderRecoveryPort pending;
    private final ProviderOperationStore operations;
    private final ProviderRecoveryProofPort proof;
    private final PaymentTransitionService transitions;
    private final ReconcilePaymentService reconcile;
    private final Clock clock;
    private final PaymentTransitionPort tx;
    private final IdempotencyService idem;
    private final IdempotencyLookupPort receipts;
    private final AuditLogPort audit;
    private final LoadSitePort sites;

    public StripeRecoveryService(
            PendingProviderRecoveryPort pending,
            ProviderOperationStore operations,
            ProviderRecoveryProofPort proof,
            PaymentTransitionService transitions,
            ReconcilePaymentService reconcile,
            Clock clock,
            PaymentTransitionPort tx,
            IdempotencyService idem,
            IdempotencyLookupPort receipts,
            AuditLogPort audit,
            LoadSitePort sites) {
        this.pending = pending;
        this.operations = operations;
        this.proof = proof;
        this.transitions = transitions;
        this.reconcile = reconcile;
        this.clock = clock;
        this.tx = tx;
        this.idem = idem;
        this.receipts = receipts;
        this.audit = audit;
        this.sites = sites;
    }

    public void runOnce() {
        var visited = new HashSet<UUID>();
        for (int i = 0; i < 20 && !Thread.currentThread().isInterrupted(); i++) {
            var claim = pending.claimNext();
            if (claim.isEmpty()) return;
            if (visited.add(claim.get().operation().id())) recover(claim.get());
            else pending.finish(claim.get(), RETRY_PENDING);
        }
    }

    public void recover(UUID operationId) {
        pending.claim(operationId).ifPresent(this::recover);
    }

    private void recover(PendingProviderRecoveryPort.Claim claim) {
        var outcome = FAILED;
        try {
            var operation = claim.operation();
            if (operation.status() != ProviderOperation.Status.ACKNOWLEDGED) {
                if (!mayReplayMissingReference(
                        operation.status(),
                        operation.providerReference(),
                        operation.firstDispatchedAt(),
                        clock.instant())) outcome = MANUAL_REVIEW;
                else {
                    proof.replay(operation);
                    outcome = operations.findById(operation.id()).orElseThrow().status()
                                    == ProviderOperation.Status.ACKNOWLEDGED
                            ? REPLAY_ACKNOWLEDGED
                            : FAILED;
                }
            } else if (operation.kind() == ProviderOperation.Kind.PRODUCT_CREATE
                    || operation.kind() == ProviderOperation.Kind.PRICE_CREATE)
                outcome = CATALOG_PUBLICATION_REQUIRED;
            else {
                reconcileAcknowledged(operation, "recovery_" + operation.id());
                outcome = RETRY_PENDING;
            }
        } catch (RuntimeException safe) {
            outcome = FAILED;
        }
        try {
            pending.finish(claim, outcome);
        } catch (RuntimeException lost) {
            /* Expired due ownership cannot overwrite a newer worker. */
        }
    }

    private void reconcileAcknowledged(ProviderOperation operation, String requestId) {
        var request = proof.request(operation);
        var payment =
                switch (request) {
                    case PaymentRequest r -> r.payment();
                    case RefundRequest r -> r.payment();
                    default -> throw new IllegalStateException("INVALID_RECOVERY_KIND");
                };
        boolean missing = payment.providerPaymentReference() == null
                || request instanceof RefundRequest r
                        && r.refund().providerRefundReference() == null;
        if (missing) {
            var lease = transitions.acquireRecovery(payment.siteId(), payment.id(), requestId);
            try {
                var verified = proof.retrieve(operation);
                if (verified.state().status() == ProviderState.Status.UNKNOWN) {
                    throw new IllegalStateException("UNRESOLVED_OBJECT");
                }
                var result = transitions.reconcileRecovered(lease, verified, requestId);
                transitions.emit(result);
            } finally {
                transitions.releaseRecovery(lease);
            }
        } else if (request instanceof RefundRequest r)
            reconcile.reconcileRefund(payment.siteId(), payment.id(), r.refund().id(), requestId);
        else reconcile.reconcile(payment.siteId(), payment.id(), requestId);
    }

    public void recover(RecoverProviderOperationCommand command) {
        transitions.requireNoAmbientTransaction();
        var operation = operations
                .findById(command.operationId())
                .orElseThrow(PartnerPaymentRules::notFound);
        var request = proof.request(operation);
        var scope = new IdempotencyRecord.Scope(
                IdempotencyRecord.Actor.ADMIN,
                request.siteId().value(),
                command.actorId(),
                Stage2TelemetryPort.Operation.PROVIDER_RECOVERY,
                operation.id());
        var fingerprint = idem.fingerprint(Map.of(
                "operation_id",
                operation.id(),
                "provider_reference",
                command.providerReference(),
                "creation_event_id",
                command.creationEventId()));
        boolean completed = tx.transaction(() -> {
            operations.lockRecovery(operation);
            requireActiveSite(request);
            var existing = receipts.find(scope.value(), command.idempotencyKey());
            if (existing.isEmpty()) return false;
            var receipt = existing.get();
            if (!receipt.fingerprint().equals(fingerprint)
                    || receipt.status() != IdempotencyRecord.Status.COMPLETED
                    || receipt.resource().type()
                            != IdempotencyRecord.ResourceType.PROVIDER_OPERATION
                    || !receipt.resource().id().equals(operation.id()))
                throw RefundCoordinator.conflict();
            return true;
        });
        if (!completed) {
            var verified =
                    proof.verify(operation, command.providerReference(), command.creationEventId());
            tx.transaction(() -> {
                operations.lockRecovery(operation);
                var current = proof.request(operation);
                requireActiveSite(current);
                if (!verified.operationId().equals(operation.id())
                        || !verified.accountId().equals(operation.accountId())
                        || !verified.providerReference().equals(command.providerReference())
                        || !verified.creationEventId().equals(command.creationEventId())
                        || !verified.fingerprint().equals(operation.fingerprint())
                        || !current.equals(verified.request())) throw PartnerPaymentRules.state();
                var outcome = idem.begin(scope, command.idempotencyKey(), fingerprint);
                if (!(outcome instanceof IdempotencyService.Owner owner))
                    throw RefundCoordinator.conflict();
                operations.bindRecoveredInTransaction(
                        operation, verified.providerReference(), verified.fingerprint());
                idem.complete(
                        owner,
                        new IdempotencyRecord.Resource(
                                IdempotencyRecord.ResourceType.PROVIDER_OPERATION, operation.id()),
                        202,
                        null);
                audit.append(new AuditEntry(
                        UUID.randomUUID(),
                        "ADMIN",
                        command.actorId().toString(),
                        AuditAction.PROVIDER_OPERATION_RECOVERED,
                        "PROVIDER_OPERATION",
                        operation.id().toString(),
                        command.requestId(),
                        null,
                        Map.of(
                                "kind",
                                operation.kind().name(),
                                "status",
                                "ACKNOWLEDGED",
                                "follow_up",
                                operation.kind() == ProviderOperation.Kind.PRODUCT_CREATE
                                                || operation.kind()
                                                        == ProviderOperation.Kind.PRICE_CREATE
                                        ? "ADMIN_PUBLICATION_REQUIRED"
                                        : "VERIFIED_RECONCILIATION"),
                        clock.instant()));
                return null;
            });
        }
        // The accepted receipt establishes binding only. A fresh, independently fenced phase
        // applies money.
        if (operation.kind() == ProviderOperation.Kind.PAYMENT_PREPARE
                || operation.kind() == ProviderOperation.Kind.REFUND_CREATE) {
            var acknowledged = operations.findById(operation.id()).orElseThrow();
            reconcileAcknowledged(acknowledged, command.requestId());
            pending.clearResolved(acknowledged);
        }
    }

    private void requireActiveSite(RecoveryRequestSnapshot request) {
        sites.findById(request.siteId())
                .filter(site -> site.status() == SiteStatus.ACTIVE)
                .orElseThrow(PartnerPaymentRules::state);
    }

    public static boolean mayReplayMissingReference(
            ProviderOperation.Status status, String reference, Instant first, Instant now) {
        return (status == ProviderOperation.Status.UNKNOWN
                        || status == ProviderOperation.Status.DISPATCHING)
                && reference == null
                && first != null
                && now != null
                && ProviderOperationGuard.withinWriteWindow(first, now);
    }
}
