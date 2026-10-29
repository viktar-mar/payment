package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.exception.StripeException;
import com.stripe.model.testhelpers.TestClock;
import com.stripe.net.RequestOptions;
import com.stripe.param.testhelpers.TestClockCreateParams;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.provider.application.service.ProviderOperationGuard;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.*;

/** Private fixture construction only. No registration, polling, recovery, advance, list or delete surface. */
@Transactional(propagation=Propagation.NEVER)
public class StripeBillingTestClockAdapter {
    public static final String API_VERSION="2026-09-30.endive";
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final PaymentAccountVerificationPort verification;
    private final ProviderOperationStore operations;
    private final StripeBillingClockStore clocks;
    private final DigestPort digest;
    public StripeBillingTestClockAdapter(StripeCredentialResolver credentials,StripeClientFactory clients,
            PaymentAccountVerificationPort verification,ProviderOperationStore operations,StripeBillingClockStore clocks,DigestPort digest){
        this.credentials=Objects.requireNonNull(credentials);this.clients=Objects.requireNonNull(clients);
        this.verification=Objects.requireNonNull(verification);this.operations=Objects.requireNonNull(operations);
        this.clocks=Objects.requireNonNull(clocks);this.digest=Objects.requireNonNull(digest);
    }
    public ReadyClock createFresh(PaymentAccount account,ClockOwner owner){
        requireIdentity(account,owner);
        // A completed immutable receipt is local evidence; ACK without it never grants a second GET.
        var ready=clocks.ready(owner.intent());
        if(ready.isPresent()){
            if(!ready.get().owner().equals(owner))throw StripeBillingOwnership.conflict();
            return ready.get();
        }
        boolean claimed=false;var reason=StripeBillingClockStore.StopReason.CLOCK_CREATE_FAILED;
        try{
            clocks.requireActive(owner);
            var reserved=operations.reserve(account,ProviderOperation.Kind.TEST_CLOCK_CREATE,owner.intent(),owner.createKey(),owner.createFingerprint());
            requireOperation(reserved,owner);
            if(reserved.firstDispatchedAt()!=null||reserved.status()!=ProviderOperation.Status.RESERVED)throw StripeBillingOwnership.conflict();
            String claimant=UUID.randomUUID().toString();
            var claim=operations.claim(reserved.id(),claimant,Instant.now(),Duration.ofSeconds(60));
            if(claim.isEmpty())throw StripeBillingOwnership.conflict();
            claimed=true;var operation=claim.get();requireOperation(operation,owner);
            clocks.requireActive(owner);verification.verify(account);
            String key=new ProviderOperationGuard().requireDispatch(operation,account,owner.createFingerprint(),Instant.now());
            var client=clients.create(credentials.resolve(account));
            TestClock posted;
            try{
                posted=client.v1().testHelpers().testClocks().create(TestClockCreateParams.builder()
                    .setFrozenTime(owner.initialFrozenTime()).setName(owner.name()).build(),options(key));
                requireClock(posted,owner,null);
            }catch(StripeException|RuntimeException unsafe){operations.unknown(operation.id(),claimant,operation.version());throw StripeBillingOwnership.mismatch();}
            clocks.requireActive(owner);
            var acknowledged=operations.acknowledge(operation.id(),claimant,operation.version(),posted.getId());
            requireOperation(acknowledged,owner);
            if(acknowledged.status()!=ProviderOperation.Status.ACKNOWLEDGED||!posted.getId().equals(acknowledged.providerReference()))throw StripeBillingOwnership.mismatch();
            reason=StripeBillingClockStore.StopReason.CLOCK_READY_FAILED;
            clocks.requireActive(owner);
            // Only this invocation holds both original claim and POST fields. Exactly one read.
            var observed=client.v1().testHelpers().testClocks().retrieve(acknowledged.providerReference(),options(null));
            requireClock(observed,owner,posted);
            clocks.requireActive(owner);
            return clocks.recordReady(owner,acknowledged,new StripeBillingClockStore.ReadyObservation(
                StripeBillingClockStore.ReadyState.READY,observed.getId(),observed.getFrozenTime(),observed.getCreated(),observed.getDeletesAfter(),Instant.now()));
        }catch(StripeException unsafe){
            var failure=StripeBillingOwnership.mismatch();stop(owner,reason,failure);throw failure;
        }catch(RuntimeException failed){
            // Losing a concurrent claim cannot revoke the actual winner's authority.
            if(claimed)stop(owner,reason,failed);throw failed;
        }
    }
    private void requireIdentity(PaymentAccount account,ClockOwner owner){
        if(account==null||owner==null||!account.id().equals(owner.account())||!account.externalAccountId().equals(owner.externalAccount())
            ||!account.credentialsRef().equals(owner.credentialsRef())||account.status()!=PaymentAccountStatus.ACTIVE
            ||account.provider()!=PaymentProviderType.STRIPE||account.mode()!=PaymentAccountMode.SANDBOX||!API_VERSION.equals(owner.apiVersion())
            ||!StripeBillingClockIdentity.name(owner.intent()).equals(owner.name())||!StripeBillingClockIdentity.createKey(account.id(),owner.intent()).equals(owner.createKey())
            ||!StripeBillingClockIdentity.createFingerprint(digest,account,owner.intent(),owner.run(),owner.site().value(),owner.customer().value(),owner.apiVersion(),owner.initialFrozenTime()).equals(owner.createFingerprint())
            ||!clocks.owner(account.id(),owner.site(),owner.customer()).filter(owner::equals).isPresent())throw StripeBillingOwnership.conflict();
    }
    private static void requireOperation(ProviderOperation op,ClockOwner owner){
        if(op.kind()!=ProviderOperation.Kind.TEST_CLOCK_CREATE||!op.accountId().equals(owner.account())||!op.resourceId().equals(owner.intent())
            ||!op.externalAccountId().equals(owner.externalAccount())||!op.credentialsRef().equals(owner.credentialsRef())
            ||!op.operationKey().equals(owner.createKey())||!op.fingerprint().equals(owner.createFingerprint()))throw StripeBillingOwnership.conflict();
    }
    private static void requireClock(TestClock remote,ClockOwner owner,TestClock original){
        if(remote==null||remote.getId()==null||!remote.getId().matches("clock_[A-Za-z0-9]{1,249}")||!"test_helpers.test_clock".equals(remote.getObject())
            ||!Boolean.FALSE.equals(remote.getLivemode())||Boolean.TRUE.equals(remote.getDeleted())||!owner.name().equals(remote.getName())
            ||!Objects.equals(owner.initialFrozenTime(),remote.getFrozenTime())||!"ready".equals(remote.getStatus())
            ||remote.getStatusDetails()!=null&&remote.getStatusDetails().getAdvancing()!=null
            ||remote.getCreated()==null||remote.getCreated()<=0||remote.getDeletesAfter()==null||remote.getDeletesAfter()<=0
            ||original!=null&&(!original.getId().equals(remote.getId())||!original.getCreated().equals(remote.getCreated())||!original.getDeletesAfter().equals(remote.getDeletesAfter())))throw StripeBillingOwnership.mismatch();
    }
    static RequestOptions options(String key){
        return RequestOptions.RequestOptionsBuilder.unsafeSetStripeVersionOverride(RequestOptions.builder(),API_VERSION)
            .setMaxNetworkRetries(0).setIdempotencyKey(key).build();
    }
    private void stop(ClockOwner owner,StripeBillingClockStore.StopReason reason,RuntimeException failure){
        try{clocks.stop(owner,reason,Instant.now());}catch(RuntimeException stopped){failure.addSuppressed(stopped);}
    }
}
