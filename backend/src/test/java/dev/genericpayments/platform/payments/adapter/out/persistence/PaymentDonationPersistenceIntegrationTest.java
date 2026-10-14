package dev.genericpayments.platform.payments.adapter.out.persistence;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.*;
import dev.genericpayments.platform.donations.application.port.out.*;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentDonationPersistenceIntegrationTest extends PaymentDatabaseFixture {
    @Autowired DonationCampaignStore campaigns;
    @Autowired DonationStore donations;
    @Autowired SavePaymentPort save;
    @Autowired LoadPaymentPort load;
    @Test void guestAndCustomerDonationPaymentsRoundTripAndRetainHistoricalCampaignContext() {
        var campaign=DonationCampaign.draft(new DonationCampaignId(UUID.randomUUID()),new SiteId(site),new DonationCampaignKey("winter-fund"),"Winter",null,"EUR",List.of(2000L),100,10000,true,true,INITIAL_TIME);
        campaigns.insert(campaign); var active=campaign.activate(INITIAL_TIME); campaigns.update(active,0);
        UUID customer=UUID.randomUUID();
        jdbc.update("insert into customers(id,site_id,status,created_at,updated_at) values (?,?,'ACTIVE',now(),now())",customer,site);
        List<Payment> original=new ArrayList<>();
        for(UUID owner:new UUID[]{null,customer}) {
            var donation=Donation.create(new DonationId(UUID.randomUUID()),new SiteId(site),campaign.id(),null,INITIAL_TIME); donations.save(donation);
            var snapshot=new DonationSnapshot(campaign.id(),campaign.campaignKey(),1,"EUR");
            Payment payment=Payment.donation(new PaymentId(UUID.randomUUID()),new SiteId(site),owner,new PaymentAccountId(account),donation.id(),snapshot,Money.of(2000,"EUR"),TaxMode.NONE,Map.of(),INITIAL_TIME);
            assertThatCode(()->save.save(payment)).doesNotThrowAnyException();
            assertThat(load.findById(new SiteId(site),payment.id())).contains(payment);
            assertThat(load.findById(new SiteId(UUID.randomUUID()),payment.id())).isEmpty();
            original.add(payment);
        }
        campaigns.update(active.updateConfiguration("Changed",null,"EUR",List.of(3000L),100,10000,true,true,INITIAL_TIME.plusSeconds(1)),1);
        for(Payment payment:original) {
            var stored=load.findById(new SiteId(site),payment.id()).orElseThrow();
            assertThat(stored.donationSnapshot().version()).isEqualTo(1);
            assertThat(stored.money().minorUnits()).isEqualTo(2000);
            assertThat(save.save(stored.processing(INITIAL_TIME)).donationSnapshot()).isEqualTo(payment.donationSnapshot());
        }
    }
}
