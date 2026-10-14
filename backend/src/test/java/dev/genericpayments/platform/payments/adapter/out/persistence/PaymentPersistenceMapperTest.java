package dev.genericpayments.platform.payments.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import static dev.genericpayments.platform.support.PostgresIntegrationTest.INITIAL_TIME;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PaymentPersistenceMapperTest {
    PaymentPersistenceMapper mapper=new PaymentPersistenceMapper(new ObjectMapper());
    PaymentId id=new PaymentId(UUID.randomUUID()); SiteId site=new SiteId(UUID.randomUUID());
    PaymentAccountId account=new PaymentAccountId(UUID.randomUUID());
    Payment sample(PaymentSource source) {
        return switch(source) {
            case SUBSCRIPTION -> Payment.subscription(id,site,UUID.randomUUID(),account,new SubscriptionBillingContext(UUID.randomUUID(),UUID.randomUUID(),SubscriptionBillingContext.Kind.RENEWAL,new ProductId(UUID.randomUUID()),new PriceId(UUID.randomUUID()),1,null,null,INITIAL_TIME,INITIAL_TIME.plusSeconds(86400),"invoice"),Money.of(700,"EUR"),TaxMode.NONE,Map.of(),INITIAL_TIME);
            case CATALOG -> Payment.catalog(id,site,account,new PriceSnapshot(new PriceId(UUID.randomUUID()),"ticket",1,Money.of(2000,"EUR"),TaxMode.NONE),Map.of(),INITIAL_TIME);
            case DYNAMIC -> Payment.dynamic(id,site,account,"order",Money.of(2000,"EUR"),TaxMode.NONE,Map.of(),INITIAL_TIME);
            case DONATION -> Payment.donation(id,site,null,account,new DonationId(UUID.randomUUID()),new DonationSnapshot(new DonationCampaignId(UUID.randomUUID()),new DonationCampaignKey("winter-fund"),0,"EUR"),Money.of(2000,"EUR"),TaxMode.NONE,Map.of(),INITIAL_TIME);
        };
    }
    PaymentJpaEntity entity(Payment p) { var e=new PaymentJpaEntity(); mapper.copy(p,e); return e; }
    @Test void allFourSourcesRoundTripAndCopyErasesPreviousShapeFields() {
        var e=new PaymentJpaEntity();
        for(PaymentSource source:new PaymentSource[]{PaymentSource.CATALOG,PaymentSource.DONATION,PaymentSource.DYNAMIC,PaymentSource.SUBSCRIPTION,PaymentSource.CATALOG}) {
            var expected=sample(source); mapper.copy(expected,e);
            assertThat(mapper.payment(e)).isEqualTo(expected);
        }
    }
    @Test void everyRequiredCatalogOrDonationColumnIsRequiredDuringReconstitution() {
        List<Consumer<PaymentJpaEntity>> priceFields=List.of(e->e.priceId=null,e->e.snapshotPriceKey=null,e->e.snapshotVersion=null,e->e.snapshotAmountMinor=null,e->e.snapshotCurrency=null,e->e.snapshotTaxMode=null);
        for(var change:priceFields) { var e=entity(sample(PaymentSource.CATALOG)); change.accept(e); assertThatThrownBy(()->mapper.payment(e)).isInstanceOf(IllegalArgumentException.class); }
        List<Consumer<PaymentJpaEntity>> donationFields=List.of(e->e.donationId=null,e->e.donationCampaignId=null,e->e.snapshotCampaignKey=null,e->e.snapshotCampaignVersion=null,e->e.snapshotCampaignCurrency=null);
        for(var change:donationFields) { var e=entity(sample(PaymentSource.DONATION)); change.accept(e); assertThatThrownBy(()->mapper.payment(e)).isInstanceOf(IllegalArgumentException.class); }
        var dynamic=entity(sample(PaymentSource.DYNAMIC)); dynamic.externalOrderId=null;
        assertThatThrownBy(()->mapper.payment(dynamic)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void mixedPriceAndDonationColumnsCannotBeSilentlyDroppedDuringReconstitution() {
        List<Consumer<PaymentJpaEntity>> priceFields=List.of(e->e.priceId=UUID.randomUUID(),e->e.snapshotPriceKey="ticket",e->e.snapshotVersion=1,e->e.snapshotAmountMinor=2000L,e->e.snapshotCurrency="EUR",e->e.snapshotTaxMode="NONE");
        for(PaymentSource source:List.of(PaymentSource.DYNAMIC,PaymentSource.DONATION)) for(var change:priceFields) {
            var e=entity(sample(source)); change.accept(e); assertThatThrownBy(()->mapper.payment(e)).isInstanceOf(IllegalArgumentException.class);
        }
        List<Consumer<PaymentJpaEntity>> donationFields=List.of(e->e.donationId=UUID.randomUUID(),e->e.donationCampaignId=UUID.randomUUID(),e->e.snapshotCampaignKey="winter-fund",e->e.snapshotCampaignVersion=0L,e->e.snapshotCampaignCurrency="EUR");
        for(PaymentSource source:List.of(PaymentSource.CATALOG,PaymentSource.DYNAMIC)) for(var change:donationFields) {
            var e=entity(sample(source)); change.accept(e); assertThatThrownBy(()->mapper.payment(e)).isInstanceOf(IllegalArgumentException.class);
        }
        for(PaymentSource source:List.of(PaymentSource.CATALOG,PaymentSource.DONATION)) {
            var e=entity(sample(source)); e.externalOrderId="mixed"; assertThatThrownBy(()->mapper.payment(e)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void invalidSnapshotValuesCurrencyAndTaxReachDomainValidation() {
        List<Consumer<PaymentJpaEntity>> changes=List.of(e->e.snapshotCampaignVersion=-1L,e->e.snapshotCampaignKey=" ",e->e.snapshotCampaignCurrency="USD",e->e.taxMode="INCLUSIVE");
        for(var change:changes) { var e=entity(sample(PaymentSource.DONATION)); change.accept(e); assertThatThrownBy(()->mapper.payment(e)).isInstanceOf(dev.genericpayments.platform.shared.domain.DomainValidationException.class); }
    }
}
