package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeBillingClockIdentity;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.shared.adapter.out.security.Sha256DigestAdapter;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPriceTerms;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Standalone pure model/digest tests; no superclass, extensions, static initialization or IO. */
class OwnedClockSubscriptionContractTest {
    final Instant utc=Instant.parse("2026-10-29T10:00:00Z");
    UUID id(int n){return new UUID(0,n);}
    final ClockOwner owner=new ClockOwner(id(1),id(2),new PaymentAccountId(id(3)),new SiteId(id(4)),new CustomerId(id(5)),
        "acct_owned","alias","2026-09-30.endive",1000,"billing_clock_"+id(1),"clock-key","a".repeat(64),utc.minusSeconds(100));
    final ReadyClock ready=new ReadyClock(owner,id(6),"clock_owned",1000,2000,3000,utc.minusSeconds(50),"b".repeat(64));
    final SubscriptionPriceTerms terms=new SubscriptionPriceTerms(new PriceId(id(7)),"monthly",1,new ProductId(id(8)),Money.of(1000,"EUR"),BillingInterval.MONTH,0,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
    OwnedClockSubscription intent(ReadyClock clock,UUID binding,UUID customerStep,String request,SubscriptionPriceTerms selected,String price){
        return new OwnedClockSubscription(id(9),clock,binding,customerStep,"cus_owned",id(12),id(13),"subscription:"+id(3)+":"+id(13)+":CREATE",
            request,0,1,selected,price,"prod_owned","c".repeat(64),utc);
    }
    OwnedClockSubscription intent(){return intent(ready,id(10),id(11),"v1.paid."+"d".repeat(64),terms,"price_owned");}
    String fingerprint(OwnedClockSubscription i){return StripeBillingClockIdentity.subscriptionFingerprint(new Sha256DigestAdapter(),i);}
    @Test void ownedIdentityHasAnIndependentFixedVector() {
        assertThat(fingerprint(intent())).isEqualTo("0507e2edbed2aeac49ac7af8be7150cf8a071def7135b529a93362d7ee2c0573");
    }
    @Test void originalLineageAndSelectedTermsChangeOwnedRequestIdentity() {
        var original=fingerprint(intent());
        assertThat(fingerprint(intent(ready,id(14),id(11),"v1.paid."+"d".repeat(64),terms,"price_owned"))).isNotEqualTo(original);
        assertThat(fingerprint(intent(ready,id(10),id(14),"v1.paid."+"d".repeat(64),terms,"price_owned"))).isNotEqualTo(original);
        assertThat(fingerprint(intent(ready,id(10),id(11),"v1.paid."+"e".repeat(64),terms,"price_owned"))).isNotEqualTo(original);
        assertThat(fingerprint(intent(ready,id(10),id(11),"v1.paid."+"d".repeat(64),terms,"price_other"))).isNotEqualTo(original);
        var changed=new SubscriptionPriceTerms(terms.priceId(),terms.priceKey(),1,terms.productId(),Money.of(1001,"EUR"),terms.interval(),0,terms.trialPolicy(),terms.taxMode());
        assertThat(fingerprint(intent(ready,id(10),id(11),"v1.paid."+"d".repeat(64),changed,"price_owned"))).isNotEqualTo(original);
        var otherClock=new ReadyClock(owner,id(14),"clock_other",1000,2000,3000,ready.observedAt(),ready.customerFingerprint());
        assertThat(fingerprint(intent(otherClock,id(10),id(11),"v1.paid."+"d".repeat(64),terms,"price_owned"))).isNotEqualTo(original);
    }
    @Test void originalResponseCreatedIsPositiveOpaqueAndPeriodContainsFrozenTime() {
        var r=new OwnedClockSubscriptionReceipt(intent(),id(15),"sub_owned",42,"si_owned",900,1100,utc.plusSeconds(1));
        assertThat(r.providerCreated()).isEqualTo(42);assertThat(r.periodStart()).isEqualTo(900);
        assertThatThrownBy(()->new OwnedClockSubscriptionReceipt(intent(),id(15),"sub_owned",0,"si_owned",900,1100,utc)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OwnedClockSubscriptionReceipt(intent(),id(15),"sub_owned",42,"si_owned",1001,1100,utc)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OwnedClockSubscriptionReceipt(intent(),id(15),"sub_owned",42,"si_owned",900,1000,utc)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void trialAndForeignResourceShapesCannotDescribeAnOwnedPaidIntent() {
        assertThatThrownBy(()->intent(ready,id(10),id(11),"v1.trial."+"d".repeat(64),terms,"price_owned")).isInstanceOf(IllegalArgumentException.class);
        var trial=new SubscriptionPriceTerms(terms.priceId(),terms.priceKey(),1,terms.productId(),terms.regularAmount(),terms.interval(),7,terms.trialPolicy(),terms.taxMode());
        assertThatThrownBy(()->intent(ready,id(10),id(11),"v1.paid."+"d".repeat(64),trial,"price_owned")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OwnedClockSubscriptionReceipt(intent(),id(15),"cus_wrong",42,"si_owned",900,1100,utc)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OwnedClockSubscriptionReceipt(intent(),id(15),"sub_owned",42,"price_wrong",900,1100,utc)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OwnedClockSubscriptionReceipt(intent(),id(15),"sub_owned",42,"si_owned",900,1100,utc.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
