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
    LaterReady recordReady(Ack ack,TestClockReady observation,Instant utcAt);
    Boundary recordBoundary(Admission admission,LaterReady ready,Boundary boundary);
    Boundary boundary(Admission admission);
    Publication recordPublicationInTransaction(Admission admission,LaterReady ready,Paid paid);
    Optional<Publication> publicationInTransaction(Admission admission);
}
