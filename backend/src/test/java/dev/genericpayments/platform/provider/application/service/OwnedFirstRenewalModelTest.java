package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.model.OwnedFirstRenewal.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Pure records only: no superclass, extensions, static resources, mocks, Spring or IO. */
class OwnedFirstRenewalModelTest {
    private UUID id(int n){return new UUID(0,n);}
    private final Instant at=Instant.parse("2026-11-01T12:00:00Z");
    private OwnedClockSubscriptionReceipt receipt() {
        var owner=new ClockOwner(id(1),id(2),new PaymentAccountId(id(3)),new SiteId(id(4)),new CustomerId(id(5)),
            "acct_owned","alias","2026-09-30.endive",1000,"billing_clock_"+id(1),"clock-key","a".repeat(64),at.minusSeconds(100));
        var ready=new ReadyClock(owner,id(6),"clock_owned",1000,2000,3000,at.minusSeconds(50),"b".repeat(64));
        var terms=new SubscriptionPriceTerms(new PriceId(id(7)),"monthly",1,new ProductId(id(8)),Money.of(1000,"EUR"),BillingInterval.MONTH,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
        var intent=new OwnedClockSubscription(id(9),ready,id(10),id(11),"cus_owned",id(12),id(13),"subscription:"+id(3)+":"+id(13)+":CREATE",
            "v1.paid."+"d".repeat(64),0,1,terms,"price_owned","prod_owned","c".repeat(64),at.minusSeconds(10));
        return new OwnedClockSubscriptionReceipt(intent,id(15),"sub_owned",42,"si_owned",900,1100,at.minusSeconds(5));
    }
    private Admission admission(String source,long version,String invoice,String payment) {
        return new Admission(id(16),receipt(),source,id(17),id(18),1,id(19),version,id(20),0,invoice,"inpay_owned",payment,2,1,"e".repeat(64),at);
    }
    @Test void admissionRequiresSourceAndCompletePaidIdentities() {
        assertThatThrownBy(()->admission("bad",0,"in_owned","pi_owned")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->admission("a".repeat(40),-1,"in_owned","pi_owned")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->admission("a".repeat(40),0,"pi_owned","pi_owned")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->admission("a".repeat(40),0,"in_owned","in_owned")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void stepIdentityAndPredecessorCannotBeConfused() {
        var admission=id(16);
        assertThatThrownBy(()->new Advance(id(21),admission,Step.BOUNDARY,1100,id(22),null,"billing-renewal-advance-v1:"+admission+":1","e".repeat(64),at))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Advance(id(21),admission,Step.FINALIZATION,4700,null,null,"billing-renewal-advance-v1:"+admission+":2","e".repeat(64),at))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Advance(id(21),admission,Step.BOUNDARY,1100,null,null,"wrong","e".repeat(64),at))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void readyMustBeSandboxAndReadyWhileAckMayCarryOldTime() {
        assertThatThrownBy(()->new TestClockReady("clock_owned","test_helpers.test_clock",false,"owned",1100,"advancing",2000,3000,null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TestClockReady("clock_owned","test_helpers.test_clock",true,"owned",1100,"ready",2000,3000,null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->new TestClockAck("clock_owned","test_helpers.test_clock",false,"owned",1000,"advancing",2000,3000,null)).doesNotThrowAnyException();
    }
    @Test void paidRequiresCompleteTypedProof() {
        assertThatThrownBy(()->new Paid(admission("a".repeat(40),0,"in_owned","pi_owned"),null,null,null,null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
