package dev.genericpayments.platform.provider.application.port.out;

import dev.genericpayments.platform.provider.application.model.OwnedClockSubscriptionReceipt;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import java.time.Instant;
import java.util.Optional;

/** No provider traffic. Paid reads and admission participate in the caller lifecycle transaction. */
public interface OwnedFirstRenewalStore {
    Admission admitPaid(OwnedClockSubscriptionReceipt receipt,String sourceSha,Instant utcAt);
    Admission requirePaidInTransaction(Admission admission);
    Advance prepare(Admission admission,Step step,Boundary boundary,Instant utcAt);
    void stop(Admission admission,String reason,Instant utcAt);
    Admission requireCurrent(Admission admission);
    /** Exact local completed receipt only; never transfers a bare ACK's continuation. */
    default Optional<LaterReady> ready(Admission admission,Step step) {throw new UnsupportedOperationException();}
    /** Insert-winner authority is implemented by the graph consumer under caller lifecycle locks. */
    default ReadClaim beginReadInTransaction(Admission admission,LaterReady ready,
            dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence fence,
            java.util.UUID invocation,Instant startedAt) {throw new UnsupportedOperationException();}
    default Optional<ReadClaim> readClaim(Admission admission,LaterReady ready) {throw new UnsupportedOperationException();}
    LaterReady recordReady(Ack ack,TestClockReady observation,Instant utcAt);
    Boundary recordBoundary(Admission admission,LaterReady ready,Boundary boundary);
    Boundary boundary(Admission admission);
    Publication recordPublicationInTransaction(Admission admission,LaterReady ready,Paid paid);
    Optional<Publication> publicationInTransaction(Admission admission);
}
