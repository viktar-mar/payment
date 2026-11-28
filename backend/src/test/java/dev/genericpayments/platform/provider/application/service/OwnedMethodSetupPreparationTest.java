package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Actual method service, inert stateful caller transactions only; no provider or runtime. */
class OwnedMethodSetupPreparationTest {
    @Test void originalBrowserReservationIsInspectableBeforeAnyAckOrProofWithoutWrites() {
        var h=new OwnedFailedRenewalMethodServiceTest.Harness(true);var r=reserve(h);var before=state(h);h.b.trace.clear();
        var c=h.service.inspectBrowserSetupPreparation(r,h.context);
        assertThat(c.reservation()).isEqualTo(r);assertThat(c.enrollment()).isEqualTo(h.enrollment);
        assertThat(c.account()).isEqualTo(h.b.account);assertThat(c.subscription()).isEqualTo(h.b.s);
        assertThat(c.operation()).isEqualTo(h.b.ops.get(r.setupOperationId()));assertThat(c.binding()).isEqualTo(h.b.binding);
        assertThat(c.billingSample()).isEqualTo(r.billingSample());assertThat(h.acks).isFalse();assertThat(h.b.methodSetup).isNull();
        assertThat(state(h)).isEqualTo(before);assertThat(h.b.trace).containsSubsequence("begin","operations","authority","lifecycle","account","subscription","bill","authority","setup-enrollment","commit");
        assertThat(h.b.trace).doesNotContain("stop","prepared","claim","reservation");
        assertThat(c.toString()).isEqualTo("SetupPreparationContext[redacted]");
        assertThatThrownBy(()->h.service.browserPhase(r,h.context,p->p)).isInstanceOf(RuntimeException.class);
        assertThat(state(h)).isEqualTo(before);
    }
    @Test void inspectionRejectsForeignOrExpiredClientAndOriginalKeyChangesWithoutStoppingHolder() {
        for(var bad:List.of("session","origin","customer","site","expiry","scope","admin","key","missing-key","expired","non-browser","ambient","revoked")) {
            var h=new OwnedFailedRenewalMethodServiceTest.Harness(true);var r=bad.equals("non-browser")?h.reserve():reserve(h);
            var s=((SubscriptionActor.Customer)h.context.actor()).session();var c=h.context;
            if(Set.of("session","origin","customer","site","expiry","scope").contains(bad)) {
                var changed=new CustomerSessionContext(bad.equals("session")?new CustomerSessionId(UUID.randomUUID()):s.sessionId(),
                    bad.equals("site")?new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()):s.siteId(),
                    bad.equals("customer")?new CustomerId(UUID.randomUUID()):s.customerId(),bad.equals("origin")?"https://foreign.example":s.origin(),
                    bad.equals("scope")?Set.of(CustomerSessionScope.SUBSCRIPTION_READ):s.scopes(),bad.equals("expiry")?s.expiresAt().plusSeconds(1):s.expiresAt());
                c=new SubscriptionCommandContext(new SubscriptionActor.Customer(changed),changed.siteId(),changed.customerId(),changed.origin(),c.idempotencyKey(),"changed");
            }
            if(bad.equals("admin"))c=new OwnedFailedRenewalMethodServiceTest.Harness().context;
            if(bad.equals("key")||bad.equals("missing-key"))c=new SubscriptionCommandContext(c.actor(),c.siteId(),c.customerId(),c.origin(),bad.equals("key")?"changed":null,"changed");
            if(bad.equals("expired"))h.b.utc=s.expiresAt();if(bad.equals("ambient"))h.b.active=true;if(bad.equals("revoked"))h.liveSession=false;
            var before=state(h);var supplied=c;
            assertThatThrownBy(()->h.service.inspectBrowserSetupPreparation(r,supplied)).as(bad).isInstanceOf(RuntimeException.class);
            assertThat(state(h)).as(bad).isEqualTo(before);assertThat(h.b.stopped).as(bad).isFalse();
        }
    }
    @Test void locksCannotExtendOriginalSessionOrAuthority() {
        for(var bad:List.of("expired","revoked","authority")) {
            var h=new OwnedFailedRenewalMethodServiceTest.Harness(true);var r=reserve(h);
            var s=((SubscriptionActor.Customer)h.context.actor()).session();
            var effects=h.b.effects;h.afterAccountLock=()->{if(bad.equals("expired"))h.b.utc=s.expiresAt();if(bad.equals("revoked"))h.liveSession=false;if(bad.equals("authority"))h.authorized=false;};
            assertThatThrownBy(()->h.service.inspectBrowserSetupPreparation(r,h.context)).as(bad).isInstanceOf(RuntimeException.class);
            assertThat(h.b.stopped).isFalse();assertThat(h.b.effects).isEqualTo(effects);assertThat(h.completion).isNull();
        }
    }
    @Test void originalOperationDebtBindingSequenceAndIsolatedTimeMustStillMatch() {
        for(var bad:List.of("stopped","authority","version","status","fingerprint","key-digest","method-authority","sequence","billing","mode","binding","debt","bill")) {
            var h=new OwnedFailedRenewalMethodServiceTest.Harness(true);var r=reserve(h);var o=h.b.ops.get(r.setupOperationId());
            if(bad.equals("stopped"))h.b.stopped=true;if(bad.equals("authority"))h.authorized=false;
            if(Set.of("version","status","fingerprint","key-digest","method-authority").contains(bad))h.b.ops.put(o.id(),new SubscriptionOperation(o.id(),o.siteId(),o.customerId(),o.subscriptionId(),o.kind(),
                bad.equals("status")?SubscriptionOperation.Status.PROCESSING:o.status(),bad.equals("method-authority")?"foreign":o.authorityScope(),bad.equals("key-digest")?"foreign":o.keyDigest(),
                bad.equals("fingerprint")?"foreign":o.fingerprint(),o.providerOperationKey(),o.expectedRevision(),o.policyVersion(),o.providerReference(),o.safeFailureCode(),o.createdAt(),o.updatedAt(),bad.equals("version")?1:o.version()));
            if(bad.equals("sequence"))h.b.sequence++;if(bad.equals("billing"))h.b.billingSecond++;if(bad.equals("mode"))h.b.isolated=false;
            if(bad.equals("binding")){var b=h.b.binding;h.b.binding=new CustomerBinding(b.id(),b.accountId(),b.siteId(),b.customerId(),b.providerReference(),b.creationOperationId(),b.version()+1);}
            if(bad.equals("debt")){var s=h.b.s;h.b.s=new Subscription(s.id(),s.siteId(),s.customerId(),s.productId(),s.paymentAccountId(),s.billingPrice(),s.entitledPrice(),s.billingStatus(),s.providerReference(),s.currentPeriod(),s.paidThrough(),s.trial(),s.cancellation(),s.pendingChange(),null,s.terminalReason(),s.revision(),s.createdAt(),s.updatedAt(),s.lastReconciledAt());}
            if(bad.equals("bill")){var b=h.b.records.get("in_renewal");h.b.records.put("in_renewal",new BillingRecord(b.id(),b.siteId(),b.subscriptionId(),b.accountId(),b.providerInvoiceReference(),b.kind(),b.billedPrice(),b.previousPrice(),b.period(),b.amountDueMinor(),b.currency(),b.settlement(),b.version()+1,b.createdAt(),b.updatedAt()));}
            var before=state(h);assertThatThrownBy(()->h.service.inspectBrowserSetupPreparation(r,h.context)).as(bad).isInstanceOf(RuntimeException.class);
            assertThat(state(h)).as(bad).isEqualTo(before);assertThat(h.b.trace).doesNotContain("stop");
        }
    }
    @Test void preparationContextRequiresEveryFieldAndContainsNoUnredactedIdentity() {
        var h=new OwnedFailedRenewalMethodServiceTest.Harness(true);var r=reserve(h);var c=h.service.inspectBrowserSetupPreparation(r,h.context);
        for(int i=0;i<7;i++){int field=i;assertThatThrownBy(()->new SetupPreparationContext(field==0?null:r,field==1?null:c.enrollment(),field==2?null:c.account(),field==3?null:c.subscription(),field==4?null:c.operation(),field==5?null:c.binding(),field==6?null:c.billingSample())).isInstanceOf(IllegalArgumentException.class);}
        assertThat(c.toString()).doesNotContain(r.id().toString(),c.enrollment().sessionId().toString(),c.binding().providerReference(),h.context.idempotencyKey());
    }
    private static Reservation reserve(OwnedFailedRenewalMethodServiceTest.Harness h){return h.service.reserveBrowserMethod(h.b.admission,h.context,true);}
    private static List<Object> state(OwnedFailedRenewalMethodServiceTest.Harness h){
        return Arrays.asList(h.b.s,new LinkedHashMap<>(h.b.ops),new LinkedHashMap<>(h.b.records),new LinkedHashSet<>(h.b.cash),new ArrayList<>(h.b.events),h.b.callbacks,h.b.sequence,h.b.effects,h.b.stopped,h.reservation,h.enrollment,h.completion,h.recovery,h.publication,new HashMap<>(h.claims),h.b.methodSetup);
    }
}
