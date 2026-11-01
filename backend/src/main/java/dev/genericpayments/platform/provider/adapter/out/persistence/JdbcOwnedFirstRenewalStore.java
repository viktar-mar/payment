package dev.genericpayments.platform.provider.adapter.out.persistence;

import dev.genericpayments.platform.provider.application.model.OwnedClockSubscriptionReceipt;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.port.out.OwnedFirstRenewalStore;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Scaffold fails closed until the admission consumer's behavioral hosted RED is observed. */
@Repository
public class JdbcOwnedFirstRenewalStore implements OwnedFirstRenewalStore {
    private final JdbcTemplate jdbc;
    public JdbcOwnedFirstRenewalStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static IllegalStateException unavailable(){return new IllegalStateException("Owned first renewal authority unavailable");}
    @Transactional(propagation=Propagation.MANDATORY)
    public Admission admitPaid(OwnedClockSubscriptionReceipt receipt,String sourceSha,Instant utcAt){throw unavailable();}
    @Transactional(propagation=Propagation.MANDATORY)
    public Admission requirePaidInTransaction(Admission admission){throw unavailable();}
    public Advance prepare(Admission admission,Step step,Boundary boundary,Instant utcAt){throw unavailable();}
    public void stop(Admission admission,String reason,Instant utcAt){throw unavailable();}
    public Admission requireCurrent(Admission admission){throw unavailable();}
    public LaterReady recordReady(Ack ack,TestClockReady observation,Instant utcAt){throw unavailable();}
    public Boundary recordBoundary(Admission admission,LaterReady ready,Boundary boundary){throw unavailable();}
    public Boundary boundary(Admission admission){throw unavailable();}
    public Publication recordPublicationInTransaction(Admission admission,LaterReady ready,Paid paid){throw unavailable();}
    public Optional<Publication> publicationInTransaction(Admission admission){throw unavailable();}
}
