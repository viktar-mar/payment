package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewalMethod.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingTimePort.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OwnedFailedRenewalMethodModelTest {
    final Instant at=Instant.parse("2026-11-01T12:00:00Z");
    final UUID reservation=UUID.randomUUID(), operation=UUID.randomUUID();
    final Sample sample=new Sample(Instant.ofEpochSecond(4700),Mode.ISOLATED);
    ReadClaim claim(Phase phase,Fence fence,Sample s,Instant deadline) {
        return new ReadClaim(UUID.randomUUID(),reservation,phase,UUID.randomUUID(),at,deadline,fence,s);
    }
    @Test void bothPhasesRequireExactSelectedOperationSequenceAndIsolatedSample() {
        for(var phase:Phase.values()) {
            assertThatCode(()->claim(phase,new Fence(2,operation,3L,null,null,5L),sample,at.plusSeconds(30))).doesNotThrowAnyException();
            assertThatThrownBy(()->claim(phase,new Fence(2,null,null,null,null,5L),sample,at.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->claim(phase,new Fence(2,operation,3L,null,null,null),sample,at.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->claim(phase,new Fence(2,operation,-1L,null,null,5L),sample,at.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->claim(phase,new Fence(2,operation,3L,null,null,5L),new Sample(sample.billingAt(),Mode.ORDINARY),at.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->claim(phase,new Fence(2,operation,3L,null,null,5L),sample,at.plusSeconds(31))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void acceptedEvidenceMustFitTheOriginalWindowInOrder() {
        var q=claim(Phase.METHOD,new Fence(2,operation,3L,null,null,5L),sample,at.plusSeconds(30));
        assertThatCode(()->q.requireAccepted(at.plusSeconds(1),at.plusSeconds(29))).doesNotThrowAnyException();
        assertThatThrownBy(()->q.requireAccepted(at.minusNanos(1),at)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->q.requireAccepted(at.plusSeconds(2),at.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->q.requireAccepted(at,at.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void completionRejectsWrongReferencesAndNegativeVersions() {
        assertThatCode(()->completion("seti_owned","pm_owned",4)).doesNotThrowAnyException();
        assertThatThrownBy(()->completion("pi_wrong","pm_owned",4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->completion("seti_owned","seti_wrong",4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->completion("seti_owned","pm_owned",-1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void reservationPinsFailureBindingAndExactReadyTwoSampleRatherThanJustIsolatedMode() {
        var h=new OwnedFailedRenewalServiceTest.Harness(true);
        var f=new dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.Failure(h.admission.id(),UUID.randomUUID(),UUID.randomUUID(),0,
            h.boundary.renewalInvoice(),"inpay_renewal","pi_renewal",h.boundary.period(),at.plusSeconds(100),2,5,at);
        var binding=h.binding;
        assertThatCode(()->new Reservation(reservation,h.admission,f,operation,"v1.setup."+"a".repeat(64),0,binding,sample,at)).doesNotThrowAnyException();
        assertThatThrownBy(()->new Reservation(reservation,h.admission,f,operation,"v1.setup."+"a".repeat(64),1,binding,sample,at)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Reservation(reservation,h.admission,f,operation,"v1.setup."+"a".repeat(64),0,binding,new Sample(sample.billingAt().plusSeconds(1),Mode.ISOLATED),at)).isInstanceOf(IllegalArgumentException.class);
        var foreign=new dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding(UUID.randomUUID(),binding.accountId(),binding.siteId(),binding.customerId(),binding.providerReference(),binding.creationOperationId(),binding.version());
        assertThatThrownBy(()->new Reservation(reservation,h.admission,f,operation,"v1.setup."+"a".repeat(64),0,foreign,sample,at)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Reservation(reservation,h.admission,f,operation,"v1.setup."+"a".repeat(64),0,binding,sample,at.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
    Completion completion(String setup,String method,long version) {
        return new Completion(reservation,UUID.randomUUID(),operation,version,UUID.randomUUID(),2,UUID.randomUUID(),2,setup,method,2,6,at);
    }
}
