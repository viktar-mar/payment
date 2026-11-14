package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceBoundary;
import dev.genericpayments.platform.provider.application.model.OwnedFailedGraceBoundary.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.TestClockReady;
import dev.genericpayments.platform.provider.application.port.out.OwnedFailedGraceBoundaryStore;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OwnedFailedGraceBoundaryServiceTest {
    static UUID id(long low){return new UUID(0,low);}
    @Test void literalCanonicalVectorIncludesExplicitPhaseNullableDetailAndUtf8ClockName(){
        var previous=new ReadyOrigin(id(82),id(83),id(84),"a".repeat(64),
            new TestClockReady("clock_owned","test_helpers.test_clock",false,"phase-µ",260299,"ready",2000,3000,null),
            Instant.parse("2026-11-01T12:00:00.123456Z"));
        assertThat(OwnedFailedGraceBoundary.fingerprint(id(71),id(81),"a".repeat(64),id(82),Phase.AT,previous,
            Instant.parse("1970-01-04T00:18:20Z"),260300,"billing-failed-grace-boundary-v1:00000000-0000-0000-0000-000000000047:at"))
            .isEqualTo("c349cde6829c4cb45fb4c532dde2d16e0233e91abab363c0aeeea9c86a4f7b2e");
    }
    @Test void twoExplicitEdgesRetainOriginalRootAndNeverAdoptOrInferLaterPhase(){
        var f=new OwnedFailedGraceBoundaryInertFixture();var before=f.base.financialState();
        assertThatThrownBy(()->f.inspect(Phase.AFTER,f.base.newBudget())).isInstanceOf(RuntimeException.class);
        var at=f.inspect(Phase.AT,f.base.newBudget());
        assertThat(at.predecessor().readyId()).isEqualTo(f.base.savedReady().id());
        assertThat(at.target()).isEqualTo(260300);assertThat(at.phase().number()).isEqualTo(2);
        assertThat(at.operationKey()).isEqualTo("billing-failed-grace-boundary-v1:00000000-0000-0000-0000-000000000047:at");
        var atReady=f.complete(Phase.AT);
        assertThatThrownBy(()->f.inspect(Phase.AT,f.base.newBudget())).isInstanceOf(RuntimeException.class);
        assertThat(f.row(Phase.AFTER)).isNull();
        var after=f.inspect(Phase.AFTER,f.base.newBudget());
        assertThat(after.originalGraceReadyId()).isEqualTo(at.originalGraceReadyId());
        assertThat(after.predecessor().readyId()).isEqualTo(atReady.id());
        assertThat(after.predecessor().operationFingerprint()).isEqualTo(at.fingerprint());
        assertThat(after.target()).isEqualTo(260301);assertThat(after.phase().number()).isEqualTo(3);
        assertThat(after.operationKey()).isEqualTo("billing-failed-grace-boundary-v1:00000000-0000-0000-0000-000000000047:after");
        assertThat(f.complete(Phase.AFTER).observation().frozenTime()).isEqualTo(260301);
        assertThat(f.base.financialState()).isEqualTo(before);assertThat(f.cleanups).isZero();
    }
    @Test void malformedOrSubstitutedPredecessorsAndForgedContextAreRejected(){
        var f=new OwnedFailedGraceBoundaryInertFixture();var at=f.inspect(Phase.AT,f.base.newBudget());var p=at.predecessor();var r=p.observation();
        for(long frozen:new long[]{4700,260300,Long.MAX_VALUE}){
            var other=new TestClockReady(r.reference(),r.object(),false,r.name(),frozen,"ready",r.created(),r.deletesAfter(),null);
            assertThatThrownBy(()->OwnedFailedGraceBoundary.context(at.original(),Phase.AT,at.originalGraceReadyId(),
                new ReadyOrigin(p.readyId(),p.operationId(),p.invocation(),p.operationFingerprint(),other,p.observedAt()))).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(()->OwnedFailedGraceBoundary.context(at.original(),Phase.AT,UUID.randomUUID(),p)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->new BoundaryContext(at.original(),Phase.AFTER,at.originalGraceReadyId(),p,at.target(),at.operationKey(),at.fingerprint())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->new BoundaryContext(at.original(),Phase.AT,at.originalGraceReadyId(),p,at.target(),at.operationKey(),"f".repeat(64))).isInstanceOf(RuntimeException.class);
    }
    @Test void defaultPortAmbientAndPostWriteCurrentDriftDenyWithRollback(){
        var f=new OwnedFailedGraceBoundaryInertFixture();var b=f.base.newBudget();var c=f.inspect(Phase.AT,b);
        assertThatThrownBy(()->new OwnedFailedGraceBoundaryStore(){}.requirePredecessorInTransaction(c.original(),Phase.AT,b)).isInstanceOf(IllegalStateException.class);
        f.base.trace().clear();f.base.h.active=true;
        try{assertThatThrownBy(()->f.service.claim(c,"owner",b)).isInstanceOf(IllegalStateException.class);}
        finally{f.base.h.active=false;}
        assertThat(f.base.trace()).isEmpty();
        f.afterWrite=()->f.base.drift("sample");
        assertThatThrownBy(()->f.service.claim(c,"owner",b)).isInstanceOf(RuntimeException.class);
        assertThat(f.row(Phase.AT)).isNull();assertThat(f.base.trace()).startsWith("begin","limit","fresh","graceLocks").endsWith("rollback");
    }
    @Test void borrowedInvocationAndStaleDescriptionsCannotStopWinner(){
        var f=new OwnedFailedGraceBoundaryInertFixture();var b=f.base.newBudget();var c=f.inspect(Phase.AT,b);
        var claim=f.service.claim(c,"winner",b);var foreign=f.base.newBudget();
        assertThatThrownBy(()->f.service.claim(c,"loser",foreign)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->f.service.inspectClaim(c,claim,foreign)).isInstanceOf(RuntimeException.class);
        f.service.uncertain(c,claim,null,foreign);assertThat(f.row(Phase.AT).stopped()).isFalse();
        var ack=f.service.acknowledge(c,claim,f.base.observation(260300,"ready",null),f.base.now(),b);
        f.service.uncertain(c,claim,ack,foreign);assertThat(f.row(Phase.AT).stopped()).isFalse();
        assertThat(f.service.inspectAck(c,ack,b)).isEqualTo(ack);
        f.base.at(f.base.now().plusSeconds(61));f.base.drift("sample");
        f.service.uncertain(c,claim,ack,b);
        assertThat(f.row(Phase.AT).stopped()).isTrue();assertThat(f.row(Phase.AT).ack()).isEqualTo(ack);
    }

    @Test void unavailableStoreDeniesEveryTransitionIndependently(){
        var s=new OwnedFailedGraceBoundaryStore(){};
        assertThatThrownBy(()->s.beforeAuthorityInTransaction(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->s.beforeFreshEntryInTransaction(null,null,null,null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->s.firstClaimInTransaction(null,null,null,null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->s.requireClaimInTransaction(null,null,null,null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->s.acknowledgeInTransaction(null,null,null,null,null,null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->s.requireAcknowledgedInTransaction(null,null,null,null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->s.recordReadyInTransaction(null,null,null,null,null,null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->s.uncertainInTransaction(null,null,null,null,null)).isInstanceOf(IllegalStateException.class);
    }

    @Test void freshEntryMayNotPrecedeTheHistoricalReadyObservation(){
        var f=new OwnedFailedGraceBoundaryInertFixture();f.base.at(f.base.savedReady().observedAt().minusNanos(1000));
        assertThatThrownBy(()->f.inspect(Phase.AT,f.base.newBudget())).isInstanceOf(RuntimeException.class);
        assertThat(f.row(Phase.AT)).isNull();
    }
}

