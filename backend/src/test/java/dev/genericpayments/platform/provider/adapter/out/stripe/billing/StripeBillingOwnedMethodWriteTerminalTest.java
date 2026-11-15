package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.Origin;
import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.SuccessorOwner;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.service.OwnedRecoveryInertFixture;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StripeBillingOwnedMethodWriteTerminalTest {
    @Test void taggedWriteCleanupPassesOnlyOriginalDispatchAndOwnerToAtomicServiceBoundary(){
        var f=new OwnedRecoveryInertFixture(value->"digest:"+value,true);
        var owner=SuccessorOwner.create();var ack=f.steps.get(f.proof.step());var at=f.now();
        var dispatch=new ProviderOperation(ack.id(),ack.accountId(),ack.externalAccountId(),ack.credentialsRef(),ack.kind(),ack.resourceId(),
            ack.operationKey(),ack.fingerprint(),ProviderOperation.Status.DISPATCHING,null,at,at.plusSeconds(23*3600),
            owner.commitment(Origin.SETUP_WRITE,ack.id()),at.plusSeconds(60),1);
        var before=f.state();
        StripeBillingOwnedMethodWrite.terminate(f.service(),f.providerSteps,f.reservation(),dispatch,owner);
        assertThat(f.writeTerminalCalls()).singleElement().satisfies(args->{
            assertThat(args.get(0)).isSameAs(f.reservation());assertThat(args.get(1)).isSameAs(dispatch);assertThat(args.get(2)).isSameAs(owner);
        });
        assertThat(f.stepLocators).isEmpty();assertThat(f.trace()).doesNotContain("stop");assertThat(f.state()).isEqualTo(before);
    }
}
