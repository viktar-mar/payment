package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Reservation;
import dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.subscriptions.application.service.*;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static dev.genericpayments.platform.provider.application.service.OwnedFailedRenewalServiceTest.*;

/** Actual method/browser services, handwritten transactions and durable receipt state; no runtime IO. */
public final class OwnedMethodBrowserInertFixture {
    final OwnedFailedRenewalServiceTest.Harness b=new OwnedFailedRenewalServiceTest.Harness(true);
    public final SubscriptionCommandContext context;
    public final OwnedFailedRenewalMethodService methods;
    public final OwnedFailedRenewalMethodStore store;
    public final Clock clock;
    public Reservation reservation;public Enrollment enrollment;public Root root;public Selection selection;
    public int transactionId,selectionTransaction,releaseTransaction,consumeTransaction,reads;
    public boolean released,consumed,failRelease,failConsume,failCommit,authorized=true;
    public String consumeKey,consumeFingerprint;public Runnable onRead=()->{};
    public Runnable onRoot=()->{};
    public Runnable afterCommit=()->{};
    public java.util.function.UnaryOperator<Pending> pendingMutation=p->p;
    private final int initialEffects;private final long initialSequence;private final Object initialSubscription,initialBills,initialCash,initialEvents;
    public OwnedMethodBrowserInertFixture(){
        b.fail();b.trace.clear();
        var session=new CustomerSessionContext(new CustomerSessionId(UUID.randomUUID()),b.owner.site(),b.owner.customer(),"https://test.example",Set.of(CustomerSessionScope.SUBSCRIPTION_MANAGE),UTC.plusSeconds(600));
        context=new SubscriptionCommandContext(new SubscriptionActor.Customer(session),session.siteId(),session.customerId(),session.origin(),"reserve-key","inert");
        clock=b.clockPort;
        var tx=new SubscriptionTransactionPort(){public boolean active(){return b.active;}public <T>T required(Supplier<T> w){return requiresNew(w);}public <T>T requiresNew(Supplier<T> w){
            var oldR=root;var oldS=selection;var oldRelease=released;var oldConsume=consumed;var oldKey=consumeKey;var oldFp=consumeFingerprint;var oldReservation=reservation;var oldEnrollment=enrollment;
            transactionId++;
            try{var result=b.transactionPort.requiresNew(()->{var value=w.get();if(failCommit){failCommit=false;throw new IllegalStateException("inert commit loss");}return value;});afterCommit.run();return result;}
            catch(RuntimeException e){root=oldR;selection=oldS;released=oldRelease;consumed=oldConsume;consumeKey=oldKey;consumeFingerprint=oldFp;reservation=oldReservation;enrollment=oldEnrollment;throw e;}
        }};
        store=port(OwnedFailedRenewalMethodStore.class,(m,a)->{if(!b.active)throw new AssertionError("receipt outside caller transaction");return switch(m){
            case "reserveBrowserInTransaction"->{var o=(SubscriptionOperation)a[2];enrollment=(Enrollment)a[6];reservation=new Reservation(UUID.randomUUID(),b.admission,b.failure,o.id(),o.fingerprint(),0,b.binding,(SubscriptionBillingTimePort.Sample)a[4],(Instant)a[5]);yield reservation;}
            case "requireReservationInTransaction"->{if(!reservation.equals(a[0]))throw denied();yield reservation;}
            case "requireBrowserPendingInTransaction"->{var s=(CustomerSessionContext)a[1];if(!reservation.equals(a[0])||!enrollment.sessionId().equals(s.sessionId().value())||!enrollment.origin().equals(s.origin())||!enrollment.sessionExpiresAt().equals(s.expiresAt()))throw denied();yield pending();}
            case "browserRootInTransaction"->{if(!reservation.id().equals(a[0]))throw denied();onRoot.run();yield Optional.ofNullable(root);}
            case "insertBrowserRootInTransaction"->{if(root!=null)throw denied();root=(Root)a[1];yield root;}
            case "requireBrowserActionInTransaction"->{valid((Root)a[1],(Instant)a[2]);if(selection!=null)throw denied();yield null;}
            case "startBrowserSelectionInTransaction"->{valid((Root)a[1],(Instant)a[3]);if(selection!=null)throw denied();var now=(Instant)a[3];var deadline=now.plusSeconds(30).isBefore(root.expiresAt())?now.plusSeconds(30):root.expiresAt();selection=new Selection(root.id(),(String)a[2],now,deadline);selectionTransaction=transactionId;yield selection;}
            case "finishBrowserSelectionInTransaction"->{valid((Root)a[1],(Instant)a[3]);if(failRelease||released||!Objects.equals(selection,a[2])||!b.utc.isBefore(selection.deadlineAt())||transactionId<=selectionTransaction)throw denied();released=true;releaseTransaction=transactionId;yield null;}
            case "consumeBrowserFirstInTransaction"->{valid((Root)a[1],(Instant)a[4]);if(!released||transactionId<=releaseTransaction||failConsume)throw denied();var key=(String)a[2];var fp=(String)a[3];if(consumed){if(!consumeKey.equals(key)||!consumeFingerprint.equals(fp))throw denied();yield false;}consumed=true;consumeKey=key;consumeFingerprint=fp;consumeTransaction=transactionId;yield true;}
            default->throw new AssertionError("Unplanned method store call "+m);
        };});
        methods=new OwnedFailedRenewalMethodService(store,port(OwnedFailedRenewalStore.class,(m,a)->{if(m.equals("requireFailureInTransaction"))return b.failure;throw new AssertionError("No stop or other failed-renewal mutation allowed: "+m);}),tx,b.operationsPort,
            (site,customer)->b.trace.add("lifecycle"),id->Optional.of(b.account),b.subscriptionsPort,b.billsPort,new SubscriptionReadPreparation(b.operationsPort,port(TrialEligibilityStore.class,(m,a)->Optional.empty())),b.observationsPort,b.factsPort,
            port(TrialEligibilityStore.class,(m,a)->Optional.empty()),b.timePort,b.clockPort,port(StripeBillingBindingPort.class,(m,a)->Optional.of(b.binding)),port(SubscriptionAuthorityPort.class,(m,a)->{if(!authorized)throw denied();return null;}),v->"digest:"+v);
        reservation=methods.reserveBrowserMethod(b.admission,context,true);
        initialEffects=b.effects;initialSequence=b.sequence;initialSubscription=b.s;initialBills=new LinkedHashMap<>(b.records);initialCash=new LinkedHashSet<>(b.cash);initialEvents=new ArrayList<>(b.events);
    }
    public Pending pending(){var o=b.ops.get(reservation.setupOperationId());var step=new ProviderOperation(UUID.fromString("00000000-0000-0000-0000-000000000091"),b.account.id(),b.account.externalAccountId(),b.account.credentialsRef(),ProviderOperation.Kind.SETUP_INTENT_CREATE,o.id(),o.providerOperationKey()+":SETUP","original-setup-fingerprint",ProviderOperation.Status.ACKNOWLEDGED,"seti_owned",UTC,UTC.plusSeconds(100),null,null,2);return pendingMutation.apply(new Pending(reservation,enrollment,b.account,b.s,o,b.binding,new SubscriptionBillingProofStore.Setup(o.id(),b.s.id(),b.account.id(),step.id(),"seti_owned",null),step));}
    public SubscriptionCommandContext command(String key){return new SubscriptionCommandContext(context.actor(),context.siteId(),context.customerId(),context.origin(),key,"inert");}
    public void at(Instant now){b.utc=now;}
    public Instant now(){return b.utc;}
    public boolean active(){return b.active;}
    public void noBusinessChanges(){org.assertj.core.api.Assertions.assertThat(b.effects).isEqualTo(initialEffects);org.assertj.core.api.Assertions.assertThat(b.sequence).isEqualTo(initialSequence);org.assertj.core.api.Assertions.assertThat(b.s).isEqualTo(initialSubscription);org.assertj.core.api.Assertions.assertThat(b.records).isEqualTo(initialBills);org.assertj.core.api.Assertions.assertThat(b.cash).isEqualTo(initialCash);org.assertj.core.api.Assertions.assertThat(b.events).isEqualTo(initialEvents);org.assertj.core.api.Assertions.assertThat(b.stopped).isFalse();org.assertj.core.api.Assertions.assertThat(b.ops.get(reservation.setupOperationId()).status()).isEqualTo(SubscriptionOperation.Status.RESERVED);org.assertj.core.api.Assertions.assertThat(b.ops.get(reservation.setupOperationId()).version()).isZero();}
    private void valid(Root expected,Instant now){if(!Objects.equals(root,expected)||now.isBefore(root.issuedAt())||!now.isBefore(root.expiresAt())||!b.utc.isBefore(root.expiresAt()))throw denied();}
    private static IllegalStateException denied(){return new IllegalStateException("Inert browser authority unavailable");}
}
