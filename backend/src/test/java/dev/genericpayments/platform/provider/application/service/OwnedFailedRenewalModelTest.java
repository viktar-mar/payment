package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.OwnedFailedRenewal.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.domain.*;
import dev.genericpayments.platform.subscriptions.application.port.in.ApplySubscriptionSnapshotUseCase.Fence;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OwnedFailedRenewalModelTest {
    private final UUID admission = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final Instant now = Instant.parse("2026-11-01T12:00:00Z");
    private ReadClaim claim(Phase phase, Fence fence, Instant deadline) {
        return new ReadClaim(UUID.randomUUID(), admission, phase, UUID.randomUUID(), now, deadline, fence);
    }
    @Test void readClaimHasFixedWindowAndPhaseSpecificOperationFence() {
        var noOperation = new Fence(3, null, null, null, null, 5L);
        var operation = new Fence(3, UUID.randomUUID(), 2L, null, null, 5L);
        assertThatCode(() -> claim(Phase.FAILED, noOperation, now.plusSeconds(30))).doesNotThrowAnyException();
        assertThatThrownBy(() -> claim(Phase.FAILED, noOperation, now.plusSeconds(31))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claim(Phase.FAILED, operation, now.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claim(Phase.RECOVERED, noOperation, now.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> claim(Phase.RECOVERED, operation, now.plusSeconds(30))).doesNotThrowAnyException();
    }
    @Test void observationAndAcceptanceMustBothFitTheSameClaimWindow() {
        var read = claim(Phase.FAILED, new Fence(3, null, null, null, null, 5L), now.plusSeconds(30));
        assertThatCode(() -> read.requireAccepted(now.plusSeconds(1), now.plusSeconds(29))).doesNotThrowAnyException();
        assertThatThrownBy(() -> read.requireAccepted(now.minusNanos(1), now.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> read.requireAccepted(now.plusSeconds(1), now.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> read.requireAccepted(now.plusSeconds(2), now.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void failureReceiptRequiresValidBillVersionAndProviderIdentities() {
        var period = new dev.genericpayments.platform.subscriptions.domain.Subscription.Period(now, now.plusSeconds(60));
        assertThatThrownBy(() -> new Failure(admission, UUID.randomUUID(), UUID.randomUUID(), -1,
                "in_renewal", "inpay_existing", "pi_existing", period, now.plusSeconds(50), 4, 6, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Failure(admission, UUID.randomUUID(), UUID.randomUUID(), 0,
                "in_renewal", "inpay_existing", "in_wrong", period, now.plusSeconds(50), 4, 6, now))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void admissionCannotReuseInitialInvoiceOrForeignItemEvenWithReadyTwo() {
        var upstream=upstream();
        var first=ready(upstream,OwnedFirstRenewal.Step.BOUNDARY,null,null,1100);
        var badItem=new OwnedFirstRenewal.Boundary(id(30),upstream.id(),first,"si_foreign","in_renewal",new Subscription.Period(Instant.ofEpochSecond(1100),Instant.ofEpochSecond(10000)),"e".repeat(64),now);
        var finalReady=ready(upstream,OwnedFirstRenewal.Step.FINALIZATION,first.id(),badItem.id(),4700);
        assertThatThrownBy(()->new Admission(id(40),upstream,finalReady,badItem,upstream.sourceSha(),now)).isInstanceOf(IllegalArgumentException.class);
        var reused=new OwnedFirstRenewal.Boundary(id(30),upstream.id(),first,"si_owned",upstream.invoice(),badItem.period(),"e".repeat(64),now);
        assertThatThrownBy(()->new Admission(id(40),upstream,finalReady,reused,upstream.sourceSha(),now)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void admissionRequiresMatchingSourceAndFixedFinalBoundary() {
        var upstream=upstream();var first=ready(upstream,OwnedFirstRenewal.Step.BOUNDARY,null,null,1100);
        var boundary=new OwnedFirstRenewal.Boundary(id(30),upstream.id(),first,"si_owned","in_renewal",new Subscription.Period(Instant.ofEpochSecond(1100),Instant.ofEpochSecond(10000)),"e".repeat(64),now);
        var finalReady=ready(upstream,OwnedFirstRenewal.Step.FINALIZATION,first.id(),boundary.id(),4700);
        assertThatCode(()->new Admission(id(40),upstream,finalReady,boundary,upstream.sourceSha(),now)).doesNotThrowAnyException();
        assertThatThrownBy(()->new Admission(id(40),upstream,finalReady,boundary,"f".repeat(40),now)).isInstanceOf(IllegalArgumentException.class);
        var wrongTime=ready(upstream,OwnedFirstRenewal.Step.FINALIZATION,first.id(),boundary.id(),4800);
        assertThatThrownBy(()->new Admission(id(40),upstream,wrongTime,boundary,upstream.sourceSha(),now)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void readClaimsRejectMissingSequenceNegativeCountersAndHalfAnOperation() {
        assertThatThrownBy(()->claim(Phase.FAILED,new Fence(3,null,null,null,null,null),now.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->claim(Phase.FAILED,new Fence(-1,null,null,null,null,5L),now.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->claim(Phase.FAILED,new Fence(3,null,null,null,null,-1L),now.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->claim(Phase.RECOVERED,new Fence(3,UUID.randomUUID(),null,null,null,5L),now.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->claim(Phase.RECOVERED,new Fence(3,UUID.randomUUID(),-1L,null,null,5L),now.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
    }
    private UUID id(long n) {return new UUID(0,n);}
    private OwnedFirstRenewal.Admission upstream() {
        var owner=new ClockOwner(id(1),id(2),new PaymentAccountId(id(3)),new SiteId(id(4)),new CustomerId(id(5)),"acct_owned","alias","2026-09-30.endive",1000,"billing_clock_"+id(1),"clock-key","a".repeat(64),now.minusSeconds(100));
        var clock=new ReadyClock(owner,id(6),"clock_owned",1000,2000,3000,now.minusSeconds(50),"b".repeat(64));
        var terms=new SubscriptionPriceTerms(new PriceId(id(7)),"monthly",1,new ProductId(id(8)),Money.of(1000,"EUR"),BillingInterval.MONTH,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
        var intent=new OwnedClockSubscription(id(9),clock,id(10),id(11),"cus_owned",id(12),id(13),"subscription:"+id(3)+":"+id(13)+":CREATE","v1.paid."+"d".repeat(64),0,1,terms,"price_owned","prod_owned","c".repeat(64),now.minusSeconds(10));
        var receipt=new OwnedClockSubscriptionReceipt(intent,id(15),"sub_owned",42,"si_owned",900,1100,now.minusSeconds(5));
        return new OwnedFirstRenewal.Admission(id(16),receipt,"a".repeat(40),id(17),id(18),1,id(19),0,id(20),0,"in_initial","inpay_initial","pi_initial",2,1,"e".repeat(64),now);
    }
    private OwnedFirstRenewal.LaterReady ready(OwnedFirstRenewal.Admission upstream,OwnedFirstRenewal.Step step,UUID previous,UUID boundary,long target) {
        var advance=new OwnedFirstRenewal.Advance(id(20+step.number()),upstream.id(),step,target,previous,boundary,"billing-renewal-advance-v1:"+upstream.id()+":"+step.number(),"e".repeat(64),now);
        var operation=new ProviderOperation(id(50+step.number()),upstream.receipt().intent().clock().owner().account(),"acct_owned","alias",ProviderOperation.Kind.TEST_CLOCK_ADVANCE,advance.id(),advance.operationKey(),advance.fingerprint(),ProviderOperation.Status.ACKNOWLEDGED,"clock_owned",now,null,null,null,1);
        var ack=new OwnedFirstRenewal.Ack(advance,operation,"owner",now.plusSeconds(60),new OwnedFirstRenewal.TestClockAck("clock_owned","test_helpers.test_clock",false,"owned",target,"ready",2000,3000,null),now);
        return new OwnedFirstRenewal.LaterReady(id(60+step.number()),upstream.id(),ack,new OwnedFirstRenewal.TestClockReady("clock_owned","test_helpers.test_clock",false,"owned",target,"ready",2000,3000,null),now);
    }
}
