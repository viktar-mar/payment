package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceAdvance.*;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OwnedFailedGraceAdvanceModelTest {
    @Test void contextRequiresExactPinnedAccountFailurePreparationAndRecomputedFingerprint(){
        var f=new OwnedFailedGraceAdvanceInertFixture();var c=f.context;var p=c.preparation();var a=c.account();var failure=c.failure();
        assertThat(new PreparationContext(c.admission(),p,failure,a)).isEqualTo(c);assertThat(p.target()).isEqualTo(failure.graceDeadline().getEpochSecond()-1);
        for(var bad:List.of("account","credentials","mode","failure","bill","ready","target","fingerprint","source")){
            assertThatThrownBy(()->{
                var account=bad.equals("account")?PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),"foreign",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,a.externalAccountId(),a.credentialsRef()):
                    bad.equals("credentials")?PaymentAccount.create(a.id(),"foreign",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,a.externalAccountId(),"other-alias"):
                    bad.equals("mode")?PaymentAccount.create(a.id(),"foreign",PaymentProviderType.STRIPE,PaymentAccountMode.LIVE,a.externalAccountId(),a.credentialsRef()):a;
                var prep=new GraceLastSecondPreparation(p.id(),p.admissionId(),bad.equals("failure")?UUID.randomUUID():p.failureClaimId(),bad.equals("bill")?UUID.randomUUID():p.billId(),bad.equals("ready")?UUID.randomUUID():p.predecessorReadyId(),p.graceDeadline(),bad.equals("target")?p.target()-1:p.target(),p.operationKey(),bad.equals("fingerprint")?"f".repeat(64):p.fingerprint(),p.preparedAt());
                var admission=bad.equals("source")?new OwnedFailedRenewal.Admission(c.admission().id(),c.admission().firstRenewalAdmission(),c.admission().ready(),c.admission().boundary(),"f".repeat(40),c.admission().admittedAt()):c.admission();
                new PreparationContext(admission,prep,failure,account);
            }).as(bad).isInstanceOf(RuntimeException.class);
        }
        assertThat(c.toString()).isEqualTo("PreparationContext[redacted]");
    }
    @Test void ackEnvelopeRequiresAckTwoOriginalLeaseNormalizedChronologyAndReference(){
        var f=new OwnedFailedGraceAdvanceInertFixture();var claim=f.claim();var ack=f.acknowledge(claim);
        assertThat(ack.toString()).isEqualTo("AdvanceAck[redacted]");
        for(var bad:List.of("kind","status","version","resource","reference","lease","invocation","claimant","deadline","observed","acknowledged","nanos")){
            assertThatThrownBy(()->{
                var o=ack.operation();var operation=new ProviderOperation(o.id(),o.accountId(),o.externalAccountId(),o.credentialsRef(),bad.equals("kind")?Kind.TEST_CLOCK_ADVANCE:o.kind(),bad.equals("resource")?UUID.randomUUID():o.resourceId(),o.operationKey(),o.fingerprint(),bad.equals("status")?Status.UNKNOWN:o.status(),bad.equals("reference")?"clock_foreign":o.providerReference(),o.firstDispatchedAt(),o.retryBefore(),bad.equals("lease")?"retained":null,bad.equals("lease")?claim.leaseExpiresAt():null,bad.equals("version")?1:o.version());
                new AdvanceAck(operation,ack.preparationId(),bad.equals("invocation")?null:ack.invocation(),bad.equals("claimant")?" ":ack.claimant(),bad.equals("deadline")?ack.acknowledgedAt():ack.deadline(),ack.observation(),bad.equals("observed")?o.firstDispatchedAt().minusSeconds(1):ack.observedAt(),bad.equals("acknowledged")?ack.observedAt().minusSeconds(1):bad.equals("nanos")?ack.acknowledgedAt().plusNanos(1):ack.acknowledgedAt());
            }).as(bad).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void readyEnvelopePreservesEveryImmutableClockFieldAndAckOrder(){
        var f=new OwnedFailedGraceAdvanceInertFixture();var ack=f.acknowledge(f.claim());var c=f.ready();
        var ready=new GraceAdvanceReady(UUID.randomUUID(),f.context.admission().id(),ack,c,f.now());assertThat(ready.toString()).isEqualTo("GraceAdvanceReady[redacted]");
        for(var bad:List.of("reference","name","created","deletes","observed","nanos")){
            assertThatThrownBy(()->new GraceAdvanceReady(UUID.randomUUID(),f.context.admission().id(),ack,new TestClockReady(bad.equals("reference")?"clock_foreign":c.reference(),c.object(),false,bad.equals("name")?"other":c.name(),c.frozenTime(),"ready",bad.equals("created")?c.created()+1:c.created(),bad.equals("deletes")?c.deletesAfter()+1:c.deletesAfter(),null),bad.equals("observed")?ack.acknowledgedAt().minusSeconds(1):bad.equals("nanos")?f.now().plusNanos(1):f.now()))
                .as(bad).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void consumingServiceChecksFixedTargetAndOriginalClockEvenForStructurallyValidEnvelopes(){
        for(var bad:List.of("target","name","reference","created","deletes","future","before-dispatch")){
            var f=new OwnedFailedGraceAdvanceInertFixture();var claim=f.claim();var c=f.post();
            var observation=new TestClockAck(bad.equals("reference")?"clock_foreign":c.reference(),c.object(),false,bad.equals("name")?"foreign":c.name(),bad.equals("target")?c.frozenTime()-1:c.frozenTime(),"advancing",bad.equals("created")?c.created()+1:c.created(),bad.equals("deletes")?c.deletesAfter()+1:c.deletesAfter(),bad.equals("target")?c.frozenTime()-1:c.advancingTarget());
            var observed=bad.equals("future")?f.now().plusNanos(1):bad.equals("before-dispatch")?claim.firstDispatchedAt().minusNanos(1):f.now();
            assertThatThrownBy(()->f.service.acknowledge(f.context,claim,observation,observed,f.budget)).as(bad).isInstanceOf(RuntimeException.class);assertThat(f.row()).isEqualTo(claim);assertThat(f.ack()).isNull();
        }
        var f=new OwnedFailedGraceAdvanceInertFixture();var ack=f.acknowledge(f.claim());var c=f.ready();
        var intermediate=new TestClockReady(c.reference(),c.object(),false,c.name(),c.frozenTime()-1,"ready",c.created(),c.deletesAfter(),null);
        assertThatThrownBy(()->f.service.recordReady(f.context,ack,intermediate,f.now(),f.budget)).isInstanceOf(RuntimeException.class);assertThat(f.savedReady()).isNull();
    }
}
