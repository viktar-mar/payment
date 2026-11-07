package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Reservation;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Test-only bridge to actual failed publication/reservation/inspection with handwritten memory ports. */
public final class OwnedMethodSetupInertFixture {
    private final OwnedFailedRenewalMethodServiceTest.Harness h=new OwnedFailedRenewalMethodServiceTest.Harness(true,"stripe-sandbox-default");
    public final OwnedFailedRenewalMethodService methods=h.service;
    public final SubscriptionCommandContext context=h.context;
    public final Reservation reservation=methods.reserveBrowserMethod(h.b.admission,context,true);
    public final Clock clock=h.b.clockPort;
    private final Object subscription=h.b.s;
    private final Map<?,?> bills=new LinkedHashMap<>(h.b.records);
    private final Set<?> cash=new LinkedHashSet<>(h.b.cash);
    private final List<?> events=new ArrayList<>(h.b.events);
    private final long sequence=h.b.sequence;
    private final int effects=h.b.effects,callbacks=h.b.callbacks;
    public OwnedMethodSetupInertFixture(){h.b.trace.clear();}
    public PaymentAccount account(){return h.b.account;}
    public Subscription subscription(){return h.b.s;}
    public SubscriptionOperation operation(){return h.b.ops.get(reservation.setupOperationId());}
    public CustomerBinding binding(){return h.b.binding;}
    public Instant now(){return h.b.utc;}
    public void at(Instant value){h.b.utc=value;}
    public boolean active(){return h.b.active;}
    public boolean stopped(){return h.b.stopped;}
    public long stops(){return h.b.trace.stream().filter("stop"::equals).count();}
    public void revoke(){h.liveSession=false;}
    public void failStop(){h.failStop=true;}
    public void drift(String kind){switch(kind){
        case "session"->revoke();case "authority"->h.authorized=false;case "sequence"->h.b.sequence++;
        case "sample"->h.b.billingSecond++;case "mode"->h.b.isolated=false;
        case "binding"->{var b=h.b.binding;h.b.binding=new CustomerBinding(b.id(),b.accountId(),b.siteId(),b.customerId(),b.providerReference(),b.creationOperationId(),b.version()+1);}
        case "operation"->{var o=operation();h.b.ops.put(o.id(),new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),o.status(),o.authorityScope(),o.keyDigest(),o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),o.safeFailureCode(),o.createdAt(),o.updatedAt(),o.version()+1));}
        default->throw new AssertionError(kind);
    }}
    public void afterAccountLock(Runnable action){h.afterAccountLock=action;}
    public void noBusinessChanges(){
        assertThat(h.b.s).isEqualTo(subscription);assertThat(h.b.records).isEqualTo(bills);assertThat(h.b.cash).isEqualTo(cash);assertThat(h.b.events).isEqualTo(events);
        assertThat(h.b.sequence).isEqualTo(sequence);assertThat(h.b.effects).isEqualTo(effects);assertThat(h.b.callbacks).isEqualTo(callbacks);
        assertThat(operation().status()).isEqualTo(SubscriptionOperation.Status.RESERVED);assertThat(operation().version()).isZero();
    }
}
