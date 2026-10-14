package dev.genericpayments.platform.donations.adapter.out.integration;

import dev.genericpayments.platform.catalog.domain.Money;
import dev.genericpayments.platform.donations.application.port.out.DonationPaymentCreationPort;
import dev.genericpayments.platform.donations.domain.*;
import dev.genericpayments.platform.payments.application.port.in.*;
import dev.genericpayments.platform.payments.domain.DonationSnapshot;
import dev.genericpayments.platform.sites.domain.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class PaymentDonationCreationAdapter implements DonationPaymentCreationPort {
    private final CreateDonationPaymentUseCase payments;
    public PaymentDonationCreationAdapter(CreateDonationPaymentUseCase payments) { this.payments=payments; }
    @Override public void preflight(Site site) { payments.preflight(site); }
    @Override public Checkout create(Site site,DonationId donationId,DonationCampaignSnapshot snapshot,Money amount,UUID customerId,
        UUID checkoutId,URI origin,String requestId,String idempotencyKey) {
        return mapped(payments.create(new CreateDonationPaymentCommand(site,donationId,new DonationSnapshot(snapshot.campaignId(),
            snapshot.campaignKey(),snapshot.version(),snapshot.currency()),amount,customerId,checkoutId,origin,requestId,idempotencyKey)));
    }
    @Override public Checkout load(SiteId site,UUID checkoutId,String token) { return mapped(payments.load(site,checkoutId,token)); }
    private Checkout mapped(CreateDonationPaymentUseCase.Result result) {
        var snapshot=result.payment().donationSnapshot();
        return new Checkout(result.checkout(),result.payment().donationId(),new DonationCampaignSnapshot(snapshot.campaignId(),
            snapshot.campaignKey(),snapshot.version(),snapshot.currency()),result.payment().customerId());
    }
}
