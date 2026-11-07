package dev.genericpayments.platform.provider.application.port.out;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.*;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Command;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort.Sample;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.time.Instant;
import java.util.*;

/** All operations join an existing caller transaction. No provider calls or transaction suspension. */
public interface OwnedFailedRenewalMethodStore {
    default Reservation reserveBrowserInTransaction(OwnedFailedRenewal.Admission a,OwnedFailedRenewal.Failure f,SubscriptionOperation o,CustomerBinding b,Sample sample,Instant now,Enrollment enrollment){throw browserDenied();}
    default Pending requireBrowserPendingInTransaction(Reservation r,CustomerSessionContext session,Instant now){throw browserDenied();}
    default Optional<Root> browserRootInTransaction(UUID reservationId){throw browserDenied();}
    default Root insertBrowserRootInTransaction(Pending p,Root proposed){throw browserDenied();}
    default void requireBrowserActionInTransaction(Pending p,Root root,Instant now){throw browserDenied();}
    default Selection startBrowserSelectionInTransaction(Pending p,Root root,String keyDigest,Instant now){throw browserDenied();}
    default void finishBrowserSelectionInTransaction(Pending p,Root root,Selection selected,Instant now){throw browserDenied();}
    default boolean consumeBrowserFirstInTransaction(Pending p,Root root,String keyDigest,String requestFingerprint,Instant now){throw browserDenied();}
    private static IllegalStateException browserDenied(){return new IllegalStateException("Owned method browser authority unavailable");}
    Reservation reserveInTransaction(OwnedFailedRenewal.Admission a,OwnedFailedRenewal.Failure f,SubscriptionOperation setup,CustomerBinding binding,Sample sample,Instant now);
    Reservation requireReservationInTransaction(Reservation expected);
    /** Both ACKs and bound method, before the service saves RESERVED -> PROCESSING in this transaction. */
    MethodReadContext requirePreparedMethodInTransaction(Reservation r);
    ReadClaim claimReadInTransaction(Reservation r,Phase phase,UUID invocation,Fence fence,Sample sample,Instant now);
    ReadClaim requireReadInTransaction(Reservation r,ReadClaim expected);
    MethodReadContext requireMethodContextInTransaction(Reservation r,ReadClaim q);
    Completion recordCompletionInTransaction(Reservation r,ReadClaim q,MethodEvidence evidence,Instant now);
    Completion requireCompletionInTransaction(Reservation r,Completion expected);
    Recovery associateRecoveryInTransaction(Reservation r,Completion c,SubscriptionOperation recover,Instant now);
    Recovery requireRecoveryInTransaction(Reservation r,Recovery expected);
    RecoveredReadContext requireRecoveredContextInTransaction(Reservation r,ReadClaim q);
    Publication recordPublicationInTransaction(Reservation r,ReadClaim q,Instant now);
    Optional<Publication> publicationInTransaction(Reservation r);
    void requireMethodFactInTransaction(Command command,Subscription locked,List<SubscriptionOperation> operations,Instant billingAt,Instant utcAt);
    void requireRecoveryFactInTransaction(Command command,Subscription locked,List<SubscriptionOperation> operations,Instant billingAt,Instant utcAt);
}
