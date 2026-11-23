package dev.genericpayments.acceptance.runtime;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Pure state/early-refusal tests: no beans, durable facts, transport, runtime or application. */
class OwnedBillingFailedRenewalSandboxStateTest {
    private static final Instant NOW=Instant.parse("2026-11-15T12:00:00Z");
    private static final String[] PHASES={"INITIAL","RENEWAL_BOUNDARY","DECLINE_ARMED","FAILED_RENEWAL",
        "GRACE_LAST_SECOND","GRACE_AT","GRACE_AFTER","METHOD_PREPARED","METHOD_SELECTED",
        "METHOD_CONSUMED","METHOD_READY","RECOVERY_ACKNOWLEDGED","RECOVERED_SETTLED_LOCAL"};

    @Test void exactOrderedStagesAreConsumedBeforeTheirEffects() {
        var state=state();
        for(int n=1;n<PHASES.length;n++) {
            long ticket=state.enter(PHASES[n-1],PHASES[n]+"_RUNNING");
            assertThat(state.phase()).isEqualTo(PHASES[n]+"_RUNNING");
            assertThatThrownBy(()->state.enter("INITIAL","SECOND_RUNNING")).hasMessage("FAILED_RENEWAL_STOPPED");
            assertThat(state.phase()).isEqualTo(PHASES[n]+"_RUNNING");
            state.complete(ticket,PHASES[n]+"_RUNNING",PHASES[n]);
            assertThat(state.phase()).isEqualTo(PHASES[n]);
        }
    }

    @Test void competingRefusalCannotStealProducerCompletionOrCleanup() {
        var state=state();long owner=state.enter("INITIAL","BOUNDARY_RUNNING");
        assertThatThrownBy(()->state.enter("INITIAL","BOUNDARY_RUNNING")).hasMessage("FAILED_RENEWAL_STOPPED");
        assertThatThrownBy(()->state.complete(owner+1,"BOUNDARY_RUNNING","RENEWAL_BOUNDARY")).hasMessage("FAILED_RENEWAL_STOPPED");
        assertThat(state.fail(owner+1)).isFalse();assertThat(state.phase()).isEqualTo("BOUNDARY_RUNNING");
        state.complete(owner,"BOUNDARY_RUNNING","RENEWAL_BOUNDARY");
        assertThat(state.phase()).isEqualTo("RENEWAL_BOUNDARY");
    }

    @Test void onlyCurrentOwnerFailureBurnsItsStage() {
        var state=state();long owner=state.enter("INITIAL","BOUNDARY_RUNNING");
        assertThat(state.fail(owner)).isTrue();assertThat(state.phase()).isEqualTo("STOPPED");
        assertThat(state.fail(owner)).isFalse();
        assertThatThrownBy(()->state.complete(owner,"BOUNDARY_RUNNING","RENEWAL_BOUNDARY")).hasMessage("FAILED_RENEWAL_STOPPED");
    }

    @Test void deadlineEqualityDeniesBeforeAcquiringAnEffect() {
        var state=new OwnedBillingFailedRenewalSandboxScenario.State(Clock.fixed(NOW,ZoneOffset.UTC),NOW);
        assertThatThrownBy(()->state.enter("INITIAL","BOUNDARY_RUNNING")).hasMessage("FAILED_RENEWAL_STOPPED");
        assertThat(state.phase()).isEqualTo("STOPPED");
    }

    @Test void everyPrematureNativeStageDeniesWithoutLookingUpAComponent() {
        for(String stage:List.of("boundary","armDecline","failedRenewal","graceLastSecond","graceAt","graceAfter",
                "prepareMethod","selectMethod","confirmMethod","applyMethod","confirmRecovery","observeRecovery")) {
            var lookedUp=new ArrayList<Class<?>>();
            var scenario=new OwnedBillingFailedRenewalSandboxScenario(admission(),type->{lookedUp.add(type);throw new AssertionError("Premature bean access");},Clock.fixed(NOW,ZoneOffset.UTC),NOW.plusSeconds(120));
            assertThatThrownBy(()->invoke(scenario,stage)).hasMessage("FAILED_RENEWAL_STOPPED");
            assertThat(scenario.status().get("phase").getAsString()).isEqualTo("STOPPED");
            assertThat(lookedUp).isEmpty();
        }
    }

    @Test void recoveryManagementCheckpointBelongsToTheConsumedCallsRefusal() throws ReflectiveOperationException {
        var scenario=new OwnedBillingFailedRenewalSandboxScenario(admission(),type->{throw new AssertionError("Unexpected bean access");},
            Clock.fixed(NOW,ZoneOffset.UTC),NOW.plusSeconds(120));
        // Seed only the outer phase so the real management gate still rejects the missing initial authority.
        var field=OwnedBillingFailedRenewalSandboxScenario.class.getDeclaredField("state");
        field.setAccessible(true);
        var state=(OwnedBillingFailedRenewalSandboxScenario.State)field.get(scenario);
        long ticket=state.enter("INITIAL","METHOD_READY_RUNNING");
        state.complete(ticket,"METHOD_READY_RUNNING","METHOD_READY");

        var refusal=catchThrowableOfType(IllegalStateException.class,scenario::confirmRecovery);
        assertThat(refusal).hasMessage("FAILED_RENEWAL_STOPPED").hasNoCause().hasNoSuppressedExceptions();
        assertThat(scenario.failureStage(refusal)).isEqualTo("RECOVERY_CONFIRM_MANAGEMENT");
        assertThat(scenario.failureStage()).isEqualTo("RECOVERY_CONFIRM");
        assertThat(state.phase()).isEqualTo("STOPPED");

        var replay=catchThrowableOfType(IllegalStateException.class,scenario::confirmRecovery);
        assertThat(replay).isNotSameAs(refusal).hasMessage("FAILED_RENEWAL_STOPPED");
        assertThat(scenario.failureStage(replay)).isEqualTo("RECOVERY_CONFIRM");
    }

    private static void invoke(OwnedBillingFailedRenewalOperations scenario,String stage) {
        switch(stage) {
            case "boundary"->scenario.boundary();case "armDecline"->scenario.armDecline();case "failedRenewal"->scenario.failedRenewal();
            case "graceLastSecond"->scenario.graceLastSecond();case "graceAt"->scenario.graceAt();case "graceAfter"->scenario.graceAfter();
            case "prepareMethod"->scenario.prepareMethod();case "selectMethod"->scenario.selectMethod();case "confirmMethod"->scenario.confirmMethod();
            case "applyMethod"->scenario.applyMethod();case "confirmRecovery"->scenario.confirmRecovery();case "observeRecovery"->scenario.observeRecovery();
            default->throw new AssertionError(stage);
        }
    }
    private static OwnedBillingFailedRenewalSandboxScenario.State state(){return new OwnedBillingFailedRenewalSandboxScenario.State(Clock.fixed(NOW,ZoneOffset.UTC),NOW.plusSeconds(120));}
    private static OwnedBillingFirstPaymentRuntime.Admission admission(){return new OwnedBillingFirstPaymentRuntime.Admission(new UUID(0,1),"a".repeat(40),1700000000L,"q".repeat(43),19091,"127.0.0.1");}
}
